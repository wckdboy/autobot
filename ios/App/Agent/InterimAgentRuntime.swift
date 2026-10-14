import Foundation
import Observation

/**
 Fallback [AgentRuntime] for builds without `AutobotShared.xcframework`: plain streaming chat
 with the session log, titles, images, reasoning, cancellation and follow-up queueing — but **no
 tools, approvals, plans, retries or compaction**. It writes the same `SessionEvent` log and
 renders the same [ChatSessionState] as the Kotlin runtime, so sessions carry over.
 */
@MainActor
@Observable
final class InterimAgentRuntime: AgentRuntime {
    /// Images sent per call; vision encoders are slow on a phone and cost context.
    private static let maxImages = 4

    private(set) var running: Set<String> = []
    let supportsTools = false

    @ObservationIgnored private var sessions: [String: AgentSession] = [:]
    @ObservationIgnored private var logs: [String: [LogEntry]] = [:]
    @ObservationIgnored private var tasks: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var queued: [String: [(text: String, images: [String])]] = [:]
    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private let conversations: ConversationRepository
    @ObservationIgnored private let providers: ProviderRepository
    @ObservationIgnored private let factory: ProviderFactory
    @ObservationIgnored private let attachments: AttachmentRepository

    init(store: SessionStore, conversations: ConversationRepository, providers: ProviderRepository, factory: ProviderFactory, attachments: AttachmentRepository) {
        self.store = store
        self.conversations = conversations
        self.providers = providers
        self.factory = factory
        self.attachments = attachments
    }

    func session(_ id: String) -> AgentSession {
        if let s = sessions[id] { return s }
        let s = AgentSession(id: id)
        logs[id] = store.load(id)
        for entry in logs[id] ?? [] {
            switch entry.event {
            case .permissionPresetChanged(let preset): s.state.permission = PermissionPreset.fromLabel(preset)?.label ?? s.state.permission
            case .agentSettingsChanged(let tools): s.state.toolsEnabled = tools
            default: break
            }
        }
        sessions[id] = s
        project(s)
        return s
    }

    func send(_ id: String, text: String, images: [String]) async {
        let prompt = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty || !images.isEmpty else { return }
        conversations.titleFromPrompt(id, prompt)
        if tasks[id] != nil {
            queued[id, default: []].append((prompt, images))
            return
        }
        startTurn(id, prompt, images)
    }

    func cancel(_ id: String) {
        queued[id] = nil
        tasks[id]?.cancel()
    }

    func answerApproval(callId: String, outcome: ApprovalOutcome) {}

    func answerQuestion(callId: String, answers: [String]?) {}

    func setToolsEnabled(_ id: String, _ enabled: Bool) {
        let s = session(id)
        guard s.state.toolsEnabled != enabled else { return }
        s.state.toolsEnabled = enabled
        append(s, [.agentSettingsChanged(toolsEnabled: enabled)])
    }

    func setPermission(_ id: String, _ preset: PermissionPreset) {
        let s = session(id)
        guard s.state.permission != preset.label else { return }
        s.state.permission = preset.label
        append(s, [.permissionPresetChanged(preset: preset.label)])
    }

    func setRoute(_ id: String, providerId: String, model: String) {
        guard var c = conversations.get(id) else { return }
        c.providerId = providerId
        c.model = model
        conversations.update(c)
        sessions[id].map(project)
    }

    func compact(_ id: String) {
        append(session(id), [.commandRun(name: "compact", input: nil),
                             .compactionEnd(replacedEntries: 0, summaryChars: 0, error: "needs the shared agent runtime")])
    }

    func discard(_ id: String) {
        tasks[id]?.cancel()
        tasks[id] = nil
        queued[id] = nil
        sessions[id] = nil
        logs[id] = nil
        running.remove(id)
        store.delete(id)
    }

    func shutdown() {
        tasks.values.forEach { $0.cancel() }
        tasks.removeAll()
        queued.removeAll()
        sessions.removeAll()
        logs.removeAll()
        running.removeAll()
        store.clearIncognito()
    }

    // MARK: Turn

    private func append(_ s: AgentSession, _ events: [SessionEvent]) {
        let log = logs[s.id] ?? []
        let base = (log.last?.seq ?? -1) + 1
        let now = nowMillis()
        let entries = events.enumerated().map { LogEntry(seq: base + Int64($0.offset), time: now, event: $0.element) }
        try? store.append(s.id, entries)
        logs[s.id] = log + entries
        project(s)
    }

    private func project(_ s: AgentSession) {
        let conversation = conversations.get(s.id)
        let provider = conversation?.providerId.flatMap(providers.get) ?? providers.providers.first
        s.state.projection = projectSession(logs[s.id] ?? [], pendingApprovals: [], running: []) { _, args in String(args.prefix(80)) }
        s.state.provider = provider?.id ?? ""
        s.state.model = conversation?.model.flatMap { $0.isEmpty ? nil : $0 } ?? provider?.defaultModel ?? ""
        s.state.running = tasks[s.id] != nil
    }

    private func startTurn(_ id: String, _ prompt: String, _ images: [String]) {
        let s = session(id)
        let turn = (logs[id] ?? []).reduce(0) { n, e in if case .turnStart = e.event { n + 1 } else { n } }
        append(s, [.turnStart(turn: turn), .userMessage(text: prompt, images: images), .stepStart(turn: turn, step: 0)])

        let conversation = conversations.get(id)
        guard let provider = (conversation?.providerId).flatMap(providers.get) ?? providers.providers.first else {
            append(s, [.stepEnd(turn: turn, step: 0), .turnEnd(turn: turn, reason: .error, detail: "No model provider — download a model or add a provider")])
            return
        }
        let model = conversation?.model.flatMap { $0.isEmpty ? nil : $0 } ?? provider.defaultModel
        let info = ProviderFactory.modelInfo(provider.kind, model)
        append(s, [.requestContext(provider: provider.id, model: model, contextWindow: info.contextWindow)])

        let (messages, plainFiles) = deriveMessages(logs[id] ?? [])
        let request = ChatRequest(model: model, messages: messages, maxTokens: info.defaultMaxTokens)
        let chat = factory.create(provider)
        let started = Date()

        s.state.live = LiveState(text: "", reasoning: "", toolNames: [], tokensPerSecond: nil)
        running.insert(id)
        tasks[id] = Task { [weak self] in
            var text = "", reasoning = ""
            var usage: TokenUsage?
            var finish: String?
            var failure: String?
            for await event in chat.streamChat(request) {
                switch event {
                case .contentDelta(let t): text += t
                case .reasoningDelta(let t): reasoning += t
                case .toolCallDelta(_, _, let name, _):
                    if let name, s.state.live?.toolNames.contains(name) == false { s.state.live?.toolNames.append(name) }
                case .usage(let p, let c, let t): usage = TokenUsage(inputTokens: p, outputTokens: c, totalTokens: t)
                case .finish(let reason): finish = reason
                case .done: break
                case .error(let kind, let message, _): failure = kind == .blocked ? "blocked: \(message)" : message
                }
                let seconds = Date().timeIntervalSince(started)
                let tokens = Double(text.count + reasoning.count) / 4
                s.state.live?.text = text
                s.state.live?.reasoning = reasoning
                s.state.live?.tokensPerSecond = seconds > 0.5 && tokens > 0 ? tokens / seconds : nil
            }
            plainFiles.forEach { try? FileManager.default.removeItem(at: $0) }
            self?.finishTurn(s, turn: turn, model: model, text: text, reasoning: reasoning, usage: usage, finish: finish, failure: failure, cancelled: Task.isCancelled)
        }
        s.state.running = true
    }

    private func finishTurn(_ s: AgentSession, turn: Int, model: String, text: String, reasoning: String, usage: TokenUsage?, finish: String?, failure: String?, cancelled: Bool) {
        var events: [SessionEvent] = []
        if !text.isEmpty || !reasoning.isEmpty {
            events.append(.assistantMessage(.init(turn: turn, step: 0, text: text, reasoning: reasoning.isEmpty ? nil : reasoning, usage: usage, model: model, interrupted: cancelled)))
        }
        events.append(.stepEnd(turn: turn, step: 0))
        let reason: TurnEndReason
        var detail: String?
        if cancelled {
            reason = .aborted
        } else if let failure {
            let blocked = failure.hasPrefix("blocked: ")
            reason = blocked ? .blocked : .error
            detail = blocked ? String(failure.dropFirst(9)) : failure
        } else if finish == "length" {
            reason = .maxTokens
        } else {
            reason = .completed
        }
        events.append(.turnEnd(turn: turn, reason: reason, detail: detail))
        tasks[s.id] = nil
        running.remove(s.id)
        s.state.live = nil
        append(s, events)
        conversations.touch(s.id)
        if !cancelled, var next = queued[s.id], !next.isEmpty {
            let item = next.removeFirst()
            queued[s.id] = next
            startTurn(s.id, item.text, item.images)
        }
    }

    /// Request history derived from the log (prompts, summaries, assistant text). Only the newest
    /// [maxImages] attached images are decrypted into short-lived files for this call.
    private func deriveMessages(_ entries: [LogEntry]) -> ([ChatMessage], [URL]) {
        var replaced = Set<Int64>()
        for entry in entries {
            if case .replace(let start, let end) = entry.surface, start <= end { (start ... end).forEach { replaced.insert($0) } }
        }
        let allImages = entries.flatMap { e -> [String] in
            if case .userMessage(_, .prompt, _, let images) = e.event { return images }
            return []
        }
        let keep = Set(allImages.suffix(Self.maxImages))
        var messages: [ChatMessage] = []
        var files: [URL] = []
        for entry in entries where !replaced.contains(entry.seq) {
            switch entry.event {
            case .userMessage(let text, .prompt, _, let images):
                let urls = images.filter(keep.contains).compactMap(attachments.openPlain)
                files += urls
                let dropped = images.count - urls.count
                messages.append(ChatMessage(role: .user, content: text + (dropped > 0 ? "\n[\(dropped) earlier image(s) not shown]" : ""), images: urls))
            case .userMessage(let text, .summary, _, _):
                messages.append(ChatMessage(role: .user, content: text))
            case .assistantMessage(let m) where !m.text.isEmpty:
                messages.append(ChatMessage(role: .assistant, content: m.text))
            default:
                break
            }
        }
        return (messages, files)
    }
}

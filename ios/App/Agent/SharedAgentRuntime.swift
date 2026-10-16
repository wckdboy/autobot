#if canImport(AutobotShared)
import AutobotShared
import Foundation
import Observation
import Synchronization

/**
 The agent runtime Android runs (`AgentHost`, the Kotlin port of DeepSeek Harness: tools,
 permission gate, approvals, plans, questions, retries, compaction, skills, `/workspace`), from
 `AutobotShared.xcframework`. Kotlin owns the loop and the transcript projection; Swift supplies
 HTTP ([URLSessionTransport], behind the kill switch), the on-device engine ([LocalEngineHost]),
 encrypted session logs ([SessionStoreHost]), attachments and the provider list, and renders the
 `SessionState` JSON the bridge streams back.
 */
@MainActor
@Observable
final class SharedAgentRuntime: AgentRuntime {
    private(set) var running: Set<String> = []
    /// Connection state of the configured MCP servers, by server id.
    private(set) var mcpStatus: [String: McpServerStatus] = [:]
    let supportsTools = true

    @ObservationIgnored private let bridge: AgentBridge
    @ObservationIgnored private let sessionStore: SessionStoreHost
    @ObservationIgnored private var sessions: [String: AgentSession] = [:]
    @ObservationIgnored private var handles: [String: any AutobotShared.Cancellable] = [:]
    @ObservationIgnored private var sessionListeners: [String: SessionStreamBox] = [:]
    /// Sessions whose state stream was stopped by [close]; [session] reopens it.
    @ObservationIgnored private var closed: Set<String> = []
    /// Closed sessions, least recently closed first. Beyond [maxIdle], idle ones (no turn
    /// running) are dropped: their objects and transcripts are not kept for every chat ever opened.
    @ObservationIgnored private var idleOrder: [String] = []
    static let maxIdle = 4
    /// Bridge calls waiting for a callback, failed by [shutdown] (the bridge never answers then).
    @ObservationIgnored private var waiting: [UUID: @MainActor () -> Void] = [:]
    @ObservationIgnored private var isShutDown = false
    @ObservationIgnored private var runningHandle: (any AutobotShared.Cancellable)?
    @ObservationIgnored private var mcpHandle: (any AutobotShared.Cancellable)?
    @ObservationIgnored private let conversations: ConversationRepository
    @ObservationIgnored private let providers: ProviderRepository
    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private let listeners = NSMutableArray()

    init(
        conversations: ConversationRepository,
        providers: ProviderRepository,
        store: SessionStore,
        clients: HttpClientFactory,
        llm: LocalLlm,
        attachments: AttachmentRepository,
        secrets: SecretStore,
        snapshots: HostSnapshots,
    ) {
        self.conversations = conversations
        self.providers = providers
        self.store = store
        let sessionStore = SessionStoreHost(store: store)
        self.sessionStore = sessionStore
        bridge = AgentBridge(
            workspaceDir: AppPaths.workspace.path,
            providers: ProviderDirectoryHost(snapshots: snapshots, secrets: secrets),
            transport: URLSessionTransport(clients: clients),
            local: LocalEngineHost(snapshots: snapshots, llm: llm),
            store: sessionStore,
            images: ImageStoreHost(attachments: attachments),
            mcpTokens: McpTokenSourceHost(snapshots: snapshots, secrets: secrets),
            audit: clients.auditLog.shared,
            journalDir: AppPaths.workspaceUndo.path,
        )
        let listener = RunningListenerBox { [weak self] ids in
            guard let self else { return }
            let next = Set(ids)
            if next != running { running = next }
            // A closed session that just finished can go now.
            evictIdle()
        }
        listeners.add(listener)
        runningHandle = bridge.observeRunning(listener: listener)
        let mcpListener = McpStatusListenerBox { [weak self] list in
            self?.mcpStatus = Dictionary(list.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        }
        listeners.add(mcpListener)
        mcpHandle = bridge.observeMcpStatus(listener: mcpListener)
        bridge.setThinkingDefaults(text: UserDefaults.standard.string(forKey: Self.thinkingDefaultsKey))
    }

    // MARK: Think switch

    /// Per-model Think defaults (`ThinkingDefaults` text): a non-secret preference.
    static let thinkingDefaultsKey = "thinking_defaults"

    func setThinking(_ id: String, _ choice: ThinkingChoice) {
        _ = session(id)
        bridge.setThinking(sessionId: id, mode: choice.mode, budget: choice.budget.map { KotlinInt(int: Int32($0)) })
    }

    func thinkingDefault(provider: String, model: String) -> ThinkingChoice? {
        let text = UserDefaults.standard.string(forKey: Self.thinkingDefaultsKey)
        guard let s = ThinkingDefaults.shared.get(text: text, provider: provider, model: model) else { return nil }
        return ThinkingChoice(mode: s.mode.name, budget: s.budget.map { Int(truncating: $0) })
    }

    func setThinkingDefault(provider: String, model: String, _ choice: ThinkingChoice?) {
        let old = UserDefaults.standard.string(forKey: Self.thinkingDefaultsKey)
        let setting = choice.map { c in
            ThinkingSetting(mode: Self.mode(c.mode), budget: c.budget.map { KotlinInt(int: Int32($0)) })
        }
        let text = ThinkingDefaults.shared.with(text: old, provider: provider, model: model, setting: setting)
        UserDefaults.standard.set(text, forKey: Self.thinkingDefaultsKey)
        bridge.setThinkingDefaults(text: text)
    }

    private static func mode(_ name: String) -> ThinkingMode {
        switch name {
        case "OFF": .off
        case "ON": .on
        default: .auto_
        }
    }

    func preview(_ id: String, state: ChatSessionState) {
        let s = sessions[id] ?? AgentSession(id: id)
        s.state = state
        sessions[id] = s
    }

    func existingSession(_ id: String) -> AgentSession? { sessions[id] }

    func session(_ id: String) -> AgentSession {
        if let s = sessions[id] {
            if closed.remove(id) != nil {
                idleOrder.removeAll { $0 == id }
                open(s)
            }
            return s
        }
        let s = AgentSession(id: id)
        sessions[id] = s
        open(s)
        return s
    }

    /// Streams the bridge's state for [s] (opening its agent on first use).
    private func open(_ s: AgentSession) {
        let id = s.id
        conversations.repoint(providers: providers.providers, only: id)
        let conversation = conversations.get(id)
        let provider = conversation?.providerId.flatMap(providers.get) ?? providers.providers.first
        let model = conversation?.model.flatMap { $0.isEmpty ? nil : $0 } ?? provider?.defaultModel ?? ""
        // The efficient stream: the transcript only when it changes, small frames with text
        // deltas otherwise. The handle holds the session open on the bridge until [close].
        nonisolated(unsafe) let bridge = bridge
        let listener = SessionStreamBox(resync: { bridge.resync(sessionId: id) }) { [weak s] update in s?.apply(update) }
        sessionListeners[id] = listener
        handles[id] = bridge.openStream(sessionId: id, providerId: provider?.id ?? "", model: model, listener: listener, textDeltas: true)
    }

    func close(_ id: String) {
        guard let handle = handles.removeValue(forKey: id) else { return }
        handle.cancel()
        sessionListeners[id] = nil
        closed.insert(id)
        idleOrder.removeAll { $0 == id }
        idleOrder.append(id)
        evictIdle()
    }

    /// Drops the oldest closed sessions beyond [maxIdle] that have no turn running.
    private func evictIdle() {
        var excess = idleOrder.count - Self.maxIdle
        guard excess > 0 else { return }
        for id in idleOrder where excess > 0 && !running.contains(id) {
            forget(id)
            excess -= 1
        }
    }

    /// Forgets a closed session's object (the next [session] call opens it afresh).
    private func forget(_ id: String) {
        sessions[id] = nil
        closed.remove(id)
        idleOrder.removeAll { $0 == id }
    }

    /// Sessions held in memory (tests).
    var heldSessionIds: Set<String> { Set(sessions.keys) }

    func send(_ id: String, text: String, images: [String]) async {
        let prompt = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty || !images.isEmpty else { return }
        _ = session(id)
        conversations.titleFromPrompt(id, prompt)
        bridge.send(sessionId: id, text: prompt, images: images)
    }

    func cancel(_ id: String) { bridge.cancel(sessionId: id) }

    func answerApproval(_ id: String, callId: String, outcome: ApprovalOutcome) {
        bridge.answerApproval(sessionId: id, callId: callId, outcome: SharedMapping.outcome(outcome))
    }

    func answerQuestion(_ id: String, callId: String, answers: [String]?) { bridge.answerQuestion(sessionId: id, callId: callId, answers: answers) }

    /// App lifecycle: in the background no MCP list-change stream stays open or reconnects.
    func setForeground(_ foreground: Bool) { bridge.setForeground(foreground: foreground) }

    /// Providers, their model lists or the network policy changed: drop the cached model adapters.
    func invalidateProviders() { bridge.invalidateProviders() }

    func setToolsEnabled(_ id: String, _ enabled: Bool) {
        _ = session(id)
        bridge.setToolsEnabled(sessionId: id, enabled: enabled)
    }

    func setPermission(_ id: String, _ preset: PermissionPreset) {
        _ = session(id)
        bridge.setPermission(sessionId: id, preset: SharedMapping.preset(preset))
    }

    func setRoute(_ id: String, providerId: String, model: String) {
        if var c = conversations.get(id) {
            c.providerId = providerId
            c.model = model
            conversations.update(c)
        }
        _ = session(id)
        bridge.setRoute(sessionId: id, providerId: providerId, model: model)
    }

    func providersChanged(_ list: [Provider]) {
        for c in conversations.repoint(providers: list) where sessions[c.id] != nil {
            bridge.setRoute(sessionId: c.id, providerId: c.providerId ?? "", model: c.model ?? "")
        }
    }

    func compact(_ id: String) {
        _ = session(id)
        bridge.compact(sessionId: id)
    }

    func undoStep(_ id: String, callId: String) {
        _ = session(id)
        bridge.undoStep(sessionId: id, callId: callId)
    }

    func undoTurn(_ id: String, turn: Int) {
        _ = session(id)
        bridge.undoTurn(sessionId: id, turn: Int32(turn))
    }

    func redo(_ id: String, revertId: String) {
        _ = session(id)
        bridge.redo(sessionId: id, revertId: revertId)
    }

    func discard(_ id: String) {
        handles.removeValue(forKey: id)?.cancel()
        sessionListeners[id] = nil
        forget(id)
        bridge.discard(sessionId: id)
        sessionStore.forget(sessionId: id)
    }

    func shutdown() {
        isShutDown = true
        handles.values.forEach { $0.cancel() }
        handles.removeAll()
        sessionListeners.removeAll()
        closed.removeAll()
        idleOrder.removeAll()
        runningHandle?.cancel()
        mcpHandle?.cancel()
        sessions.removeAll()
        // Kotlin calls this on its own thread: a closure formed here would be main-actor isolated
        // and trap there (Swift 6 checks isolation at run time), killing a panic wipe midway.
        bridge.shutdown { @Sendable in }
        store.clearIncognito()
        // The bridge's scope is cancelled: callbacks still outstanding never come.
        let pending = waiting.values
        waiting.removeAll()
        pending.forEach { $0() }
    }

    /// How long a "Test" or model-list fetch waits before saying the server does not answer.
    static var testTimeout: Duration = .seconds(30)

    /**
     Calls the bridge with a callback and waits for it, or returns [stopped] at once once the
     bridge is shut down (now or while waiting), or [late] after [testTimeout]. Exactly one of
     them resumes the caller.
     */
    private func call<T: Sendable>(stopped: T, late: T, _ start: (_ done: @escaping @Sendable (T) -> Void) -> Void) async -> T {
        if isShutDown { return stopped }
        let id = UUID()
        let once = ResumeOnce<T>()
        let limit = Self.testTimeout
        let timer = Task { try? await Task.sleep(for: limit); if !Task.isCancelled { once.resume(late) } }
        let value = await withCheckedContinuation { (continuation: CheckedContinuation<T, Never>) in
            once.set(continuation)
            waiting[id] = { once.resume(stopped) }
            start { once.resume($0) }
        }
        timer.cancel()
        waiting[id] = nil
        return value
    }

    /// Provider "test connection" through the shared codec and the app's transport: success, a
    /// summary (model count and first ids, or the error) and the model list to cache.
    func testConnection(_ spec: ProviderSpec) async -> (ok: Bool, message: String, modelsJson: String?) {
        let result = await call(stopped: Reply3(ok: false, message: Self.stoppedMessage, json: nil),
                                late: Reply3(ok: false, message: noAnswerMessage(spec.baseUrl, after: Self.testTimeout), json: nil)) { done in
            bridge.testConnection(spec: spec) { @Sendable ok, message, json in done(Reply3(ok: ok.boolValue, message: message, json: json)) }
        }
        return (result.ok, result.ok ? result.message : withNetworkHint(result.message), result.json)
    }

    /// Fetches a provider's chat models: the `ModelCache` JSON, or an error message.
    func listModels(_ spec: ProviderSpec) async -> (json: String?, error: String?) {
        let result = await call(stopped: Reply2(json: nil, error: Self.stoppedMessage), late: Reply2(json: nil, error: noAnswerMessage(spec.baseUrl, after: Self.testTimeout))) { done in
            bridge.listModels(spec: spec) { @Sendable json, error in done(Reply2(json: json, error: error)) }
        }
        return (result.json, result.error.map(withNetworkHint))
    }

    private static let stoppedMessage = "The agent runtime was stopped"
    private struct Reply3: Sendable { let ok: Bool, message: String, json: String? }
    private struct Reply2: Sendable { let json: String?, error: String? }

    /// The configured MCP servers; sessions pick up enabled servers' tools at their next turn.
    func setMcpServers(_ servers: [McpServer]) { bridge.setMcpServers(servers: servers.map(SharedMapping.mcpSpec)) }

    /// MCP "test connection" (initialize + tools/list) through the app's transport.
    func testMcpServer(_ server: McpServer, token: Secret?) async -> (Bool, String) {
        let result = await call(stopped: Reply3(ok: false, message: Self.stoppedMessage, json: nil),
                                late: Reply3(ok: false, message: noAnswerMessage(server.url, after: Self.testTimeout), json: nil)) { done in
            bridge.testMcpServer(spec: SharedMapping.mcpSpec(server), token: token?.reveal()) { @Sendable ok, message in
                done(Reply3(ok: ok.boolValue, message: message, json: nil))
            }
        }
        return (result.ok, result.ok ? result.message : withNetworkHint(result.message))
    }

    /// Adds a host-implemented tool (e.g. `generate_image`) to every session.
    func registerTool(_ tool: any HostTool) { bridge.registerTool(tool: tool) }

    /// Local memory on (the encrypted notes) or off: tools and recall come and go in every session.
    func setMemory(_ store: MemoryStoreHost?) { bridge.setMemory(store: store) }

    /// The private vault on (`vault_search` in every session) or off.
    func setVault(_ host: VaultHost?) { bridge.setVault(host: host) }

    /// `web_search` on (settings read per call) or off.
    func setWebSearch(_ source: WebSearchSourceHost?) { bridge.setWebSearch(source: source) }

    /// The Studio's `run_workflow` on (the queue runner) or off.
    func setStudio(_ runner: (any StudioRunner)?) { bridge.setStudio(runner: runner) }

    /// "On limit, fall back" (off by default): a turn whose provider's usage limit is used up is
    /// retried once on the provider's helper model, else on [onDevice] (the "This phone" provider).
    func setQuotaFallback(_ enabled: Bool, onDevice: Provider?) {
        let model = onDevice?.defaultModel ?? ""
        bridge.setQuotaFallback(enabled: enabled, onDeviceProviderId: model.isEmpty ? nil : onDevice?.id, onDeviceModel: model.isEmpty ? nil : model)
    }

    /// Skills found in the workspace (`name: description`).
    func skills() -> [String] { bridge.skills() }
}

/**
 Turns the bridge's session stream ([AgentBridge.openStream]) into [SessionUpdate]s, off the main
 thread (one instance per session, used serially). Transcripts (`TranscriptState`) come only
 when the transcript changed and are decoded then, with tool outputs capped to what the screen
 shows; frames (`LiveFrame`) are small and carry the streamed answer as text deltas, which are
 applied to the text kept here. A delta that does not continue that text (a lost frame) asks the
 bridge to resync, which sends the full text again.
 */
final class SessionStreamDecoder {
    /// Characters of a tool's output kept for the transcript (it shows at most this many).
    static let outputCap = 4000

    private struct TranscriptState: Decodable {
        var projection: SessionProjection
    }

    fileprivate struct LiveFrameStream: Decodable {
        var turn: Int
        var step: Int
        var textFrom: Int
        var text: String
        var reasoningFrom: Int
        var reasoning: String
        var toolNames: [String]
        var tokensPerSecond: Double?
        var reasoningTokens: Int?
        var reasoningDone: Bool?
        var reasoningStop: String?
    }

    /// The frame fields (lenient, so a frame from a newer or older bridge still renders).
    struct Frame: Decodable {
        fileprivate var live: LiveFrameStream?
        var running = false
        var approvals: [ApprovalState] = []
        var question: QuestionState?
        var toolsEnabled = true
        var permission = PermissionPreset.workspace.label
        var provider = ""
        var model = ""
        var thinking = "AUTO"
        var thinkingBudget: Int?
        var thinkingSupport = "NONE"

        private enum K: String, CodingKey { case live, running, approvals, question, toolsEnabled, permission, provider, model, thinking, thinkingBudget, thinkingSupport }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: K.self)
            live = try c.decodeIfPresent(LiveFrameStream.self, forKey: .live)
            running = try c.decodeIfPresent(Bool.self, forKey: .running) ?? false
            approvals = try c.decodeIfPresent([ApprovalState].self, forKey: .approvals) ?? []
            question = try c.decodeIfPresent(QuestionState.self, forKey: .question)
            toolsEnabled = try c.decodeIfPresent(Bool.self, forKey: .toolsEnabled) ?? true
            permission = try c.decodeIfPresent(String.self, forKey: .permission) ?? PermissionPreset.workspace.label
            provider = try c.decodeIfPresent(String.self, forKey: .provider) ?? ""
            model = try c.decodeIfPresent(String.self, forKey: .model) ?? ""
            thinking = try c.decodeIfPresent(String.self, forKey: .thinking) ?? "AUTO"
            thinkingBudget = try c.decodeIfPresent(Int.self, forKey: .thinkingBudget)
            thinkingSupport = try c.decodeIfPresent(String.self, forKey: .thinkingSupport) ?? "NONE"
        }
    }

    /// The streamed answer as applied so far: its stream (turn, step), text and reasoning, with
    /// their lengths in UTF-16 units (the bridge's offsets are Kotlin string offsets).
    private var stream: (turn: Int, step: Int)?
    private var text = ""
    private var textLength = 0
    private var reasoning = ""
    private var reasoningLength = 0

    func transcript(_ json: String) throws -> SessionTranscript {
        let state = try JSONDecoder().decode(TranscriptState.self, from: Data(json.utf8))
        return SessionTranscript(Self.capped(state.projection))
    }

    /// Applies a frame. Returns the update fields and whether the bridge must resync.
    func frame(_ json: String) throws -> (update: SessionUpdate, resync: Bool) {
        let frame = try JSONDecoder().decode(Frame.self, from: Data(json.utf8))
        var resync = false
        var live: LiveState?
        if let l = frame.live {
            if stream.map({ $0 != (l.turn, l.step) }) ?? true { (text, textLength, reasoning, reasoningLength) = ("", 0, "", 0) }
            stream = (l.turn, l.step)
            resync = !Self.apply(l.text, from: l.textFrom, to: &text, length: &textLength)
            resync = !Self.apply(l.reasoning, from: l.reasoningFrom, to: &reasoning, length: &reasoningLength) || resync
            live = LiveState(
                text: text, reasoning: reasoning, toolNames: l.toolNames, tokensPerSecond: l.tokensPerSecond,
                reasoningTokens: l.reasoningTokens, reasoningDone: l.reasoningDone ?? false, reasoningStop: l.reasoningStop,
            )
        } else {
            stream = nil
            (text, textLength, reasoning, reasoningLength) = ("", 0, "", 0)
        }
        let update = SessionUpdate(
            transcript: nil, live: live, running: frame.running, approvals: frame.approvals, question: frame.question,
            settings: SessionSettings(
                toolsEnabled: frame.toolsEnabled, permission: frame.permission, provider: frame.provider, model: frame.model,
                thinking: ThinkingChoice(mode: frame.thinking, budget: frame.thinkingBudget), thinkingSupport: frame.thinkingSupport,
            ),
        )
        return (update, resync)
    }

    /// The delta rule: from 0 replaces, from the current length appends; anything else is a gap
    /// (false: keep what is shown and resync).
    private static func apply(_ delta: String, from: Int, to text: inout String, length: inout Int) -> Bool {
        if from == 0 {
            text = delta
            length = delta.utf16.count
        } else if from == length {
            text += delta
            length += delta.utf16.count
        } else {
            return false
        }
        return true
    }

    /// [projection] with tool outputs cut to [outputCap] characters.
    static func capped(_ projection: SessionProjection) -> SessionProjection {
        var p = projection
        for (i, item) in p.items.enumerated() {
            guard case .tool(var t) = item, let output = t.output, output.utf8.count > outputCap else { continue }
            t.output = String(output.prefix(outputCap))
            p.items[i] = .tool(t)
        }
        return p
    }
}

/**
 Receives a session's stream on the bridge's thread, decodes it on its own queue and delivers to
 the main actor, coalescing: while one update is on its way, newer transcripts replace each other
 (each is complete) and frames queue up; the next pass decodes the latest transcript, applies all
 queued frames' deltas in order and delivers one update. A streaming answer never queues work
 faster than the screen takes it, and no delta is lost.
 */
final class SessionStreamBox: NSObject, SessionStreamListener, @unchecked Sendable {
    private struct Pending {
        var transcript: String?
        var frames: [String] = []
        var busy = false
    }

    private let deliver: @MainActor (SessionUpdate) -> Void
    private let resync: @Sendable () -> Void
    private let slot = Mutex(Pending())
    private let queue = DispatchQueue(label: "autobot.session-stream", qos: .userInitiated)
    /// Used on [queue] only.
    private let decoder = SessionStreamDecoder()

    init(resync: @escaping @Sendable () -> Void, _ deliver: @escaping @MainActor (SessionUpdate) -> Void) {
        self.resync = resync
        self.deliver = deliver
    }

    func onTranscript(json: String) { enqueue { $0.transcript = json } }

    func onFrame(json: String) { enqueue { $0.frames.append(json) } }

    private func enqueue(_ change: (inout Pending) -> Void) {
        let start = slot.withLock { p -> Bool in
            change(&p)
            defer { p.busy = true }
            return !p.busy
        }
        if start { queue.async { self.drain() } }
    }

    /// Decodes what arrived and hands one update to the main actor; runs again if more came.
    private func drain() {
        let batch = slot.withLock { p -> (transcript: String?, frames: [String])? in
            guard p.transcript != nil || !p.frames.isEmpty else {
                p.busy = false
                return nil
            }
            defer {
                p.transcript = nil
                p.frames = []
            }
            return (p.transcript, p.frames)
        }
        guard let batch else { return }
        var update: SessionUpdate?
        var transcript: SessionTranscript?
        var needsResync = false
        do {
            if let json = batch.transcript { transcript = try decoder.transcript(json) }
            for json in batch.frames {
                let (u, resync) = try decoder.frame(json)
                update = u
                needsResync = needsResync || resync
            }
        } catch {
            // A shared/Swift mismatch would otherwise freeze the chat with no trace.
            EngineErrors.record("session stream decode: \(error)")
            needsResync = true
        }
        if needsResync { resync() }
        guard transcript != nil || update != nil else {
            queue.async { self.drain() }
            return
        }
        let deliver = deliver
        let delivered = update
        DispatchQueue.main.async {
            MainActor.assumeIsolated {
                // A transcript alone keeps the rest of the screen as it is.
                if var u = delivered {
                    u.transcript = transcript
                    deliver(u)
                } else if let transcript {
                    deliver(SessionUpdate(transcriptOnly: transcript))
                }
            }
            // Delivered: the next pass picks up whatever arrived meanwhile (or ends).
            self.queue.async { self.drain() }
        }
    }
}

private final class McpStatusListenerBox: NSObject, McpStatusListener, @unchecked Sendable {
    private let deliver: @MainActor ([McpServerStatus]) -> Void

    init(_ deliver: @escaping @MainActor ([McpServerStatus]) -> Void) { self.deliver = deliver }

    func onStatus(servers: [McpServerStatus]) {
        let deliver = deliver
        nonisolated(unsafe) let servers = servers
        DispatchQueue.main.async { MainActor.assumeIsolated { deliver(servers) } }
    }
}

private final class RunningListenerBox: NSObject, RunningListener, @unchecked Sendable {
    private let deliver: @MainActor ([String]) -> Void

    init(_ deliver: @escaping @MainActor ([String]) -> Void) { self.deliver = deliver }

    func onRunning(sessionIds: [String]) {
        let deliver = deliver
        DispatchQueue.main.async { MainActor.assumeIsolated { deliver(sessionIds) } }
    }
}
#endif

import AutobotShared
import PhotosUI
import SwiftUI
import UIKit

private let commands: [(String, String)] = [
    ("/compact", "summarize older history to free context"),
    ("/tools on|off", "toggle tool use for this session"),
    ("/permission read-only|workspace-write|full-access", "set the permission preset"),
    ("/model <id>", "switch model"),
    ("/stop", "cancel the running turn"),
]

/// Human names for tool steps (mockup: "Read calendar / calendar.list").
func stepTitle(_ name: String) -> String {
    // mcp__<server>__<tool> → "Tool · server"
    let rest = name.dropFirst(5)
    if name.hasPrefix("mcp__"), let split = rest.range(of: "__"), split.upperBound < rest.endIndex {
        return stepTitle(String(rest[split.upperBound...])) + " · " + rest[..<split.lowerBound]
    }
    return switch name {
    case "read": "Read file"
    case "write": "Write file"
    case "edit": "Edit file"
    case "delete": "Delete file"
    case "glob": "Find files"
    case "grep": "Search files"
    case "web_fetch": "Fetch page"
    case "generate_image": "Generate image"
    case "todo_write": "Update plan"
    case "ask_user_question": "Ask you"
    case "skill": "Load skill"
    case "task": "Delegate"
    default: name.replacingOccurrences(of: "_", with: " ").prefix(1).uppercased() + name.replacingOccurrences(of: "_", with: " ").dropFirst()
    }
}

/// A setup problem shown above a chat, with the screen that fixes it.
struct ChatBanner: Equatable, Identifiable {
    enum Target: Equatable { case providers, privacyCenter, models }

    let id: String
    let text: String
    let action: String
    let target: Target
}

/**
 What is wrong with a chat's setup before anything is sent: no provider at all, Offline mode
 blocking a remote provider, a cloud provider saved without its API key, or an on-device model
 that is no longer on this phone. Each says what to do and opens the screen that does it.
 */
enum ChatBanners {
    /// The selected on-device model: fine, or gone (removed, not finished, or its files deleted).
    enum LocalModel: Equatable { case fine, missing(String) }

    static func list(loaded: Bool, noProviders: Bool, showOffline: Bool, provider: Provider?, needsKey: Bool, local: LocalModel) -> [ChatBanner] {
        guard loaded else { return [] }
        if noProviders {
            return [ChatBanner(id: "none", text: "No model yet — download one or add a provider.", action: "Add", target: .providers)]
        }
        var out: [ChatBanner] = []
        if showOffline {
            out.append(ChatBanner(id: "offline", text: "Offline mode: this provider is remote, so the kill switch blocks it.", action: "Privacy", target: .privacyCenter))
        }
        if let provider, needsKey, provider.kind != .local, provider.kind != .invincibleNode, (provider.apiKeyRef ?? "").isEmpty {
            // The words Android shows (shared `ChatBannerWords`), before anything is sent.
            out.append(ChatBanner(id: "key", text: ChatBannerWords.shared.missingKey(provider: provider.displayName), action: "Add key", target: .providers))
        }
        if case .missing(let name) = local {
            out.append(ChatBanner(id: "local", text: "“\(name)” is not on this phone any more. Download it again, or pick another model.", action: "Models", target: .models))
        }
        return out
    }

    /// [model] of the "This phone" provider: whether it can run (Apple Intelligence needs no file).
    @MainActor
    static func local(_ model: String?, library: ModelLibrary) -> LocalModel {
        guard let model, !model.isEmpty, model != AppleIntelligence.id else { return .fine }
        guard let installed = library.get(model) else { return .missing(model.components(separatedBy: "catalog:").last ?? model) }
        guard installed.isReady else { return .missing(installed.title) }
        let files = library.paths(installed).values
        return files.allSatisfy { FileManager.default.fileExists(atPath: $0.path) } ? .fine : .missing(installed.title)
    }
}

/// Routing facts of the session's provider (port of the routing half of `ChatViewModel.uiState`).
@MainActor
private struct ChatRouting {
    var provider: Provider?
    var selectedModel: String?
    var modelLabel: String?
    var privacy: PrivacyStatus = .offline
    var showOfflineBanner = false
    var route: Route = .cloudApi

    init(app: AppContainer, conversationId: String) {
        let conversation = app.conversations.get(conversationId)
        let providers = app.providers.providers
        provider = providers.first { $0.id == conversation?.providerId } ?? providers.first
        let mode = app.settings.settings.mode
        let allowLoopback = app.settings.settings.allowLoopbackWhenOffline
        let effective = provider.map { RouteResolver.effectiveMode(mode, $0.routing.override, socksFallback: app.policy.socksFallback) } ?? mode
        let loopback = provider.map { Loopback.isLoopbackURL($0.baseUrl) } ?? false
        let isLocal = provider?.kind == .local
        selectedModel = conversation?.model.flatMap { $0.isEmpty ? nil : $0 } ?? provider?.defaultModel
        privacy = loopback || isLocal ? .offline : effective.privacyStatus
        showOfflineBanner = provider != nil && !isLocal && mode == .offline && !(loopback && allowLoopback)
        route = provider.map { routeOf($0, model: conversation?.model.flatMap { app.library.get($0) }) } ?? .cloudApi
        modelLabel = conversation?.model.map { m in
            guard isLocal else { return m }
            if m == AppleIntelligence.id { return AppleIntelligence.title }
            if let title = app.library.get(m)?.title { return title }
            let catalog = ChatListModel.modelLabel(m)
            if catalog != m { return catalog }
            let tail = m.components(separatedBy: "catalog:").last ?? m
            return (tail.components(separatedBy: "/").last ?? tail).components(separatedBy: ".gguf").first ?? tail
        }
    }
}

/**
 An agent run (mockup 03): red run line, the task as a display title, numbered tool steps with
 timings, approvals as a full red block, and the active policy in the footer.

 The session's parts are observed separately: the body follows the transcript, approvals,
 question, running and settings; the streamed answer re-renders only [LiveSlot]. Rows are
 equatable, so a transcript change re-renders only the rows that changed.
 */
struct ChatScreen: View {
    let conversationId: String
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var model = ChatModel()
    /// The open session, set in .task (opening must not happen while the view renders). Held in
    /// state so setting it re-renders the screen: a chat opened from the list never stays blank.
    @State private var opened: AgentSession?
    @State private var showModels = false
    @State private var showSession = false
    /// The privacy receipt being looked at.
    @State private var openReceipt: Receipt?
    /// The "files changed" sheet.
    @State private var showChanges = false
    @State private var showThink = false
    @State private var showCascade = false
    /// Which transcript items are new since the screen loaded (they ink in).
    @State private var ledger = InkLedger()

    /// The chat's model when it runs on this device through llama.cpp (not Apple Intelligence).
    private var localModelToPrepare: String? {
        let routing = ChatRouting(app: app, conversationId: conversationId)
        guard routing.provider?.kind == .local, let id = routing.selectedModel, id != AppleIntelligence.id else { return nil }
        return id
    }

    var body: some View {
        let _ = InkProbe.hit("body.chat")
        // A previewed (or already open) session shows at once, before .task opens it.
        let session = opened ?? app.agent.existingSession(conversationId)
        let transcript = session?.transcript ?? Self.emptyTranscript
        let settings = session?.settings ?? SessionSettings()
        let running = session?.running ?? false
        let approvals = session?.approvals ?? []
        let isLive = session?.isLive ?? false
        let routing = ChatRouting(app: app, conversationId: conversationId)
        let _ = ledger.observe(transcript.keys + approvals.map { "approval:" + $0.callId })
        let header = ["run", routing.modelLabel ?? routing.selectedModel, transcript.contextUsed.map { "\(compactCount($0)) tok" }].compactMap { $0 }.joined(separator: " · ")
        let speakingKey = model.speakingKey
        let planShown = transcript.projection.todos.contains(where: { $0.status != .completed })

        VStack(spacing: 0) {
            AutobotTopBar(title: settings.toolsEnabled ? "Agent run" : "Chat", onBack: { nav.back() }) {
                Tag("voice", accent: model.voice != nil ? c.primary : nil, filled: model.voice != nil) {
                    model.setVoiceMode(model.voice == nil, app: app, conversationId: conversationId)
                }
                Button { showModels = true } label: { RouteTag(route: routing.route) }.buttonStyle(PressStyle())
                IconAction(symbol: "ellipsis", label: "Session settings") { showSession = true }
            }
            ForEach(banners(routing)) { b in
                Banner(text: b.text, action: b.action) {
                    switch b.target {
                    case .providers: nav.push(.providers)
                    case .privacyCenter: nav.push(.privacyCenter)
                    case .models: nav.reset(.models)
                    }
                }
            }
            if let notice = model.notice {
                Banner(text: notice, action: "OK") { model.notice = nil }
            }

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if app.dataLoaded && transcript.items.isEmpty && !isLive {
                            EmptySession(toolsEnabled: settings.toolsEnabled)
                        }
                        let retry = AnswerRetry.target(transcript.items, running: running || isLive)
                        ForEach(transcript.items) { item in
                            let approval: ApprovalState? = if case .tool(let t) = item { approvals.first { $0.callId == t.callId } } else { nil }
                            TranscriptRow(
                                item: item,
                                retryPrompt: retry?.key == item.key ? retry?.prompt : nil,
                                onRetry: send,
                                number: transcript.stepNumbers[item.key] ?? 0,
                                header: item.key == transcript.firstPrompt ? header : nil,
                                approval: approval,
                                ghost: approval.map { ledger.isNew("approval:" + $0.callId) } ?? false,
                                running: running,
                                speaking: speakingKey == item.key,
                                conversationId: conversationId,
                                model: model,
                                ledger: ledger,
                                openReceipt: $openReceipt,
                            )
                            .equatable()
                            .inkEnter(ledger.isNew(item.key)) { ledger.entered(item.key) }
                        }
                        if let session { LiveSlot(session: session) }
                        // The cascade's offer sits under the phone's answer.
                        if let offer = transcript.projection.cascadeOffer, !running, !isLive {
                            CascadeOfferRow(offer: offer, model: routing.modelLabel ?? routing.selectedModel ?? "") {
                                app.agent.escalate(conversationId)
                            }
                            .id("cascade-offer:\(offer.turn)")
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                .defaultScrollAnchor(.bottom)
                // The plan panel and a question card take room from the transcript as they come and
                // go: the end of the answer stays in view instead of sliding under them.
                .defaultScrollAnchor(.bottom, for: .sizeChanges)
                .scrollDismissesKeyboard(.interactively)
                // One ground per screen: with the voice panel up, its own paper is the ground (two fields
                // with their own grids would meet at the panel's edge).
                .inkGround(app.dataLoaded && transcript.items.isEmpty && !isLive && model.voice == nil, fade: UnitPoint(x: 0.3, y: 0.15))
                .onChange(of: transcript.items.last?.key) { _, _ in withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
                .onChange(of: isLive) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: planShown) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
                // A chat opened with its transcript already there: its end once laid out (images and
                // the lazy rows' real heights arrive after the first pass, where the anchor was set).
                .task(id: conversationId) {
                    try? await Task.sleep(for: .milliseconds(350))
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
                .onChange(of: session?.question?.callId) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
            }

            if planShown {
                TodoPanel(todos: transcript.projection.todos)
            }
            if let question = session?.question {
                QuestionCard(question: question) { app.agent.answerQuestion(conversationId, callId: question.callId, answers: $0) }
            }
            PolicyLine(
                settings: settings, contextUsed: transcript.contextUsed, contextWindow: transcript.projection.contextWindow,
                onThink: { app.agent.setThinking(conversationId, settings.thinking.next) },
                onThinkSheet: { showThink = true },
                onRedact: { app.agent.setRedaction(conversationId, settings.redaction != "ON") },
                onCascade: { showCascade = true },
            )
            if let voice = model.voice {
                VoicePanel(voice: voice, model: model) { model.setVoiceMode(false, app: app, conversationId: conversationId) }
            } else {
                InputBar(model: model, running: running, onSend: send, onStop: { app.agent.cancel(conversationId) }).equatable()
            }
        }
        .background(c.background)
        .imageViewer(Binding(get: { model.viewer }, set: { model.viewer = $0 })) { id, intent in nav.push(.imagine(handoffId: id, intent: intent)) }
        .sheet(isPresented: $showModels) {
            ModelSheet(conversationId: conversationId, routing: routing) { showModels = false }
        }
        .sheet(isPresented: $showSession) {
            SessionSheet(conversationId: conversationId, session: session, onDelete: deleteChat, onChanges: {
                showSession = false
                showChanges = true
            }) { showSession = false }
        }
        .sheet(item: $openReceipt) { r in
            ReceiptSheet(receipt: r) {
                openReceipt = nil
                nav.push(.usage)
            }
        }
        .sheet(isPresented: $showThink) {
            ThinkSheet(
                choice: settings.thinking,
                modelDefault: app.agent.thinkingDefault(provider: settings.provider, model: settings.model),
                onChoose: { app.agent.setThinking(conversationId, $0) },
                onDefault: { app.agent.setThinkingDefault(provider: settings.provider, model: settings.model, $0) },
            )
            .presentationDetents([.height(300)])
        }
        .sheet(isPresented: $showCascade) {
            CascadeSheet(policy: settings.cascade, model: routing.modelLabel ?? routing.selectedModel ?? "") {
                app.agent.setCascade(conversationId, $0)
            }
            .presentationDetents([.height(300)])
        }
        .sheet(isPresented: $showChanges) {
            ChangesSheet(
                groups: transcript.projection.changes, running: running,
                onUndoStep: { app.agent.undoStep(conversationId, callId: $0) },
                onUndoTurn: { app.agent.undoTurn(conversationId, turn: $0) },
            )
        }
        .task {
            opened = app.agent.session(conversationId)
            await start()
        }
        // The chat's on-device model loads while the chat is open (again after a switch).
        .task(id: localModelToPrepare) {
            if let id = localModelToPrepare { await app.prepareLocalChat(modelId: id) }
        }
        .onDisappear {
            model.setVoiceMode(false, app: app, conversationId: conversationId)
            model.stopSpeaking()
            model.stopRecording(app: app)
            // Nothing shows this chat now: stop streaming its state (a running turn goes on) and
            // free the voice and dictation models.
            app.agent.close(conversationId)
            Task { await EngineMemory.shared.release([.tts, .whisper]) }
        }
    }

    private static let emptyTranscript = SessionTranscript(SessionProjection())

    /// Deletes this chat (undoable from the list for a few seconds); a running turn is cancelled first.
    private func deleteChat() {
        showSession = false
        model.setVoiceMode(false, app: app, conversationId: conversationId)
        let title = app.conversations.get(conversationId)?.title ?? ""
        app.chats.trash([conversationId], label: title.isEmpty ? "Chat deleted" : "Deleted “\(title)”")
        nav.back()
    }

    // MARK: Intents

    /// A chat started from an entry point (App Intent, share sheet, widget, control): images, then
    /// voice mode, then the prompt (sent only for an App Intent) or the draft.
    private func start() async {
        guard let start = EntryRouter.shared.take(conversationId) else { return }
        for image in start.images { await model.attach(image, app: app) }
        if start.voice { model.setVoiceMode(true, app: app, conversationId: conversationId) }
        if let prompt = start.send {
            send(prompt)
        } else if let draft = start.draft {
            model.dictated = draft
        }
    }

    /// Sends a prompt (with the attached images) or runs a slash command.
    private func send(_ text: String) {
        let input = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if input.isEmpty && model.pendingImages.isEmpty { return }
        if input.hasPrefix("/") && runCommand(input) { return }
        let images = model.pendingImages
        model.pendingImages = []
        Task { await app.agent.send(conversationId, text: input, images: images) }
    }

    private func runCommand(_ input: String) -> Bool {
        let parts = input.dropFirst().split(maxSplits: 1, whereSeparator: { $0.isWhitespace }).map(String.init)
        let arg = parts.count > 1 ? parts[1].trimmingCharacters(in: .whitespaces) : ""
        switch (parts.first ?? "").lowercased() {
        case "compact":
            app.agent.compact(conversationId)
        case "tools":
            app.agent.setToolsEnabled(conversationId, arg != "off")
        case "permission", "perm":
            if let preset = PermissionPreset.fromLabel(arg) {
                app.agent.setPermission(conversationId, preset)
            } else {
                model.notice = "usage: /permission read-only | workspace-write | full-access"
            }
        case "model":
            if arg.isEmpty {
                model.notice = "usage: /model <id>"
            } else if let provider = ChatRouting(app: app, conversationId: conversationId).provider ?? app.providers.providers.first {
                app.agent.setRoute(conversationId, providerId: provider.id, model: arg)
            }
        case "stop":
            app.agent.cancel(conversationId)
        default:
            return false
        }
        return true
    }
}

// MARK: - Transcript

/**
 One transcript row. Equatable on what it shows (not its actions, which only reach stable
 objects), so a transcript change re-evaluates only the rows whose item or context changed.
 */
private struct TranscriptRow: View, Equatable {
    let item: SessionItem
    /// The prompt "Retry" sends again, on the last answer (or failed turn) only.
    var retryPrompt: String? = nil
    var onRetry: (String) -> Void = { _ in }
    let number: Int
    /// The run header's label when this is the first prompt.
    let header: String?
    let approval: ApprovalState?
    /// The approval card enters out of register.
    let ghost: Bool
    let running: Bool
    let speaking: Bool
    let conversationId: String
    let model: ChatModel
    let ledger: InkLedger
    @Binding var openReceipt: Receipt?
    /// "Select text" from the long-press menu: the message in a sheet where any part can be selected.
    @State private var selecting: SelectableText?
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.item == b.item && a.retryPrompt == b.retryPrompt && a.number == b.number && a.header == b.header && a.approval == b.approval && a.ghost == b.ghost
            && a.running == b.running && a.speaking == b.speaking && a.conversationId == b.conversationId && a.model === b.model
    }

    var body: some View {
        switch item {
        case .user(_, let text, let images):
            VStack(alignment: .leading, spacing: 8) {
                if let header {
                    RunHeader(prompt: text, label: header)
                } else {
                    FollowUp(text: text)
                }
                if !images.isEmpty { AttachmentStrip(ids: images, model: model) }
            }
            .contextMenu { MessageActions(text: text, onSelect: { selecting = SelectableText(text: text) }).menu() }
            .sheet(item: $selecting) { SelectTextSheet(text: $0.text) }
        case .assistant(let key, let text, let reasoning, let interrupted, let outputTokens, _, let receipt, let turnChanges, let thinking):
            VStack(alignment: .leading, spacing: 6) {
                AssistantMessage(
                    text: text, reasoning: reasoning, interrupted: interrupted, outputTokens: outputTokens, speaking: speaking,
                    receipt: receipt, onReceipt: { openReceipt = $0 }, thinking: thinking,
                    onRetry: retryPrompt.map { prompt in { onRetry(prompt) } },
                    onSelect: { selecting = SelectableText(text: text) },
                ) {
                    model.speak(key: key, text: text)
                }
                .sheet(item: $selecting) { SelectTextSheet(text: $0.text) }
                UndoTurnLine(
                    changes: turnChanges, running: running,
                    onUndo: { app.agent.undoTurn(conversationId, turn: $0) },
                    onRedo: { app.agent.redo(conversationId, revertId: $0) },
                )
            }
        case .tool(let tool):
            if let approval {
                let key = "approval:" + tool.callId
                ApprovalCard(number: number, tool: tool, approval: approval) { outcome in
                    app.agent.answerApproval(conversationId, callId: tool.callId, outcome: outcome)
                }
                .registerGhost(ghost, color: c.primary)
                .onAppear { ledger.entered(key) }
            } else {
                StepRow(
                    number: number, tool: tool, model: model, running: running,
                    onUndo: { app.agent.undoStep(conversationId, callId: $0) },
                    onRedo: { app.agent.redo(conversationId, revertId: $0) },
                ) { id, all in if app.studio.library.asset(id) != nil { nav.enterStudio(.library) } else { model.viewer = ImageViewerTarget(ids: all, start: id) } }
            }
        case .notice(_, let text, let kind):
            NoticeRow(text: text, kind: kind, onRetry: retryPrompt.map { prompt in { onRetry(prompt) } })
        }
    }
}

/// The streamed answer: the only view that follows the session's live frames.
private struct LiveSlot: View {
    let session: AgentSession

    var body: some View {
        if let live = session.live { LiveMessage(live: live) }
    }
}

private struct RunHeader: View {
    let prompt: String
    let label: String
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel(label)
            Text(prompt).textStyle(.headlineLarge).foregroundStyle(c.onBackground).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.bottom, 8)
    }
}

private struct FollowUp: View {
    let text: String
    @Environment(\.scheme) private var c

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("›").textStyle(.code).foregroundStyle(c.primary)
            Text(text).textStyle(.titleMedium).foregroundStyle(c.onBackground).fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct AssistantMessage: View {
    let text: String
    let reasoning: String?
    let interrupted: Bool
    let outputTokens: Int?
    let speaking: Bool
    var receipt: Receipt? = nil
    var onReceipt: (Receipt) -> Void = { _ in }
    var thinking: ThinkingInfo? = nil
    /// Sends the last prompt again (the last answer only).
    var onRetry: (() -> Void)? = nil
    var onSelect: (() -> Void)? = nil
    let onSpeak: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let reasoning, !reasoning.isEmpty { ThinkingDisclosure(reasoning: reasoning, tokens: thinking?.tokens, stop: thinking?.stop) }
            if !text.isEmpty {
                ChatBubble(role: .assistant, text: text) {
                    // Status and actions on one line, or the actions under the status at large sizes.
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 8) {
                            footerStatus
                            Spacer(minLength: 0)
                            footerActions
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            footerStatus
                            HStack(spacing: 8) {
                                Spacer(minLength: 0)
                                footerActions
                            }
                        }
                    }
                }
            }
            ReceiptLine(receipt: receipt, onOpen: onReceipt)
        }
        .contextMenu { MessageActions(text: text, onRetry: onRetry, onSpeak: onSpeak, onSelect: onSelect).menu() }

    }

    @ViewBuilder private var footerStatus: some View {
        if interrupted { Tag("interrupted", accent: c.inks.amber) }
        if let outputTokens { TokenRateLabel(totalTokens: outputTokens) }
    }

    @ViewBuilder private var footerActions: some View {
        // Copies the message as written (markdown), not the rendered text.
        CopyMessageButton(markdown: text)
        ShareMessageButton(markdown: text)
        if let onRetry {
            IconAction(symbol: "arrow.clockwise", label: "Retry", tint: c.onSurfaceVariant, size: 17, action: onRetry)
                .compactActionFrame()
        }
        IconAction(.speaker, label: speaking ? "Stop reading" : "Read aloud", tint: speaking ? c.primary : c.onSurfaceVariant, size: 18, action: onSpeak)
            .compactActionFrame()

    }
}

private struct LiveMessage: View {
    let live: LiveState
    @Environment(\.scheme) private var c

    var body: some View {
        let _ = InkProbe.hit("body.liveMessage")
        VStack(alignment: .leading, spacing: 6) {
            if !live.reasoning.isEmpty {
                ThinkingDisclosure(
                    reasoning: live.reasoning, isThinking: live.text.isEmpty && live.reasoningDone != true,
                    tokens: live.reasoningTokens, stop: live.reasoningStop,
                )
            }
            if !live.text.isEmpty || live.reasoning.isEmpty {
                if let rate = live.tokensPerSecond {
                    ChatBubble(role: .assistant, text: live.text, isStreaming: true) { TokenRateLabel(tokensPerSecond: rate) }
                } else {
                    ChatBubble(role: .assistant, text: live.text, isStreaming: true)
                }
            }
            ForEach(Array(live.toolNames.enumerated()), id: \.offset) { _, name in
                HStack(spacing: 10) {
                    HalftoneStrip(color: c.primary, cell: 3)
                    Text(stepTitle(name)).textStyle(.titleMedium).foregroundStyle(c.onSurface)
                    MicroLabel("composing…")
                }
            }
        }
    }
}

/// `▪ Read file / └ read · notes.md   ✓ 0.4 s` (the terminal's step grammar)
private struct StepRow: View {
    let number: Int
    let tool: SessionItem.Tool
    let model: ChatModel
    /// A turn is running: undo waits for it.
    var running = false
    /// Undo this file step (call id) / redo an undo (revert id).
    var onUndo: (String) -> Void = { _ in }
    var onRedo: (String) -> Void = { _ in }
    /// Opens that gallery item's detail.
    let onOpenGallery: (_ id: String, _ all: [String]) -> Void
    @State private var expanded = false
    @Environment(\.scheme) private var c
    @Environment(AppContainer.self) private var app

    /**
     Opens an image full screen with the step's other images. A saved incognito image opens under
     its gallery id; an unsaved one is shown from memory (viewing writes nothing anywhere).
     */
    private func open(_ id: String) {
        if app.studio.library.asset(id) != nil { return onOpenGallery(id, tool.galleryIds) }
        let resolve: (String) -> String = { app.gallery.keptIncognito[$0] ?? $0 }
        let start = resolve(id)
        guard app.gallery.get(start) != nil else { return }
        onOpenGallery(start, tool.galleryIds.map(resolve).filter { app.gallery.get($0) != nil })
    }

    /// The `▪`: purple while the step works or waits for you, ash once done, red when it failed.
    private var stepMarkColor: Color {
        switch tool.state {
        case .running, .streaming, .waitingApproval: AutobotColors.purple
        case .error: c.error
        case .ok, .denied: c.onSurfaceVariant
        }
    }

    /// A step mark stamps in when the step reaches one of these.

    private static func settled(_ state: ToolState) -> Bool { state == .ok || state == .error || state == .denied }

    /// The mark stamps again when the step is undone or redone.
    private struct StampKey: Equatable {
        var state: ToolState
        var undone: Bool
    }

    var body: some View {
        let _ = InkProbe.hit("body.stepRow")
        let (mark, markColor): (String, Color) = switch tool.state {
        case .ok: (tool.isUndone ? "undone" : "✓", c.onSurfaceVariant)
        case .error: ("×", c.error)
        case .denied: ("denied", c.onSurfaceVariant)
        case .running, .streaming, .waitingApproval: ("", c.primary)
        }
        VStack(alignment: .leading, spacing: 0) {
            Button { if tool.output != nil { expanded.toggle() } } label: {
                HStack(alignment: .top, spacing: 12) {
                    // The terminal's `▪`, drawn (a text ▪ turns into an emoji on iOS).
                    Rectangle().fill(stepMarkColor).frame(width: 6, height: 6)
                        .padding(.top, 9)
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(stepTitle(tool.name)).textStyle(.titleMedium).strikethrough(tool.isUndone).foregroundStyle(c.onSurface)
                        HStack(spacing: 8) {
                            Text(StepGrammar.detail(name: tool.name, summary: tool.summary))

                                .textStyle(.micro)
                                .foregroundStyle(c.onSurfaceVariant)
                                .lineLimit(1)
                            if let change = tool.change { ChangeCounts(change: change) }
                        }
                        // Why a step failed shouldn't take a tap to find out.
                        if !expanded, tool.state == .error || tool.state == .denied,
                           let reason = tool.output?.split(whereSeparator: \.isNewline).first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }),
                           !["denied", "error: denied"].contains(reason.lowercased()) {
                            Text(reason.hasPrefix("Error: ") ? String(reason.dropFirst(7)) : String(reason))
                                .textStyle(.bodySmall)
                                .foregroundStyle(tool.state == .error ? c.error : c.onSurfaceVariant)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // The mark keeps its identity so it can stamp in when the step settles.
                    ZStack(alignment: .topTrailing) {
                        Text(mark + (tool.durationMs.map { String(format: " %.1f s", Double($0) / 1000) } ?? ""))
                            .textStyle(.readout)
                            .foregroundStyle(markColor)
                            .stamp(StampKey(state: tool.state, undone: tool.isUndone), when: { Self.settled($0.state) })
                        if mark.isEmpty { InkLoader().padding(.top, 4) }
                    }
                }
                .padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(PressStyle())
            if expanded, let steps = tool.steps, !steps.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(steps.enumerated()), id: \.offset) { _, step in
                        Text(step).textStyle(.micro).foregroundStyle(c.onSurfaceVariant).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 34)
                .padding(.bottom, 6)
            }
            if expanded, let change = tool.change {
                VStack(alignment: .leading, spacing: 6) {
                    DiffBlock(change: change)
                    if tool.isUndone, let redo = tool.revertId {
                        Tag("↷ redo", accent: c.primary) { if !running { onRedo(redo) } }
                    } else if !tool.isUndone && change.undoable {
                        Tag("↶ undo step", accent: c.primary) { if !running { onUndo(tool.callId) } }
                    }
                }
                .padding(.bottom, 6)
            } else if expanded, let output = tool.output {
                Text(String(output.prefix(4000)))
                    .textStyle(.codeBlock)
                    .foregroundStyle(c.onSurfaceVariant)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(c.surfaceContainerLow)
                    .textSelection(.enabled)
            }
            if tool.name == "generate_image" && (tool.state == .running || !tool.galleryIds.isEmpty) {
                // The agent's job draws the same ink plate as Imagine, then prints its first image.
                HStack(alignment: .top, spacing: 6) {
                    VStack(alignment: .leading, spacing: 4) {
                        AgentProof(tool: tool, model: model).onTapGesture { if let id = tool.galleryIds.first { open(id) } }
                            .accessibilityAddTraits(tool.galleryIds.isEmpty ? [] : .isButton)
                        if let id = tool.galleryIds.first { UnsavedMark(id: id) }
                    }
                    ForEach(tool.galleryIds.dropFirst(), id: \.self) { id in
                        VStack(alignment: .leading, spacing: 4) {
                            GalleryThumb(id: id, model: model).onTapGesture { open(id) }.accessibilityAddTraits(.isButton)
                            UnsavedMark(id: id)
                        }
                    }
                }
                .padding(.bottom, 6)
            } else if !tool.galleryIds.isEmpty {
                HStack(alignment: .top, spacing: 6) {
                    ForEach(tool.galleryIds, id: \.self) { id in
                        VStack(alignment: .leading, spacing: 4) {
                            GalleryThumb(id: id, model: model).onTapGesture { open(id) }.accessibilityAddTraits(.isButton)
                            UnsavedMark(id: id)
                        }
                    }
                }
                .padding(.bottom, 6)
            }
            Hairline()
        }
    }
}

/**
 Under an incognito image: NOT SAVED with SAVE, then SAVED. The image is in memory only until the
 user saves it ([GalleryRepository.keep]); a gallery image shows nothing.
 */
private struct UnsavedMark: View {
    let id: String
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    var body: some View {
        if UnsavedImages.isUnsaved(id) && app.gallery.unsaved.get(id) != nil || app.gallery.keptIncognito[id] != nil {
            if app.gallery.keptIncognito[id] != nil {
                MicroLabel(PanicCopy.shared.SAVED)
            } else {
                HStack(spacing: 6) {
                    MicroLabel(PanicCopy.shared.NOT_SAVED)
                    Tag(PanicCopy.shared.SAVE, accent: c.primary) { Task { try? await app.gallery.keep(id) } }
                }
            }
        }
    }
}

private struct GalleryThumb: View {
    let id: String
    let model: ChatModel
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            c.surfaceContainerLow
            if let image { Image(uiImage: image).resizable().scaledToFill() }
        }
        .frame(width: 120, height: 120)
        .clipped()
        .accessibilityLabel("Generated image")
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Opens the image full screen")
        .task(id: id) { image = await model.galleryThumbnail(id, app: app) }
    }
}

/// `generate_image` in the transcript: flows and develops with the agent's job, prints its first image.
private struct AgentProof: View {
    let tool: SessionItem.Tool
    let model: ChatModel
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var image: UIImage?
    @State private var preview: PlateImage?
    @State private var serial = 0

    var body: some View {
        let id = tool.galleryIds.first
        let job: (step: Int, total: Int, preview: Data?)? = {
            if case .running(let source, _, _, _, let step, let total, let preview, _) = app.generator.state, source == .agent { return (step, total, preview) }
            return nil
        }()
        let ink: PlateInk = {
            if let id, let image, let grid = LumaCache.grid(image) { return .final(PlateImage(id: id, grid: grid, image: image)) }
            if id != nil || tool.state != .running { return .hold }
            // Between images (or once the job ends) keep what the plate shows.
            guard let job else { return preview != nil ? .hold : .flow(progress: 0, loading: true) }
            if job.preview != nil, let preview { return .preview(preview) }
            return .flow(progress: job.total > 0 ? Double(job.step) / Double(job.total) : 0, loading: app.generator.phase != nil || job.total == 0)
        }()
        InkPlate(ink: ink, dot: c.primary, idleDot: c.outlineVariant, plate: c.primary)
            .frame(width: 120, height: 120)
            .background(c.surfaceContainerLow)
            .clipped()
            .accessibilityElement()
            .accessibilityLabel(id != nil ? "Generated image" : "Generating image")
            .task(id: job?.preview) {
                guard let data = job?.preview, let decoded = await decodeImage(data, maxSide: 512), !Task.isCancelled,
                      let cg = decoded.cgImage, let grid = LumaGrid(image: cg) else { return }
                serial += 1
                preview = PlateImage(id: "\(serial)", grid: grid)
            }
            .task(id: id) {
                guard let id else { return }
                image = await model.galleryThumbnail(id, app: app)
            }
    }
}

/// The approval block: `▪ Approval needed · network access`, title, args, Deny / Approve.
/// When the call would send private data (taint tracking), the header says so and every private
/// fragment is printed in reverse, in the arguments and in the reason.
private struct ApprovalCard: View {
    let number: Int
    let tool: SessionItem.Tool
    let approval: ApprovalState
    let onDecide: (ApprovalOutcome) -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                HStack(spacing: 6) {
                    // What needs you now: the one purple mark on the card.
                    Rectangle().fill(AutobotColors.purple).frame(width: 6, height: 6)
                    Text(StepGrammar.approvalNeeded).textStyle(.micro).foregroundStyle(c.onSurface)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Text((privateData.isEmpty ? approval.reason ?? "" : "private data")).textStyle(.micro).foregroundStyle(c.onSurfaceVariant)
            }
            Text(stepTitle(tool.name)).textStyle(.headlineMedium).foregroundStyle(c.onSurface)
            Text(marked("\(tool.name)(\(approval.summary))")).textStyle(.codeBlock).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
            if !privateData.isEmpty {
                Text(marked(approval.reason ?? "")).textStyle(.bodyMedium).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 8) {
                GhostButton("Deny") { onDecide(.rejected) }
                PaperButton("Approve") { onDecide(.allowedOnce) }
            }
            Button { onDecide(.allowedForSession) } label: {
                Text("Always allow \(tool.name) in this run").textStyle(.micro).underline(color: c.primary).foregroundStyle(c.onSurface)
                    .padding(.vertical, 4).frame(minHeight: 44)
            }
            .buttonStyle(PressStyle())
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: AutobotShapes.medium).fill(c.surfaceContainer))
        .overlay(RoundedRectangle(cornerRadius: AutobotShapes.medium).strokeBorder(c.primary, lineWidth: Tokens.Stroke.border))
    }

    private var privateData: [String] { (approval.privateData ?? []).filter { !$0.isEmpty } }

    /// `text` with every case-insensitive occurrence of a private fragment in reverse.
    private func marked(_ text: String) -> AttributedString {
        var out = AttributedString(text)
        for fragment in privateData {
            var rest = out.startIndex..<out.endIndex
            while let range = out[rest].range(of: fragment, options: .caseInsensitive) {
                out[range].backgroundColor = c.primaryContainer
                out[range].foregroundColor = c.onPrimaryContainer
                rest = range.upperBound..<out.endIndex
            }
        }
        return out
    }
}

/// `// stopped`; errors keep their case (ids, hosts and keys must stay readable) and expand on tap.
private struct NoticeRow: View {
    let text: String
    let kind: NoticeKind
    /// The failed turn's prompt can be sent again.
    var onRetry: (() -> Void)? = nil
    @State private var expanded = false
    @Environment(\.scheme) private var c

    var body: some View {
        let color: Color = switch kind {
        case .info: c.outline
        case .warn: c.inks.amber
        case .error: c.error
        }
        if kind == .error {
            VStack(alignment: .leading, spacing: 4) {
                Text("✗ " + text)
                    .textStyle(.bodySmall)
                    .foregroundStyle(color)
                    .lineLimit(expanded ? nil : 3)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture { expanded.toggle() }
                if let onRetry { TextAction("Retry", color: c.onSurface, action: onRetry) }
            }

        } else {
            Text(text.sentenceCased).textStyle(.micro).foregroundStyle(color).lineLimit(3)
        }
    }
}

private struct EmptySession: View {
    let toolsEnabled: Bool
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel(toolsEnabled ? "agent" : "chat")
            Text(toolsEnabled ? "What should\nit do?" : "Ask\nanything.").textStyle(.displaySmall).foregroundStyle(c.onSurface)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 48)
    }
}

/// `POLICY · WORKSPACE · ASKS: DELETE, NETWORK` (as on Android), with the Think chip when the
/// chat's model reasons and the REDACT chip on a cloud route.
private struct PolicyLine: View {
    let settings: SessionSettings
    let contextUsed: Int?
    let contextWindow: Int?
    var onThink: () -> Void = {}
    var onThinkSheet: () -> Void = {}
    var onRedact: () -> Void = {}
    var onCascade: () -> Void = {}

    @ViewBuilder private var chips: some View {
        if settings.thinkingSupport != "NONE" { ThinkChip(choice: settings.thinking, onTap: onThink, onLongPress: onThinkSheet).fixedSize() }
        if settings.redaction != "NONE" { RedactChip(on: settings.redaction == "ON", onTap: onRedact).fixedSize() }
        if settings.cascade != "NONE" { CascadeChip(policy: settings.cascade, onTap: onCascade).fixedSize() }
    }

    @ViewBuilder private var context: some View {
        if settings.toolsEnabled, let used = contextUsed, let window = contextWindow, window > 0 {
            MicroLabel("\(compactCount(used))/\(compactCount(window))").fixedSize()
        }
    }

    var body: some View {
        let think = settings.thinkingSupport != "NONE"
        let redact = settings.redaction != "NONE"
        let cascade = settings.cascade != "NONE"
        if settings.toolsEnabled || think || redact || cascade {
            let text = switch settings.permissionPreset {
            case .readOnly: "policy · read-only · asks: network"
            case .workspace: "policy · workspace · asks: delete, network"
            case .fullAccess: "policy · full access"
            }
            // One row when it fits; on a narrow phone the policy moves under the chips rather than
            // squeezing them (`THINK · AU…`) or cutting itself short.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    chips
                    if settings.toolsEnabled { MicroLabel(text).fixedSize() }
                    Spacer(minLength: 0)
                    context
                }
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        chips
                        Spacer(minLength: 0)
                        context
                    }
                    if settings.toolsEnabled { MicroLabel(text, lines: 2) }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
    }
}

/// `∴ THINK · AUTO`: tap cycles auto → on → off; a long press opens the budget sheet.
private struct ThinkChip: View {
    let choice: ThinkingChoice
    let onTap: () -> Void
    let onLongPress: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        let active = choice.mode != "OFF"
        HStack(spacing: 4) {
            Text("∴").textStyle(.code).foregroundStyle(active ? c.secondary : c.outline)
            MicroLabel(choice.chipLabel, color: active ? c.secondary : c.onSurfaceVariant)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .overlay(Rectangle().stroke(active ? c.secondary.opacity(0.7) : c.outlineVariant, lineWidth: 1))
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .onLongPressGesture(perform: onLongPress)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Thinking")
        .accessibilityValue(choice.chipLabel.replacingOccurrences(of: "think · ", with: ""))
        .accessibilityHint("Tap to switch auto, on or off. Hold for the budget.")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction(named: "Budget", onLongPress)
    }
}

/// `▮ REDACT` while personal data becomes placeholders before a cloud model sees it, `REDACT OFF`
/// when this chat sends it as written. A tap switches it for this chat.
private struct RedactChip: View {
    let on: Bool
    let onTap: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        MicroLabel(on ? "▮ redact" : "redact off", color: on ? c.primary : c.onSurfaceVariant)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .overlay(Rectangle().stroke(on ? c.primary.opacity(0.7) : c.outlineVariant, lineWidth: 1))
            .contentShape(Rectangle())
            .onTapGesture(perform: onTap)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Redaction")
            .accessibilityValue(on ? "On: names, emails and numbers are hidden from the cloud" : "Off")
            .accessibilityHint("Tap to switch it for this chat.")
            .accessibilityAddTraits(.isButton)
    }
}

/// `▮ PHONE FIRST · ASK` while this phone answers a plain cloud chat first, `PHONE FIRST OFF`
/// dimmed otherwise (the shared `CascadePolicy` chip words). A tap opens the choice for this chat.
private struct CascadeChip: View {
    let policy: String
    let onTap: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        let p = CascadePolicy.companion.parse(value: policy) ?? CascadePolicy.off
        let on = p.localFirst
        MicroLabel(on ? "▮ " + p.chip : p.chip, color: on ? c.primary : c.onSurfaceVariant)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .overlay(Rectangle().stroke(on ? c.primary.opacity(0.7) : c.outlineVariant, lineWidth: 1))
            .contentShape(Rectangle())
            .onTapGesture(perform: onTap)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Phone first")
            .accessibilityValue("\(p.title). \(p.detail)")
            .accessibilityHint("Tap to choose for this chat.")
            .accessibilityAddTraits(.isButton)
    }
}

/// Under the phone's answer when the judge was not convinced: why, and "Ask <cloud model>".
struct CascadeOfferRow: View {
    let offer: CascadeOffer
    let model: String
    let onAsk: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            MicroLabel("▮ phone first", color: c.primary)
            Text(offer.text).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
            Tag(CascadeLabels.shared.ask(model: model), accent: c.primary, filled: true, action: onAsk)
                .accessibilityHint("Sends this prompt to the cloud model, redacted when redaction is on.")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(Rectangle().stroke(c.primary.opacity(0.45), lineWidth: 1))
    }
}

/// The chat's "phone first" policy: off, ask, auto or never, each with its line, and the scope.
struct CascadeSheet: View {
    let policy: String
    let model: String
    let onChoose: (String) -> Void
    @State private var current: String
    @Environment(\.scheme) private var c

    init(policy: String, model: String, onChoose: @escaping (String) -> Void) {
        self.policy = policy
        self.model = model
        self.onChoose = onChoose
        _current = State(initialValue: policy)
    }

    var body: some View {
        let chosen = CascadePolicy.companion.parse(value: current) ?? CascadePolicy.off
        VStack(alignment: .leading, spacing: 14) {
            SectionLabel("phone first")
            Segmented(options: CascadePolicy.entries.map(\.name), selected: chosen.name, label: { (CascadePolicy.companion.parse(value: $0)?.title ?? $0).lowercased() }) { name in
                current = name
                onChoose(name)
            }
            Text(chosen.detail).textStyle(.bodyMedium).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
            Text(CascadeLabels.shared.sheetNote(model: model, node: false)).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(c.surface)
    }
}

/// The Think switch in full: off / auto / on, a reasoning budget for on, and this model's default.
struct ThinkSheet: View {
    let choice: ThinkingChoice
    let modelDefault: ThinkingChoice?
    let onChoose: (ThinkingChoice) -> Void
    let onDefault: (ThinkingChoice?) -> Void
    @State private var current: ThinkingChoice
    @State private var saved: ThinkingChoice?
    @Environment(\.scheme) private var c

    init(choice: ThinkingChoice, modelDefault: ThinkingChoice?, onChoose: @escaping (ThinkingChoice) -> Void, onDefault: @escaping (ThinkingChoice?) -> Void) {
        self.choice = choice
        self.modelDefault = modelDefault
        self.onChoose = onChoose
        self.onDefault = onDefault
        _current = State(initialValue: choice)
        _saved = State(initialValue: modelDefault)
    }

    private struct Mode: Hashable {
        let id: String
        let label: String
    }

    private static let modes = [Mode(id: "OFF", label: "off"), Mode(id: "AUTO", label: "auto"), Mode(id: "ON", label: "on")]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionLabel("think")
            Segmented(options: Self.modes, selected: Self.modes.first { $0.id == current.mode } ?? Self.modes[1], label: \.label) { mode in
                current = ThinkingChoice(mode: mode.id, budget: mode.id == "ON" ? current.budget : nil)
                onChoose(current)
            }
            if current.mode == "ON" {
                VStack(alignment: .leading, spacing: 6) {
                    MicroLabel("budget")
                    HStack(spacing: 6) {
                        ForEach(ThinkingChoice.budgets, id: \.self) { budget in
                            let label = budget.map { ThinkingChoice.tokens($0) } ?? "model"
                            Tag(label, accent: current.budget == budget ? c.secondary : nil, filled: current.budget == budget) {
                                current = ThinkingChoice(mode: "ON", budget: budget)
                                onChoose(current)
                            }
                        }
                    }
                }
            }
            HStack {
                MicroLabel(saved.map { "model default · " + $0.chipLabel.replacingOccurrences(of: "think · ", with: "") } ?? "model default · auto")
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(saved == current ? "Clear" : "Set") {
                    let next: ThinkingChoice? = saved == current ? nil : current
                    saved = next
                    onDefault(next)
                }
                .textStyle(.labelLarge)
                .foregroundStyle(c.primary)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background(c.surface)
    }
}

extension ChatScreen {
    fileprivate func banners(_ routing: ChatRouting) -> [ChatBanner] {
        let provider = routing.provider
        return ChatBanners.list(
            loaded: app.dataLoaded,
            noProviders: app.providers.providers.isEmpty,
            showOffline: routing.showOfflineBanner,
            provider: provider,
            needsKey: provider.map { ProviderPresets.resolve($0).requiresApiKey } ?? false,
            local: provider?.kind == .local ? ChatBanners.local(routing.selectedModel, library: app.library) : .fine,
        )
    }
}

private struct Banner: View {
    let text: String
    let action: String
    let onAction: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        HStack(spacing: 0) {
            Text(text).textStyle(.bodySmall).foregroundStyle(c.onSurface).frame(maxWidth: .infinity, alignment: .leading)
            TextAction(action, style: .labelMedium, action: onAction)
        }
        .padding(.leading, 16)
        .background(c.surfaceContainerHigh)
        .hairlineEdge()
    }
}

// MARK: - Bottom stack

private struct TodoPanel: View {
    let todos: [TodoItem]
    @State private var open = true
    @Environment(\.scheme) private var c

    var body: some View {
        let done = todos.filter { $0.status == .completed }.count
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                SectionLabel("plan · \(done)/\(todos.count)")
                Tag(open ? "hide" : "show") { open.toggle() }
            }
            if open {
                ForEach(Array(todos.enumerated()), id: \.offset) { _, todo in
                    let (glyph, color): (String, Color) = switch todo.status {
                    case .completed: ("■", c.outline)
                    case .inProgress: ("▶", c.primary)
                    case .pending: ("□", c.onSurfaceVariant)
                    }
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(glyph).textStyle(.code).foregroundStyle(color)
                        Text(todo.content).textStyle(.bodySmall).foregroundStyle(todo.status == .completed ? c.outline : c.onSurface).lineLimit(2)
                    }
                    .padding(.top, 6)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(c.surfaceContainer)
    }
}

private struct QuestionCard: View {
    let question: QuestionState
    let onAnswer: ([String]?) -> Void
    @State private var free = ""
    @State private var picked: [String] = []
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                SectionLabel("agent asks")
                Tag("skip") { onAnswer(nil) }
            }
            Text(question.question).textStyle(.titleMedium).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
            if !question.options.isEmpty {
                ChatFlow(spacing: 6) {
                    ForEach(question.options, id: \.self) { option in
                        let active = picked.contains(option)
                        Tag(option, accent: active ? c.primary : c.onSurfaceVariant, filled: active) {
                            if question.multiSelect {
                                if active { picked.removeAll { $0 == option } } else { picked.append(option) }
                            } else {
                                onAnswer([option])
                            }
                        }
                    }
                }
            }
            HStack(alignment: .center, spacing: 8) {
                ConsoleTextField(text: $free, placeholder: "or type an answer")
                let answer = picked + (free.trimmingCharacters(in: .whitespaces).isEmpty ? [] : [free.trimmingCharacters(in: .whitespaces)])
                PrimaryButton("Send", enabled: !answer.isEmpty) { onAnswer(answer) }.frame(minWidth: 96).fixedSize(horizontal: true, vertical: false)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(c.surfaceContainerHigh)
        .onChange(of: question.callId) { _, _ in
            free = ""
            picked = []
        }
    }
}

private struct InputBar: View, Equatable {
    let model: ChatModel
    let running: Bool
    let onSend: (String) -> Void
    let onStop: () -> Void

    /// The actions reach stable objects (the screen's agent and conversation): not compared.
    nonisolated static func == (a: Self, b: Self) -> Bool { a.model === b.model && a.running == b.running }
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var text = ""
    @State private var picks: [PhotosPickerItem] = []

    var body: some View {
        VStack(spacing: 0) {
            if !model.pendingImages.isEmpty {
                AttachmentStrip(ids: model.pendingImages, model: model) { model.removeAttachment($0, app: app) }
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if text.hasPrefix("/") {
                let typed = String(text.split(separator: " ", maxSplits: 1).first ?? "")
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(commands.filter { $0.0.hasPrefix(typed) }, id: \.0) { cmd, help in
                        Button { text = String(cmd.split(separator: " ").first ?? "") + " " } label: {
                            HStack(spacing: 10) {
                                Text(cmd).textStyle(.codeBlock).foregroundStyle(c.primary).lineLimit(1).fixedSize()
                                Text(help).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).lineLimit(1)
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PressStyle())
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
                Hairline()
            }
            if model.dictation != .idle {
                LiveWordsLine(words: model.heardWords)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
            HStack(alignment: .bottom, spacing: 8) {
                PhotosPicker(selection: $picks, maxSelectionCount: max(1, ChatModel.maxAttachments - model.pendingImages.count), matching: .images) {
                    AutobotIconView(icon: .image, size: 24, color: model.pendingImages.count < ChatModel.maxAttachments ? c.onSurface : c.onSurface.opacity(0.38))
                        .frame(width: 44, height: 52)
                }
                .disabled(model.pendingImages.count >= ChatModel.maxAttachments)
                .accessibilityLabel("Attach image")
                Button { model.toggleDictation(app: app) } label: {
                    Group {
                        switch model.dictation {
                        case .idle: AutobotIconView(icon: .mic, size: 24, color: c.onSurface)
                        case .recording: Rectangle().fill(c.primary).frame(width: 14, height: 14)
                        case .transcribing: InkLoader(.button)
                        }
                    }
                    .frame(width: 44, height: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .disabled(model.dictation == .transcribing)
                .accessibilityLabel(model.dictation == .recording ? "Stop dictation" : "Dictate")
                ConsoleTextField(text: $text, placeholder: running ? "Queue a follow-up…" : "Message · / for commands", maxLines: 8)
                if running && text.trimmingCharacters(in: .whitespaces).isEmpty && model.pendingImages.isEmpty {
                    Button(action: onStop) {
                        Rectangle().fill(c.primary).frame(width: 14, height: 14)
                            .frame(width: 52, height: 52)
                            .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainerHigh))
                    }
                    .buttonStyle(PressStyle())
                    .accessibilityLabel("Stop")
                } else {
                    let enabled = !text.trimmingCharacters(in: .whitespaces).isEmpty || !model.pendingImages.isEmpty
                    Button {
                        onSend(text)
                        text = ""
                    } label: {
                        // Pointing right, as Android's Material "Send".
                        Image(systemName: "paperplane.fill")
                            .symbolSize(18, weight: .semibold)
                            .rotationEffect(.degrees(45))

                            .foregroundStyle(enabled ? c.onPrimary : c.onSurface.opacity(0.38))
                            .frame(width: 52, height: 52)
                            .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(enabled ? c.primary : c.onSurface.opacity(0.12)))
                    }
                    .buttonStyle(PressStyle())
                    .disabled(!enabled)
                    .accessibilityLabel(running ? "Queue" : "Send")
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(c.background)
        .hairlineEdge(top: true, bottom: false)
        .onChange(of: model.dictated) { _, spoken in
            guard let spoken else { return }
            text = text.trimmingCharacters(in: .whitespaces).isEmpty ? spoken : text.replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression) + " " + spoken
            model.consumeDictation()
        }
        .onChange(of: picks) { _, items in
            guard !items.isEmpty else { return }
            picks = []
            Task {
                if items.count > ChatModel.maxAttachments { model.notice = "Up to \(ChatModel.maxAttachments) images per message" }
                for item in items.prefix(ChatModel.maxAttachments) {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        await model.attach(data, app: app)
                    } else {
                        model.notice = "Couldn't load that photo (it may still be downloading from iCloud)"
                    }
                }
            }
        }
    }
}

/**
 Voice mode's composer: what the loop is doing (`// VOICE · LISTENING`), a level meter while
 listening, and one button that talks, sends, stops the agent or interrupts the answer.
 */
private struct VoicePanel: View {
    let voice: VoiceState
    let model: ChatModel
    let onType: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        let (label, hint): (String, String) = switch voice.phase {
        case .ready: ("ready", "tap to talk")
        case .listening: ("listening", voice.handsFree ? "pause to send · tap to send now" : "tap to send")
        case .transcribing: ("transcribing", "on this phone")
        case .thinking: ("thinking", "tap to stop")
        case .speaking: ("speaking", "tap to interrupt and talk")
        }
        let quiet = voice.phase == .thinking || voice.phase == .speaking
        let action = switch voice.phase {
        case .ready: "Talk"
        case .listening: "Send"
        case .transcribing: "Transcribing"
        case .thinking: "Stop"
        case .speaking: "Interrupt"
        }
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                StatusDot(color: voice.phase == .ready ? c.outline : c.primary, live: voice.phase != .ready)
                SectionLabel("voice · \(label)").inkClearance(c.background).frame(maxWidth: .infinity, alignment: .leading)
                Tag("hands-free", accent: voice.handsFree ? c.primary : nil, filled: voice.handsFree) { model.setHandsFree(!voice.handsFree) }
                Tag("type", action: onType).inkClearance(c.background)
            }
            if voice.phase == .listening || voice.phase == .transcribing {
                LiveWordsLine(words: model.heardWords).inkClearance(c.background)
            }
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Group {
                        if voice.phase == .speaking {
                            // On plain paper: its 6 pt screen over the ground's own grid read as two
                            // misaligned dot grids.
                            SpeechStrip(spoken: voice.spoken).background(c.background)
                        } else {
                            LevelMeter(levels: model.levels, listening: voice.phase == .listening)
                        }
                    }
                    .frame(height: 36)
                    MicroLabel(hint).inkClearance(c.background)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button { model.voiceTap() } label: {
                    Group {
                        switch voice.phase {
                        case .ready, .speaking: AutobotIconView(icon: .mic, size: 26, color: quiet ? c.primary : c.onPrimary)
                        case .listening: Rectangle().fill(c.onPrimary).frame(width: 16, height: 16)
                        case .transcribing: InkLoader(.button, color: c.onPrimary)
                        case .thinking: Rectangle().fill(c.primary).frame(width: 14, height: 14)
                        }
                    }
                    .frame(width: 64, height: 64)
                    .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(quiet ? c.surfaceContainerHigh : c.primary))
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .disabled(voice.phase == .transcribing)
                .accessibilityLabel(action)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .inkGround(plate: false)
        .background(c.background)
        .hairlineEdge(top: true, bottom: false)
    }
}

/**
 The words heard so far, live: settled words in ink, the still-changing tail faded. Observes
 [words] on its own, so each transcription re-renders this line only. Shows the newest
 [maxChars] characters; nothing until a word is heard.
 */
private struct LiveWordsLine: View {
    let words: HeardWords
    private let maxChars = 160
    @Environment(\.scheme) private var c

    var body: some View {
        if !words.isEmpty {
            let committed = words.committed
            let tentative = words.tentative
            let full = committed.isEmpty || tentative.isEmpty ? committed + tentative : committed + " " + tentative
            let cut = max(full.count - maxChars, 0)
            let solid = max(committed.count - cut, 0)
            let visible = full.dropFirst(cut)
            (Text(cut > 0 ? "…" : "") + Text(String(visible.prefix(solid))).foregroundStyle(c.onSurface) + Text(String(visible.dropFirst(solid))).foregroundStyle(c.onSurfaceVariant))
                .textStyle(.bodyMedium)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityLabel("Heard: \(full)")
        }
    }
}

/// Microphone peaks as riso bars, newest on the right, easing toward each new peak (no jitter);
/// a flat rule when not listening.
private struct LevelMeter: View {
    /// Observed here only: ~10 updates a second re-render the meter, not the chat screen.
    let levels: VoiceLevels
    let listening: Bool
    @Environment(\.scheme) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var bars = LevelBars()

    var body: some View {
        InkTimeline(active: listening) { date in
            Canvas { context, size in
                bars.step(levels.peaks, at: date, ease: listening && !reduceMotion)
                let slot = size.width / CGFloat(LevelBars.slots)
                var live = Path(), rule = Path()
                for i in 0 ..< LevelBars.slots {
                    let h = max(CGFloat(bars.shown[i]).squareRoot() * size.height, 2)
                    let rect = CGRect(x: CGFloat(i) * slot, y: (size.height - h) / 2, width: slot * 0.6, height: h)
                    if bars.heard[i] { live.addRect(rect) } else { rule.addRect(rect) }
                }
                context.fill(rule, with: .color(c.outlineVariant))
                context.fill(live, with: .color(c.primary))
            }
        }
        .accessibilityHidden(true)
    }
}

/// The meter's displayed bar levels, eased toward the latest peaks frame by frame.
@MainActor
private final class LevelBars {
    static let slots = 32
    private(set) var shown = [Double](repeating: 0, count: slots)
    private(set) var heard = [Bool](repeating: false, count: slots)
    private var last: Date?

    func step(_ levels: [Float], at date: Date, ease: Bool) {
        let dt = last.map { min(max(date.timeIntervalSince($0), 0), 0.1) } ?? 0
        last = date
        for i in 0 ..< Self.slots {
            let index = levels.count - Self.slots + i
            heard[i] = index >= 0
            let target = index >= 0 ? Double(min(max(levels[index], 0), 1)) : 0
            shown[i] = ease ? InkMath.approach(shown[i], target, dt: dt, timeConstant: 0.07) : target
        }
    }
}

/// While the answer is read aloud: a slow flow strip that swells with each spoken piece.
private struct SpeechStrip: View {
    let spoken: Int
    @Environment(\.scheme) private var c
    @State private var pulse: Date?

    var body: some View {
        // App UI v4: a flat running bar, no halftone.
        InkProgressBar(.indeterminate, height: 2)
            .frame(maxHeight: .infinity)
            .onChange(of: spoken, initial: true) { _, _ in self.pulse = Date() }
    }
}

/// Square thumbnails of attached images; [onRemove] adds a remove button (composer only).
private struct AttachmentStrip: View {
    let ids: [String]
    let model: ChatModel
    var onRemove: ((String) -> Void)? = nil

    var body: some View {
        HStack(spacing: 8) {
            ForEach(ids, id: \.self) { id in AttachmentThumb(id: id, model: model, onRemove: onRemove) }
        }
    }
}

private struct AttachmentThumb: View {
    let id: String
    let model: ChatModel
    let onRemove: ((String) -> Void)?
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            c.surfaceContainerHigh
            if let image { Image(uiImage: image).resizable().scaledToFill().frame(width: 64, height: 64).clipped() }
            if let onRemove {
                Button { onRemove(id) } label: {
                    Text("×").textStyle(.code).foregroundStyle(c.onBackground)
                        .padding(.horizontal, 6)
                        .background(c.background.opacity(0.8))
                }
                .buttonStyle(PressStyle())
                .accessibilityLabel("Remove image")
            }
        }
        .frame(width: 64, height: 64)
        .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall))
        .accessibilityLabel("Attached image")
        .task(id: id) { image = await model.attachmentThumbnail(id, app: app) }
    }
}

// MARK: - Sheets

private struct ModelSheet: View {
    let conversationId: String
    let routing: ChatRouting
    let onDismiss: () -> Void
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var providerId: String?
    @State private var modelId = ""
    @State private var refreshing = false
    @State private var refreshError: String?
    @State private var confirmTrains: String?

    var body: some View {
        let providers = app.providers.providers
        let selected = providers.first { $0.id == (providerId ?? routing.provider?.id) }
        let models: [RemoteModelItem] = selected.map(models) ?? []
        ScrollView {
            InkStack(alignment: .leading, spacing: 8) {
                SectionLabel("route")
                if providers.isEmpty {
                    TextAction("Add a provider", color: c.primary) {
                        onDismiss()
                        nav.push(.providers)
                    }
                }
                ForEach(providers) { option in
                    Button {
                        providerId = option.id
                        modelId = option.defaultModel
                        app.agent.setRoute(conversationId, providerId: option.id, model: option.defaultModel)
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: option.id == selected?.id ? "largecircle.fill.circle" : "circle")
                                .symbolSize(20)
                                .foregroundStyle(option.id == selected?.id ? c.primary : c.onSurfaceVariant)
                            VStack(alignment: .leading, spacing: 0) {
                                Text(option.displayName).textStyle(.titleMedium).foregroundStyle(c.onSurface)
                                Text(option.defaultModel).textStyle(.micro).foregroundStyle(c.onSurfaceVariant)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle())
                }
                Hairline().padding(.vertical, 4)
                HStack(spacing: 8) {
                    SectionLabel("model")
                    Spacer(minLength: 0)
                    if let selected { MicroLabel(refreshError ?? modelsLabel(selected), color: refreshError != nil ? c.error : nil) }
                    if let selected, canRefresh(selected) {
                        TextAction("Refresh", color: c.primary, busy: refreshing) { refresh(selected) }
                    }
                }
                ConsoleTextField(text: $modelId, label: "Model id · type to search", kind: .mono)
                // The field searches the list unless it already names one of its models.
                let query = models.contains { $0.id == modelId } ? "" : modelId
                ForEach(ModelLists.filter(models, query, limit: 30)) { m in
                    let active = m.id == modelId
                    Button { modelId = m.id } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text((active ? "● " : "") + m.id).textStyle(.code).foregroundStyle(active ? c.primary : c.onSurface).lineLimit(1)
                            if !m.caption.isEmpty { MicroLabel(m.caption) }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 3)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle())
                }
                PrimaryButton("Use model", enabled: !modelId.trimmingCharacters(in: .whitespaces).isEmpty && selected != nil) {
                    let model = modelId.trimmingCharacters(in: .whitespaces)
                    if let selected, ModelPrivacy.trains(presetId: ProviderPresets.resolve(selected).id, model: model) { confirmTrains = model; return }
                    if let selected { app.agent.setRoute(conversationId, providerId: selected.id, model: model) }
                    onDismiss()
                }
                .alert("Switch to \(confirmTrains ?? "this model")?", isPresented: Binding(get: { confirmTrains != nil }, set: { if !$0 { confirmTrains = nil } })) {
                    Button("Switch anyway") { if let selected, let m = confirmTrains { app.agent.setRoute(conversationId, providerId: selected.id, model: m) }; onDismiss() }
                    Button("Cancel", role: .cancel) {}
                } message: { Text(ModelPrivacy.trainsWarning + " Anything you send in this chat from now on may end up in a future model.") }
            }
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 24)
        }
        .onAppear { modelId = routing.selectedModel ?? "" }
        .presentationDetents([.medium, .large])
        .presentationBackground(c.surfaceContainerLow)
        .presentationDragIndicator(.visible)
    }

    /// The provider's fetched list, else its preset's offline suggestions; the default model leads.
    private func models(_ p: Provider) -> [RemoteModelItem] {
        // "This phone" lists every downloaded chat model, newest first, by title.
        var list = p.kind == .local
            ? app.library.models.filter(\.isLocalChat).sorted { $0.addedAt > $1.addedAt }.map { RemoteModelItem(id: $0.id, caption: $0.title) }
            : ModelLists.models(app.providers.modelLists[p.id]?.json, presetId: ProviderPresets.resolve(p).id)
                ?? ProviderPresets.resolve(p).suggestedModels.map { RemoteModelItem(id: $0, presetId: ProviderPresets.resolve(p).id) }
        // "This phone" also offers Apple Intelligence when it can answer.
        if p.kind == .local && AppleIntelligence.unavailability == nil { list.append(RemoteModelItem(id: AppleIntelligence.id)) }
        if p.defaultModel.isEmpty || list.contains(where: { $0.id == p.defaultModel }) { return list }
        return [RemoteModelItem(id: p.defaultModel)] + list
    }

    private func modelsLabel(_ p: Provider) -> String {
        guard let stored = app.providers.modelLists[p.id], let list = ModelLists.models(stored.json) else { return "offline list" }
        return "\(counted(list.count, "model")) · \(ModelLists.age(fetchedAt: stored.fetchedAt))"
    }

    private func canRefresh(_ p: Provider) -> Bool { p.kind != .local && ProviderPresets.resolve(p).hasModelsEndpoint }

    /// Refetches the provider's list (kill switch, route and audit log apply) and caches it.
    private func refresh(_ p: Provider) {
        guard let shared = app.agent as? SharedAgentRuntime else { return }
        refreshing = true
        refreshError = nil
        let providers = app.providers
        let spec = ProviderSpec(
            id: p.id, kind: SharedMapping.kind(p.kind), displayName: p.displayName, baseUrl: p.baseUrl,
            apiKey: providers.apiKey(p)?.reveal(), defaultModel: p.defaultModel, route: p.routing.rawValue, presetId: p.presetId, modelsJson: nil,
            helperModel: p.helperModel,
        )
        Task {
            let (json, error) = await shared.listModels(spec)
            refreshing = false
            if let json, let cache = ModelCache.companion.decode(json: json) {
                providers.saveModels(p.id, fetchedAt: cache.fetchedAt, json: json)
            } else {
                refreshError = error
            }
        }
    }
}

private struct SessionSheet: View {
    let conversationId: String
    let session: AgentSession?
    let onDelete: () -> Void
    /// Opens the "files changed" sheet.
    var onChanges: () -> Void = {}
    let onDismiss: () -> Void
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var renaming = false
    @State private var name = ""

    var body: some View {
        let settings = session?.settings ?? SessionSettings()
        let changes = session?.transcript.projection.changes ?? []
        let conversation = app.conversations.get(conversationId)
        InkStack(alignment: .leading, spacing: 14) {
            SectionLabel("chat")
            HStack(spacing: 12) {
                Text(conversation?.title ?? "").textStyle(.titleMedium).foregroundStyle(c.onSurface).lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                TextAction("Rename", color: c.primary) {
                    name = conversation?.title ?? ""
                    renaming = true
                }
            }
            HStack(spacing: 12) {
                Text("Pin to top").textStyle(.titleMedium).foregroundStyle(c.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
                RisoToggle(isOn: Binding(get: { conversation?.pinned ?? false }, set: { app.conversations.setPinned([conversationId], $0) }))
            }
            HStack(spacing: 16) {
                TextAction(conversation?.archived == true ? "Unarchive" : "Archive", color: c.primary) {
                    app.conversations.setArchived([conversationId], !(conversation?.archived ?? false))
                    onDismiss()
                }
                TextAction("Delete chat", color: c.error, action: onDelete)
            }
            SectionLabel("session")
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Agent tools").textStyle(.titleMedium).foregroundStyle(c.onSurface)
                    Text("Files in /workspace, web fetch, image generation, skills, plans. Off = plain chat.")
                        .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                    if !app.agent.supportsTools {
                        Text("This build runs plain chat: tools need the shared agent runtime.")
                            .textStyle(.bodySmall).foregroundStyle(c.inks.amber).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                RisoToggle(isOn: Binding(get: { settings.toolsEnabled }, set: { app.agent.setToolsEnabled(conversationId, $0) }))
            }
            VStack(alignment: .leading, spacing: 6) {
                MicroLabel("permission preset")
                Segmented(options: PermissionPreset.allCases, selected: settings.permissionPreset, label: \.label) {
                    app.agent.setPermission(conversationId, $0)
                }
            }
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Compact context").textStyle(.titleMedium).foregroundStyle(c.onSurface)
                    Text("Summarize older turns now (also automatic near the limit).")
                        .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                TextAction("Compact", color: c.primary, enabled: !(session?.running ?? false)) {
                    app.agent.compact(conversationId)
                    onDismiss()
                }
            }
            HStack(spacing: 12) {
                let files = changes.reduce(0) { $0 + $1.files.count }
                let turns = changes.count
                VStack(alignment: .leading, spacing: 2) {
                    Text("Files changed").textStyle(.titleMedium).foregroundStyle(c.onSurface)
                    Text(files == 0 ? "Nothing yet. Every file the agent changes can be undone."
                         : "\(files) file\(files == 1 ? "" : "s") across \(turns) turn\(turns == 1 ? "" : "s"); undo a step or a turn.")
                        .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                TextAction("View", color: c.primary, action: onChanges)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.top, 20)
        .padding(.bottom, 24)
        .presentationDetents([.height(560), .large])
        .presentationBackground(c.surfaceContainerLow)
        .presentationDragIndicator(.visible)
        .alert("Rename chat", isPresented: $renaming) {
            TextField("Name", text: $name)
            Button("Rename") { app.conversations.rename(conversationId, name) }
                .disabled(ChatNames.shared.title(title: name) == nil)
            Button("Cancel", role: .cancel) {}
        }
    }
}

/// Wrapping row of tags (Compose `FlowRow`).
struct ChatFlow: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            x += size.width + spacing
            maxX = max(maxX, x - spacing)
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: proposal.width ?? maxX, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowHeight: CGFloat = 0
        for view in subviews {
            let size = view.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

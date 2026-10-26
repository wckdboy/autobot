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
            return [ChatBanner(id: "none", text: ChatBannerWords.shared.NO_MODEL, action: "Add", target: .providers)]
        }
        var out: [ChatBanner] = []
        if showOffline {
            out.append(ChatBanner(id: "offline", text: ChatBannerWords.shared.OFFLINE, action: "Privacy", target: .privacyCenter))
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
    /// While typing only the field, the transcript and what is being answered stay (Keyboard.swift).
    @Environment(\.keyboardUp) private var keyboardUp
    @Environment(\.dynamicTypeSize) private var dynamicType
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

    /// The chat's model when it runs on this device through llama.cpp (not Apple Intelligence).
    private var localModelToPrepare: String? {
        let routing = ChatRouting(app: app, conversationId: conversationId)
        guard routing.provider?.kind == .local, let id = routing.selectedModel, id != AppleIntelligence.id else { return nil }
        return id
    }

    var body: some View {
        let _ = RenderProbe.hit("body.chat")
        // A previewed (or already open) session shows at once, before .task opens it.
        let session = opened ?? app.agent.existingSession(conversationId)
        let transcript = session?.transcript ?? Self.emptyTranscript
        let settings = session?.settings ?? SessionSettings()
        let running = session?.running ?? false
        let approvals = session?.approvals ?? []
        let isLive = session?.isLive ?? false
        let routing = ChatRouting(app: app, conversationId: conversationId)
        let header = ["run", routing.modelLabel ?? routing.selectedModel, transcript.contextUsed.map { "\(compactCount($0)) tok" }].compactMap { $0 }.joined(separator: " · ")
        let speakingKey = model.speakingKey
        let planShown = transcript.projection.todos.contains(where: { $0.status != .completed })

        VStack(spacing: 0) {
            // One banner at a time: a notice answers what was just done, else the most pressing one.
            if let notice = model.notice {
                Banner(text: notice, action: "OK") { model.notice = nil }
            } else if Composer.shared.shows(part: .banner, keyboardUp: keyboardUp), let b = banners(routing).first {
                Banner(text: b.text, action: b.action) {
                    switch b.target {
                    case .providers: nav.push(.providers)
                    case .privacyCenter: nav.push(.privacyCenter)
                    case .models: nav.reset(.models)
                    }
                }
            }

            ScrollViewReader { proxy in
                ScrollView {
                    TranscriptStack(lazy: !TranscriptStack<EmptyView>.eager(rows: transcript.items.count, dynamicType: dynamicType)) {
                        transcriptRows(session: session, transcript: transcript, settings: settings, running: running, approvals: approvals, isLive: isLive, routing: routing, header: header, speakingKey: speakingKey)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .readableWidth()
                }
                .defaultScrollAnchor(.bottom)
                // The plan panel and a question card take room from the transcript as they come and
                // go: the end of the answer stays in view instead of sliding under them.
                .defaultScrollAnchor(.bottom, for: .sizeChanges)
                .scrollDismissesKeyboard(.interactively)
                // The bottom stack rides on the keyboard; the transcript's end stays right above it.
                .safeAreaInset(edge: .bottom, spacing: 0) { bottomStack(session: session, transcript: transcript, settings: settings, running: running, planShown: planShown, onNode: routing.provider?.kind == .invincibleNode) }
                // Generative UI follows every new transcript: a sent form locks, an updated one unlocks.
                .onChange(of: transcript, initial: true) { _, t in model.a2ui.sync(t) }
                .onChange(of: transcript.items.last?.key) { _, _ in withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
                .onChange(of: isLive) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: planShown) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
                .onChange(of: keyboardUp) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
                // A chat opened with its transcript already there: its end once laid out (images and
                // the lazy rows' real heights arrive after the first pass, where the anchor was set).
                .task(id: conversationId) {
                    try? await Task.sleep(for: .milliseconds(350))
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
                .onChange(of: session?.question?.callId) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
            }
        }
        .background(Color(.systemBackground))
        .navigationTitle(settings.toolsEnabled ? "Agent run" : "Chat")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                TitleWithDot(settings.toolsEnabled ? "Agent run" : "Chat")
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    model.setVoiceMode(model.voice == nil, app: app, conversationId: conversationId)
                } label: {
                    Label("Voice", systemImage: model.voice != nil ? "waveform.circle.fill" : "waveform")
                }
                .accessibilityValue(model.voice != nil ? "On" : "Off")
                Button { showModels = true } label: { RouteBadge(route: routing.route) }
                    .accessibilityLabel("Model and route: \(routing.route.label)")
                Button { showSession = true } label: { Label("Session settings", systemImage: "ellipsis") }
            }
        }
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
            CascadeSheet(policy: settings.cascade, model: routing.modelLabel ?? routing.selectedModel ?? "", node: routing.provider?.kind == .invincibleNode) {
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


    /// The transcript's rows (the empty state, the items, the live answer, the cascade's offer, the end marker).
    @ViewBuilder
    private func transcriptRows(session: AgentSession?, transcript: SessionTranscript, settings: SessionSettings, running: Bool, approvals: [ApprovalState], isLive: Bool, routing: ChatRouting, header: String, speakingKey: String?) -> some View {
        if app.dataLoaded && transcript.items.isEmpty && !isLive && Composer.shared.shows(part: .decor, keyboardUp: keyboardUp) {
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
                running: running,
                speaking: speakingKey == item.key,
                conversationId: conversationId,
                model: model,
                openReceipt: $openReceipt,
            )
            .equatable()
        }
        if let session { LiveSlot(session: session) }
        // The cascade's offer sits under the phone's answer. "Ask" goes to the chat's model as it is
        // now, and never to a cloud model under "Never" (an offer made on a node chat outlives a
        // switch; the shared LocalFirstCascade.escalate refuses it, CascadePolicy.allowsAsk).
        if let offer = transcript.projection.cascadeOffer, !running, !isLive, settings.cascade != "NEVER" || routing.provider?.kind == .invincibleNode {
            CascadeOfferRow(offer: offer, model: routing.modelLabel ?? routing.selectedModel ?? "", node: routing.provider?.kind == .invincibleNode) {
                app.agent.escalate(conversationId)
            }
            .id("cascade-offer:\(offer.turn)")
        }
        Color.clear.frame(height: 1).id("bottom")
    }

    /// Plan, question, chips and the composer, under the transcript. While typing only the
    /// question being answered and the composer stay. [onNode]: the chat's model is the user's own node.
    @ViewBuilder
    private func bottomStack(session: AgentSession?, transcript: SessionTranscript, settings: SessionSettings, running: Bool, planShown: Bool, onNode: Bool) -> some View {
        VStack(spacing: 0) {
            if planShown && Composer.shared.shows(part: .plan, keyboardUp: keyboardUp) {
                TodoPanel(todos: transcript.projection.todos)
            }
            if let question = session?.question {
                QuestionCard(question: question) { app.agent.answerQuestion(conversationId, callId: question.callId, answers: $0) }
            }
            if Composer.shared.shows(part: .chips, keyboardUp: keyboardUp) {
                PolicyLine(
                    settings: settings, contextUsed: transcript.contextUsed, contextWindow: transcript.projection.contextWindow, onNode: onNode,
                    onThink: { app.agent.setThinking(conversationId, settings.thinking.next) },
                    onThinkSheet: { showThink = true },
                    onRedact: { app.agent.setRedaction(conversationId, settings.redaction != "ON") },
                    onCascade: { showCascade = true },
                )
            }
            if let voice = model.voice {
                VoicePanel(voice: voice, model: model) { model.setVoiceMode(false, app: app, conversationId: conversationId) }
            } else {
                InputBar(model: model, draftKey: conversationId, running: running, onSend: send, onStop: stop).equatable()
            }
        }
        // One glass panel floats over the transcript's end: the plan, a question, the chips and the composer.
        .composerPanel()
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
        // A follow-up queued behind a running turn is not timed: the running answer's next render would take its mark.
        let session = app.agent.existingSession(conversationId)
        if !(session?.running ?? false) && !(session?.isLive ?? false) { ChatTiming.sent() }
        Task { await app.agent.send(conversationId, text: input, images: images) }
    }

    /// Stops the agent; queued turns are dropped, so a form sent behind the running turn unlocks.
    private func stop() {
        app.agent.cancel(conversationId)
        model.a2ui.stopped()
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
            stop()
        default:
            return false
        }
        return true
    }
}

// MARK: - Transcript

/**
 The transcript's rows, lazily, except a short chat at the accessibility text sizes: there, with
 a tall composer panel, the lazy stack could settle at the transcript's end without drawing the
 rows (a blank chat until the first scroll). Laid out whole only then and only up to
 [eagerLimit] rows: whole, a chat of heavy markdown costs about 15 ms and 1.5 MB per row up front
 (40 rows: ~650 ms and ~60 MB against ~150 ms and ~6 MB lazily), so every other chat stays lazy.
 */
struct TranscriptStack<Content: View>: View {
    static var eagerLimit: Int { 16 }

    static func eager(rows: Int, dynamicType: DynamicTypeSize) -> Bool {
        dynamicType.isAccessibilitySize && rows <= eagerLimit
    }

    let lazy: Bool
    @ViewBuilder var content: Content

    var body: some View {
        if lazy {
            LazyVStack(alignment: .leading, spacing: 10) { content }
        } else {
            VStack(alignment: .leading, spacing: 10) { content }
        }
    }
}

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
    let running: Bool
    let speaking: Bool
    let conversationId: String
    let model: ChatModel
    @Binding var openReceipt: Receipt?
    /// "Select text" from the long-press menu: the message in a sheet where any part can be selected.
    @State private var selecting: SelectableText?
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav

    nonisolated static func == (a: Self, b: Self) -> Bool {
        a.item == b.item && a.retryPrompt == b.retryPrompt && a.number == b.number && a.header == b.header && a.approval == b.approval
            && a.running == b.running && a.speaking == b.speaking && a.conversationId == b.conversationId && a.model === b.model
    }

    var body: some View {
        switch item {
        case .user(_, let text, let images):
            VStack(alignment: .leading, spacing: 8) {
                if let header {
                    RunHeader(prompt: text, label: header)
                } else {
                    PromptLine(text: text)
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
                ApprovalCard(number: number, tool: tool, approval: approval) { outcome in
                    app.agent.answerApproval(conversationId, callId: tool.callId, outcome: outcome)
                }
            } else if tool.a2ui != nil {
                // Generative UI (A2UI): a `show_ui` call draws its surfaces in place of its step row.
                A2uiToolSurfaces(chat: model.a2ui, conversationId: conversationId, itemKey: tool.key) { model.notice = $0 }
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

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(prompt).font(.title2.weight(.semibold)).foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
        }
        .padding(.bottom, 8)
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

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let reasoning, !reasoning.isEmpty { ThinkingDisclosure(reasoning: reasoning, tokens: thinking?.tokens, stop: thinking?.stop) }
            if !text.isEmpty {
                ChatBubble(text: text) {
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
        if interrupted { CapsuleTag("interrupted", color: .orange) }
        if let outputTokens { TokenRateLabel(totalTokens: outputTokens) }
    }

    @ViewBuilder private var footerActions: some View {
        // Copies the message as written (markdown), not the rendered text.
        CopyMessageButton(markdown: text)
        ShareMessageButton(markdown: text)
        if let onRetry {
            Button(action: onRetry) { Image(systemName: "arrow.clockwise") }
                .messageAction()
                .accessibilityLabel("Retry")
        }
        Button(action: onSpeak) { Image(systemName: speaking ? "speaker.wave.2.fill" : "speaker.wave.2") }
            .messageAction(active: speaking)
            .accessibilityLabel(speaking ? "Stop reading" : "Read aloud")
    }
}

private struct LiveMessage: View {
    let live: LiveState

    var body: some View {
        let _ = RenderProbe.hit("body.liveMessage")
        let _ = ChatTiming.shown(live.text)
        VStack(alignment: .leading, spacing: 6) {
            if !live.reasoning.isEmpty {
                ThinkingDisclosure(
                    reasoning: live.reasoning, isThinking: live.text.isEmpty && live.reasoningDone != true,
                    tokens: live.reasoningTokens, stop: live.reasoningStop,
                )
            }
            if !live.text.isEmpty || live.reasoning.isEmpty {
                if let rate = live.tokensPerSecond {
                    ChatBubble(text: live.text, isStreaming: true) { TokenRateLabel(tokensPerSecond: rate) }
                } else {
                    ChatBubble(text: live.text, isStreaming: true)
                }
            }
            ForEach(Array(live.toolNames.enumerated()), id: \.offset) { _, name in
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text(stepTitle(name)).font(.subheadline.weight(.semibold))
                    Text("composing…").font(.caption).foregroundStyle(.secondary)
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
        case .running, .streaming, .waitingApproval: BrandColors.purple
        case .error: .red
        case .ok, .denied: .secondary
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
        let _ = RenderProbe.hit("body.stepRow")
        let (mark, markColor): (String, Color) = switch tool.state {
        case .ok: (tool.isUndone ? "undone" : "✓", .secondary)
        case .error: ("×", .red)
        case .denied: ("denied", .secondary)
        case .running, .streaming, .waitingApproval: ("", .accentColor)
        }
        VStack(alignment: .leading, spacing: 0) {
            Button { if tool.output != nil { expanded.toggle() } } label: {
                HStack(alignment: .top, spacing: 12) {
                    // The terminal's `▪`, drawn (a text ▪ turns into an emoji on iOS).
                    Rectangle().fill(stepMarkColor).frame(width: 6, height: 6)
                        .padding(.top, 9)
                        .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(stepTitle(tool.name)).font(.subheadline.weight(.semibold)).strikethrough(tool.isUndone).foregroundStyle(.primary)
                        HStack(spacing: 8) {
                            Text(StepGrammar.detail(name: tool.name, summary: tool.summary))
                                .font(.caption.monospaced())
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            if let change = tool.change { ChangeCounts(change: change) }
                        }
                        // Why a step failed shouldn't take a tap to find out.
                        if !expanded, tool.state == .error || tool.state == .denied,
                           let reason = tool.output?.split(whereSeparator: \.isNewline).first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }),
                           !["denied", "error: denied"].contains(reason.lowercased()) {
                            Text(reason.hasPrefix("Error: ") ? String(reason.dropFirst(7)) : String(reason))
                                .font(.footnote)
                                .foregroundStyle(tool.state == .error ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // The mark keeps its identity so it can stamp in when the step settles.
                    ZStack(alignment: .topTrailing) {
                        Text(mark + (tool.durationMs.map { String(format: " %.1f s", Double($0) / 1000) } ?? ""))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(markColor)
                            .contentTransition(.opacity)
                            .animation(.easeOut(duration: 0.2), value: StampKey(state: tool.state, undone: tool.isUndone))
                        if mark.isEmpty { ProgressView().controlSize(.small).padding(.top, 2) }
                    }
                }
                .padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if expanded, let steps = tool.steps, !steps.isEmpty {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(steps.enumerated()), id: \.offset) { _, step in
                        Text(step).font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1)
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
                        Button { if !running { onRedo(redo) } } label: { Label("Redo", systemImage: "arrow.uturn.forward") }
                            .buttonStyle(.bordered).buttonBorderShape(.capsule).controlSize(.small)
                    } else if !tool.isUndone && change.undoable {
                        Button { if !running { onUndo(tool.callId) } } label: { Label("Undo step", systemImage: "arrow.uturn.backward") }
                            .buttonStyle(.bordered).buttonBorderShape(.capsule).controlSize(.small)
                    }
                }
                .padding(.bottom, 6)
            } else if expanded, let output = tool.output {
                Text(String(output.prefix(4000)))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(10)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .textSelection(.enabled)
            }
            if tool.name == "generate_image" && (tool.state == .running || !tool.galleryIds.isEmpty) {
                // The agent's job: a spinner and the latent preview, then its first image.
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
            Divider()
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

    var body: some View {
        if UnsavedImages.isUnsaved(id) && app.gallery.unsaved.get(id) != nil || app.gallery.keptIncognito[id] != nil {
            if app.gallery.keptIncognito[id] != nil {
                Text(PanicCopy.shared.SAVED).font(.caption).foregroundStyle(.secondary)
            } else {
                HStack(spacing: 6) {
                    Text(PanicCopy.shared.NOT_SAVED).font(.caption).foregroundStyle(.secondary)
                    Button(PanicCopy.shared.SAVE) { Task { try? await app.gallery.keep(id) } }
                        .buttonStyle(.bordered)
                        .buttonBorderShape(.capsule)
                        .controlSize(.mini)
                }
            }
        }
    }
}

private struct GalleryThumb: View {
    let id: String
    let model: ChatModel
    @Environment(AppContainer.self) private var app
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            Color(.secondarySystemBackground)
            if let image { Image(uiImage: image).resizable().scaledToFill() }
        }
        .frame(width: 120, height: 120)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .accessibilityLabel("Generated image")
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Opens the image full screen")
        .task(id: id) { image = await model.galleryThumbnail(id, app: app) }
    }
}

/// `generate_image` in the transcript: a spinner (and the latent preview) while the agent's job
/// runs, then its first image.
private struct AgentProof: View {
    let tool: SessionItem.Tool
    let model: ChatModel
    @Environment(AppContainer.self) private var app
    @State private var image: UIImage?
    @State private var preview: UIImage?

    var body: some View {
        let id = tool.galleryIds.first
        let job: (step: Int, total: Int, preview: Data?)? = {
            if case .running(let source, _, _, _, let step, let total, let preview, _) = app.generator.state, source == .agent { return (step, total, preview) }
            return nil
        }()
        let shape = RoundedRectangle(cornerRadius: 12, style: .continuous)
        ZStack {
            shape.fill(.fill.tertiary)
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else if let preview {
                Image(uiImage: preview).resizable().scaledToFill()
            }
            if image == nil, id == nil, tool.state == .running {
                if let job, job.total > 0, app.generator.phase == nil {
                    ProgressView(value: Double(job.step), total: Double(job.total))
                        .progressViewStyle(.circular)
                } else {
                    ProgressView()
                }
            }
        }
        .frame(width: 120, height: 120)
        .clipShape(shape)
        .accessibilityElement()
        .accessibilityLabel(id != nil ? "Generated image" : "Generating image")
        .task(id: job?.preview) {
            guard let data = job?.preview, let decoded = await decodeImage(data, maxSide: 512), !Task.isCancelled else { return }
            preview = decoded
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

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label(StepGrammar.approvalNeeded, systemImage: "hand.raised.fill")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tint)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text((privateData.isEmpty ? approval.reason ?? "" : "private data")).font(.caption).foregroundStyle(.secondary)
            }
            Text(stepTitle(tool.name)).font(.title3.weight(.semibold))
            Text(marked("\(tool.name)(\(approval.summary))")).font(.callout.monospaced()).fixedSize(horizontal: false, vertical: true)
            if !privateData.isEmpty {
                Text(marked(approval.reason ?? "")).font(.subheadline).fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 8) {
                Button { onDecide(.rejected) } label: { Text("Deny").frame(maxWidth: .infinity) }
                    .glassButton()
                Button { onDecide(.allowedOnce) } label: { Text("Approve").frame(maxWidth: .infinity) }
                    .glassProminentButton()
            }
            .controlSize(.large)
            Button("Always allow \(tool.name) in this run") { onDecide(.allowedForSession) }
                .font(.footnote)
                .frame(minHeight: 44)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.tint, lineWidth: 1.5))
    }

    private var privateData: [String] { (approval.privateData ?? []).filter { !$0.isEmpty } }

    /// `text` with every case-insensitive occurrence of a private fragment in reverse.
    private func marked(_ text: String) -> AttributedString {
        var out = AttributedString(text)
        for fragment in privateData {
            var rest = out.startIndex..<out.endIndex
            while let range = out[rest].range(of: fragment, options: .caseInsensitive) {
                out[range].backgroundColor = Color.brandPurple.opacity(0.22)
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

    var body: some View {
        if kind == .error {
            VStack(alignment: .leading, spacing: 6) {
                Label {
                    Text(text)
                        .lineLimit(expanded ? nil : 3)
                        .textSelection(.enabled)
                } icon: {
                    Image(systemName: "xmark.octagon.fill")
                }
                .font(.footnote)
                .foregroundStyle(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { expanded.toggle() }
                if let onRetry {
                    Button("Retry", action: onRetry).glassButton().controlSize(.small)
                }
            }
        } else {
            Text(text.sentenceCased).font(.caption).foregroundStyle(kind == .warn ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary)).lineLimit(3)
        }
    }
}

private struct EmptySession: View {
    let toolsEnabled: Bool

    var body: some View {
        MarkUnavailable(toolsEnabled ? "What should it do?" : "Ask anything.", description: toolsEnabled ? "Agent" : "Chat")
            .padding(.vertical, 48)
    }
}

/// `POLICY · WORKSPACE · ASKS: DELETE, NETWORK` (as on Android), with the Think chip when the
/// chat's model reasons and the REDACT chip on a cloud route.
struct PolicyLine: View {
    @Environment(\.dynamicTypeSize) private var dynamicType
    let settings: SessionSettings
    let contextUsed: Int?
    let contextWindow: Int?
    /// The chat's model is the user's own node: the cascade chip says what happens there.
    var onNode = false
    var onThink: () -> Void = {}
    var onThinkSheet: () -> Void = {}
    var onRedact: () -> Void = {}
    var onCascade: () -> Void = {}

    @ViewBuilder private var chips: some View {
        chips(wrapping: false)
    }

    /// [wrapping]: a chip may break its words over lines (one chip per line on a narrow phone at the
    /// largest sizes, where even "Phone first · ask" alone is wider than the screen).
    @ViewBuilder private func chips(wrapping: Bool) -> some View {
        if settings.thinkingSupport != "NONE" { ThinkChip(choice: settings.thinking, onTap: onThink, onLongPress: onThinkSheet).fixedSize(horizontal: !wrapping, vertical: true) }
        if settings.redaction != "NONE" { RedactChip(on: settings.redaction == "ON", onTap: onRedact).fixedSize(horizontal: !wrapping, vertical: true) }
        if settings.cascade != "NONE" { CascadeChip(policy: settings.cascade, onNode: onNode, onTap: onCascade).fixedSize(horizontal: !wrapping, vertical: true) }
    }

    @ViewBuilder private var context: some View {
        if settings.toolsEnabled, let used = contextUsed, let window = contextWindow, window > 0 {
            Text("\(compactCount(used))/\(compactCount(window))").font(.caption.monospacedDigit()).foregroundStyle(.secondary).fixedSize()
        }
    }

    private func oneRow(_ text: String) -> some View {
        HStack(spacing: 8) {
            chips
            if settings.toolsEnabled { Text(text).font(.caption).foregroundStyle(.secondary).fixedSize() }
            Spacer(minLength: 0)
            context
        }
    }

    private func policyUnderChips(_ text: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                chips
                Spacer(minLength: 0)
                context
            }
            // Two lines, all of it at the accessibility sizes.
            if settings.toolsEnabled { Text(text).font(.caption).foregroundStyle(.secondary).lineLimit(dynamicType.isAccessibilitySize ? nil : 2) }
        }
    }

    var body: some View {
        let think = settings.thinkingSupport != "NONE"
        let redact = settings.redaction != "NONE"
        let cascade = settings.cascade != "NONE"
        if settings.toolsEnabled || think || redact || cascade {
            let text = switch settings.permissionPreset {
            case .readOnly: "Policy · read-only · asks: network"
            case .workspace: "Policy · workspace · asks: delete, network"
            case .fullAccess: "Policy · full access"
            }
            let chipCount = [think, redact, cascade].filter { $0 }.count
            // One row when it fits; on a narrow phone the policy moves under the chips rather than
            // squeezing them or cutting itself short; when even the chips are wider than the screen
            // (two or three of them on a 375 pt phone, or the accessibility sizes), one chip per
            // line, each free to wrap: a fixed-size row widened the whole chat past the screen's
            // edges. That last option only where it can be needed: measured on every chat, a third
            // ViewThatFits candidate added ~25 ms to opening one.
            Group {
                if chipCount >= 2 || dynamicType.isAccessibilitySize {
                    ViewThatFits(in: .horizontal) {
                        oneRow(text)
                        policyUnderChips(text)
                        VStack(alignment: .leading, spacing: 6) {
                            chips(wrapping: true)
                            context
                            if settings.toolsEnabled { Text(text).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
                        }
                    }
                } else {
                    ViewThatFits(in: .horizontal) {
                        oneRow(text)
                        policyUnderChips(text)
                    }
                }
            }
            .padding(.horizontal, 14)
            .padding(.top, 10)
            .padding(.bottom, 2)
        }
    }
}

/// `∴ THINK · AUTO`: tap cycles auto → on → off; a long press opens the budget sheet.
private struct ThinkChip: View {
    let choice: ThinkingChoice
    let onTap: () -> Void
    let onLongPress: () -> Void

    var body: some View {
        let active = choice.mode != "OFF"
        Label(choice.chipLabel.prefix(1).uppercased() + choice.chipLabel.dropFirst(), systemImage: "brain")
            .chipLook(active: active)
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

    var body: some View {
        Label(on ? "Redact" : "Redact off", systemImage: on ? "eye.slash.fill" : "eye")
            .chipLook(active: on)
            .onTapGesture(perform: onTap)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Redaction")
            .accessibilityValue(on ? "On: names, emails and numbers are hidden from the cloud" : "Off")
            .accessibilityHint("Tap to switch it for this chat.")
            .accessibilityAddTraits(.isButton)
    }
}

/// `▮ PHONE FIRST · ASK` while this phone answers a plain chat on a cloud model or the user's own
/// node ([onNode]) first, `PHONE FIRST OFF` dimmed otherwise (the shared `CascadePolicy` chip
/// words). A tap opens the choice for this chat.
private struct CascadeChip: View {
    let policy: String
    let onNode: Bool
    let onTap: () -> Void

    var body: some View {
        let p = CascadePolicy.companion.parse(value: policy) ?? CascadePolicy.off
        let on = p.localFirst
        let chip = onNode ? p.nodeChip : p.chip
        Label(chip.prefix(1).uppercased() + chip.dropFirst(), systemImage: "iphone")
            .chipLook(active: on)
            .onTapGesture(perform: onTap)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Phone first")
            .accessibilityValue("\(p.title). \(onNode ? p.nodeDetail : p.detail)")
            .accessibilityHint("Tap to choose for this chat.")
            .accessibilityAddTraits(.isButton)
    }
}

/// Under the phone's answer when the judge was not convinced: why, and "Ask <model>".
/// [node]: the chat's model is the user's own node ("Ask my node"), which gets the prompt marked private.
struct CascadeOfferRow: View {
    let offer: CascadeOffer
    let model: String
    let node: Bool
    let onAsk: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Phone first", systemImage: "iphone").font(.caption.weight(.semibold)).foregroundStyle(.tint)
            Text(offer.text).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            Button(CascadeLabels.shared.ask(model: model, node: node), action: onAsk)
                .glassProminentButton()
                .controlSize(.small)
                .accessibilityHint(node ? "Sends this prompt to your node, marked private." : "Sends this prompt to the cloud model, redacted when redaction is on.")
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// The chat's "phone first" policy: off, ask, auto or never, each with its line, and the scope.
/// [node]: the chat's model is the user's own node.
struct CascadeSheet: View {
    let policy: String
    let model: String
    let node: Bool
    let onChoose: (String) -> Void
    @State private var current: String

    init(policy: String, model: String, node: Bool, onChoose: @escaping (String) -> Void) {
        self.policy = policy
        self.model = model
        self.node = node
        self.onChoose = onChoose
        _current = State(initialValue: policy)
    }

    var body: some View {
        let chosen = CascadePolicy.companion.parse(value: current) ?? CascadePolicy.off
        NavigationStack {
            Form {
                Section {
                    Picker("Phone first", selection: Binding(get: { chosen.name }, set: { name in
                        current = name
                        onChoose(name)
                    })) {
                        ForEach(CascadePolicy.entries, id: \.name) { p in Text(p.title).tag(p.name) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                } footer: {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(node ? chosen.nodeDetail : chosen.detail)
                        Text(CascadeLabels.shared.sheetNote(model: model, node: node))
                    }
                }
            }
            .navigationTitle("Phone first")
            .navigationBarTitleDisplayMode(.inline)
        }
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

    init(choice: ThinkingChoice, modelDefault: ThinkingChoice?, onChoose: @escaping (ThinkingChoice) -> Void, onDefault: @escaping (ThinkingChoice?) -> Void) {
        self.choice = choice
        self.modelDefault = modelDefault
        self.onChoose = onChoose
        self.onDefault = onDefault
        _current = State(initialValue: choice)
        _saved = State(initialValue: modelDefault)
    }

    private static let modes = [("OFF", "Off"), ("AUTO", "Auto"), ("ON", "On")]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Think", selection: Binding(get: { current.mode }, set: { mode in
                        current = ThinkingChoice(mode: mode, budget: mode == "ON" ? current.budget : nil)
                        onChoose(current)
                    })) {
                        ForEach(Self.modes, id: \.0) { Text($0.1).tag($0.0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                }
                if current.mode == "ON" {
                    Section("Budget") {
                        Picker("Budget", selection: Binding(get: { current.budget }, set: { budget in
                            current = ThinkingChoice(mode: "ON", budget: budget)
                            onChoose(current)
                        })) {
                            ForEach(ThinkingChoice.budgets, id: \.self) { budget in
                                Text(budget.map { ThinkingChoice.tokens($0) } ?? "model").tag(budget)
                            }
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                    }
                }
                Section {
                    LabeledContent("Model default", value: saved.map { $0.chipLabel.replacingOccurrences(of: "think · ", with: "") } ?? "auto")
                    Button(saved == current ? "Clear default" : "Set as default") {
                        let next: ThinkingChoice? = saved == current ? nil : current
                        saved = next
                        onDefault(next)
                    }
                }
            }
            .navigationTitle("Think")
            .navigationBarTitleDisplayMode(.inline)
        }
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

struct Banner: View {
    let text: String
    let action: String
    let onAction: () -> Void
    @Environment(\.dynamicTypeSize) private var dynamicType

    private var words: some View {
        Label(text, systemImage: "exclamationmark.circle")
            .font(.footnote)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var button: some View {
        Button(action, action: onAction).glassButton().controlSize(.small)
    }

    var body: some View {
        // At the accessibility sizes the button goes under the words: beside them it left a column
        // a word or two wide, broken mid-word and cut short.
        Group {
            if dynamicType.isAccessibilitySize {
                // Every line of the words, however tall (only here: a fixed vertical size at every
                // size added ~15 ms to opening a chat).
                VStack(alignment: .leading, spacing: 8) { words.fixedSize(horizontal: false, vertical: true); button }
            } else {
                HStack(spacing: 12) { words; button }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color(.secondarySystemBackground))
    }
}

// MARK: - Bottom stack

private struct TodoPanel: View {
    let todos: [TodoItem]
    @State private var open = true

    var body: some View {
        let done = todos.filter { $0.status == .completed }.count
        DisclosureGroup(isExpanded: $open) {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(Array(todos.enumerated()), id: \.offset) { _, todo in
                    let (symbol, style): (String, AnyShapeStyle) = switch todo.status {
                    case .completed: ("checkmark.circle.fill", AnyShapeStyle(.secondary))
                    case .inProgress: ("play.circle.fill", AnyShapeStyle(.tint))
                    case .pending: ("circle", AnyShapeStyle(.secondary))
                    }
                    Label {
                        Text(todo.content).foregroundStyle(todo.status == .completed ? AnyShapeStyle(.secondary) : AnyShapeStyle(.primary)).lineLimit(2)
                    } icon: {
                        Image(systemName: symbol).foregroundStyle(style)
                    }
                    .font(.footnote)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 6)
        } label: {
            Text("Plan · \(done)/\(todos.count)").font(.subheadline.weight(.semibold))
        }
        .padding(.horizontal, 14)
        .padding(.top, 10)
    }
}

private struct QuestionCard: View {
    let question: QuestionState
    let onAnswer: ([String]?) -> Void
    @State private var free = ""
    @State private var picked: [String] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("Agent asks", systemImage: "questionmark.bubble").font(.caption.weight(.semibold)).foregroundStyle(.tint)
                Spacer()
                Button("Skip") { onAnswer(nil) }.controlSize(.small)
            }
            Text(question.question).font(.headline).fixedSize(horizontal: false, vertical: true)
            if !question.options.isEmpty {
                ChatFlow(spacing: 6) {
                    ForEach(question.options, id: \.self) { option in
                        let active = picked.contains(option)
                        let button = Button(option) {
                            if question.multiSelect {
                                if active { picked.removeAll { $0 == option } } else { picked.append(option) }
                            } else {
                                onAnswer([option])
                            }
                        }
                        .buttonBorderShape(.capsule)
                        .controlSize(.small)
                        .accessibilityAddTraits(active ? .isSelected : [])
                        if active { button.buttonStyle(.borderedProminent) } else { button.buttonStyle(.bordered) }
                    }
                }
            }
            HStack(alignment: .center, spacing: 8) {
                TextField("or type an answer", text: $free)
                    .textFieldStyle(.roundedBorder)
                let answer = picked + (free.trimmingCharacters(in: .whitespaces).isEmpty ? [] : [free.trimmingCharacters(in: .whitespaces)])
                Button("Send") { onAnswer(answer) }
                    .glassProminentButton()
                    .disabled(answer.isEmpty)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .onChange(of: question.callId) { _, _ in
            free = ""
            picked = []
        }
    }
}

private struct InputBar: View, Equatable {
    let model: ChatModel
    /// The chat whose unsent text this composer keeps ([ComposerDrafts]).
    let draftKey: String
    let running: Bool
    let onSend: (String) -> Void
    let onStop: () -> Void

    /// The actions reach stable objects (the screen's agent and conversation): not compared.
    nonisolated static func == (a: Self, b: Self) -> Bool { a.model === b.model && a.draftKey == b.draftKey && a.running == b.running }
    @Environment(AppContainer.self) private var app
    @State private var text = ""
    @State private var picks: [PhotosPickerItem] = []
    @FocusState private var focused: Bool
    @Environment(\.dynamicTypeSize) private var dynamicType

    private var large: Bool { dynamicType.isAccessibilitySize }

    /// Attach and dictate.
    @ViewBuilder private var tools: some View {
        let attached = model.pendingImages.count
        PhotosPicker(selection: $picks, maxSelectionCount: max(1, ChatModel.maxAttachments - attached), matching: .images) {
            ComposerToolLabel(systemImage: "photo.on.rectangle")
        }
        .disabled(attached >= ChatModel.maxAttachments)
        .accessibilityLabel("Attach image")
        Button { model.toggleDictation(app: app) } label: {
            Group {
                switch model.dictation {
                case .idle: Image(systemName: "mic")
                case .recording: Image(systemName: "stop.circle.fill").foregroundStyle(.tint)
                case .transcribing: ProgressView()
                }
            }
            .font(.title3)
            // At least 40 pt: the symbol grows with the text and must not spill over the row above.
            .frame(minWidth: 40, minHeight: 40)
            .contentShape(Rectangle())
        }
        .disabled(model.dictation == .transcribing)
        .accessibilityLabel(model.dictation == .recording ? "Stop dictation" : "Dictate")
    }

    var body: some View {
        let attached = model.pendingImages.count
        let action = Composer.shared.action(text: text, attachments: Int32(attached), running: running)
        VStack(spacing: 0) {
            if !model.pendingImages.isEmpty {
                AttachmentStrip(ids: model.pendingImages, model: model) { model.removeAttachment($0, app: app) }
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            if text.hasPrefix("/") {
                let typed = String(text.split(separator: " ", maxSplits: 1).first ?? "")
                VStack(alignment: .leading, spacing: 6) {
                    ForEach(commands.filter { $0.0.hasPrefix(typed) }, id: \.0) { cmd, help in
                        Button { text = String(cmd.split(separator: " ").first ?? "") + " " } label: {
                            HStack(spacing: 10) {
                                Text(cmd).font(.footnote.monospaced()).foregroundStyle(.tint).lineLimit(1).fixedSize()
                                Text(help).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                                Spacer(minLength: 0)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                Divider()
            }
            if model.dictation != .idle {
                LiveWordsLine(words: model.heardWords)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
            }
            // A large font leaves the field too narrow beside attach and dictate: they take a row above it (as on Android).
            if large {
                HStack(spacing: 8) { tools }
                    .padding(.horizontal, 8)
                    .padding(.top, 4)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(alignment: .bottom, spacing: 4) {
                if !large { tools }
                ComposerField(text: $text, placeholder: Composer.shared.placeholder(running: running, node: false), focused: $focused)
                if action == .stop {
                    ComposerStopButton(action: onStop)
                } else {
                    ComposerSendButton(enabled: Composer.shared.enabled(action: action, text: text, attachments: Int32(attached), ready: true), label: Composer.shared.label(action: action)) {
                        onSend(text)
                        text = ""
                    }
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
        }
        .task { if DebugFlags.keyboard { try? await Task.sleep(for: .milliseconds(600)); focused = true } }
        // The words typed before leaving the chat (or switching voice mode on and off) come back.
        .onAppear { if text.isEmpty { text = app.drafts.text(draftKey) } }
        .onChange(of: text) { _, now in app.drafts.set(draftKey, now) }
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
                Image(systemName: "circle.fill")
                    .font(.system(size: 8))
                    .foregroundStyle(voice.phase == .ready ? AnyShapeStyle(.tertiary) : AnyShapeStyle(.tint))
                    .symbolEffect(.pulse, isActive: voice.phase != .ready)
                    .accessibilityHidden(true)
                Text("Voice · \(label)").font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
                Toggle("Hands-free", isOn: Binding(get: { voice.handsFree }, set: { model.setHandsFree($0) }))
                    .toggleStyle(.button)
                    .buttonBorderShape(.capsule)
                    .controlSize(.small)
                Button("Type", action: onType)
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.capsule)
                    .controlSize(.small)
            }
            if voice.phase == .listening || voice.phase == .transcribing {
                LiveWordsLine(words: model.heardWords)
            }
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Group {
                        if voice.phase == .speaking {
                            SpeechStrip(spoken: voice.spoken)
                        } else {
                            LevelMeter(levels: model.levels, listening: voice.phase == .listening)
                        }
                    }
                    .frame(height: 36)
                    Text(hint.prefix(1).uppercased() + hint.dropFirst()).font(.caption).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                let button = Button { model.voiceTap() } label: {
                    Group {
                        switch voice.phase {
                        case .ready, .speaking: Image(systemName: "mic.fill")
                        case .listening: Image(systemName: "stop.fill")
                        case .transcribing: ProgressView()
                        case .thinking: Image(systemName: "stop.fill")
                        }
                    }
                    .font(.title2)
                    .frame(width: 44, height: 44)
                }
                .buttonBorderShape(.circle)
                .disabled(voice.phase == .transcribing)
                .accessibilityLabel(action)
                if quiet { button.glassButton() } else { button.glassProminentButton() }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

/**
 The words heard so far, live: settled words in the primary colour, the still-changing tail faded. Observes
 [words] on its own, so each transcription re-renders this line only. Shows the newest
 [maxChars] characters; nothing until a word is heard.
 */
private struct LiveWordsLine: View {
    let words: HeardWords
    private let maxChars = 160

    var body: some View {
        if !words.isEmpty {
            let committed = words.committed
            let tentative = words.tentative
            let full = committed.isEmpty || tentative.isEmpty ? committed + tentative : committed + " " + tentative
            let cut = max(full.count - maxChars, 0)
            let solid = max(committed.count - cut, 0)
            let visible = full.dropFirst(cut)
            (Text(cut > 0 ? "…" : "") + Text(String(visible.prefix(solid))).foregroundStyle(.primary) + Text(String(visible.dropFirst(solid))).foregroundStyle(.secondary))
                .font(.subheadline)
                .lineLimit(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityLabel("Heard: \(full)")
        }
    }
}

/// Microphone peaks as bars, newest on the right, easing toward each new peak (no jitter);
/// a flat rule when not listening.
private struct LevelMeter: View {
    /// Observed here only: ~10 updates a second re-render the meter, not the chat screen.
    let levels: VoiceLevels
    let listening: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var bars = LevelBars()

    var body: some View {
        TimelineView(.animation(paused: !listening)) { timeline in
            let date = timeline.date
            Canvas { context, size in
                bars.step(levels.peaks, at: date, ease: listening && !reduceMotion)
                let slot = size.width / CGFloat(LevelBars.slots)
                var live = Path(), rule = Path()
                for i in 0 ..< LevelBars.slots {
                    let h = max(CGFloat(bars.shown[i]).squareRoot() * size.height, 2)
                    let rect = CGRect(x: CGFloat(i) * slot, y: (size.height - h) / 2, width: slot * 0.6, height: h)
                    if bars.heard[i] { live.addRect(rect) } else { rule.addRect(rect) }
                }
                context.fill(rule, with: .color(Color(uiColor: .tertiaryLabel)))
                context.fill(live, with: .color(Color.brandPurple))
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
            // Exponential approach: a time constant of 70 ms, whatever the frame rate.
            shown[i] = ease ? target + (shown[i] - target) * exp(-dt / 0.07) : target
        }
    }
}

/// While the answer is read aloud: a slow flow strip that swells with each spoken piece.
private struct SpeechStrip: View {
    let spoken: Int

    var body: some View {
        // A running bar while the answer is read aloud.
        ProgressView()
            .progressViewStyle(.linear)
            .frame(maxHeight: .infinity)
            .accessibilityLabel("Reading aloud")
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
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color(.secondarySystemBackground)
            if let image { Image(uiImage: image).resizable().scaledToFill().frame(width: 64, height: 64).clipped() }
            if let onRemove {
                Button { onRemove(id) } label: {
                    Image(systemName: "xmark.circle.fill")
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, .black.opacity(0.6))
                        .font(.body)
                        .padding(4)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Remove image")
            }
        }
        .frame(width: 64, height: 64)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
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
    @State private var providerId: String?
    @State private var modelId = ""
    @State private var refreshing = false
    @State private var refreshError: String?
    @State private var confirmTrains: String?

    var body: some View {
        let providers = app.providers.providers
        let selected = providers.first { $0.id == (providerId ?? routing.provider?.id) }
        let models: [RemoteModelItem] = selected.map(models) ?? []
        let trimmed = modelId.trimmingCharacters(in: .whitespaces)
        NavigationStack {
            List {
                Section("Route") {
                    if providers.isEmpty {
                        Button("Add a provider") {
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
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(option.displayName).foregroundStyle(.primary)
                                    Text(option.defaultModel).font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer(minLength: 0)
                                if option.id == selected?.id {
                                    Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(.tint)
                                }
                            }
                            .contentShape(Rectangle())
                        }
                        .accessibilityAddTraits(option.id == selected?.id ? .isSelected : [])
                    }
                }
                Section {
                    TextField("Model id · type to search", text: $modelId)
                        .font(.body.monospaced())
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    // The field searches the list unless it already names one of its models.
                    let query = models.contains { $0.id == modelId } ? "" : modelId
                    ForEach(ModelLists.filter(models, query, limit: 30)) { m in
                        Button { modelId = m.id } label: {
                            HStack(spacing: 12) {
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(m.id).font(.callout.monospaced()).foregroundStyle(.primary).lineLimit(1)
                                    if !m.caption.isEmpty { Text(m.caption).font(.caption).foregroundStyle(.secondary) }
                                }
                                Spacer(minLength: 0)
                                if m.id == modelId { Image(systemName: "checkmark").font(.body.weight(.semibold)).foregroundStyle(.tint) }
                            }
                            .contentShape(Rectangle())
                        }
                        .accessibilityAddTraits(m.id == modelId ? .isSelected : [])
                    }
                } header: {
                    HStack(spacing: 8) {
                        Text("Model")
                        Spacer(minLength: 0)
                        if let selected {
                            Text(refreshError ?? modelsLabel(selected)).textCase(nil).foregroundStyle(refreshError != nil ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                        }
                        if let selected, canRefresh(selected) {
                            if refreshing {
                                ProgressView().controlSize(.small)
                            } else {
                                Button("Refresh") { refresh(selected) }.textCase(nil)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Model")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onDismiss)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use model") {
                        if let selected, ModelPrivacy.trains(presetId: ProviderPresets.resolve(selected).id, model: trimmed) { confirmTrains = trimmed; return }
                        if let selected { app.agent.setRoute(conversationId, providerId: selected.id, model: trimmed) }
                        onDismiss()
                    }
                    .disabled(trimmed.isEmpty || selected == nil)
                }
            }
            .alert("Switch to \(confirmTrains ?? "this model")?", isPresented: Binding(get: { confirmTrains != nil }, set: { if !$0 { confirmTrains = nil } })) {
                Button("Switch anyway") { if let selected, let m = confirmTrains { app.agent.setRoute(conversationId, providerId: selected.id, model: m) }; onDismiss() }
                Button("Cancel", role: .cancel) {}
            } message: { Text(ModelPrivacy.trainsWarning + " Anything you send in this chat from now on may end up in a future model.") }
        }
        .onAppear { modelId = routing.selectedModel ?? "" }
        .presentationDetents([.medium, .large])
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
    @State private var renaming = false
    @State private var name = ""

    var body: some View {
        let settings = session?.settings ?? SessionSettings()
        let changes = session?.transcript.projection.changes ?? []
        let conversation = app.conversations.get(conversationId)
        let files = changes.reduce(0) { $0 + $1.files.count }
        let turns = changes.count
        NavigationStack {
            Form {
                Section("Chat") {
                    HStack {
                        Text(conversation?.title ?? "").lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                        Button("Rename") {
                            name = conversation?.title ?? ""
                            renaming = true
                        }
                    }
                    Toggle("Pin to top", isOn: Binding(get: { conversation?.pinned ?? false }, set: { app.conversations.setPinned([conversationId], $0) }))
                    Button(conversation?.archived == true ? "Unarchive" : "Archive") {
                        app.conversations.setArchived([conversationId], !(conversation?.archived ?? false))
                        onDismiss()
                    }
                    Button("Delete chat", role: .destructive, action: onDelete)
                }
                Section("Session") {
                    Toggle(isOn: Binding(get: { settings.toolsEnabled }, set: { app.agent.setToolsEnabled(conversationId, $0) })) {
                        Text("Agent tools")
                        Text("Files in /workspace, web fetch, image generation, skills, plans. Off = plain chat.")
                        if !app.agent.supportsTools {
                            Text("This build runs plain chat: tools need the shared agent runtime.").foregroundStyle(.orange)
                        }
                    }
                    Picker("Permission preset", selection: Binding(get: { settings.permissionPreset }, set: { app.agent.setPermission(conversationId, $0) })) {
                        ForEach(PermissionPreset.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    Button {
                        app.agent.compact(conversationId)
                        onDismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Compact context")
                            Text("Summarize older turns now (also automatic near the limit).").font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    .disabled(session?.running ?? false)
                    Button(action: onChanges) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Files changed").foregroundStyle(.primary)
                                Text(files == 0 ? "Nothing yet. Every file the agent changes can be undone."
                                     : "\(files) file\(files == 1 ? "" : "s") across \(turns) turn\(turns == 1 ? "" : "s"); undo a step or a turn.")
                                    .font(.footnote).foregroundStyle(.secondary)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
                        }
                    }
                    .accessibilityLabel("Files changed, view")
                }
            }
            .navigationTitle("Session")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done", action: onDismiss) }
            }
        }
        .presentationDetents([.medium, .large])
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

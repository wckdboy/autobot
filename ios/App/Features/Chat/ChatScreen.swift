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
        route = isLocal ? .localGpu : loopback ? .localCpu : .cloudApi
        modelLabel = conversation?.model.map { m in
            guard isLocal else { return m }
            if m == AppleIntelligence.id { return AppleIntelligence.title }
            if let title = app.library.get(m)?.title { return title }
            let tail = m.components(separatedBy: "catalog:").last ?? m
            return (tail.components(separatedBy: "/").last ?? tail).components(separatedBy: ".gguf").first ?? tail
        }
    }
}

/**
 An agent run (mockup 03): red run line, the task as a display title, numbered tool steps with
 timings, approvals as a full red block, and the active policy in the footer.
 */
struct ChatScreen: View {
    let conversationId: String
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var model = ChatModel()
    @State private var showModels = false
    @State private var showSession = false
    /// The privacy receipt being looked at.
    @State private var openReceipt: Receipt?
    /// Which transcript items are new since the screen loaded (they ink in).
    @State private var ledger = InkLedger()

    var body: some View {
        let session = app.agent.session(conversationId)
        let state = session.state
        let routing = ChatRouting(app: app, conversationId: conversationId)
        let toolItems = state.projection.items.compactMap { item -> String? in if case .tool(let t) = item { t.key } else { nil } }
        let stepNumbers = Dictionary(uniqueKeysWithValues: toolItems.enumerated().map { ($0.element, $0.offset + 1) })
        let firstPrompt = state.projection.items.first { if case .user = $0 { true } else { false } }?.key
        let usage = state.projection.lastUsage
        let contextUsed = usage.map { ($0.inputTokens ?? 0) + ($0.cacheReadTokens ?? 0) + ($0.outputTokens ?? 0) }
        let _ = ledger.observe(state.projection.items.map(\.key) + state.approvals.map { "approval:" + $0.callId })

        VStack(spacing: 0) {
            AutobotTopBar(title: state.toolsEnabled ? "Agent run" : "Chat", onBack: { nav.back() }) {
                Tag("voice", accent: model.voice != nil ? c.primary : nil, filled: model.voice != nil) {
                    model.setVoiceMode(model.voice == nil, app: app, conversationId: conversationId)
                }
                Button { showModels = true } label: { RouteTag(route: routing.route) }.buttonStyle(PressStyle())
                IconAction(symbol: "ellipsis", label: "Session settings") { showSession = true }
            }
            if routing.showOfflineBanner {
                Banner(text: "Offline mode: this provider is remote, so the kill switch blocks it.", action: "PRIVACY") { nav.push(.privacyCenter) }
            }
            if app.dataLoaded && app.providers.providers.isEmpty {
                Banner(text: "No model yet — download one or add a provider.", action: "ADD") { nav.push(.providers) }
            }
            if let notice = model.notice {
                Banner(text: notice, action: "OK") { model.notice = nil }
            }

            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if app.dataLoaded && state.projection.items.isEmpty && state.live == nil {
                            EmptySession(toolsEnabled: state.toolsEnabled)
                        }
                        ForEach(state.projection.items) { item in
                            row(item, state: state, routing: routing, contextUsed: contextUsed, stepNumbers: stepNumbers, firstPrompt: firstPrompt)
                                .inkEnter(ledger.isNew(item.key)) { ledger.entered(item.key) }
                        }
                        if let live = state.live { LiveMessage(live: live) }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
                .defaultScrollAnchor(.bottom)
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: state.projection.items.last?.key) { _, _ in withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
                .onChange(of: state.live == nil) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
            }

            if state.projection.todos.contains(where: { $0.status != .completed }) {
                TodoPanel(todos: state.projection.todos)
            }
            if let question = state.question {
                QuestionCard(question: question) { app.agent.answerQuestion(callId: question.callId, answers: $0) }
            }
            PolicyLine(state: state, contextUsed: contextUsed)
            if let voice = model.voice {
                VoicePanel(voice: voice, model: model) { model.setVoiceMode(false, app: app, conversationId: conversationId) }
            } else {
                InputBar(model: model, running: state.running, onSend: send, onStop: { app.agent.cancel(conversationId) })
            }
        }
        .background(c.background)
        .sheet(isPresented: $showModels) {
            ModelSheet(conversationId: conversationId, routing: routing) { showModels = false }
        }
        .sheet(isPresented: $showSession) {
            SessionSheet(conversationId: conversationId, state: state) { showSession = false }
        }
        .sheet(item: $openReceipt) { ReceiptSheet(receipt: $0) }
        .onChange(of: state) { _, new in model.sessionChanged(new) }
        .onDisappear {
            model.setVoiceMode(false, app: app, conversationId: conversationId)
            model.stopSpeaking()
            model.stopRecording(app: app)
        }
    }

    @ViewBuilder
    private func row(_ item: SessionItem, state: ChatSessionState, routing: ChatRouting, contextUsed: Int?, stepNumbers: [String: Int], firstPrompt: String?) -> some View {
        switch item {
        case .user(let key, let text, let images):
            VStack(alignment: .leading, spacing: 8) {
                if key == firstPrompt {
                    RunHeader(prompt: text, label: ["run", routing.modelLabel ?? routing.selectedModel, contextUsed.map { "\(compactCount($0)) tok" }].compactMap { $0 }.joined(separator: " · "))
                } else {
                    FollowUp(text: text)
                }
                if !images.isEmpty { AttachmentStrip(ids: images, model: model) }
            }
        case .assistant(let key, let text, let reasoning, let interrupted, let outputTokens, _, let receipt):
            AssistantMessage(
                text: text, reasoning: reasoning, interrupted: interrupted, outputTokens: outputTokens, speaking: model.speakingKey == key,
                receipt: receipt, onReceipt: { openReceipt = $0 },
            ) {
                model.speak(key: key, text: text)
            }
        case .tool(let tool):
            if let approval = state.approvals.first(where: { $0.callId == tool.callId }) {
                let key = "approval:" + tool.callId
                ApprovalCard(number: stepNumbers[tool.key] ?? 0, tool: tool, approval: approval) { outcome in
                    app.agent.answerApproval(callId: tool.callId, outcome: outcome)
                }
                .registerGhost(ledger.isNew(key), color: c.primary)
                .onAppear { ledger.entered(key) }
            } else {
                StepRow(number: stepNumbers[tool.key] ?? 0, tool: tool, model: model) { id in nav.push(.gallery(openId: id)) }
            }
        case .notice(_, let text, let kind):
            NoticeRow(text: text, kind: kind)
        }
    }

    // MARK: Intents

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
    let onSpeak: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let reasoning, !reasoning.isEmpty { ThinkingDisclosure(reasoning: reasoning) }
            if !text.isEmpty {
                ChatBubble(role: .assistant, text: text) {
                    HStack(spacing: 8) {
                        if interrupted { Tag("interrupted", accent: AutobotColors.amber) }
                        if let outputTokens { TokenRateLabel(totalTokens: outputTokens) }
                        Spacer(minLength: 0)
                        IconAction(.speaker, label: speaking ? "Stop reading" : "Read aloud", tint: speaking ? c.primary : c.onSurfaceVariant, size: 18, action: onSpeak)
                            .frame(width: 32, height: 32)
                    }
                }
            }
            ReceiptLine(receipt: receipt, onOpen: onReceipt)
        }
    }
}

private struct LiveMessage: View {
    let live: LiveState
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !live.reasoning.isEmpty { ThinkingDisclosure(reasoning: live.reasoning, isThinking: live.text.isEmpty) }
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

/// `01  Read file / read · notes.md   ✓ 0.4 s`
private struct StepRow: View {
    let number: Int
    let tool: SessionItem.Tool
    let model: ChatModel
    /// Opens that gallery item's detail.
    let onOpenGallery: (String) -> Void
    @State private var expanded = false
    @Environment(\.scheme) private var c

    /// A step mark stamps in when the step reaches one of these.
    private static func settled(_ state: ToolState) -> Bool { state == .ok || state == .error || state == .denied }

    var body: some View {
        let (mark, markColor): (String, Color) = switch tool.state {
        case .ok: ("✓", c.onSurfaceVariant)
        case .error: ("×", c.error)
        case .denied: ("denied", c.onSurfaceVariant)
        case .running, .streaming, .waitingApproval: ("…", c.primary)
        }
        VStack(alignment: .leading, spacing: 0) {
            Button { if tool.output != nil { expanded.toggle() } } label: {
                HStack(alignment: .top, spacing: 12) {
                    Text(String(format: "%02d", number)).textStyle(.readout).foregroundStyle(c.onSurfaceVariant)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(stepTitle(tool.name)).textStyle(.titleMedium).foregroundStyle(c.onSurface)
                        Text([tool.name, tool.summary].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: " · "))
                            .textStyle(.micro)
                            .foregroundStyle(c.onSurfaceVariant)
                            .lineLimit(1)
                        // Why a step failed shouldn't take a tap to find out.
                        if !expanded, tool.state == .error || tool.state == .denied,
                           let reason = tool.output?.split(whereSeparator: \.isNewline).first(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }) {
                            Text(reason.hasPrefix("Error: ") ? String(reason.dropFirst(7)) : String(reason))
                                .textStyle(.bodySmall)
                                .foregroundStyle(tool.state == .error ? c.error : c.onSurfaceVariant)
                                .lineLimit(2)
                                .multilineTextAlignment(.leading)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if tool.state == .running { StatusDot(color: c.primary, live: true).padding(.top, 6) }
                    Text(mark + (tool.durationMs.map { String(format: " %.1f s", Double($0) / 1000) } ?? ""))
                        .textStyle(.readout)
                        .foregroundStyle(markColor)
                        .stamp(tool.state, when: Self.settled)
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
            if expanded, let output = tool.output {
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
                HStack(spacing: 6) {
                    AgentProof(tool: tool, model: model).onTapGesture { if let id = tool.galleryIds.first { onOpenGallery(id) } }
                    ForEach(tool.galleryIds.dropFirst(), id: \.self) { id in
                        GalleryThumb(id: id, model: model).onTapGesture { onOpenGallery(id) }
                    }
                }
                .padding(.bottom, 6)
            } else if !tool.galleryIds.isEmpty {
                HStack(spacing: 6) {
                    ForEach(tool.galleryIds, id: \.self) { id in
                        GalleryThumb(id: id, model: model).onTapGesture { onOpenGallery(id) }
                    }
                }
                .padding(.bottom, 6)
            }
            Hairline()
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

/// The red approval block: `04 · APPROVAL NEEDED · NETWORK ACCESS`, title, args, DENY / APPROVE.
private struct ApprovalCard: View {
    let number: Int
    let tool: SessionItem.Tool
    let approval: ApprovalState
    let onDecide: (ApprovalOutcome) -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(String(format: "%02d · APPROVAL NEEDED", number)).textStyle(.micro).frame(maxWidth: .infinity, alignment: .leading)
                Text((approval.reason ?? "").uppercased()).textStyle(.micro)
            }
            Text(stepTitle(tool.name)).textStyle(.headlineMedium)
            Text("\(tool.name)(\(approval.summary))").textStyle(.codeBlock).fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 8) {
                GhostButton("Deny", accent: c.onPrimary) { onDecide(.rejected) }
                PaperButton("Approve") { onDecide(.allowedOnce) }
            }
            Button { onDecide(.allowedForSession) } label: {
                Text("ALWAYS ALLOW \(tool.name.uppercased()) IN THIS RUN").textStyle(.micro).padding(.vertical, 4)
            }
            .buttonStyle(PressStyle())
        }
        .foregroundStyle(c.onPrimary)
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.primary))
    }
}

/// `// STOPPED`; errors keep their case (ids, hosts and keys must stay readable) and expand on tap.
private struct NoticeRow: View {
    let text: String
    let kind: NoticeKind
    @State private var expanded = false
    @Environment(\.scheme) private var c

    var body: some View {
        let color: Color = switch kind {
        case .info: c.outline
        case .warn: AutobotColors.amber
        case .error: c.error
        }
        if kind == .error {
            Text("// \(text)")
                .textStyle(.bodySmall)
                .foregroundStyle(color)
                .lineLimit(expanded ? nil : 3)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
                .onTapGesture { expanded.toggle() }
        } else {
            Text("// \(text.uppercased())").textStyle(.micro).foregroundStyle(color).lineLimit(3)
        }
    }
}

private struct EmptySession: View {
    let toolsEnabled: Bool
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel(toolsEnabled ? "agent ready · workspace /workspace" : "chat")
            Text(toolsEnabled ? "What should\nit do?" : "Ask\nanything.").textStyle(.displaySmall).foregroundStyle(c.onSurface)
            MicroLabel("type / for commands", color: c.outline)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 48)
    }
}

/// `POLICY · READ: AUTO · WRITE: AUTO · DELETE: ASK · NETWORK: ASK`
private struct PolicyLine: View {
    let state: ChatSessionState
    let contextUsed: Int?

    var body: some View {
        if state.toolsEnabled {
            let text = switch state.permissionPreset {
            case .readOnly: "policy · read: auto · write: deny · network: ask"
            case .workspace: "policy · read: auto · write: auto · delete: ask · network: ask"
            case .fullAccess: "policy · everything: auto · kill switch still applies"
            }
            HStack {
                MicroLabel(text).frame(maxWidth: .infinity, alignment: .leading)
                if let used = contextUsed, let window = state.projection.contextWindow, window > 0 {
                    MicroLabel("\(compactCount(used))/\(compactCount(window))")
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
        }
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
            TextAction(action, color: c.primary, style: .labelLarge, action: onAction)
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
                PrimaryButton("Send", enabled: !answer.isEmpty) { onAnswer(answer) }.frame(width: 96)
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

private struct InputBar: View {
    let model: ChatModel
    let running: Bool
    let onSend: (String) -> Void
    let onStop: () -> Void
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
                        case .transcribing: Text("…").textStyle(.code).foregroundStyle(c.primary)
                        }
                    }
                    .frame(width: 44, height: 52)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .disabled(model.dictation == .transcribing)
                .accessibilityLabel("Dictate")
                ConsoleTextField(text: $text, placeholder: running ? "queue a follow-up…" : "message · / for commands", maxLines: 8)
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
                        Image(systemName: "paperplane.fill")
                            .font(.system(size: 18, weight: .semibold))
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
                SectionLabel("voice · \(label)").frame(maxWidth: .infinity, alignment: .leading)
                Tag("hands-free", accent: voice.handsFree ? c.primary : nil, filled: voice.handsFree) { model.setHandsFree(!voice.handsFree) }
                Tag("type", action: onType)
            }
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Group {
                        if voice.phase == .speaking {
                            SpeechStrip(spoken: voice.spoken)
                        } else {
                            LevelMeter(levels: voice.levels, listening: voice.phase == .listening)
                        }
                    }
                    .frame(height: 36)
                    MicroLabel(hint)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button { model.voiceTap() } label: {
                    Group {
                        switch voice.phase {
                        case .ready, .speaking: AutobotIconView(icon: .mic, size: 26, color: quiet ? c.primary : c.onPrimary)
                        case .listening: Rectangle().fill(c.onPrimary).frame(width: 16, height: 16)
                        case .transcribing: Text("…").textStyle(.code).foregroundStyle(c.onPrimary)
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
        .background(c.background)
        .hairlineEdge(top: true, bottom: false)
    }
}

/// Microphone peaks as riso bars, newest on the right, easing toward each new peak (no jitter);
/// a flat rule when not listening.
private struct LevelMeter: View {
    let levels: [Float]
    let listening: Bool
    @Environment(\.scheme) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var bars = LevelBars()

    var body: some View {
        InkTimeline(active: listening) { date in
            Canvas { context, size in
                bars.step(levels, at: date, ease: listening && !reduceMotion)
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
        let pulse = pulse
        FlowingHalftone(active: true, speed: InkMath.loadingSpeed, dot: c.primary, cell: 6) { date in
            let rest = Double(Tokens.Motion.flowMinFill)
            guard let pulse else { return rest }
            return rest + 0.45 * exp(-max(date.timeIntervalSince(pulse), 0) / 0.9)
        }
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
            VStack(alignment: .leading, spacing: 8) {
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
                                .font(.system(size: 20))
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
                        TextAction(refreshing ? "…" : "Refresh", color: c.primary, enabled: !refreshing) { refresh(selected) }
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
            ? app.library.models.filter { $0.isReady && $0.engine == .llama }.sorted { $0.addedAt > $1.addedAt }.map { RemoteModelItem(id: $0.id, caption: $0.title) }
            : ModelLists.models(app.providers.modelLists[p.id]?.json, presetId: ProviderPresets.resolve(p).id)
                ?? ProviderPresets.resolve(p).suggestedModels.map { RemoteModelItem(id: $0, presetId: ProviderPresets.resolve(p).id) }
        // "This phone" also offers Apple Intelligence when it can answer.
        if p.kind == .local && AppleIntelligence.unavailability == nil { list.append(RemoteModelItem(id: AppleIntelligence.id)) }
        if p.defaultModel.isEmpty || list.contains(where: { $0.id == p.defaultModel }) { return list }
        return [RemoteModelItem(id: p.defaultModel)] + list
    }

    private func modelsLabel(_ p: Provider) -> String {
        guard let stored = app.providers.modelLists[p.id], let list = ModelLists.models(stored.json) else { return "offline list" }
        return "\(list.count) models · \(ModelLists.age(fetchedAt: stored.fetchedAt))"
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
    let state: ChatSessionState
    let onDismiss: () -> Void
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    var body: some View {
        let session = app.agent.session(conversationId)
        VStack(alignment: .leading, spacing: 14) {
            SectionLabel("session")
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Agent tools").textStyle(.titleMedium).foregroundStyle(c.onSurface)
                    Text("Files in /workspace, web fetch, image generation, skills, plans. Off = plain chat.")
                        .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                    if !app.agent.supportsTools {
                        Text("This build runs plain chat: tools need the shared agent runtime.")
                            .textStyle(.bodySmall).foregroundStyle(AutobotColors.amber).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                RisoToggle(isOn: Binding(get: { session.state.toolsEnabled }, set: { app.agent.setToolsEnabled(conversationId, $0) }))
            }
            VStack(alignment: .leading, spacing: 6) {
                MicroLabel("permission preset")
                Segmented(options: PermissionPreset.allCases, selected: session.state.permissionPreset, label: \.label) {
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
                TextAction("Compact", color: c.primary, enabled: !session.state.running) {
                    app.agent.compact(conversationId)
                    onDismiss()
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.top, 20)
        .padding(.bottom, 24)
        .presentationDetents([.height(340), .medium])
        .presentationBackground(c.surfaceContainerLow)
        .presentationDragIndicator(.visible)
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

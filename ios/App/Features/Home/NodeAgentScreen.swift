import AutobotShared
import Observation
import PhotosUI
import SwiftUI

/// One line of the agent transcript on screen.
enum NodeAgentRow: Identifiable, Equatable {
    /// [photos]: the JPEGs sent with it from here; [count]: how many it has (from the node's history: their bytes stay there).
    case user(id: Int, text: String, photos: [Data] = [], count: Int = 0)
    case reply(id: Int, text: String, route: String?)
    /// A tool run, a refusal or a failure: one short line.
    case note(id: Int, text: String, tone: Tone)
    case question(id: Int, approval: String, title: String, detail: String, answer: Answer?)
    /// What the agent kept on its own after an answer (`kept · profile: …`): a quiet line.
    case kept(id: Int, text: String)

    enum Tone { case tool, refused, error }

    /// How the owner answered: allowed, always, denied, or "no, do this instead".
    enum Answer: Equatable {
        case once, always, deny
        case instead(String)

        init(_ answer: ApprovalAnswer) {
            switch answer {
            case .always: self = .always
            case .deny: self = .deny
            default: self = .once
            }
        }
    }

    var id: Int {
        switch self {
        case .user(let id, _, _, _), .reply(let id, _, _), .note(let id, _, _), .question(let id, _, _, _, _), .kept(let id, _): id
        }
    }
}

/**
 The Autobot Agent on a paired node (Invincible's agent API through the Noise channel): tasks run
 on that machine, and every command, file write or read outside its working folder comes here as a
 card first. The conversation lives on the machine.
 */
@MainActor
@Observable
final class NodeAgentModel {
    let providerId: String
    private(set) var node = ""
    private(set) var info: AgentInfo?
    private(set) var problem: String?
    private(set) var rows: [NodeAgentRow] = []
    private(set) var running = false
    private(set) var schedules: [AgentSchedule]?
    private(set) var scheduleProblem: String?
    /// What the node and the agent did and know; nil until loaded.
    private(set) var activity: NodeActivity?
    private(set) var activityProblem: String?
    /// The runtime is there to ask (the node may still be unreachable).
    private(set) var connected = false
    /// Photos attached to the message being written (JPEG, downscaled).
    private(set) var photos: [Data] = []
    /// Why the last photo could not be attached.
    var photoProblem: String?
    /// Text handed back to the composer (the model could not see the photo); the screen takes it once.
    var returned: String?
    @ObservationIgnored private var session = ""
    @ObservationIgnored private var counter = 0
    @ObservationIgnored private var turn: AutobotShared.Cancellable?
    @ObservationIgnored private var route: String?
    @ObservationIgnored private weak var runtime: SharedAgentRuntime?

    /// A conversation to open instead of this device's own (the one approvals wait in).
    let openSession: String?
    /// The bot of [openSession], or with no session, the bot to start a new conversation with (bots mode).
    let bot: String?

    /// The node has more than one bot: the screen says which one this is.
    var severalBots: Bool { (info?.bots.count ?? 0) > 1 }

    init(providerId: String, session: String? = nil, bot: String? = nil) {
        self.providerId = providerId
        openSession = session
        self.bot = bot
    }

    func connect(app: AppContainer) async {
        await connect(runtime: app.agent as? SharedAgentRuntime, node: app.providers.get(providerId)?.displayName ?? "my node")
    }

    func connect(runtime: SharedAgentRuntime?, node: String) async {
        self.runtime = runtime
        self.node = node
        // Debug builds' demo node: a chat with a photo, an answer and what the agent kept.
        if BotsDemo.on(providerId) {
            self.node = BotsDemo.nodeName
            connected = true
            info = NodeChatSample.shared.info
            rows = NodeChatDemo.rows(photo: NodeChatDemo.photo()) { [self] in next() }
            return
        }
        guard let runtime else { return problem = "My node is not available here" }
        connected = true
        problem = nil
        // A new chat with a bot: a conversation of its own, kept across reconnects.
        var target = openSession
        let fresh = openSession == nil && bot != nil
        if fresh, let bot {
            if session.isEmpty { session = await runtime.nodeAgentNewSession(providerId, bot: bot) ?? "" }
            target = session.isEmpty ? nil : session
        }
        let (opened, error) = await runtime.nodeAgentOpen(providerId, session: target)
        guard let opened else { return problem = error ?? "The node did not answer" }
        session = opened.session
        info = opened.info
        let history: [NodeAgentRow] = (fresh && rows.isEmpty ? [] : opened.history).map { line in
            switch line.role {
            case "user": .user(id: next(), text: line.text, count: line.images.count)
            case "tool": .note(id: next(), text: line.text, tone: .tool)
            default: .reply(id: next(), text: line.text, route: nil)
            }
        }
        // Approvals waiting anywhere on the node, as cards (an older node cannot list them: none shown).
        let waiting = await runtime.nodeAgentApprovals(providerId)
        rows = Self.waiting(history, waiting, severalBots: severalBots) { [self] in next() }
    }

    /// One turn as it streams: what was sent, whether the node took it, and what a retry would put back.
    private final class Following {
        let sent: (id: Int, text: String, photos: [Data])?
        let restore: [NodeAgentRow]?
        var accepted = false
        init(sent: (id: Int, text: String, photos: [Data])?, restore: [NodeAgentRow]?) {
            self.sent = sent
            self.restore = restore
        }
    }

    /// The last message when it never reached the node: Retry sends it again instead of re-running the turn before it.
    @ObservationIgnored private var unsent: (id: Int, text: String, photos: [Data])?

    /// Photos still being read and downscaled: the message waits for them.
    var encoding = 0

    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !running, encoding == 0, info != nil else { return }
        let id = next()
        let sent = photos
        rows.append(.user(id: id, text: trimmed, photos: sent, count: sent.count))
        photos = []
        photoProblem = nil
        running = true
        route = nil
        if BotsDemo.on(providerId) { return demoAnswer() }
        guard let runtime else { running = false; return }
        follow(runtime, Following(sent: (id, trimmed, sent), restore: nil))
    }

    private func follow(_ runtime: SharedAgentRuntime, _ f: Following) {
        let handler: @MainActor (AgentEvent) -> Void = { [weak self] event in self?.apply(event, f) }
        if let sent = f.sent {
            turn = runtime.nodeAgentTurn(providerId, session: session, text: sent.text, bot: bot, images: sent.photos.map { $0.base64EncodedString() }, onEvent: handler)
        } else {
            turn = runtime.nodeAgentRetry(providerId, session: session, onEvent: handler)
        }
    }

    /// The row whose retry runs the last turn again: the last answer or failure after the last message, when nothing runs.
    nonisolated static func retryTarget(_ rows: [NodeAgentRow], running: Bool) -> Int? {
        guard !running, let user = rows.lastIndex(where: { if case .user = $0 { true } else { false } }) else { return nil }
        let after = rows[(user + 1)...]
        if after.contains(where: { if case .question(_, _, _, _, nil) = $0 { true } else { false } }) { return nil }
        return after.last(where: { row in
            switch row {
            case .reply: true
            case .note(_, _, let tone): tone == .error
            default: false
            }
        })?.id
    }

    /// [rows] as a retry starts: the last turn's rows go, its message stays.
    nonisolated static func retrying(_ rows: [NodeAgentRow]) -> [NodeAgentRow] {
        guard let user = rows.lastIndex(where: { if case .user = $0 { true } else { false } }) else { return rows }
        return Array(rows[...user])
    }

    /**
     Retry on the last answer or failure: the node runs that turn again (`POST …/retry`), or, when
     the message never reached it, the message goes again. Its rows go, its message stays; a retry
     the node does not take puts them back.
     */
    func retry() {
        guard !running else { return }
        let before = rows
        rows = Self.retrying(rows)
        running = true
        route = nil
        if BotsDemo.on(providerId) { return demoAnswer() }
        guard let runtime else { rows = before; running = false; return }
        let lastUser = before.last { if case .user = $0 { true } else { false } }?.id
        if let again = unsent, again.id == lastUser {
            follow(runtime, Following(sent: again, restore: nil))
        } else {
            follow(runtime, Following(sent: nil, restore: before))
        }
    }

    private func demoAnswer() {
        Task {
            try? await Task.sleep(for: .milliseconds(600))
            rows.append(.reply(id: next(), text: NodeChatSample.shared.FOLLOW_ANSWER, route: NodeChatSample.shared.ROUTE))
            running = false
        }
    }

    /// Attaches a photo (library or camera): downscaled to 1568 px on its longest side, JPEG, no EXIF.
    func attach(_ data: Data) {
        guard photos.count < Int(NodeImages.shared.MAX_PHOTOS) else { return }
        guard let jpeg = NodePhotos.encode(data) else { return photoProblem = "Could not read that photo." }
        photos.append(jpeg)
        photoProblem = nil
    }

    func removePhoto(at index: Int) {
        guard photos.indices.contains(index) else { return }
        photos.remove(at: index)
    }

    private func apply(_ event: AgentEvent, _ f: Following) {
        let failed = event as? AgentEventFailed
        if failed == nil { f.accepted = true }
        // The model cannot see images: said plainly; the text goes back to the composer, the photo does not.
        if let e = failed, e.code == NodeImages.shared.CANNOT_SEE, let sent = f.sent {
            unsent = nil
            rows.removeAll { $0.id == sent.id }
            rows.append(.note(id: next(), text: e.text, tone: .error))
            returned = sent.text
            running = false
            return
        }
        if failed != nil && !f.accepted {
            if let sent = f.sent { unsent = sent }
            // A retry the node did not take: the answer it was to replace comes back.
            if let restore = f.restore { rows = restore }
        } else if f.sent != nil && f.accepted {
            unsent = nil
        }
        apply(event)
    }

    private func apply(_ event: AgentEvent) {
        switch event {
        case let e as AgentEventReceipt:
            route = [e.model, e.tier].compactMap { $0 }.joined(separator: " · ")
        case let e as AgentEventTool:
            rows.append(.note(id: next(), text: e.text, tone: .tool))
        case let e as AgentEventRefused:
            rows.append(.note(id: next(), text: e.text, tone: .refused))
        case let e as AgentEventApproval:
            let (title, detail) = Self.describe(e.action, fallback: e.text)
            rows.append(.question(id: next(), approval: e.id, title: e.agent.map { "\($0): \(title)" } ?? title, detail: detail, answer: nil))
        case let e as AgentEventMember:
            // A team member's answer to the lead, under its name.
            rows.append(.reply(id: next(), text: e.text, route: e.agent))
        case let e as AgentEventAnswer:
            rows.append(.reply(id: next(), text: e.text, route: route))
            running = false
        case let e as AgentEventFailed:
            rows.append(.note(id: next(), text: e.text, tone: .error))
            running = false
        case let e as AgentEventKept:
            rows.append(.kept(id: next(), text: NodeBotDetails.shared.kept(text: e.text)))
        default:
            break
        }
    }

    nonisolated static func describe(_ action: AgentAction, fallback: String) -> (String, String) {
        switch action {
        case let a as AgentActionCommand: ("Run a command?", a.command)
        case let a as AgentActionWrite: (a.inside ? "Write a file?" : "Write outside the working directory?", "\(a.path) · \(a.bytes) bytes")
        case let a as AgentActionRead: ("Read outside the working directory?", a.path)
        case let a as AgentActionCloud: ("Use a cloud model for this?", a.reason)
        case let a as AgentActionFetch: ("Fetch a web page?", a.url)
        case let a as AgentActionMcp: ("Use \(a.tool)?", a.arguments)
        default: ("Approve?", fallback)
        }
    }

    /// [rows] with [approval]'s card answered.
    nonisolated static func answered(_ rows: [NodeAgentRow], _ approval: String, _ answer: NodeAgentRow.Answer) -> [NodeAgentRow] {
        rows.map { row in
            if case .question(let id, approval, let title, let detail, nil) = row { return .question(id: id, approval: approval, title: title, detail: detail, answer: answer) }
            return row
        }
    }

    /// [rows] and, after them, a card for each approval waiting that is not on screen yet.
    /// With [severalBots], each card says which bot asks ("ops · Run a command?").
    static func waiting(_ rows: [NodeAgentRow], _ approvals: [PendingApproval], severalBots: Bool = false, next: () -> Int) -> [NodeAgentRow] {
        let shown = Set(rows.compactMap { row -> String? in if case .question(_, let approval, _, _, _) = row { approval } else { nil } })
        return rows + approvals.filter { !shown.contains($0.id) }.map { a in
            let (title, detail) = describe(a.action, fallback: a.text)
            let asked = a.agent.map { "\($0): \(title)" } ?? title
            return .question(id: next(), approval: a.id, title: severalBots ? "\(NodeBots.shared.of(bot: a.bot)) · \(asked)" : asked, detail: detail, answer: nil)
        }
    }

    /// "No, and do this instead": answers [approval] with [text].
    func answerInstead(_ approval: String, _ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let runtime, !trimmed.isEmpty else { return }
        rows = Self.answered(rows, approval, .instead(trimmed))
        Task {
            if !(await runtime.nodeAgentInstead(providerId, id: approval, text: trimmed)) {
                rows.append(.note(id: next(), text: "That question had expired.", tone: .error))
            }
        }
    }

    func answer(_ approval: String, _ answer: ApprovalAnswer) {
        guard let runtime else { return }
        rows = Self.answered(rows, approval, NodeAgentRow.Answer(answer))
        Task {
            if !(await runtime.nodeAgentAnswer(providerId, id: approval, answer: answer)) {
                rows.append(.note(id: next(), text: "That question had expired.", tone: .error))
            }
        }
    }

    func newConversation() async {
        guard let runtime, !running else { return }
        if let error = await runtime.nodeAgentReset(providerId, session: session) {
            rows.append(.note(id: next(), text: error, tone: .error))
        } else {
            rows = []
        }
    }

    func loadSchedules() async {
        guard let runtime else { return }
        let (list, error) = await runtime.nodeAgentSchedules(providerId)
        if let list { schedules = list; scheduleProblem = nil } else { scheduleProblem = error }
    }

    /// True when the node accepted it.
    func addSchedule(_ schedule: String, task: String, telegram: Bool) async -> Bool {
        guard let runtime else { return false }
        if let error = await runtime.nodeAgentAddSchedule(providerId, schedule: schedule.trimmingCharacters(in: .whitespaces), task: task.trimmingCharacters(in: .whitespacesAndNewlines), telegram: telegram) {
            scheduleProblem = error
            return false
        }
        await loadSchedules()
        return true
    }

    func removeSchedule(_ id: String) async {
        guard let runtime else { return }
        if let error = await runtime.nodeAgentRemoveSchedule(providerId, id: id) { scheduleProblem = error } else { await loadSchedules() }
    }

    func loadActivity() async {
        guard let runtime else { return }
        let (value, error) = await runtime.nodeActivity(providerId)
        if let value { activity = value; activityProblem = nil } else { activityProblem = error }
    }

    /// Answers a question a team member asked on the board.
    func answerBoard(_ id: String, _ text: String) async -> Bool {
        guard let runtime else { return false }
        let error = await runtime.nodeBoardAnswer(providerId, id: id, text: text.trimmingCharacters(in: .whitespacesAndNewlines))
        if let error { activityProblem = error }
        await loadActivity()
        return error == nil
    }

    func forget(_ id: Int64) async {
        guard let runtime else { return }
        if let error = await runtime.nodeAgentForget(providerId, id: id) { activityProblem = error }
        await loadActivity()
    }

    func stopListening() { turn?.cancel() }

    private func next() -> Int {
        counter += 1
        return counter
    }
}

/// A sheet the agent screen opens on arrival: the activity, or straight to its board.
enum NodeAgentSheet: Hashable { case activity, board }

struct NodeAgentScreen: View {
    @Environment(AppContainer.self) private var app
    @State private var model: NodeAgentModel
    @State private var draft = ""
    @State private var showSchedules = false
    @State private var showActivity = false

    private let open: NodeAgentSheet?
    /// This conversation's unsent text ([ComposerDrafts]).
    private let draftKey: String

    @State private var otherFor: String?
    @State private var otherText = ""
    @State private var picks: [PhotosPickerItem] = []
    @State private var showCamera = false
    @FocusState private var focused: Bool
    @Environment(\.keyboardUp) private var keyboardUp

    init(providerId: String, open: NodeAgentSheet? = nil, session: String? = nil, bot: String? = nil) {
        _model = State(initialValue: NodeAgentModel(providerId: providerId, session: session, bot: bot))
        _showActivity = State(initialValue: open != nil)
        self.open = open
        draftKey = "node:\(providerId)/\(session ?? "")/\(bot ?? "")"
    }

    var body: some View {
        transcript
            .background(Color(.systemBackground))
            .navigationTitle("Agent · \(model.node)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .principal) { TitleWithDot("Agent · \(model.node)") }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        showActivity = true
                        Task { await model.loadActivity() }
                    } label: {
                        Label("Activity: what the node and the agent did", systemImage: "list.bullet.rectangle")
                    }
                    .disabled(!model.connected)
                    Button {
                        showSchedules = true
                        Task { await model.loadSchedules() }
                    } label: {
                        Label("Scheduled tasks", systemImage: "calendar")
                    }
                    .disabled(model.info == nil)
                    Button {
                        Task { await model.newConversation() }
                    } label: {
                        Label("New conversation", systemImage: "arrow.clockwise")
                    }
                    .disabled(model.info == nil || model.running)
                }
            }
            .task {
                await model.connect(app: app)
                if open != nil { await model.loadActivity() }
            }
            .task { if DebugFlags.keyboard { try? await Task.sleep(for: .milliseconds(900)); focused = true } }
            .onDisappear { model.stopListening() }
            .onAppear { if draft.isEmpty { draft = app.drafts.text(draftKey) } }
            .onChange(of: draft) { _, now in app.drafts.set(draftKey, now) }
            // The model could not see the photo: the text comes back to the box.
            .onChange(of: model.returned) { _, text in
                if let text {
                    draft = text
                    model.returned = nil
                }
            }
            .onChange(of: picks) { _, items in
                guard !items.isEmpty else { return }
                picks = []
                model.encoding += items.count
                Task {
                    for item in items {
                        let data = try? await item.loadTransferable(type: Data.self)
                        model.encoding -= 1
                        if let data { model.attach(data) } else { model.photoProblem = "Could not read that photo." }
                    }
                }
            }
            .fullScreenCover(isPresented: $showCamera) {
                CameraPicker { data in
                    showCamera = false
                    if let data { model.attach(data) }
                }
                .ignoresSafeArea()
            }
            .sheet(isPresented: $showSchedules) { NodeSchedulesSheet(model: model) }
            .sheet(isPresented: $showActivity) { NodeActivitySheet(model: model, initialTab: open == .board ? NodeActivitySheet.boardTab : 0) }
    }

    /// The conversation, with the composer floating over its end.
    private var transcript: some View {
        ScrollViewReader { proxy in
            ScrollView {
                let retry = NodeAgentModel.retryTarget(model.rows, running: model.running)
                LazyVStack(alignment: .leading, spacing: 10) {
                    header
                    ForEach(model.rows) { row in rowView(row, retry: retry == row.id).id(row.id) }
                    if model.running { ProgressView().frame(maxWidth: .infinity, alignment: .leading) }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(.horizontal)
                .padding(.vertical, 8)
                .readableWidth()
            }
            .defaultScrollAnchor(.bottom)
            .defaultScrollAnchor(.bottom, for: .sizeChanges)
            .scrollDismissesKeyboard(.interactively)
            // The composer rides on the keyboard; the newest row stays right above it.
            .safeAreaInset(edge: .bottom, spacing: 0) { composer }
            .onChange(of: model.rows.count) { withAnimation { proxy.scrollTo("bottom", anchor: .bottom) } }
            .onChange(of: keyboardUp) { proxy.scrollTo("bottom", anchor: .bottom) }
        }
    }

    /// The node's composer: the photo menu, the field and Send. The node's agent neither queues nor
    /// stops a turn, so Send waits for it (and for a photo still being downscaled).
    private var composer: some View {
        VStack(spacing: 0) {
            if !model.photos.isEmpty || model.photoProblem != nil || model.encoding > 0 { pendingPhotos }
            HStack(alignment: .bottom, spacing: 4) {
                photoMenu
                ComposerField(text: $draft, placeholder: Composer.shared.placeholder(running: model.running, node: true), focused: $focused)
                    .disabled(model.info == nil)
                ComposerSendButton(enabled: Composer.shared.enabled(action: .send, text: draft, attachments: 0, ready: !model.running && model.encoding == 0 && model.info != nil)) {
                    model.send(draft)
                    draft = ""
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
        }
        .composerPanel()
    }

    @ViewBuilder private var header: some View {
        if let problem = model.problem {
            VStack(alignment: .leading, spacing: 8) {
                Text(problem).font(.callout).fixedSize(horizontal: false, vertical: true)
                Button("Retry") { Task { await model.connect(app: app) } }
                    .glassButton()
            }
        } else if let info = model.info, Composer.shared.shows(part: .decor, keyboardUp: keyboardUp) {
            let folder = info.workdir.split(separator: "/").suffix(2).joined(separator: "/")
            // Several bots: which one this conversation is with. Invincible's pixel i: about the node.
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                NodeMark()
                Text([model.severalBots ? "bot \(NodeBots.shared.of(bot: model.bot))" : nil, "runs on \(model.node)", "approvals: \(info.approvals)", info.private_ ? "local models only" : nil, "…/\(folder)"].compactMap { $0 }.joined(separator: " · "))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
        } else if model.info == nil {
            ProgressView().frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    /// Attach a photo: the camera (when there is one), or the library (the system picker, no permission needed).
    private var photoMenu: some View {
        let enabled = model.info != nil && !model.running && model.photos.count < Int(NodeImages.shared.MAX_PHOTOS)
        return Menu {
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                Button { showCamera = true } label: { Label("Take a photo", systemImage: "camera") }
            }
            PhotosPicker(selection: $picks, maxSelectionCount: max(1, Int(NodeImages.shared.MAX_PHOTOS) - model.photos.count), matching: .images) {
                Label("Choose from library", systemImage: "photo.on.rectangle")
            }
        } label: {
            ComposerToolLabel(systemImage: "photo.on.rectangle")
        }
        .disabled(!enabled)
        .accessibilityLabel("Attach a photo")
    }

    private var pendingPhotos: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                ForEach(Array(model.photos.enumerated()), id: \.offset) { i, data in
                    ZStack(alignment: .topTrailing) {
                        PhotoThumb(data: data, side: 64).accessibilityLabel("Photo \(i + 1)")
                        Button { model.removePhoto(at: i) } label: {
                            Image(systemName: "xmark.circle.fill")
                                .symbolRenderingMode(.palette)
                                .foregroundStyle(.white, .black.opacity(0.6))
                                .font(.title3)
                        }
                        .frame(width: 44, height: 44, alignment: .topTrailing)
                        .accessibilityLabel("Remove photo \(i + 1)")
                    }
                }
            }
            Group {
                if let problem = model.photoProblem { Text(problem).foregroundStyle(.red).lineLimit(2) }
                else if model.encoding > 0 { Text("preparing \(NodeImages.shared.label(count: Int32(model.encoding)))…").foregroundStyle(.secondary) }
                else { Text("sent to the node at up to \(NodeImages.shared.MAX_SIDE) px, without location").foregroundStyle(.secondary).lineLimit(2) }
            }
            .font(.caption)
        }
        .padding(.horizontal)
        .padding(.top, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func retryButton() -> some View {
        Button { model.retry() } label: { Image(systemName: "arrow.clockwise") }
            .messageAction()
        .accessibilityLabel("Retry on the node")
    }

    @ViewBuilder private func rowView(_ row: NodeAgentRow, retry: Bool = false) -> some View {
        switch row {
        case .user(_, let text, let photos, let count):
            VStack(alignment: .leading, spacing: 6) {
                if !photos.isEmpty {
                    HStack(spacing: 6) {
                        ForEach(Array(photos.enumerated()), id: \.offset) { i, data in
                            PhotoThumb(data: data, side: photos.count == 1 ? 160 : 96).accessibilityLabel("Photo \(i + 1) sent")
                        }
                    }
                }
                PromptLine(text: text)
                // From the node's history: the photos stay there; how many.
                if photos.isEmpty && count > 0 { Text(NodeImages.shared.label(count: Int32(count))).font(.caption).foregroundStyle(.secondary) }
            }
            .padding(.top, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
        case .reply(_, let text, let route):
            ChatBubble(text: text, hasFooter: route != nil || retry) {
                HStack {
                    if let route { Text(route).font(.footnote).foregroundStyle(.secondary).lineLimit(1) }
                    Spacer(minLength: 0)
                    if retry { retryButton() }
                }
            }
        case .kept(_, let text):
            // Quiet: what the agent kept about the owner or the work, on its own.
            Text(text).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("kept")
        case .note(_, let text, let tone):
            HStack(alignment: .firstTextBaseline) {
                // A tool line is code: monospaced.
                Text((tone == .tool ? "› " : "✗ ") + text)
                    .font(.footnote.monospaced())
                    .foregroundStyle(tone == .tool ? Color.secondary : Color.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if retry { retryButton() }
            }
        case .question(_, let approval, let title, let detail, let answer):
            GroupBox {
                VStack(alignment: .leading, spacing: 10) {
                    Text(detail).font(.callout.monospaced()).frame(maxWidth: .infinity, alignment: .leading)
                    switch answer {
                    case nil:
                        HStack(spacing: 8) {
                            Button { model.answer(approval, .once) } label: { Text("Allow").frame(maxWidth: .infinity) }
                                .glassProminentButton()
                            Button { model.answer(approval, .always) } label: { Text("Always").frame(maxWidth: .infinity) }
                                .glassButton()
                            Button(role: .destructive) { model.answer(approval, .deny) } label: { Text("Deny").frame(maxWidth: .infinity) }
                                .glassButton()
                        }
                        .controlSize(.large)
                        // The quiet third answer: no, and do this instead.
                        if otherFor == approval {
                            HStack(spacing: 8) {
                                TextField("What to do instead", text: $otherText)
                                    .textFieldStyle(.roundedBorder)
                                Button("Send") {
                                    model.answerInstead(approval, otherText)
                                    otherFor = nil
                                    otherText = ""
                                }
                                .glassButton()
                                .disabled(otherText.trimmingCharacters(in: .whitespaces).isEmpty)
                            }
                        } else {
                            Button("Something else…") {
                                otherFor = approval
                                otherText = ""
                            }
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .accessibilityHint("Say no, and what to do instead")
                        }
                    case .always?: answered("allowed for this conversation")
                    case .deny?: answered("denied")
                    case .instead(let text)?: answered("asked instead: \(text)")
                    default: answered("allowed")
                    }
                }
            } label: {
                Text(title).font(.headline)
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Approval: \(title) \(detail)")
        }
    }

    private func answered(_ text: String) -> some View {
        Text(text).font(.footnote).foregroundStyle(.secondary).lineLimit(3)
    }
}

/// The agent's scheduled tasks on the machine: list, remove, add.
struct NodeSchedulesSheet: View {
    let model: NodeAgentModel
    @Environment(\.dismiss) private var dismiss
    @State private var schedule = ""
    @State private var task = ""
    @State private var telegram = false
    @State private var adding = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    if let problem = model.scheduleProblem {
                        Text(problem).foregroundStyle(.red)
                    }
                    if let list = model.schedules {
                        if list.isEmpty { Text("Nothing scheduled yet.").foregroundStyle(.secondary) }
                        ForEach(list, id: \.id) { item in
                            scheduleRow(item)
                                .swipeActions {
                                    Button(role: .destructive) { Task { await model.removeSchedule(item.id) } } label: {
                                        Label("Remove \(item.task)", systemImage: "trash")
                                    }
                                }
                        }
                    } else {
                        ProgressView()
                    }
                } header: {
                    Text("Scheduled on \(model.node)")
                } footer: {
                    Text("Runs on the machine with nobody to ask: only allowlisted commands and writes in its working directory.")
                }
                Section("Add") {
                    TextField("Schedule", text: $schedule, prompt: Text("0 8 * * 1-5 · @daily · every 30m"))
                        .font(.body.monospaced())
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    TextField("Task", text: $task, prompt: Text("What to do, e.g. check disk space and warn me"), axis: .vertical)
                        .lineLimit(2 ... 5)
                    Toggle("Send answers to Telegram", isOn: $telegram)
                }
                Section {
                    Button {
                        adding = true
                        Task {
                            if await model.addSchedule(schedule, task: task, telegram: telegram) { schedule = ""; task = "" }
                            adding = false
                        }
                    } label: {
                        Group { if adding { ProgressView() } else { Text("Schedule") } }.frame(maxWidth: .infinity)
                    }
                    .glassProminentButton()
                    .controlSize(.large)
                    .disabled(schedule.isEmpty || task.isEmpty || adding)
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            .formStyle(.grouped)
            .navigationTitle("Scheduled tasks")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .presentationDetents([.large])
    }

    private func scheduleRow(_ item: AgentSchedule) -> some View {
        let format = { (seconds: Int64) in Date(timeIntervalSince1970: TimeInterval(seconds)).formatted(date: .abbreviated, time: .shortened) }
        return HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 3) {
                Text(item.task)
                Text([item.schedule, item.next.map { "next \(format($0.int64Value))" }, item.telegram ? "→ Telegram" : nil].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary)
                if let at = item.lastAt {
                    Text("\(item.lastOk?.boolValue == true ? "✓" : "✗") \(format(at.int64Value)) · \(item.lastResult?.split(separator: "\n").first.map(String.init) ?? "")")
                        .font(.footnote)
                        .foregroundStyle(item.lastOk?.boolValue == true ? Color.secondary : Color.red)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            Button { Task { await model.removeSchedule(item.id) } } label: {
                Image(systemName: "trash")
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Remove \(item.task)")
        }
    }
}

/// What the node answered, what its agent did, and what it remembers: from the node's own logs.
struct NodeActivitySheet: View {
    let model: NodeAgentModel
    @Environment(\.dismiss) private var dismiss
    @State private var tab: Int
    private static let tabs = ["requests", "actions", "board", "memory"]
    /// The board's tab, to open the sheet on it.
    static let boardTab = 2

    init(model: NodeAgentModel, initialTab: Int = 0) {
        self.model = model
        _tab = State(initialValue: initialTab)
    }
    @State private var answers: [String: String] = [:]
    private let words = NodeActivityWords.shared

    var body: some View {
        NavigationStack {
            List {
                Section {
                    let waiting = model.activity?.waiting.count ?? 0
                    Picker("Activity", selection: $tab) {
                        ForEach(Self.tabs.indices, id: \.self) { i in
                            Text(Self.tabs[i] == "board" && waiting > 0 ? "board · \(waiting)" : Self.tabs[i]).tag(i)
                        }
                    }
                    .pickerStyle(.segmented)
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
                }
                if let problem = model.activityProblem {
                    Section { Text(problem).foregroundStyle(.red) }
                }
                if let activity = model.activity {
                    switch tab {
                    case 0: requests(activity)
                    case 1: actions(activity)
                    case 2: board(activity)
                    default: memory(activity)
                    }
                } else if model.activityProblem == nil {
                    Section { ProgressView().frame(maxWidth: .infinity) }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("Activity on \(model.node)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button { Task { await model.loadActivity() } } label: { Label("Refresh", systemImage: "arrow.clockwise") }
                }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .refreshable { await model.loadActivity() }
        }
        .presentationDetents([.large])
    }

    private func time(_ seconds: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(seconds)).formatted(date: .abbreviated, time: .shortened)
    }

    private func problemSection(_ problem: String) -> some View {
        Section { Text(problem).foregroundStyle(.red) }
    }

    @ViewBuilder private func requests(_ activity: NodeActivity) -> some View {
        Section {
            if activity.requests.isEmpty {
                Text("The node has not answered anything yet.").foregroundStyle(.secondary)
            }
            ForEach(Array(activity.requests.enumerated()), id: \.offset) { _, r in
                let clean = r.receipt.verified && r.receipt.egress.isEmpty
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(words.who(r: r)) · \(words.where(r: r))")
                    Text([time(r.at), r.receipt.verified ? "signed ✓" : "not verified", words.left(r: r)].joined(separator: " · "))
                        .font(.footnote)
                        .foregroundStyle(clean ? Color.secondary : Color.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
            }
        } header: {
            Text(words.summary(a: activity)).textCase(nil)
        }
    }

    @ViewBuilder private func actions(_ activity: NodeActivity) -> some View {
        if let problem = activity.agentProblem {
            problemSection(problem)
        } else {
            Section {
                if activity.actions.isEmpty {
                    Text("The agent has not asked to do anything yet.").foregroundStyle(.secondary)
                }
                ForEach(Array(activity.actions.enumerated()), id: \.offset) { _, a in
                    VStack(alignment: .leading, spacing: 2) {
                        // The action is a command or a path: code.
                        Text(a.action).font(.callout.monospaced()).lineLimit(3)
                        Text("\(time(a.at)) · \(words.decided(a: a)) · \(a.session)")
                            .font(.footnote)
                            .foregroundStyle(a.ran ? Color.secondary : Color.red)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
    }

    @ViewBuilder private func board(_ activity: NodeActivity) -> some View {
        if let problem = activity.agentProblem {
            problemSection(problem)
        } else if activity.board.isEmpty {
            Section {
                Text("Nothing on the board. When the agent plans work for its team (board_plan), its tasks show here.")
                    .foregroundStyle(.secondary)
            }
        } else {
            let plans = activity.board.reduce(into: [String]()) { if $0.last != $1.plan { $0.append($1.plan) } }
            ForEach(Array(plans.enumerated()), id: \.offset) { _, plan in
                Section("Plan \(plan)") {
                    ForEach(activity.board.filter { $0.plan == plan }, id: \.id) { task in boardTask(task) }
                }
            }
        }
    }

    private func boardTask(_ task: BoardTask) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(words.task(t: task))
            if (task.status == "done" || task.status == "failed"), let result = task.result {
                Text(result.split(separator: "\n").first.map(String.init) ?? "").font(.footnote).foregroundStyle(.secondary).lineLimit(3)
            }
            if task.waiting {
                Text("\(task.agent) asks: \(task.question ?? "")").fixedSize(horizontal: false, vertical: true)
                TextField("Your answer", text: Binding(get: { answers[task.id] ?? "" }, set: { answers[task.id] = $0 }), axis: .vertical)
                    .lineLimit(1 ... 4)
                    .textFieldStyle(.roundedBorder)
                Button("Answer") {
                    let text = answers[task.id] ?? ""
                    Task { if await model.answerBoard(task.id, text) { answers[task.id] = nil } }
                }
                .glassProminentButton()
                .disabled((answers[task.id] ?? "").trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    @ViewBuilder private func memory(_ activity: NodeActivity) -> some View {
        if let problem = activity.agentProblem {
            problemSection(problem)
        } else {
            Section("Remembered") {
                if activity.facts.isEmpty {
                    Text("Nothing yet. The agent keeps short facts when they will help later.").foregroundStyle(.secondary)
                }
                ForEach(activity.facts, id: \.id) { fact in
                    HStack {
                        Text(fact.text).fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 8)
                        Button { Task { await model.forget(fact.id) } } label: { Image(systemName: "trash") }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Forget: \(fact.text)")
                    }
                    .swipeActions {
                        Button(role: .destructive) { Task { await model.forget(fact.id) } } label: { Label("Forget", systemImage: "trash") }
                    }
                }
            }
            Section("Skills") {
                if activity.skills.isEmpty {
                    Text("None yet: put Markdown how-to notes in ~/.autobot/skills on the machine.").foregroundStyle(.secondary)
                }
                ForEach(activity.skills, id: \.name) { skill in
                    VStack(alignment: .leading, spacing: 2) {
                        // A skill's name is its file name: code.
                        Text(skill.name).font(.callout.monospaced())
                        Text(skill.description_).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

import AutobotShared
import Observation
import SwiftUI

/// One line of the agent transcript on screen.
enum NodeAgentRow: Identifiable, Equatable {
    case user(id: Int, text: String)
    case reply(id: Int, text: String, route: String?)
    /// A tool run, a refusal or a failure: one short line.
    case note(id: Int, text: String, tone: Tone)
    case question(id: Int, approval: String, title: String, detail: String, answer: Answer?)

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
        case .user(let id, _), .reply(let id, _, _), .note(let id, _, _), .question(let id, _, _, _, _): id
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
            case "user": .user(id: next(), text: line.text)
            case "tool": .note(id: next(), text: line.text, tone: .tool)
            default: .reply(id: next(), text: line.text, route: nil)
            }
        }
        // Approvals waiting anywhere on the node, as cards (an older node cannot list them: none shown).
        let waiting = await runtime.nodeAgentApprovals(providerId)
        rows = Self.waiting(history, waiting, severalBots: severalBots) { [self] in next() }
    }

    func send(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let runtime, !trimmed.isEmpty, !running, info != nil else { return }
        rows.append(.user(id: next(), text: trimmed))
        running = true
        route = nil
        turn = runtime.nodeAgentTurn(providerId, session: session, text: trimmed, bot: bot) { [weak self] event in self?.apply(event) }
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
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var model: NodeAgentModel
    @State private var draft = ""
    @State private var showSchedules = false
    @State private var showActivity = false

    private let open: NodeAgentSheet?

    @State private var otherFor: String?
    @State private var otherText = ""

    init(providerId: String, open: NodeAgentSheet? = nil, session: String? = nil, bot: String? = nil) {
        _model = State(initialValue: NodeAgentModel(providerId: providerId, session: session, bot: bot))
        _showActivity = State(initialValue: open != nil)
        self.open = open
    }

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: "Agent · \(model.node)", onBack: { nav.back() }) {
                IconAction(symbol: "list.bullet.rectangle", label: "Activity: what the node and the agent did", enabled: model.connected) {
                    showActivity = true
                    Task { await model.loadActivity() }
                }
                IconAction(symbol: "calendar", label: "Scheduled tasks", enabled: model.info != nil) {
                    showSchedules = true
                    Task { await model.loadSchedules() }
                }
                IconAction(symbol: "arrow.clockwise", label: "New conversation", enabled: model.info != nil && !model.running) {
                    Task { await model.newConversation() }
                }
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        header
                        ForEach(model.rows) { row in rowView(row).id(row.id) }
                        if model.running { InkLoader(.inline) }
                    }
                    .padding(.horizontal, Tokens.Spacing.gutter)
                    .padding(.vertical, 8)
                }
                .onChange(of: model.rows.count) {
                    if let last = model.rows.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
            }
            HStack(alignment: .bottom, spacing: 8) {
                ConsoleTextField(text: $draft, placeholder: "A task for your machine", minLines: 1, maxLines: 5, enabled: model.info != nil)
                IconAction(symbol: "paperplane.fill", label: "Send", tint: c.primary, enabled: !draft.trimmingCharacters(in: .whitespaces).isEmpty && !model.running && model.info != nil) {
                    model.send(draft)
                    draft = ""
                }
            }
            .padding(Tokens.Spacing.gutter)
        }
        .background(c.background)
        .task {
            await model.connect(app: app)
            if open != nil { await model.loadActivity() }
        }
        .onDisappear { model.stopListening() }
        .sheet(isPresented: $showSchedules) { NodeSchedulesSheet(model: model) }
        .sheet(isPresented: $showActivity) { NodeActivitySheet(model: model, initialTab: open == .board ? NodeActivitySheet.boardTab : 0) }
    }

    @ViewBuilder private var header: some View {
        if let problem = model.problem {
            VStack(alignment: .leading, spacing: 8) {
                Text(problem).textStyle(.bodyMedium).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
                GhostButton("Retry") { Task { await model.connect(app: app) } }
            }
        } else if let info = model.info {
            let folder = info.workdir.split(separator: "/").suffix(2).joined(separator: "/")
            // Several bots: which one this conversation is with.
            SectionLabel([model.severalBots ? "bot \(NodeBots.shared.of(bot: model.bot))" : nil, "runs on \(model.node)", "approvals: \(info.approvals)", info.private_ ? "local models only" : nil, "…/\(folder)"].compactMap { $0 }.joined(separator: " · "))
        } else {
            InkLoader(.inline)
        }
    }

    @ViewBuilder private func rowView(_ row: NodeAgentRow) -> some View {
        switch row {
        case .user(_, let text):
            ChatBubble(role: .user, text: text, hasFooter: false) { EmptyView() }
        case .reply(_, let text, let route):
            ChatBubble(role: .assistant, text: text, hasFooter: route != nil) {
                if let route { MicroLabel(route) }
            }
        case .note(_, let text, let tone):
            Text((tone == .tool ? "› " : "✗ ") + text)
                .textStyle(.code)
                .foregroundStyle(tone == .tool ? c.onSurfaceVariant : c.error)
        case .question(_, let approval, let title, let detail, let answer):
            VStack(alignment: .leading, spacing: 8) {
                Text(title).textStyle(.titleSmall).foregroundStyle(c.onSurface)
                Text(detail).textStyle(.code).foregroundStyle(c.onSurface)
                switch answer {
                case nil:
                    HStack(spacing: 8) {
                        PrimaryButton("Allow") { model.answer(approval, .once) }
                        GhostButton("Always") { model.answer(approval, .always) }
                        GhostButton("Deny") { model.answer(approval, .deny) }
                    }
                    // The quiet third answer: no, and do this instead.
                    if otherFor == approval {
                        HStack(alignment: .bottom, spacing: 8) {
                            ConsoleTextField(text: $otherText, placeholder: "What to do instead", minLines: 1, maxLines: 1)
                            GhostButton("Send", enabled: !otherText.trimmingCharacters(in: .whitespaces).isEmpty) {
                                model.answerInstead(approval, otherText)
                                otherFor = nil
                                otherText = ""
                            }
                            .fixedSize()
                        }
                    } else {
                        TextAction("Something else…", color: c.onSurfaceVariant) {
                            otherFor = approval
                            otherText = ""
                        }
                        .accessibilityHint("Say no, and what to do instead")
                    }
                case .always?: MicroLabel("allowed for this conversation")
                case .deny?: MicroLabel("denied")
                case .instead(let text)?: MicroLabel("asked instead: \(text)", lines: 3)
                default: MicroLabel("allowed")
                }
            }
            .padding(12)
            .overlay(RoundedRectangle(cornerRadius: AutobotShapes.medium).stroke(c.outlineVariant))
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Approval: \(title) \(detail)")
        }
    }
}

/// The agent's scheduled tasks on the machine: list, remove, add.
struct NodeSchedulesSheet: View {
    let model: NodeAgentModel
    @Environment(\.scheme) private var c
    @State private var schedule = ""
    @State private var task = ""
    @State private var telegram = false
    @State private var adding = false

    var body: some View {
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel("scheduled on \(model.node)")
                Text("Runs on the machine with nobody to ask: only allowlisted commands and writes in its working directory.")
                    .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                if let problem = model.scheduleProblem {
                    Text(problem).textStyle(.bodySmall).foregroundStyle(c.error).fixedSize(horizontal: false, vertical: true)
                }
                if let list = model.schedules {
                    if list.isEmpty { Text("Nothing scheduled yet.").textStyle(.bodyMedium).foregroundStyle(c.onSurface) }
                    ForEach(list, id: \.id) { item in scheduleRow(item) }
                } else {
                    InkLoader(.inline)
                }
                SectionLabel("add")
                ConsoleTextField(text: $schedule, placeholder: "0 8 * * 1-5 · @daily · every 30m", kind: .mono)
                ConsoleTextField(text: $task, placeholder: "What to do, e.g. check disk space and warn me", minLines: 2, maxLines: 5)
                SwitchRow(title: "Send answers to Telegram", isOn: $telegram)
                PrimaryButton("Schedule", enabled: !schedule.isEmpty && !task.isEmpty, busy: adding) {
                    adding = true
                    Task {
                        if await model.addSchedule(schedule, task: task, telegram: telegram) { schedule = ""; task = "" }
                        adding = false
                    }
                }
            }
            .padding(Tokens.Spacing.gutter)
        }
        .background(c.background)
        .presentationDetents([.large])
    }

    private func scheduleRow(_ item: AgentSchedule) -> some View {
        let format = { (seconds: Int64) in Date(timeIntervalSince1970: TimeInterval(seconds)).formatted(date: .abbreviated, time: .shortened) }
        return HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 3) {
                Text(item.task).textStyle(.bodyLarge).foregroundStyle(c.onSurface)
                MicroLabel([item.schedule, item.next.map { "next \(format($0.int64Value))" }, item.telegram ? "→ Telegram" : nil].compactMap { $0 }.joined(separator: " · "))
                if let at = item.lastAt {
                    Text("\(item.lastOk?.boolValue == true ? "✓" : "✗") \(format(at.int64Value)) · \(item.lastResult?.split(separator: "\n").first.map(String.init) ?? "")")
                        .textStyle(.bodySmall)
                        .foregroundStyle(item.lastOk?.boolValue == true ? c.onSurfaceVariant : c.error)
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 8)
            IconAction(symbol: "trash", label: "Remove \(item.task)") { Task { await model.removeSchedule(item.id) } }
        }
    }
}

/// What the node answered, what its agent did, and what it remembers: from the node's own logs.
struct NodeActivitySheet: View {
    let model: NodeAgentModel
    @Environment(\.scheme) private var c
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
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                HStack {
                    SectionLabel("activity on \(model.node)")
                    Spacer()
                    IconAction(symbol: "arrow.clockwise", label: "Refresh") { Task { await model.loadActivity() } }
                }
                HStack(spacing: 8) {
                    ForEach(Self.tabs.indices, id: \.self) { i in
                        let waiting = model.activity?.waiting.count ?? 0
                        Tag(Self.tabs[i] == "board" && waiting > 0 ? "board · \(waiting)" : Self.tabs[i], accent: c.primary, filled: tab == i) { tab = i }
                            .accessibilityAddTraits(tab == i ? .isSelected : [])
                    }
                }
                if let problem = model.activityProblem {
                    Text(problem).textStyle(.bodySmall).foregroundStyle(c.error).fixedSize(horizontal: false, vertical: true)
                }
                if let activity = model.activity {
                    switch tab {
                    case 0: requests(activity)
                    case 1: actions(activity)
                    case 2: board(activity)
                    default: memory(activity)
                    }
                } else if model.activityProblem == nil {
                    InkLoader(.inline)
                }
            }
            .padding(Tokens.Spacing.gutter)
        }
        .background(c.background)
        .presentationDetents([.large])
    }

    private func time(_ seconds: Int64) -> String {
        Date(timeIntervalSince1970: TimeInterval(seconds)).formatted(date: .abbreviated, time: .shortened)
    }

    @ViewBuilder private func requests(_ activity: NodeActivity) -> some View {
        Text(words.summary(a: activity)).textStyle(.bodyMedium).foregroundStyle(c.onSurface)
        if activity.requests.isEmpty {
            Text("The node has not answered anything yet.").textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
        }
        ForEach(Array(activity.requests.enumerated()), id: \.offset) { _, r in
            let clean = r.receipt.verified && r.receipt.egress.isEmpty
            VStack(alignment: .leading, spacing: 2) {
                Text("\(words.who(r: r)) · \(words.where(r: r))").textStyle(.bodyLarge).foregroundStyle(c.onSurface)
                Text([time(r.at), r.receipt.verified ? "signed ✓" : "not verified", words.left(r: r)].joined(separator: " · "))
                    .textStyle(.code)
                    .foregroundStyle(clean ? c.onSurfaceVariant : c.error)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder private func actions(_ activity: NodeActivity) -> some View {
        if let problem = activity.agentProblem {
            Text(problem).textStyle(.bodySmall).foregroundStyle(c.error).fixedSize(horizontal: false, vertical: true)
        } else {
            if activity.actions.isEmpty {
                Text("The agent has not asked to do anything yet.").textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
            }
            ForEach(Array(activity.actions.enumerated()), id: \.offset) { _, a in
                VStack(alignment: .leading, spacing: 2) {
                    Text(a.action).textStyle(.code).foregroundStyle(c.onSurface).lineLimit(3)
                    Text("\(time(a.at)) · \(words.decided(a: a)) · \(a.session)")
                        .textStyle(.bodySmall)
                        .foregroundStyle(a.ran ? c.onSurfaceVariant : c.error)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    @ViewBuilder private func board(_ activity: NodeActivity) -> some View {
        if let problem = activity.agentProblem {
            Text(problem).textStyle(.bodySmall).foregroundStyle(c.error).fixedSize(horizontal: false, vertical: true)
        } else {
            if activity.board.isEmpty {
                Text("Nothing on the board. When the agent plans work for its team (board_plan), its tasks show here.")
                    .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
            }
            ForEach(Array(activity.board.enumerated()), id: \.element.id) { i, task in
                if i == 0 || activity.board[i - 1].plan != task.plan { SectionLabel("plan \(task.plan)") }
                VStack(alignment: .leading, spacing: 4) {
                    Text(words.task(t: task)).textStyle(.bodyMedium).foregroundStyle(c.onSurface)
                    if (task.status == "done" || task.status == "failed"), let result = task.result {
                        Text(result.split(separator: "\n").first.map(String.init) ?? "").textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).lineLimit(3)
                    }
                    if task.waiting {
                        Text("\(task.agent) asks: \(task.question ?? "")").textStyle(.bodyMedium).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
                        ConsoleTextField(text: Binding(get: { answers[task.id] ?? "" }, set: { answers[task.id] = $0 }), placeholder: "Your answer", minLines: 1, maxLines: 4)
                        PrimaryButton("Answer", enabled: !(answers[task.id] ?? "").trimmingCharacters(in: .whitespaces).isEmpty) {
                            let text = answers[task.id] ?? ""
                            Task { if await model.answerBoard(task.id, text) { answers[task.id] = nil } }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder private func memory(_ activity: NodeActivity) -> some View {
        if let problem = activity.agentProblem {
            Text(problem).textStyle(.bodySmall).foregroundStyle(c.error).fixedSize(horizontal: false, vertical: true)
        } else {
            SectionLabel("remembered")
            if activity.facts.isEmpty {
                Text("Nothing yet. The agent keeps short facts when they will help later.").textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
            }
            ForEach(activity.facts, id: \.id) { fact in
                HStack(alignment: .center) {
                    Text(fact.text).textStyle(.bodyMedium).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    IconAction(symbol: "trash", label: "Forget: \(fact.text)") { Task { await model.forget(fact.id) } }
                }
            }
            SectionLabel("skills")
            if activity.skills.isEmpty {
                Text("None yet: put Markdown how-to notes in ~/.autobot/skills on the machine.").textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
            }
            ForEach(activity.skills, id: \.name) { skill in
                VStack(alignment: .leading, spacing: 2) {
                    Text(skill.name).textStyle(.code).foregroundStyle(c.onSurface)
                    Text(skill.description_).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
                }
            }
        }
    }
}

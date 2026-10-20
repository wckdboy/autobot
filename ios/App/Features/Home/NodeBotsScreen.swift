import AutobotShared
import Observation
import SwiftUI

/// Unix seconds now, for the bots' "active 5m ago".
private func unixNow() -> Int64 { Int64(Date().timeIntervalSince1970) }

/**
 Debug builds' sample bots (`-AutobotDemo`): the demo's paired node ("demo-node") has three bots,
 so Remote and Bot detail have something to show without a node.
 */
enum BotsDemo {
    static let nodeId = "demo-node"
    static let nodeName = "studio-mac"

    static func on(_ providerId: String) -> Bool {
        #if DEBUG
        DebugFlags.demo && providerId == nodeId
        #else
        false
        #endif
    }

    static func state(now: Int64 = unixNow()) -> NodeBotsState {
        NodeBotsState(bots: NodeBots.shared.ordered(bots: NodeBots.shared.sample(now: now)), supported: KotlinBoolean(value: true), loading: false, problem: nil, notice: nil)
    }
}

/// Each paired node's bots, for Remote's list (bots mode; an older node has none and shows no list).
@MainActor
@Observable
final class NodeBotsModel {
    private(set) var states: [String: NodeBotsState] = [:]

    /// Asks node [id] to bring its bots in line with their configuration now; what it did shows under the list.
    func reload(app: AppContainer, node id: String) async {
        if BotsDemo.on(id) {
            let s = BotsDemo.state()
            states[id] = s.doCopy(bots: s.bots, supported: s.supported, loading: false, problem: nil, notice: "Nothing to change.")
            return
        }
        guard let runtime = app.agent as? SharedAgentRuntime else { return }
        let state = await runtime.nodeBotsReload(id)
        // A node that did not answer keeps the list it showed, with why.
        if state.problem == nil || states[id] == nil { states[id] = state } else if let old = states[id] {
            states[id] = old.doCopy(bots: old.bots, supported: old.supported, loading: false, problem: state.problem, notice: state.notice)
        }
    }

    func refresh(app: AppContainer, nodes: [String]) async {
        let runtime = app.agent as? SharedAgentRuntime
        // One paired node is the usual case: one at a time is fine.
        for id in nodes {
            if BotsDemo.on(id) { states[id] = BotsDemo.state(); continue }
            guard let runtime else { continue }
            let state = await runtime.nodeBots(id)
            // A node that did not answer keeps what it showed (or nothing).
            if state.problem == nil { states[id] = state }
        }
    }
}

/**
 One bot in Remote's list: name and model, the persona line, its state in words with the channel
 kinds as a quiet tag, a channel that is down in red (words first), and quiet meta.
 */
struct BotRow: View {
    let bot: NodeBot
    let now: Int64
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(bot.name).font(.headline).lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        if !bot.model.isEmpty { TintTag(bot.model, color: .secondary) }
                    }
                    if !bot.persona.isEmpty {
                        Text(bot.persona).font(.subheadline).foregroundStyle(Color.secondary).lineLimit(2)
                    }
                    HStack(spacing: 8) {
                        Text(NodeBots.shared.status(bot: bot)).font(.caption)
                        let kinds = NodeBots.shared.kinds(bot: bot)
                        if !kinds.isEmpty { TintTag(kinds.joined(separator: " · "), color: .secondary) }
                    }
                    if let problem = NodeBots.shared.problem(bot: bot) {
                        Text(problem).font(.caption).foregroundStyle(Color.red).fixedSize(horizontal: false, vertical: true)
                    }
                    Text(NodeBots.shared.meta(bot: bot, now: now)).font(.caption).foregroundStyle(Color.secondary)
                }
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color(uiColor: .tertiaryLabel))
            }
            .foregroundStyle(Color.primary)
            .contentShape(Rectangle())
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(NodeBots.shared.spoken(bot: bot, now: now))
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("bot-\(bot.name)")
    }
}

/// One bot on a paired node: its state, channels, sessions and approvals; switch it on or off.
@MainActor
@Observable
final class NodeBotModel {
    let providerId: String
    let name: String
    private(set) var node = ""
    private(set) var state = NodeBotsState(bots: [], supported: nil, loading: true, problem: nil, notice: nil)
    /// Its personality, profile, usage and insights; nil until loaded (each part nil on an older node).
    private(set) var detail: NodeBotDetail?
    /// A personality being set ("none" for none).
    private(set) var settingPersonality: String?
    /// Its recent sessions, newest first; nil until loaded.
    private(set) var sessions: [AutobotShared.AgentSession]?
    private(set) var approvals: [PendingApproval] = []
    private(set) var answered: [String: ApprovalAnswer] = [:]
    private(set) var problem: String?
    private(set) var now = unixNow()
    @ObservationIgnored private weak var runtime: SharedAgentRuntime?

    var bot: NodeBot? { state.bot(name: name) }

    init(providerId: String, name: String) {
        self.providerId = providerId
        self.name = name
    }

    func refresh(app: AppContainer) async {
        // A deep link can arrive before the demo seed: the demo node has its name anyway.
        node = app.providers.get(providerId)?.displayName ?? (BotsDemo.on(providerId) ? BotsDemo.nodeName : "my node")
        now = unixNow()
        if BotsDemo.on(providerId) {
            state = BotsDemo.state(now: now)
            sessions = NodeBots.shared.sampleSessions(now: now).filter { NodeBots.shared.of(bot: $0.bot) == name }
            approvals = NodeBots.shared.sampleApprovals(now: now).filter { NodeBots.shared.of(bot: $0.bot) == name }
            if detail == nil { detail = NodeBotDetails.shared.sample(bot: name) }
            return
        }
        runtime = app.agent as? SharedAgentRuntime
        guard let runtime else { return problem = "My node is not available here" }
        state = await runtime.nodeBots(providerId)
        problem = state.problem
        let (list, error) = await runtime.nodeAgentSessions(providerId, bot: name)
        sessions = list ?? []
        if let error { problem = error }
        approvals = await runtime.nodeAgentApprovals(providerId).filter { NodeBots.shared.of(bot: $0.bot) == name }
        let edits = personalityEdits
        let fetched = await runtime.nodeBotDetail(providerId, bot: name)
        // A personality chosen while this was asked for wins over what the node said before it.
        if edits != personalityEdits, let current = detail?.personalities {
            detail = Self.with(fetched, current)
        } else {
            detail = fetched
        }
    }

    /// Personalities set here, so a refresh that started before one does not undo it on screen.
    @ObservationIgnored private var personalityEdits = 0

    /// Sets the bot's personality ([personality] nil for none): shown at once, put back when the node says no.
    func setPersonality(_ personality: String?) async {
        guard let detail, let before = detail.personalities else { return }
        personalityEdits += 1
        let wanted = personality == NodeBotDetails.shared.NONE ? nil : personality
        self.detail = Self.with(detail, NodePersonalities(active: wanted, soul: before.soul, list: before.list))
        settingPersonality = wanted ?? NodeBotDetails.shared.NONE
        problem = nil
        defer { settingPersonality = nil }
        guard !BotsDemo.on(providerId), let runtime else { return }
        let (active, error) = await runtime.nodeBotSetPersonality(providerId, bot: name, name: wanted)
        guard let current = self.detail else { return }
        if let error {
            self.detail = Self.with(current, before)
            problem = error
        } else {
            self.detail = Self.with(current, NodePersonalities(active: active, soul: before.soul, list: before.list))
        }
    }

    /// Shows [detail] as if the node had answered (tests).
    func show(detail: NodeBotDetail) { self.detail = detail }

    nonisolated static func with(_ d: NodeBotDetail, _ p: NodePersonalities) -> NodeBotDetail {
        d.doCopy(personalities: p, profile: d.profile, usage: d.usage, insights: d.insights, problem: d.problem)
    }

    func setEnabled(_ enabled: Bool) async {
        guard let before = bot else { return }
        state = state.doCopy(bots: state.bots.map { $0.name == name ? NodeBots.shared.switched(bot: $0, enabled: enabled) : $0 }, supported: state.supported, loading: false, problem: nil, notice: nil)
        guard !BotsDemo.on(providerId), let runtime else {
            let answer = BotSwitch(enabled: enabled, restart: false, status: enabled ? "running" : "stopped", error: nil)
            state = state.doCopy(bots: state.bots, supported: state.supported, loading: false, problem: nil, notice: NodeBotDetails.shared.switched(name: name, s: answer))
            return
        }
        let (answer, error) = await runtime.nodeBotSetEnabled(providerId, name: name, enabled: enabled)
        if let answer {
            // As the node says it is now, and what it did, in words.
            state = state.doCopy(bots: state.bots.map { $0.name == name ? NodeBotDetails.shared.afterSwitch(bot: $0, s: answer) : $0 }, supported: state.supported, loading: false, problem: nil, notice: NodeBotDetails.shared.switched(name: name, s: answer))
        } else {
            // The node said no: put it back as it was.
            let why = error ?? "The node did not answer"
            state = state.doCopy(bots: state.bots.map { $0.name == name ? before : $0 }, supported: state.supported, loading: false, problem: why, notice: nil)
            problem = why
        }
    }

    func answer(_ id: String, _ answer: ApprovalAnswer) {
        answered[id] = answer
        guard let runtime else { return }
        Task {
            if !(await runtime.nodeAgentAnswer(providerId, id: id, answer: answer)) { problem = "That question had expired." }
        }
    }
}

struct NodeBotScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var model: NodeBotModel
    @State private var confirmOff = false

    init(providerId: String, name: String) {
        _model = State(initialValue: NodeBotModel(providerId: providerId, name: name))
    }

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: "Bot · \(model.name)", onBack: { nav.back() }) {
                Button { Task { await model.refresh(app: app) } } label: { Label("Refresh", systemImage: "arrow.clockwise") }
            }
            List {
                NodeBotContent(model: model, confirmOff: $confirmOff) { session in
                    nav.push(.nodeAgent(providerId: model.providerId, session: session, bot: model.name))
                }
            }
            .listStyle(.insetGrouped)
            .refreshable { await model.refresh(app: app) }
        }
        .task { await model.refresh(app: app) }
        .alert("Turn off \(model.name)?", isPresented: $confirmOff) {
            Button("Turn off", role: .destructive) { Task { await model.setEnabled(false) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(Self.offMessage(model.bot))
        }
    }

    /// "It stops answering on telegram and slack until you turn it on again."
    nonisolated static func offMessage(_ bot: NodeBot?) -> String {
        let kinds = bot.map { NodeBots.shared.kinds(bot: $0).filter { $0 != "api" } } ?? []
        return kinds.isEmpty ? "It stops answering until you turn it on again." : "It stops answering on \(kinds.joined(separator: " and ")) until you turn it on again."
    }
}

/// The detail's body (List sections), apart from its navigation, so tests and snapshots can render it.
struct NodeBotContent: View {
    let model: NodeBotModel
    @Binding var confirmOff: Bool
    /// Opens a session (nil: a new chat with the bot).
    let open: (String?) -> Void

    var body: some View {
        if let problem = model.problem {
            Section { Text(problem).foregroundStyle(.red) }
        }
        if let bot = model.bot {
            content(bot)
        } else if model.state.loading || (model.state.supported == nil && model.problem == nil) {
            Section { ProgressView().frame(maxWidth: .infinity) }
        } else {
            MarkUnavailableView("There is no bot called \(model.name) on \(model.node).")
                .listRowBackground(Color.clear)
        }
    }

    @ViewBuilder private func content(_ bot: NodeBot) -> some View {
        let failed = bot.status == "error"
        Section {
            Toggle(isOn: Binding(get: { bot.enabled }, set: { on in
                if on { Task { await model.setEnabled(true) } } else { confirmOff = true }
            })) {
                Text("On")
                Text(NodeBots.shared.status(bot: bot) + (failed ? bot.error.map { ": \($0) ✗" } ?? "" : ""))
                    .foregroundStyle(failed ? Color.red : Color.secondary)
            }
            .accessibilityLabel("On")
        } header: {
            // The name is in the bar; the head says who it is, after Invincible's pixel i (about the node), as on Android.
            VStack(alignment: .leading, spacing: 6) {
                if !bot.persona.isEmpty {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        NodeMark()
                        Text(bot.persona).font(.body).foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
                    }
                } else {
                    NodeMark()
                }
                Text("on \(model.node) · \(NodeBots.shared.meta(bot: bot, now: model.now))")
            }
            .textCase(nil)
        } footer: {
            // What the node said it did ("ops is running.").
            if let notice = model.state.notice { Text(notice) }
        }

        Section("Channels") {
            if bot.channels.isEmpty {
                Text("Only through the API.").foregroundStyle(.secondary)
            }
            ForEach(Array(bot.channels.enumerated()), id: \.offset) { _, channel in
                VStack(alignment: .leading, spacing: 2) {
                    // Large text wraps the state under the name (LabeledContent does).
                    LabeledContent(channel.kind) {
                        Text(NodeBots.shared.channelState(c: channel)).foregroundStyle(channel.down ? Color.red : Color.secondary)
                    }
                    if !channel.account.isEmpty {
                        Text(channel.account).font(.caption).foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }

        Section {
            LabeledContent("Model", value: bot.model.isEmpty ? "auto" : bot.model)
            LabeledContent("Approvals", value: bot.approvals)
        } header: {
            Text("Model and approvals")
        } footer: {
            Text(NodeBots.shared.approvals(mode: bot.approvals))
        }

        if let detail = model.detail {
            BotDetailSections(detail: detail, bot: bot.name, node: model.node, setting: model.settingPersonality) { name in
                Task { await model.setPersonality(name) }
            }
        } else {
            Section { ProgressView().frame(maxWidth: .infinity) }
        }

        if !model.approvals.isEmpty {
            Section("Waiting for you · \(model.approvals.count)") {
                ForEach(model.approvals, id: \.id) { a in approval(a) }
            }
        }

        Section("Recent sessions") {
            if let sessions = model.sessions {
                if sessions.isEmpty { Text("No sessions yet.").foregroundStyle(.secondary) }
                ForEach(sessions.prefix(8), id: \.id) { s in
                    Button { open(s.id) } label: {
                        HStack {
                            // A session id: code.
                            Text(s.id).font(.callout.monospaced()).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                            Text(NodeBots.shared.ago(at: s.modified, now: model.now)).font(.caption).foregroundStyle(Color.secondary)
                            Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(Color(uiColor: .tertiaryLabel))
                        }
                        .foregroundStyle(Color.primary)
                        .contentShape(Rectangle())
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity)
            }
        }

        Section {
            Button { open(nil) } label: { Text("New chat with \(bot.name)").frame(maxWidth: .infinity) }
                .primaryActionStyle()
                .controlSize(.large)
                .disabled(!bot.enabled)
        }
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
    }

    private func approval(_ a: PendingApproval) -> some View {
        let (title, detail) = NodeAgentModel.describe(a.action, fallback: a.text)
        return VStack(alignment: .leading, spacing: 8) {
            Text(a.agent.map { "\($0): \(title)" } ?? title).font(.headline)
            Text(detail).font(.callout.monospaced())
            switch model.answered[a.id] {
            case nil:
                HStack(spacing: 8) {
                    Button { model.answer(a.id, .once) } label: { Text("Allow").frame(maxWidth: .infinity) }
                        .primaryActionStyle()
                    Button { model.answer(a.id, .always) } label: { Text("Always").frame(maxWidth: .infinity) }
                        .secondaryActionStyle()
                    Button(role: .destructive) { model.answer(a.id, .deny) } label: { Text("Deny").frame(maxWidth: .infinity) }
                        .secondaryActionStyle()
                }
            case .deny?: Text("denied").font(.footnote).foregroundStyle(.secondary)
            case .always?: Text("allowed for this conversation").font(.footnote).foregroundStyle(.secondary)
            default: Text("allowed").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Approval: \(title) \(detail)")
    }
}

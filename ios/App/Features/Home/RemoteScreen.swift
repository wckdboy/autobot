import AutobotShared
import SwiftUI

/// REMOTE tab: everything that leaves the phone — APIs, PCs on the LAN, hub accounts, the route.
struct RemoteScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    /// Each paired node's bots (bots mode).
    @State private var bots = NodeBotsModel()

    var body: some View {
        let providers = app.providers.providers.filter { $0.kind != .local }
        let backends = app.backends.backends.filter { $0.kind != .onDevice }
        let accounts = app.accounts.state
        let status = app.settings.settings.mode.privacyStatus
        VStack(spacing: 0) {
            AutobotTopBar(title: "Remote") {
                // The Privacy Center, with its state: one way in.
                // An HStack, not a Label: the bar would show a Label's icon only.
                Button { nav.push(.privacyCenter) } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "circle.fill").font(.system(size: 8)).foregroundStyle(Self.statusColor(status))
                        Text(status.label)
                    }
                    .padding(.horizontal, 6)
                }
                .accessibilityLabel("Network: \(status.description)")
            }
            List {
                let nodes = providers.filter { $0.kind == .invincibleNode }
                Section("Agents · my node") {
                    if nodes.isEmpty {
                        // No node yet: pairing is one tap from here (as on Android).
                        RemoteRow(title: NodePairingWords.shared.REMOTE_ROW, subtitle: NodePairingWords.shared.REMOTE_ROW_DETAIL, leading: { NodeMark() }) {
                            nav.push(.providers)
                            nav.pendingPairing = ""
                        }
                    }
                    ForEach(nodes) { n in
                        // The Autobot Agent on each paired node: tasks run there, approvals come here.
                        // The node's own mark: Invincible's pixel i.
                        RemoteRow(title: "Agent on \(n.displayName)", subtitle: "runs there · you approve", tag: routeLabel(routeOf(n)), leading: { NodeMark() }) {
                            nav.push(.nodeAgent(providerId: n.id))
                        }
                    }
                }
                // Bots mode: each node's bots, one row each (an older node has no list).
                ForEach(nodes) { n in
                    if let state = bots.states[n.id], state.showList {
                        Section {
                            let now = Int64(Date().timeIntervalSince1970)
                            ForEach(state.bots, id: \.name) { bot in
                                BotRow(bot: bot, now: now) { nav.push(.nodeBot(providerId: n.id, name: bot.name)) }
                            }
                        } header: {
                            HStack {
                                Text(nodes.count > 1 ? "Bots · \(n.displayName)" : "Bots")
                                Spacer()
                                // The node picks up a changed bot.toml or SOUL.md on its own within seconds; this is now.
                                Button("Reload") { Task { await bots.reload(app: app, node: n.id) } }
                                    .font(.subheadline)
                                    .textCase(nil)
                            }
                        } footer: {
                            if let problem = state.problem {
                                Text("\(problem) ✗").foregroundStyle(.red)
                            } else if let notice = state.notice {
                                Text(notice)
                            }
                        }
                    }
                }
                Section("Providers") {
                    ForEach(providers) { p in
                        RemoteRow(title: p.displayName, subtitle: ChatListModel.modelLabel(p.defaultModel), tag: routeLabel(routeOf(p))) { nav.push(.providers) }
                    }
                    PushRow(title: providers.isEmpty ? "Add an API key or server" : "Add or edit") { nav.push(.providers) }
                }
                Section("Image backends") {
                    ForEach(backends) { b in
                        RemoteRow(title: b.name, subtitle: b.kind.label, tag: routeLabel(Route.imageBackend(b, model: nil))) { nav.push(.imageBackends) }
                    }
                    PushRow(title: backends.isEmpty ? "Add an image server" : "Add or edit") { nav.push(.imageBackends) }
                }
                Section("Accounts") {
                    RemoteRow(title: "Hugging Face", subtitle: accounts.huggingFaceUser ?? "not signed in") { nav.push(.accounts) }
                    RemoteRow(title: "Civitai", subtitle: accounts.civitaiUser ?? "no key") { nav.push(.accounts) }
                }
            }
            .listStyle(.insetGrouped)
        }
        // Each time the tab shows: a bot's state changes on the node, not here.
        .task(id: app.providers.providers.filter { $0.kind == .invincibleNode }.map(\.id)) {
            await bots.refresh(app: app, nodes: app.providers.providers.filter { $0.kind == .invincibleNode }.map(\.id))
        }
    }

    /// `● local · cpu` / `◐ pc · lan` / `○ cloud · api`: where a run goes, as a quiet tag.
    private func routeLabel(_ route: Route) -> String { "\(route.glyph) \(route.label)" }

    /// The network mode's dot: the brand tint offline, a system color for each route out.
    static func statusColor(_ status: PrivacyStatus) -> Color {
        switch status {
        case .offline: Color.brandPurple
        case .direct: .orange
        case .tor: .indigo
        case .proxy: .cyan
        }
    }
}

/// A Remote row that opens a screen: optional leading mark, title, a subtitle, a route tag and the chevron.
private struct RemoteRow<Leading: View>: View {
    let title: String
    var subtitle: String = ""
    var tag: String? = nil
    @ViewBuilder var leading: () -> Leading
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                leading()
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(Color.primary).lineLimit(2)
                    if !subtitle.isEmpty {
                        Text(subtitle).font(.subheadline).foregroundStyle(Color.secondary).lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let tag { CapsuleTag(tag, color: .secondary) }
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color(uiColor: .tertiaryLabel))
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
    }
}

extension RemoteRow where Leading == EmptyView {
    init(title: String, subtitle: String = "", tag: String? = nil, action: @escaping () -> Void) {
        self.init(title: title, subtitle: subtitle, tag: tag, leading: { EmptyView() }, action: action)
    }
}

import AutobotShared
import SwiftUI

/// REMOTE tab: everything that leaves the phone — APIs, PCs on the LAN, hub accounts, the route.
struct RemoteScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    /// Each paired node's bots (bots mode).
    @State private var bots = NodeBotsModel()

    var body: some View {
        let providers = app.providers.providers.filter { $0.kind != .local }
        let backends = app.backends.backends.filter { $0.kind != .onDevice }
        let accounts = app.accounts.state
        VStack(spacing: 0) {
            AutobotTopBar(title: "Remote") {
                PrivacyStatusPill(status: app.settings.settings.mode.privacyStatus) { nav.push(.privacyCenter) }
                    // A bordered pill lines up with the content gutter (the bar's end padding is sized for icon targets).
                    .padding(.trailing, Tokens.Spacing.gutter - Tokens.Spacing.topBarPaddingEnd)
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    // The Privacy Center is the status pill above (one way in, with its state).
                    let nodes = providers.filter { $0.kind == .invincibleNode }
                    if !nodes.isEmpty {
                        // The Autobot Agent on each paired node: tasks run there, approvals come here.
                        SectionLabel("agents · my node")
                        ForEach(nodes) { n in
                            RunRow(
                                title: "Agent on \(n.displayName)",
                                subtitle: "runs there · you approve",
                                action: { nav.push(.nodeAgent(providerId: n.id)) },
                                // The node's own mark: Invincible's orange square.
                                leading: { NodeMark() },
                            ) {
                                RouteTag(route: routeOf(n))
                            }
                            // Bots mode: the node's bots, one row each (an older node has no list).
                            if let state = bots.states[n.id], state.showList {
                                HStack {
                                    SectionLabel(nodes.count > 1 ? "bots · \(n.displayName)" : "bots").frame(maxWidth: .infinity, alignment: .leading)
                                    // The node picks up a changed bot.toml or SOUL.md on its own within seconds; this is now.
                                    Tag("Reload") { Task { await bots.reload(app: app, node: n.id) } }
                                }
                                .padding(.top, 12)
                                if let problem = state.problem {
                                    Text("\(problem) ✗").textStyle(.micro).foregroundStyle(c.error).fixedSize(horizontal: false, vertical: true).padding(.bottom, 4)
                                }
                                if let notice = state.notice {
                                    Text(notice).textStyle(.micro).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true).padding(.bottom, 4)
                                }
                                let now = Int64(Date().timeIntervalSince1970)
                                ForEach(state.bots, id: \.name) { bot in
                                    BotRow(bot: bot, now: now) { nav.push(.nodeBot(providerId: n.id, name: bot.name)) }
                                }
                            }
                        }
                        Spacer().frame(height: 24)
                    } else {
                        // No node yet: pairing is one tap from here (as on Android).
                        SectionLabel("agents · my node")
                        RunRow(
                            title: NodePairingWords.shared.REMOTE_ROW,
                            subtitle: NodePairingWords.shared.REMOTE_ROW_DETAIL,
                            action: {
                                nav.push(.providers)
                                nav.pendingPairing = ""
                            },
                            leading: { NodeMark() },
                        ) { Chevron() }
                        Spacer().frame(height: 24)
                    }
                    SectionLabel("providers")
                    ForEach(providers) { p in
                        RunRow(title: p.displayName, subtitle: ChatListModel.modelLabel(p.defaultModel), action: { nav.push(.providers) }) {
                            RouteTag(route: routeOf(p))
                        }
                    }
                    RunRow(title: providers.isEmpty ? "Add an API key or server" : "Add or edit", subtitle: "", action: { nav.push(.providers) }) { Chevron() }
                    Spacer().frame(height: 24)
                    SectionLabel("image backends")
                    ForEach(backends) { b in
                        RunRow(title: b.name, subtitle: b.kind.label, action: { nav.push(.imageBackends) }) {
                            RouteTag(route: Route.imageBackend(b, model: nil))
                        }
                    }
                    RunRow(title: backends.isEmpty ? "Add an image server" : "Add or edit", subtitle: "", action: { nav.push(.imageBackends) }) { Chevron() }
                    Spacer().frame(height: 24)
                    SectionLabel("accounts")
                    RunRow(title: "Hugging Face", subtitle: accounts.huggingFaceUser ?? "not signed in"
, action: { nav.push(.accounts) }) { Chevron() }
                    RunRow(title: "Civitai", subtitle: accounts.civitaiUser ?? "no key", action: { nav.push(.accounts) }) { Chevron() }
                    Spacer().frame(height: 16)
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
                .padding(.vertical, 8)
            }
        }
        .background(c.background)
        // Each time the tab shows: a bot's state changes on the node, not here.
        .task(id: app.providers.providers.filter { $0.kind == .invincibleNode }.map(\.id)) {
            await bots.refresh(app: app, nodes: app.providers.providers.filter { $0.kind == .invincibleNode }.map(\.id))
        }
    }

    private func afterScheme(_ url: String) -> String { url.range(of: "://").map { String(url[$0.upperBound...]) } ?? url }

    private func host(_ url: String) -> String { String(afterScheme(url).split(separator: "/").first ?? "") }
}

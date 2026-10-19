import SwiftUI

/// REMOTE tab: everything that leaves the phone — APIs, PCs on the LAN, hub accounts, the route.
struct RemoteScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c

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
                            RunRow(title: "Agent on \(n.displayName)", subtitle: "tasks run on that machine · you approve", action: { nav.push(.nodeAgent(providerId: n.id)) }) {
                                RouteTag(route: routeOf(n))
                            }
                        }
                        Spacer().frame(height: 24)
                    }
                    SectionLabel("providers")
                    ForEach(providers) { p in
                        RunRow(title: p.displayName, subtitle: p.defaultModel, action: { nav.push(.providers) }) {
                            RouteTag(route: routeOf(p))
                        }
                    }
                    RunRow(title: providers.isEmpty ? "Add a provider" : "Add or edit", subtitle: "", action: { nav.push(.providers) }) { Chevron() }
                    Spacer().frame(height: 24)
                    SectionLabel("image backends")
                    ForEach(backends) { b in
                        RunRow(title: b.name, subtitle: b.kind.label, action: { nav.push(.imageBackends) }) {
                            RouteTag(route: Route.imageBackend(b, model: nil))
                        }
                    }
                    RunRow(title: backends.isEmpty ? "Add a PC" : "Add or edit", subtitle: "", action: { nav.push(.imageBackends) }) { Chevron() }
                    Spacer().frame(height: 24)
                    SectionLabel("accounts")
                    RunRow(title: "Hugging Face", subtitle: accounts.huggingFaceUser ?? "signed out", action: { nav.push(.accounts) }) { Chevron() }
                    RunRow(title: "Civitai", subtitle: accounts.civitaiUser ?? "no key", action: { nav.push(.accounts) }) { Chevron() }
                    Spacer().frame(height: 16)
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
                .padding(.vertical, 8)
            }
        }
        .background(c.background)
    }

    private func afterScheme(_ url: String) -> String { url.range(of: "://").map { String(url[$0.upperBound...]) } ?? url }

    private func host(_ url: String) -> String { String(afterScheme(url).split(separator: "/").first ?? "") }
}

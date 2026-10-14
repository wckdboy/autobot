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
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    SectionLabel("models · api & lan")
                    ForEach(providers) { p in
                        RunRow(title: p.displayName, subtitle: "\(p.defaultModel) · \(host(p.baseUrl))", action: { nav.push(.providers) }) {
                            RouteTag(route: Loopback.isLoopbackURL(p.baseUrl) || p.kind == .ollama ? .pcLan : .cloudApi)
                        }
                    }
                    RunRow(title: "Add or edit providers", subtitle: "deepseek · openrouter · ollama · any openai-compatible", action: { nav.push(.providers) }) { Tag("open") }
                    Spacer().frame(height: 18)
                    SectionLabel("image backends · pc & lan")
                    ForEach(backends) { b in
                        RunRow(title: b.name, subtitle: "\(b.kind.label) · \(afterScheme(b.baseUrl))", action: { nav.push(.imageBackends) }) {
                            RouteTag(route: b.isLoopback ? .localCpu : .pcLan)
                        }
                    }
                    RunRow(title: "Add or edit backends", subtitle: "a1111 / forge · local dream host · sse engines", action: { nav.push(.imageBackends) }) { Tag("open") }
                    Spacer().frame(height: 18)
                    SectionLabel("accounts · model hubs")
                    RunRow(title: "Hugging Face", subtitle: accounts.huggingFaceUser.map { "signed in · \($0)" } ?? "not signed in", action: { nav.push(.accounts) }) { Tag("open") }
                    RunRow(title: "Civitai", subtitle: (accounts.civitaiUser.map { "key · \($0)" } ?? "no api key") + " · \(accounts.civitaiHost.host)", action: { nav.push(.accounts) }) { Tag("open") }
                    Spacer().frame(height: 18)
                    SectionLabel("privacy")
                    RunRow(title: "Privacy Center", subtitle: "network mode · kill switch · audit log · panic wipe", action: { nav.push(.privacyCenter) }) { Tag("open") }
                    Spacer().frame(height: 12)
                    MicroLabel("on-device models never use the network; everything here goes through the kill switch", lines: 3)
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

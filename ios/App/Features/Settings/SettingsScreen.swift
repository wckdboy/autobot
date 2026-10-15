import SwiftUI

/// SYSTEM tab (port of `SettingsScreen.kt`): providers, backends, privacy, appearance, security.
struct SettingsScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var showBackup = false
    @State private var showSearch = false

    var body: some View {
        let settings = app.settings.settings
        let lockAvailable = app.lock.canAuthenticate()
        VStack(spacing: 0) {
            AutobotTopBar(title: "System", onBack: nav.path.isEmpty ? nil : { nav.back() })
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    NavigationRow(title: "Model providers", subtitle: "Chat endpoints and API keys") { nav.push(.providers) }
                    NavigationRow(title: "MCP servers", subtitle: "Remote tools for agent sessions, each call approved") { nav.push(.mcpServers) }
                    NavigationRow(title: "Image backends", subtitle: "Diffusion servers: Local Dream host, A1111/Forge, local SSE engines") { nav.push(.imageBackends) }
                    NavigationRow(title: "Privacy Center", subtitle: "Network mode, audit log, panic wipe") { nav.push(.privacyCenter) }
                    NavigationRow(title: "Export & import", subtitle: "Move sessions, gallery and memory between phones, sealed with a passphrase") { showBackup = true }
                    NavigationRow(title: "Licenses", subtitle: "Autobot and the open-source software inside it") { nav.push(.licenses) }
                    Hairline().padding(.vertical, 8)

                    header("Agent")
                    SwitchRow(
                        title: "Memory",
                        subtitle: "Let the agent recall and save notes, kept encrypted on this phone",
                        isOn: Binding(get: { settings.memoryEnabled }, set: { app.settings.setMemoryEnabled($0) }),
                    )
                    NavigationRow(title: "Memory notes", subtitle: "View, search, edit and delete what the agent remembers") { nav.push(.memory) }
                    SwitchRow(
                        title: "On limit, fall back",
                        subtitle: "When a provider's usage limit is used up, answer once with its helper model, else the on-device model, and say so",
                        isOn: Binding(get: { settings.quotaFallback }, set: { app.settings.setQuotaFallback($0) }),
                    )
                    NavigationRow(
                        title: "Web search",
                        subtitle: settings.webSearchOn ? "\(WebSearchDraft.label(settings.searchProvider)) · queries only, through your route" : "Off · SearXNG or Brave Search",
                    ) { showSearch = true }
                    note("Used in sessions with agent tools on (not plain chat or Apple Intelligence)")
                    if settings.webSearchOn && settings.mode == .offline {
                        note("Offline mode blocks searches", color: c.error)
                    }
                    Hairline().padding(.vertical, 8)

                    header("Appearance")
                    Segmented(options: ThemeMode.allCases, selected: settings.themeMode, label: { $0.rawValue.capitalized }) {
                        app.settings.setThemeMode($0)
                    }
                    .padding(.horizontal, Tokens.Spacing.gutter)
                    .padding(.vertical, 8)
                    SwitchRow(
                        title: "AMOLED black",
                        subtitle: "Pure black backgrounds in dark theme",
                        isOn: Binding(get: { settings.amoled }, set: { app.settings.setAmoled($0) }),
                        enabled: settings.themeMode != .light,
                    )
                    Hairline().padding(.vertical, 8)

                    header("Security")
                    SwitchRow(
                        title: "App lock",
                        subtitle: lockAvailable ? "Require \(app.lock.biometryLabel()) or the device passcode" : "Set up a passcode on this device to enable",
                        isOn: Binding(get: { settings.appLockEnabled && lockAvailable }, set: { app.settings.setAppLockEnabled($0) }),
                        enabled: lockAvailable,
                    )
                    if settings.appLockEnabled && lockAvailable {
                        Text("Lock after leaving the app")
                            .textStyle(.bodyMedium)
                            .foregroundStyle(c.onSurface)
                            .padding(.horizontal, Tokens.Spacing.gutter)
                            .padding(.vertical, 4)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(lockTimeoutOptions, id: \.millis) { option in
                                    let active = settings.lockTimeoutMillis == option.millis
                                    Tag(option.label, accent: active ? c.primary : c.onSurfaceVariant, filled: active) {
                                        app.settings.setLockTimeoutMillis(option.millis)
                                    }
                                }
                            }
                            .padding(.horizontal, Tokens.Spacing.gutter)
                            .padding(.vertical, 6)
                        }
                    }
                    SwitchRow(
                        title: "Incognito by default",
                        subtitle: "New chats are kept in memory only",
                        isOn: Binding(get: { settings.incognitoByDefault }, set: { app.settings.setIncognitoByDefault($0) }),
                    )
                    Hairline().padding(.vertical, 8)

                    header("About")
                    MicroLabel("autobot \(Self.version) · no analytics · no telemetry", lines: 2)
                        .padding(.horizontal, Tokens.Spacing.gutter)
                        .padding(.bottom, 24)
                }
            }
        }
        .background(c.background)
        .sheet(isPresented: $showBackup) { BackupSheet() }
        .sheet(isPresented: $showSearch) { WebSearchSheet { showSearch = false } }
    }

    private func note(_ text: String, color: Color? = nil) -> some View {
        Text(text)
            .textStyle(.bodySmall)
            .foregroundStyle(color ?? c.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, Tokens.Spacing.gutter)
            .padding(.vertical, 4)
    }

    private func header(_ text: String) -> some View {
        SectionLabel(text).padding(.horizontal, Tokens.Spacing.gutter).padding(.top, 12).padding(.bottom, 4)
    }

    static var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }
}

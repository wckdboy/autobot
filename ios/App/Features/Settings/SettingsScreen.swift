import SwiftUI

/// SYSTEM tab (port of `SettingsScreen.kt`): providers, backends, privacy, appearance, security.
struct SettingsScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var showBackup = false
    @State private var showSearch = false
    @State private var confirmDeleteChats = false

    var body: some View {
        let settings = app.settings.settings
        let lockAvailable = app.lock.canAuthenticate()
        VStack(spacing: 0) {
            AutobotTopBar(title: "System", onBack: nav.path.isEmpty ? nil : { nav.back() })
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header("Connections")
                    NavigationRow(title: "Model providers", value: count(app.providers.providers.count)) { nav.push(.providers) }
                    NavigationRow(title: "MCP servers") { nav.push(.mcpServers) }
                    NavigationRow(title: "Image backends", value: count(app.backends.backends.count)) { nav.push(.imageBackends) }
                    NavigationRow(title: "Privacy Center", value: Self.modeLabel(settings.mode)) { nav.push(.privacyCenter) }
                    Hairline().padding(.vertical, 8)

                    header("Agent")
                    SwitchRow(
                        title: "Memory",
                        isOn: Binding(get: { settings.memoryEnabled }, set: { app.settings.setMemoryEnabled($0) }),
                        info: "The agent recalls and saves notes, kept encrypted on this phone.",
                    )
                    NavigationRow(title: "Memory notes") { nav.push(.memory) }
                    NavigationRow(title: "Vault", value: settings.vaultEnabled ? "On" : "Off") { nav.push(.vault) }
                    SwitchRow(
                        title: "Fallback on limit",
                        isOn: Binding(get: { settings.quotaFallback }, set: { app.settings.setQuotaFallback($0) }),
                        info: "When a provider's usage limit is used up, answer once with its helper model, else the on-device model, and say so.",
                    )
                    NavigationRow(title: "Web search", value: settings.webSearchOn ? WebSearchDraft.label(settings.searchProvider) : "Off") { showSearch = true }
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
                        isOn: Binding(get: { settings.amoled }, set: { app.settings.setAmoled($0) }),
                        enabled: settings.themeMode != .light,
                    )
                    Hairline().padding(.vertical, 8)

                    header("Security")
                    SwitchRow(
                        title: "App lock",
                        subtitle: lockAvailable ? nil : "Needs a device passcode",
                        isOn: Binding(get: { settings.appLockEnabled && lockAvailable }, set: { app.settings.setAppLockEnabled($0) }),
                        enabled: lockAvailable,
                        info: lockAvailable ? "Require \(app.lock.biometryLabel()) or the device passcode." : nil,
                    )
                    if settings.appLockEnabled && lockAvailable {
                        Text("Lock after")
                            .textStyle(.bodyMedium)
                            .foregroundStyle(c.onSurfaceVariant)
                            .padding(.horizontal, Tokens.Spacing.gutter)
                            .padding(.top, 4)
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
                            .padding(.vertical, 8)
                        }
                    }
                    SwitchRow(
                        title: "Incognito by default",
                        isOn: Binding(get: { settings.incognitoByDefault }, set: { app.settings.setIncognitoByDefault($0) }),
                        info: "New chats are kept in memory only, never written to disk.",
                    )
                    Hairline().padding(.vertical, 8)

                    header("Data")
                    NavigationRow(title: "Export & import") { showBackup = true }
                    Button { confirmDeleteChats = true } label: {
                        Text("Delete all chats").textStyle(.bodyLarge).foregroundStyle(c.error)
                            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                            .padding(.horizontal, Tokens.Spacing.gutter)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(PressStyle())
                    Hairline().padding(.vertical, 8)

                    header("About")
                    NavigationRow(title: "Licenses") { nav.push(.licenses) }
                    MicroLabel("autobot \(Self.version) · no analytics", lines: 2)
                        .padding(.horizontal, Tokens.Spacing.gutter)
                        .padding(.top, 8)
                        .padding(.bottom, 24)
                }
            }
        }
        .background(c.background)
        .sheet(isPresented: $showBackup) { BackupSheet() }
        .sheet(isPresented: $showSearch) { WebSearchSheet { showSearch = false } }
        .risoAlert(
            $confirmDeleteChats,
            title: "Delete all chats?",
            message: "Every session is removed from this phone. Folders, memory and files stay.",
            confirm: "Delete all",
        ) { app.chats.deleteAll() }
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

    /// A count as a row value; nothing for none.
    private func count(_ n: Int) -> String? { n > 0 ? "\(n)" : nil }

    /// The network mode in one word (the Privacy Center row's value).
    static func modeLabel(_ mode: NetworkMode) -> String {
        switch mode {
        case .offline: "Offline"
        case .direct: "Direct"
        case .tor: "Tor"
        case .socks5: "SOCKS5"
        }
    }

    static var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(short) (\(build))"
    }
}

import SwiftUI

/// SETTINGS tab (port of `SettingsScreen.kt`), laid out by [SettingsSection]: privacy & security,
/// agent, appearance, data, about. Providers, image backends and accounts live in the Remote tab.
struct SettingsScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var showBackup = false
    @State private var showSearch = false
    @State private var confirmDeleteChats = false
    @State private var showWipe = false

    var body: some View {
        VStack(spacing: 0) {
            AutobotTopBar(title: "Settings", onBack: nav.path.isEmpty ? nil : { nav.back() })
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    ForEach(SettingsSection.allCases) { section in
                        if section != SettingsSection.allCases.first { Hairline().padding(.vertical, 8) }
                        header(section.title)
                        ForEach(section.rows) { row($0) }
                    }
                    MicroLabel(AboutInfo.privacyStatement.lowercased(), lines: 2)
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
        .panicWipeAlert(isPresented: $showWipe)
    }

    @ViewBuilder private func row(_ row: SettingsRow) -> some View {
        let settings = app.settings.settings
        let title = row.title()
        switch row {
        case .privacyCenter:
            NavigationRow(title: title, value: Self.modeLabel(settings.mode)) { nav.push(.privacyCenter) }
        case .appLock:
            let lockAvailable = app.lock.canAuthenticate()
            SwitchRow(
                title: title,
                subtitle: lockAvailable ? nil : "Needs a device passcode",
                isOn: Binding(get: { settings.appLockEnabled && lockAvailable }, set: { app.settings.setAppLockEnabled($0) }),
                enabled: lockAvailable,
                info: lockAvailable ? "Require \(app.lock.biometryLabel()) or the device passcode." : nil,
            )
            if settings.appLockEnabled && lockAvailable { lockTimeouts(settings.lockTimeoutMillis) }
        case .incognito:
            SwitchRow(
                title: title,
                isOn: Binding(get: { settings.incognitoByDefault }, set: { app.settings.setIncognitoByDefault($0) }),
                info: "New chats are kept in memory only, never written to disk.",
            )
        case .redactCloud:
            SwitchRow(
                title: title,
                isOn: Binding(get: { settings.redactCloud }, set: { app.settings.setRedactCloud($0) }),
                info: "Names, emails, numbers, addresses and memory facts become placeholders before a cloud model sees them. Answers show the real values.",
            )
        case .panicWipe:
            DestructiveRow(title: title, info: "Destroys every key and all data on this phone, then closes the app.") { showWipe = true }
        case .memory:
            SwitchRow(
                title: title,
                isOn: Binding(get: { settings.memoryEnabled }, set: { app.settings.setMemoryEnabled($0) }),
                info: "The agent recalls and saves notes, kept encrypted on this phone.",
            )
        case .memoryNotes:
            NavigationRow(title: title) { nav.push(.memory) }
        case .vault:
            NavigationRow(title: title, value: settings.vaultEnabled ? "On" : "Off") { nav.push(.vault) }
        case .webSearch:
            NavigationRow(title: title, value: settings.webSearchOn ? WebSearchDraft.label(settings.searchProvider) : "Off") { showSearch = true }
            if settings.webSearchOn && settings.mode == .offline {
                note("Offline mode blocks searches", color: c.error)
            }
        case .quotaFallback:
            SwitchRow(
                title: title,
                isOn: Binding(get: { settings.quotaFallback }, set: { app.settings.setQuotaFallback($0) }),
                info: "When a provider's usage limit is used up, answer once with its helper model, else the on-device model, and say so.",
            )
        case .usage:
            NavigationRow(title: title) { nav.push(.usage) }
        case .mcpServers:
            NavigationRow(title: title) { nav.push(.mcpServers) }
        case .recipes:
            NavigationRow(title: title) { nav.push(.recipes) }
        case .theme:
            Segmented(options: ThemeMode.allCases, selected: settings.themeMode, label: { $0.rawValue.capitalized }) {
                app.settings.setThemeMode($0)
            }
            .accessibilityLabel(title)
            .padding(.horizontal, Tokens.Spacing.gutter)
            .padding(.vertical, 8)
        case .amoled:
            SwitchRow(
                title: title,
                isOn: Binding(get: { settings.amoled }, set: { app.settings.setAmoled($0) }),
                enabled: settings.themeMode != .light,
            )
        case .exportImport:
            NavigationRow(title: title) { showBackup = true }
        case .deleteAll:
            DestructiveRow(title: title) { confirmDeleteChats = true }
        case .about:
            NavigationRow(title: title) { nav.push(.about) }
        }
    }

    private func lockTimeouts(_ selected: Int64) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Lock after")
                .textStyle(.bodyMedium)
                .foregroundStyle(c.onSurfaceVariant)
                .padding(.horizontal, Tokens.Spacing.gutter)
                .padding(.top, 4)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(lockTimeoutOptions, id: \.millis) { option in
                        let active = selected == option.millis
                        Tag(option.label, accent: active ? c.primary : c.onSurfaceVariant, filled: active) {
                            app.settings.setLockTimeoutMillis(option.millis)
                        }
                    }
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
                .padding(.vertical, 8)
            }
        }
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

    /// The network mode in one word (the Privacy Center row's value).
    static func modeLabel(_ mode: NetworkMode) -> String {
        switch mode {
        case .offline: "Offline"
        case .direct: "Direct"
        case .tor: "Tor"
        case .socks5: "SOCKS5"
        }
    }
}

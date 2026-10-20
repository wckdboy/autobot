import AutobotShared
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
    @State private var panic = PanicUiState()
    @State private var panicDialog: PanicDialog?

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
        .sheet(item: $panicDialog, onDismiss: refreshPanic) { dialog in
            PanicSheet(dialog: dialog, state: panic, lock: app.pinLock) { panicDialog = nil }
        }
        .onAppear(perform: refreshPanic)
    }

    private func refreshPanic() { panic = PanicUiState(app.pinLock.record) }

    @ViewBuilder private func row(_ row: SettingsRow) -> some View {
        let settings = app.settings.settings
        let title = row.title()
        switch row {
        case .privacyCenter:
            NavigationRow(title: title, value: Self.modeLabel(settings.mode)) { nav.push(.privacyCenter) }
        case .appLock:
            let lockAvailable = app.lock.canAuthenticate()
            // An app PIN always locks: the wipe and the duress PIN depend on it.
            let pinLocks = panic.hasPin
            SwitchRow(
                title: title,
                subtitle: lockAvailable || pinLocks ? nil : "Needs a device passcode",
                isOn: Binding(get: { pinLocks || (settings.appLockEnabled && lockAvailable) }, set: { app.settings.setAppLockEnabled($0) }),
                enabled: lockAvailable && !pinLocks,
                info: lockAvailable || pinLocks ? "Require \(app.lock.biometryLabel()) or the device passcode." : nil,
            )
            if pinLocks || (settings.appLockEnabled && lockAvailable) { lockTimeouts(settings.lockTimeoutMillis) }
        case .appPin:
            PanicRows(
                state: panic,
                biometryLabel: app.lock.biometryLabel(),
                onOpen: { panicDialog = $0 },
                onBiometrics: { on in
                    app.pinLock.setBiometrics(on)
                    refreshPanic()
                },
                onWipeModels: { on in
                    app.pinLock.setWipeModels(on)
                    refreshPanic()
                },
            )
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
        case .cascade:
            CascadeSettingRow(title: title, policy: settings.cascade) { app.settings.setCascade($0) }
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
                        // The shared v4 selection (onSurface over the purple bar).
                        Tag(option.label, accent: active ? c.onSurface : c.onSurfaceVariant, filled: active) {
                            app.settings.setLockTimeoutMillis(option.millis)
                        }
                        .accessibilityAddTraits(active ? .isSelected : [])
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

/// Settings → "Phone first": off, ask, auto or never for plain chats on a cloud model, with the
/// chosen option's line (the shared `CascadePolicy` words, as on Android).
private struct CascadeSettingRow: View {
    let title: String
    let policy: String
    let onSelect: (String) -> Void
    @Environment(\.scheme) private var c

    /// What "Phone first" covers (the same words on Android).
    static let scope = "For plain chats on a cloud model; chats can choose their own."

    var body: some View {
        let options = CascadePolicy.entries.map(\.name)
        let chosen = CascadePolicy.companion.parse(value: policy) ?? CascadePolicy.off
        VStack(alignment: .leading, spacing: 8) {
            Text(title).textStyle(.bodyLarge).foregroundStyle(c.onSurface)
            Segmented(options: options, selected: chosen.name, label: { (CascadePolicy.companion.parse(value: $0)?.title ?? $0).lowercased() }) { onSelect($0) }
                .accessibilityLabel(title)
            Text(chosen.detail + " " + Self.scope).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, Tokens.Spacing.gutter)
        .padding(.vertical, 8)
    }
}

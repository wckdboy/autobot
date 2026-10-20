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
                    // The privacy statement is on About, as on Android.
                    Spacer().frame(height: 24)
                }
            }
        }
        .background(c.background)
        .sheet(isPresented: $showBackup) { BackupSheet() }
        .sheet(isPresented: $showSearch) { WebSearchSheet { showSearch = false } }
        .risoAlert(
            $confirmDeleteChats,
            title: SettingsWords.shared.DELETE_ALL_TITLE,
            message: SettingsWords.shared.DELETE_ALL_TEXT,
            confirm: SettingsWords.shared.DELETE_ALL_CONFIRM,
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
                info: SettingsWords.shared.INCOGNITO,
            )
        case .redactCloud:
            SwitchRow(
                title: title,
                isOn: Binding(get: { settings.redactCloud }, set: { app.settings.setRedactCloud($0) }),
                info: SettingsWords.shared.REDACT,
            )
        case .cascade:
            CascadeSettingRow(title: title, policy: settings.cascade) { app.settings.setCascade($0) }
        case .panicWipe:
            DestructiveRow(title: title, info: SettingsWords.shared.PANIC_WIPE) { showWipe = true }
        case .memory:
            SwitchRow(
                title: title,
                isOn: Binding(get: { settings.memoryEnabled }, set: { app.settings.setMemoryEnabled($0) }),
                info: SettingsWords.shared.MEMORY,
            )
        case .memoryNotes:
            NavigationRow(title: title) { nav.push(.memory) }
        case .vault:
            NavigationRow(title: title, value: settings.vaultEnabled ? "On" : "Off") { nav.push(.vault) }
        case .webSearch:
            NavigationRow(title: title, value: settings.webSearchOn ? WebSearchDraft.label(settings.searchProvider) : "Off", info: SettingsWords.shared.WEB_SEARCH) { showSearch = true }
            if settings.webSearchOn && settings.mode == .offline {
                note(SettingsWords.shared.OFFLINE_BLOCKS_SEARCH, color: c.error)
            }
        case .quotaFallback:
            SwitchRow(
                title: title,
                isOn: Binding(get: { settings.quotaFallback }, set: { app.settings.setQuotaFallback($0) }),
                info: SettingsWords.shared.QUOTA_FALLBACK,
            )
        case .usage:
            NavigationRow(title: title) { nav.push(.usage) }
        case .mcpServers:
            NavigationRow(title: title) { nav.push(.mcpServers) }
        case .recipes:
            NavigationRow(title: title) { nav.push(.recipes) }
        case .theme:
            VStack(alignment: .leading, spacing: 8) {
                ChoiceTitle(title)
                Segmented(options: ThemeMode.allCases, selected: settings.themeMode, label: { $0.rawValue.capitalized }) {
                    app.settings.setThemeMode($0)
                }
                .accessibilityLabel(title)
            }
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
            ChoiceTitle("Lock after")
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

/// Settings → "Phone first": off, ask, auto or never for plain chats on a cloud model; what each
/// does sits behind the ⓘ (the shared `SettingsWords.cascade`, as on Android).
private struct CascadeSettingRow: View {
    let title: String
    let policy: String
    let onSelect: (String) -> Void

    var body: some View {
        let options = CascadePolicy.entries.map(\.name)
        let chosen = CascadePolicy.companion.parse(value: policy) ?? CascadePolicy.off
        VStack(alignment: .leading, spacing: 8) {
            ChoiceTitle(title, info: SettingsWords.shared.cascade)
            Segmented(options: options, selected: chosen.name, label: { CascadePolicy.companion.parse(value: $0)?.title ?? $0 }) { onSelect($0) }
                .accessibilityLabel(title)
        }
        .padding(.horizontal, Tokens.Spacing.gutter)
        .padding(.vertical, 8)
    }
}

/// The title over a choice row (Lock after, Phone first, Theme): a row title without a row, its ⓘ after it.
private struct ChoiceTitle: View {
    let text: String
    var info: String? = nil
    @Environment(\.scheme) private var c

    init(_ text: String, info: String? = nil) {
        self.text = text
        self.info = info
    }

    var body: some View {
        HStack(spacing: 0) {
            Text(text).textStyle(.bodyLarge).foregroundStyle(c.onSurface)
            if let info { InfoButton(title: text, text: info).padding(.vertical, -12).padding(.leading, -6) }
        }
    }
}

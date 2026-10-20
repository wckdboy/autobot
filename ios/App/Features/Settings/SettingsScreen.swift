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
            Form {
                ForEach(SettingsSection.allCases) { section in
                    Section(section.title) {
                        ForEach(section.rows) { row($0) }
                    }
                }
                // The privacy statement is on About, as on Android.
            }
            .formStyle(.grouped)
        }
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
            PushRow(title: title, value: Self.modeLabel(settings.mode)) { nav.push(.privacyCenter) }
        case .appLock:
            let lockAvailable = app.lock.canAuthenticate()
            // An app PIN always locks: the wipe and the duress PIN depend on it.
            let pinLocks = panic.hasPin
            Toggle(isOn: Binding(get: { pinLocks || (settings.appLockEnabled && lockAvailable) }, set: { app.settings.setAppLockEnabled($0) })) {
                InfoTitle(title, info: lockAvailable || pinLocks ? "Require \(app.lock.biometryLabel()) or the device passcode." : nil)
                if !(lockAvailable || pinLocks) { Text("Needs a device passcode") }
            }
            .disabled(!(lockAvailable && !pinLocks))
            .accessibilityLabel(title)
            if pinLocks || (settings.appLockEnabled && lockAvailable) {
                Picker("Lock after", selection: Binding(get: { settings.lockTimeoutMillis }, set: { app.settings.setLockTimeoutMillis($0) })) {
                    ForEach(lockTimeoutOptions, id: \.millis) { Text($0.label).tag($0.millis) }
                }
            }
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
            InfoToggle(title: title, info: SettingsWords.shared.INCOGNITO, isOn: Binding(get: { settings.incognitoByDefault }, set: { app.settings.setIncognitoByDefault($0) }))
        case .redactCloud:
            InfoToggle(title: title, info: SettingsWords.shared.REDACT, isOn: Binding(get: { settings.redactCloud }, set: { app.settings.setRedactCloud($0) }))
        case .cascade:
            CascadeSettingRow(title: title, policy: settings.cascade) { app.settings.setCascade($0) }
        case .panicWipe:
            HStack {
                Button(title, role: .destructive) { showWipe = true }
                    .buttonStyle(.borderless)
                    .foregroundStyle(.red)
                RowInfo(title: title, text: SettingsWords.shared.PANIC_WIPE)
            }
        case .memory:
            InfoToggle(title: title, info: SettingsWords.shared.MEMORY, isOn: Binding(get: { settings.memoryEnabled }, set: { app.settings.setMemoryEnabled($0) }))
        case .memoryNotes:
            PushRow(title: title) { nav.push(.memory) }
        case .vault:
            PushRow(title: title, value: settings.vaultEnabled ? "On" : "Off") { nav.push(.vault) }
        case .webSearch:
            PushRow(title: title, value: settings.webSearchOn ? WebSearchDraft.label(settings.searchProvider) : "Off", info: SettingsWords.shared.WEB_SEARCH) { showSearch = true }
            if settings.webSearchOn && settings.mode == .offline {
                Text(SettingsWords.shared.OFFLINE_BLOCKS_SEARCH).font(.footnote).foregroundStyle(.red)
            }
        case .quotaFallback:
            InfoToggle(title: title, info: SettingsWords.shared.QUOTA_FALLBACK, isOn: Binding(get: { settings.quotaFallback }, set: { app.settings.setQuotaFallback($0) }))
        case .usage:
            PushRow(title: title) { nav.push(.usage) }
        case .mcpServers:
            PushRow(title: title) { nav.push(.mcpServers) }
        case .recipes:
            PushRow(title: title) { nav.push(.recipes) }
        case .theme:
            Picker(title, selection: Binding(get: { settings.themeMode }, set: { app.settings.setThemeMode($0) })) {
                ForEach(ThemeMode.allCases, id: \.self) { Text($0.rawValue.capitalized).tag($0) }
            }
        case .amoled:
            Toggle(title, isOn: Binding(get: { settings.amoled }, set: { app.settings.setAmoled($0) }))
                .disabled(settings.themeMode == .light)
        case .exportImport:
            PushRow(title: title) { showBackup = true }
        case .deleteAll:
            Button(title, role: .destructive) { confirmDeleteChats = true }
                .foregroundStyle(.red)
        case .about:
            PushRow(title: title) { nav.push(.about) }
        }
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
        Picker(selection: Binding(get: { chosen.name }, set: { onSelect($0) })) {
            ForEach(options, id: \.self) { Text(CascadePolicy.companion.parse(value: $0)?.title ?? $0).tag($0) }
        } label: {
            InfoTitle(title, info: SettingsWords.shared.cascade)
        }
        .accessibilityLabel(title)
    }
}

/// A Form row that pushes a screen (or opens a sheet): the title, an optional ⓘ, the state value
/// trailing like iOS Settings, and the system's disclosure chevron.
struct PushRow: View {
    let title: String
    var value: String? = nil
    var info: String? = nil
    let action: () -> Void

    var body: some View {
        // A NavigationLink look without a destination: the app's Navigator owns the stack. With an
        // ⓘ the row is split so the ⓘ keeps its own tap (a Form row button would swallow it).
        if let info {
            HStack(spacing: 6) {
                Button(action: action) { Text(title) }.buttonStyle(.borderless)
                RowInfo(title: title, text: info)
                Button(action: action) { trailing.frame(maxWidth: .infinity, alignment: .trailing).contentShape(Rectangle()) }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(title)
            }
            .foregroundStyle(.primary)
        } else {
            Button(action: action) {
                HStack {
                    Text(title)
                    Spacer(minLength: 8)
                    trailing
                }
                .contentShape(Rectangle())
            }
            .foregroundStyle(.primary)
        }
    }

    private var trailing: some View {
        HStack(spacing: 8) {
            if let value { Text(value).foregroundStyle(.secondary).lineLimit(1) }
            Image(systemName: "chevron.forward")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.tertiary)
                .accessibilityHidden(true)
        }
    }
}

/// A Form toggle whose title carries an ⓘ.
struct InfoToggle: View {
    let title: String
    var info: String? = nil
    var subtitle: String? = nil
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            InfoTitle(title, info: info)
            if let subtitle { Text(subtitle) }
        }
        .accessibilityLabel(title)
    }
}

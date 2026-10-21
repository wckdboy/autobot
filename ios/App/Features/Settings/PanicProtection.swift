import AutobotShared
import SwiftUI

/// The panic-protection part of a `LockRecord`, as Settings shows it. No verifier leaves the lock.
struct PanicUiState: Equatable {
    var hasPin = false
    var biometrics = true
    var wipeAfter = 0
    var duress = false
    var wipeModels = false

    init(_ record: LockRecord) {
        hasPin = record.hasPin
        biometrics = record.biometrics
        wipeAfter = Int(record.wipeAfter)
        duress = record.duress != nil
        wipeModels = record.wipeModels
    }

    init() {}
}

/// Which panic sheet Settings shows.
enum PanicDialog: String, Identifiable {
    case pin, wipe, duress
    var id: String { rawValue }
}

/**
 The rows under App lock (port of Android's `PanicRows`): App PIN, then (with a PIN) biometrics,
 the wipe after failed unlocks, the duress PIN and whether models go too. The words are the shared
 `PanicCopy`; each feature says what it destroys and that it is final.
 */
struct PanicRows: View {
    let state: PanicUiState
    let biometryLabel: String
    let onOpen: (PanicDialog) -> Void
    let onBiometrics: (Bool) -> Void
    let onWipeModels: (Bool) -> Void

    private var copy: PanicCopy { PanicCopy.shared }

    var body: some View {
        PushRow(title: copy.PIN_TITLE, value: copy.onOff(on: state.hasPin)) { onOpen(.pin) }
        if state.hasPin {
            InfoToggle(
                title: copy.BIOMETRICS_TITLE,
                info: copy.BIOMETRICS_INFO,
                subtitle: biometryLabel == "passcode" ? nil : biometryLabel,
                isOn: Binding(get: { state.biometrics }, set: { onBiometrics($0) }),
            )
            PushRow(title: copy.WIPE_TITLE, value: copy.wipeValue(attempts: Int32(state.wipeAfter))) { onOpen(.wipe) }
            PushRow(title: copy.DURESS_TITLE, value: copy.onOff(on: state.duress)) { onOpen(.duress) }
            if state.wipeAfter > 0 || state.duress {
                InfoToggle(title: copy.MODELS_TITLE, info: copy.MODELS_INFO, isOn: Binding(get: { state.wipeModels }, set: { onWipeModels($0) }))
            }
        }
    }
}

/**
 The PIN, wipe and duress sheets (port of Android's `PanicDialogs`). Every change that needs the
 current PIN checks it off the main thread (PBKDF2) and reports the shared `PinRules` problem, or
 closes on success.
 */
struct PanicSheet: View {
    let dialog: PanicDialog
    let state: PanicUiState
    let lock: PinLock
    let onDone: () -> Void
    @State private var current = ""
    @State private var pin = ""
    @State private var confirm = ""
    @State private var removing = false
    @State private var chosen = 0
    @State private var error: String?
    @State private var busy = false

    private var copy: PanicCopy { PanicCopy.shared }

    var body: some View {
        NavigationStack {
            Form {
                switch dialog {
                case .pin: pinBody
                case .wipe: wipeBody
                case .duress: duressBody
                }
                if let error {
                    Section { Text(error).foregroundStyle(.red) }
                }
            }
            .formStyle(.grouped)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(copy.CANCEL, action: onDone) }
            }
        }
        .presentationDetents([.large])
        .onAppear { chosen = state.wipeAfter > 0 ? state.wipeAfter : Int(truncating: UnlockPolicy.shared.WipeChoices[1]) }
    }

    private var title: String {
        switch dialog {
        case .pin: removing ? copy.REMOVE_PIN : state.hasPin ? copy.CHANGE_PIN : copy.SET_PIN
        case .wipe: chosen == 0 ? copy.WIPE_TITLE : copy.wipeConfirmTitle(attempts: Int32(chosen))
        case .duress: state.duress ? copy.DURESS_TITLE : copy.DURESS_CONFIRM_TITLE
        }
    }

    /// The sheet's one action, full width under the fields.
    private func action(_ label: String, enabled: Bool = true, role: ButtonRole? = nil, perform: @escaping () -> Void) -> some View {
        Section {
            Button(role: role, action: perform) {
                Group {
                    if busy { ProgressView() } else { Text(label) }
                }
                .frame(maxWidth: .infinity)
            }
            .primaryActionStyle()
            .controlSize(.large)
            .disabled(!enabled || busy)
        }
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
    }

    // MARK: PIN

    @ViewBuilder private var pinBody: some View {
        Section {
            if state.hasPin { field(copy.CURRENT_PIN, $current) }
            if !removing {
                field(copy.NEW_PIN, $pin)
                field(copy.CONFIRM_PIN, $confirm)
            }
        } footer: {
            Text(removing ? copy.REMOVE_PIN_BODY : copy.PIN_INFO)
        }
        let ready = removing ? !current.isEmpty : (!pin.isEmpty && !confirm.isEmpty && (!state.hasPin || !current.isEmpty))
        action(title, enabled: ready) {
            let (current, pin, confirm, removing, hasPin) = (current, pin, confirm, removing, state.hasPin)
            run {
                if removing { return $0.removePin(current: current) }
                return hasPin ? $0.changePin(current: current, pin: pin, confirm: confirm) : $0.setPin(pin, confirm: confirm)
            }
        }
        if state.hasPin && !removing {
            Section {
                Button(copy.REMOVE_PIN, role: .destructive) {
                    removing = true
                    error = nil
                }
                .foregroundStyle(.red)
            }
        }
    }

    // MARK: Wipe

    @ViewBuilder private var wipeBody: some View {
        let turningOff = chosen == 0
        Section {
            Picker(copy.WIPE_TITLE, selection: $chosen) {
                ForEach([0] + UnlockPolicy.shared.WipeChoices.map { Int(truncating: $0) }, id: \.self) { n in
                    Text(n == 0 ? copy.wipeValue(attempts: 0) : "\(n)").tag(n)
                }
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)
            .listRowInsets(EdgeInsets())
        } footer: {
            Text(turningOff ? copy.WIPE_INFO : copy.wipeConfirmBody(attempts: Int32(chosen), eraseModels: state.wipeModels))
        }
        if !turningOff {
            Section { field(copy.CURRENT_PIN, $current) }
        }
        action(turningOff ? copy.TURN_OFF : copy.TURN_ON, enabled: chosen != state.wipeAfter && (turningOff || !current.isEmpty)) {
            let (chosen, current) = (chosen, current)
            run { $0.setWipeAfter(chosen, current: chosen == 0 ? nil : current) }
        }
    }

    // MARK: Duress

    @ViewBuilder private var duressBody: some View {
        if state.duress {
            Section {} footer: { Text(copy.DURESS_INFO) }
            action(copy.TURN_OFF) {
                lock.clearDuress()
                onDone()
            }
        } else {
            Section {
                field(copy.CURRENT_PIN, $current)
                field(copy.DURESS_PIN, $pin)
                field(copy.CONFIRM_DURESS_PIN, $confirm)
            } footer: {
                Text(copy.duressConfirmBody(eraseModels: state.wipeModels))
            }
            action(copy.TURN_ON, enabled: !current.isEmpty && !pin.isEmpty && !confirm.isEmpty) {
                let (current, pin, confirm) = (current, pin, confirm)
                run { $0.setDuress(current: current, duress: pin, confirm: confirm) }
            }
        }
    }

    /// A masked digits-only field; secure fields teach the keyboard nothing.
    private func field(_ label: String, _ text: Binding<String>) -> some View {
        FormField(label, text: Binding(get: { text.wrappedValue }, set: { typed in
            text.wrappedValue = String(typed.filter(\.isNumber).prefix(Int(PinRules.shared.MAX_LENGTH)))
        }), kind: .secret)
        .keyboardType(.numberPad)
    }

    /// Runs a lock change off the main thread; closes on success, shows the problem otherwise.
    private func run(_ change: @escaping @Sendable (PinLock) -> String?) {
        busy = true
        error = nil
        let lock = lock
        Task {
            let problem = await Task.detached(priority: .userInitiated) { change(lock) }.value
            busy = false
            if let problem { error = problem } else { onDone() }
        }
    }
}

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
    @Environment(\.scheme) private var c

    private var copy: PanicCopy { PanicCopy.shared }

    var body: some View {
        NavigationRow(title: copy.PIN_TITLE, value: copy.onOff(on: state.hasPin)) { onOpen(.pin) }
        if state.hasPin {
            SwitchRow(
                title: copy.BIOMETRICS_TITLE,
                subtitle: biometryLabel == "passcode" ? nil : biometryLabel,
                isOn: Binding(get: { state.biometrics }, set: { onBiometrics($0) }),
                info: copy.BIOMETRICS_INFO,
            )
            NavigationRow(title: copy.WIPE_TITLE, value: copy.wipeValue(attempts: Int32(state.wipeAfter))) { onOpen(.wipe) }
            NavigationRow(title: copy.DURESS_TITLE, value: copy.onOff(on: state.duress)) { onOpen(.duress) }
            if state.wipeAfter > 0 || state.duress {
                SwitchRow(title: copy.MODELS_TITLE, isOn: Binding(get: { state.wipeModels }, set: { onWipeModels($0) }), info: copy.MODELS_INFO)
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
    @Environment(\.scheme) private var c
    @State private var current = ""
    @State private var pin = ""
    @State private var confirm = ""
    @State private var removing = false
    @State private var chosen = 0
    @State private var error: String?
    @State private var busy = false

    private var copy: PanicCopy { PanicCopy.shared }

    var body: some View {
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                switch dialog {
                case .pin: pinBody
                case .wipe: wipeBody
                case .duress: duressBody
                }
                if let error { MicroLabel(error, color: c.error, lines: 3) }
                TextAction(copy.CANCEL, color: c.onSurfaceVariant, action: onDone).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .background(c.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents([.large])
        .onAppear { chosen = state.wipeAfter > 0 ? state.wipeAfter : Int(truncating: UnlockPolicy.shared.WipeChoices[1]) }
    }

    // MARK: PIN

    @ViewBuilder private var pinBody: some View {
        let title = removing ? copy.REMOVE_PIN : state.hasPin ? copy.CHANGE_PIN : copy.SET_PIN
        SectionLabel(title.lowercased())
        Text(removing ? copy.REMOVE_PIN_BODY : copy.PIN_INFO).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
        if state.hasPin { field(copy.CURRENT_PIN, $current) }
        if !removing {
            field(copy.NEW_PIN, $pin)
            field(copy.CONFIRM_PIN, $confirm)
        }
        let ready = removing ? !current.isEmpty : (!pin.isEmpty && !confirm.isEmpty && (!state.hasPin || !current.isEmpty))
        PrimaryButton(title, enabled: ready && !busy, busy: busy) {
            let (current, pin, confirm, removing, hasPin) = (current, pin, confirm, removing, state.hasPin)
            run {
                if removing { return $0.removePin(current: current) }
                return hasPin ? $0.changePin(current: current, pin: pin, confirm: confirm) : $0.setPin(pin, confirm: confirm)
            }
        }
        if state.hasPin && !removing {
            TextAction(copy.REMOVE_PIN, color: c.error) {
                removing = true
                error = nil
            }
        }
    }

    // MARK: Wipe

    @ViewBuilder private var wipeBody: some View {
        let turningOff = chosen == 0
        SectionLabel((turningOff ? copy.WIPE_TITLE : copy.wipeConfirmTitle(attempts: Int32(chosen))).lowercased())
        HStack(spacing: 8) {
            ForEach([0] + UnlockPolicy.shared.WipeChoices.map { Int(truncating: $0) }, id: \.self) { n in
                let active = chosen == n
                Tag(n == 0 ? copy.wipeValue(attempts: 0) : "\(n)", accent: active ? c.primary : c.onSurfaceVariant, filled: active) { chosen = n }
            }
        }
        if turningOff {
            Text(copy.WIPE_INFO).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
        } else {
            Text(copy.wipeConfirmBody(attempts: Int32(chosen), eraseModels: state.wipeModels))
                .textStyle(.bodySmall).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
            field(copy.CURRENT_PIN, $current)
        }
        PrimaryButton(turningOff ? copy.TURN_OFF : copy.TURN_ON, enabled: !busy && chosen != state.wipeAfter && (turningOff || !current.isEmpty), busy: busy) {
            let (chosen, current) = (chosen, current)
            run { $0.setWipeAfter(chosen, current: chosen == 0 ? nil : current) }
        }
    }

    // MARK: Duress

    @ViewBuilder private var duressBody: some View {
        if state.duress {
            SectionLabel(copy.DURESS_TITLE.lowercased())
            Text(copy.DURESS_INFO).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
            PrimaryButton(copy.TURN_OFF) {
                lock.clearDuress()
                onDone()
            }
        } else {
            SectionLabel(copy.DURESS_CONFIRM_TITLE.lowercased())
            Text(copy.duressConfirmBody(eraseModels: state.wipeModels)).textStyle(.bodySmall).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
            field(copy.CURRENT_PIN, $current)
            field(copy.DURESS_PIN, $pin)
            field(copy.CONFIRM_DURESS_PIN, $confirm)
            PrimaryButton(copy.TURN_ON, enabled: !busy && !current.isEmpty && !pin.isEmpty && !confirm.isEmpty, busy: busy) {
                let (current, pin, confirm) = (current, pin, confirm)
                run { $0.setDuress(current: current, duress: pin, confirm: confirm) }
            }
        }
    }

    /// A masked digits-only field; secure fields teach the keyboard nothing.
    private func field(_ label: String, _ text: Binding<String>) -> some View {
        ConsoleTextField(text: Binding(get: { text.wrappedValue }, set: { typed in
            text.wrappedValue = String(typed.filter(\.isNumber).prefix(Int(PinRules.shared.MAX_LENGTH)))
        }), label: label, kind: .secret)
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

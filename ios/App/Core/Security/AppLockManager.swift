import AutobotShared
import Foundation
import LocalAuthentication
import Observation

/// Whether the UI is currently allowed to show protected content.
enum LockState: Equatable { case locked, unlocked }

private struct OutcomeBox: @unchecked Sendable {
    let outcome: (any UnlockOutcome)?
}

/// What the lock screen shows after a PIN.
enum PinResult: Equatable {
    case unlocked
    /// A wrong PIN, or an attempt refused while the back-off runs (nothing counted then).
    case wrong(LockStatus, refused: Bool)
    /// The duress PIN or the last allowed wrong PIN: a wipe runs and the app starts over.
    case wiping
}

/**
 App lock gate (the iOS counterpart of `AppLockManager.kt`).

 - Starts locked. [configure] is called once settings are loaded; if the lock is disabled (or
   the device has no passcode) the app unlocks immediately.
 - When the app returns to the foreground after being in the background for at least
   [timeoutSeconds] it re-locks. A timeout of 0 locks on every background transition.
 - [authenticate] uses Face ID / Touch ID with the device passcode as fallback
   (`.deviceOwnerAuthentication`).

 The background timestamp uses `ProcessInfo.systemUptime`, a monotonic clock, so changing the
 wall clock cannot be used to bypass the lock.

 With an app PIN ([PinLock]) the lock screen asks for the PIN instead and the device passcode no
 longer opens the app. Face ID / Touch ID may, while no wrong PIN was entered since the last
 unlock; they never count as attempts and never trigger the duress PIN, which works on the PIN
 pad only. The duress PIN and the wipe threshold hand over to [onPanic]; for the duress PIN the
 screen behaves exactly as for the real one.
 */
@MainActor
@Observable
final class AppLockManager {
    static let defaultTimeoutSeconds: TimeInterval = 60

    private(set) var state: LockState = .locked
    /// `false` until [configure] has been called once; the UI shows nothing until then.
    private(set) var ready = false
    private(set) var enabled = true
    private(set) var timeoutSeconds: TimeInterval = AppLockManager.defaultTimeoutSeconds
    private(set) var authenticating = false
    @ObservationIgnored private var backgroundedAt: TimeInterval?
    /// The app PIN, the duress PIN and the attempt counter (nil: device credential only).
    @ObservationIgnored let pin: PinLock?
    /// Runs a wipe from the lock screen (the duress PIN with its verifier, or the failed-unlock limit).
    @ObservationIgnored var onPanic: (@MainActor (WipeLanding, PinVerifier?) async -> Void)?
    /// This start follows a duress wipe: the lock screen shows for a moment and prints away as if
    /// the PIN had just been accepted ([finishQuietStart]).
    private(set) var quietStart = false
    /// A PIN is being checked.
    private(set) var checking = false

    init(pin: PinLock? = nil) {
        self.pin = pin
    }

    /// Applies persisted lock preferences.
    func configure(enabled: Bool, timeoutSeconds: TimeInterval) {
        self.enabled = enabled
        self.timeoutSeconds = max(0, timeoutSeconds)
        let first = !ready
        if quietStart {
            // Locked until the lock screen prints away (finishQuietStart).
            if first { state = .locked }
        } else if !isLockActive() {
            state = .unlocked
        } else if first {
            state = .locked
        }
        ready = true
    }

    /**
     `true` while an app PIN is set (a PIN always locks: the wipe and the duress PIN depend on it),
     or the lock is enabled and the device can authenticate the user.
     */
    func isLockActive() -> Bool { !DebugFlags.screenshots && (usesPin || (enabled && canAuthenticate())) }

    /// The lock screen shows the PIN pad.
    var usesPin: Bool { pin?.record.hasPin ?? false }

    /// Biometrics may open the app now (with a PIN: allowed in Settings and no wrong PIN since the last unlock).
    func biometricsOffered() -> Bool {
        guard let pin, pin.record.hasPin else { return true }
        return pin.record.biometricsAllowed && LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    /// Counter, warning and back-off for the lock screen.
    func pinStatus() -> LockStatus? { pin?.status() }

    /**
     Checks a PIN typed on the lock screen (two PBKDF2 derivations, off the main thread). The real
     PIN unlocks; the duress PIN and the last allowed wrong PIN run [onPanic], the duress one with
     no visible difference from the real PIN.
     */
    func submitPin(_ typed: String) async -> PinResult {
        guard let pin, !checking else { return .wrong(pin?.status() ?? LockStatus(failures: 0, remaining: nil, warn: false, waitMillis: 0), refused: true) }
        checking = true
        defer { checking = false }
        // Kotlin values are immutable; the box only carries the result back to the main actor.
        let outcome = await Task.detached(priority: .userInitiated) { OutcomeBox(outcome: pin.attempt(typed)) }.value.outcome
        switch outcome {
        case nil:
            return .wrong(pin.status(), refused: true)
        case is UnlockOutcomeUnlocked:
            unlocked()
            return .unlocked
        case let duress as UnlockOutcomeDuress:
            await onPanic?(.quiet, duress.record.duress)
            return .wiping
        case is UnlockOutcomeWipe:
            await onPanic?(.firstRun, nil)
            return .wiping
        default:
            return .wrong(pin.status(), refused: false)
        }
    }

    /// Marks this start as the end of a duress unlock.
    func beginQuietStart() {
        quietStart = true
        state = .locked
    }

    func finishQuietStart() {
        guard quietStart else { return }
        quietStart = false
        unlocked()
    }

    private func unlocked() {
        backgroundedAt = nil
        state = .unlocked
    }

    /// Whether a passcode (and possibly biometrics) is set up.
    func canAuthenticate() -> Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    }

    /// Name of the biometric the device offers ("Face ID", "Touch ID", or "passcode").
    func biometryLabel() -> String {
        let context = LAContext()
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return switch context.biometryType {
        case .faceID: "Face ID"
        case .touchID: "Touch ID"
        case .opticID: "Optic ID"
        default: "passcode"
        }
    }

    /// Locks immediately (e.g. from a "lock now" action).
    func lock() {
        guard !quietStart else { return }
        if isLockActive() { state = .locked }
    }

    /// Prompts the user. On success the state becomes unlocked; failures leave it locked and
    /// return the reason.
    func authenticate(reason: String) async -> String? {
        guard isLockActive() else {
            state = .unlocked
            return nil
        }
        guard !authenticating else { return nil }
        let withPin = usesPin
        // With an app PIN the passcode must not open the app: biometrics only, the PIN pad is the fallback.
        if withPin && !biometricsOffered() { return nil }
        authenticating = true
        defer { authenticating = false }
        let context = LAContext()
        context.localizedCancelTitle = withPin ? PanicCopy.shared.CANCEL : "Cancel"
        if withPin { context.localizedFallbackTitle = "" }
        do {
            let policy: LAPolicy = withPin ? .deviceOwnerAuthenticationWithBiometrics : .deviceOwnerAuthentication
            if try await context.evaluatePolicy(policy, localizedReason: reason) {
                // A wrong PIN typed while the prompt was up withdraws biometrics.
                if withPin && !(pin?.record.biometricsAllowed ?? false) { return nil }
                unlocked()
                return nil
            }
            return "Authentication failed"
        } catch {
            // With a PIN, closing the prompt just leaves the PIN pad: nothing to report.
            if withPin, let la = error as? LAError, [.userCancel, .appCancel, .systemCancel, .userFallback].contains(la.code) { return nil }
            return error.localizedDescription
        }
    }

    func didEnterBackground() {
        backgroundedAt = ProcessInfo.processInfo.systemUptime
        if timeoutSeconds == 0 { lock() }
    }

    func willEnterForeground() {
        guard let since = backgroundedAt else { return }
        backgroundedAt = nil
        if ProcessInfo.processInfo.systemUptime - since >= timeoutSeconds { lock() }
    }
}

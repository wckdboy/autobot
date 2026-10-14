import Foundation
import LocalAuthentication
import Observation

/// Whether the UI is currently allowed to show protected content.
enum LockState: Equatable { case locked, unlocked }

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

    /// Applies persisted lock preferences.
    func configure(enabled: Bool, timeoutSeconds: TimeInterval) {
        self.enabled = enabled
        self.timeoutSeconds = max(0, timeoutSeconds)
        let first = !ready
        if !isLockActive() {
            state = .unlocked
        } else if first {
            state = .locked
        }
        ready = true
    }

    /// `true` if the lock is enabled and the device can actually authenticate the user.
    func isLockActive() -> Bool { enabled && canAuthenticate() && !DebugFlags.screenshots }

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
        authenticating = true
        defer { authenticating = false }
        let context = LAContext()
        context.localizedCancelTitle = "Cancel"
        do {
            if try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) {
                backgroundedAt = nil
                state = .unlocked
                return nil
            }
            return "Authentication failed"
        } catch {
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

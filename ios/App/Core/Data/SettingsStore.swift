import Foundation
import Observation

/// Non-secret user preferences. Secrets (API keys) never go here; they live in the Keychain.
struct AppSettings: Codable, Equatable, Sendable {
    static let defaultLockTimeoutMillis: Int64 = 60_000

    /// iOS follows the system appearance by default (Android ships dark-by-default).
    var themeMode: ThemeMode = .system
    var amoled = false
    /// [NetworkMode.encode] form; malformed values fail closed to Offline.
    var networkMode = NetworkMode.encode(.offline)
    var allowLoopbackWhenOffline = true
    var appLockEnabled = true
    var lockTimeoutMillis = AppSettings.defaultLockTimeoutMillis
    var incognitoByDefault = false
    /// The first-run welcome has been dismissed.
    var onboarded = false

    var mode: NetworkMode { NetworkMode.decode(networkMode) }

    init() {}

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        themeMode = (try? c.decode(ThemeMode.self, forKey: .themeMode)) ?? .system
        amoled = (try? c.decode(Bool.self, forKey: .amoled)) ?? false
        networkMode = (try? c.decode(String.self, forKey: .networkMode)) ?? NetworkMode.encode(.offline)
        allowLoopbackWhenOffline = (try? c.decode(Bool.self, forKey: .allowLoopbackWhenOffline)) ?? true
        appLockEnabled = (try? c.decode(Bool.self, forKey: .appLockEnabled)) ?? true
        lockTimeoutMillis = (try? c.decode(Int64.self, forKey: .lockTimeoutMillis)) ?? AppSettings.defaultLockTimeoutMillis
        incognitoByDefault = (try? c.decode(Bool.self, forKey: .incognitoByDefault)) ?? false
        onboarded = (try? c.decode(Bool.self, forKey: .onboarded)) ?? false
    }
}

/**
 Observable settings, persisted in [Preferences], and mirrored into the in-memory enforcement
 points ([NetworkPolicy], [AppLockManager]) — the iOS counterpart of `SettingsRepository` +
 `SettingsSync`. Both enforcement points fail closed (Offline / Locked) until settings load.
 */
@MainActor
@Observable
final class SettingsStore {
    private(set) var settings: AppSettings
    @ObservationIgnored private let preferences: Preferences
    @ObservationIgnored private let policy: NetworkPolicy
    @ObservationIgnored private let lock: AppLockManager

    init(preferences: Preferences, policy: NetworkPolicy, lock: AppLockManager) {
        self.preferences = preferences
        self.policy = policy
        self.lock = lock
        settings = preferences.get("settings", as: AppSettings.self) ?? AppSettings()
        sync()
    }

    private func update(_ change: (inout AppSettings) -> Void) {
        var next = settings
        change(&next)
        guard next != settings else { return }
        settings = next
        preferences.set("settings", next)
        sync()
    }

    private func sync() {
        policy.setAllowLoopbackWhenOffline(settings.allowLoopbackWhenOffline)
        policy.setMode(settings.mode)
        lock.configure(enabled: settings.appLockEnabled, timeoutSeconds: TimeInterval(settings.lockTimeoutMillis) / 1000)
    }

    func setThemeMode(_ mode: ThemeMode) { update { $0.themeMode = mode } }
    func setAmoled(_ enabled: Bool) { update { $0.amoled = enabled } }
    func setNetworkMode(_ mode: NetworkMode) { update { $0.networkMode = NetworkMode.encode(mode) } }
    func setAllowLoopbackWhenOffline(_ allow: Bool) { update { $0.allowLoopbackWhenOffline = allow } }
    func setAppLockEnabled(_ enabled: Bool) { update { $0.appLockEnabled = enabled } }
    func setLockTimeoutMillis(_ millis: Int64) { update { $0.lockTimeoutMillis = max(0, millis) } }
    func setIncognitoByDefault(_ enabled: Bool) { update { $0.incognitoByDefault = enabled } }
    func setOnboarded() { update { $0.onboarded = true } }
}

/// Lock timeout choices (same as Android).
let lockTimeoutOptions: [(millis: Int64, label: String)] = [
    (0, "Immediately"),
    (30_000, "30 s"),
    (60_000, "1 min"),
    (5 * 60_000, "5 min"),
    (15 * 60_000, "15 min"),
]

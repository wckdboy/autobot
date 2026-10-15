import AutobotShared
import Foundation
import Observation

/// Non-secret user preferences. Secrets (API keys) never go here; they live in the Keychain.
/// Defaults, keys and parsing are the shared `SettingsSchema`, so both apps agree.
struct AppSettings: Codable, Equatable, Sendable {
    static let defaultLockTimeoutMillis: Int64 = 60_000

    var themeMode: ThemeMode = .dark
    var amoled = false
    /// Android only (Material You); kept so an export round-trips it.
    var dynamicColor = false
    /// [NetworkMode.encode] form; malformed values fail closed to Offline.
    var networkMode = NetworkMode.encode(.offline)
    var allowLoopbackWhenOffline = true
    var appLockEnabled = true
    var lockTimeoutMillis = AppSettings.defaultLockTimeoutMillis
    var incognitoByDefault = false
    /// The first-run welcome has been dismissed.
    var onboarded = false
    /// Local memory for the agent (off by default).
    var memoryEnabled = false
    /// Private vault: the agent may search the user's indexed documents (off by default).
    var vaultEnabled = false
    /// `SearchProvider` name: `NONE` (web search off), `SEARXNG` or `BRAVE`.
    var searchProvider = "NONE"
    /// SearXNG instance URL. The Brave key is in the Keychain (`AppSettings.SEARCH_API_KEY_REF`).
    var searchBaseUrl = ""
    var searchRoute: ProviderRouting = .inherit
    /// When a provider's usage limit is used up, answer once with its helper model, else the on-device one (off by default).
    var quotaFallback = false

    var webSearchOn: Bool { searchProvider != "NONE" }

    var mode: NetworkMode { NetworkMode.decode(networkMode) }

    init() {}

    /// Pre-1.2.4 storage: one Codable blob under `settings` (read once, then migrated).
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

    /// The shared string form (`SettingsSchema.encode`): what is stored and exported.
    var values: [String: String] {
        let shared = AutobotShared.AppSettings(
            themeMode: AutobotShared.ThemeMode.entries.first { $0.name == themeMode.rawValue } ?? .dark,
            amoled: amoled,
            dynamicColor: dynamicColor,
            networkMode: mode.shared,
            allowLoopbackWhenOffline: allowLoopbackWhenOffline,
            appLockEnabled: appLockEnabled,
            lockTimeoutMillis: lockTimeoutMillis,
            incognitoByDefault: incognitoByDefault,
            onboarded: onboarded,
            memoryEnabled: memoryEnabled,
            vaultEnabled: vaultEnabled,
            searchProvider: SettingsSchema.shared.searchProvider(value: searchProvider),
            searchBaseUrl: searchBaseUrl,
            searchRoute: SettingsSchema.shared.searchRoute(value: searchRoute.rawValue),
            quotaFallback: quotaFallback,
        )
        return SettingsSchema.shared.encode(settings: shared)
    }

    /// Parses the shared string form; unknown, missing or malformed values fall back to defaults.
    init(values: [String: String]) {
        let s = SettingsSchema.shared.decode(values: values)
        themeMode = ThemeMode(rawValue: s.themeMode.name) ?? .dark
        amoled = s.amoled
        dynamicColor = s.dynamicColor
        networkMode = NetworkMode.encode(NetworkMode(shared: s.networkMode))
        allowLoopbackWhenOffline = s.allowLoopbackWhenOffline
        appLockEnabled = s.appLockEnabled
        lockTimeoutMillis = s.lockTimeoutMillis
        incognitoByDefault = s.incognitoByDefault
        onboarded = s.onboarded
        memoryEnabled = s.memoryEnabled
        vaultEnabled = s.vaultEnabled
        searchProvider = s.searchProvider.name
        searchBaseUrl = s.searchBaseUrl
        searchRoute = ProviderRouting(rawValue: s.searchRoute.name) ?? .inherit
        quotaFallback = s.quotaFallback
    }
}

/**
 Observable settings, persisted in [Preferences] as the shared `SettingsSchema` string map, and
 mirrored into the in-memory enforcement points ([NetworkPolicy], [AppLockManager]) — the iOS
 counterpart of `SettingsRepository` + `SettingsSync`. Both fail closed (Offline / Locked).
 */
@MainActor
@Observable
final class SettingsStore {
    static let key = "app_settings"
    private static let legacyKey = "settings"

    private(set) var settings: AppSettings
    @ObservationIgnored private let preferences: Preferences
    @ObservationIgnored private let policy: NetworkPolicy
    @ObservationIgnored private let lock: AppLockManager
    /// Called after every change (keeps the agent's opt-in features in step).
    @ObservationIgnored var onChange: ((AppSettings) -> Void)?

    init(preferences: Preferences, policy: NetworkPolicy, lock: AppLockManager) {
        self.preferences = preferences
        self.policy = policy
        self.lock = lock
        if let values = preferences.get(Self.key, as: [String: String].self) {
            settings = AppSettings(values: values)
        } else if let legacy = preferences.get(Self.legacyKey, as: AppSettings.self) {
            // One-time migration from the Codable blob; round-trips through the shared codec.
            settings = AppSettings(values: legacy.values)
            preferences.set(Self.key, settings.values)
            preferences.remove(Self.legacyKey)
        } else {
            settings = AppSettings(values: [:])
        }
        sync()
    }

    private func update(_ change: (inout AppSettings) -> Void) {
        var next = settings
        change(&next)
        guard next != settings else { return }
        settings = next
        preferences.set(Self.key, next.values)
        sync()
        onChange?(next)
    }

    private func sync() {
        policy.setAllowLoopbackWhenOffline(settings.allowLoopbackWhenOffline)
        policy.setMode(settings.mode)
        lock.configure(enabled: settings.appLockEnabled, timeoutSeconds: TimeInterval(settings.lockTimeoutMillis) / 1000)
    }

    func setThemeMode(_ mode: ThemeMode) { update { $0.themeMode = mode } }
    func setAmoled(_ enabled: Bool) { update { $0.amoled = enabled } }
    func setDynamicColor(_ enabled: Bool) { update { $0.dynamicColor = enabled } }
    func setNetworkMode(_ mode: NetworkMode) { update { $0.networkMode = NetworkMode.encode(mode) } }
    func setAllowLoopbackWhenOffline(_ allow: Bool) { update { $0.allowLoopbackWhenOffline = allow } }
    func setAppLockEnabled(_ enabled: Bool) { update { $0.appLockEnabled = enabled } }
    func setLockTimeoutMillis(_ millis: Int64) { update { $0.lockTimeoutMillis = SettingsSchema.shared.lockTimeout(value: KotlinLong(value: millis)) } }
    func setIncognitoByDefault(_ enabled: Bool) { update { $0.incognitoByDefault = enabled } }
    func setOnboarded() { update { $0.onboarded = true } }
    func setMemoryEnabled(_ enabled: Bool) { update { $0.memoryEnabled = enabled } }
    func setVaultEnabled(_ enabled: Bool) { update { $0.vaultEnabled = enabled } }
    func setQuotaFallback(_ enabled: Bool) { update { $0.quotaFallback = enabled } }

    func setWebSearch(provider: String, baseUrl: String, route: ProviderRouting) {
        update {
            $0.searchProvider = SettingsSchema.shared.searchProvider(value: provider).name
            $0.searchBaseUrl = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            $0.searchRoute = route
        }
    }
}

/// Lock timeout choices: the shared `SettingsSchema.LOCK_TIMEOUTS_MILLIS` (same as Android).
let lockTimeoutOptions: [(millis: Int64, label: String)] = SettingsSchema.shared.LOCK_TIMEOUTS_MILLIS.map { value in
    let millis = value.int64Value
    let label = switch millis {
    case 0: "Immediately"
    case ..<60_000: "\(millis / 1000) s"
    default: "\(millis / 60_000) min"
    }
    return (millis, label)
}

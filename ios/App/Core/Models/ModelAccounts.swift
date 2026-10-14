import Foundation
import Observation

/// Civitai web/API host. `.red` is the domain that includes mature content.
enum CivitaiHost: String, Codable, CaseIterable, Sendable {
    case com = "COM", red = "RED"

    var host: String { self == .com ? "civitai.com" : "civitai.red" }
}

struct AccountState: Equatable, Sendable {
    var huggingFaceUser: String? = nil
    var civitaiUser: String? = nil
    var civitaiHost: CivitaiHost = .com
    /// Mature content shown in Civitai results; requires an explicit 18+ confirmation.
    var showMature = false
}

/**
 Model-hub credentials (port of `ModelAccounts.kt`). Tokens are kept in the Keychain
 ([SecretStore]); only the display names returned by the hubs are kept in [Preferences]. A token
 is only ever sent to its own hub's host — downloads strip it on redirects to CDNs.
 */
@MainActor
@Observable
final class ModelAccounts {
    private static let refHF = "account-huggingface"
    private static let refCivitai = "account-civitai"

    private(set) var state: AccountState
    @ObservationIgnored private let preferences: Preferences
    @ObservationIgnored private let secrets: SecretStore

    init(preferences: Preferences, secrets: SecretStore) {
        self.preferences = preferences
        self.secrets = secrets
        state = AccountState(
            huggingFaceUser: preferences.get("account_hf_user", as: String.self),
            civitaiUser: preferences.get("account_civitai_user", as: String.self),
            civitaiHost: preferences.get("civitai_host", as: CivitaiHost.self) ?? .com,
            showMature: preferences.get("civitai_show_mature", as: Bool.self) ?? false,
        )
    }

    func huggingFaceToken() -> Secret? { secrets.get(Self.refHF) }
    func civitaiKey() -> Secret? { secrets.get(Self.refCivitai) }

    func setHuggingFace(_ token: Secret, user: String) throws {
        try secrets.put(Self.refHF, token)
        preferences.set("account_hf_user", user)
        state.huggingFaceUser = user
    }

    func clearHuggingFace() {
        secrets.delete(Self.refHF)
        preferences.remove("account_hf_user")
        state.huggingFaceUser = nil
    }

    func setCivitai(_ key: Secret, user: String) throws {
        try secrets.put(Self.refCivitai, key)
        preferences.set("account_civitai_user", user)
        state.civitaiUser = user
    }

    func clearCivitai() {
        secrets.delete(Self.refCivitai)
        preferences.remove("account_civitai_user")
        state.civitaiUser = nil
    }

    func setCivitaiHost(_ host: CivitaiHost) {
        preferences.set("civitai_host", host)
        state.civitaiHost = host
    }

    func setShowMature(_ show: Bool) {
        preferences.set("civitai_show_mature", show)
        state.showMature = show
    }
}

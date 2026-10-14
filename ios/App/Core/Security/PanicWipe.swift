import Foundation

/// Summary of a completed wipe. Contains no user data.
struct WipeReport: Sendable {
    let deletedPaths: Int
    let failedPaths: Int
}

/**
 Irreversibly destroys all local app state (port of `PanicWipe.kt`):

 1. lets every participant stop and release its resources (agents, downloads, generation,
    network sessions, the database connection),
 2. deletes every Keychain item (data keys, the Secure Enclave key handle, API keys, hub tokens),
    making any surviving ciphertext useless,
 3. deletes the app directory (database, gallery, attachments, models, workspace, preferences),
    caches, temporary files and user defaults.

 Callers terminate the process afterwards via [killProcess] so no in-memory state survives.
 */
@MainActor
final class PanicWipe {
    private let keys: KeyManager
    private let secrets: SecretStore
    private let participants: [@MainActor () async -> Void]

    init(keys: KeyManager, secrets: SecretStore, participants: [@MainActor () async -> Void]) {
        self.keys = keys
        self.secrets = secrets
        self.participants = participants
    }

    func wipe() async -> WipeReport {
        for participant in participants { await participant() }
        keys.destroyAllKeys()
        secrets.deleteAll()

        let fm = FileManager.default
        var deleted = 0, failed = 0
        func delete(_ url: URL) {
            guard fm.fileExists(atPath: url.path) else { return }
            if (try? fm.removeItem(at: url)) != nil { deleted += 1 } else { failed += 1 }
        }
        delete(AppPaths.root)
        for dir in [fm.temporaryDirectory] + fm.urls(for: .cachesDirectory, in: .userDomainMask) {
            (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil))?.forEach(delete)
        }
        if let bundle = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: bundle) }
        URLCache.shared.removeAllCachedResponses()
        HTTPCookieStorage.shared.removeCookies(since: .distantPast)
        return WipeReport(deletedPaths: deleted, failedPaths: failed)
    }

    /// Terminates the app process immediately.
    func killProcess() -> Never { exit(0) }
}

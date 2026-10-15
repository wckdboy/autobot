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
    /// How long one participant may take before the wipe goes on without it (an engine unload
    /// queued behind a model load, a bridge that never answers).
    private let participantTimeout: Duration

    init(keys: KeyManager, secrets: SecretStore, participants: [@MainActor () async -> Void], participantTimeout: Duration = .seconds(3)) {
        self.keys = keys
        self.secrets = secrets
        self.participants = participants
        self.participantTimeout = participantTimeout
    }

    func wipe() async -> WipeReport {
        for participant in participants { await Self.run(participant, timeout: participantTimeout) }
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

    /// Terminates the app process immediately: `_exit` skips atexit handlers and static
    /// destructors, which would otherwise run under Metal, Core ML and llama.cpp threads that are
    /// still working and crash on the way out.
    func killProcess() -> Never { _exit(0) }

    /// Runs [participant] for at most [timeout]; a participant still running then is left behind.
    static func run(_ participant: @escaping @MainActor () async -> Void, timeout: Duration) async {
        let gate = Gate()
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            gate.continuation = continuation
            Task { @MainActor in
                await participant()
                gate.open()
            }
            Task { @MainActor in
                try? await Task.sleep(for: timeout)
                gate.open()
            }
        }
    }

    /// Resumes the waiting wipe once, whichever of the participant and the timeout ends first.
    @MainActor
    private final class Gate {
        var continuation: CheckedContinuation<Void, Never>?

        func open() {
            continuation?.resume()
            continuation = nil
        }
    }
}

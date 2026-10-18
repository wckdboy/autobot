import AutobotShared
import Foundation
import Security

/// Summary of a completed wipe. Contains no user data.
struct WipeReport: Sendable {
    let steps: [String]
}

/**
 The file and key side of each `WipeStep`, free of the app's object graph so a wipe found at
 launch can finish before anything opens, and tests can run it on a temporary tree. Every
 method is idempotent: a step cut off by a kill simply runs again.
 */
struct WipeSteps: Sendable {
    let paths: PanicPaths
    let lock: PinLock
    /// Destroys every key of the app except the PIN key (the Keychain, the Secure Enclave handle).
    let destroyKeys: @Sendable () -> Void
    /// Folders whose contents go too (caches, the temporary folder, the share extension's inbox).
    let sweep: [URL]
    /// Everything outside the file system: user defaults, URL cache, cookies.
    let clearOther: @Sendable () -> Void

    func commit(_ journal: WipeJournal) throws {
        try DurableFile.write(Data(WipeJournal.companion.encode(journal: journal).utf8), to: paths.journal)
    }

    func parkModels() {
        let fm = FileManager.default
        guard fm.fileExists(atPath: paths.models.path), !fm.fileExists(atPath: paths.parkedModels.path) else { return }
        try? fm.moveItem(at: paths.models, to: paths.parkedModels)
    }

    /**
     Renames the data folder into a tomb in one step (the app's data is gone from where anything
     looks for it), clears the swept folders and everything else, then deletes the tombs:
     [async] leaves that last part to a background task (the duress landing must not wait for
     gigabytes of gallery to be unlinked); the next launch deletes any tomb left over.
     */
    func deleteData(keepModels: Bool, async: Bool = false) {
        let fm = FileManager.default
        if !keepModels { try? fm.removeItem(at: paths.parkedModels) }
        if fm.fileExists(atPath: paths.appRoot.path) {
            if (try? fm.moveItem(at: paths.appRoot, to: paths.tomb())) == nil { try? fm.removeItem(at: paths.appRoot) }
        }
        for dir in sweep {
            for item in (try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] { try? fm.removeItem(at: item) }
        }
        clearOther()
        let tombs = paths.tombs()
        if async {
            Task.detached(priority: .utility) { for t in tombs { try? FileManager.default.removeItem(at: t) } }
        } else {
            for t in tombs { try? fm.removeItem(at: t) }
        }
    }

    func restore(_ journal: WipeJournal, landing: LockRecord?) {
        let fm = FileManager.default
        if fm.fileExists(atPath: paths.parkedModels.path) {
            try? fm.createDirectory(at: paths.appRoot, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
            if fm.fileExists(atPath: paths.models.path) { try? fm.removeItem(at: paths.models) }
            try? fm.moveItem(at: paths.parkedModels, to: paths.models)
        }
        if let kept = journal.keptModels, fm.fileExists(atPath: paths.models.path) {
            try? DurableFile.write(Data(kept.utf8), to: paths.keptRegistry)
        }
        if let landing {
            try? lock.save(landing)
        } else {
            lock.reset()
        }
    }

    func finish() {
        try? FileManager.default.removeItem(at: paths.journal)
        lock.reload()
    }

    /// The journal of a wipe that did not finish, if any.
    func pending() -> WipeJournal? {
        guard let data = try? Data(contentsOf: paths.journal) else {
            return FileManager.default.fileExists(atPath: paths.journal.path) ? WipeJournal.companion.decode(text: "") : nil
        }
        return WipeJournal.companion.decode(text: String(decoding: data, as: UTF8.self))
    }

    /// Runs the synchronous steps of [journal] that are left (everything but STOP_WORK).
    func runRemaining(_ journal: WipeJournal, resumed: Bool, asyncDelete: Bool = false) -> [WipeStep] {
        var current = journal
        var ran: [WipeStep] = []
        for step in WipePlan.shared.remaining(journal: journal, resumed: resumed) {
            switch step {
            case .commit: continue
            case .destroyKeys: destroyKeys()
            case .stopWork: continue
            case .parkModels: if current.keepModels { parkModels() }
            case .deleteData: deleteData(keepModels: current.keepModels, async: asyncDelete)
            case .restore: restore(current, landing: WipePlan.shared.landingRecord(journal: current))
            case .finish: finish()
            default: continue
            }
            ran.append(step)
            current = current.after(step: step)
            if step != .finish { try? commit(current) }
        }
        return ran
    }

    /// Finishes a wipe a kill interrupted. Call at launch before anything opens the app's data. Also clears tombs.
    @discardableResult
    func resumeIfNeeded() -> Bool {
        defer { for t in paths.tombs() { try? FileManager.default.removeItem(at: t) } }
        guard let journal = pending() else { return false }
        _ = runRemaining(journal, resumed: true)
        return true
    }

    /// The live app's steps.
    static func live(lock: PinLock) -> WipeSteps {
        let fm = FileManager.default
        var sweep = [fm.temporaryDirectory] + fm.urls(for: .cachesDirectory, in: .userDomainMask)
        if let inbox = SharedInbox.defaultRoot { sweep.append(inbox) }
        return WipeSteps(
            paths: lock.paths,
            lock: lock,
            destroyKeys: { KeychainWipe.deleteAll(except: KeychainLockKey.service) },
            sweep: sweep,
            clearOther: {
                if let bundle = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: bundle) }
                URLCache.shared.removeAllCachedResponses()
                HTTPCookieStorage.shared.removeCookies(since: .distantPast)
            },
        )
    }
}

/// Deletes the app's Keychain items: every class, every generic-password service but [except].
enum KeychainWipe {
    static func deleteAll(except keep: String) {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecReturnAttributes as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecUseDataProtectionKeychain as String: true,
        ]
        var result: CFTypeRef?
        var services: Set<String> = []
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let items = result as? [[String: Any]] {
            for item in items { services.insert(item[kSecAttrService as String] as? String ?? "") }
        }
        // The known ones even if the listing failed (the device is locked, say).
        services.formUnion([Keychain.service, SecretStore.service])
        services.remove(keep)
        for service in services {
            query = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecUseDataProtectionKeychain as String: true]
            SecItemDelete(query as CFDictionary)
        }
        for cls in [kSecClassInternetPassword, kSecClassKey, kSecClassCertificate, kSecClassIdentity] {
            SecItemDelete([kSecClass as String: cls, kSecUseDataProtectionKeychain as String: true] as CFDictionary)
        }
    }
}

/**
 Irreversibly destroys the app's local state, in the shared `WipePlan` order (port of `PanicWipe.kt`):

 1. COMMIT: the journal is written and synced: from here the wipe finishes, even after a kill;
 2. DESTROY_KEYS: every Keychain item (data keys, the Secure Enclave handle, API keys, hub
    tokens) goes first, so any ciphertext left behind is noise;
 3. STOP_WORK: every participant stops and releases its resources (agents, downloads,
    generation, network sessions, the database connection);
 4. PARK_MODELS / DELETE_DATA / RESTORE: downloaded models are moved aside (unless they go too),
    the data folder, caches, temporary files and defaults are deleted, the models come back with
    their registry and the lock record is reset for the landing;
 5. FINISH: the journal is removed.

 No step logs, writes an audit entry or posts a notification.
 */
@MainActor
final class PanicWipe {
    let steps: WipeSteps
    private let participants: [@MainActor () async -> Void]
    /// The model registry to carry across (catalog metadata of finished downloads), read before the keys go.
    private let keptModels: @MainActor () -> String?
    /// How long one participant may take before the wipe goes on without it (an engine unload
    /// queued behind a model load, a bridge that never answers).
    private let participantTimeout: Duration

    init(steps: WipeSteps, keptModels: @escaping @MainActor () -> String? = { nil }, participants: [@MainActor () async -> Void], participantTimeout: Duration = .seconds(3)) {
        self.steps = steps
        self.keptModels = keptModels
        self.participants = participants
        self.participantTimeout = participantTimeout
    }

    /**
     Runs a whole wipe in this process. [carriedPin] becomes the PIN of a quiet landing. With
     [asyncDelete] the removal of the renamed data folder finishes in the background.
     */
    func wipe(landing: WipeLanding, keepModels: Bool, carriedPin: PinVerifier? = nil, biometrics: Bool = true, asyncDelete: Bool = false) async -> WipeReport {
        let kept = keepModels ? keptModels() : nil
        var journal = WipePlan.shared.journal(landing: landing, keepModels: keepModels, keptModels: kept, carriedPin: landing == .quiet ? carriedPin : nil, biometrics: biometrics)
        var ran: [String] = []
        // A journal that cannot be written still wipes: the keys go now, the rest right after.
        try? steps.commit(journal.after(step: .commit))
        journal = journal.after(step: .commit)
        ran.append("COMMIT")
        steps.destroyKeys()
        journal = journal.after(step: .destroyKeys)
        try? steps.commit(journal)
        ran.append("DESTROY_KEYS")
        for participant in participants { await Self.run(participant, timeout: participantTimeout) }
        journal = journal.after(step: .stopWork)
        try? steps.commit(journal)
        ran.append("STOP_WORK")
        ran += steps.runRemaining(journal, resumed: false, asyncDelete: asyncDelete).map(\.name)
        return WipeReport(steps: ran)
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

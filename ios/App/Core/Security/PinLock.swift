import AutobotShared
import CommonCrypto
import CryptoKit
import Foundation
import Security

/**
 Where panic protection keeps its files: in Application Support beside (never inside) the app's
 data folder, so a wipe can delete that folder whole and still finish from its journal.

 - `autobot-lock.json`: the shared `LockRecord` (PIN verifiers, the attempt counter, the panic options).
 - `autobot-wipe.json`: the journal of a wipe in progress (see `WipePlan`).
 - `autobot-keep-models/`: downloaded models parked while a wipe deletes everything else.
 - `autobot-tomb-<uuid>/`: the data folder, renamed in one step at the start of the delete and removed after.
 */
struct PanicPaths: Sendable {
    let base: URL
    let appRoot: URL

    var lockRecord: URL { base.appendingPathComponent("autobot-lock.json") }
    var journal: URL { base.appendingPathComponent("autobot-wipe.json") }
    var parkedModels: URL { base.appendingPathComponent("autobot-keep-models", isDirectory: true) }
    var models: URL { appRoot.appendingPathComponent("models", isDirectory: true) }
    /// The model registry carried across a wipe, adopted by `ModelLibrary` on the next load.
    var keptRegistry: URL { models.appendingPathComponent(".kept-models.json") }

    func tomb() -> URL { base.appendingPathComponent("autobot-tomb-\(UUID().uuidString)", isDirectory: true) }

    /// Data folders renamed by a wipe that were not removed yet.
    func tombs() -> [URL] {
        ((try? FileManager.default.contentsOfDirectory(at: base, includingPropertiesForKeys: nil)) ?? [])
            .filter { $0.lastPathComponent.hasPrefix("autobot-tomb-") }
    }

    static let live = PanicPaths(
        base: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0],
        appRoot: AppPaths.root,
    )
}

/// Writes that survive a crash or power loss: a temporary file, `F_FULLFSYNC`, then an atomic rename.
enum DurableFile {
    static func write(_ data: Data, to url: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let temp = url.deletingLastPathComponent().appendingPathComponent(".\(url.lastPathComponent).\(UUID().uuidString)")
        guard fm.createFile(atPath: temp.path, contents: data, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else {
            throw CocoaError(.fileWriteUnknown)
        }
        let fd = open(temp.path, O_RDWR)
        if fd >= 0 {
            _ = fcntl(fd, F_FULLFSYNC)
            close(fd)
        }
        if rename(temp.path, url.path) != 0 {
            try? fm.removeItem(at: temp)
            throw CocoaError(.fileWriteUnknown)
        }
        let dir = open(url.deletingLastPathComponent().path, O_RDONLY)
        if dir >= 0 {
            _ = fsync(dir)
            close(dir)
        }
    }
}

/// The device-bound key that turns a stretched PIN into a verifier.
protocol LockKeyStore: Sendable {
    func read() -> Data?
    func write(_ key: Data) throws
    func delete()
}

/**
 The PIN key in the Keychain, under its own service (`ai.wckd.autobot.lock`), this device only. A
 wipe destroys every other Keychain item first; this one only goes when the app returns to first
 run, so a duress landing keeps accepting the PIN it was opened with.
 */
struct KeychainLockKey: LockKeyStore {
    static let service = "ai.wckd.autobot.lock"
    private static let account = "autobot.lock.pin.v1"

    func read() -> Data? { try? Keychain.read(Self.account, service: Self.service) }
    func write(_ key: Data) throws { try Keychain.write(key, Self.account, service: Self.service) }
    func delete() { Keychain.delete(Self.account, service: Self.service) }
}

/// A PIN key in memory (tests, and the simulator's unit tests that must not touch the Keychain).
final class MemoryLockKey: LockKeyStore, @unchecked Sendable {
    private let lock = NSLock()
    private var key: Data?

    init(_ key: Data? = nil) { self.key = key }

    func read() -> Data? { lock.withLock { key } }
    func write(_ key: Data) throws { lock.withLock { self.key = key } }
    func delete() { lock.withLock { key = nil } }
}

/// `PinCrypto` for iOS: CommonCrypto PBKDF2-HMAC-SHA256, then HMAC-SHA256 with the device PIN key.
final class DevicePinCrypto: NSObject, PinCrypto, @unchecked Sendable {
    private let keys: LockKeyStore
    private let lock = NSLock()

    init(keys: LockKeyStore) { self.keys = keys }

    func doNewSalt() -> String { Self.random(16).base64EncodedString() }

    func derive(pin: String, salt: String, iterations: Int32) -> String {
        let saltBytes = [UInt8](Data(base64Encoded: salt) ?? Data())
        let password = Array(pin.utf8)
        var stretched = [UInt8](repeating: 0, count: 32)
        let status = password.withUnsafeBufferPointer { pw in
            CCKeyDerivationPBKDF(
                CCPBKDFAlgorithm(kCCPBKDF2), pw.baseAddress.map { UnsafeRawPointer($0).assumingMemoryBound(to: CChar.self) }, pw.count,
                saltBytes, saltBytes.count, CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(max(iterations, 1)), &stretched, stretched.count,
            )
        }
        guard status == kCCSuccess else { return "" }
        let mac = HMAC<SHA256>.authenticationCode(for: stretched, using: SymmetricKey(data: deviceKey()))
        stretched.withUnsafeMutableBytes { _ = memset($0.baseAddress, 0, $0.count) }
        return Data(mac).base64EncodedString()
    }

    /// The device key, created on first use.
    private func deviceKey() -> Data {
        lock.withLock {
            if let key = keys.read(), key.count == 32 { return key }
            let key = Self.random(32)
            try? keys.write(key)
            return key
        }
    }

    private static func random(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes)
    }
}

/**
 The persisted `LockRecord` and the checks around it (the iOS side of the shared `UnlockPolicy`).
 Every attempt is written (and synced) as failed before the PIN is checked, so killing the app
 mid-check cannot reset the counter. Nothing here logs.
 */
final class PinLock: @unchecked Sendable {
    let paths: PanicPaths
    let keys: LockKeyStore
    let vault: PinVault
    private let lock = NSLock()
    private var cached: LockRecord?

    init(paths: PanicPaths = .live, keys: LockKeyStore = KeychainLockKey()) {
        self.paths = paths
        self.keys = keys
        vault = PinVault(crypto: DevicePinCrypto(keys: keys))
    }

    var record: LockRecord {
        lock.withLock {
            if let cached { return cached }
            let text = (try? Data(contentsOf: paths.lockRecord)).map { String(decoding: $0, as: UTF8.self) }
            let loaded = LockRecord.companion.decode(text: text)
            cached = loaded
            return loaded
        }
    }

    func save(_ record: LockRecord) throws {
        try DurableFile.write(Data(LockRecord.companion.encode(record: record).utf8), to: paths.lockRecord)
        lock.withLock { cached = record }
    }

    /// Forgets the record and the PIN key (the app returns to first run).
    func reset() {
        try? FileManager.default.removeItem(at: paths.lockRecord)
        keys.delete()
        lock.withLock { cached = nil }
    }

    /// Drops the in-memory copy (a wipe rewrote the file).
    func reload() { lock.withLock { cached = nil } }

    /**
     One PIN attempt: journal it as failed, check it against both PINs, store the outcome. Nil while
     the back-off runs (the attempt is refused and not counted). Runs two PBKDF2 derivations: call
     off the main thread.
     */
    func attempt(_ pin: String, now: Int64 = nowMillis()) -> (any UnlockOutcome)? {
        guard let begun = UnlockPolicy.shared.begin(record: record, now: now) else { return nil }
        // If the journal cannot be written, the attempt is not allowed: an unrecorded guess is a free guess.
        guard (try? save(begun)) != nil else { return nil }
        let match = vault.match(pin: pin, record: begun)
        let outcome = UnlockPolicy.shared.resolve(begun: begun, match: match, now: now)
        try? save(outcome.record)
        return outcome
    }

    func matchesPin(_ pin: String) -> Bool {
        guard let real = record.pin else { return false }
        return vault.matches(pin: pin, verifier: real)
    }

    func status(now: Int64 = nowMillis()) -> LockStatus { UnlockPolicy.shared.status(record: record, now: now) }

    // MARK: Settings (each returns nil on success or the reason it failed, as on Android)

    static let wrongPin = PanicCopy.shared.WRONG_PIN

    /// Sets the first PIN (no PIN yet).
    func setPin(_ pin: String, confirm: String) -> String? {
        if record.hasPin { return "A PIN is already set" }
        if let problem = PinRules.shared.confirmProblem(pin: pin, confirm: confirm) { return problem }
        let r = record
        return store(r.doCopy(pin: vault.create(pin: pin), duress: r.duress, wipeAfter: r.wipeAfter, wipeModels: r.wipeModels, biometrics: r.biometrics, failures: 0, lastFailureAt: 0, quietStart: false))
    }

    /// Replaces the PIN; the duress PIN must still differ from the new one.
    func changePin(current: String, pin: String, confirm: String) -> String? {
        guard matchesPin(current) else { return Self.wrongPin }
        if let problem = PinRules.shared.confirmProblem(pin: pin, confirm: confirm) { return problem }
        let r = record
        if let duress = r.duress, vault.matches(pin: pin, verifier: duress) { return "Your PIN must differ from the duress PIN" }
        return store(r.doCopy(pin: vault.create(pin: pin), duress: r.duress, wipeAfter: r.wipeAfter, wipeModels: r.wipeModels, biometrics: r.biometrics, failures: r.failures, lastFailureAt: r.lastFailureAt, quietStart: r.quietStart))
    }

    /// Removes the PIN with the duress PIN and the wipe threshold that depend on it.
    func removePin(current: String) -> String? {
        guard matchesPin(current) else { return Self.wrongPin }
        return store(record.withoutPin())
    }

    func setDuress(current: String, duress: String, confirm: String) -> String? {
        guard matchesPin(current), let real = record.pin else { return Self.wrongPin }
        if let problem = PinRules.shared.confirmProblem(pin: duress, confirm: confirm) { return problem }
        if let problem = PinRules.shared.duressProblem(candidate: duress, matchesReal: vault.matches(pin: duress, verifier: real)) { return problem }
        let r = record
        return store(r.doCopy(pin: r.pin, duress: vault.create(pin: duress), wipeAfter: r.wipeAfter, wipeModels: r.wipeModels, biometrics: r.biometrics, failures: r.failures, lastFailureAt: r.lastFailureAt, quietStart: r.quietStart))
    }

    func clearDuress() {
        let r = record
        _ = store(r.doCopy(pin: r.pin, duress: nil, wipeAfter: r.wipeAfter, wipeModels: r.wipeModels, biometrics: r.biometrics, failures: r.failures, lastFailureAt: r.lastFailureAt, quietStart: r.quietStart))
    }

    /// Turning the wipe on needs the PIN; turning it off does not.
    func setWipeAfter(_ attempts: Int, current: String?) -> String? {
        if attempts > 0 && !(current.map(matchesPin) ?? false) { return Self.wrongPin }
        guard attempts == 0 || UnlockPolicy.shared.WipeChoices.contains(KotlinInt(value: Int32(attempts))) else { return "Unsupported wipe threshold" }
        return store(UnlockPolicy.shared.withWipeAfter(record: record, attempts: Int32(attempts)))
    }

    func setWipeModels(_ on: Bool) {
        let r = record
        _ = store(r.doCopy(pin: r.pin, duress: r.duress, wipeAfter: r.wipeAfter, wipeModels: on, biometrics: r.biometrics, failures: r.failures, lastFailureAt: r.lastFailureAt, quietStart: r.quietStart))
    }

    func setBiometrics(_ on: Bool) {
        let r = record
        _ = store(r.doCopy(pin: r.pin, duress: r.duress, wipeAfter: r.wipeAfter, wipeModels: r.wipeModels, biometrics: on, failures: r.failures, lastFailureAt: r.lastFailureAt, quietStart: r.quietStart))
    }

    /// Saves [record]; a failed write is a problem too.
    private func store(_ record: LockRecord) -> String? {
        do {
            try save(record)
            return nil
        } catch {
            return "Could not save. Try again."
        }
    }

    /// The quiet landing was shown: later starts lock as usual.
    func clearQuietStart() {
        let r = record
        guard r.quietStart else { return }
        try? save(r.doCopy(pin: r.pin, duress: r.duress, wipeAfter: r.wipeAfter, wipeModels: r.wipeModels, biometrics: r.biometrics, failures: r.failures, lastFailureAt: r.lastFailureAt, quietStart: false))
    }
}

extension PinLock: CustomStringConvertible {
    var description: String { "PinLock(<redacted>)" }
}

import CryptoKit
import Foundation
import Security
import Synchronization

/// Keychain operation failure (status code only; never contains key material).
struct KeychainError: LocalizedError {
    let status: OSStatus
    var errorDescription: String? { "Keychain error \(status)" }
}

/// Minimal generic-password Keychain access. Every item is `WhenUnlockedThisDeviceOnly`: never
/// synced to iCloud Keychain, never in backups, unreadable while the device is locked.
enum Keychain {
    static let service = "ai.wckd.autobot"

    static func read(_ account: String, service: String = Keychain.service) throws -> Data? {
        var query = base(account, service)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw KeychainError(status: status) }
        return result as? Data
    }

    static func write(_ data: Data, _ account: String, service: String = Keychain.service) throws {
        let query = base(account, service)
        let update = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else { throw KeychainError(status: update) }
        var add = query
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    static func delete(_ account: String, service: String = Keychain.service) {
        SecItemDelete(base(account, service) as CFDictionary)
    }

    /// Deletes every generic password of [service] (panic wipe).
    static func deleteAll(service: String = Keychain.service) {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
    }

    private static func base(_ account: String, _ service: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }
}

/**
 Owns all key material of the app (the iOS counterpart of `KeyManager.kt`).

 - Data keys (database, media) are 32 random bytes generated on first use.
 - When the device has a Secure Enclave, each data key is *wrapped* by a P-256 key that lives in
   the Secure Enclave (ECDH with an ephemeral key → HKDF-SHA256 → AES-256-GCM); only the wrapped
   blob and the enclave key's opaque, device-bound handle are stored in the Keychain. Without a
   Secure Enclave (Simulator) the data key is stored in the Keychain directly.
 - Unwrapped keys live only in process memory.
 */
final class KeyManager: Sendable {
    static let aliasDatabase = "autobot.key.database.v1"
    static let aliasMedia = "autobot.key.media.v1"
    private static let enclaveKey = "autobot.kek.enclave.v1"
    private static let wrapInfo = Data("autobot:wrap:v1".utf8)

    private let cache = Mutex<[String: SymmetricKey]>([:])

    init() {}

    /// Whether data keys are wrapped by the Secure Enclave on this device.
    var secureEnclaveBacked: Bool { SecureEnclave.isAvailable }

    /// Key for the encrypted database payloads.
    func databaseKey() throws -> SymmetricKey { try key(Self.aliasDatabase) }

    /// Key for bulk media (gallery images, attachments).
    func mediaKey() throws -> SymmetricKey { try key(Self.aliasMedia) }

    func key(_ alias: String) throws -> SymmetricKey {
        if let cached = cache.withLock({ $0[alias] }) { return cached }
        let key: SymmetricKey
        if let stored = try Keychain.read(alias) {
            key = try unwrap(stored, alias: alias)
        } else {
            key = SymmetricKey(size: .bits256)
            try Keychain.write(try wrap(key, alias: alias), alias)
        }
        cache.withLock { $0[alias] = key }
        return key
    }

    /**
     Deletes every key (Keychain items, the enclave key handle) and the in-memory copies. After
     this, previously encrypted data is cryptographically unrecoverable.
     */
    func destroyAllKeys() {
        cache.withLock { $0.removeAll() }
        Keychain.deleteAll()
    }

    // MARK: Wrapping

    private enum Format: UInt8 { case plain = 0, enclave = 1 }

    private func wrap(_ key: SymmetricKey, alias: String) throws -> Data {
        let raw = key.withUnsafeBytes { Data($0) }
        guard SecureEnclave.isAvailable else { return Data([Format.plain.rawValue]) + raw }
        let enclave = try enclaveKeyPair()
        let ephemeral = P256.KeyAgreement.PrivateKey()
        let kek = try derive(ephemeral.sharedSecretFromKeyAgreement(with: enclave.publicKey), ephemeral.publicKey)
        let sealed = try AesGcmEnvelope.seal(kek, raw, aad: Data(alias.utf8))
        let pub = ephemeral.publicKey.x963Representation
        return Data([Format.enclave.rawValue, UInt8(pub.count)]) + pub + sealed
    }

    private func unwrap(_ blob: Data, alias: String) throws -> SymmetricKey {
        let bytes = [UInt8](blob)
        guard let first = bytes.first, let format = Format(rawValue: first) else { throw EnvelopeError(message: "Unknown key format") }
        switch format {
        case .plain:
            return SymmetricKey(data: Data(bytes.dropFirst()))
        case .enclave:
            guard bytes.count > 2 else { throw EnvelopeError(message: "Wrapped key truncated") }
            let pubLen = Int(bytes[1])
            let pub = try P256.KeyAgreement.PublicKey(x963Representation: Data(bytes[2 ..< 2 + pubLen]))
            let enclave = try enclaveKeyPair()
            let kek = try derive(enclave.sharedSecretFromKeyAgreement(with: pub), pub)
            return SymmetricKey(data: try AesGcmEnvelope.open(kek, Data(bytes[(2 + pubLen)...]), aad: Data(alias.utf8)))
        }
    }

    private func derive(_ secret: SharedSecret, _ ephemeral: P256.KeyAgreement.PublicKey) throws -> SymmetricKey {
        secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: ephemeral.x963Representation, sharedInfo: Self.wrapInfo, outputByteCount: 32)
    }

    private func enclaveKeyPair() throws -> SecureEnclave.P256.KeyAgreement.PrivateKey {
        if let handle = try Keychain.read(Self.enclaveKey) {
            return try SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: handle)
        }
        var error: Unmanaged<CFError>?
        guard let access = SecAccessControlCreateWithFlags(nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .privateKeyUsage, &error) else {
            throw error!.takeRetainedValue() as Error
        }
        let key = try SecureEnclave.P256.KeyAgreement.PrivateKey(accessControl: access)
        try Keychain.write(key.dataRepresentation, Self.enclaveKey)
        return key
    }
}

extension KeyManager: CustomStringConvertible {
    var description: String { "KeyManager(<redacted>)" }
}

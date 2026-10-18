import AutobotShared
import CryptoKit
import Foundation

/// Crypto for talking to an Invincible node (`HostNodeCrypto`), on CryptoKit: X25519,
/// ChaCha20-Poly1305, SHA-256, HMAC-SHA256 and Ed25519 verification. Checked against the node's
/// conformance vectors by `InvincibleNodeTests`.
final class SwiftNodeCrypto: NSObject, HostNodeCrypto, @unchecked Sendable {
    private func data(_ bytes: KotlinByteArray) -> Data { BackupBytes.shared.toData(bytes: bytes) as Data }
    private func bytes(_ data: Data) -> KotlinByteArray { BackupBytes.shared.fromData(data: data) }

    func randomBytes(count: Int32) -> KotlinByteArray {
        var out = Data(count: Int(count))
        let status = out.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, Int(count), $0.baseAddress!) }
        precondition(status == errSecSuccess, "no random bytes")
        return bytes(out)
    }

    func x25519PublicKey(privateKey: KotlinByteArray) -> KotlinByteArray? {
        guard let key = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data(privateKey)) else { return nil }
        return bytes(key.publicKey.rawRepresentation)
    }

    func x25519(privateKey: KotlinByteArray, publicKey: KotlinByteArray) -> KotlinByteArray? {
        guard let mine = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: data(privateKey)),
              let theirs = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: data(publicKey)),
              let shared = try? mine.sharedSecretFromKeyAgreement(with: theirs) else { return nil }
        return bytes(shared.withUnsafeBytes { Data($0) })
    }

    func chachaSeal(key: KotlinByteArray, nonce: KotlinByteArray, aad: KotlinByteArray, plaintext: KotlinByteArray) -> KotlinByteArray? {
        guard let n = try? ChaChaPoly.Nonce(data: data(nonce)),
              let box = try? ChaChaPoly.seal(data(plaintext), using: SymmetricKey(data: data(key)), nonce: n, authenticating: data(aad))
        else { return nil }
        return bytes(box.ciphertext + box.tag)
    }

    func chachaOpen(key: KotlinByteArray, nonce: KotlinByteArray, aad: KotlinByteArray, sealed: KotlinByteArray) -> KotlinByteArray? {
        let all = data(sealed)
        guard all.count >= 16, let n = try? ChaChaPoly.Nonce(data: data(nonce)),
              let box = try? ChaChaPoly.SealedBox(nonce: n, ciphertext: all.prefix(all.count - 16), tag: all.suffix(16)),
              let plain = try? ChaChaPoly.open(box, using: SymmetricKey(data: data(key)), authenticating: data(aad))
        else { return nil }
        return bytes(plain)
    }

    func sha256(data input: KotlinByteArray) -> KotlinByteArray { bytes(Data(SHA256.hash(data: data(input)))) }

    func hmacSha256(key: KotlinByteArray, data input: KotlinByteArray) -> KotlinByteArray {
        bytes(Data(HMAC<SHA256>.authenticationCode(for: data(input), using: SymmetricKey(data: data(key)))))
    }

    func ed25519Verify(publicKey: KotlinByteArray, message: KotlinByteArray, signature: KotlinByteArray) -> Bool {
        guard let key = try? Curve25519.Signing.PublicKey(rawRepresentation: data(publicKey)) else { return false }
        return key.isValidSignature(data(signature), for: data(message))
    }
}

import CryptoKit
import Foundation

/// Malformed, tampered or wrongly keyed envelope.
struct EnvelopeError: LocalizedError, Equatable {
    let message: String
    var errorDescription: String? { message }
}

/**
 Self-describing AES-256-GCM envelope, byte-compatible with Android's `AesGcmEnvelope.kt`:

 ```
 +---------+--------+---------+--------------------------+
 | version | ivLen  |   iv    | ciphertext || 16B GCM tag |
 |   1     |   1    | ivLen   |          rest            |
 +---------+--------+---------+--------------------------+
 ```

 The IV is always random (CryptoKit generates it). Optional associated data (AAD) binds a
 ciphertext to its context (e.g. a row id), so blobs cannot be swapped between slots without
 detection.
 */
enum AesGcmEnvelope {
    static let version: UInt8 = 1
    static let ivLength = 12
    static let tagLength = 16
    private static let headerLength = 2

    /// Decoded but still encrypted envelope parts.
    struct Parts: Equatable, CustomStringConvertible {
        let iv: Data
        /// Ciphertext followed by the 16-byte tag.
        let ciphertext: Data
        var description: String { "Parts(iv=\(iv.count)B, ciphertext=\(ciphertext.count)B)" }
    }

    /// Encrypts [plaintext] with [key] and returns an encoded envelope.
    static func seal(_ key: SymmetricKey, _ plaintext: Data, aad: Data? = nil) throws -> Data {
        let box = if let aad {
            try AES.GCM.seal(plaintext, using: key, authenticating: aad)
        } else {
            try AES.GCM.seal(plaintext, using: key)
        }
        return encode(Parts(iv: Data(box.nonce), ciphertext: box.ciphertext + box.tag))
    }

    /// Decrypts an envelope produced by [seal]. Throws if malformed, tampered, the AAD does not
    /// match, or the key is wrong.
    static func open(_ key: SymmetricKey, _ envelope: Data, aad: Data? = nil) throws -> Data {
        let parts = try decode(envelope)
        do {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: parts.iv),
                ciphertext: parts.ciphertext.dropLast(tagLength),
                tag: parts.ciphertext.suffix(tagLength),
            )
            return if let aad { try AES.GCM.open(box, using: key, authenticating: aad) } else { try AES.GCM.open(box, using: key) }
        } catch {
            throw EnvelopeError(message: "Envelope authentication failed")
        }
    }

    /// Serializes [parts] into the wire format.
    static func encode(_ parts: Parts) -> Data {
        precondition((1 ... 255).contains(parts.iv.count), "Invalid IV length")
        var out = Data(capacity: headerLength + parts.iv.count + parts.ciphertext.count)
        out.append(version)
        out.append(UInt8(parts.iv.count))
        out.append(parts.iv)
        out.append(parts.ciphertext)
        return out
    }

    /// Parses the wire format. Throws on unknown version or truncated input.
    static func decode(_ envelope: Data) throws -> Parts {
        let bytes = [UInt8](envelope)
        guard bytes.count >= headerLength else { throw EnvelopeError(message: "Envelope too short") }
        guard bytes[0] == version else { throw EnvelopeError(message: "Unsupported envelope version \(bytes[0])") }
        let ivLen = Int(bytes[1])
        guard ivLen == ivLength else { throw EnvelopeError(message: "Unexpected IV length \(ivLen)") }
        guard bytes.count >= headerLength + ivLen + tagLength else { throw EnvelopeError(message: "Envelope truncated") }
        return Parts(iv: Data(bytes[headerLength ..< headerLength + ivLen]), ciphertext: Data(bytes[(headerLength + ivLen)...]))
    }
}

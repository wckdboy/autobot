import CryptoKit
import Foundation
import Testing
@testable import Autobot

/// Port of `AesGcmEnvelopeTest.kt` (the wire format is byte-compatible with Android).
struct AesGcmEnvelopeTests {
    private let key = SymmetricKey(size: .bits256)
    private let plaintext = Data("sk-very-secret-api-key".utf8)

    @Test func roundTrip() throws {
        #expect(try AesGcmEnvelope.open(key, AesGcmEnvelope.seal(key, plaintext)) == plaintext)
    }

    @Test func roundTripWithAad() throws {
        let aad = Data("ref-1".utf8)
        #expect(try AesGcmEnvelope.open(key, AesGcmEnvelope.seal(key, plaintext, aad: aad), aad: aad) == plaintext)
    }

    @Test func emptyPlaintextRoundTrips() throws {
        #expect(try AesGcmEnvelope.open(key, AesGcmEnvelope.seal(key, Data())).isEmpty)
    }

    @Test func headerLayout() throws {
        let sealed = try AesGcmEnvelope.seal(key, plaintext)
        #expect(sealed[0] == AesGcmEnvelope.version)
        #expect(Int(sealed[1]) == AesGcmEnvelope.ivLength)
        // version + ivLen + iv + ciphertext + 16 byte tag
        #expect(sealed.count == 2 + 12 + plaintext.count + 16)
    }

    @Test func ivIsFreshPerEncryption() throws {
        let a = try AesGcmEnvelope.decode(AesGcmEnvelope.seal(key, plaintext))
        let b = try AesGcmEnvelope.decode(AesGcmEnvelope.seal(key, plaintext))
        #expect(a.iv != b.iv)
        #expect(a.ciphertext != b.ciphertext)
    }

    @Test func encodeDecodeAreInverse() throws {
        let parts = AesGcmEnvelope.Parts(iv: Data((0 ..< 12).map { UInt8($0) }), ciphertext: Data((0 ..< 20).map { UInt8($0 * 3) }))
        #expect(try AesGcmEnvelope.decode(AesGcmEnvelope.encode(parts)) == parts)
    }

    @Test func tamperedCiphertextIsRejected() throws {
        var sealed = try AesGcmEnvelope.seal(key, plaintext)
        sealed[sealed.count - 1] ^= 0x01
        #expect(throws: (any Error).self) { try AesGcmEnvelope.open(key, sealed) }
    }

    @Test func tamperedIvIsRejected() throws {
        var sealed = try AesGcmEnvelope.seal(key, plaintext)
        sealed[3] ^= 0x01
        #expect(throws: (any Error).self) { try AesGcmEnvelope.open(key, sealed) }
    }

    @Test func wrongAadIsRejected() throws {
        let sealed = try AesGcmEnvelope.seal(key, plaintext, aad: Data("ref-1".utf8))
        #expect(throws: (any Error).self) { try AesGcmEnvelope.open(key, sealed, aad: Data("ref-2".utf8)) }
    }

    @Test func wrongKeyIsRejected() throws {
        let sealed = try AesGcmEnvelope.seal(key, plaintext)
        #expect(throws: (any Error).self) { try AesGcmEnvelope.open(SymmetricKey(size: .bits256), sealed) }
    }

    @Test func unknownVersionIsRejected() throws {
        var sealed = try AesGcmEnvelope.seal(key, plaintext)
        sealed[0] = 9
        #expect(throws: EnvelopeError.self) { try AesGcmEnvelope.decode(sealed) }
    }

    @Test func truncatedEnvelopeIsRejected() throws {
        let sealed = try AesGcmEnvelope.seal(key, plaintext)
        #expect(throws: EnvelopeError.self) { try AesGcmEnvelope.decode(sealed.prefix(10)) }
        #expect(throws: EnvelopeError.self) { try AesGcmEnvelope.decode(Data([1])) }
    }

    /// An envelope sealed on Android (JCE AES/GCM/NoPadding) opens here and vice versa: the IV is
    /// the 12-byte nonce and the tag trails the ciphertext in both.
    @Test func layoutMatchesJceGcm() throws {
        let sealed = try AesGcmEnvelope.seal(key, plaintext, aad: Data("x".utf8))
        let parts = try AesGcmEnvelope.decode(sealed)
        let box = try AES.GCM.SealedBox(combined: parts.iv + parts.ciphertext)
        #expect(try AES.GCM.open(box, using: key, authenticating: Data("x".utf8)) == plaintext)
    }
}

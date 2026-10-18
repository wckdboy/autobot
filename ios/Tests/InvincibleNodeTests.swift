import AutobotShared
import Foundation
import Testing
@testable import Autobot

private final class TestBundle {}

/// Autobot on iOS against an Invincible node's conformance vectors (`shared/src/jvmTest/resources/
/// invincible`, the same files the JVM tests run): the Kotlin Noise and receipt code, on CryptoKit.
@Suite
struct InvincibleNodeTests {
    private func vectors(_ name: String) throws -> String {
        let url = try #require(Bundle(for: TestBundle.self).url(forResource: name, withExtension: "json", subdirectory: "invincible"))
        return try String(contentsOf: url, encoding: .utf8)
    }

    @Test func noiseTranscriptsMatchTheNodeOnCryptoKit() throws {
        let failures = NodeConformance.shared.noise(crypto: NodeCryptoKt.nodeCrypto(host: SwiftNodeCrypto()), vectors: try vectors("noise"))
        #expect(failures.isEmpty, "\(failures.joined(separator: "\n"))")
    }

    @Test func receiptsVerifyOnCryptoKit() throws {
        let failures = NodeConformance.shared.receipts(crypto: NodeCryptoKt.nodeCrypto(host: SwiftNodeCrypto()), vectors: try vectors("receipts"))
        #expect(failures.isEmpty, "\(failures.joined(separator: "\n"))")
    }
}

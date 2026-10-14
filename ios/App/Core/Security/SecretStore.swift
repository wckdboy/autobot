import Foundation

/**
 A secret value (e.g. an API key) that refuses to print itself.

 `description` is always redacted so the value cannot leak through string interpolation, logs,
 crash messages or the description of containing values. Use [reveal] only where the raw value
 is actually required (e.g. building an `Authorization` header).
 */
struct Secret: Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    static let redacted = "Secret(██████)"

    private let value: String

    init(_ value: String) { self.value = value }

    /// Returns the raw secret. Never log the result.
    func reveal() -> String { value }

    var isBlank: Bool { value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var description: String { Self.redacted }
    var debugDescription: String { Self.redacted }
    var customMirror: Mirror { Mirror(self, children: [], displayStyle: .struct) }
}

/**
 Credentials (API keys, hub tokens) in the Keychain, one item per reference, under a dedicated
 service. Items are `WhenUnlockedThisDeviceOnly`. There is no listing API that would return
 secrets in bulk.
 */
final class SecretStore: Sendable {
    static let service = "dev.wckdboy.autobot.secrets"
    private static let refPattern = try! NSRegularExpression(pattern: "^[A-Za-z0-9_-]{1,64}$")

    init() {}

    /// Generates a new opaque reference for a secret.
    func newRef() -> String { UUID().uuidString.lowercased() }

    func put(_ ref: String, _ secret: Secret) throws {
        try Keychain.write(Data(secret.reveal().utf8), try checked(ref), service: Self.service)
    }

    /// The secret stored under [ref], or nil if absent or unreadable.
    func get(_ ref: String) -> Secret? {
        guard let ref = try? checked(ref), let data = try? Keychain.read(ref, service: Self.service) else { return nil }
        return String(data: data, encoding: .utf8).map(Secret.init)
    }

    func contains(_ ref: String) -> Bool { get(ref) != nil }

    func delete(_ ref: String) {
        guard let ref = try? checked(ref) else { return }
        Keychain.delete(ref, service: Self.service)
    }

    /// Deletes every stored secret.
    func deleteAll() { Keychain.deleteAll(service: Self.service) }

    private func checked(_ ref: String) throws -> String {
        guard Self.refPattern.firstMatch(in: ref, range: NSRange(ref.startIndex..., in: ref)) != nil else {
            throw KeychainError(status: errSecParam)
        }
        return ref
    }
}

extension SecretStore: CustomStringConvertible {
    var description: String { "SecretStore(<redacted>)" }
}

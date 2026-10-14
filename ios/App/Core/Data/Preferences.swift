import Foundation
import Synchronization

/**
 Non-secret preferences (the iOS counterpart of the settings DataStore): a small JSON file under
 the protected, backup-excluded app directory. Values are Codable, stored per key. Secrets never
 go here; they live in the Keychain ([SecretStore]). Unreadable files fall back to defaults.
 */
final class Preferences: Sendable {
    private let url: URL
    private let values: Mutex<[String: String]>

    init(url: URL = AppPaths.preferences) {
        self.url = url
        let loaded = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode([String: String].self, from: $0) } ?? [:]
        values = Mutex(loaded)
    }

    func get<T: Decodable>(_ key: String, as type: T.Type = T.self) -> T? {
        guard let text = values.withLock({ $0[key] }) else { return nil }
        return try? JSONDecoder().decode(T.self, from: Data(text.utf8))
    }

    func set<T: Encodable>(_ key: String, _ value: T?) {
        let snapshot = values.withLock { v -> [String: String] in
            if let value, let data = try? JSONEncoder.store.encode(value), let text = String(data: data, encoding: .utf8) {
                v[key] = text
            } else {
                v.removeValue(forKey: key)
            }
            return v
        }
        if let data = try? JSONEncoder.store.encode(snapshot) {
            try? AppPaths.write(data, to: url, protection: .completeFileProtectionUntilFirstUserAuthentication)
        }
    }

    func remove(_ key: String) { set(key, String?.none) }
}

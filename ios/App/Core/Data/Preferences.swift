import Foundation
import Synchronization

/**
 Non-secret preferences (the iOS counterpart of the settings DataStore): a small JSON file under
 the protected, backup-excluded app directory. Values are Codable, stored per key. Secrets never
 go here; they live in the Keychain ([SecretStore]). A missing or damaged file falls back to
 defaults. A file that exists but cannot be read yet (launched before the first unlock) is never
 overwritten: changes stay in memory until [reloadIfNeeded] reads it and merges them in.
 */
final class Preferences: Sendable {
    private struct State {
        var values: [String: String]
        /// The file was read (or there is none): writing it keeps everything.
        var loaded: Bool
        /// Keys changed while not [loaded] (nil value: removed), applied over the file once read.
        var changes: [String: String?] = [:]
    }

    private let url: URL
    private let state: Mutex<State>

    init(url: URL = AppPaths.preferences) {
        self.url = url
        let read = Self.read(url)
        state = Mutex(State(values: read ?? [:], loaded: read != nil))
    }

    /// Whether the file has been read (false while the device has not been unlocked since boot).
    var isLoaded: Bool { state.withLock { $0.loaded } }

    func get<T: Decodable>(_ key: String, as type: T.Type = T.self) -> T? {
        guard let text = state.withLock({ $0.values[key] }) else { return nil }
        return try? JSONDecoder().decode(T.self, from: Data(text.utf8))
    }

    func set<T: Encodable>(_ key: String, _ value: T?) {
        let text = value.flatMap { try? JSONEncoder.store.encode($0) }.flatMap { String(data: $0, encoding: .utf8) }
        state.withLock { s in
            s.values[key] = text
            if !s.loaded { s.changes[key] = .some(text) }
        }
        reloadIfNeeded()
        save()
    }

    func remove(_ key: String) { set(key, String?.none) }

    /// Reads the file if it could not be read at launch, keeping changes made since. Call when
    /// protected data becomes available.
    func reloadIfNeeded() {
        guard !isLoaded, let read = Self.read(url) else { return }
        state.withLock { s in
            guard !s.loaded else { return }
            var merged = read
            for (key, value) in s.changes { merged[key] = value }
            s.values = merged
            s.changes = [:]
            s.loaded = true
        }
        save()
    }

    private func save() {
        guard let snapshot = state.withLock({ $0.loaded ? $0.values : nil }), let data = try? JSONEncoder.store.encode(snapshot) else { return }
        try? AppPaths.write(data, to: url, protection: .completeFileProtectionUntilFirstUserAuthentication)
    }

    /// The stored values: empty when there is no file or it is damaged, nil when it exists but cannot be read now.
    private static func read(_ url: URL) -> [String: String]? {
        guard FileManager.default.fileExists(atPath: url.path) else { return [:] }
        guard let data = try? Data(contentsOf: url) else { return nil }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
    }
}

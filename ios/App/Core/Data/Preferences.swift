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
    /// With [background], writes run here, coalesced: a burst of changes (a slider, a settings
    /// screen) is one file write, and the caller (usually the main thread) never waits for it.
    private let writer: DispatchQueue?
    private let saveScheduled = Atomic<Bool>(false)
    /// Set by [close] (panic wipe): nothing is written any more.
    private let closed = Atomic<Bool>(false)

    /// [background]: write off the caller's thread, coalesced (the app); off: write at once (tests).
    init(url: URL = AppPaths.preferences, background: Bool = false) {
        self.url = url
        writer = background ? DispatchQueue(label: "autobot.preferences", qos: .utility) : nil
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
        guard let writer else { return write() }
        guard saveScheduled.compareExchange(expected: false, desired: true, ordering: .acquiringAndReleasing).exchanged else { return }
        writer.asyncAfter(deadline: .now() + Self.coalesce) { [self] in
            // Cleared before the snapshot: a change after it schedules another write.
            saveScheduled.store(false, ordering: .releasing)
            write()
        }
    }

    /// Changes within this window share one write.
    private static let coalesce: DispatchTimeInterval = .milliseconds(200)

    private func write() {
        guard !closed.load(ordering: .acquiring),
              let snapshot = state.withLock({ $0.loaded ? $0.values : nil }), let data = try? JSONEncoder.store.encode(snapshot) else { return }
        try? AppPaths.write(data, to: url, protection: .completeFileProtectionUntilFirstUserAuthentication)
    }

    /// Writes pending changes now (backgrounding, tests).
    func flush() {
        guard let writer else { return }
        writer.sync { write() }
    }

    /// Stops writing (panic wipe: a late write must not recreate the deleted file).
    func close() { closed.store(true, ordering: .releasing) }

    /// The stored values: empty when there is no file or it is damaged, nil when it exists but cannot be read now.
    private static func read(_ url: URL) -> [String: String]? {
        guard FileManager.default.fileExists(atPath: url.path) else { return [:] }
        guard let data = try? Data(contentsOf: url) else { return nil }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
    }
}

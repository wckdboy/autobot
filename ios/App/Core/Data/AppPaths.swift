import Foundation

/// Milliseconds since the epoch (as Android's `System.currentTimeMillis()`).
func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

/**
 Where Autobot keeps its files. Everything lives under `Application Support/Autobot`, which is
 excluded from iCloud / iTunes backup and protected with `NSFileProtectionComplete` (unreadable
 while the device is locked). Model files use `completeUntilFirstUserAuthentication` so a
 background download can finish while the phone is locked.
 */
enum AppPaths {
    static let root: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("Autobot", isDirectory: true)
    }()

    static var database: URL { root.appendingPathComponent("autobot.sqlite") }
    static var preferences: URL { root.appendingPathComponent("preferences.json") }
    static var gallery: URL { directory("gallery") }
    static var attachments: URL { directory("attachments") }
    static var workspace: URL { directory("workspace") }
    static var models: URL { directory("models", protection: .completeUntilFirstUserAuthentication) }

    /// Short-lived plaintext copies handed to engines; cleared at start-up and after use.
    static var plainCache: URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("autobot-plain", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        return url
    }

    /// Creates the root, marks it excluded from backup and protects it. Call once at launch.
    static func prepare() {
        let fm = FileManager.default
        try? fm.createDirectory(at: root, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        var url = root
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        try? fm.removeItem(at: plainCache)
    }

    static func directory(_ name: String, protection: FileProtectionType = .complete) -> URL {
        let url = root.appendingPathComponent(name, isDirectory: true)
        if !FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true, attributes: [.protectionKey: protection])
        }
        return url
    }

    /// Writes [data] atomically with complete file protection.
    static func write(_ data: Data, to url: URL, protection: Data.WritingOptions = .completeFileProtection) throws {
        try data.write(to: url, options: [.atomic, protection])
    }
}

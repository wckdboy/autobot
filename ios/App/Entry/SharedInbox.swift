import Foundation

/**
 Hand-off from the share extension to the app through the App Group container. An item is a
 folder of plain files written with complete file protection (unreadable while the phone is
 locked), picked up once the app is unlocked and deleted on pickup; anything left behind (the app
 was never opened, or never unlocked) is swept after [maxAge]. Images are already downscaled and
 stripped of metadata by the extension. Compiled into the app and the share extension.
 */
enum SharedInbox {
    static let appGroup = "group.ai.wckd.autobot"
    /// Items older than this are deleted unread.
    static let maxAge: TimeInterval = 60 * 60
    static let maxImages = 4
    static let maxText = 20_000

    struct Item: Equatable {
        var text: String?
        var images: [Data] = []
    }

    /// The inbox folder in the App Group container (nil without the App Group entitlement).
    static var defaultRoot: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?.appendingPathComponent("Inbox", isDirectory: true)
    }

    /// Writes [item] and returns its id (a UUID, the only thing that goes into the app's URL).
    static func write(_ item: Item, root: URL) throws -> String {
        let id = UUID().uuidString
        let folder = root.appendingPathComponent(id, isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
        if let text = item.text.map({ String($0.prefix(maxText)) }), !text.isEmpty {
            try Data(text.utf8).write(to: folder.appendingPathComponent("text.txt"), options: [.completeFileProtection, .atomic])
        }
        for (i, image) in item.images.prefix(maxImages).enumerated() {
            try image.write(to: folder.appendingPathComponent("image-\(i).jpg"), options: [.completeFileProtection, .atomic])
        }
        return id
    }

    /// Reads and deletes item [id]; nil for a malformed id or a missing or expired item.
    static func take(_ id: String, root: URL, now: Date = Date()) -> Item? {
        // Only a UUID: never a path from a URL another app could have crafted.
        guard let uuid = UUID(uuidString: id) else { return nil }
        let folder = root.appendingPathComponent(uuid.uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        guard let created = (try? FileManager.default.attributesOfItem(atPath: folder.path))?[.creationDate] as? Date,
              now.timeIntervalSince(created) < maxAge else { return nil }
        let text = (try? Data(contentsOf: folder.appendingPathComponent("text.txt"))).flatMap { String(data: $0, encoding: .utf8) }
        let images = (0..<maxImages).compactMap { try? Data(contentsOf: folder.appendingPathComponent("image-\($0).jpg")) }
        let item = Item(text: text, images: images)
        return item.text == nil && item.images.isEmpty ? nil : item
    }

    /// Deletes items older than [maxAge].
    static func sweep(root: URL, now: Date = Date()) {
        let fm = FileManager.default
        guard let folders = try? fm.contentsOfDirectory(at: root, includingPropertiesForKeys: [.creationDateKey]) else { return }
        for folder in folders {
            let created = (try? folder.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? .distantPast
            if now.timeIntervalSince(created) >= maxAge { try? fm.removeItem(at: folder) }
        }
    }
}

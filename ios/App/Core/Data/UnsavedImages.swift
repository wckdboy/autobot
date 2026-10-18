import AutobotShared
import Foundation
import Observation

/**
 Images from incognito runs (an incognito chat's `generate_image`, the Imagine screen with
 Incognito on), port of Android's `UnsavedImages.kt`: process memory only, under
 `IncognitoImages.ID_PREFIX` ids that [GalleryRepository] resolves here. Nothing reaches disk, the
 gallery or the Studio until the user taps Save ([GalleryRepository.keep]). Entries go with their
 incognito chat, when the Imagine screen's incognito results are replaced, with a panic wipe and
 with the process. At most [max] are held, oldest dropped first.
 */
@MainActor
@Observable
final class UnsavedImages {
    /// The Imagine screen's owner tag.
    nonisolated static let imageScreen = "image-screen"
    /// Memory bound: a handful of batches.
    nonisolated static let max = 24

    struct Entry {
        let item: GalleryItem
        let image: Data
        let thumbnail: Data
        /// The incognito chat (session id) or [imageScreen].
        let owner: String
    }

    @ObservationIgnored private var entries: [String: Entry] = [:]
    @ObservationIgnored private var order: [String] = []
    /// Unsaved ids the user saved, mapped to their new gallery ids.
    private(set) var saved: [String: String] = [:]

    nonisolated static func isUnsaved(_ id: String) -> Bool { IncognitoImages.shared.isUnsaved(id: id) }

    func newId() -> String { IncognitoImages.shared.ID_PREFIX + UUID().uuidString.lowercased() }

    func put(_ item: GalleryItem, image: Data, thumbnail: Data, owner: String) {
        precondition(Self.isUnsaved(item.id), "Unsaved images need an incognito id")
        if entries[item.id] == nil { order.append(item.id) }
        entries[item.id] = Entry(item: item, image: image, thumbnail: thumbnail, owner: owner)
        while order.count > Self.max { remove(order[0]) }
    }

    func get(_ id: String) -> Entry? { entries[id] }

    var ids: [String] { order }

    func markSaved(_ id: String, galleryId: String) { saved[id] = galleryId }

    /// Drops every image of [owner] (and of its subagents, whose session ids start with it).
    func drop(owner: String) {
        for id in order where entries[id]?.owner.hasPrefix(owner) == true { remove(id) }
    }

    func clear() {
        entries.removeAll()
        order.removeAll()
        saved = [:]
    }

    private func remove(_ id: String) {
        entries.removeValue(forKey: id)
        order.removeAll { $0 == id }
    }
}

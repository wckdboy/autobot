import Foundation
import UIKit

/**
 Decoded Studio thumbnails in memory, by asset id (the Library grid recycles cells while
 scrolling; without this every reappearing cell decrypted and decoded its thumbnail again).
 Bounded by decoded bytes, evicted under memory pressure, cleared by a delete and a panic wipe:
 nothing decrypted outlives the asset.
 */
@MainActor
enum StudioThumbs {
    private static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.totalCostLimit = 32 * 1024 * 1024
        return c
    }()

    static func get(_ id: String) -> UIImage? { cache.object(forKey: id as NSString) }

    static func put(_ id: String, _ image: UIImage) {
        let pixels = image.size.width * image.scale * image.size.height * image.scale
        cache.setObject(image, forKey: id as NSString, cost: Int(pixels) * 4)
    }

    static func remove(_ id: String) { cache.removeObject(forKey: id as NSString) }

    static func removeAll() { cache.removeAllObjects() }
}

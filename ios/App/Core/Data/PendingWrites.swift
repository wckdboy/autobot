import Foundation
import Synchronization

/**
 Writes that could not reach the [EncryptedStore] yet, kept in process memory in order. The
 database is `NSFileProtectionComplete`: while the device is locked a write fails, so it waits
 here and is retried before any newer one (and when protected data becomes available again), so
 nothing written while locked is lost and nothing lands out of order. Writes must be idempotent
 (a retry may repeat one that half-succeeded).
 */
final class PendingWrites<Item: Sendable>: Sendable {
    private let queue = Mutex<[Item]>([])
    private let write: @Sendable (Item) throws -> Void
    private let limit: Int

    /// [limit]: items kept at most; beyond it the oldest is dropped (and reported).
    init(limit: Int = 4096, write: @escaping @Sendable (Item) throws -> Void) {
        self.limit = limit
        self.write = write
    }

    /// Writes [item] after everything still waiting. Returns the error that kept it waiting.
    @discardableResult
    func submit(_ item: Item) -> Error? {
        queue.withLock { q in
            q.append(item)
            if q.count > limit {
                q.removeFirst(q.count - limit)
                EngineErrors.record("Storage: unsaved writes over \(limit); the oldest was dropped")
            }
            return drain(&q)
        }
    }

    /// Retries the waiting items in order; returns the error that stopped it (nil: all written).
    @discardableResult
    func flush() -> Error? { queue.withLock { drain(&$0) } }

    /// Items still waiting, oldest first.
    var pending: [Item] { queue.withLock { $0 } }

    /// Forgets waiting items (their target was deleted).
    func discard(where match: (Item) -> Bool) { queue.withLock { $0.removeAll(where: match) } }

    private func drain(_ q: inout [Item]) -> Error? {
        while let first = q.first {
            do { try write(first) } catch { return error }
            q.removeFirst()
        }
        return nil
    }
}

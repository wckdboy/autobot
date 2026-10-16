import Foundation
import Synchronization

/**
 Writes that could not reach the [EncryptedStore] yet, kept in process memory in order. The
 database is `NSFileProtectionComplete`: while the device is locked a write fails, so it waits
 here and is retried before any newer one (and when protected data becomes available again), so
 nothing written while locked is lost and nothing lands out of order. Writes must be idempotent
 (a retry may repeat one that half-succeeded).

 With a [queue], [submit] returns at once and the writes run there, in submission order (the
 main thread never waits for SQLite, the cipher or a writer on another thread); without one they
 run on the caller's thread.
 */
final class PendingWrites<Item: Sendable>: Sendable {
    private let items = Mutex<[Item]>([])
    private let write: @Sendable (Item) throws -> Void
    private let limit: Int
    private let queue: DispatchQueue?

    /// [limit]: items kept at most; beyond it the oldest is dropped (and reported). [queue]: a
    /// serial queue to write on (nil: the caller's thread).
    init(limit: Int = 4096, queue: DispatchQueue? = nil, write: @escaping @Sendable (Item) throws -> Void) {
        self.limit = limit
        self.queue = queue
        self.write = write
    }

    /// Writes [item] after everything still waiting. Returns the error that kept it waiting
    /// (always nil with a [queue]: the write happens later).
    @discardableResult
    func submit(_ item: Item) -> Error? {
        items.withLock { q in
            q.append(item)
            if q.count > limit {
                q.removeFirst(q.count - limit)
                EngineErrors.record("Storage: unsaved writes over \(limit); the oldest was dropped")
            }
        }
        guard let queue else { return flush() }
        queue.async { self.flush() }
        return nil
    }

    /// Retries the waiting items in order; returns the error that stopped it (nil: all written).
    /// Runs on the caller's thread (with a [queue], call it there or after [waitUntilWritten]).
    @discardableResult
    func flush() -> Error? { items.withLock { drain(&$0) } }

    /// Retries the waiting items on the queue (or now, without one).
    func retry() {
        guard let queue else { return _ = flush() }
        queue.async { self.flush() }
    }

    /// Blocks until the writes submitted so far were attempted (tests, before a reload or a close).
    func waitUntilWritten() { queue?.sync {} }

    /// Items still waiting, oldest first.
    var pending: [Item] { items.withLock { $0 } }

    /// Forgets waiting items (their target was deleted). A write in progress finishes first.
    func discard(where match: (Item) -> Bool) { items.withLock { $0.removeAll(where: match) } }

    /// Writes items while the lock is held: [pending] and [discard] wait for the one in flight,
    /// so a reader never sees an item both written and waiting.
    private func drain(_ q: inout [Item]) -> Error? {
        while let first = q.first {
            do { try write(first) } catch { return error }
            q.removeFirst()
        }
        return nil
    }
}

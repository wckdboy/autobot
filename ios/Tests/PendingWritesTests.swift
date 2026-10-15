import Foundation
import Synchronization
import Testing
@testable import Autobot

/// Writes made while the device is locked wait in memory, in order, and land before newer ones.
struct PendingWritesTests {
    private final class Disk: Sendable {
        let locked = Atomic<Bool>(true)
        let written = Mutex<[Int]>([])

        func write(_ n: Int) throws {
            if locked.load(ordering: .sequentiallyConsistent) { throw StoreError(message: "locked") }
            written.withLock { $0.append(n) }
        }
    }

    @Test func lockedWritesWaitAndLandInOrderBeforeNewerOnes() {
        let disk = Disk()
        let writes = PendingWrites<Int> { try disk.write($0) }
        #expect(writes.submit(1) != nil)
        #expect(writes.submit(2) != nil)
        #expect(writes.pending == [1, 2])
        #expect(disk.written.withLock { $0 }.isEmpty)

        disk.locked.store(false, ordering: .sequentiallyConsistent)
        #expect(writes.submit(3) == nil)
        #expect(disk.written.withLock { $0 } == [1, 2, 3])
        #expect(writes.pending.isEmpty)
    }

    @Test func flushRetriesInOrderOnceUnlocked() {
        let disk = Disk()
        let writes = PendingWrites<Int> { try disk.write($0) }
        (1 ... 4).forEach { writes.submit($0) }
        #expect(writes.flush() != nil)
        disk.locked.store(false, ordering: .sequentiallyConsistent)
        #expect(writes.flush() == nil)
        #expect(disk.written.withLock { $0 } == [1, 2, 3, 4])
    }

    @Test func discardAndLimit() {
        let disk = Disk()
        let writes = PendingWrites<Int>(limit: 3) { try disk.write($0) }
        (1 ... 5).forEach { writes.submit($0) }
        #expect(writes.pending == [3, 4, 5])
        writes.discard { $0 == 4 }
        #expect(writes.pending == [3, 5])
    }

    /// A store that cannot open (as while locked) keeps the session log in memory; [load] still
    /// sees it, and it is written in order once the store opens.
    @Test func sessionAppendsSurviveALockedStore() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pending-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        let host = SessionStoreHost(store: SessionStore(store: store))

        host.append(sessionId: "s", entries: [#"{"seq":1,"t":"a"}"#, #"{"seq":2,"t":"b"}"#])
        host.append(sessionId: "s", entries: [#"{"seq":3,"t":"c"}"#])
        #expect(host.unsaved.pending.count == 2)
        #expect(host.load(sessionId: "s").count == 3)

        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        host.retry()
        #expect(host.unsaved.pending.isEmpty)
        let lines = host.load(sessionId: "s")
        #expect(lines == [#"{"seq":1,"t":"a"}"#, #"{"seq":2,"t":"b"}"#, #"{"seq":3,"t":"c"}"#])

        // A retried batch that already landed repeats nothing.
        try SessionStore(store: store).appendJSON("s", [(seq: 3, json: Data(#"{"seq":3,"t":"c"}"#.utf8))])
        #expect(host.load(sessionId: "s").count == 3)
    }
}

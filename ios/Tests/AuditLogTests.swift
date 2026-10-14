import Foundation
import Testing
@testable import Autobot

/// Port of `AuditLogTest.kt`.
struct AuditLogTests {
    @Test func ringBufferKeepsNewestEntries() {
        let log = AuditLog(capacity: 3, clock: { 0 })
        for i in 0 ..< 5 { log.record(method: "GET", host: "h\(i)", path: "/", route: "direct", blocked: false) }
        #expect(log.entries.map(\.host) == ["h2", "h3", "h4"])
    }

    @Test func stripsQueryAndFragmentFromPath() {
        let log = AuditLog(capacity: 3, clock: { 0 })
        let entry = log.record(method: "GET", host: "h", path: "/a/b?x=1#y", route: "direct", blocked: false)
        #expect(entry.path == "/a/b")
    }

    @Test func clearEmptiesBuffer() {
        let log = AuditLog(capacity: 3, clock: { 0 })
        log.record(method: "GET", host: "h", path: "/", route: "direct", blocked: true)
        log.clear()
        #expect(log.entries.isEmpty)
    }

    @Test func updatesEmitSnapshotThenChanges() async {
        let log = AuditLog(capacity: 3, clock: { 0 })
        var iterator = log.updates().makeAsyncIterator()
        #expect(await iterator.next()?.isEmpty == true)
        log.record(method: "POST", host: "h", path: "/", route: "direct", blocked: false)
        #expect(await iterator.next()?.count == 1)
    }
}

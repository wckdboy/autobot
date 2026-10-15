package dev.wckdboy.autobot.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S11: a turn's audit entries survive the shared ring until its receipt took them. */
class AuditRetentionTest {

    @Test
    fun turnEntriesOutliveTheRingUntilReleased() {
        val log = AuditLog(capacity = 10, clock = { 0L })
        val mine = (1..5).map { log.record("POST", "api.example.com", "/v1/chat/$it", route = "direct", blocked = false, session = "s1", turn = 3) }
        // Other traffic meanwhile pushes them out of the ring.
        repeat(50) { log.record("GET", "other$it.example.com", "/x", route = "direct", blocked = false) }
        assertTrue(log.entries.value.none { it.session == "s1" })
        log.received(mine[2].id, 4_096)

        val kept = log.forTurn("s1", 3)
        assertEquals(mine.map { it.id }, kept.map { it.id })
        assertEquals(4_096L, kept[2].bytesReceived)

        // Once the receipt is written they are only in the ring (here: gone).
        log.release("s1", 3)
        assertTrue(log.forTurn("s1", 3).isEmpty())
    }

    @Test
    fun repeatedPollsCollapseIntoOneEntryAndReceiptsStillCountThem() {
        var now = 0L
        val log = AuditLog(capacity = 5, clock = { now })
        val chat = log.record("POST", "api.example.com", "/v1/chat", bytesSent = 900, route = "direct", blocked = false, session = "s", turn = 1)
        val polls = (1..40).map {
            now += 500
            log.record("GET", "127.0.0.1", "/sdapi/v1/progress", bytesReceived = 100, status = 200, route = "loopback", blocked = false, session = "s", turn = 1)
                .also { e -> log.received(e.id, 120) }
        }
        // One entry for forty polls: the chat request is still in a ring of five.
        assertEquals(listOf(chat.id, polls.first().id), log.entries.value.map { it.id })
        val poll = log.entries.value.last()
        assertEquals(40, poll.count)
        assertEquals(40 * 120L, poll.bytesReceived)
        assertEquals(now, poll.timeMillis)

        val hosts = dev.wckdboy.autobot.shared.session.Receipts.network(log.forTurn("s", 1))
        assertEquals(listOf(1, 40), hosts.map { it.requests })
        assertEquals(40 * 120L, hosts.last().bytesReceived)

        // Different turns, errors or far-apart untraced requests stay separate.
        log.record("GET", "127.0.0.1", "/sdapi/v1/progress", route = "loopback", blocked = false, session = "s", turn = 2)
        log.record("GET", "127.0.0.1", "/sdapi/v1/progress", route = "loopback", blocked = false, session = "s", turn = 2, error = "reset")
        log.record("GET", "h", "/", route = "direct", blocked = false)
        now += AuditLog.COLLAPSE_MS + 1
        log.record("GET", "h", "/", route = "direct", blocked = false)
        assertEquals(listOf(1, 1, 1, 1), log.entries.value.takeLast(4).map { it.count })
    }

    @Test
    fun openTurnsAreBounded() {
        val log = AuditLog(capacity = 5, clock = { 0L })
        repeat(AuditLog.MAX_OPEN_TURNS + 10) { t -> log.record("POST", "h", "/", route = "direct", blocked = false, session = "s", turn = t) }
        // The oldest open turns were dropped (and are out of the ring too).
        assertTrue(log.forTurn("s", 0).isEmpty())
        assertEquals(1, log.forTurn("s", AuditLog.MAX_OPEN_TURNS + 9).size)
        log.clear()
        assertTrue(log.forTurn("s", AuditLog.MAX_OPEN_TURNS + 9).isEmpty())
    }
}

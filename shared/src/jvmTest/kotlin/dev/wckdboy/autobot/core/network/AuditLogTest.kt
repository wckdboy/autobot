package dev.wckdboy.autobot.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class AuditLogTest {

    @Test
    fun ringBufferKeepsNewestEntries() {
        val log = AuditLog(capacity = 3, clock = { 0L })
        repeat(5) { i -> log.record("GET", "h$i", "/", route = "direct", blocked = false) }
        assertEquals(listOf("h2", "h3", "h4"), log.entries.value.map { it.host })
    }

    @Test
    fun stripsQueryAndFragmentFromPath() {
        val log = AuditLog(capacity = 3, clock = { 0L })
        val entry = log.record("GET", "h", "/a/b?x=1#y", route = "direct", blocked = false)
        assertEquals("/a/b", entry.path)
    }

    @Test
    fun clearEmptiesBuffer() {
        val log = AuditLog(capacity = 3, clock = { 0L })
        log.record("GET", "h", "/", route = "direct", blocked = true)
        log.clear()
        assertEquals(0, log.entries.value.size)
    }
}

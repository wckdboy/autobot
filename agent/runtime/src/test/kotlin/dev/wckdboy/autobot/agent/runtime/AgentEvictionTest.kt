package dev.wckdboy.autobot.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentEvictionTest {
    @Test
    fun keepsUpToMaxOpen() {
        assertEquals(emptyList<String>(), AgentEviction.evictable(listOf("a", "b", "c"), emptySet(), max = 4))
        assertEquals(listOf("a", "b"), AgentEviction.evictable(listOf("a", "b", "c", "d", "e", "f"), emptySet(), max = 4))
    }

    @Test
    fun neverClosesABusyAgent() {
        // "a" runs, "b" is on screen: the next idle one goes instead.
        assertEquals(listOf("c"), AgentEviction.evictable(listOf("a", "b", "c", "d", "e"), setOf("a", "b"), max = 4))
        // Everything busy: over the limit rather than stopping work.
        assertEquals(emptyList<String>(), AgentEviction.evictable(listOf("a", "b", "c"), setOf("a", "b", "c"), max = 1))
    }
}

package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.providers.remote.RouteTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepointGateTest {
    private val a = RouteTarget("a", "m1")
    private val b = RouteTarget("b", "m2")

    @Test
    fun scansAtStartAndWhenAProviderGoes() {
        assertTrue(RepointGate.needsScan(null, listOf(a)))
        assertTrue(RepointGate.needsScan(listOf(a, b), listOf(b)))
        // The first provider arrives: chats without one get it.
        assertTrue(RepointGate.needsScan(emptyList(), listOf(a)))
    }

    @Test
    fun skipsChangesThatMoveNobody() {
        assertFalse(RepointGate.needsScan(listOf(a), listOf(RouteTarget("a", "m9"))))
        assertFalse(RepointGate.needsScan(listOf(a), listOf(a, b)))
        assertFalse(RepointGate.needsScan(listOf(a, b), listOf(b, a)))
        assertFalse(RepointGate.needsScan(listOf(a), emptyList()))
    }
}

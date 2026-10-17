package dev.wckdboy.autobot.shared.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInTest {
    /** Feeds [levels] 100 ms apart from [fromMs]; returns the time it fired, or null. */
    private fun feed(b: BargeIn, levels: List<Float>, fromMs: Long = 0): Long? {
        levels.forEachIndexed { i, level -> if (b.onLevel(level, fromMs + i * 100L)) return fromMs + i * 100L }
        return null
    }

    @Test
    fun ourOwnVoiceLeakingThroughDoesNotTrigger() {
        val b = BargeIn()
        b.start(0)
        // Echo after cancellation: up to 0.2, louder than the plain gate, for 20 s.
        val echo = List(200) { if (it % 3 == 0) 0.2f else 0.1f }
        assertEquals(null, feed(b, echo))
        assertEquals(BargeIn.State.ARMED, b.state)
    }

    @Test
    fun theUserTalkingOverTheAnswerTriggersOnce() {
        val b = BargeIn()
        b.start(0)
        assertEquals(null, feed(b, List(10) { 0.15f }))
        val at = feed(b, List(10) { 0.7f }, fromMs = 1_000)
        assertEquals(1_300L, at) // 300 ms of speech
        assertEquals(BargeIn.State.TRIGGERED, b.state)
        assertFalse(b.onLevel(0.9f, 2_100))
    }

    @Test
    fun aLoudStartOfPlaybackIsLearnedNotHeard() {
        val b = BargeIn()
        b.start(0)
        // The first half second is loud (the canceller converging): it only raises the gate.
        assertEquals(null, feed(b, List(5) { 0.4f }))
        assertTrue(b.gate >= 0.6f - 1e-6f)
        assertEquals(null, feed(b, List(10) { 0.35f }, fromMs = 500))
    }

    @Test
    fun aClickOrCoughIsNotABargeIn() {
        val b = BargeIn()
        b.start(0)
        feed(b, List(6) { 0.05f })
        assertEquals(null, feed(b, listOf(0.9f, 0.9f, 0.05f, 0.05f, 0.9f, 0.05f, 0.05f), fromMs = 600))
    }

    @Test
    fun theGateFollowsTheEchoDown() {
        val b = BargeIn()
        b.start(0)
        feed(b, List(6) { 0.2f })
        val high = b.gate
        feed(b, List(30) { 0.02f }, fromMs = 600)
        assertTrue("gate ${b.gate} < $high", b.gate < high)
        assertEquals(0.12f, b.gate, 0.001f)
    }

    @Test
    fun idleIgnoresTheMicrophoneUntilPlaybackStarts() {
        val b = BargeIn()
        assertEquals(null, feed(b, List(20) { 0.9f }))
        b.start(5_000)
        b.reset()
        assertEquals(BargeIn.State.IDLE, b.state)
        assertEquals(null, feed(b, List(20) { 0.9f }, fromMs = 6_000))
    }
}

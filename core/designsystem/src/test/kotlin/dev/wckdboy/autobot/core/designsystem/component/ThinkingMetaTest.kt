package dev.wckdboy.autobot.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingMetaTest {
    @Test
    fun countedTokensAreShownAsSuchAndEstimatesAreMarked() {
        assertEquals("412 tokens", thinkingMeta("x".repeat(100), 412, null))
        assertEquals("1 token", thinkingMeta("x", 1, null))
        assertEquals("≈25 tok", thinkingMeta("x".repeat(100), null, null))
    }

    @Test
    fun anEarlyStopSaysWhy() {
        assertEquals("140 tokens · stopped at budget", thinkingMeta("", 140, "budget"))
        assertEquals("830 tokens · stopped: repeating", thinkingMeta("", 830, "loop"))
    }
}

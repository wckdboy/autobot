package dev.wckdboy.autobot.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Huge single-line output must wrap and page, never be laid out as one unmeasurable line. */
class LongTextTest {
    @Test
    fun findsLinesTooLongToScrollSideways() {
        assertFalse(LongText.hasLongLine("short\nlines\n" + "x".repeat(LongText.MAX_SCROLL_LINE)))
        assertTrue(LongText.hasLongLine("ok\n" + "x".repeat(40_000)))
    }

    @Test
    fun clipsToAPage() {
        val huge = "y".repeat(1_000_000)
        assertEquals(LongText.PAGE, LongText.clip(huge, LongText.PAGE).length)
        assertEquals("abc", LongText.clip("abc", LongText.PAGE))
    }
}

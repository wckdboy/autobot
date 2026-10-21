package dev.wckdboy.autobot.core.a2ui

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class A2uiHelpersTest {
    @Test
    fun everyCatalogIconHasAVector() {
        assertEquals(59, A2uiIcons.names.size)
        A2uiIcons.names.forEach { assertNotNull(it, A2uiIcons.forName(it)) }
        assertNotNull(A2uiIcons.forName("account_circle"))
        assertNull(A2uiIcons.forName("rocket"))
        assertNotNull(A2uiIcons.fromSvgPath("M6,6h12v12H6z"))
        assertNull(A2uiIcons.fromSvgPath("M".repeat(9_000)))
    }

    @Test
    fun dateTimeValuesAreIso8601() {
        val both = A2uiDateTime.parse("2026-10-21T19:30:00")!!
        assertEquals(LocalDate.of(2026, 10, 21), both.date)
        assertEquals(LocalTime.of(19, 30), both.time)
        assertEquals(LocalDate.of(2026, 10, 21), A2uiDateTime.parse("2026-10-21")!!.date)
        assertNull(A2uiDateTime.parse("2026-10-21")!!.time)
        assertEquals(LocalTime.of(7, 5), A2uiDateTime.parse("07:05")!!.time)
        assertNotNull(A2uiDateTime.parse("2026-10-21T19:30:00Z")!!.date)
        assertNull(A2uiDateTime.parse(""))
        assertNull(A2uiDateTime.parse("tomorrow"))
        assertEquals("2026-10-21T19:30:00", A2uiDateTime.format(LocalDate.of(2026, 10, 21), LocalTime.of(19, 30, 12, 5)))
        assertEquals("2026-10-21", A2uiDateTime.format(LocalDate.of(2026, 10, 21), null))
        assertEquals("19:30", A2uiDateTime.format(null, LocalTime.of(19, 30)))
        val d = LocalDate.of(2026, 3, 29)
        assertEquals(d, A2uiDateTime.fromUtcMillis(A2uiDateTime.utcMillis(d)))
    }

    @Test
    fun markdownIsDetectedAndStrippedWhereItCannotRender() {
        assertTrue(looksLikeMarkdown("# Contact Us"))
        assertTrue(looksLikeMarkdown("Ready in **20** minutes"))
        assertFalse(looksLikeMarkdown("Send Message"))
        assertEquals("Contact Us", stripMarkdown("# Contact Us"))
        assertEquals("Ready in 20 minutes", stripMarkdown("Ready in *20* minutes"))
        assertEquals("Open the menu", stripMarkdown("Open the [menu](https://example.org)"))
        assertEquals("a * b", stripMarkdown("a * b"))
    }
}

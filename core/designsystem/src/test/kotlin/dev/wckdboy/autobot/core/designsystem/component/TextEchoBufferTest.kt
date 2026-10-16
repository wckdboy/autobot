package dev.wckdboy.autobot.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class TextEchoBufferTest {

    @Test
    fun lateEchoesDoNotOverwriteNewerEdits() {
        val b = TextEchoBuffer("")
        b.edit("h")
        b.edit("ht")
        b.edit("htt")
        b.sync("h") // the owner's flow is two edits behind
        assertEquals("htt", b.text)
        b.sync("ht")
        assertEquals("htt", b.text)
        b.sync("htt")
        assertEquals("htt", b.text)
    }

    @Test
    fun conflatedEchoesSkipIntermediateValues() {
        val b = TextEchoBuffer("")
        "demo".forEachIndexed { i, _ -> b.edit("demo".take(i + 1)) }
        b.sync("de")
        b.sync("demo")
        assertEquals("demo", b.text)
    }

    @Test
    fun ownerChangesWin() {
        val b = TextEchoBuffer("draft")
        b.edit("draft!")
        b.sync("") // cleared after send
        assertEquals("", b.text)
        b.sync("loaded")
        assertEquals("loaded", b.text)
    }

    @Test
    fun normalizedEchoIsAdopted() {
        val b = TextEchoBuffer("")
        b.edit("A")
        b.sync("a")
        assertEquals("a", b.text)
    }

    @Test
    fun repeatedValuesAfterBackspaceStayLocal() {
        val b = TextEchoBuffer("")
        b.edit("a")
        b.edit("ab")
        b.edit("a")
        b.sync("a")
        assertEquals("a", b.text)
        b.sync("ab") // stale
        assertEquals("a", b.text)
        b.sync("a")
        assertEquals("a", b.text)
    }
}

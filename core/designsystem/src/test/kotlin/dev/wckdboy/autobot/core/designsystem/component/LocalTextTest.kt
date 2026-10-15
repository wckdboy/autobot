package dev.wckdboy.autobot.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTextTest {
    @Test
    fun lateEchoesDoNotRewindTyping() {
        val pushed = mutableListOf<String>()
        val field = LocalText("") { pushed += it }
        listOf("c", "ca", "cat").forEach(field::edit)
        assertEquals(listOf("c", "ca", "cat"), pushed)
        // The owner's state catches up one value at a time, behind the keyboard.
        field.sync("c")
        field.sync("ca")
        assertEquals("cat", field.text)
        field.sync("cat")
        assertEquals("cat", field.text)
    }

    @Test
    fun ownerChangesAreTakenOver() {
        val field = LocalText("old") { }
        field.edit("old prompt")
        field.sync("reused prompt")
        assertEquals("reused prompt", field.text)
        // After a reset, typing what was typed before is still pushed.
        field.sync("")
        assertEquals("", field.text)
    }
}

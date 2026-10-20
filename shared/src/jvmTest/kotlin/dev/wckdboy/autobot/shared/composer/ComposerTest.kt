package dev.wckdboy.autobot.shared.composer

import dev.wckdboy.autobot.shared.composer.Composer.Action
import dev.wckdboy.autobot.shared.composer.Composer.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {
    @Test
    fun `idle sends, enabled only with something to send`() {
        assertEquals(Action.SEND, Composer.action("", 0, running = false))
        assertFalse(Composer.enabled(Action.SEND, "  ", 0))
        assertTrue(Composer.enabled(Action.SEND, "hi", 0))
        assertTrue(Composer.enabled(Action.SEND, "", 1))
        assertFalse(Composer.enabled(Action.SEND, "hi", 0, ready = false))
    }

    @Test
    fun `a running turn stops when empty and queues what is typed`() {
        assertEquals(Action.STOP, Composer.action(" ", 0, running = true))
        assertEquals(Action.QUEUE, Composer.action("next", 0, running = true))
        assertEquals(Action.QUEUE, Composer.action("", 2, running = true))
        assertTrue(Composer.enabled(Action.STOP, "", 0))
    }

    @Test
    fun `labels and placeholders are short`() {
        assertEquals(listOf("Send", "Queue", "Stop"), Action.entries.map(Composer::label))
        assertEquals("Message", Composer.placeholder(running = false))
        assertEquals("Queue a follow-up", Composer.placeholder(running = true))
        assertEquals("A task for your machine", Composer.placeholder(running = false, node = true))
        Action.entries.forEach { assertTrue(Composer.placeholder(it == Action.QUEUE).length <= 24) }
    }

    @Test
    fun `with the keyboard up only the field and what it answers stay`() {
        Part.entries.forEach { assertTrue(it.name, Composer.shows(it, keyboardUp = false)) }
        val up = Part.entries.filter { Composer.shows(it, keyboardUp = true) }
        assertEquals(listOf(Part.FIELD, Part.ATTACHMENTS, Part.ANSWERING), up)
    }

    @Test
    fun `the field grows to five lines`() {
        assertEquals(5, Composer.MAX_LINES)
    }
}

package dev.wckdboy.autobot.shared.studio

import org.junit.Test
import org.junit.Assert.assertTrue

class StudioRoomWordsTest {
    @Test
    fun anEmptyRoomSaysOneSentence() {
        StudioRoomWords.all.forEach { e ->
            assertTrue("one sentence: ${e.text}", e.text.endsWith(".") && ". " !in e.text)
            assertTrue("too long: ${e.text}", e.text.length <= 90)
            assertTrue(e.title, e.title.first().isUpperCase())
        }
    }
}

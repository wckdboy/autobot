package dev.wckdboy.autobot.shared.memory

import org.junit.Test
import org.junit.Assert.assertEquals

class MemoryWordsTest {
    @Test
    fun theEmptyListSaysOneThing() {
        assertEquals("No notes yet.", MemoryWords.empty(0))
        assertEquals("No notes match.", MemoryWords.empty(4))
        assertEquals("Notes · 4 / 500", MemoryWords.count(4))
    }
}

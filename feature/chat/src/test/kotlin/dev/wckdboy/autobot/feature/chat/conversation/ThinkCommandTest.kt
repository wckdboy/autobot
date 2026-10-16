package dev.wckdboy.autobot.feature.chat.conversation

import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `/think off | auto | on [budget]`. */
class ThinkCommandTest {
    @Test
    fun modesAndBudgets() {
        assertEquals(ThinkingSetting.OFF, parseThinking("off"))
        assertEquals(ThinkingSetting.AUTO, parseThinking(" Auto "))
        assertEquals(ThinkingSetting(ThinkingMode.ON), parseThinking("on"))
        assertEquals(ThinkingSetting(ThinkingMode.ON, 512), parseThinking("on 512"))
        assertEquals(ThinkingSetting(ThinkingMode.ON, 2048), parseThinking("on 2k"))
        assertEquals(ThinkingSetting(ThinkingMode.ON, 256), parseThinking("256"))
        assertNull(parseThinking("maybe"))
        assertNull(parseThinking(""))
    }
}

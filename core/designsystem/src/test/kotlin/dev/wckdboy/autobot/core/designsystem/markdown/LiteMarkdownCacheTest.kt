package dev.wckdboy.autobot.core.designsystem.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LiteMarkdownCacheTest {
    @Test
    fun theFinishedReplyContinuesFromItsLastStreamedFrame() {
        val intro = "First paragraph of a long answer ${System.nanoTime()}."
        val frame = "$intro\n\n```kotlin\nval x = 1\n```\n\nThe last para"
        val streamed = LiteMarkdown.cached(frame)
        val final = LiteMarkdown.cached("$frame graph is done.")
        // Finished blocks keep their identity: only the last one was parsed again.
        assertSame(streamed.blocks[0], final.blocks[0])
        assertSame(streamed.blocks[1], final.blocks[1])
        assertEquals(LiteMarkdown.parse("$frame graph is done."), final.blocks)
    }

    @Test
    fun aParsedTextIsNotParsedAgain() {
        val text = "History answer ${System.nanoTime()} with `code`."
        val warmed = LiteMarkdown.cached(text)
        assertSame(warmed, LiteMarkdown.cached(text))
        assertEquals(LiteMarkdown.parse(text), warmed.blocks)
    }
}

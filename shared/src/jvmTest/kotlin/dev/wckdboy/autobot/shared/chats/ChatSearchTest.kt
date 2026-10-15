package dev.wckdboy.autobot.shared.chats

import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchTest {
    @Test
    fun everyTermMustMatchTitleOrText() {
        assertNotNull(ChatSearch.match("trip budget", "Trip to Rome", "the budget is 900 euros"))
        assertNull(ChatSearch.match("trip budget", "Trip to Rome", "flights only"))
        assertNull(ChatSearch.match("   ", "anything", "anything"))
    }

    @Test
    fun titleOnlyMatchHasNoSnippet() {
        val m = ChatSearch.match("rome", "Trip to Rome", "flights")!!
        assertNull(m.snippet)
        assertEquals(listOf(HighlightSpan(8, 12)), m.titleSpans)
    }

    @Test
    fun snippetIsAWindowAroundTheFirstHitWithHighlights() {
        val text = "word ".repeat(40) + "The BUDGET is tight, budget again\n\n" + "tail ".repeat(60)
        val m = ChatSearch.match("budget", "Plans", text)!!
        val snippet = m.snippet!!
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(!snippet.contains("\n"))
        assertEquals(2, m.snippetSpans.size)
        m.snippetSpans.forEach { assertEquals("budget", snippet.substring(it.start, it.end).lowercase()) }
    }

    @Test
    fun overlappingSpansMerge() {
        assertEquals(listOf(HighlightSpan(0, 6)), ChatSearch.spans("abcdef", listOf("abcd", "cdef")))
    }

    @Test
    fun likePatternEscapes() {
        assertEquals("%50\\%\\_off\\\\%", ChatSearch.likePattern("50%_off\\"))
        assertEquals("budget", ChatSearch.prefilterTerm(ChatSearch.terms("a  Budget to")))
    }

    @Test
    fun indexesPromptsAndRepliesButNotContextOrReasoning() {
        val entries = listOf(
            LogEntry(1, 0, SessionEvent.UserMessage("hello there", images = listOf("img-1"))),
            LogEntry(2, 0, SessionEvent.UserMessage("<system-reminder>", kind = UserMessageKind.CONTEXT)),
            LogEntry(3, 0, SessionEvent.AssistantMessage(0, 0, "general kenobi", reasoning = "secret thoughts")),
        )
        val text = ChatSearch.append("", entries)
        assertEquals("hello there\ngeneral kenobi", text)
        assertEquals(setOf("img-1"), ChatSearch.attachments(entries))
        val lines = entries.map { SessionJson.encodeToString(LogEntry.serializer(), it) } + "not json"
        assertEquals(text, ChatSearch.textOfLines(lines))
        assertEquals(listOf("img-1"), ChatSearch.attachmentsOfLines(lines))
    }

    @Test
    fun storedTextIsCapped() {
        val big = LogEntry(1, 0, SessionEvent.UserMessage("x".repeat(ChatSearch.MAX_TEXT + 10)))
        val capped = ChatSearch.append("", listOf(big))
        assertEquals(ChatSearch.MAX_TEXT, capped.length)
        assertEquals(capped, ChatSearch.append(capped, listOf(LogEntry(2, 0, SessionEvent.UserMessage("more")))))
    }
}

package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.SurfaceOp
import dev.wckdboy.autobot.agent.core.session.CASCADE_ESCALATION
import dev.wckdboy.autobot.agent.core.session.TOOL_OUTCOME_UNKNOWN
import dev.wckdboy.autobot.agent.core.session.ToolCallRecord
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.session.deriveMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeriveMessagesTest {

    private fun log(vararg events: Pair<SessionEvent, SurfaceOp>): List<LogEntry> =
        events.mapIndexed { i, (e, s) -> LogEntry(i + 1L, 0L, e, s) }

    private fun SessionEvent.a() = this to SurfaceOp.Append

    @Test
    fun latestSystemPromptLeadsAndContextsAreWrapped() {
        val messages = deriveMessages(
            log(
                SessionEvent.SystemMessage("old").a(),
                SessionEvent.UserMessage("cwd: /w", UserMessageKind.CONTEXT, "cwd").a(),
                SessionEvent.UserMessage("hi").a(),
                SessionEvent.SystemMessage("new").a(),
            ),
        )
        assertEquals(ModelRole.SYSTEM, messages[0].role)
        assertEquals("new", messages[0].text)
        assertEquals("<system-reminder>\ncwd: /w\n</system-reminder>", messages[1].text)
        assertEquals("hi", messages[2].text)
    }

    @Test
    fun unansweredToolCallsGetSyntheticResultsAndCurrentTurnIsTagged() {
        val messages = deriveMessages(
            log(
                SessionEvent.UserMessage("go").a(),
                SessionEvent.AssistantMessage(1, 1, "", toolCalls = listOf(ToolCallRecord("a", "read", "{}"), ToolCallRecord("b", "read", "{}"))).a(),
                SessionEvent.ToolCall("a", "read", "{}").a(),
                SessionEvent.ToolResult("a", "read", "ok").a(),
            ),
        )
        val roles = messages.map { it.role }
        assertEquals(listOf(ModelRole.USER, ModelRole.ASSISTANT, ModelRole.TOOL, ModelRole.TOOL), roles)
        assertEquals("ok", messages[2].text)
        assertEquals(TOOL_OUTCOME_UNKNOWN, messages[3].text)
        assertTrue(messages[1].inCurrentTurn)
    }

    @Test
    fun compactionSummaryReplacesItsRangeAndIsSupersededByLaterCompaction() {
        val entries = log(
            SessionEvent.UserMessage("one").a(), // 1
            SessionEvent.AssistantMessage(1, 1, "r1").a(), // 2
            SessionEvent.UserMessage("two").a(), // 3
            SessionEvent.AssistantMessage(2, 1, "r2").a(), // 4
            SessionEvent.UserMessage("S1", UserMessageKind.SUMMARY) to SurfaceOp.Replace(1, 2), // 5
            SessionEvent.UserMessage("three").a(), // 6
        )
        val first = deriveMessages(entries).map { it.text }
        assertEquals(listOf("<compacted-summary>\nS1\n</compacted-summary>", "two", "r2", "three"), first)

        val second = deriveMessages(
            entries + LogEntry(7, 0, SessionEvent.UserMessage("S2", UserMessageKind.SUMMARY), SurfaceOp.Replace(1, 5)),
        ).map { it.text }
        assertEquals(listOf("<compacted-summary>\nS2\n</compacted-summary>", "three"), second)
        assertFalse(second.any { "S1" in it })
    }

    @Test
    fun logEntriesRoundTripThroughJson() {
        val entry = LogEntry(
            3,
            42,
            SessionEvent.AssistantMessage(1, 2, "x", "why", listOf(ToolCallRecord("c", "read", "{\"p\":1}"))),
            SurfaceOp.Replace(1, 2),
        )
        val json = SessionJson.encodeToString(LogEntry.serializer(), entry)
        assertTrue(json.contains("\"type\":\"assistant/message\""))
        assertEquals(entry, SessionJson.decodeFromString(LogEntry.serializer(), json))
    }

    @Test
    fun workspaceRevertBecomesAContextLineAndRoundTrips() {
        val revert = SessionEvent.WorkspaceRevert("r1", callIds = listOf("a"), turn = 3, restored = listOf("notes/a.md"), deleted = listOf("notes/b.md"))
        val messages = deriveMessages(
            log(
                SessionEvent.UserMessage("go").a(),
                SessionEvent.AssistantMessage(3, 1, "done").a(),
                revert.a(),
                SessionEvent.WorkspacePruned(listOf(1)).a(),
                SessionEvent.UserMessage("next").a(),
            ),
        )
        assertEquals(listOf(ModelRole.USER, ModelRole.ASSISTANT, ModelRole.USER, ModelRole.USER), messages.map { it.role })
        assertEquals(
            "<system-reminder>\nThe user reverted turn 3's file changes: notes/a.md restored, notes/b.md deleted. Read a file again before editing it.\n</system-reminder>",
            messages[2].text,
        )
        val entry = LogEntry(1, 0, revert)
        val json = SessionJson.encodeToString(LogEntry.serializer(), entry)
        assertTrue(json.contains("\"type\":\"workspace/revert\""))
        assertEquals(entry, SessionJson.decodeFromString(LogEntry.serializer(), json))
    }

    @Test
    fun aCascadeEscalationDropsThePhonesAnswerAndIsNotSent() {
        val entries = log(
            SessionEvent.UserMessage("earlier").a(),
            SessionEvent.AssistantMessage(1, 1, "earlier answer").a(),
            SessionEvent.UserMessage("who won yesterday?").a(),
            SessionEvent.AssistantMessage(2, 1, "I don't know.", model = "phone-model").a(),
            SessionEvent.TurnStart(3).a(),
            SessionEvent.UserMessage("recall: none", UserMessageKind.CONTEXT, "memory").a(),
            SessionEvent.UserMessage("asked", UserMessageKind.CONTEXT, CASCADE_ESCALATION).a(),
        )
        val messages = deriveMessages(entries)
        assertEquals(listOf("earlier", "earlier answer", "who won yesterday?", "<system-reminder>\nrecall: none\n</system-reminder>"), messages.map { it.text })
        assertFalse(messages.any { "I don't know" in it.text || it.text == "asked" })

        // Later requests carry the cloud's answer only.
        val after = deriveMessages(entries + LogEntry(8, 0, SessionEvent.AssistantMessage(3, 1, "Team A won.", model = "cloud"), SurfaceOp.Append) +
            LogEntry(9, 0, SessionEvent.UserMessage("thanks"), SurfaceOp.Append))
        assertEquals(listOf("earlier", "earlier answer", "who won yesterday?", "<system-reminder>\nrecall: none\n</system-reminder>", "Team A won.", "thanks"), after.map { it.text })
    }

    /**
     * A follow-up whose turn ends without an answer (refused before its request, failed, stopped
     * first) takes nothing away: later requests carry the phone's answer. While that turn runs,
     * its own requests leave the phone's answer out.
     */
    @Test
    fun aCascadeEscalationThatGotNoAnswerLeavesThePhonesAnswer() {
        val running = listOf(
            SessionEvent.UserMessage("who won yesterday?"),
            SessionEvent.AssistantMessage(1, 1, "I don't know.", model = "phone-model"),
            SessionEvent.TurnStart(2),
            SessionEvent.UserMessage("asked", UserMessageKind.CONTEXT, CASCADE_ESCALATION),
            // An empty one (stopped before any output) is no answer.
            SessionEvent.AssistantMessage(2, 1, "", interrupted = true),
        )
        assertEquals(listOf("who won yesterday?"), deriveMessages(log(*running.map { it.a() }.toTypedArray())).map { it.text })

        for (end in listOf<SessionEvent>(SessionEvent.TurnEnd(2, TurnEndReason.ERROR, "refused"), SessionEvent.TurnStart(3))) {
            val after = log(*(running + end + SessionEvent.UserMessage("and the day before?")).map { it.a() }.toTypedArray())
            assertEquals("$end", listOf("who won yesterday?", "I don't know.", "and the day before?"), deriveMessages(after).map { it.text })
        }
    }
}

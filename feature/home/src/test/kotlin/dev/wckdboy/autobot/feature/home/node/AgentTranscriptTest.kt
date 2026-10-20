package dev.wckdboy.autobot.feature.home.node

import dev.wckdboy.autobot.shared.invincible.AgentAction
import dev.wckdboy.autobot.shared.invincible.AgentEvent
import dev.wckdboy.autobot.shared.invincible.ApprovalAnswer
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTranscriptTest {
    @Test
    fun eventsBecomeRowsAndReceiptsLabelTheReply() {
        var rows: List<AgentRow> = listOf(AgentRow.User("u", "make a file"))
        var route: String? = null
        val events = listOf(
            AgentEvent.Receipt("r1", "qwen3.5-4b", "node"),
            AgentEvent.Approval("a1", "run: touch x", AgentAction.Command("touch x")),
            AgentEvent.Tool("run: touch x"),
            AgentEvent.Refused("not allowlisted"),
            AgentEvent.Answer("Done.", listOf("r1")),
        )
        events.forEachIndexed { i, event ->
            if (event is AgentEvent.Receipt) route = AgentTranscript.route(event)
            rows = AgentTranscript.apply(rows, event, route, "k$i")
        }
        assertEquals(
            listOf(
                AgentRow.User("u", "make a file"),
                AgentRow.Question("k1", "a1", "run: touch x", AgentAction.Command("touch x")),
                AgentRow.Note("k2", "run: touch x", AgentRow.Tone.TOOL),
                AgentRow.Note("k3", "not allowlisted", AgentRow.Tone.REFUSED),
                AgentRow.Reply("k4", "Done.", "qwen3.5-4b · node"),
            ),
            rows,
        )
        val answered = AgentTranscript.answered(rows, "a1", ApprovalAnswer.DENY)
        assertEquals(ApprovalAnswer.DENY, (answered[1] as AgentRow.Question).answer)
        assertEquals(rows - rows[1], answered - answered[1])
    }

    @Test
    fun somethingElseIsKeptOnTheQuestion() {
        val rows = listOf(AgentRow.Question("k1", "a1", "run: rm x", AgentAction.Command("rm x")))
        val after = AgentTranscript.instead(rows, "a1", "Move it to the trash")
        val q = after[0] as AgentRow.Question
        assertEquals("Move it to the trash", q.instead)
        assertEquals(null, q.answer)
        assertEquals(true, q.answered)
    }

    @Test
    fun approvalsWaitingOnTheNodeBecomeCardsOnce() {
        val rows = listOf<AgentRow>(AgentRow.User("u", "hi"), AgentRow.Question("k1", "a1", "run: ls", AgentAction.Command("ls")))
        val waiting = listOf(
            dev.wckdboy.autobot.shared.invincible.PendingApproval("a1", "app-x", "run: ls", AgentAction.Command("ls"), askedAt = 1),
            dev.wckdboy.autobot.shared.invincible.PendingApproval("a2", "cron-t1", "write", AgentAction.Write("/etc/x", 3, false), agent = "coder", askedAt = 2),
        )
        val after = AgentTranscript.waiting(rows, waiting) { "w-${it.id}" }
        assertEquals(3, after.size)
        assertEquals(AgentRow.Question("w-a2", "a2", "write", AgentAction.Write("/etc/x", 3, false), agent = "coder"), after[2])
    }

    @Test
    fun keptNotesFollowTheAnswerQuietly() {
        val rows = AgentTranscript.apply(listOf(AgentRow.Reply("k0", "Done.", null)), AgentEvent.Kept("profile: prefers short answers"), null, "k1")
        assertEquals(AgentRow.Kept("k1", "kept · profile: prefers short answers"), rows.last())
    }

    @Test
    fun retryIsOnTheLastAnswerOrFailureOfAFinishedTurn() {
        val turn = listOf(
            AgentRow.User("u0", "first"),
            AgentRow.Reply("r0", "one", null),
            AgentRow.User("u1", "second"),
            AgentRow.Note("t1", "run: ls", AgentRow.Tone.TOOL),
            AgentRow.Reply("r1", "two", null),
            AgentRow.Kept("k1", "kept · memory: x"),
        )
        assertEquals("r1", AgentTranscript.retryTarget(turn, running = false))
        assertEquals(null, AgentTranscript.retryTarget(turn, running = true))
        // A failed turn retries from its failure.
        val failed = turn.take(3) + AgentRow.Note("e1", "The node did not answer.", AgentRow.Tone.ERROR)
        assertEquals("e1", AgentTranscript.retryTarget(failed, running = false))
        // Waiting for an approval: not finished.
        val asking = turn.take(3) + AgentRow.Question("q", "a", "run: rm", AgentAction.Command("rm"))
        assertEquals(null, AgentTranscript.retryTarget(asking, running = false))
        assertEquals(null, AgentTranscript.retryTarget(listOf(AgentRow.Reply("r", "hi", null)), running = false))
        // Retrying: the last turn's rows go, its message stays.
        assertEquals(turn.take(3), AgentTranscript.retrying(turn))
    }

    @Test
    fun aMessageTheModelCouldNotSeeLeavesTheTranscript() {
        val rows = listOf(AgentRow.User("u0", "hi"), AgentRow.User("u1", "what is this", listOf(byteArrayOf(1, 2, 3))))
        assertEquals(listOf(AgentRow.User("u0", "hi")), AgentTranscript.unsent(rows, "u1"))
        // Photos compare by content.
        assertEquals(AgentRow.User("u1", "what is this", listOf(byteArrayOf(1, 2, 3))), rows[1])
        assertEquals(1, (rows[1] as AgentRow.User).photoCount)
    }
}

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
}

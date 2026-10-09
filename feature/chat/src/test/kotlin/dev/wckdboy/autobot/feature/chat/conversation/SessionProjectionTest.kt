package dev.wckdboy.autobot.feature.chat.conversation

import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TodoItem
import dev.wckdboy.autobot.agent.core.session.TodoStatus
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.ToolCallRecord
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProjectionTest {

    private fun entries(vararg events: SessionEvent) = events.mapIndexed { i, e -> LogEntry(i + 1L, 0L, e) }

    @Test
    fun projectsMessagesToolsTodosAndNotices() {
        val log = entries(
            SessionEvent.UserMessage("ctx", UserMessageKind.CONTEXT, "cwd"),
            SessionEvent.UserMessage("draw a cat"),
            SessionEvent.RequestContext("p", "m", 128_000),
            SessionEvent.AssistantMessage(
                1, 1, "", "plan",
                toolCalls = listOf(ToolCallRecord("a", "generate_image", "{}"), ToolCallRecord("b", "web_fetch", "{}"), ToolCallRecord("c", "read", "{}")),
                usage = TokenUsage(inputTokens = 900, outputTokens = 100),
            ),
            SessionEvent.ToolResult(
                "a", "generate_image", "ok",
                meta = JsonObject(mapOf("gallery" to JsonArray(listOf(JsonPrimitive("g1"))))),
            ),
            SessionEvent.ToolResult("b", "web_fetch", "Error: denied", isError = true, code = "USER_REJECTED"),
            SessionEvent.TodoWrite(listOf(TodoItem("draw", TodoStatus.IN_PROGRESS))),
            SessionEvent.TurnEnd(1, TurnEndReason.ERROR, "AUTH: bad key"),
        )

        val p = projectSession(log, pendingApprovals = emptySet(), running = setOf("c")) { name, _ -> "args of $name" }

        val tools = p.items.filterIsInstance<SessionItem.Tool>()
        assertEquals(listOf(ToolState.OK, ToolState.DENIED, ToolState.RUNNING), tools.map { it.state })
        assertEquals(listOf("g1"), tools[0].galleryIds)
        assertEquals("args of read", tools[2].summary)
        assertEquals("draw a cat", (p.items.first() as SessionItem.User).text)
        assertEquals("plan", p.items.filterIsInstance<SessionItem.Assistant>().single().reasoning)
        assertTrue(p.items.last() is SessionItem.Notice)
        assertEquals(1, p.todos.size)
        assertEquals(128_000, p.contextWindow)
        assertEquals(900, p.lastUsage?.inputTokens)
    }

    @Test
    fun pendingApprovalWinsOverStreaming() {
        val log = entries(SessionEvent.AssistantMessage(1, 1, "", toolCalls = listOf(ToolCallRecord("x", "delete", "{}"))))
        val p = projectSession(log, pendingApprovals = setOf("x"), running = emptySet()) { _, _ -> "" }
        assertEquals(ToolState.WAITING_APPROVAL, (p.items.single() as SessionItem.Tool).state)
    }
}

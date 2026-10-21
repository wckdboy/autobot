package dev.wckdboy.autobot.shared.session

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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.wckdboy.autobot.providers.remote.SystemModels
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
    fun failuresAreExplainedInWords() {
        assertEquals(
            "The provider rejected the API key. Check it in Remote › Providers (HTTP 401: invalid key)",
            explainFailure("AUTH: HTTP 401: invalid key"),
        )
        assertTrue(explainFailure("BLOCKED: offline mode").startsWith("Blocked by the network mode"))
        assertEquals("No model provider selected", explainFailure("BAD_REQUEST: no model provider selected"))
        assertEquals("something odd", explainFailure("something odd"))
        assertEquals("The turn failed", explainFailure(null))
    }

    @Test
    fun usedUpLimitsSayWhenTheyResetAndWhatToDo() {
        assertEquals(
            "OpenCode Go's 5-hour limit is used up; it resets in ~2 h. Switch to the helper model or an on-device model.",
            explainFailure("QUOTA: OpenCode Go's 5-hour limit is used up; it resets in ~2 h."),
        )
        assertEquals(
            "The provider says the account is out of credit or quota (HTTP 402: Insufficient balance). Switch to the helper model or an on-device model.",
            explainFailure("QUOTA: HTTP 402: Insufficient balance"),
        )
    }

    @Test
    fun fallbackAnswersCarryANotice() {
        assertEquals("glm-5.3-flash · fallback — kimi-k2.6 hit its usage limit — answered by the helper model",
            fallbackNotice("glm-5.3-flash", "kimi-k2.6 hit its usage limit — answered by the helper model"))
        assertTrue(fallbackNotice(SystemModels.APPLE, "too big").contains("system model"))
    }

    @Test
    fun taskResultsCarryTheSubagentSteps() {
        val trace = buildJsonObject {
            put("subagent", buildJsonObject {
                put("end", "completed")
                put("steps", buildJsonArray {
                    add(buildJsonObject { put("name", "read"); put("summary", "notes.md"); put("state", "ok") })
                    add(buildJsonObject { put("name", "web_fetch"); put("summary", ""); put("state", "error") })
                })
            })
        }
        val log = entries(
            SessionEvent.UserMessage("go"),
            SessionEvent.AssistantMessage(1, 1, "", toolCalls = listOf(ToolCallRecord("t", "task", "{}"))),
            SessionEvent.ToolResult("t", "task", "report", meta = trace),
        )
        val tool = projectSession(log, emptySet(), emptySet()) { _, _ -> "" }.items.filterIsInstance<SessionItem.Tool>().single()
        assertEquals(listOf("✓ read · notes.md", "× web_fetch"), tool.steps)
        assertEquals("report", tool.output)
    }

    @Test
    fun labelsAnswersFromTheSystemModelFallback() {
        val log = entries(
            SessionEvent.UserMessage("hi"),
            SessionEvent.AssistantMessage(1, 1, "hello", model = "system:apple", fallback = "'qwen' failed to load (out of memory)"),
            SessionEvent.AssistantMessage(2, 1, "again", model = "qwen"),
        )
        val items = projectSession(log, emptySet(), emptySet()) { _, _ -> "" }.items
        val notice = items[1] as SessionItem.Notice
        assertEquals("Apple Intelligence (on-device) · system model · fallback — 'qwen' failed to load (out of memory)", notice.text)
        assertEquals(NoticeKind.WARN, notice.kind)
        assertEquals("system:apple", (items[2] as SessionItem.Assistant).model)
        assertEquals(4, items.size)
    }

    @Test
    fun pendingApprovalWinsOverStreaming() {
        val log = entries(SessionEvent.AssistantMessage(1, 1, "", toolCalls = listOf(ToolCallRecord("x", "delete", "{}"))))
        val p = projectSession(log, pendingApprovals = setOf("x"), running = emptySet()) { _, _ -> "" }
        assertEquals(ToolState.WAITING_APPROVAL, (p.items.single() as SessionItem.Tool).state)
    }

    @Test
    fun fileStepsCarryDiffsUndoStateAndTheTurnsChanges() {
        fun change(path: String, op: ChangeOp, added: Int = 1) =
            JsonObject(mapOf(FileChange.META_KEY to FileChange.toJson(FileChange(path, op, added = added, diff = listOf("+x")))))
        val log = entries(
            SessionEvent.TurnStart(1),
            SessionEvent.UserMessage("edit"),
            SessionEvent.AssistantMessage(1, 1, "", toolCalls = listOf(ToolCallRecord("a", "write", "{}"), ToolCallRecord("b", "edit", "{}"))),
            SessionEvent.ToolResult("a", "write", "Created", meta = change("a.md", ChangeOp.CREATED)),
            SessionEvent.ToolResult("b", "edit", "Edited", meta = change("b.md", ChangeOp.MODIFIED, added = 2)),
            SessionEvent.AssistantMessage(1, 2, "done"),
            SessionEvent.TurnEnd(1, TurnEndReason.COMPLETED),
            SessionEvent.WorkspaceRevert("revert-1", callIds = listOf("b"), restored = listOf("b.md")),
        )
        val p = projectSession(log, emptySet(), emptySet()) { _, _ -> "" }
        val tools = p.items.filterIsInstance<SessionItem.Tool>()
        assertEquals(listOf("a.md", "b.md"), tools.map { it.change!!.path })
        assertEquals(listOf(false, true), tools.map { it.undone })
        assertEquals("revert-1", tools[1].revertId)
        assertEquals(TurnChanges(1, files = 2, undone = false, revertId = null, undoable = true), p.items.filterIsInstance<SessionItem.Assistant>().single().turnChanges)
        assertEquals(listOf(ChangeGroup(1, listOf(ChangedFile("a.md", ChangeOp.CREATED, 1, 0, false, "a"), ChangedFile("b.md", ChangeOp.MODIFIED, 2, 0, true, "b")))), p.changes)
        assertEquals("undid a step · 1 restored", (p.items.last() as SessionItem.Notice).text)

        // Undo the rest as a turn, then redo it; and a pruned turn can no longer be undone.
        val more = log + entries(
            SessionEvent.WorkspaceRevert("revert-2", callIds = listOf("a"), turn = 1, deleted = listOf("a.md")),
        ).map { it.copy(seq = it.seq + 100) }
        val undone = projectSession(more, emptySet(), emptySet()) { _, _ -> "" }.items.filterIsInstance<SessionItem.Assistant>().single().turnChanges!!
        assertTrue(undone.undone)
        assertEquals("revert-2", undone.revertId)
        val redone = more + entries(SessionEvent.WorkspaceRevert("revert-3", callIds = listOf("a"), turn = 1, redoOf = "revert-2"), SessionEvent.WorkspacePruned(listOf(1)))
            .map { it.copy(seq = it.seq + 200) }
        val p2 = projectSession(redone, emptySet(), emptySet()) { _, _ -> "" }
        val a = p2.items.filterIsInstance<SessionItem.Tool>().first()
        assertEquals(false, a.undone)
        assertEquals(false, a.change!!.undoable)
        assertEquals("undo history of turn 1 dropped", (p2.items.last() as SessionItem.Notice).text)
    }
}

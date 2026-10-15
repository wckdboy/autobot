package dev.wckdboy.autobot.shared.session

import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.ReceiptModel
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** S3: [SessionProjector] gives exactly what a full [projectSession] gives, at every step. */
class IncrementalProjectionTest {

    private fun change(path: String, op: ChangeOp, added: Int = 1) =
        JsonObject(mapOf(FileChange.META_KEY to FileChange.toJson(FileChange(path, op, added = added, diff = listOf("+x")))))

    private val subagent = buildJsonObject {
        put("subagent", buildJsonObject {
            put("end", "completed")
            put("steps", buildJsonArray {
                add(buildJsonObject { put("name", "read"); put("summary", "notes.md"); put("state", "ok") })
                add(buildJsonObject { put("name", "web_fetch"); put("summary", ""); put("state", "error") })
            })
        })
    }

    /** Two turns with files, undo / redo / prune, receipts, a subagent, approvals, a repeated call id. */
    private val fixture: List<LogEntry> = listOf(
        SessionEvent.UserMessage("ctx", UserMessageKind.CONTEXT, "cwd"),
        SessionEvent.TurnStart(1),
        SessionEvent.UserMessage("edit the notes"),
        SessionEvent.RequestContext("p", "m", 128_000),
        SessionEvent.AssistantMessage(
            1, 1, "", "plan",
            toolCalls = listOf(ToolCallRecord("a", "write", "{\"file_path\":\"a.md\"}"), ToolCallRecord("b", "edit", "{}"), ToolCallRecord("t", "task", "{}")),
            usage = TokenUsage(inputTokens = 900, outputTokens = 100),
        ),
        SessionEvent.ToolCall("a", "write"),
        SessionEvent.ToolResult("a", "write", "Created", meta = change("a.md", ChangeOp.CREATED)),
        SessionEvent.ToolCall("b", "edit", "{}"),
        SessionEvent.ToolResult("b", "edit", "Edited", meta = change("b.md", ChangeOp.MODIFIED, added = 2)),
        SessionEvent.ToolCall("t", "task"),
        SessionEvent.ToolResult("t", "task", "report", meta = subagent),
        SessionEvent.TodoWrite(listOf(TodoItem("notes", TodoStatus.IN_PROGRESS))),
        SessionEvent.AssistantMessage(1, 2, "done", model = "m", fallback = "x hit its usage limit"),
        SessionEvent.TurnReceipt(1, listOf(ReceiptModel("p", "m")), tools = listOf("write"), network = listOf(ReceiptHost("api.example.com", "direct", 2))),
        SessionEvent.TurnEnd(1, TurnEndReason.COMPLETED),
        SessionEvent.WorkspaceRevert("revert-1", callIds = listOf("b"), restored = listOf("b.md")),
        SessionEvent.TurnStart(2),
        SessionEvent.UserMessage("more"),
        SessionEvent.AssistantMessage(2, 1, "", toolCalls = listOf(ToolCallRecord("a", "delete", "{}"), ToolCallRecord("d", "web_fetch", "{}"), ToolCallRecord("e", "read", "{}"))),
        SessionEvent.LlmRetry(1, 500, "RATE_LIMIT", "slow down"),
        SessionEvent.ToolResult("d", "web_fetch", "Error: denied", isError = true, code = "USER_REJECTED"),
        SessionEvent.WorkspaceRevert("revert-2", callIds = listOf("a"), turn = 1, deleted = listOf("a.md")),
        SessionEvent.WorkspaceRevert("revert-3", callIds = listOf("a"), turn = 1, redoOf = "revert-2"),
        SessionEvent.WorkspacePruned(listOf(1), "full"),
        SessionEvent.PermissionPresetChanged("read-only"),
        SessionEvent.AgentSettingsChanged(false),
        SessionEvent.TurnEnd(2, TurnEndReason.ERROR, "AUTH: bad key"),
    ).mapIndexed { i, e -> LogEntry(i + 1L, 1_000L * i, e) }

    private val describe: (String, String) -> String = { name, args -> "$name(${args.length})" }

    @Test
    fun everyPrefixProjectsLikeTheFullProjection() {
        val projector = SessionProjector(describe)
        val states = listOf(emptySet<String>() to emptySet(), setOf("e") to emptySet(), emptySet<String>() to setOf("e", "a"))
        for (n in 0..fixture.size) {
            val prefix = fixture.subList(0, n)
            for ((pending, running) in states) {
                assertEquals("prefix $n", projectSession(prefix, pending, running, describe), projector.project(prefix, pending, running))
            }
        }
        assertEquals(fixture.size, projector.size)
    }

    @Test
    fun unchangedInputsReturnTheSameInstanceAndDescribeRunsOncePerCall() {
        var described = 0
        val projector = SessionProjector { name, _ -> described++; name }
        val first = projector.project(fixture, emptySet(), emptySet())
        val callCount = fixture.sumOf { (it.event as? SessionEvent.AssistantMessage)?.toolCalls?.size ?: 0 }
        assertEquals(callCount, described)
        assertSame(first, projector.project(fixture, emptySet(), emptySet()))
        assertNotSame(first, projector.project(fixture, setOf("e"), emptySet()))
        assertEquals(callCount, described)
    }

    @Test
    fun aDifferentLogStartsOver() {
        val projector = SessionProjector(describe)
        projector.project(fixture, emptySet(), emptySet())
        val other = fixture.take(5).map { it.copy(seq = it.seq + 1_000) }
        assertEquals(projectSession(other, emptySet(), emptySet(), describe), projector.project(other, emptySet(), emptySet()))
        val shorter = fixture.take(3)
        assertEquals(projectSession(shorter, emptySet(), emptySet(), describe), projector.project(shorter, emptySet(), emptySet()))
    }

    @Test
    fun galleryIdsSurvive() {
        val log = listOf(
            SessionEvent.AssistantMessage(1, 1, "", toolCalls = listOf(ToolCallRecord("g", "generate_image", "{}"))),
            SessionEvent.ToolResult("g", "generate_image", "ok", meta = JsonObject(mapOf("gallery" to JsonArray(listOf(JsonPrimitive("g1")))))),
        ).mapIndexed { i, e -> LogEntry(i + 1L, 0L, e) }
        val projector = SessionProjector(describe)
        projector.project(log.take(1), emptySet(), setOf("g"))
        assertEquals(projectSession(log, emptySet(), emptySet(), describe), projector.project(log, emptySet(), emptySet()))
    }
}

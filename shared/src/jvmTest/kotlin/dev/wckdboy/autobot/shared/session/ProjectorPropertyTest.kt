package dev.wckdboy.autobot.shared.session

import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.ReceiptModel
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SurfaceOp
import dev.wckdboy.autobot.agent.core.session.TodoItem
import dev.wckdboy.autobot.agent.core.session.TodoStatus
import dev.wckdboy.autobot.agent.core.session.TokenUsage
import dev.wckdboy.autobot.agent.core.session.ToolCallRecord
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Property: an incremental [SessionProjector] fed a growing log at random points always equals a
 * full [projectSession] of the same prefix (fixed seeds, so failures reproduce).
 */
class ProjectorPropertyTest {

    private val describe: (String, String) -> String = { name, args -> "$name(${args.length})" }

    /** A plausible random session log: turns of prompts, answers, tool calls and side events. */
    private fun randomLog(random: Random, size: Int): List<LogEntry> {
        val events = ArrayList<Pair<SessionEvent, SurfaceOp>>()
        var turn = 0
        var callN = 0
        val open = ArrayList<ToolCallRecord>()
        val done = ArrayList<String>()
        val reverts = ArrayList<String>()
        fun add(e: SessionEvent, op: SurfaceOp = SurfaceOp.Append) {
            events += e to op
        }
        while (events.size < size) {
            when (random.nextInt(20)) {
                0, 1 -> {
                    turn++
                    add(SessionEvent.TurnStart(turn))
                    add(SessionEvent.UserMessage("prompt $turn", images = if (random.nextInt(5) == 0) listOf("img") else emptyList()))
                }
                2, 3, 4 -> {
                    val calls = List(random.nextInt(0, 4)) {
                        val name = listOf("read", "write", "web_fetch", "task", "edit").random(random)
                        // Old logs repeat ids: sometimes reuse one.
                        val id = if (done.isNotEmpty() && random.nextInt(8) == 0) done.random(random) else "c${++callN}"
                        ToolCallRecord(id, name, "{\"n\":$callN}")
                    }
                    add(
                        SessionEvent.AssistantMessage(
                            turn, 1, if (random.nextBoolean()) "answer ${events.size}" else "",
                            reasoning = if (random.nextInt(3) == 0) "thinking" else null,
                            toolCalls = calls,
                            usage = if (random.nextBoolean()) TokenUsage(random.nextInt(1000), random.nextInt(100)) else null,
                            model = "m",
                            interrupted = random.nextInt(10) == 0,
                            fallback = if (random.nextInt(15) == 0) "limit reached" else null,
                        ),
                    )
                    open += calls
                }
                5, 6 -> if (open.isNotEmpty()) {
                    val call = open.removeAt(random.nextInt(open.size))
                    add(SessionEvent.ToolCall(call.id, call.name))
                }
                7, 8, 9 -> if (open.isNotEmpty() || done.isNotEmpty()) {
                    val call = if (open.isNotEmpty()) open.removeAt(0) else ToolCallRecord(done.random(random), "read", "{}")
                    val code = listOf(null, null, null, "USER_REJECTED", "TOOL_ERROR", "TIMEOUT", "ABORTED_BEFORE_DISPATCH").random(random)
                    val meta = when (random.nextInt(4)) {
                        0 -> buildJsonObject { put("gallery", JsonArray(listOf(JsonPrimitive("g1")))) }
                        1 -> buildJsonObject {
                            put("change", buildJsonObject {
                                put("path", JsonPrimitive("f${random.nextInt(3)}.md"))
                                put("op", JsonPrimitive(listOf("created", "modified", "deleted").random(random)))
                                put("added", JsonPrimitive(random.nextInt(5)))
                                put("removed", JsonPrimitive(random.nextInt(5)))
                                put("undoable", JsonPrimitive(random.nextBoolean()))
                            })
                        }
                        else -> null
                    }
                    add(SessionEvent.ToolResult(call.id, call.name, "out", code != null, code, meta))
                    done += call.id
                }
                10 -> if (open.isNotEmpty()) {
                    add(SessionEvent.ApprovalAsked(open.first().id, open.first().name, "net"))
                    if (random.nextBoolean()) add(SessionEvent.ApprovalDecided(open.first().id, ApprovalOutcome.entries.random(random)))
                }
                11 -> add(
                    SessionEvent.TurnReceipt(
                        turn,
                        models = listOf(ReceiptModel("p", "m", onDevice = random.nextBoolean())),
                        tools = listOf("read"),
                        network = if (random.nextBoolean()) null else listOf(ReceiptHost("api.example", "direct", 1, bytesSent = random.nextLong(0, 100_000))),
                    ),
                )
                12 -> add(SessionEvent.TurnEnd(turn, TurnEndReason.entries.random(random), listOf(null, "AUTH: bad key", "QUOTA: limit", "oops").random(random)))
                13 -> if (done.isNotEmpty()) {
                    val id = "revert:${events.size}"
                    if (reverts.isNotEmpty() && random.nextInt(3) == 0) {
                        add(SessionEvent.WorkspaceRevert(id, listOf(done.random(random)), redoOf = reverts.random(random), restored = listOf("f1.md")))
                    } else {
                        add(SessionEvent.WorkspaceRevert(id, done.shuffled(random).take(2), turn = if (random.nextBoolean()) turn else null, deleted = listOf("f2.md")))
                        reverts += id
                    }
                }
                14 -> add(SessionEvent.WorkspacePruned(listOf(random.nextInt(0, turn + 1)), if (random.nextBoolean()) "cap" else null))
                15 -> add(SessionEvent.TodoWrite(listOf(TodoItem("t", TodoStatus.entries.random(random)))))
                16 -> add(SessionEvent.LlmRetry(1, 500, "SERVER", "502"))
                17 -> add(SessionEvent.UserMessage("summary", UserMessageKind.SUMMARY), SurfaceOp.Replace(1, events.size.toLong()))
                18 -> add(SessionEvent.RequestContext("p", "m", random.nextInt(1000, 200_000)))
                else -> add(
                    listOf(
                        SessionEvent.PermissionPresetChanged("full-access"),
                        SessionEvent.AgentSettingsChanged(random.nextBoolean()),
                        SessionEvent.CompactionEnd(3, 10, error = if (random.nextBoolean()) "down" else null),
                        SessionEvent.UserMessage("ctx", UserMessageKind.CONTEXT, "c"),
                        SessionEvent.StepStart(turn, 1),
                    ).random(random),
                )
            }
        }
        return events.mapIndexed { i, (e, op) -> LogEntry(i + 1L, 1_000L * i, e, op) }
    }

    private fun randomIds(random: Random, entries: List<LogEntry>): Set<String> {
        val ids = entries.mapNotNull { (it.event as? SessionEvent.AssistantMessage)?.toolCalls }.flatten().map { it.id }
        return ids.filter { random.nextInt(4) == 0 }.toSet()
    }

    @Test
    fun incrementalProjectionEqualsAFullOneOnRandomLogs() {
        for (seed in 1..60) {
            val random = Random(seed)
            val log = randomLog(random, random.nextInt(20, 160))
            val projector = SessionProjector(describe)
            var at = 0
            while (at < log.size) {
                at = minOf(log.size, at + random.nextInt(0, 6))
                val prefix = log.subList(0, at)
                val pending = randomIds(random, prefix)
                val running = randomIds(random, prefix)
                val incremental = projector.project(prefix, pending, running)
                val full = projectSession(prefix, pending, running, describe)
                assertEquals("seed $seed at $at", full, incremental)
                // Nothing new: the same instance comes back.
                assertSame("seed $seed at $at", incremental, projector.project(prefix, pending, running))
            }
        }
    }

    @Test
    fun aReplacedOrShorterLogStartsOver() {
        for (seed in 100..120) {
            val random = Random(seed)
            val a = randomLog(random, 80)
            val b = randomLog(random, 60)
            val projector = SessionProjector(describe)
            projector.project(a, emptySet(), emptySet())
            assertEquals("seed $seed: another log", projectSession(b, emptySet(), emptySet(), describe), projector.project(b, emptySet(), emptySet()))
            val shorter = b.subList(0, 30)
            assertEquals("seed $seed: shorter", projectSession(shorter, emptySet(), emptySet(), describe), projector.project(shorter, emptySet(), emptySet()))
            assertEquals("seed $seed: grows again", projectSession(b, emptySet(), emptySet(), describe), projector.project(b, emptySet(), emptySet()))
        }
    }

    @Test
    fun aDescribeThatThrowsNeverBreaksTheProjection() {
        val random = Random(7)
        val log = randomLog(random, 120)
        val flaky: (String, String) -> String = { name, _ -> if (name == "write") error("no tool") else name }
        val projection = SessionProjector(flaky).project(log, emptySet(), emptySet())
        assertEquals(projectSession(log, emptySet(), emptySet(), flaky), projection)
    }
}

package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.loop.AgentStatus
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.toolsPlugin
import dev.wckdboy.autobot.agent.runtime.workspace.FileTools
import dev.wckdboy.autobot.agent.runtime.workspace.Workspace
import dev.wckdboy.autobot.agent.runtime.workspace.WorkspaceJournal
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.projectSession
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Undo through the agent loop: the agent edits a file, the user undoes the turn, the model hears about it. */
class UndoAgentTest {

    /** Turn 1: read, then overwrite notes.md, then answer. Turn 2: answer. */
    private class Scripted : LlmAdapter {
        override val provider = "cloud"
        val calls = CopyOnWriteArrayList<LlmCall>()

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            calls += call
            when (calls.size) {
                1 -> emit(StreamChunk.ToolCallDelta(0, "r1", "read", """{"file_path":"notes.md"}"""))
                2 -> emit(StreamChunk.ToolCallDelta(0, "w1", "write", """{"file_path":"notes.md","content":"rewritten\n"}"""))
                else -> emit(StreamChunk.TextDelta("done"))
            }
            emit(StreamChunk.Finish(if (calls.size <= 2) "tool_calls" else "stop"))
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 100_000)
    }

    private suspend fun Agent.awaitTurnEnd(turn: Int) = withTimeout(10_000) {
        log.entries.first { list -> list.any { (it.event as? SessionEvent.TurnEnd)?.turn == turn } }
        status.first { it == AgentStatus.IDLE }
    }

    @Test
    fun undoTurnRestoresTheFileAndTellsTheModel() = runBlocking {
        val dir = Files.createTempDirectory("ws").toFile()
        File(dir, "notes.md").writeText("original\n")
        val workspace = Workspace(dir.toOkioPath())
        val journal = WorkspaceJournal(workspace, Files.createTempDirectory("undo").toFile().toOkioPath())
        val harness = Harness()
        val llm = Scripted()
        harness.llm.register(llm)
        harness.root.plugin(toolsPlugin("workspace-files", *FileTools(workspace, journal).all.toTypedArray()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val agent = harness.openAgent("s1", InMemorySessionStore(), AgentConfig(LlmCallConfig("cloud", "big")), scope)
            agent.followup("rewrite my notes")
            agent.awaitTurnEnd(1)
            assertEquals("rewritten\n", File(dir, "notes.md").readText())

            val before = projectSession(agent.log.entries.value, emptySet(), emptySet()) { _, _ -> "" }
            val step = before.items.filterIsInstance<SessionItem.Tool>().single { it.name == "write" }
            assertEquals(listOf("@@ -1,1 +1,1 @@", "-original", "+rewritten"), step.change!!.diff)
            val answer = before.items.filterIsInstance<SessionItem.Assistant>().last()
            assertEquals(1, answer.turnChanges!!.files)

            val event = journal.undoTurn(agent, 1)!!
            assertEquals("original\n", File(dir, "notes.md").readText())
            assertEquals(listOf("notes.md"), event.restored)
            assertEquals(event, agent.log.latest<SessionEvent.WorkspaceRevert>())

            val after = projectSession(agent.log.entries.value, emptySet(), emptySet()) { _, _ -> "" }
            assertTrue(after.items.filterIsInstance<SessionItem.Tool>().single { it.name == "write" }.undone)
            assertEquals(event.id, after.items.filterIsInstance<SessionItem.Assistant>().last().turnChanges!!.revertId)
            assertTrue(after.items.any { it is SessionItem.Notice && it.text == "undid turn 1 · 1 restored" })

            agent.followup("what happened?")
            agent.awaitTurnEnd(2)
            val reminder = llm.calls.last().messages.map { it.text }.single { "reverted turn 1" in it }
            assertTrue(reminder, reminder.contains("notes.md restored"))
            agent.dispose()
        } finally {
            scope.cancel()
        }
    }
}

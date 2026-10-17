package dev.wckdboy.autobot.shared.recipes

import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.approval.ApprovalPolicy
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.toolsPlugin
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Headless recipe turns: the allowlist, denied approvals, the timeout and what the run reports. */
class RecipeTurnsTest {

    /** Plays [script] one step per model call (each entry: tool calls, or the final text). */
    private class Scripted(private val script: List<Any>) : LlmAdapter {
        override val provider = "local"
        val calls = CopyOnWriteArrayList<LlmCall>()

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            calls += call
            when (val step = script.getOrElse(calls.size - 1) { "done" }) {
                is String -> {
                    if (step == HANG) awaitCancellation()
                    emit(StreamChunk.TextDelta(step))
                    emit(StreamChunk.Finish("stop"))
                }
                is List<*> -> {
                    step.forEachIndexed { i, name -> emit(StreamChunk.ToolCallDelta(i, "c${calls.size}_$i", name as String, "{}")) }
                    emit(StreamChunk.Finish("tool_calls"))
                }
            }
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 32_000)
    }

    /** A tool that records that it ran. */
    private class Probe(override val name: String, override val kind: ToolKind, val ran: MutableList<String>) : ToolDefinition {
        override val description = name
        override val parameters = JsonObject(emptyMap())
        override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
            ran += name
            return ToolOutput("ok")
        }
    }

    private val recipe = Recipe(id = "r", name = "Brief", prompt = "Summarise the feeds", schedule = RecipeSchedule.Daily(420))

    private fun <T> withAgent(script: List<Any>, block: suspend (agent: dev.wckdboy.autobot.agent.core.loop.Agent, llm: Scripted, ran: MutableList<String>) -> T): T = runBlocking {
        val harness = Harness()
        val llm = Scripted(script)
        harness.llm.register(llm)
        val ran = CopyOnWriteArrayList<String>()
        harness.root.plugin(
            toolsPlugin(
                "probes",
                Probe("web_fetch", ToolKind.FETCH, ran),
                Probe("read", ToolKind.READ, ran),
                Probe("write", ToolKind.DELETE, ran),
                Probe("ask_user_question", ToolKind.INTERACT, ran),
            ),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val agent = harness.openAgent("chat", InMemorySessionStore(), AgentConfig(LlmCallConfig("cloud", "big")), scope)
            block(agent, llm, ran).also { agent.dispose() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aRunAnswersOnTheGivenRouteWithOnlyTheAllowedTools() = withAgent(listOf(listOf("web_fetch"), "Five headlines.")) { agent, llm, ran ->
        val result = RecipeTurns.run(agent, recipe.copy(tools = listOf("web_fetch", "web_search")), "local", "qwen", context = "Chats: none")
        assertEquals(RecipeRunStatus.DONE, result.status)
        assertEquals("Five headlines.", result.answer)
        assertEquals("Five headlines.", result.summary)
        assertTrue(result.notes.isEmpty())
        // The allowed network tool ran without a prompt (the recipe approved it).
        assertEquals(listOf("web_fetch"), ran)
        val first = llm.calls.first()
        assertEquals(LlmCallConfig("local", "qwen"), first.config)
        assertEquals(listOf("web_fetch"), first.tools.map { it.name })
        // The run's reminder (with the digest) reaches the model as context, not as a prompt.
        val context = first.messages.map { it.text }.single { "scheduled recipe" in it }
        assertTrue(context.contains("Chats: none"))
        val prompts = agent.log.entries.value.mapNotNull { it.event as? SessionEvent.UserMessage }
        assertEquals(listOf("Summarise the feeds"), prompts.filter { it.kind == UserMessageKind.PROMPT }.map { it.text })
    }

    @Test
    fun aToolOutsideTheAllowlistIsNotRunAndNoted() = withAgent(listOf(listOf("read"), "Could not read.")) { agent, _, ran ->
        val result = RecipeTurns.run(agent, recipe.copy(tools = listOf("web_fetch")), "local", "qwen")
        assertTrue(ran.isEmpty())
        assertEquals(listOf(RecipeCopy.notAllowed("read")), result.notes)
        assertEquals(RecipeRunStatus.DONE, result.status)
    }

    @Test
    fun aToolThatNeedsApprovalIsDeniedWithoutAPrompt() = withAgent(listOf(listOf("write"), "Left it.")) { agent, _, ran ->
        // `write` here is a delete-kind tool: the recipe allows it, but it still needs a person.
        val result = RecipeTurns.run(agent, recipe.copy(tools = listOf("write")), "local", "qwen")
        assertTrue(ran.isEmpty())
        assertEquals(listOf("Needed approval for write"), result.notes)
        val asked = agent.log.entries.value.mapNotNull { it.event as? SessionEvent.ApprovalDecided }
        assertEquals(1, asked.size)
    }

    @Test
    fun theChatIsInteractiveAgainAfterTheRun() = withAgent(listOf("ok")) { agent, _, _ ->
        RecipeTurns.run(agent, recipe.copy(tools = listOf("read")), "local", "qwen")
        assertEquals(ApprovalPolicy.ASK, agent.approvalPolicy.value)
        assertTrue(agent.sessionGrants.isEmpty())
        assertNull(agent.config.value.restriction.allow)
        assertEquals(AgentConfig.DEFAULT_MAX_STEPS, agent.config.value.maxStepsPerTurn)
        // The route stays on the on-device model for a follow-up.
        assertEquals(LlmCallConfig("local", "qwen"), agent.config.value.llm)
    }

    @Test
    fun noToolsMeansAPlainChatTurn() = withAgent(listOf("Digest.")) { agent, llm, _ ->
        val result = RecipeTurns.run(agent, recipe, "local", "qwen")
        assertEquals(RecipeRunStatus.DONE, result.status)
        assertTrue(llm.calls.single().tools.isEmpty())
        assertFalse(agent.config.value.toolsEnabled)
    }

    @Test
    fun aRunThatTakesTooLongIsStopped() = withAgent(listOf(HANG)) { agent, _, _ ->
        val result = RecipeTurns.run(agent, recipe, "local", "qwen", timeoutMs = 300)
        assertEquals(RecipeRunStatus.TIMED_OUT, result.status)
        assertEquals(RecipeCopy.TIMED_OUT, result.summary)
        assertTrue(agent.log.entries.value.any { (it.event as? SessionEvent.TurnEnd)?.turn == 1 })
    }

    @Test
    fun aSecondRunInTheSameChatReadsItsOwnTurn() = withAgent(listOf("First.", "Second.")) { agent, _, _ ->
        assertEquals("First.", RecipeTurns.run(agent, recipe, "local", "qwen").answer)
        assertEquals("Second.", RecipeTurns.run(agent, recipe, "local", "qwen").answer)
    }

    @Test
    fun outcomeReadsTheReceiptAndFailures() {
        val entries = listOf(
            SessionEvent.TurnStart(1),
            SessionEvent.UserMessage("go"),
            SessionEvent.AssistantMessage(1, 1, "**Top** story: [link](https://x)\n\n- one\n- two"),
            SessionEvent.TurnReceipt(
                1,
                network = listOf(ReceiptHost("hnrss.org", "tor", 2, bytesSent = 800), ReceiptHost("blocked.example", "direct", 1, blocked = 1)),
            ),
            SessionEvent.TurnEnd(1, dev.wckdboy.autobot.agent.core.session.TurnEndReason.COMPLETED),
        ).mapIndexed { i, e -> dev.wckdboy.autobot.agent.core.session.LogEntry((i + 1).toLong(), 0, e) }
        val done = RecipeTurns.outcome(entries, 1)
        assertEquals(RecipeRunStatus.DONE, done.status)
        assertEquals("Top story: link one two", done.summary)
        assertEquals(1, done.hosts)
        assertEquals(800, done.bytesSent)

        val failed = listOf(
            SessionEvent.TurnStart(1),
            SessionEvent.TurnEnd(1, dev.wckdboy.autobot.agent.core.session.TurnEndReason.ERROR, "PROTOCOL: out of memory"),
        ).mapIndexed { i, e -> dev.wckdboy.autobot.agent.core.session.LogEntry((i + 1).toLong(), 0, e) }
        val result = RecipeTurns.outcome(failed, 1)
        assertEquals(RecipeRunStatus.FAILED, result.status)
        assertEquals("PROTOCOL: out of memory", result.summary)
        assertEquals(RecipeRunStatus.FAILED, RecipeTurns.outcome(failed, 2).status)
    }

    private companion object {
        const val HANG = "<hang>"
    }
}

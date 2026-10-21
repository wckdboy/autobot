package dev.wckdboy.autobot.shared.a2ui

import dev.wckdboy.autobot.agent.core.Harness
import dev.wckdboy.autobot.agent.core.llm.LlmAdapter
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelInfo
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.loop.AgentConfig
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.UserMessageKind
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.projectSession
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASIC = A2uiCatalog.BASIC_CATALOG_ID

/** `show_ui` in an agent loop: valid UI is stored and replayed, invalid UI goes back to the model, actions become turns. */
class ShowUiToolTest {

    /** One model step per call: a String answers, a `ShowUi` calls the tool with its messages. */
    private class ShowUi(val messages: String)

    private class Scripted(private val script: List<Any>) : LlmAdapter {
        override val provider = "local"
        val calls = CopyOnWriteArrayList<LlmCall>()

        override fun stream(call: LlmCall): Flow<StreamChunk> = flow {
            calls += call
            when (val step = script.getOrElse(calls.size - 1) { "done" }) {
                is ShowUi -> {
                    val args = buildJsonObject { put("messages", JsonPrimitive(step.messages)) }.toString()
                    emit(StreamChunk.ToolCallDelta(0, "c${calls.size}", A2uiChat.TOOL, args))
                    emit(StreamChunk.Finish("tool_calls"))
                }
                else -> {
                    emit(StreamChunk.TextDelta(step.toString()))
                    emit(StreamChunk.Finish("stop"))
                }
            }
        }

        override suspend fun resolveModel(model: String) = ModelInfo(contextWindow = 32_000)
    }

    private fun withAgent(script: List<Any>, block: suspend (Agent, Scripted) -> Unit) = runBlocking {
        val harness = Harness()
        val llm = Scripted(script)
        harness.llm.register(llm)
        harness.root.plugin(a2uiPlugin())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val agent = harness.openAgent("chat", InMemorySessionStore(), AgentConfig(LlmCallConfig("local", "small")), scope)
            block(agent, llm)
            agent.dispose()
        } finally {
            scope.cancel()
        }
    }

    private suspend fun Agent.awaitTurns(n: Int) = withTimeout(10_000) {
        log.entries.first { list -> list.count { it.event is SessionEvent.TurnEnd } >= n }
    }

    private fun Agent.items(): List<SessionItem> = projectSession(log.entries.value, emptySet(), emptySet()) { _, _ -> "" }.items

    private val form = """
        [{"version":"v1.0","createSurface":{"surfaceId":"order","catalogId":"$BASIC","components":[
          {"id":"root","component":"Column","children":["name","go"]},
          {"id":"name","component":"TextField","label":"Name","value":{"@path":"/name"},
           "checks":[{"condition":{"@call":"required","args":{"value":{"@path":"/name"}}},"message":"Name is required."}]},
          {"id":"go","component":"Button","variant":"primary","child":"go_t",
           "action":{"event":{"name":"placeOrder","context":{"name":{"@path":"/name"}}}}},
          {"id":"go_t","component":"Text","text":"Order"}],
          "dataModel":{"name":"Ada"}}}]
    """.trimIndent()

    @Test
    fun validUiIsStoredWithTheChatAndReplayed() = withAgent(listOf(ShowUi(form), "Here is the form.")) { agent, llm ->
        agent.followup("I want to order")
        agent.awaitTurns(1)

        // The tool and its prompt went to the model.
        val first = llm.calls.first()
        assertTrue(first.tools.any { it.name == A2uiChat.TOOL })
        val system = first.messages.first { it.role == ModelRole.SYSTEM }.text
        assertTrue(system.contains("Generative UI: call show_ui"))

        // The model was told it was shown; the messages live in the result's meta.
        val result = agent.log.entries.value.firstNotNullOf { it.event as? SessionEvent.ToolResult }
        assertFalse(result.isError)
        assertTrue(result.content, result.content.startsWith("Shown to the user (surface 'order')"))
        // What is stored is what was validated: the messages as one JSON array.
        assertEquals(Json.parseToJsonElement(form), Json.parseToJsonElement(A2uiChat.stream(result.meta)!!))
        val toolMessage = llm.calls[1].messages.single { it.role == ModelRole.TOOL }
        assertFalse(toolMessage.text.contains("createSurface"))

        // The transcript carries them, and a transcript replays them into a host.
        val tool = agent.items().filterIsInstance<SessionItem.Tool>().single()
        assertEquals(A2uiChat.stream(result.meta), tool.a2ui)
        assertEquals("surface order", ShowUiTool().describeCall(buildJsonObject { put("messages", JsonPrimitive(form)) }))
        val transcript = A2uiTranscript()
        assertTrue(transcript.sync(agent.items()))
        assertFalse(transcript.sync(agent.items()))
        assertEquals(listOf("order"), transcript.surfaces(tool.key))
        assertEquals("Ada", transcript.host.render("order")!!.find("name")!!.string("value"))
    }

    @Test
    fun invalidUiGoesBackToTheModelUntilTheCap() {
        val broken = """[{"version":"v1.0","createSurface":{"surfaceId":"x","catalogId":"$BASIC","components":[{"id":"root","component":"Column","children":["missing"]},{"id":"t","component":"Txt","text":"hi"}]}}]"""
        withAgent(List(ShowUiTool.MAX_INVALID_PER_TURN + 1) { ShowUi(broken) } + "Plain answer.") { agent, llm ->
            agent.followup("show me")
            agent.awaitTurns(1)
            val results = agent.log.entries.value.mapNotNull { it.event as? SessionEvent.ToolResult }
            assertEquals(ShowUiTool.MAX_INVALID_PER_TURN + 1, results.size)
            results.take(ShowUiTool.MAX_INVALID_PER_TURN).forEach {
                assertTrue(it.isError)
                assertEquals("A2UI_INVALID", it.code)
                assertNull(it.meta)
            }
            // The model reads the validator's lines (forModel) to fix its output.
            val feedback = llm.calls[1].messages.single { it.role == ModelRole.TOOL }.text
            assertTrue(feedback, feedback.startsWith("Error: the UI was not shown. Fix these and call show_ui again:"))
            assertTrue(feedback, feedback.contains("VALIDATION_FAILED [message 0]"))
            assertEquals(A2uiFeedback.lines(A2uiValidator().validate(broken), null).joinToString("\n"), feedback.substringAfter("again:\n"))
            // A repeat of the same call is pointed out.
            val second = llm.calls[2].messages.last { it.role == ModelRole.TOOL }.text
            assertTrue(second, second.startsWith("Error: the UI was not shown. This call is the same as your last one: change it as the lines below say."))
            // Then it is told to stop, and nothing reached the transcript as UI.
            assertEquals("A2UI_GIVE_UP", results.last().code)
            assertTrue(agent.items().filterIsInstance<SessionItem.Tool>().all { it.a2ui == null })
            assertEquals("Plain answer.", agent.items().filterIsInstance<SessionItem.Assistant>().last().text)
        }
    }

    @Test
    fun anActionBecomesTheNextUserTurn() = withAgent(listOf(ShowUi(form), "Fill it in.", "Ordered for Ada Lovelace.")) { agent, llm ->
        agent.followup("order")
        agent.awaitTurns(1)
        val transcript = A2uiTranscript(nowMillis = { 1_780_390_643_000L })
        transcript.sync(agent.items())
        val host = transcript.host
        assertTrue(host.setString(host.render("order")!!.find("name")!!, "value", "Ada Lovelace"))
        val action = host.dispatch(host.render("order")!!.find("go")!!) as A2uiActionResult.SendEvent

        agent.queueTurn(A2uiChat.actionMessage(action))
        agent.awaitTurns(2)

        // The model reads the action message; the transcript shows it in words.
        val prompt = llm.calls.last().messages.last { it.role == ModelRole.USER }.text
        assertTrue(prompt, prompt.startsWith("[UI action]"))
        val json = prompt.substringAfter("<a2ui_action>\n").substringBefore("\n</a2ui_action>")
        val sent = Json.parseToJsonElement(json).jsonObject["action"]!!.jsonObject
        assertEquals("placeOrder", sent["name"]!!.jsonPrimitive.content)
        assertEquals("Ada Lovelace", sent["context"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val user = agent.log.entries.value.mapNotNull { it.event as? SessionEvent.UserMessage }.last { it.kind == UserMessageKind.PROMPT }
        assertEquals("Place order", user.display)
        assertEquals(listOf("order", "Place order"), agent.items().filterIsInstance<SessionItem.User>().map { it.text })
        assertEquals("Ordered for Ada Lovelace.", agent.items().filterIsInstance<SessionItem.Assistant>().last().text)
    }

    @Test
    fun laterCallsMayUpdateEarlierSurfacesButNotRecreateThem() {
        val update = """{"version":"v1.0","updateDataModel":{"surfaceId":"order","path":"/name","value":"Grace"}}"""
        withAgent(listOf(ShowUi(form), "Form.", ShowUi(form), ShowUi(update), "Updated.")) { agent, _ ->
            agent.followup("order")
            agent.awaitTurns(1)
            agent.followup("again")
            agent.awaitTurns(2)
            val results = agent.log.entries.value.mapNotNull { it.event as? SessionEvent.ToolResult }
            assertEquals(listOf(false, true, false), results.map { it.isError })
            assertTrue(results[1].content.contains("surface 'order' already exists"))
            // The update is drawn on the first call's surface.
            val transcript = A2uiTranscript()
            transcript.sync(agent.items())
            val tools = agent.items().filterIsInstance<SessionItem.Tool>()
            assertEquals(listOf("order"), transcript.surfaces(tools[0].key))
            assertEquals(emptyList<String>(), transcript.surfaces(tools[2].key))
            assertEquals("Grace", transcript.host.render("order")!!.find("name")!!.string("value"))
        }
    }

    @Test
    fun withoutToolsThePromptStaysOut() = withAgent(listOf("Hi.")) { agent, llm ->
        agent.updateConfig { it.copy(toolsEnabled = false) }
        agent.followup("hi")
        agent.awaitTurns(1)
        val system = llm.calls.first().messages.firstOrNull { it.role == ModelRole.SYSTEM }?.text.orEmpty()
        assertFalse(system.contains("show_ui"))
        assertTrue(llm.calls.first().tools.isEmpty())
    }

    @Test
    fun thePromptsExampleIsValidAndSmall() {
        val report = A2uiValidator().validate(A2uiPrompt.example)
        assertEquals("valid: 1 A2UI message(s)", report.forModel())
        assertEquals(listOf("tea"), report.surfaceIds)
        assertTrue("prompt is ${A2uiPrompt.text.length} chars", A2uiPrompt.text.length <= 3_000)
    }

    @Test
    fun aTranscriptStartsOverWhenTheHistoryChanges() {
        val transcript = A2uiTranscript()
        val a = tool("t1", form)
        val other = form.replace("\"order\"", "\"other\"")
        assertTrue(transcript.sync(listOf(a)))
        assertEquals(listOf("order"), transcript.host.surfaceIds)
        // A different history (compaction rewrote it, another chat): the old surfaces go.
        assertTrue(transcript.sync(listOf(tool("t9", other))))
        assertEquals(listOf("other"), transcript.host.surfaceIds)
        assertEquals(listOf("other"), transcript.surfaces("t9"))
        assertEquals(emptyList<String>(), transcript.surfaces("t1"))
    }

    @Test
    fun actionLabelsReadAsWords() {
        assertEquals("Submit contact form", A2uiChat.humanize("submitContactForm"))
        assertEquals("Place order", A2uiChat.humanize("place_order"))
        assertEquals("UI action", A2uiChat.humanize(""))
        assertNotNull(A2uiChat.meta("x")[A2uiChat.META])
    }

    private fun tool(key: String, stream: String) = SessionItem.Tool(
        key = key, callId = key, name = A2uiChat.TOOL, summary = "", state = dev.wckdboy.autobot.shared.session.ToolState.OK,
        output = "ok", code = null, galleryIds = emptyList(), a2ui = stream,
    )
}

package dev.wckdboy.autobot.shared.memory

import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.LocalModelState
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.shared.AgentBridge
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.LocalEngine
import dev.wckdboy.autobot.shared.LocalSink
import dev.wckdboy.autobot.shared.ProviderDirectory
import dev.wckdboy.autobot.shared.ProviderSpec
import dev.wckdboy.autobot.shared.SessionState
import dev.wckdboy.autobot.shared.SessionStoreBridge
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Memory through the shared agent loop: tools, approval for deletes, recall context, on/off. */
class MemoryAgentTest {

    /** Answers each model request with the SSE lines [respond] computes from it. */
    private class ScriptedTransport(private val respond: (HttpRequestSpec) -> List<String>) : HttpTransport {
        val sent = CopyOnWriteArrayList<HttpRequestSpec>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            sink.onResponse(200, emptyMap(), null)
            respond(request).forEach(sink::onLine)
            sink.onComplete()
            return Cancellable { }
        }
    }

    private object NoLocal : LocalEngine {
        override fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable? = null
        override fun mmproj(modelId: String): String? = null
        override fun readyModelCount() = 0
        override fun modelState(modelId: String) = LocalModelState.NOT_INSTALLED
        override fun systemModel(): SystemModelStatus? = null
        override fun systemChat(promptJson: String, sink: LocalSink): Cancellable? = null    }

    private object NoStore : SessionStoreBridge {
        override fun load(sessionId: String): List<String> = emptyList()
        override fun append(sessionId: String, entries: List<String>) = Unit
    }

    private class Notes : MemoryStoreBridge {
        val notes = LinkedHashMap<String, MemoryNote>()
        override fun all() = synchronized(notes) { notes.values.toList() }
        override fun put(note: MemoryNote) = synchronized(notes) { notes[note.id] = note }
        override fun remove(id: String) = synchronized(notes) { notes.remove(id) != null }
        override fun removeAll() = synchronized(notes) { notes.clear() }
    }

    private fun toolCall(id: String, name: String, args: JsonObject): List<String> {
        val chunk = buildJsonObject {
            putJsonArray("choices") {
                add(
                    buildJsonObject {
                        putJsonObject("delta") {
                            put(
                                "tool_calls",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("index", 0)
                                            put("id", id)
                                            putJsonObject("function") {
                                                put("name", name)
                                                put("arguments", args.toString())
                                            }
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }
        return listOf("data: $chunk", """data: {"choices":[{"finish_reason":"tool_calls"}]}""", "data: [DONE]")
    }

    private fun text(content: String) = listOf(
        """data: {"choices":[{"delta":{"content":"$content"}}]}""",
        """data: {"choices":[{"finish_reason":"stop"}]}""",
        "data: [DONE]",
    )

    @Test
    fun memoryToolsRecallAndToggleThroughTheAgentLoop() = runBlocking {
        val notes = Notes()
        val transport = ScriptedTransport { request ->
            val body = request.body.orEmpty()
            val last = body.substringAfterLast("\"role\":\"user\"")
            when {
                body.contains("Remember that my dog") && !body.contains("\"tool_call_id\":\"c1\"") ->
                    toolCall("c1", "memory_save", buildJsonObject { put("text", "The user's dog is called Rex"); putJsonArray("tags") { add(kotlinx.serialization.json.JsonPrimitive("pets")) } })
                last.contains("forget my dog") && !body.contains("\"tool_call_id\":\"c2\"") ->
                    toolCall("c2", "memory_delete", buildJsonObject { put("id", notes.all().single().id) })
                else -> text("Done.")
            }
        }
        val providers = object : ProviderDirectory {
            override fun provider(id: String) =
                ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Test", "https://api.example.com/v1", null, "m").takeIf { id == "p" }
        }
        val bridge = AgentBridge(Files.createTempDirectory("memory").toString(), providers, transport, NoLocal, NoStore, null, { null })
        bridge.setMemory(notes)

        val states = CopyOnWriteArrayList<SessionState>()
        val json = Json { ignoreUnknownKeys = true }
        val handle = bridge.open("s1", "p", "m") { states += json.decodeFromString(SessionState.serializer(), it) }
        withTimeout(5_000) { while (states.isEmpty()) delay(10) }

        suspend fun turn(prompt: String, approve: Boolean = false) {
            fun answers(s: SessionState?) = s?.projection?.items?.count { it is SessionItem.Assistant } ?: 0
            val before = answers(states.lastOrNull())
            bridge.send("s1", prompt, emptyList())
            withTimeout(10_000) {
                while (true) {
                    val s = states.lastOrNull()
                    if (approve) s?.approvals?.firstOrNull()?.let { bridge.answerApproval(it.callId, ApprovalOutcome.ALLOWED_ONCE) }
                    if (s != null && !s.running && answers(s) > before) {
                        break
                    }
                    delay(20)
                }
            }
        }

        // 1. The agent saves a note; edits run without a prompt under the default preset.
        turn("Remember that my dog is called Rex")
        val saved = notes.all().single()
        assertEquals("The user's dog is called Rex", saved.text)
        assertEquals(listOf("pets"), saved.tags)
        assertEquals("s1", saved.sessionId)
        assertTrue(transport.sent.first().body!!.contains("\"memory_save\""))
        assertTrue(transport.sent.first().body!!.contains("local memory"))

        // 2. The next message recalls it as a delimited context with the id, before the prompt.
        val before = transport.sent.size
        turn("What is my dog called?")
        val recall = transport.sent[before].body!!
        val context = recall.indexOf("<memory>")
        assertTrue(context > 0 && recall.indexOf("[${saved.id}] The user's dog is called Rex") > context)
        assertTrue(context < recall.indexOf("What is my dog called?"))

        // 3. Deleting asks for approval first.
        turn("Please forget my dog", approve = true)
        assertTrue(notes.all().isEmpty())
        val delete = states.last().projection.items.filterIsInstance<SessionItem.Tool>().last()
        assertEquals("memory_delete", delete.name)
        assertEquals(ToolState.OK, delete.state)

        // 4. Off: the tools disappear from the next request.
        bridge.setMemory(null)
        val off = transport.sent.size
        turn("Hello again")
        assertFalse(transport.sent[off].body!!.contains("\"name\":\"memory_search\""))
        assertTrue(transport.sent[0].body!!.contains("\"name\":\"memory_search\""))
        handle.cancel()
    }
}

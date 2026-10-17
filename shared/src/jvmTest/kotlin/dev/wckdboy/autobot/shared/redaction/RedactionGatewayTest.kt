package dev.wckdboy.autobot.shared.redaction

import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.LogEntry
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
import dev.wckdboy.autobot.shared.memory.MemoryNote
import dev.wckdboy.autobot.shared.memory.MemoryStoreBridge
import dev.wckdboy.autobot.shared.session.SessionItem
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The redaction gateway through the shared bridge: cloud requests carry placeholders, the chat keeps real values. */
class RedactionGatewayTest {

    /** Answers each model request with the SSE lines [respond] computes from its body. */
    private class ScriptedTransport(private val respond: (String) -> List<String>) : HttpTransport {
        val sent = CopyOnWriteArrayList<HttpRequestSpec>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            sink.onResponse(200, emptyMap(), null)
            respond(request.body.orEmpty()).forEach(sink::onLine)
            sink.onComplete()
            return Cancellable { }
        }

        val bodies: List<String> get() = sent.map { it.body.orEmpty() }
    }

    private object NoLocal : LocalEngine {
        override fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable? = null
        override fun mmproj(modelId: String): String? = null
        override fun readyModelCount() = 0
        override fun modelState(modelId: String) = LocalModelState.NOT_INSTALLED
        override fun systemModel(): SystemModelStatus? = null
        override fun systemChat(promptJson: String, sink: LocalSink): Cancellable? = null
    }

    private class Store : SessionStoreBridge {
        val lines = CopyOnWriteArrayList<String>()
        override fun load(sessionId: String): List<String> = emptyList()
        override fun append(sessionId: String, entries: List<String>) {
            lines += entries
        }

        fun events(): List<SessionEvent> = lines.map { SessionJson.decodeFromString(LogEntry.serializer(), it).event }
    }

    private class Notes(vararg initial: MemoryNote) : MemoryStoreBridge {
        val notes = LinkedHashMap(initial.associateBy { it.id })
        override fun all() = synchronized(notes) { notes.values.toList() }
        override fun put(note: MemoryNote) = synchronized(notes) { notes[note.id] = note }
        override fun remove(id: String) = synchronized(notes) { notes.remove(id) != null }
        override fun removeAll() = synchronized(notes) { notes.clear() }
    }

    /** Text streamed in the given pieces (each its own SSE event), then stop. */
    private fun text(vararg pieces: String): List<String> =
        pieces.map { p -> "data: " + buildJsonObject { putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { put("content", p) } }) } } } +
            listOf("""data: {"choices":[{"finish_reason":"stop"}]}""", "data: [DONE]")

    /** One tool call whose arguments stream in the given pieces. */
    private fun toolCall(id: String, name: String, vararg pieces: String): List<String> =
        pieces.mapIndexed { i, piece ->
            "data: " + buildJsonObject {
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
                                                if (i == 0) put("id", id)
                                                putJsonObject("function") {
                                                    if (i == 0) put("name", name)
                                                    put("arguments", piece)
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
        } + listOf("""data: {"choices":[{"finish_reason":"tool_calls"}]}""", "data: [DONE]")

    private class Rig(val bridge: AgentBridge, val transport: ScriptedTransport, val store: Store, val workspace: Path) {
        val states = CopyOnWriteArrayList<SessionState>()
        private val json = Json { ignoreUnknownKeys = true }
        private var handle: Cancellable? = null

        suspend fun open(provider: String = "cloud") {
            handle = bridge.open("s1", provider, "m") { states += json.decodeFromString(SessionState.serializer(), it) }
            withTimeout(5_000) { while (states.isEmpty()) delay(10) }
        }

        suspend fun turn(prompt: String) {
            fun answers(s: SessionState?) = s?.projection?.items?.count { it is SessionItem.Assistant && it.receipt != null } ?: 0
            val before = answers(states.lastOrNull())
            bridge.send("s1", prompt, emptyList())
            withTimeout(10_000) {
                while (true) {
                    val s = states.lastOrNull()
                    if (s != null && !s.running && answers(s) > before) break
                    delay(20)
                }
            }
        }

        val last: SessionState get() = states.last()
        val answers: List<SessionItem.Assistant> get() = last.projection.items.filterIsInstance<SessionItem.Assistant>()

        fun close() = handle?.cancel()
    }

    private val providers = object : ProviderDirectory {
        override fun provider(id: String) = when (id) {
            "cloud" -> ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Cloud", "https://api.example.com/v1", "k", "m")
            "ollama" -> ProviderSpec(id, ProviderKind.OLLAMA, "Ollama", "http://127.0.0.1:11434/v1", null, "m")
            else -> null
        }
    }

    private fun rig(respond: (String) -> List<String>): Rig {
        val workspace = Files.createTempDirectory("redaction")
        val transport = ScriptedTransport(respond)
        val store = Store()
        return Rig(AgentBridge(workspace.toString(), providers, transport, NoLocal, store, null, { null }), transport, store, workspace)
    }

    @Test
    fun cloudRequestsCarryPlaceholdersAndTheChatKeepsRealValues() = runBlocking<Unit> {
        val rig = rig { body ->
            when {
                // Turn 1: the model writes a file with placeholders in its (split) arguments.
                "\"tool_call_id\"" !in body && "draft" in body ->
                    toolCall("c1", "write", """{"file_path":"draft.txt","content":"Dear [PER""", """SON_1], your IBAN is [IBA""", """N_1]."}""")
                "\"tool_call_id\"" in body && "draft" in body && "second" !in body ->
                    text("Saved a draft for [PERSON", "_1] (", "[EMAIL_1]).")
                else -> text("Yes, [EMAIL_1] again.")
            }
        }
        rig.open()
        rig.turn("Please draft a note to my sister Hannah (hannah@posteo.de) with IBAN DE89 3704 0044 0532 0130 00")

        // What left the phone: placeholders only, plus the note that explains them.
        val first = rig.transport.bodies.first()
        assertFalse(first, "Hannah" in first || "hannah@posteo.de" in first || "DE89" in first)
        assertTrue(first, "my sister [PERSON_1] ([EMAIL_1]) with IBAN [IBAN_1]" in first)
        assertTrue(first, "placeholders such as [PERSON_1]" in first)

        // The tool ran with the real values; the request that carried its result did not.
        assertEquals("Dear Hannah, your IBAN is DE89 3704 0044 0532 0130 00.", rig.workspace.resolve("draft.txt").toFile().readText())
        val second = rig.transport.bodies[1]
        assertFalse(second, "Hannah" in second || "DE89 3704" in second)
        assertTrue(second, "Dear [PERSON_1], your IBAN is [IBAN_1]." in second)

        // The screen and the log show real values, restored across chunk boundaries.
        assertEquals("Saved a draft for Hannah (hannah@posteo.de).", rig.answers.last().text)
        assertTrue(rig.store.events().filterIsInstance<SessionEvent.AssistantMessage>().none { "[PERSON_1]" in it.text || "[EMAIL_1]" in it.text })

        // The receipt counts what was hidden, by kind, without the values.
        val receipt = rig.answers.last().receipt!!
        assertTrue(receipt.redacted)
        assertEquals(listOf("1 name", "1 email", "1 IBAN"), receipt.redactions.map { it.label })
        assertTrue(receipt.line, receipt.line.endsWith("3 redacted"))
        val logged = rig.store.events().filterIsInstance<SessionEvent.TurnReceipt>().single()
        assertEquals(mapOf("PERSON" to 1, "EMAIL" to 1, "IBAN" to 1), logged.redacted)
        assertFalse(rig.store.lines.filter { "turn/receipt" in it }.joinToString().contains("hannah"))

        // A later turn: the same values keep their placeholders (the history is resent redacted).
        rig.turn("second question: is hannah@posteo.de right?")
        val third = rig.transport.bodies.last()
        assertFalse(third, "hannah@posteo.de" in third)
        assertTrue(third, "is [EMAIL_1] right?" in third)
        assertTrue(third, "Saved a draft for [PERSON_1] ([EMAIL_1])." in third)
        assertEquals("Yes, hannah@posteo.de again.", rig.answers.last().text)
        assertEquals("ON", rig.last.redaction)
        rig.close()
    }

    @Test
    fun loopbackAndSwitchedOffRequestsAreNotTouched() = runBlocking<Unit> {
        val rig = rig { text("ok") }
        rig.open("ollama")
        rig.turn("mail hannah@posteo.de")
        assertTrue("hannah@posteo.de" in rig.transport.bodies.last())
        assertEquals("NONE", rig.last.redaction)
        // Its receipt says nothing about redaction: the gateway did not run.
        assertFalse(rig.answers.last().receipt!!.redacted)

        rig.bridge.setRoute("s1", "cloud", "m")
        rig.bridge.setRedactionDefault(false)
        rig.turn("mail lea@mail.com")
        assertTrue("lea@mail.com" in rig.transport.bodies.last())
        withTimeout(2_000) { while (rig.last.redaction != "OFF") delay(10) }

        // The chat's own choice beats Settings, and is remembered in the log.
        rig.bridge.setRedaction("s1", true)
        withTimeout(2_000) { while (rig.last.redaction != "ON") delay(10) }
        rig.turn("mail lea@mail.com again")
        val body = rig.transport.bodies.last()
        assertFalse(body, "lea@mail.com" in body)
        assertTrue(rig.store.events().filterIsInstance<SessionEvent.RedactionChanged>().last().enabled == true)
        assertTrue(rig.answers.last().receipt!!.redacted)

        // Choosing what Settings says makes the chat follow Settings again.
        rig.bridge.setRedactionDefault(true)
        rig.bridge.setRedaction("s1", true)
        withTimeout(2_000) { while (rig.store.events().filterIsInstance<SessionEvent.RedactionChanged>().last().enabled != null) delay(10) }
        rig.close()
    }

    @Test
    fun memoryFactsAreHiddenToo() = runBlocking<Unit> {
        val now = 1_700_000_000_000L
        val notes = Notes(MemoryNote("n1", "The user works at Siemens, customer number 8812345.", emptyList(), now, now))
        val rig = rig { text("Noted.") }
        rig.bridge.setMemory(notes)
        rig.open()
        rig.turn("Draft a complaint to Siemens about order 8812345")
        val body = rig.transport.bodies.last()
        // The recalled note and the prompt both carry the facts; neither leaves.
        assertFalse(body, "Siemens" in body || "8812345" in body)
        assertTrue(body, "[PRIVATE_1]" in body)
        val receipt = rig.answers.last().receipt!!
        assertTrue(receipt.redactions.any { it.kind == "PRIVATE" })
        rig.close()
    }

    @Test
    fun nothingPersonalMeansNoNoteAndAnEmptyCount() = runBlocking<Unit> {
        val rig = rig { text("4") }
        rig.open()
        rig.turn("what is 2 + 2?")
        val body = rig.transport.bodies.single()
        assertFalse(body, "placeholders such as" in body)
        val receipt = rig.answers.last().receipt!!
        assertTrue(receipt.redacted)
        assertTrue(receipt.redactions.isEmpty())
        assertFalse(receipt.line, "redacted" in receipt.line)
        assertNull(rig.store.events().filterIsInstance<SessionEvent.TurnReceipt>().single().redacted?.takeIf { it.isNotEmpty() })
        rig.close()
    }

    @Suppress("unused")
    private fun unusedImports() = JsonPrimitive("")
}

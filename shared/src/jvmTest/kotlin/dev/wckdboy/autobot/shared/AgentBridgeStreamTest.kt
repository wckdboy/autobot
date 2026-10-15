package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.LocalModelState
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.shared.session.SessionItem
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S7 / S8 and the adapter cache: the bridge's stream protocol, agent eviction, adapter reuse. */
class AgentBridgeStreamTest {

    /** Streams canned SSE answers line by line from a thread, [lineDelayMs] apart. */
    private class SlowTransport(private val answers: () -> List<String>, private val lineDelayMs: Long) : HttpTransport {
        val requests = AtomicInteger()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            requests.incrementAndGet()
            val lines = answers()
            val thread = Thread {
                sink.onResponse(200, mapOf("content-type" to "text/event-stream"), null)
                for (line in lines) {
                    sink.onLine(line)
                    if (lineDelayMs > 0) Thread.sleep(lineDelayMs)
                }
                sink.onComplete()
            }
            thread.start()
            return Cancellable { }
        }
    }

    private object NoLocal : LocalEngine {
        override fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable? = null
        override fun mmproj(modelId: String): String? = null
        override fun readyModelCount() = 0
        override fun modelState(modelId: String) = LocalModelState.NOT_INSTALLED
        override fun systemModel(): SystemModelStatus? = null
        override fun systemChat(promptJson: String, sink: LocalSink): Cancellable? = null
    }

    private class MemoryStore : SessionStoreBridge {
        val lines = HashMap<String, MutableList<String>>()
        override fun load(sessionId: String): List<String> = synchronized(lines) { lines[sessionId].orEmpty().toList() }
        override fun append(sessionId: String, entries: List<String>) {
            synchronized(lines) { lines.getOrPut(sessionId) { mutableListOf() }.addAll(entries) }
        }
    }

    private class CountingDirectory : ProviderDirectory {
        val lookups = AtomicInteger()
        override fun provider(id: String): ProviderSpec? {
            lookups.incrementAndGet()
            return ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Test", "https://api.example.com/v1", "secret", "m").takeIf { id == "p" }
        }
    }

    private fun data(json: String) = "data: $json"

    private fun textAnswer(vararg pieces: String) =
        pieces.map { data("""{"choices":[{"delta":{"content":${Json.encodeToString(String.serializer(), it)}}}]}""") } +
            listOf(data("""{"choices":[{"finish_reason":"stop"}]}"""), "data: [DONE]")

    private val json = Json { ignoreUnknownKeys = true }

    private class Recorder : SessionStreamListener {
        val events = CopyOnWriteArrayList<Any>()
        override fun onTranscript(json: String) {
            events += Json { ignoreUnknownKeys = true }.decodeFromString(TranscriptState.serializer(), json)
        }
        override fun onFrame(json: String) {
            events += json.length
            events += Json { ignoreUnknownKeys = true }.decodeFromString(LiveFrame.serializer(), json)
        }
        fun transcripts() = events.filterIsInstance<TranscriptState>()
        fun frames() = events.filterIsInstance<LiveFrame>()
    }

    @Test
    fun streamSendsTheTranscriptOnlyOnChangeAndTextAsDeltas() = runBlocking {
        val pieces = (1..30).map { "word$it " }
        val transport = SlowTransport({ textAnswer(*pieces.toTypedArray()) }, lineDelayMs = 15)
        val bridge = AgentBridge(Files.createTempDirectory("ws").toString(), CountingDirectory(), transport, NoLocal, MemoryStore(), null, { null })
        val recorder = Recorder()
        val handle = bridge.openStream("s1", "p", "m", recorder)
        withTimeout(5_000) { while (recorder.frames().isEmpty()) delay(10) }
        bridge.send("s1", "hi", emptyList())
        withTimeout(15_000) {
            while (recorder.transcripts().lastOrNull()?.projection?.items?.any { it is SessionItem.Assistant } != true || recorder.frames().last().running) delay(20)
        }
        handle.cancel()

        // Revisions count up from 1, and each frame names a transcript delivered before it.
        val transcripts = recorder.transcripts()
        assertEquals((1L..transcripts.size).toList(), transcripts.map { it.revision })
        var latest = 0L
        for (event in recorder.events) {
            if (event is TranscriptState) latest = event.revision
            if (event is LiveFrame) assertEquals(latest, event.revision)
        }
        // A projection is never sent twice in a row unchanged.
        transcripts.zipWithNext().forEach { (a, b) -> assertTrue(a.projection != b.projection) }

        // The streamed text, rebuilt from the deltas, is the answer.
        val streamed = StringBuilder()
        val frames = recorder.frames()
        for (frame in frames) {
            val live = frame.live ?: continue
            if (live.textFrom == 0) streamed.clear()
            assertEquals("delta offset", streamed.length, live.textFrom)
            streamed.append(live.text)
            assertEquals(live.textLength, streamed.length)
        }
        assertEquals(pieces.joinToString(""), streamed.toString())
        // Many small frames, few transcripts.
        assertTrue("${frames.size} frames", frames.count { it.live != null } >= 3)
        assertTrue("${transcripts.size} transcripts", transcripts.size < frames.size)
        assertTrue(recorder.events.filterIsInstance<Int>().all { it < 4_096 })
        // Frame counters are consecutive.
        assertEquals((1L..frames.size).toList(), frames.map { it.frame })

        // resync: the transcript again, as a new revision.
        val again = Recorder()
        val h2 = bridge.openStream("s1", "p", "m", again)
        withTimeout(5_000) { while (again.transcripts().isEmpty()) delay(10) }
        bridge.resync("s1")
        withTimeout(5_000) { while (again.transcripts().size < 2) delay(10) }
        assertEquals(2L, again.transcripts().last().revision)
        h2.cancel()
        bridge.shutdown { }
    }

    @Test
    fun theOldListenerStillGetsWholeStates() = runBlocking {
        val transport = SlowTransport({ textAnswer("hello", " there") }, lineDelayMs = 0)
        val bridge = AgentBridge(Files.createTempDirectory("ws").toString(), CountingDirectory(), transport, NoLocal, MemoryStore(), null, { null })
        val states = CopyOnWriteArrayList<SessionState>()
        val handle = bridge.open("s1", "p", "m") { states += json.decodeFromString(SessionState.serializer(), it) }
        withTimeout(5_000) { while (states.isEmpty()) delay(10) }
        bridge.send("s1", "hi", emptyList())
        withTimeout(10_000) {
            while (states.last().let { s -> s.running || s.projection.items.none { it is SessionItem.Assistant } }) delay(20)
        }
        handle.cancel()
        assertEquals("hello there", states.last().projection.items.filterIsInstance<SessionItem.Assistant>().single().text)
        bridge.shutdown { }
    }

    @Test
    fun idleAgentsBeyondFourAreClosedAndReopenOnUse() = runBlocking {
        val transport = SlowTransport({ textAnswer("ok") }, lineDelayMs = 0)
        val store = MemoryStore()
        val bridge = AgentBridge(Files.createTempDirectory("ws").toString(), CountingDirectory(), transport, NoLocal, store, null, { null })
        bridge.retain("s0")
        for (i in 0 until 7) {
            val recorder = Recorder()
            val handle = bridge.openStream("s$i", "p", "m", recorder)
            withTimeout(5_000) { while (recorder.frames().isEmpty()) delay(10) }
            handle.cancel()
        }
        withTimeout(5_000) { while (bridge.openSessions().size > AgentBridge.MAX_OPEN_AGENTS) delay(10) }
        val open = bridge.openSessions()
        assertEquals(AgentBridge.MAX_OPEN_AGENTS, open.size)
        // Retained: never closed. The most recently used ones stay.
        assertTrue(open.toString(), "s0" in open)
        assertTrue(open.toString(), "s6" in open)
        assertTrue(open.toString(), "s1" !in open)

        // An evicted session reopens from the store when used.
        bridge.send("s1", "hello again", emptyList())
        withTimeout(10_000) {
            while (store.load("s1").none { "turn/end" in it }) delay(20)
        }
        assertTrue("s1" in bridge.openSessions())
        bridge.release("s0")
        bridge.shutdown { }
    }

    @Test
    fun theModelAdapterIsBuiltOncePerProvider() = runBlocking {
        val answers = ArrayDeque(
            listOf(
                listOf(
                    data("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"glob","arguments":"{\"pattern\":\"*\"}"}}]}}]}"""),
                    data("""{"choices":[{"finish_reason":"tool_calls"}]}"""),
                    "data: [DONE]",
                ),
                listOf(
                    data("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c2","function":{"name":"glob","arguments":"{\"pattern\":\"*.md\"}"}}]}}]}"""),
                    data("""{"choices":[{"finish_reason":"tool_calls"}]}"""),
                    "data: [DONE]",
                ),
            ),
        )
        val transport = SlowTransport({ synchronized(answers) { answers.removeFirstOrNull() } ?: textAnswer("done") }, lineDelayMs = 0)
        val directory = CountingDirectory()
        val store = MemoryStore()
        val bridge = AgentBridge(Files.createTempDirectory("ws").toString(), directory, transport, NoLocal, store, null, { null })
        val recorder = Recorder()
        val handle = bridge.openStream("s1", "p", "m", recorder)
        withTimeout(5_000) { while (recorder.frames().isEmpty()) delay(10) }
        bridge.send("s1", "list files", emptyList())
        withTimeout(10_000) { while (store.load("s1").none { "turn/end" in it }) delay(20) }
        assertEquals(3, transport.requests.get())
        assertEquals(1, bridge.adapterBuildCount)

        bridge.invalidateProviders()
        bridge.send("s1", "again", emptyList())
        withTimeout(10_000) { while (store.load("s1").count { "turn/end" in it } < 2) delay(20) }
        assertEquals(2, bridge.adapterBuildCount)
        handle.cancel()
        bridge.shutdown { }
    }
}

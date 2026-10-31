package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.shared.cascade.CascadeRoutes
import dev.wckdboy.autobot.providers.remote.LocalModelState
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "phone first" chip the bridge hands iOS: on cloud chats and on the user's own node, as on Android. */
class AgentBridgeCascadeTest {

    /** Nothing is sent in these tests. */
    private object NoTransport : HttpTransport {
        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable = error("no requests here")
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

    private object Providers : ProviderDirectory {
        override fun provider(id: String): ProviderSpec? = when (id) {
            "cloud" -> ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Cloud", "https://api.example.com/v1", "k", "m")
            "node" -> ProviderSpec(id, ProviderKind.INVINCIBLE_NODE, "My node", ProviderPresets.NODE_BASE_URL, null, "auto")
            "phone" -> ProviderSpec(id, ProviderKind.LOCAL, "This phone", "local://", null, "qwen")
            "loopback" -> ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Ollama", "http://127.0.0.1:11434/v1", null, "qwen")
            else -> null
        }
    }

    private class Frames : SessionStreamListener {
        val frames = CopyOnWriteArrayList<LiveFrame>()
        override fun onTranscript(json: String) = Unit
        override fun onFrame(json: String) {
            frames += Json { ignoreUnknownKeys = true }.decodeFromString(LiveFrame.serializer(), json)
        }
    }

    /** A bridge on a workspace of its own; both are gone after [block]. */
    private fun withBridge(store: MemoryStore = MemoryStore(), block: suspend (AgentBridge) -> Unit) = runBlocking {
        val workspace = Files.createTempDirectory("ws").toFile()
        val bridge = AgentBridge(workspace.path, Providers, NoTransport, NoLocal, store, null, { null })
        try {
            block(bridge)
        } finally {
            val done = CompletableDeferred<Unit>()
            bridge.shutdown { done.complete(Unit) }
            withTimeout(5_000) { done.await() }
            workspace.deleteRecursively()
        }
    }

    /** The frame of a plain chat (tools off) on [provider]. */
    private suspend fun AgentBridge.frame(provider: String): LiveFrame {
        val frames = Frames()
        val handle = openStream(provider, provider, "m", frames)
        withTimeout(5_000) { while (frames.frames.isEmpty()) delay(10) }
        setToolsEnabled(provider, false)
        withTimeout(5_000) { while (frames.frames.last().toolsEnabled) delay(10) }
        return frames.frames.last().also { handle.cancel() }
    }

    /** The chip of a plain chat (tools off) on [provider]. */
    private suspend fun AgentBridge.chip(provider: String): String = frame(provider).cascade

    @Test
    fun aPlainChatOnTheUsersNodeHasThePhoneFirstChip() = withBridge { bridge ->
        bridge.setCascadeDefault("AUTO")
        assertEquals("AUTO", bridge.chip("cloud"))
        // The node is not a cloud route, but the cascade applies there (LocalFirstCascade's owned).
        assertEquals("AUTO", bridge.chip("node"))
        // On the phone or a loopback server there is nothing to cascade to.
        assertEquals("NONE", bridge.chip("phone"))
        assertEquals("NONE", bridge.chip("loopback"))
    }

    /**
     * Whether iOS shows the offer's "Ask": the rule the shared escalate applies (cascadeAsk: the
     * user's node, or a cloud model unless "Never"), not the chip, whose "NONE" iOS read as "not
     * Never" and so showed an "Ask" that did nothing.
     */
    @Test
    fun theOfferShowsOnlyWhereAskWouldSendIt() = withBridge { bridge ->
        bridge.setCascadeDefault("NEVER")
        val never = listOf("node", "cloud", "phone", "loopback").associateWith { bridge.frame(it) }
        assertEquals(mapOf("node" to true, "cloud" to false, "phone" to false, "loopback" to false), never.mapValues { it.value.cascadeAsk })
        assertEquals("NONE", never.getValue("phone").cascade)
        bridge.setCascadeDefault("ASK")
        assertEquals(
            mapOf("node" to true, "cloud" to true, "phone" to false, "loopback" to false),
            listOf("node", "cloud", "phone", "loopback").associateWith { bridge.frame(it).cascadeAsk },
        )
    }

    /**
     * The bridge's manual `/compact` goes through the cascade's check: under "Never" a plain cloud
     * chat's summary is not sent (NoTransport would fail it with another error if it were).
     */
    @Test
    fun underNeverAManualCompactOfACloudChatSendsNothing() {
        val store = MemoryStore()
        val history = listOf(
            SessionEvent.TurnStart(1),
            SessionEvent.UserMessage("What is the capital of France?"),
            SessionEvent.AssistantMessage(1, 1, "Paris."),
            SessionEvent.TurnEnd(1, TurnEndReason.COMPLETED),
            SessionEvent.TurnStart(2),
            SessionEvent.UserMessage("And of Italy?"),
            SessionEvent.AssistantMessage(2, 1, "Rome."),
            SessionEvent.TurnEnd(2, TurnEndReason.COMPLETED),
        )
        store.append("cloud", history.mapIndexed { i, e -> SessionJson.encodeToString(LogEntry.serializer(), LogEntry(i + 1L, 0L, e)) })
        withBridge(store) { bridge ->
            bridge.setCascadeDefault("NEVER")
            assertEquals("NEVER", bridge.chip("cloud"))
            bridge.compact("cloud")
            val end = withTimeout(5_000) {
                var found: SessionEvent.CompactionEnd? = null
                while (found == null) {
                    found = store.load("cloud").map { SessionJson.decodeFromString(LogEntry.serializer(), it).event }.filterIsInstance<SessionEvent.CompactionEnd>().firstOrNull()
                    if (found == null) delay(10)
                }
                found
            }
            assertEquals(CascadeRoutes.NOT_SENT_NEVER, end.error)
        }
    }

    @Test
    fun anAgentRunOnTheNodeHasNoChip() = withBridge { bridge ->
        bridge.setCascadeDefault("NEVER")
        val frames = Frames()
        val handle = bridge.openStream("run", "node", "auto", frames)
        withTimeout(5_000) { while (frames.frames.isEmpty()) delay(10) }
        assertEquals(true, frames.frames.last().toolsEnabled)
        assertEquals("NONE", frames.frames.last().cascade)
        handle.cancel()
    }
}

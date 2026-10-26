package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.LocalModelState
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
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

    /** The chip of a plain chat (tools off) on [provider]. */
    private suspend fun AgentBridge.chip(provider: String): String {
        val frames = Frames()
        val handle = openStream(provider, provider, "m", frames)
        withTimeout(5_000) { while (frames.frames.isEmpty()) delay(10) }
        setToolsEnabled(provider, false)
        withTimeout(5_000) { while (frames.frames.last().toolsEnabled) delay(10) }
        return frames.frames.last().cascade.also { handle.cancel() }
    }

    @Test
    fun aPlainChatOnTheUsersNodeHasThePhoneFirstChip() = runBlocking {
        val bridge = AgentBridge(Files.createTempDirectory("ws").toString(), Providers, NoTransport, NoLocal, MemoryStore(), null, { null })
        bridge.setCascadeDefault("AUTO")
        assertEquals("AUTO", bridge.chip("cloud"))
        // The node is not a cloud route, but the cascade applies there (LocalFirstCascade's owned).
        assertEquals("AUTO", bridge.chip("node"))
        // On the phone or a loopback server there is nothing to cascade to.
        assertEquals("NONE", bridge.chip("phone"))
        assertEquals("NONE", bridge.chip("loopback"))
        bridge.shutdown { }
    }

    @Test
    fun anAgentRunOnTheNodeHasNoChip() = runBlocking {
        val bridge = AgentBridge(Files.createTempDirectory("ws").toString(), Providers, NoTransport, NoLocal, MemoryStore(), null, { null })
        bridge.setCascadeDefault("NEVER")
        val frames = Frames()
        val handle = bridge.openStream("run", "node", "auto", frames)
        withTimeout(5_000) { while (frames.frames.isEmpty()) delay(10) }
        assertEquals(true, frames.frames.last().toolsEnabled)
        assertEquals("NONE", frames.frames.last().cascade)
        handle.cancel()
        bridge.shutdown { }
    }
}

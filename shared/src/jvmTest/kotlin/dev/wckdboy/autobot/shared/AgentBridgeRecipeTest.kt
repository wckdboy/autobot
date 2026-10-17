package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.LocalModelState
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.shared.recipes.RecipeCodec
import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipeRunStatus
import dev.wckdboy.autobot.shared.recipes.RecipeTemplates
import dev.wckdboy.autobot.shared.recipes.RecipeTrigger
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recipes through the bridge the iOS app uses: on-device only, one record per run, written to the chat. */
class AgentBridgeRecipeTest {

    private class Cloud : HttpTransport {
        val sent = CopyOnWriteArrayList<HttpRequestSpec>()
        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            sink.onFailure(false, "no network in this test")
            return Cancellable { }
        }
    }

    /** The on-device engine: answers every request with [answer]. */
    private class Phone(private val answer: String) : LocalEngine {
        val requests = CopyOnWriteArrayList<String>()
        override fun chat(modelId: String, requestJson: String, sink: LocalSink): Cancellable? {
            requests += requestJson
            sink.onContent(answer)
            sink.onResult("""{"finish_reason":"stop","usage":{"prompt_tokens":10,"completion_tokens":3}}""")
            return Cancellable { }
        }
        override fun mmproj(modelId: String): String? = null
        override fun readyModelCount() = 1
        override fun modelState(modelId: String) = LocalModelState.READY
        override fun systemModel(): SystemModelStatus? = null
        override fun systemChat(promptJson: String, sink: LocalSink): Cancellable? = null
    }

    private class MemoryStore : SessionStoreBridge {
        val lines = ConcurrentHashMap<String, MutableList<String>>()
        override fun load(sessionId: String): List<String> = lines[sessionId].orEmpty().toList()
        override fun append(sessionId: String, entries: List<String>) {
            lines.getOrPut(sessionId) { CopyOnWriteArrayList() }.addAll(entries)
        }
    }

    private val providers = object : ProviderDirectory {
        override fun provider(id: String) = when (id) {
            "local-this-phone" -> ProviderSpec(id, ProviderKind.LOCAL, "This phone", "local://", null, "qwen")
            "cloud" -> ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Cloud", "https://api.example.com/v1", "k", "big")
            else -> null
        }
    }

    private fun run(bridge: AgentBridge, session: String, provider: String, model: String): String = runBlocking {
        val done = CompletableDeferred<String>()
        val recipe = RecipeCodec.encode(RecipeTemplates.all(0)[2])
        bridge.runRecipe(session, provider, model, recipe, "No chats changed since the last run.", 100, false, 60_000) { done.complete(it) }
        withTimeout(15_000) { done.await() }
    }

    @Test
    fun aRecipeRunsOnTheOnDeviceModelAndIsWrittenToItsChat() {
        val phone = Phone("Today you planned a trip.")
        val store = MemoryStore()
        val cloud = Cloud()
        val bridge = AgentBridge(Files.createTempDirectory("recipes").toString(), providers, cloud, phone, store, null, { null })
        val record = RecipeCodec.decodeRecord(run(bridge, "chat-1", "local-this-phone", "qwen"))!!
        assertEquals(RecipeRunStatus.DONE, record.status)
        assertEquals("Today you planned a trip.", record.summary)
        assertEquals("chat-1", record.sessionId)
        assertEquals(RecipeTrigger.SCHEDULE, record.trigger)
        assertEquals(100L, record.startedAt)
        assertEquals(1, phone.requests.size)
        assertTrue(cloud.sent.isEmpty())
        // The chat holds the prompt, the answer and the turn's privacy receipt.
        val log = store.lines["chat-1"].orEmpty().joinToString("\n")
        assertTrue(log.contains("Write a short digest of my chats"))
        assertTrue(log.contains("Today you planned a trip."))
        assertTrue(log.contains("turn/receipt"))
    }

    @Test
    fun aCloudRouteNeverRunsARecipe() {
        val phone = Phone("never")
        val cloud = Cloud()
        val store = MemoryStore()
        val bridge = AgentBridge(Files.createTempDirectory("recipes").toString(), providers, cloud, phone, store, null, { null })
        val record = RecipeCodec.decodeRecord(run(bridge, "chat-2", "cloud", "big"))!!
        assertEquals(RecipeRunStatus.FAILED, record.status)
        assertEquals(RecipeCopy.ON_DEVICE_ONLY, record.summary)
        assertNull(record.sessionId)
        assertTrue(cloud.sent.isEmpty())
        assertTrue(phone.requests.isEmpty())
        assertNull(store.lines["chat-2"])
        // An unknown provider and an empty model are refused the same way.
        assertEquals(RecipeCopy.ON_DEVICE_ONLY, RecipeCodec.decodeRecord(run(bridge, "chat-3", "gone", "x"))!!.summary)
        assertEquals(RecipeCopy.ON_DEVICE_ONLY, RecipeCodec.decodeRecord(run(bridge, "chat-4", "local-this-phone", ""))!!.summary)
    }
}

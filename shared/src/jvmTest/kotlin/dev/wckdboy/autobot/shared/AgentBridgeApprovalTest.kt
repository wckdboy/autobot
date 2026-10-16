package dev.wckdboy.autobot.shared

import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.LocalModelState
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Approvals through the bridge the iOS app uses: prompts are listed per session and answered by
 * (session, call id). The bridge runs on real dispatchers; every wait is bounded.
 */
class AgentBridgeApprovalTest {

    /** Answers the first request of a chat with a `web_fetch` call `c1`, later ones with text. */
    private class Provider : HttpTransport {
        val sent = CopyOnWriteArrayList<HttpRequestSpec>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            sent += request
            val body = request.body.orEmpty()
            sink.onResponse(200, mapOf("content-type" to "text/event-stream"), null)
            val lines = if ("\"role\":\"tool\"" in body) {
                listOf(
                    """data: {"choices":[{"delta":{"content":"done"}}]}""",
                    """data: {"choices":[{"finish_reason":"stop"}]}""",
                )
            } else {
                listOf(
                    """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"web_fetch","arguments":"{\"url\":\"https://example.org/\"}"}}]}}]}""",
                    """data: {"choices":[{"finish_reason":"tool_calls"}]}""",
                )
            }
            (lines + "data: [DONE]").forEach(sink::onLine)
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
        override fun systemChat(promptJson: String, sink: LocalSink): Cancellable? = null
    }

    private class MemoryStore : SessionStoreBridge {
        private val lines = ConcurrentHashMap<String, MutableList<String>>()
        override fun load(sessionId: String): List<String> = lines[sessionId].orEmpty().toList()
        override fun append(sessionId: String, entries: List<String>) {
            lines.getOrPut(sessionId) { CopyOnWriteArrayList() }.addAll(entries)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private class Chat(val states: CopyOnWriteArrayList<SessionState>, val handle: Cancellable) {
        val last: SessionState? get() = states.lastOrNull()
    }

    private fun bridge(): AgentBridge {
        val dir = Files.createTempDirectory("approvals").toFile()
        val providers = object : ProviderDirectory {
            override fun provider(id: String) =
                ProviderSpec(id, ProviderKind.OPENAI_COMPATIBLE, "Test", "https://api.example.com/v1", "k", "m").takeIf { id == "p" }
        }
        return AgentBridge(dir.path, providers, Provider(), NoLocal, MemoryStore(), null, { null })
    }

    private fun AgentBridge.chat(id: String): Chat {
        val states = CopyOnWriteArrayList<SessionState>()
        val handle = open(id, "p", "m") { states += json.decodeFromString(SessionState.serializer(), it) }
        return Chat(states, handle)
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(10) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what", e)
        }
    }

    private fun Chat.tool(): SessionItem.Tool? = last?.projection?.items?.filterIsInstance<SessionItem.Tool>()?.firstOrNull()

    @Test
    fun twoChatsWaitingOnTheSameCallIdAreAnsweredSeparately() = runBlocking {
        val bridge = bridge()
        val a = bridge.chat("a")
        val b = bridge.chat("b")
        until("both chats open") { a.last != null && b.last != null }
        bridge.send("a", "fetch it", emptyList())
        bridge.send("b", "fetch it", emptyList())
        until("both prompts") { a.last?.approvals?.size == 1 && b.last?.approvals?.size == 1 }
        assertEquals("c1", a.last!!.approvals.single().callId)
        assertEquals(ToolState.WAITING_APPROVAL, a.tool()?.state)

        bridge.answerApproval("b", "c1", ApprovalOutcome.REJECTED)
        until("chat b finished") { b.last?.running == false && b.tool()?.state == ToolState.DENIED }
        assertEquals("chat a is untouched", 1, a.last!!.approvals.size)
        assertTrue(a.last!!.running)

        // Unknown and stale answers change nothing.
        bridge.answerApproval("b", "c1", ApprovalOutcome.ALLOWED_ONCE)
        bridge.answerApproval("a", "nope", ApprovalOutcome.ALLOWED_ONCE)
        bridge.answerApproval("zzz", "c1", ApprovalOutcome.ALLOWED_ONCE)
        delay(100)
        assertEquals(1, a.last!!.approvals.size)

        bridge.answerApproval("a", "c1", ApprovalOutcome.REJECTED)
        until("chat a finished") { a.last?.running == false && a.last?.approvals?.isEmpty() == true }
        assertEquals(ToolState.DENIED, a.tool()?.state)
        a.handle.cancel()
        b.handle.cancel()
    }

    @Test
    fun cancellingWithAPromptOpenWithdrawsItAndALateAnswerIsIgnored() = runBlocking {
        val bridge = bridge()
        val a = bridge.chat("a")
        until("open") { a.last != null }
        bridge.send("a", "fetch it", emptyList())
        until("prompt") { a.last?.approvals?.size == 1 }

        bridge.cancel("a")
        until("stopped") { a.last?.running == false && a.last?.approvals?.isEmpty() == true }
        val tool = a.tool()!!
        assertEquals(ToolState.ERROR, tool.state)
        assertEquals("ABORTED_BEFORE_DISPATCH", tool.code)
        assertTrue(a.last!!.projection.items.any { it is SessionItem.Notice && it.text == "stopped" })

        bridge.answerApproval("a", "c1", ApprovalOutcome.ALLOWED_ONCE)
        delay(100)
        assertFalse(a.last!!.running)
        a.handle.cancel()
    }
}

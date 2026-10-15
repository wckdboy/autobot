package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import dev.wckdboy.autobot.agent.runtime.workspace.FileTools
import dev.wckdboy.autobot.agent.runtime.workspace.Workspace
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ChatProvider
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.LocalLlmCodec
import dev.wckdboy.autobot.providers.remote.ModelListResult
import java.io.File
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Model- and web-controlled input must not pin a core forever, and a stopped engine stops the turn. */
class RunawayInputTest {

    private val run = object : ToolRunContext {
        override val callId = "c"
        override val sessionId = "s"
        override fun deferContext(text: String) = Unit
        override suspend fun log(event: SessionEvent) = Unit
        override suspend fun askUser(question: UserQuestion): List<String>? = null
    }

    private fun grep(dir: File) = FileTools(Workspace(dir.toOkioPath())).all.single { it.name == "grep" }

    private fun catastrophic(): File = Files.createTempDirectory("redos").toFile().also {
        // (.*x){8}y against a run of x without a y backtracks for ages (n^8), even with the JDK's loop memoization.
        File(it, "a.txt").writeText("x".repeat(200) + "\n")
    }

    @Test
    fun catastrophicGrepIsCancellable() = runBlocking {
        val tool = grep(catastrophic())
        val started = TimeSource.Monotonic.markNow()
        val job = async(Dispatchers.Default) { tool.execute(JsonObject(mapOf("pattern" to JsonPrimitive("(.*x){8}y"))), run) }
        delay(300)
        job.cancel()
        try {
            withTimeout(5_000) { job.await() }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue("stopped promptly", started.elapsedNow() < 5.seconds)
    }

    @Test
    fun catastrophicGrepIsTimeLimited() = runBlocking {
        val tool = grep(catastrophic())
        val output = withTimeout(FileTools.GREP_BUDGET_MS + 10_000) {
            tool.execute(JsonObject(mapOf("pattern" to JsonPrimitive("(.*x){8}y"))), run)
        }
        assertEquals("TIMEOUT", output.code)
    }

    @Test
    fun pathologicalHtmlFallsBackToPlainStripping() {
        // Thousands of unclosed <script> tags make the lazy back-referencing pass quadratic.
        val html = "<p>start</p>" + "<script>".repeat(40_000) + "text"
        val started = TimeSource.Monotonic.markNow()
        val text = WebFetchTool.htmlToText(html)
        assertTrue(text, text.contains("start"))
        assertTrue("bounded", started.elapsedNow() < 30.seconds)
    }

    @Test
    fun aCancelledEngineResultStopsTheTurnInsteadOfEndingTheAnswer() = runBlocking {
        val events = LocalLlmCodec.decodeResult("""{"error":{"code":"CANCELLED","message":"cancelled"}}""")
        assertEquals("CANCELLED", (events.single() as ChatEvent.Error).code)

        val engine = object : ChatProvider {
            override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flowOf(ChatEvent.ContentDelta("partial"), *events.toTypedArray())
            override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.Success(0)
            override suspend fun listModels(): ModelListResult = ModelListResult.Success(emptyList())
        }
        val adapter = ProviderLlmAdapter("local", engine, ProviderKind.LOCAL)
        val seen = mutableListOf<StreamChunk>()
        try {
            adapter.stream(LlmCall(LlmCallConfig("local", "m"), emptyList(), emptyList())).toList(seen)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf<StreamChunk>(StreamChunk.TextDelta("partial")), seen)
    }
}

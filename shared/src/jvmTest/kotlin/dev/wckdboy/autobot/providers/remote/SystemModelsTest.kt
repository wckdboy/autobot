package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.agent.runtime.ProviderLlmAdapter
import dev.wckdboy.autobot.core.data.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemModelsTest {

    private val apple = SystemModelStatus(SystemModels.APPLE, "Apple Intelligence (on-device)", SystemAvailability.AVAILABLE)
    private val appleOff = apple.copy(availability = SystemAvailability.UNAVAILABLE, detail = "Apple Intelligence is turned off in Settings")

    // ---- resolver ----

    @Test
    fun readyModelRunsItself() {
        assertEquals(LocalPlan.Gguf("qwen"), LocalModelResolver.resolve("qwen", LocalModelState.READY, apple))
        assertEquals(LocalPlan.Gguf("qwen"), LocalModelResolver.resolve("qwen", LocalModelState.READY, null))
    }

    @Test
    fun missingModelFallsBackToTheSystemModel() {
        val plan = LocalModelResolver.resolve("qwen", LocalModelState.NOT_INSTALLED, apple)
        assertEquals(LocalPlan.System(SystemModels.APPLE, "'qwen' is not installed"), plan)
    }

    @Test
    fun noModelAtAllFallsBackToo() {
        val plan = LocalModelResolver.resolve("", LocalModelState.NOT_INSTALLED, apple) as LocalPlan.System
        assertEquals("no model is installed", plan.fallback)
    }

    @Test
    fun missingModelWithoutSystemModelExplainsBoth() {
        val plan = LocalModelResolver.resolve("qwen", LocalModelState.NOT_INSTALLED, appleOff) as LocalPlan.Fail
        assertEquals(
            "'qwen' is not installed. Download a chat model in the Models tab. Apple Intelligence (on-device) is not available either: " +
                "unavailable · Apple Intelligence is turned off in Settings.",
            plan.message,
        )
        val bare = LocalModelResolver.resolve("qwen", LocalModelState.NOT_INSTALLED, null) as LocalPlan.Fail
        assertEquals("'qwen' is not installed. Download a chat model in the Models tab.", bare.message)
    }

    @Test
    fun tooBigPrefersTheSystemModelButStillTriesWithoutOne() {
        val plan = LocalModelResolver.resolve("big", LocalModelState.TOO_BIG, apple) as LocalPlan.System
        assertEquals("'big' is too big for this phone's memory", plan.fallback)
        assertEquals(LocalPlan.Gguf("big"), LocalModelResolver.resolve("big", LocalModelState.TOO_BIG, appleOff))
        assertEquals(LocalPlan.Gguf("big"), LocalModelResolver.resolve("big", LocalModelState.TOO_BIG, null))
    }

    @Test
    fun loadFailureFallsBackOrSaysWhatToDo() {
        val plan = LocalModelResolver.resolve("qwen", LocalModelState.READY, apple, loadError = "out of memory") as LocalPlan.System
        assertEquals("'qwen' failed to load (out of memory)", plan.fallback)
        val fail = LocalModelResolver.resolve("qwen", LocalModelState.READY, null, loadError = "out of memory") as LocalPlan.Fail
        assertEquals("'qwen' failed to load (out of memory). Try a smaller model or a shorter context.", fail.message)
        // A too-big model that also failed to load is not retried on itself.
        assertTrue(LocalModelResolver.resolve("big", LocalModelState.TOO_BIG, null, loadError = "oom") is LocalPlan.Fail)
    }

    @Test
    fun requestedSystemModel() {
        assertEquals(LocalPlan.System(SystemModels.APPLE, null), LocalModelResolver.resolve(SystemModels.APPLE, LocalModelState.NOT_INSTALLED, apple))
        val off = LocalModelResolver.resolve(SystemModels.APPLE, LocalModelState.NOT_INSTALLED, appleOff) as LocalPlan.Fail
        assertTrue(off.message, off.message.startsWith("Apple Intelligence (on-device) is not available: unavailable · Apple Intelligence is turned off"))
        val downloading = apple.copy(id = SystemModels.GEMINI_NANO, title = "Gemini Nano (on-device)", availability = SystemAvailability.DOWNLOADING)
        val wait = LocalModelResolver.resolve(SystemModels.GEMINI_NANO, LocalModelState.NOT_INSTALLED, downloading) as LocalPlan.Fail
        assertTrue(wait.message, "downloading" in wait.message)
        // A conversation from the other platform (imported) asks for a model this OS does not have.
        val other = LocalModelResolver.resolve(SystemModels.GEMINI_NANO, LocalModelState.NOT_INSTALLED, apple) as LocalPlan.Fail
        assertEquals("Gemini Nano (on-device) is not available on this device", other.message)
    }

    @Test
    fun loadFailureCodes() {
        assertTrue(LocalModelResolver.isLoadFailure(ChatEvent.Error(ErrorKind.BAD_REQUEST, "x", code = "LOAD_FAILED")))
        assertTrue(!LocalModelResolver.isLoadFailure(ChatEvent.Error(ErrorKind.BAD_REQUEST, "x", code = "CONTEXT_WINDOW_EXCEEDED")))
        assertTrue(!LocalModelResolver.isLoadFailure(ChatEvent.Error(ErrorKind.BAD_REQUEST, "x")))
        // The codec carries the engine's code.
        val decoded = LocalLlmCodec.decodeResult("""{"error":{"code":"LOAD_FAILED","message":"could not load model"}}""").single() as ChatEvent.Error
        assertEquals("LOAD_FAILED", decoded.code)
    }

    // ---- router ----

    private class FakeSystem(var status: SystemModelStatus, private val answer: List<ChatEvent> = listOf(ChatEvent.ContentDelta("hi"), ChatEvent.Done)) : SystemLlm {
        val prompts = mutableListOf<SystemPrompt>()
        override val id: String get() = status.id
        override suspend fun status() = status
        override fun chat(prompt: SystemPrompt): Flow<ChatEvent> {
            prompts += prompt
            return flowOf(*answer.toTypedArray())
        }
    }

    private fun request(model: String) = ChatRequest(model, listOf(ChatMessage(ChatRole.USER, "hello")))

    private fun run(router: LocalChatRouter, model: String) = runBlocking { router.stream(request(model)).toList() }

    @Test
    fun routerFallsBackWhenTheModelFailsToLoad() {
        val system = FakeSystem(apple)
        val router = LocalChatRouter(
            modelState = { LocalModelState.READY },
            system = system,
            gguf = { flowOf(ChatEvent.Error(ErrorKind.BAD_REQUEST, "could not load model", code = "LOAD_FAILED")) },
        )
        val events = run(router, "qwen")
        assertEquals(ChatEvent.ServedBy(SystemModels.APPLE, "'qwen' failed to load (could not load model)"), events.first())
        assertEquals(listOf(ChatEvent.ContentDelta("hi"), ChatEvent.Done), events.drop(1))
        assertEquals("hello", system.prompts.single().prompt)
    }

    @Test
    fun routerKeepsLoadErrorsWhenNothingCanStandIn() {
        val router = LocalChatRouter(
            modelState = { LocalModelState.READY },
            system = FakeSystem(appleOff),
            gguf = { flowOf(ChatEvent.Error(ErrorKind.BAD_REQUEST, "could not load model", code = "LOAD_FAILED")) },
        )
        val error = run(router, "qwen").single() as ChatEvent.Error
        assertEquals("LOAD_FAILED", error.code)
        assertTrue(error.message, error.message.startsWith("'qwen' failed to load (could not load model). Try a smaller model"))
    }

    @Test
    fun routerDoesNotRetryAfterOutputOrOnOtherErrors() {
        val system = FakeSystem(apple)
        val midway = LocalChatRouter(
            modelState = { LocalModelState.READY },
            system = system,
            gguf = { flowOf(ChatEvent.ContentDelta("par"), ChatEvent.Error(ErrorKind.BAD_REQUEST, "died", code = "ENGINE_DIED")) },
        )
        assertEquals(listOf(ChatEvent.ContentDelta("par"), ChatEvent.Error(ErrorKind.BAD_REQUEST, "died", code = "ENGINE_DIED")), run(midway, "qwen"))
        val overflow = LocalChatRouter(
            modelState = { LocalModelState.READY },
            system = system,
            gguf = { flowOf(ChatEvent.Error(ErrorKind.BAD_REQUEST, "context length exceeded: x", code = "CONTEXT_WINDOW_EXCEEDED")) },
        )
        assertEquals("CONTEXT_WINDOW_EXCEEDED", (run(overflow, "qwen").single() as ChatEvent.Error).code)
        assertTrue(system.prompts.isEmpty())
    }

    @Test
    fun routerRunsTheSystemModelDirectlyWithoutAFallbackLabel() {
        val system = FakeSystem(apple)
        val router = LocalChatRouter(modelState = { error("not asked for system ids") }, system = system, gguf = { error("no gguf") })
        assertEquals(listOf(ChatEvent.ContentDelta("hi"), ChatEvent.Done), run(router, SystemModels.APPLE))
    }

    @Test
    fun routerFallsBackForMissingModelsAndFailsWithoutSystemModel() {
        val withSystem = LocalChatRouter(modelState = { LocalModelState.NOT_INSTALLED }, system = FakeSystem(apple), gguf = { error("no gguf") })
        assertEquals(ChatEvent.ServedBy(SystemModels.APPLE, "'gone' is not installed"), run(withSystem, "gone").first())
        val without = LocalChatRouter(modelState = { LocalModelState.NOT_INSTALLED }, system = null, gguf = { error("no gguf") })
        val error = run(without, "gone").single() as ChatEvent.Error
        assertEquals("'gone' is not installed. Download a chat model in the Models tab.", error.message)
    }

    // ---- prompt mapping ----

    @Test
    fun mapsMessagesToTurns() {
        val p = SystemPrompt.from(
            ChatRequest(
                model = SystemModels.APPLE,
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, "You are Autobot."),
                    ChatMessage(ChatRole.SYSTEM, "Workspace: /workspace"),
                    ChatMessage(ChatRole.USER, "look", images = listOf("/tmp/a.png")),
                    ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(ToolCall("c1", "read", """{"path":"a"}"""))),
                    ChatMessage(ChatRole.TOOL, "file text", toolCallId = "c1"),
                    ChatMessage(ChatRole.ASSISTANT, "It says file text."),
                    ChatMessage(ChatRole.USER, "thanks"),
                    ChatMessage(ChatRole.USER, "and summarise"),
                ),
                temperature = 0.3,
                maxTokens = 4000,
                tools = listOf(ToolSpec("read", "Read a file", "{}")),
            ),
        )
        assertEquals("You are Autobot.\n\nWorkspace: /workspace", p.instructions)
        assertEquals(
            listOf(
                SystemPrompt.Turn("user", "look\n[1 image(s) attached, not visible to this model]"),
                SystemPrompt.Turn("assistant", """[called tool read with {"path":"a"}]"""),
                SystemPrompt.Turn("user", "[tool result]\nfile text"),
                SystemPrompt.Turn("assistant", "It says file text."),
            ),
            p.history,
        )
        assertEquals("thanks\n\nand summarise", p.prompt)
        assertEquals(0.3, p.temperature)
        assertEquals(SystemModels.MAX_OUTPUT_TOKENS, p.maxTokens)
    }

    @Test
    fun trailingToolResultBecomesThePrompt() {
        val p = SystemPrompt.from(
            ChatRequest(
                "m",
                listOf(
                    ChatMessage(ChatRole.USER, "read a"),
                    ChatMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(ToolCall("c1", "read", "{}"))),
                    ChatMessage(ChatRole.TOOL, "contents", toolCallId = "c1"),
                ),
            ),
        )
        assertEquals("[tool result]\ncontents", p.prompt)
        assertEquals(listOf("user", "assistant"), p.history.map { it.role })
        assertNull(p.instructions)
    }

    @Test
    fun trimsToTheContextNewestFirst() {
        val long = "x".repeat(2_000)
        val messages = listOf(ChatMessage(ChatRole.SYSTEM, "s".repeat(20_000))) +
            (1..10).flatMap { listOf(ChatMessage(ChatRole.USER, "q$it $long"), ChatMessage(ChatRole.ASSISTANT, "a$it $long")) } +
            ChatMessage(ChatRole.USER, "last")
        val p = SystemPrompt.from(ChatRequest("m", messages, maxTokens = 1024))
        val budget = (SystemModels.CONTEXT_TOKENS - 1024) * 3
        assertTrue(p.instructions!!.length <= budget / 3)
        assertTrue(p.instructions!!.endsWith("[…shortened]"))
        val used = p.instructions!!.length + p.prompt.length + p.history.sumOf { it.text.length }
        assertTrue("used $used of $budget", used <= budget)
        assertEquals("last", p.prompt)
        assertTrue(p.history.isNotEmpty())
        assertEquals("user", p.history.first().role)
        assertTrue(p.history.last().text.startsWith("a10 "))
    }

    @Test
    fun flattensForSinglePromptApis() {
        val p = SystemPrompt("Be brief.", listOf(SystemPrompt.Turn("user", "hi"), SystemPrompt.Turn("assistant", "hello")), "bye")
        assertEquals("Be brief.\n\nUser: hi\n\nAssistant: hello\n\nUser: bye\n\nAssistant:", p.flatten())
    }

    @Test
    fun systemModelsAreLocalWithSmallContextAndNoTools() {
        val info = ProviderLlmAdapter.modelInfo(ProviderKind.LOCAL, SystemModels.GEMINI_NANO)
        assertEquals(SystemModels.CONTEXT_TOKENS, info.contextWindow)
        assertEquals(false, info.supportsTools)
        assertEquals("Gemini Nano (on-device) · system model · fallback — 'q' is not installed", SystemModels.fallbackNotice(SystemModels.GEMINI_NANO, "'q' is not installed"))
    }
}

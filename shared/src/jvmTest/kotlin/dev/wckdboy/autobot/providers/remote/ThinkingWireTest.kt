package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.ModelMessage
import dev.wckdboy.autobot.agent.core.llm.ModelRole
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.agent.core.llm.ToolSchema
import dev.wckdboy.autobot.agent.core.session.ToolCallRecord
import dev.wckdboy.autobot.agent.runtime.ProviderLlmAdapter
import dev.wckdboy.autobot.core.data.model.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Think switch on every wire: what each provider receives for off, auto and on. */
class ThinkingWireTest {
    private val on = ThinkingSetting(ThinkingMode.ON)
    private val on2k = ThinkingSetting(ThinkingMode.ON, 2048)
    private val tools = listOf(ToolSpec("t", "d", "{}"))

    private fun req(model: String, thinking: ThinkingSetting?, withTools: Boolean = false) =
        ChatRequest(model, listOf(ChatMessage(ChatRole.USER, "hi")), tools = if (withTools) tools else emptyList(), thinking = thinking)

    private fun body(preset: String, model: String, thinking: ThinkingSetting?, withTools: Boolean = false): JsonObject =
        Json.parseToJsonElement(ProviderPresets.byId(preset)!!.wire(model).encode(req(model, thinking, withTools))).jsonObject

    private val thinkingKeys = listOf("reasoning_effort", "reasoning", "chat_template_kwargs", "reasoning_budget_tokens", "enable_thinking", "thinking_budget", "thinking", "output_config")

    private fun JsonObject.noThinking() = thinkingKeys.none { it in this }

    @Test
    fun autoSendsNothingAnywhere() {
        for (preset in ProviderPresets.all.filter { it.kind != ProviderKind.LOCAL }) {
            val model = preset.defaultModel.ifEmpty { "qwen3-8b" }
            for (setting in listOf(null, ThinkingSetting.AUTO)) {
                assertTrue("${preset.id} $model", body(preset.id, model, setting).noThinking())
            }
        }
    }

    @Test
    fun providersWithoutAControlNeverGetAnUnknownField() {
        // Mistral, xAI, Cohere, OpenCode Go (no documented field), LM Studio, Ollama, custom servers…
        for (preset in listOf("mistral", "xai", "cohere", "perplexity", ProviderPresets.OPENCODE_GO, "lmstudio", ProviderPresets.OLLAMA, ProviderPresets.CUSTOM, "together")) {
            for (setting in listOf(ThinkingSetting.OFF, on2k)) {
                assertTrue(preset, body(preset, "kimi-k2.6", setting).noThinking())
            }
        }
    }

    @Test
    fun openAi() {
        assertEquals("none", body("openai", "gpt-6-luna", ThinkingSetting.OFF)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("minimal", body("openai", "gpt-5", ThinkingSetting.OFF)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("medium", body("openai", "gpt-6-astra", on2k)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("high", body("openai", "gpt-6-astra", on)["reasoning_effort"]!!.jsonPrimitive.content)
        // Tool turns on Sol / Luna must stay at none, whatever the chip says.
        assertEquals("none", body("openai", "gpt-6-sol", on, withTools = true)["reasoning_effort"]!!.jsonPrimitive.content)
        // Not a reasoning model: nothing.
        assertTrue(body("openai", "gpt-4.1", on).noThinking())
    }

    @Test
    fun openRouterUsesItsReasoningObject() {
        assertFalse(body("openrouter", "z-ai/glm-5.3", ThinkingSetting.OFF)["reasoning"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(2048, body("openrouter", "anthropic/claude-sonnet-5.5", on2k)["reasoning"]!!.jsonObject["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("high", body("openrouter", "deepseek/deepseek-v4.1-flash", on)["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun selfHostedServersGetTheTemplateSwitch() {
        assertFalse(body("vllm", "Qwen/Qwen3-8B", ThinkingSetting.OFF)["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
        val llama = body("llamacpp", "qwen3.5-9b", on2k)
        assertTrue(llama["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals(2048, llama["reasoning_budget_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun qwenZaiDeepSeekGroqGemini() {
        val qwen = body("qwen", "qwen3-max", on2k)
        assertTrue(qwen["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals(2048, qwen["thinking_budget"]!!.jsonPrimitive.int)
        assertFalse(body("qwen", "qwen3-max", ThinkingSetting.OFF)["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals("disabled", body("zai", "glm-5.3", ThinkingSetting.OFF)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("enabled", body("deepseek", "deepseek-chat", on)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(body("deepseek", "deepseek-reasoner", ThinkingSetting.OFF).noThinking())
        assertEquals("none", body("groq", "qwen/qwen3-32b", ThinkingSetting.OFF)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("low", body("gemini", "gemini-3.8-flash", ThinkingSetting.OFF)["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun anthropicPerModelFamily() {
        // Opus 5.5 cannot turn thinking off: low effort is as far as it goes.
        val opusOff = body("anthropic", "claude-opus-5-5", ThinkingSetting.OFF)
        assertNull(opusOff["thinking"])
        assertEquals("low", opusOff["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        // Sonnet 5.5 turns it off with between_tools; Opus 5 / 4.x with disabled.
        assertEquals("between_tools", body("anthropic", "claude-sonnet-5-5", ThinkingSetting.OFF)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("disabled", body("anthropic", "claude-opus-5", ThinkingSetting.OFF)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        // On: adaptive with a summarized trace and an effort from the budget (no budget_tokens: refused there).
        val sonnetOn = body("anthropic", "claude-sonnet-5-5", on2k)
        assertEquals("adaptive", sonnetOn["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("medium", sonnetOn["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse("budget_tokens" in sonnetOn["thinking"]!!.jsonObject)
        // Haiku 4.5 takes a token budget, kept below max_tokens.
        val haiku = body("anthropic", "claude-haiku-4-5", on2k)
        assertEquals(2048, haiku["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
        assertTrue(haiku["max_tokens"]!!.jsonPrimitive.int > 2048)
        assertTrue(body("anthropic", "claude-haiku-4-5", ThinkingSetting.OFF).noThinking())
        // Models that refuse sampling parameters never get a temperature.
        val withTemp = AnthropicCodec.encodeRequest(ChatRequest("claude-opus-5-5", listOf(ChatMessage(ChatRole.USER, "hi")), temperature = 0.2))
        assertFalse("temperature" in withTemp)
        val sonnet46 = AnthropicCodec.encodeRequest(ChatRequest("claude-sonnet-4-6", listOf(ChatMessage(ChatRole.USER, "hi")), temperature = 0.2))
        assertTrue("temperature" in sonnet46)
    }

    // ---- on this device ----------------------------------------------------------------------

    private fun local(thinking: ThinkingSetting?, quick: Boolean, paramsB: Double?) =
        LocalLlmCodec.encode(req("m", thinking, withTools = !quick), paramsB = paramsB)

    @Test
    fun localAutoIsOffForQuickChatOnTinyModelsAndBudgetedForAgents() {
        val chat = local(null, quick = true, paramsB = 0.8)
        assertFalse(chat["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals(2048, chat["max_tokens"]!!.jsonPrimitive.int)
        assertNull(chat["reasoning_budget"])

        val agent = local(null, quick = false, paramsB = 0.8)
        assertTrue(agent["enable_thinking"]!!.jsonPrimitive.boolean)
        assertEquals(1024, agent["reasoning_budget"]!!.jsonPrimitive.int)
        // The answer keeps its 2048 tokens; the reasoning comes on top.
        assertEquals(2048 + 1024, agent["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(1024, agent["min_answer_tokens"]!!.jsonPrimitive.int)
    }

    @Test
    fun localOnAndOff() {
        assertEquals(128, local(ThinkingSetting(ThinkingMode.ON, 128), quick = true, paramsB = 0.8)["reasoning_budget"]!!.jsonPrimitive.int)
        assertFalse(local(ThinkingSetting.OFF, quick = false, paramsB = 27.0)["enable_thinking"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun modelSizeFromNameOrFile() {
        assertEquals(0.8, LocalModelSize.paramsB("catalog:qwen3.5-0.8b-q4_0")!!, 1e-9)
        assertEquals(30.0, LocalModelSize.paramsB("Qwen3-30B-A3B-Instruct")!!, 1e-9)
        assertEquals(20.0, LocalModelSize.paramsB("hf:unsloth/gpt-oss-20b-GGUF:gpt-oss-20b-Q4_K_M.gguf")!!, 1e-9)
        assertEquals(0.27, LocalModelSize.paramsB("gemma-3-270m-it-q8_0")!!, 1e-9)
        assertEquals(2.0, LocalModelSize.paramsB("my-finetune", fileBytes = 1_500_000_000L)!!, 1e-9)
        assertNull(LocalModelSize.paramsB("my-finetune"))
    }

    @Test
    fun engineProgressAndStopsDecode() {
        assertEquals(ChatEvent.ReasoningStatus(64, false, null), LocalLlmCodec.decodeStatus("""{"tokens":64,"done":false}"""))
        assertNull(LocalLlmCodec.decodeStatus("nonsense"))
        val events = LocalLlmCodec.decodeResult(
            """{"content":"17:25","reasoning":"r","tool_calls":[],"finish_reason":"stop","reasoning_stop":"budget",
               "usage":{"prompt_tokens":10,"completion_tokens":150,"reasoning_tokens":140}}""",
        )
        assertTrue(events.contains(ChatEvent.ReasoningStatus(140, true, "budget")))
        assertEquals(140, events.filterIsInstance<ChatEvent.Usage>().single().reasoningTokens)
    }

    // ---- history: what reasoning goes back ---------------------------------------------------

    private class Capture : ChatProvider {
        val requests = mutableListOf<ChatRequest>()
        override fun streamChat(request: ChatRequest): Flow<ChatEvent> {
            requests += request
            return flowOf(ChatEvent.ContentDelta("ok"), ChatEvent.ReasoningStatus(3, true, "loop"), ChatEvent.Finish("stop"), ChatEvent.Done)
        }

        override suspend fun testConnection() = ConnectionTestResult.Success(1)
    }

    private val history = listOf(
        ModelMessage(ModelRole.SYSTEM, "sys"),
        ModelMessage(ModelRole.USER, "earlier"),
        ModelMessage(ModelRole.ASSISTANT, "old answer", reasoning = "old thoughts"),
        ModelMessage(ModelRole.USER, "now"),
        ModelMessage(ModelRole.ASSISTANT, "", reasoning = "plan the call", toolCalls = listOf(ToolCallRecord("c1", "read", "{}")), inCurrentTurn = true),
        ModelMessage(ModelRole.TOOL, "result", toolCallId = "c1", toolName = "read"),
    )

    private fun send(kind: ProviderKind, thinking: ThinkingSetting?): Pair<ChatRequest, List<StreamChunk>> {
        val capture = Capture()
        val adapter = ProviderLlmAdapter("p", capture, kind)
        val chunks = runBlocking {
            adapter.stream(LlmCall(LlmCallConfig("p", "m", thinking = thinking), history, listOf(ToolSchema("read", "d", JsonObject(emptyMap()))))).toList()
        }
        return capture.requests.single() to chunks
    }

    @Test
    fun localModelsGetOnlyTheCurrentTurnsToolStepReasoning() {
        val (request, chunks) = send(ProviderKind.LOCAL, on)
        val reasonings = request.messages.filter { it.role == ChatRole.ASSISTANT }.map { it.reasoning }
        assertEquals(listOf(null, "plan the call"), reasonings)
        assertEquals(on, request.thinking)
        // The encoded request carries it as reasoning_content (Qwen3 templates render it in tool loops).
        val encoded = LocalLlmCodec.encode(request)["messages"]!!.jsonArray
        assertEquals(listOf("plan the call"), encoded.mapNotNull { (it as JsonObject)["reasoning_content"]?.jsonPrimitive?.content })
        assertTrue(chunks.contains(StreamChunk.ReasoningStatus(3, true, "loop")))
    }

    @Test
    fun remoteModelsThatDoNotEchoNeverSeeReasoning() {
        val (request, _) = send(ProviderKind.OPENAI_COMPATIBLE, ThinkingSetting.OFF)
        assertTrue(request.messages.all { it.reasoning == null })
        assertEquals(ThinkingSetting.OFF, request.thinking)
    }

    // ---- capability and defaults -------------------------------------------------------------

    @Test
    fun supportFromTemplateOrName() {
        assertEquals(ThinkingSupport.SWITCH, ThinkingSupport.fromTemplate("{% if enable_thinking is false %}<think>\n\n</think>{% endif %}"))
        assertEquals(ThinkingSupport.ALWAYS, ThinkingSupport.fromTemplate("<｜Assistant｜><think>\n"))
        assertEquals(ThinkingSupport.NONE, ThinkingSupport.fromTemplate("<start_of_turn>model\n"))
        assertNull(ThinkingSupport.fromTemplate(null))
        assertEquals(ThinkingSupport.SWITCH, ThinkingSupport.byName(ProviderKind.LOCAL, "catalog:qwen3.5-0.8b-q4_0"))
        assertEquals(ThinkingSupport.ALWAYS, ThinkingSupport.byName(ProviderKind.LOCAL, "DeepSeek-R1-Distill-Qwen-1.5B"))
        assertEquals(ThinkingSupport.NONE, ThinkingSupport.byName(ProviderKind.LOCAL, "gemma-3-270m"))
        assertEquals(ThinkingSupport.SWITCH, ThinkingSupport.byName(ProviderKind.ANTHROPIC, "claude-opus-5-5"))
        assertEquals(ThinkingSupport.NONE, ThinkingSupport.byName(ProviderKind.OPENAI_COMPATIBLE, "gpt-4.1-mini"))
    }

    @Test
    fun perModelDefaultsRoundTrip() {
        var text = ThinkingDefaults.with(null, "local", "qwen3.5", ThinkingSetting.OFF)
        text = ThinkingDefaults.with(text, "anthropic", "claude-opus-5-5", ThinkingSetting(ThinkingMode.ON, 4096))
        assertEquals(ThinkingSetting.OFF, ThinkingDefaults.get(text, "local", "qwen3.5"))
        assertEquals(ThinkingSetting(ThinkingMode.ON, 4096), ThinkingDefaults.get(text, "anthropic", "claude-opus-5-5"))
        // Auto is the default: setting it removes the entry.
        text = ThinkingDefaults.with(text, "local", "qwen3.5", ThinkingSetting.AUTO)
        assertNull(ThinkingDefaults.get(text, "local", "qwen3.5"))
        assertEquals(1, ThinkingDefaults.decode(text).size)
        assertTrue(ThinkingDefaults.decode("garbage\n\tON").isEmpty())
    }

    @Test
    fun labels() {
        assertEquals("think · auto", ThinkingLabels.chip(ThinkingSetting.AUTO))
        assertEquals("think · 1k", ThinkingLabels.chip(ThinkingSetting(ThinkingMode.ON, 1024)))
        assertEquals("thinking · 412 tokens", ThinkingLabels.row(true, 412, null))
        assertEquals("thought · 140 tokens · stopped at budget", ThinkingLabels.row(false, 140, "budget"))
        assertEquals(ThinkingSetting(ThinkingMode.ON), ThinkingLabels.next(ThinkingSetting.AUTO))
        assertEquals(ThinkingSetting.OFF, ThinkingLabels.next(ThinkingSetting(ThinkingMode.ON, 512)))
        assertEquals(ThinkingSetting.AUTO, ThinkingLabels.next(ThinkingSetting.OFF))
        assertTrue(JsonArray(emptyList()).isEmpty())
    }
}

package dev.wckdboy.autobot.providers.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every models-list parser against trimmed real responses (`src/jvmTest/resources/models`).
 * Live responses: OpenRouter, DeepInfra, Cerebras, SambaNova, Hugging Face, Venice, Novita.
 * Official docs examples: xAI, DeepSeek, MiniMax, llama.cpp, Gemini/Anthropic/Cohere/Mistral/
 * OpenAI/Qwen/Ollama (schema examples filled with documented ids). Groq, Together, Fireworks,
 * Nebius, Moonshot, vLLM, LM Studio, abliteration.ai: documented schema with documented ids.
 */
class ModelListsTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/models/$name")) { name }.readText()

    private fun ids(api: ModelsApi, name: String, flagship: List<String> = emptyList()): List<String> =
        checkNotNull(ModelLists.parse(api, fixture(name), flagship)) { name }.map { it.id }

    private fun models(api: ModelsApi, name: String) = checkNotNull(ModelLists.parse(api, fixture(name))).associateBy { it.id }

    @Test
    fun openAiDropsEmbeddingTtsImageAndModeration() {
        assertEquals(setOf("gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna", "gpt-5.5"), ids(ModelsApi.OPENAI, "openai_models.json").toSet())
    }

    @Test
    fun anthropicReadsDisplayNameCreatedAtAndInputLimit() {
        val m = models(ModelsApi.ANTHROPIC, "anthropic_models.json")
        assertEquals(5, m.size)
        val opus = m.getValue("claude-opus-5-5")
        assertEquals("Claude Opus 5.5", opus.name)
        assertEquals(1_000_000, opus.contextWindow)
        assertEquals(1_789_948_800L, opus.created) // 2026-09-21T00:00:00Z
        // Newest first without a flagship list; the preset's order with one.
        assertEquals("claude-haiku-5-5", ids(ModelsApi.ANTHROPIC, "anthropic_models.json").first())
        assertEquals("claude-opus-5-5", ids(ModelsApi.ANTHROPIC, "anthropic_models.json", ProviderPresets.byId("anthropic")!!.suggestedModels).first())
    }

    @Test
    fun geminiNativeKeepsGenerateContentModelsAndStripsThePrefix() {
        val ids = ids(ModelsApi.GEMINI, "gemini_native_models.json")
        assertEquals(setOf("gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-3.6-flash", "gemini-3.5-flash-lite"), ids.toSet())
        assertEquals(1_048_576, models(ModelsApi.GEMINI, "gemini_native_models.json").getValue("gemini-3.8-flash").contextWindow)
    }

    @Test
    fun cohereKeepsChatEndpointsOnly() {
        val m = models(ModelsApi.COHERE, "cohere_models.json")
        assertEquals(5, m.size)
        assertFalse("embed-v5.0-pro" in m)
        assertFalse("rerank-v4.0-pro" in m)
        assertEquals(256_000, m.getValue("command-a-03-2025").contextWindow)
    }

    @Test
    fun dashScopeReadsNativeShape() {
        val m = models(ModelsApi.DASHSCOPE, "qwen_models.json")
        assertEquals(setOf("qwen3.8-max", "qwen3.7-plus", "qwen3.8-flash"), m.keys)
        assertEquals(131_072, m.getValue("qwen3.8-max").contextWindow)
        assertEquals("Qwen3.8-Max", m.getValue("qwen3.8-max").name)
        assertTrue(m.getValue("qwen3.8-max").created!! > 0) // "2025-11-11 12:00:00"
    }

    @Test
    fun ollamaTagsCarrySizeAndQuantization() {
        val m = models(ModelsApi.OLLAMA, "ollama_tags.json")
        assertFalse("nomic-embed-text:latest" in m)
        assertEquals(5, m.size)
        assertEquals("8.0B · Q4_K_M", m.getValue("gemma4:latest").detail)
        assertTrue(m.getValue("gemma4:latest").created!! > 0) // RFC 3339 with offset and nanos
    }

    @Test
    fun openRouterReadsContextAndPricingAndDropsImageOutput() {
        val m = models(ModelsApi.OPENROUTER, "openrouter_models.json")
        assertFalse("google/gemini-3.1-flash-image" in m)
        assertEquals(5, m.size)
        val sonnet = m.getValue("anthropic/claude-sonnet-5.5")
        assertEquals(1_000_000, sonnet.contextWindow)
        assertEquals(2.0, sonnet.inputPerMTok!!, 1e-9)
        assertEquals(0.0, m.getValue("nvidia/nemotron-3.5-lightning:free").inputPerMTok!!, 0.0)
    }

    @Test
    fun openAiShapedHostsFilterByMetadataAndName() {
        assertEquals(5, ids(ModelsApi.OPENAI, "deepinfra_models.json").size) // tags: embed, stt, image-gen
        assertEquals(1_048_576, models(ModelsApi.OPENAI, "deepinfra_models.json").getValue("moonshotai/Kimi-K3").contextWindow)
        assertEquals(5, ids(ModelsApi.OPENAI, "groq_models.json").size) // whisper, orpheus, prompt-guard
        assertEquals(5, ids(ModelsApi.OPENAI, "together_models.json").size) // bare array with `type`
        assertEquals(5, ids(ModelsApi.OPENAI, "fireworks_models.json").size)
        assertEquals(4, ids(ModelsApi.OPENAI, "nebius_models.json").size)
        assertEquals(5, ids(ModelsApi.OPENAI, "novita_models.json").size) // ocr
        assertEquals(131_072, models(ModelsApi.OPENAI, "novita_models.json").getValue("openai/gpt-oss-120b").contextWindow)
        assertEquals(5, ids(ModelsApi.OPENAI, "venice_models.json").size) // embedding, tts, image types
        assertEquals(1_000_000, models(ModelsApi.OPENAI, "venice_models.json").getValue("kimi-k3").contextWindow)
        assertEquals(4, ids(ModelsApi.OPENAI, "mistral_models.json").size) // completion_chat=false
        assertEquals(262_144, models(ModelsApi.OPENAI, "mistral_models.json").getValue("mistral-medium-3-5").contextWindow)
        assertEquals(listOf("latest", "grok-420-reasoning").toSet(), ids(ModelsApi.OPENAI, "xai_models.json").toSet())
        assertEquals(4, ids(ModelsApi.OPENAI, "lmstudio_models.json").size)
    }

    @Test
    fun contextWindowFieldsOfEveryShape() {
        assertEquals(1_048_576, models(ModelsApi.OPENAI, "deepseek_models.json").getValue("deepseek-flash").contextWindow)
        assertEquals(1_048_576, models(ModelsApi.OPENAI, "moonshot_models.json").getValue("kimi-k3").contextWindow)
        assertEquals(131_072, models(ModelsApi.OPENAI, "groq_models.json").getValue("openai/gpt-oss-120b").contextWindow)
        assertEquals(131_072, models(ModelsApi.OPENAI, "sambanova_models.json").getValue("DeepSeek-V3.1").contextWindow)
        assertEquals(40_960, models(ModelsApi.OPENAI, "vllm_models.json").getValue("Qwen/Qwen3-8B").contextWindow)
        assertEquals(131_072, models(ModelsApi.OPENAI, "llamacpp_models.json").values.single().contextWindow) // meta.n_ctx_train
        // Hugging Face router: the largest window among live providers.
        assertEquals(1_000_000, models(ModelsApi.OPENAI, "huggingface_models.json").getValue("Qwen/Qwen3.8-27B").contextWindow)
        assertEquals(131_072, models(ModelsApi.OPENAI, "cerebras_models.json").getValue("gpt-oss-120b").contextWindow) // limits.max_context_length
        assertEquals(3, ids(ModelsApi.OPENAI, "abliteration_models.json").size)
        assertEquals(3, ids(ModelsApi.OPENAI, "minimax_models.json").size)
        assertEquals(7, ids(ModelsApi.OPENAI, "huggingface_models.json").size)
    }

    @Test
    fun deepInfraZeroCreatedIsUnknown() {
        assertNull(models(ModelsApi.OPENAI, "deepinfra_models.json").getValue("moonshotai/Kimi-K3").created)
    }

    @Test
    fun malformedOrWrongShapeIsNull() {
        assertNull(ModelLists.parse(ModelsApi.OPENAI, "<html>nope</html>"))
        assertNull(ModelLists.parse(ModelsApi.OPENAI, """{"error":{"message":"x"}}"""))
        assertNull(ModelLists.parse(ModelsApi.GEMINI, fixture("openai_models.json")))
        assertEquals(emptyList<RemoteModel>(), ModelLists.parse(ModelsApi.OPENAI, """{"data":[]}"""))
    }

    @Test
    fun nonChatIdRules() {
        listOf(
            "text-embedding-3-large", "Qwen/Qwen3-Embedding-8B", "rerank-v4.0-pro", "whisper-large-v3", "gpt-4o-mini-tts",
            "gpt-4o-transcribe", "omni-moderation-latest", "dall-e-3", "gpt-image-2", "imagen-4", "veo-3", "sora-2",
            "black-forest-labs/FLUX-2-dev", "venice-sd35", "mistral-ocr-2505", "meta-llama/Llama-Guard-4-12B",
            "gpt-4o-realtime-preview", "gpt-4o-audio-preview", "babbage-002", "davinci-002", "computer-use-preview",
            "gemini-2.5-flash-live", "BAAI/bge-m3", "intfloat/multilingual-e5-large", "stable-diffusion-xl",
        ).forEach { assertTrue(it, ModelLists.nonChatId(it)) }
        listOf(
            "gpt-6-luna", "claude-opus-5-5", "gemini-3.8-flash", "qwen3.5-397b-a17b", "Qwen/Qwen3-8B", "gpt-oss:20b",
            "deepseek-v4-pro", "llama-3.2-11b-vision-instruct", "kimi-k3", "glm-5.3", "voxtral-small-2507", "sonar-pro",
            "e2ee-glm-5-3-p", "MiniMax-M3", "openrouter/auto", "command-a-vision-07-2025",
        ).forEach { assertFalse(it, ModelLists.nonChatId(it)) }
    }

    @Test
    fun sortPutsFlagshipFirstThenNewestThenName() {
        val list = listOf(
            RemoteModel("b-old", created = 100),
            RemoteModel("a-unknown"),
            RemoteModel("c-new", created = 300),
            RemoteModel("flag-2", created = 1),
            RemoteModel("Flag-1"),
            RemoteModel("z-unknown"),
        )
        assertEquals(
            listOf("Flag-1", "flag-2", "c-new", "b-old", "a-unknown", "z-unknown"),
            ModelLists.sort(list, flagship = listOf("flag-1", "flag-2", "absent")).map { it.id },
        )
    }

    @Test
    fun flagshipOrderAppliesToParsedLists() {
        val ids = ids(ModelsApi.OPENAI, "groq_models.json", flagship = listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b"))
        assertEquals(listOf("llama-3.3-70b-versatile", "openai/gpt-oss-120b"), ids.take(2))
    }

    @Test
    fun cacheRoundTripsAndLabelsItsAge() {
        val cache = ModelCache(1_000_000L, listOf(RemoteModel("m1", contextWindow = 32_000), RemoteModel("M2")))
        val back = ModelCache.decode(cache.encode())
        assertEquals(cache, back)
        assertEquals(32_000, back!!.find("m1")?.contextWindow)
        assertEquals("M2", back.find("m2")?.id)
        assertNull(ModelCache.decode("{broken"))
        assertNull(ModelCache.decode(null))
        assertEquals("fetched just now", cache.ageLabel(1_000_000L + 30_000))
        assertEquals("fetched 12 min ago", cache.ageLabel(1_000_000L + 12 * 60_000))
        assertEquals("fetched 3 h ago", cache.ageLabel(1_000_000L + 3 * 3_600_000))
        assertEquals("fetched 5 d ago", cache.ageLabel(1_000_000L + 5 * 86_400_000L))
    }

    @Test
    fun resultMapsHttpErrorsAndBadBodies() {
        val groq = ProviderPresets.byId("groq")!!
        val unauthorized = ModelLists.result(groq, 401, """{"error":{"message":"Invalid API Key"}}""") as ModelListResult.Failure
        assertEquals(ErrorKind.UNAUTHORIZED, unauthorized.kind)
        assertEquals("HTTP 401: Invalid API Key", unauthorized.message)
        assertEquals(ErrorKind.PROTOCOL, (ModelLists.result(groq, 200, "not json") as ModelListResult.Failure).kind)
        val ok = ModelLists.result(groq, 200, fixture("groq_models.json")) as ModelListResult.Success
        assertEquals("openai/gpt-oss-120b", ok.models.first().id) // the preset's default leads
    }
}

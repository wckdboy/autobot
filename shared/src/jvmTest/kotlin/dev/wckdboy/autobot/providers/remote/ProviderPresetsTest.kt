package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.network.Loopback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetsTest {

    @Test
    fun idsAreUniqueAndEveryKindHasAPreset() {
        val ids = ProviderPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ProviderKind.entries.forEach { assertEquals(it, ProviderPresets.forKind(it).kind) }
    }

    @Test
    fun baseUrlsAreHttpsExceptLoopbackAndThePlaceholders() {
        ProviderPresets.all.forEach { p ->
            when {
                p.id == ProviderPresets.LOCAL -> assertEquals(ProviderPresets.LOCAL_BASE_URL, p.baseUrl)
                p.id == ProviderPresets.CUSTOM -> assertEquals("https://", p.baseUrl)
                p.id == ProviderPresets.INVINCIBLE -> assertEquals(ProviderPresets.NODE_BASE_URL, p.baseUrl)
                p.group == PresetGroup.LOCAL_SERVERS -> assertTrue(p.id, p.baseUrl.startsWith("http://127.0.0.1:") && Loopback.isLoopbackUrl(p.baseUrl))
                else -> assertTrue(p.id, p.baseUrl.startsWith("https://") && ProviderAuth.origin(p.baseUrl) != null)
            }
        }
    }

    @Test
    fun everyPresetLinksItsDocsAndCloudKeysAreRequired() {
        ProviderPresets.all.forEach { p ->
            assertTrue(p.id, p.docsUrl.startsWith("https://"))
            p.privacyUrl?.let { assertTrue(p.id, it.startsWith("https://")) }
            if (p.group == PresetGroup.CLOUD || p.group == PresetGroup.OPEN_MODEL_HOSTS) {
                assertTrue(p.id, p.requiresApiKey)
                assertTrue(p.id, p.defaultModel.isNotBlank())
                assertTrue(p.id, p.defaultModel in p.suggestedModels)
            } else {
                assertFalse(p.id, p.requiresApiKey)
            }
            assertEquals(p.id, p.suggestedModels.size, p.suggestedModels.toSet().size)
        }
    }

    @Test
    fun modelsEndpointsStayOnTheProvidersOrigin() {
        ProviderPresets.all.filter { it.modelsApi != ModelsApi.NONE && it.baseUrl.length > "https://".length }.forEach { p ->
            val request = ModelLists.request(p, p.baseUrl, "k") ?: error(p.id)
            assertEquals(p.id, ProviderAuth.origin(p.baseUrl), ProviderAuth.origin(request.url))
        }
        val gemini = ModelLists.request(ProviderPresets.byId("gemini")!!, ProviderPresets.byId("gemini")!!.baseUrl, "g")!!
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", gemini.url)
        assertEquals("g", gemini.headers["x-goog-api-key"])
        val ollama = ModelLists.request(ProviderPresets.byId("ollama")!!, "http://192.168.1.5:11434/v1", null)!!
        assertEquals("http://192.168.1.5:11434/api/tags", ollama.url)
        assertNull(ollama.headers["Authorization"])
        val anthropic = ModelLists.request(ProviderPresets.byId("anthropic")!!, "https://api.anthropic.com/v1", "a")!!
        assertEquals("https://api.anthropic.com/v1/models?limit=1000", anthropic.url)
        assertEquals("a", anthropic.headers["x-api-key"])
        assertEquals(ProviderAuth.ANTHROPIC_VERSION, anthropic.headers["anthropic-version"])
        assertNull(ModelLists.request(ProviderPresets.byId("zai")!!, "https://api.z.ai/api/paas/v4", "k"))
    }

    @Test
    fun keysAreOnlyScopedToTheBaseOrigin() {
        val base = "https://api.groq.com/openai/v1"
        assertEquals("Bearer k", ProviderAuth.scopedHeaders(AuthStyle.BEARER, "k", "https://api.groq.com/openai/v1/models", base)["Authorization"])
        assertEquals("Bearer k", ProviderAuth.scopedHeaders(AuthStyle.BEARER, "k", "https://API.groq.com:443/x", base)["Authorization"])
        listOf(
            "https://evil.example/models",
            "http://api.groq.com/openai/v1/models", // scheme downgrade
            "https://api.groq.com:8443/models", // other port
            "https://api.groq.com.evil.example/models",
            "https://user@api.groq.com/models", // userinfo
            "ftp://api.groq.com/models",
        ).forEach { assertTrue(it, ProviderAuth.scopedHeaders(AuthStyle.BEARER, "k", it, base).isEmpty()) }
        assertTrue(ProviderAuth.headers(AuthStyle.BEARER, "  ").isEmpty())
        assertEquals(mapOf("anthropic-version" to ProviderAuth.ANTHROPIC_VERSION), ProviderAuth.headers(AuthStyle.ANTHROPIC, null))
    }

    @Test
    fun originNormalisesHostAndPort() {
        assertEquals("https://api.x.ai:443", ProviderAuth.origin("HTTPS://API.X.AI/v1"))
        assertEquals("http://127.0.0.1:1234", ProviderAuth.origin("http://127.0.0.1:1234/v1"))
        assertEquals("http://[::1]:80", ProviderAuth.origin("http://[::1]/v1"))
        assertNull(ProviderAuth.origin("local://this-phone"))
        assertNull(ProviderAuth.origin("https://"))
    }

    @Test
    fun resolveKeepsOldRowsWorking() {
        // Rows saved before presets were stored: kind first, then host / loopback port.
        assertEquals("deepseek", ProviderPresets.resolve(null, ProviderKind.DEEPSEEK, "https://api.deepseek.com").id)
        assertEquals("ollama", ProviderPresets.resolve(null, ProviderKind.OLLAMA, "http://10.0.2.2:11434/v1").id)
        assertEquals("groq", ProviderPresets.resolve(null, ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1").id)
        assertEquals("together", ProviderPresets.resolve(null, ProviderKind.OPENAI_COMPATIBLE, "https://api.together.xyz/v1").id)
        assertEquals("lmstudio", ProviderPresets.resolve(null, ProviderKind.OPENAI_COMPATIBLE, "http://localhost:1234/v1").id)
        assertEquals(ProviderPresets.CUSTOM, ProviderPresets.resolve(null, ProviderKind.OPENAI_COMPATIBLE, "https://llm.example.com/v1").id)
        assertEquals(ProviderPresets.CUSTOM, ProviderPresets.resolve(null, ProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.9:1234/v1").id)
        // A stored id wins, unless it names another wire family.
        assertEquals("venice", ProviderPresets.resolve("venice", ProviderKind.OPENAI_COMPATIBLE, "https://proxy.example/v1").id)
        assertEquals("anthropic", ProviderPresets.resolve("groq", ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1").id)
        assertEquals(ProviderPresets.CUSTOM, ProviderPresets.resolve("gone", ProviderKind.OPENAI_COMPATIBLE, "https://x.example").id)
    }

    @Test
    fun searchGroupsInOrderAndMatchesNameIdOrHost() {
        assertEquals(PresetGroup.entries, ProviderPresets.search("").map { it.first })
        assertEquals(listOf("groq"), ProviderPresets.search("GROQ").flatMap { it.second }.map { it.id })
        assertEquals(listOf("huggingface"), ProviderPresets.search("router.hugging").flatMap { it.second }.map { it.id })
        assertTrue(ProviderPresets.search("no such provider").isEmpty())
    }

    @Test
    fun wireQuirksPerPreset() {
        assertTrue(ProviderPresets.byId("anthropic")!!.wire() is AnthropicWire)
        assertTrue(ProviderPresets.byId("deepseek")!!.echoReasoning)
        assertFalse(ProviderPresets.byId("openrouter")!!.echoReasoning)
        val tools = listOf(ToolSpec("t", "d", "{}"))
        assertEquals("none", ProviderPresets.openAiReasoningEffort(ChatRequest("gpt-6-luna", emptyList(), tools = tools)))
        assertNull(ProviderPresets.openAiReasoningEffort(ChatRequest("gpt-6-luna", emptyList())))
        assertNull(ProviderPresets.openAiReasoningEffort(ChatRequest("gpt-6-astra", emptyList(), tools = tools)))
        val body = ProviderPresets.byId("openai")!!.wire().encode(ChatRequest("gpt-6-sol", listOf(ChatMessage(ChatRole.USER, "hi")), tools = tools))
        assertTrue(body.contains("\"reasoning_effort\":\"none\""))
        assertTrue(body.contains("\"include_usage\":true"))
        val mistral = ProviderPresets.byId("mistral")!!.wire().encode(ChatRequest("m", listOf(ChatMessage(ChatRole.USER, "hi"))))
        assertFalse(mistral.contains("stream_options"))
        assertFalse(mistral.contains("reasoning_effort"))
    }
}

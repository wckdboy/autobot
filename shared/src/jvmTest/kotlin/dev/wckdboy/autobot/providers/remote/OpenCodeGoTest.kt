package dev.wckdboy.autobot.providers.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OpenCode Go preset against https://opencode.ai/docs/go/ and a live `GET /models` response
 * (2026-10-15, `models/opencode_go_models.json`, 37 models).
 */
class OpenCodeGoTest {
    private val go = ProviderPresets.byId(ProviderPresets.OPENCODE_GO)!!
    private val body = checkNotNull(javaClass.getResource("/models/opencode_go_models.json")).readText()

    /** 2026-10-15T12:00:00Z and the day after DeepSeek's ZDR agreement ends. */
    private val today = 1_792_065_600_000L
    private val november = 1_793_534_400_000L

    @Test
    fun presetIsAnOpenModelHostOnOpencodeAi() {
        assertEquals(PresetGroup.OPEN_MODEL_HOSTS, go.group)
        assertEquals("https://opencode.ai/inference/go/v1", go.baseUrl)
        assertEquals("opencode.ai", go.host)
        assertEquals(go, ProviderPresets.resolve(null, go.kind, "https://opencode.ai/inference/go/v1"))
        assertEquals("https://opencode.ai/inference/go/v1/models", ModelLists.request(go, go.baseUrl, "k")!!.url)
        assertEquals("Bearer k", ModelLists.request(go, go.baseUrl, "k")!!.headers["Authorization"])
        assertEquals("x-opencode-session", go.sessionHeader)
    }

    @Test
    fun liveModelListHidesModelsOnApisTheAppDoesNotSpeak() {
        val result = ModelLists.result(go, 200, body) as ModelListResult.Success
        val ids = result.models.map { it.id }
        assertEquals(30, ids.size)
        listOf("grok-4.7", "gpt-6-luna", "muse-spark-1.3-contributor", "gemini-4-argon-contributor").forEach { assertFalse(it, it in ids) }
        // Flagships first, in the preset's order; every suggestion is a live model.
        assertEquals(go.suggestedModels, ids.take(go.suggestedModels.size))
        assertTrue(go.suggestedModels.all { it in ids })
        assertTrue(go.helperModel!! in ids)
    }

    @Test
    fun eachModelGoesToItsDocumentedEndpoint() {
        // chat/completions
        listOf("kimi-k2.6", "glm-5.3-flash", "deepseek-v4-pro", "mimo-v2.6-flash", "longcat-2.0", "hy3", "space-bunny").forEach {
            assertTrue(it, go.wire(it) is OpenAiWire)
        }
        // messages (Anthropic wire, same base URL)
        listOf("claude-haiku-5-5", "minimax-m3", "minimax-m2.7", "qwen3.8-max", "qwen3.8-flash", "qwen3.7-plus").forEach {
            assertTrue(it, go.wire(it) is AnthropicWire)
        }
        assertTrue(go.wire() is OpenAiWire)
        assertFalse(go.supports("gpt-5.6-luna"))
        assertTrue(go.supports("glm-5.3"))
        // DeepSeek and Kimi thinking models need their reasoning back in tool loops; others don't get it.
        assertTrue(go.echoesReasoning("deepseek-v4.1-flash"))
        assertTrue(go.echoesReasoning("kimi-k3"))
        assertFalse(go.echoesReasoning("glm-5.3"))
    }

    @Test
    fun defaultsAreToolCapableOpenModelsThatKeepNothing() {
        assertEquals(ModelDataPolicy.NoRetention, ModelDataPolicies.policy(go.id, go.defaultModel, november))
        assertEquals(ModelDataPolicy.NoRetention, ModelDataPolicies.policy(go.id, go.helperModel!!, november))
        assertTrue(go.wire(go.defaultModel) is OpenAiWire)
        assertTrue(go.wire(go.helperModel!!) is OpenAiWire)
        // No preset suggests or defaults to a model whose provider trains on prompts.
        ProviderPresets.all.forEach { p ->
            (p.suggestedModels + p.defaultModel + listOfNotNull(p.helperModel)).filter { it.isNotEmpty() }
                .forEach { assertTrue("${p.id}/$it", ModelDataPolicies.autoPickable(p.id, it, today)) }
        }
    }

    @Test
    fun perModelPoliciesFollowTheDocsTable() {
        val captions = (ModelLists.result(go, 200, body) as ModelListResult.Success).models.associate {
            it.id to ModelDataPolicies.caption(go.id, it.id, today)
        }
        assertEquals("no training · zero retention", captions["kimi-k2.6"])
        assertEquals("no training · zero retention", captions["qwen3.8-flash"])
        assertEquals("no training · kept 30 days", captions["claude-haiku-5-5"])
        assertEquals("no training · kept 30 days", captions["cat-jam-free"])
        assertEquals("no training · zero retention until 2026-10-31", captions["deepseek-v4-pro"])
        // Listed by /models but not in the docs table.
        assertEquals(ModelDataPolicies.UNDOCUMENTED, captions["omen-alpha"])

        assertEquals(ModelDataPolicy.Trains, ModelDataPolicies.policy(go.id, "muse-spark-1.3-contributor", today))
        assertEquals(ModelDataPolicy.Trains, ModelDataPolicies.policy(go.id, "Gemini-4-Argon-Contributor", today))
        assertTrue(ModelDataPolicies.trains(go.id, "muse-spark-1.2-contributor", today))
        assertFalse(ModelDataPolicies.autoPickable(go.id, "gemini-4-argon-contributor", today))
        assertEquals(ModelDataPolicy.Retention(30), ModelDataPolicies.policy(go.id, "grok-4.7", today))
    }

    @Test
    fun deepSeeksAgreementCountsThroughItsLastDayOnly() {
        val lastSecond = 1_793_491_199_000L // 2026-10-31T23:59:59Z
        assertEquals(ModelDataPolicy.NoRetention, ModelDataPolicies.policy(go.id, "deepseek-v4.1-flash", lastSecond))
        assertNull(ModelDataPolicies.policy(go.id, "deepseek-v4.1-flash", lastSecond + 1000))
        assertEquals("data policy not documented (agreement ended 2026-10-31)", ModelDataPolicies.caption(go.id, "deepseek-v4.1-flash", november))
        assertTrue(ModelDataPolicies.autoPickable(go.id, "deepseek-v4.1-flash", november))
    }

    @Test
    fun otherProvidersKeepTheirPresetNote() {
        assertNull(ModelDataPolicies.caption("openai", "gpt-6-luna", today))
        assertNull(ModelDataPolicies.policy(null, "kimi-k2.6", today))
        assertFalse(ModelDataPolicies.trains("openrouter", "some-contributor", today))
        assertEquals(
            "262k ctx · no training · zero retention",
            ModelDataPolicies.modelCaption(go.id, RemoteModel("kimi-k2.6", contextWindow = 262_144), today),
        )
        assertEquals("262k ctx", ModelDataPolicies.modelCaption("groq", RemoteModel("x", contextWindow = 262_144), today))
    }

    @Test
    fun sessionKeysAreStableOpaqueAndPerConversation() {
        val a = ProviderSessions.key("conversation-1")
        assertEquals(32, a.length)
        assertEquals(a, ProviderSessions.key("conversation-1"))
        assertTrue(a != ProviderSessions.key("conversation-2"))
        assertFalse("conversation" in a)
        val request = ChatRequest("kimi-k2.6", emptyList(), sessionKey = a)
        assertEquals("x-opencode-session" to a, ProviderSessions.header(go, request))
        assertNull(ProviderSessions.header(ProviderPresets.byId("groq")!!, request))
        assertEquals(32, ProviderSessions.header(go, request.copy(sessionKey = null))!!.second.length)
    }
}

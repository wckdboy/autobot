package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.network.Loopback

/** Sections of the "add provider" list. */
enum class PresetGroup(val label: String) {
    CLOUD("Cloud"),
    OPEN_MODEL_HOSTS("Open-model hosts"),
    LOCAL_SERVERS("Local servers"),
    THIS_PHONE("This phone"),
}

/**
 * Everything the app knows about one provider, keyed by a stable [id] that is stored with each
 * configured provider. Each entry was checked against the provider's own documentation
 * ([docsUrl]) on 2026-10-15.
 *
 * @param kind the wire family: [ProviderKind.ANTHROPIC] speaks the native Messages API, the rest
 *   speak OpenAI chat completions with the quirks below.
 * @param includeUsage send `stream_options.include_usage`; only where the provider documents it
 *   (others either always send usage or do not say whether the field is accepted).
 * @param echoReasoning send earlier `reasoning_content` back on assistant messages, for providers
 *   whose thinking models require it in tool loops (DeepSeek, Kimi).
 * @param modelsPath models endpoint when it is not `{base}/models`; `{base}` and `{origin}`
 *   (scheme://host:port) are filled in from the user's base URL, so it stays on the same host.
 * @param legacyHosts other hosts the provider answered on, to recognise providers saved before
 *   presets existed.
 * @param messagesModels id prefixes that a provider of several model families serves over
 *   Anthropic's Messages API (`{base}/messages`, [AnthropicWire]) instead of chat completions.
 * @param unsupportedModels id prefixes served only on APIs the app does not speak (OpenAI
 *   Responses, Gemini's native API); they are left out of model lists.
 * @param echoReasoningModels id prefixes that need [echoReasoning], on hosts of several families.
 * @param sessionHeader header that carries [ChatRequest.sessionKey], for providers that refuse
 *   requests without a per-conversation id.
 * @param helperModel the helper model a new provider of this preset starts with: fast, cheap,
 *   tool-capable and not trained on prompts (see [ModelDataPolicies]).
 */
data class ProviderPreset(
    val id: String,
    val kind: ProviderKind,
    val displayName: String,
    val group: PresetGroup,
    val baseUrl: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val requiresApiKey: Boolean,
    val docsUrl: String,
    val modelsApi: ModelsApi = ModelsApi.OPENAI,
    val modelsPath: String? = null,
    val modelsAuth: AuthStyle = AuthStyle.BEARER,
    val includeUsage: Boolean = false,
    val echoReasoning: Boolean = false,
    val note: String? = null,
    val privacyNote: String? = null,
    val privacyUrl: String? = null,
    val legacyHosts: List<String> = emptyList(),
    val messagesModels: List<String> = emptyList(),
    val unsupportedModels: List<String> = emptyList(),
    val echoReasoningModels: List<String> = emptyList(),
    val sessionHeader: String? = null,
    val helperModel: String? = null,
) {
    /** The chat dialect of this provider, for [model] when it serves families on several APIs. */
    fun wire(model: String? = null): ChatWire = when {
        kind == ProviderKind.ANTHROPIC -> AnthropicWire
        model != null && matches(messagesModels, model) -> AnthropicWire
        else -> OpenAiWire(
            includeUsage = includeUsage,
            echoExtraContent = id == ProviderPresets.GEMINI,
            reasoningEffort = if (id == ProviderPresets.OPENAI) ProviderPresets::openAiReasoningEffort else { _ -> null },
        )
    }

    /** Whether the app can chat with [model] here (it is not on an API the app does not speak). */
    fun supports(model: String): Boolean = !matches(unsupportedModels, model)

    /** Whether [model]'s reasoning must be sent back in tool loops. */
    fun echoesReasoning(model: String): Boolean = echoReasoning || matches(echoReasoningModels, model)

    private fun matches(prefixes: List<String>, model: String): Boolean {
        val m = model.trim().lowercase()
        return prefixes.any { m.startsWith(it) }
    }

    /** Host shown in lists (`api.groq.com`, `127.0.0.1:11434`). */
    val host: String get() = baseUrl.substringAfter("://").substringBefore('/')
}

object ProviderPresets {
    const val OPENAI = "openai"
    const val ANTHROPIC = "anthropic"
    const val GEMINI = "gemini"
    const val DEEPSEEK = "deepseek"
    const val OPENROUTER = "openrouter"
    const val OPENCODE_GO = "opencode-go"
    const val OLLAMA = "ollama"
    const val LOCAL = "local"
    const val CUSTOM = "openai-compatible"

    val all: List<ProviderPreset> = listOf(
        // ---- Cloud: the model makers' own APIs ------------------------------------------------
        ProviderPreset(
            id = OPENAI, kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "OpenAI", group = PresetGroup.CLOUD,
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-6-luna",
            suggestedModels = listOf("gpt-6-luna", "gpt-6-sol", "gpt-6.1-sol", "gpt-6-astra", "gpt-5.5"),
            requiresApiKey = true,
            // https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events.md
            docsUrl = "https://developers.openai.com/api/docs/guides/latest-model/gpt-6-astra.md",
            includeUsage = true,
            note = "GPT-6 Astra and 6.1 Sol can't call tools over chat completions; Luna and Sol can (reasoning effort is set to none for tool turns).",
            privacyNote = "API data is not used for training unless you opt in; abuse logs are kept up to 30 days.",
            privacyUrl = "https://developers.openai.com/api/docs/guides/your-data",
        ),
        ProviderPreset(
            id = ANTHROPIC, kind = ProviderKind.ANTHROPIC, displayName = "Anthropic", group = PresetGroup.CLOUD,
            baseUrl = "https://api.anthropic.com/v1",
            defaultModel = "claude-opus-5-5",
            suggestedModels = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1", "claude-sonnet-4-6"),
            requiresApiKey = true,
            // Native Messages API; the OpenAI compatibility layer drops thinking and prompt caching.
            // https://platform.claude.com/docs/en/build-with-claude/streaming · /docs/en/api/openai-sdk
            docsUrl = "https://platform.claude.com/docs/en/api/messages/create",
            modelsApi = ModelsApi.ANTHROPIC,
            modelsPath = "{base}/models?limit=1000",
            modelsAuth = AuthStyle.ANTHROPIC,
            note = "Native Messages API with prompt caching (x-api-key auth).",
        ),
        ProviderPreset(
            id = GEMINI, kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Google Gemini", group = PresetGroup.CLOUD,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
            defaultModel = "gemini-3.8-flash",
            suggestedModels = listOf("gemini-3.8-flash", "gemini-3.1-pro-preview", "gemini-3.6-flash", "gemini-3.5-flash-lite"),
            requiresApiKey = true,
            // Thought signatures: https://ai.google.dev/gemini-api/docs/generate-content/thought-signatures
            // Model list (native, x-goog-api-key): https://ai.google.dev/api/models
            docsUrl = "https://ai.google.dev/gemini-api/docs/openai",
            modelsApi = ModelsApi.GEMINI,
            modelsPath = "{origin}/v1beta/models?pageSize=1000",
            modelsAuth = AuthStyle.GOOGLE,
            includeUsage = true,
            note = "OpenAI compatibility (beta). Tool calls carry Gemini's thought signatures back.",
            privacyNote = "Free tier: prompts may be used to improve Google products and read by reviewers. Paid tier: not used.",
            privacyUrl = "https://ai.google.dev/gemini-api/terms",
        ),
        ProviderPreset(
            id = "xai", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "xAI (Grok)", group = PresetGroup.CLOUD,
            baseUrl = "https://api.x.ai/v1",
            defaultModel = "grok-4.7",
            suggestedModels = listOf("grok-4.7", "grok-4.6", "grok-4.3", "grok-4.20-0309-reasoning", "grok-4.20-0309-non-reasoning"),
            requiresApiKey = true,
            // Models: https://docs.x.ai/developers/rest-api-reference/inference/models.md
            docsUrl = "https://docs.x.ai/developers/rest-api-reference/inference/chat-completions.md",
            includeUsage = true,
            privacyNote = "Not used for training without permission; kept encrypted 30 days for abuse audits.",
            privacyUrl = "https://docs.x.ai/developers/faq/security",
        ),
        ProviderPreset(
            id = "mistral", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Mistral", group = PresetGroup.CLOUD,
            baseUrl = "https://api.mistral.ai/v1",
            defaultModel = "mistral-medium-3-5",
            suggestedModels = listOf("mistral-medium-3-5", "mistral-large-4", "mistral-small-latest", "ministral-14b-2512"),
            requiresApiKey = true,
            // Schema: https://docs.mistral.ai/openapi.yaml (no stream_options); models: https://docs.mistral.ai/models.md
            docsUrl = "https://docs.mistral.ai/openapi.yaml",
            privacyNote = "API data may be used for training unless \"Anonymous improvement data\" is turned off.",
            privacyUrl = "https://help.mistral.ai/en/articles/455207",
        ),
        ProviderPreset(
            id = DEEPSEEK, kind = ProviderKind.DEEPSEEK, displayName = "DeepSeek", group = PresetGroup.CLOUD,
            baseUrl = DEEPSEEK_BASE_URL,
            defaultModel = "deepseek-flash",
            suggestedModels = listOf("deepseek-flash", "deepseek-v4-pro"),
            requiresApiKey = true,
            // reasoning_content must be resent with tools: https://api-docs.deepseek.com/guides/thinking_mode
            // Models: https://api-docs.deepseek.com/api/list-models
            docsUrl = "https://api-docs.deepseek.com/api/create-chat-completion",
            includeUsage = true,
            echoReasoning = true,
            privacyNote = "Stored in the PRC; inputs may be used for training (opt-out available).",
            privacyUrl = "https://cdn.deepseek.com/policies/en-US/deepseek-privacy-policy.html",
        ),
        ProviderPreset(
            id = "moonshot", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Moonshot (Kimi)", group = PresetGroup.CLOUD,
            baseUrl = "https://api.moonshot.ai/v1",
            defaultModel = "kimi-k3",
            suggestedModels = listOf("kimi-k3", "kimi-k2.7-code", "kimi-k2.6"),
            requiresApiKey = true,
            // Thinking + tools: https://platform.kimi.ai/docs/guide/use-thinking-models
            // Models: https://platform.kimi.ai/docs/api/list-models
            docsUrl = "https://platform.kimi.ai/docs/api/chat",
            includeUsage = true,
            echoReasoning = true,
            privacyNote = "API inputs and outputs are not used for training.",
            privacyUrl = "https://www.kimi.com/help/kimi-api/api-data-security",
        ),
        ProviderPreset(
            id = "qwen", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Alibaba Qwen (Model Studio)", group = PresetGroup.CLOUD,
            baseUrl = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen3.8-max",
            suggestedModels = listOf("qwen3.8-max", "qwen3.7-plus", "qwen3.8-flash"),
            requiresApiKey = true,
            // Models (native list): https://www.alibabacloud.com/help/en/model-studio/list-models
            docsUrl = "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope",
            modelsApi = ModelsApi.DASHSCOPE,
            modelsPath = "{origin}/api/v1/models?page_size=100",
            includeUsage = true,
            note = "International (Singapore) endpoint. Keys are per region.",
            privacyNote = "Prompts kept up to 30 days; not used for training without consent.",
            privacyUrl = "https://www.alibabacloud.com/help/en/model-studio/data-retention-policy",
        ),
        ProviderPreset(
            id = "zai", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Z.ai (GLM)", group = PresetGroup.CLOUD,
            baseUrl = "https://api.z.ai/api/paas/v4",
            defaultModel = "glm-5.3",
            suggestedModels = listOf("glm-5.3", "glm-5.2", "glm-5.3-flash", "glm-5.1"),
            requiresApiKey = true,
            // OpenAPI: https://docs.z.ai/openapi.json (no models endpoint)
            docsUrl = "https://docs.z.ai/guides/overview/quick-start",
            modelsApi = ModelsApi.NONE,
            note = "No models endpoint: the list below is the documented one.",
            privacyNote = "API content is processed in real time and not stored.",
            privacyUrl = "https://docs.z.ai/legal-agreement/privacy-policy",
        ),
        ProviderPreset(
            id = "minimax", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "MiniMax", group = PresetGroup.CLOUD,
            baseUrl = "https://api.minimax.io/v1",
            defaultModel = "MiniMax-M3",
            suggestedModels = listOf("MiniMax-M3", "MiniMax-M3.1-Flash-Preview", "MiniMax-M2.7", "MiniMax-M2.5"),
            requiresApiKey = true,
            // Models: https://platform.minimax.io/docs/api-reference/models/openai/list-models
            docsUrl = "https://platform.minimax.io/docs/llms-full.txt",
            includeUsage = true,
            note = "Reasoning arrives inside the answer as <think>…</think> and is kept as-is, which MiniMax requires.",
        ),
        ProviderPreset(
            id = "cohere", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Cohere", group = PresetGroup.CLOUD,
            baseUrl = "https://api.cohere.com/compatibility/v1",
            defaultModel = "command-a-plus-05-2026",
            suggestedModels = listOf("command-a-plus-05-2026", "command-a-reasoning-08-2025", "command-a-03-2025", "command-r7b-12-2024"),
            requiresApiKey = true,
            // Models (native): https://docs.cohere.com/reference/list-models
            docsUrl = "https://docs.cohere.com/docs/compatibility-api",
            modelsApi = ModelsApi.COHERE,
            modelsPath = "{origin}/v1/models?endpoint=chat&page_size=1000",
            note = "Compatibility API: no documents or citations.",
            privacyNote = "API data is used for training by default; opt out in Settings → Data Controls.",
            privacyUrl = "https://cohere.com/enterprise-data-commitments",
        ),
        ProviderPreset(
            id = "perplexity", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Perplexity (Sonar)", group = PresetGroup.CLOUD,
            baseUrl = "https://api.perplexity.ai",
            defaultModel = "sonar",
            suggestedModels = listOf("sonar", "sonar-pro", "sonar-reasoning-pro", "sonar-deep-research"),
            requiresApiKey = true,
            // Sonar chat completions are converted to Agent API requests since 2026-09-27:
            // https://docs.perplexity.ai/docs/agent-api/migrate-from-sonar/overview
            docsUrl = "https://docs.perplexity.ai/openapi.json",
            modelsApi = ModelsApi.NONE,
            note = "Search-grounded answers, no tool calling. Its /models lists Agent API models, not Sonar.",
            privacyNote = "No training on API content by default; zero retention only by agreement.",
            privacyUrl = "https://docs.perplexity.ai/docs/resources/privacy-security",
        ),

        // ---- Open-model hosts -----------------------------------------------------------------
        ProviderPreset(
            id = OPENROUTER, kind = ProviderKind.OPENROUTER, displayName = "OpenRouter", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = OPENROUTER_BASE_URL,
            defaultModel = "deepseek/deepseek-v4.1-flash",
            suggestedModels = listOf(
                "deepseek/deepseek-v4.1-flash", "anthropic/claude-sonnet-5.5", "google/gemini-3.8-flash", "moonshotai/kimi-k3", "z-ai/glm-5.3",
            ),
            requiresApiKey = true,
            // Usage is always sent: https://openrouter.ai/docs/use-cases/usage-accounting
            docsUrl = "https://openrouter.ai/docs/api-reference/list-available-models",
            modelsApi = ModelsApi.OPENROUTER,
            note = "No HTTP-Referer / X-Title attribution headers are sent.",
            privacyNote = "Prompts are not logged unless you opt in; zero data retention can be enforced per account.",
            privacyUrl = "https://openrouter.ai/docs/guides/features/zdr",
        ),
        ProviderPreset(
            id = OPENCODE_GO, kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "OpenCode Go", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://opencode.ai/inference/go/v1",
            defaultModel = "kimi-k2.6",
            suggestedModels = listOf(
                "kimi-k2.6", "kimi-k3", "glm-5.3", "glm-5.2", "deepseek-v4-pro", "glm-5.3-flash", "deepseek-v4.1-flash", "qwen3.8-max", "minimax-m3",
            ),
            requiresApiKey = true,
            // Endpoint per model, limits and per-model privacy: https://opencode.ai/docs/go/#endpoints
            // Models (public): https://opencode.ai/inference/go/v1/models
            docsUrl = "https://opencode.ai/docs/go/",
            note = "$10/month plan; each model has 5-hour, weekly and monthly limits. Grok, GPT, Muse and Gemini need APIs the app doesn't speak and are hidden.",
            privacyNote = "Per model: most keep nothing and never train; Claude Haiku keeps 30 days; \"Contributor\" models train on your prompts.",
            privacyUrl = "https://opencode.ai/docs/go/#privacy",
            messagesModels = listOf("claude-", "minimax-", "qwen3"),
            unsupportedModels = listOf("grok-", "gpt-", "muse-spark-", "gemini-"),
            echoReasoningModels = listOf("deepseek-", "kimi-"),
            sessionHeader = "x-opencode-session",
            helperModel = "glm-5.3-flash",
        ),
        ProviderPreset(
            id = "groq", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Groq", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://api.groq.com/openai/v1",
            defaultModel = "openai/gpt-oss-120b",
            suggestedModels = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile", "qwen/qwen3.8-27b", "minimaxai/minimax-m2.7"),
            requiresApiKey = true,
            // Models: https://console.groq.com/docs/models · reasoning: https://console.groq.com/docs/reasoning
            docsUrl = "https://console.groq.com/docs/api-reference",
            includeUsage = true,
            privacyNote = "Not retained by default (up to 30 days for abuse cases); zero retention in Data Controls.",
            privacyUrl = "https://console.groq.com/docs/your-data",
        ),
        ProviderPreset(
            id = "cerebras", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Cerebras", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://api.cerebras.ai/v1",
            defaultModel = "gpt-oss-120b",
            suggestedModels = listOf("gpt-oss-120b", "qwen-3.8-27b"),
            requiresApiKey = true,
            // Reasoning: https://inference-docs.cerebras.ai/capabilities/reasoning
            docsUrl = "https://inference-docs.cerebras.ai/resources/openai",
        ),
        ProviderPreset(
            id = "together", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Together AI", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://api.together.ai/v1",
            defaultModel = "moonshotai/Kimi-K3",
            suggestedModels = listOf(
                "moonshotai/Kimi-K3", "deepseek-ai/DeepSeek-V4-Pro-0813", "deepseek-ai/DeepSeek-V4.1-Flash", "zai-org/GLM-5.3",
                "Qwen/Qwen3.8-Flash", "openai/gpt-oss-120b",
            ),
            requiresApiKey = true,
            // Models (a bare array with `type`): https://docs.together.ai/reference/models-1
            docsUrl = "https://docs.together.ai/docs/openai-api-compatibility",
            privacyNote = "Prompts are stored unless zero data retention is set in Privacy settings; training is opt-in.",
            privacyUrl = "https://docs.together.ai/docs/zero-data-retention",
            legacyHosts = listOf("api.together.xyz"),
        ),
        ProviderPreset(
            id = "fireworks", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Fireworks AI", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://api.fireworks.ai/inference/v1",
            defaultModel = "accounts/fireworks/models/kimi-k3",
            suggestedModels = listOf(
                "accounts/fireworks/models/kimi-k3", "accounts/fireworks/models/deepseek-v4p1-flash", "accounts/fireworks/models/glm-5p3",
                "accounts/fireworks/models/minimax-m3", "accounts/fireworks/models/qwen3p7-plus", "accounts/fireworks/models/gpt-oss-120b",
            ),
            requiresApiKey = true,
            docsUrl = "https://docs.fireworks.ai/tools-sdks/openai-compatibility",
            note = "Its OpenAI-style /models endpoint is undocumented; the list may be incomplete.",
            privacyNote = "Zero data retention by default for open models.",
            privacyUrl = "https://docs.fireworks.ai/guides/security_compliance/data_handling",
        ),
        ProviderPreset(
            id = "deepinfra", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "DeepInfra", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://api.deepinfra.com/v1/openai",
            defaultModel = "moonshotai/Kimi-K3",
            suggestedModels = listOf("moonshotai/Kimi-K3", "deepseek-ai/DeepSeek-V4-Pro", "zai-org/GLM-5.3", "Qwen/Qwen3.8-27B", "openai/gpt-oss-120b"),
            requiresApiKey = true,
            // Models: https://docs.deepinfra.com/api-reference/models/openai-models
            docsUrl = "https://docs.deepinfra.com/chat/overview",
            privacyNote = "Inputs and outputs are kept in memory only and never used for training.",
            privacyUrl = "https://docs.deepinfra.com/account/data-privacy",
        ),
        ProviderPreset(
            id = "nebius", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Nebius Token Factory", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://api.tokenfactory.nebius.com/v1",
            defaultModel = "deepseek-ai/DeepSeek-V4-Flash-0731",
            suggestedModels = listOf(
                "deepseek-ai/DeepSeek-V4-Flash-0731", "MiniMaxAI/MiniMax-M3", "Qwen/Qwen3.5-397B-A17B", "nvidia/nemotron-3-super-120b-a12b",
            ),
            requiresApiKey = true,
            // Models (verbose has context_length): https://docs.tokenfactory.nebius.com/api-reference/models/list-models
            docsUrl = "https://docs.tokenfactory.nebius.com/api-reference/inference/create-chat-completion",
            modelsPath = "{base}/models?verbose=true",
            includeUsage = true,
            note = "Formerly Nebius AI Studio.",
            privacyNote = "Not used for training; zero retention can be enabled in your profile.",
            privacyUrl = "https://docs.tokenfactory.nebius.com/legal/legal-quick-guide",
            legacyHosts = listOf("api.studio.nebius.com", "api.studio.nebius.ai"),
        ),
        ProviderPreset(
            id = "sambanova", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "SambaNova Cloud", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://api.sambanova.ai/v1",
            defaultModel = "gpt-oss-120b",
            suggestedModels = listOf("gpt-oss-120b", "DeepSeek-V3.1", "Meta-Llama-3.3-70B-Instruct", "MiniMax-M3"),
            requiresApiKey = true,
            // Models: https://docs.sambanova.ai/docs/api-reference/models/get-environments-available-model-list-metadata.md
            docsUrl = "https://docs.sambanova.ai/docs/api-reference/chat-completions/create-chat-based-completion.md",
            includeUsage = true,
        ),
        ProviderPreset(
            id = "novita", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Novita AI", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://api.novita.ai/openai/v1",
            defaultModel = "zai-org/glm-5.3",
            suggestedModels = listOf("zai-org/glm-5.3", "deepseek/deepseek-v4-pro", "moonshotai/kimi-k3", "minimax/minimax-m3", "deepseek/deepseek-v4.1-flash"),
            requiresApiKey = true,
            // Reasoning: https://docs.novita.ai/guides/llm-reasoning.md
            docsUrl = "https://docs.novita.ai/guides/llm-api",
            includeUsage = true,
            note = "Reasoning stays inside the answer as <think>…</think> unless the model separates it.",
        ),
        ProviderPreset(
            id = "huggingface", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Hugging Face Inference Providers", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://router.huggingface.co/v1",
            defaultModel = "openai/gpt-oss-120b",
            suggestedModels = listOf("openai/gpt-oss-120b", "zai-org/GLM-5.3", "deepseek-ai/DeepSeek-V4-Pro", "moonshotai/Kimi-K3", "Qwen/Qwen3.8-27B"),
            requiresApiKey = true,
            // Chat API: https://huggingface.co/docs/inference-providers/tasks/chat-completion
            docsUrl = "https://huggingface.co/docs/inference-providers/index",
            includeUsage = true,
            note = "Routes to third-party providers; append :fastest, :cheapest or :<provider> to a model id.",
            privacyNote = "Hugging Face keeps no bodies (logs up to 30 days); each upstream provider's policy applies.",
            privacyUrl = "https://huggingface.co/docs/inference-providers/security",
        ),
        ProviderPreset(
            id = "venice", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Venice.ai", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://api.venice.ai/api/v1",
            defaultModel = "zai-org-glm-5-2",
            suggestedModels = listOf("zai-org-glm-5-2", "venice-uncensored-1-2", "kimi-k3", "deepseek-v4-pro", "e2ee-glm-5-3-p"),
            requiresApiKey = true,
            // Models: https://docs.venice.ai/api-reference/endpoint/models/list · spec: https://api.venice.ai/api/v1/swagger.yaml
            docsUrl = "https://docs.venice.ai/api-reference/endpoint/chat/completions",
            includeUsage = true,
            privacyNote = "\"Private\" models keep no prompts; \"anonymized\" ones are hosted by third parties that may.",
            privacyUrl = "https://venice.ai/privacy",
        ),
        ProviderPreset(
            id = "abliteration", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "abliteration.ai", group = PresetGroup.OPEN_MODEL_HOSTS,
            baseUrl = "https://api.abliteration.ai/v1",
            defaultModel = "abliterated-model",
            suggestedModels = listOf("abliterated-model", "abliterated-model-large-v2", "abliterated-model-large"),
            requiresApiKey = true,
            // Models: https://docs.abliteration.ai/models.md · spec: https://api.abliteration.ai/openapi.json
            docsUrl = "https://docs.abliteration.ai/api/openai-compatibility.md",
            includeUsage = true,
            note = "Uncensored (abliterated) models.",
            privacyNote = "Prompts and completions are processed transiently, not stored, never used for training.",
            privacyUrl = "https://abliteration.ai/privacy",
        ),

        // ---- Local servers (loopback works in Offline mode) ---------------------------------------
        ProviderPreset(
            id = OLLAMA, kind = ProviderKind.OLLAMA, displayName = "Ollama", group = PresetGroup.LOCAL_SERVERS,
            baseUrl = OLLAMA_BASE_URL,
            defaultModel = "gpt-oss:20b",
            suggestedModels = listOf("gpt-oss:20b", "qwen3:8b", "qwen3.6:27b", "gemma4", "llama3.2"),
            requiresApiKey = false,
            // Model list: https://docs.ollama.com/api/tags
            docsUrl = "https://docs.ollama.com/api/openai-compatibility",
            modelsApi = ModelsApi.OLLAMA,
            modelsPath = "{origin}/api/tags",
            includeUsage = true,
        ),
        ProviderPreset(
            id = "lmstudio", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "LM Studio", group = PresetGroup.LOCAL_SERVERS,
            baseUrl = "http://127.0.0.1:1234/v1",
            defaultModel = "",
            suggestedModels = listOf("openai/gpt-oss-20b", "qwen/qwen3-8b", "google/gemma-4-26b-a4b"),
            requiresApiKey = false,
            docsUrl = "https://lmstudio.ai/docs/developer/openai-compat",
            note = "Key only if API tokens are enabled in LM Studio.",
        ),
        ProviderPreset(
            id = "llamacpp", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "llama.cpp server", group = PresetGroup.LOCAL_SERVERS,
            baseUrl = "http://127.0.0.1:9931/v1",
            defaultModel = "",
            suggestedModels = emptyList(),
            requiresApiKey = false,
            docsUrl = "https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md",
            includeUsage = true,
            note = "Port 9931 since October 2026; older builds listen on 8080. Key only with --api-key.",
        ),
        ProviderPreset(
            id = "vllm", kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "vLLM", group = PresetGroup.LOCAL_SERVERS,
            baseUrl = "http://127.0.0.1:8000/v1",
            defaultModel = "",
            suggestedModels = emptyList(),
            requiresApiKey = false,
            // Reasoning field is `reasoning`: https://docs.vllm.ai/en/latest/features/reasoning_outputs.html
            docsUrl = "https://docs.vllm.ai/en/latest/serving/online_serving/",
            includeUsage = true,
            note = "Key only with --api-key.",
        ),
        ProviderPreset(
            id = CUSTOM, kind = ProviderKind.OPENAI_COMPATIBLE, displayName = "Other OpenAI-compatible", group = PresetGroup.LOCAL_SERVERS,
            baseUrl = "https://",
            defaultModel = "",
            suggestedModels = emptyList(),
            requiresApiKey = false,
            docsUrl = "https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events.md",
            includeUsage = true,
            note = "Any server with /chat/completions and /models.",
        ),

        // ---- This phone (added automatically when a chat model is installed) ---------------------
        ProviderPreset(
            id = LOCAL, kind = ProviderKind.LOCAL, displayName = "This phone", group = PresetGroup.THIS_PHONE,
            baseUrl = LOCAL_BASE_URL,
            defaultModel = "",
            suggestedModels = emptyList(),
            requiresApiKey = false,
            docsUrl = "https://github.com/ggml-org/llama.cpp",
            modelsApi = ModelsApi.NONE,
            note = "Runs downloaded models on this phone; appears once a chat model is installed.",
        ),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String?): ProviderPreset? = id?.let { byId[it] }

    /** The canonical preset of a kind (the generic one for [ProviderKind.OPENAI_COMPATIBLE]). */
    fun forKind(kind: ProviderKind): ProviderPreset = when (kind) {
        ProviderKind.DEEPSEEK -> byId.getValue(DEEPSEEK)
        ProviderKind.OPENROUTER -> byId.getValue(OPENROUTER)
        ProviderKind.OLLAMA -> byId.getValue(OLLAMA)
        ProviderKind.LOCAL -> byId.getValue(LOCAL)
        ProviderKind.ANTHROPIC -> byId.getValue(ANTHROPIC)
        ProviderKind.OPENAI_COMPATIBLE -> byId.getValue(CUSTOM)
    }

    /**
     * The preset of a stored provider: its [presetId], else (providers saved before presets were
     * stored) the kind's preset, else a preset whose host or local port matches [baseUrl], else
     * the generic OpenAI-compatible one. A preset of another wire family is never chosen.
     */
    fun resolve(presetId: String?, kind: ProviderKind, baseUrl: String): ProviderPreset {
        byId(presetId)?.takeIf { it.kind == kind }?.let { return it }
        if (kind != ProviderKind.OPENAI_COMPATIBLE) return forKind(kind)
        return byHost(baseUrl)?.takeIf { it.kind == kind } ?: forKind(kind)
    }

    /** The preset serving [baseUrl] (cloud: same host; local servers: same loopback port). */
    fun byHost(baseUrl: String): ProviderPreset? {
        val origin = ProviderAuth.origin(baseUrl) ?: return null
        val host = origin.substringAfter("://").substringBeforeLast(':')
        val port = origin.substringAfterLast(':')
        return all.firstOrNull { p ->
            if (p.group == PresetGroup.LOCAL_SERVERS) {
                p.id != CUSTOM && Loopback.isLoopbackUrl(baseUrl) && ProviderAuth.origin(p.baseUrl)?.substringAfterLast(':') == port
            } else {
                val presetHost = ProviderAuth.origin(p.baseUrl)?.substringAfter("://")?.substringBeforeLast(':')
                host == presetHost || host in p.legacyHosts
            }
        }
    }

    /** Presets the user can add, filtered by [query] (name, id or host), by group in display order. */
    fun search(query: String): List<Pair<PresetGroup, List<ProviderPreset>>> {
        return PresetGroup.entries.mapNotNull { group ->
            all.filter { it.group == group && matches(it, query) }
                .takeIf { it.isNotEmpty() }
                ?.let { group to it }
        }
    }

    /** Whether [preset] matches a search [query] (name, id or host, ignoring case). */
    fun matches(preset: ProviderPreset, query: String): Boolean {
        val q = query.trim().lowercase()
        return q.isEmpty() || q in preset.displayName.lowercase() || q in preset.id || q in preset.host.lowercase()
    }

    /**
     * OpenAI's GPT-6 Sol and Luna only call functions over chat completions with
     * `reasoning_effort: "none"` (https://developers.openai.com/api/docs/guides/latest-model/gpt-6-astra.md).
     */
    fun openAiReasoningEffort(request: ChatRequest): String? {
        if (request.tools.isEmpty()) return null
        val m = request.model.lowercase()
        return if (m.startsWith("gpt-6-sol") || m.startsWith("gpt-6-luna")) "none" else null
    }

    /** Placeholder base URL of the on-device provider; it is never dialled. */
    const val LOCAL_BASE_URL = "local://this-phone"

    /** DeepSeek's official API (`reasoning_content`). */
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
    const val DEEPSEEK_CHAT = "deepseek-chat"
    const val DEEPSEEK_REASONER = "deepseek-reasoner"

    /** OpenRouter (`reasoning` deltas). No `HTTP-Referer`/`X-Title` attribution headers are sent. */
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

    /** A local Ollama server's OpenAI-compatible API. Works in Offline mode via the loopback exception. */
    const val OLLAMA_BASE_URL = "http://127.0.0.1:11434/v1"
}

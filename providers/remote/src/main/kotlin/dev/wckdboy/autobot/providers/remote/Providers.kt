package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.security.Secret
import okhttp3.OkHttpClient

/** DeepSeek's official API (`deepseek-chat`, `deepseek-reasoner` with `reasoning_content`). */
class DeepSeekProvider(
    apiKey: Secret?,
    client: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
) : OpenAICompatibleProvider(baseUrl, apiKey, client) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val MODEL_CHAT = "deepseek-chat"
        const val MODEL_REASONER = "deepseek-reasoner"
    }
}

/** OpenRouter (`reasoning` deltas). No `HTTP-Referer`/`X-Title` attribution headers are sent. */
class OpenRouterProvider(
    apiKey: Secret?,
    client: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
) : OpenAICompatibleProvider(baseUrl, apiKey, client) {
    companion object {
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
    }
}

/** A local Ollama server's OpenAI-compatible API. Works in Offline mode via the loopback exception. */
class OllamaProvider(
    apiKey: Secret?,
    client: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
) : OpenAICompatibleProvider(baseUrl, apiKey, client) {
    companion object {
        const val DEFAULT_BASE_URL = "http://127.0.0.1:11434/v1"
    }
}

/** UI defaults for each [ProviderKind]. */
data class ProviderPreset(
    val kind: ProviderKind,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val suggestedModels: List<String>,
    val requiresApiKey: Boolean,
)

object ProviderPresets {
    val all: List<ProviderPreset> = listOf(
        ProviderPreset(
            kind = ProviderKind.DEEPSEEK,
            displayName = "DeepSeek",
            baseUrl = DeepSeekProvider.DEFAULT_BASE_URL,
            defaultModel = DeepSeekProvider.MODEL_CHAT,
            suggestedModels = listOf(DeepSeekProvider.MODEL_CHAT, DeepSeekProvider.MODEL_REASONER),
            requiresApiKey = true,
        ),
        ProviderPreset(
            kind = ProviderKind.OPENROUTER,
            displayName = "OpenRouter",
            baseUrl = OpenRouterProvider.DEFAULT_BASE_URL,
            defaultModel = "deepseek/deepseek-chat",
            suggestedModels = listOf("deepseek/deepseek-chat", "deepseek/deepseek-r1", "qwen/qwen3-235b-a22b"),
            requiresApiKey = true,
        ),
        ProviderPreset(
            kind = ProviderKind.OLLAMA,
            displayName = "Ollama (local)",
            baseUrl = OllamaProvider.DEFAULT_BASE_URL,
            defaultModel = "llama3.2",
            suggestedModels = listOf("llama3.2", "qwen3", "deepseek-r1"),
            requiresApiKey = false,
        ),
        ProviderPreset(
            kind = ProviderKind.OPENAI_COMPATIBLE,
            displayName = "OpenAI-compatible",
            baseUrl = "https://",
            defaultModel = "",
            suggestedModels = emptyList(),
            requiresApiKey = false,
        ),
    )

    fun forKind(kind: ProviderKind): ProviderPreset = all.first { it.kind == kind }
}

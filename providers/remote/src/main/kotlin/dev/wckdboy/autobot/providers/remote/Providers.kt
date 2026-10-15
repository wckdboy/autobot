package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.security.Secret
import okhttp3.OkHttpClient

/** DeepSeek's official API (`reasoning_content`, resent in tool loops). */
class DeepSeekProvider(
    apiKey: Secret?,
    client: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
) : OpenAICompatibleProvider(baseUrl, apiKey, client, ProviderPresets.byId(ProviderPresets.DEEPSEEK)!!) {
    companion object {
        const val DEFAULT_BASE_URL = ProviderPresets.DEEPSEEK_BASE_URL
        const val MODEL_CHAT = ProviderPresets.DEEPSEEK_CHAT
        const val MODEL_REASONER = ProviderPresets.DEEPSEEK_REASONER
    }
}

/** OpenRouter (`reasoning` deltas). No `HTTP-Referer`/`X-Title` attribution headers are sent. */
class OpenRouterProvider(
    apiKey: Secret?,
    client: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
) : OpenAICompatibleProvider(baseUrl, apiKey, client, ProviderPresets.byId(ProviderPresets.OPENROUTER)!!) {
    companion object {
        const val DEFAULT_BASE_URL = ProviderPresets.OPENROUTER_BASE_URL
    }
}

/** A local Ollama server's OpenAI-compatible API. Works in Offline mode via the loopback exception. */
class OllamaProvider(
    apiKey: Secret?,
    client: OkHttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
) : OpenAICompatibleProvider(baseUrl, apiKey, client, ProviderPresets.byId(ProviderPresets.OLLAMA)!!) {
    companion object {
        const val DEFAULT_BASE_URL = ProviderPresets.OLLAMA_BASE_URL
    }
}

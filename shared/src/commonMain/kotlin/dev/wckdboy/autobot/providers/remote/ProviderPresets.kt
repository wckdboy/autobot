package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.model.ProviderKind

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
            baseUrl = DEEPSEEK_BASE_URL,
            defaultModel = DEEPSEEK_CHAT,
            suggestedModels = listOf(DEEPSEEK_CHAT, DEEPSEEK_REASONER),
            requiresApiKey = true,
        ),
        ProviderPreset(
            kind = ProviderKind.OPENROUTER,
            displayName = "OpenRouter",
            baseUrl = OPENROUTER_BASE_URL,
            defaultModel = "deepseek/deepseek-chat",
            suggestedModels = listOf("deepseek/deepseek-chat", "deepseek/deepseek-r1", "qwen/qwen3-235b-a22b"),
            requiresApiKey = true,
        ),
        ProviderPreset(
            kind = ProviderKind.OLLAMA,
            displayName = "Ollama (local)",
            baseUrl = OLLAMA_BASE_URL,
            defaultModel = "llama3.2",
            suggestedModels = listOf("llama3.2", "qwen3", "deepseek-r1"),
            requiresApiKey = false,
        ),
        ProviderPreset(
            kind = ProviderKind.LOCAL,
            displayName = "This phone",
            baseUrl = LOCAL_BASE_URL,
            defaultModel = "",
            suggestedModels = emptyList(),
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

    /** Placeholder base URL of the on-device provider; it is never dialled. */
    const val LOCAL_BASE_URL = "local://this-phone"

    /** DeepSeek's official API (`deepseek-chat`, `deepseek-reasoner` with `reasoning_content`). */
    const val DEEPSEEK_BASE_URL = "https://api.deepseek.com"
    const val DEEPSEEK_CHAT = "deepseek-chat"
    const val DEEPSEEK_REASONER = "deepseek-reasoner"

    /** OpenRouter (`reasoning` deltas). No `HTTP-Referer`/`X-Title` attribution headers are sent. */
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

    /** A local Ollama server's OpenAI-compatible API. Works in Offline mode via the loopback exception. */
    const val OLLAMA_BASE_URL = "http://127.0.0.1:11434/v1"
}

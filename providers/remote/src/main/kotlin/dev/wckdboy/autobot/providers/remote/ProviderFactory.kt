package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.models.ComputeProfile
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.engine.llama.LocalLlm
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a [ChatProvider] for a stored [Provider].
 *
 * A fresh OkHttp client is created per call through [HttpClientFactory], so the provider always
 * uses the route that is effective *now* (global mode + the provider's routing override) and is
 * subject to the kill switch.
 */
@Singleton
class ProviderFactory @Inject constructor(
    private val httpClientFactory: HttpClientFactory,
    private val providerRepository: ProviderRepository,
    private val modelLibrary: ModelLibrary,
    private val localLlm: LocalLlm,
    private val computeProfile: ComputeProfile,
) {
    /** Creates a provider, decrypting its API key from the SecretStore. */
    suspend fun create(provider: Provider): ChatProvider = create(provider, providerRepository.apiKey(provider))

    /** Creates a provider with an explicit [apiKey] (e.g. an unsaved key being tested). */
    fun create(provider: Provider, apiKey: Secret?): ChatProvider {
        // On-device: Binder IPC to the engine process, no HTTP client at all.
        if (provider.kind == ProviderKind.LOCAL) return LocalChatProvider(modelLibrary, localLlm, computeProfile)
        val client = httpClientFactory.create(tag = provider.displayName, override = provider.routing.toOverride())
        val baseUrl = provider.baseUrl
        return when (provider.kind) {
            ProviderKind.DEEPSEEK -> DeepSeekProvider(apiKey, client, baseUrl.ifBlank { DeepSeekProvider.DEFAULT_BASE_URL })
            ProviderKind.OPENROUTER -> OpenRouterProvider(apiKey, client, baseUrl.ifBlank { OpenRouterProvider.DEFAULT_BASE_URL })
            ProviderKind.OLLAMA -> OllamaProvider(apiKey, client, baseUrl.ifBlank { OllamaProvider.DEFAULT_BASE_URL })
            ProviderKind.OPENAI_COMPATIBLE -> OpenAICompatibleProvider(baseUrl, apiKey, client)
            ProviderKind.LOCAL -> error("handled above")
        }
    }
}

fun ProviderRouting.toOverride(): RouteOverride = when (this) {
    ProviderRouting.DIRECT -> RouteOverride.DIRECT
    ProviderRouting.TOR -> RouteOverride.TOR
    ProviderRouting.SOCKS5 -> RouteOverride.SOCKS5
    ProviderRouting.INHERIT -> RouteOverride.INHERIT
}

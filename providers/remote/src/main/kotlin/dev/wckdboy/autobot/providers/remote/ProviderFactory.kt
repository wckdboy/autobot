package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.models.ComputeProfile
import dev.wckdboy.autobot.core.models.DeviceProfiler
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.models.SystemModel
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.engine.llama.LocalLlm
import dev.wckdboy.autobot.shared.invincible.JvmNodeCrypto
import dev.wckdboy.autobot.shared.invincible.NodeProviders
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

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
    private val systemModel: SystemModel,
    private val profiler: DeviceProfiler,
    private val settings: SettingsRepository,
    private val nodes: NodeConnections,
) {
    // Cached for the process: only its RAM-based fit() is used here (free storage, which would go
    // stale, is never read from it).
    private val device by lazy { profiler.profile() }

    /** Creates a provider, decrypting its API key from the SecretStore. */
    suspend fun create(provider: Provider): ChatProvider = create(provider, providerRepository.apiKey(provider))

    /** Creates a provider with an explicit [apiKey] (e.g. an unsaved key being tested). */
    fun create(provider: Provider, apiKey: Secret?): ChatProvider {
        // On-device: Binder IPC to the engine process, no HTTP client at all.
        if (provider.kind == ProviderKind.LOCAL) {
            return LocalChatProvider(
                modelLibrary,
                localLlm,
                computeProfile,
                system = systemModel.llm,
                fit = { device.fit(it.kind, it.totalBytes, it.engine) },
                speculativeMode = { settings.settings.first().speculativeMode },
                device = { device.capabilities },
            )
        }
        // A paired node: Noise sessions under the network policy; the stored "key" is the pairing,
        // never sent as one.
        if (provider.kind == ProviderKind.INVINCIBLE_NODE) {
            return NodeProviders.chatProvider(
                JvmNodeCrypto,
                apiKey?.reveal(),
                provider.displayName,
                provider.preset(),
                nodes.receipts,
                nodes.connector(provider.routing, provider.displayName),
            )
        }
        val client = httpClientFactory.create(tag = provider.displayName, override = provider.routing.toOverride())
        val preset = provider.preset()
        return OpenAICompatibleProvider(provider.baseUrl.ifBlank { preset.baseUrl }, apiKey, client, preset)
    }
}

fun ProviderRouting.toOverride(): RouteOverride = when (this) {
    ProviderRouting.DIRECT -> RouteOverride.DIRECT
    ProviderRouting.TOR -> RouteOverride.TOR
    ProviderRouting.SOCKS5 -> RouteOverride.SOCKS5
    ProviderRouting.INHERIT -> RouteOverride.INHERIT
}

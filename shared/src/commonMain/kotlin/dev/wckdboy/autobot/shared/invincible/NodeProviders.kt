package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.providers.remote.ChatEvent
import dev.wckdboy.autobot.providers.remote.ChatProvider
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ConnectionTestResult
import dev.wckdboy.autobot.providers.remote.ErrorKind
import dev.wckdboy.autobot.providers.remote.ModelListResult
import dev.wckdboy.autobot.providers.remote.ProviderPreset
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.shared.TransportChatProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The chat client for a "My node" provider: [secret] is its stored pairing ([PairedNode.toSecret]),
 * [connect] opens connections under the network policy. The secret is never an API key: the
 * provider is built with none, so nothing of it is ever sent.
 */
object NodeProviders {
    fun chatProvider(
        crypto: NodeCrypto,
        secret: String?,
        displayName: String,
        preset: ProviderPreset = ProviderPresets.forKind(dev.wckdboy.autobot.core.data.model.ProviderKind.INVINCIBLE_NODE),
        receipts: NodeReceiptLog? = null,
        privateTurns: PrivateTurns? = null,
        connect: suspend (List<String>) -> NodeLink,
    ): ChatProvider {
        val paired = secret?.let(PairedNode::fromSecret) ?: return UnavailableChatProvider("This node is not paired: pair it again")
        return TransportChatProvider(ProviderPresets.NODE_BASE_URL, null, paired.transport(crypto, receipts, privateTurns, connect), displayName, preset = preset)
    }
}

/** A provider that cannot be used here; every call says why. */
class UnavailableChatProvider(private val reason: String) : ChatProvider {
    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flowOf(ChatEvent.Error(ErrorKind.BAD_REQUEST, reason))
    override suspend fun testConnection(): ConnectionTestResult = ConnectionTestResult.of(listModels(), hasEndpoint = true)
    override suspend fun listModels(): ModelListResult = ModelListResult.Failure(ErrorKind.BAD_REQUEST, reason)
}

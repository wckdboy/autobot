package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.shared.invincible.JvmNodeCrypto
import dev.wckdboy.autobot.shared.invincible.NodeActivity
import dev.wckdboy.autobot.shared.invincible.NodeAgent
import dev.wckdboy.autobot.shared.invincible.PairedNode
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Autobot Agent on a paired node (spec/agent.md), reached like the node's models: Noise
 * sessions under the network policy, every connection in the audit log.
 */
@Singleton
class NodeAgents @Inject constructor(
    private val providers: ProviderRepository,
    private val nodes: NodeConnections,
) {
    /** The agent on [provider]'s node, with the name this device is paired as; null when it is not a usable pairing. */
    suspend fun forProvider(provider: Provider): Pair<NodeAgent, String>? {
        if (provider.kind != ProviderKind.INVINCIBLE_NODE) return null
        val paired = providers.apiKey(provider)?.reveal()?.let(PairedNode::fromSecret) ?: return null
        val tag = "${provider.displayName} · agent"
        return NodeAgent(paired.transport(JvmNodeCrypto, connect = nodes.connector(provider.routing, tag)), tag) to paired.deviceName
    }

    /**
     * What [provider]'s node and its agent did and know (spec/audit.md): every receipt checked
     * against the key pinned at pairing. Null when it is not a usable pairing.
     */
    suspend fun activity(provider: Provider): NodeActivity? {
        if (provider.kind != ProviderKind.INVINCIBLE_NODE) return null
        val paired = providers.apiKey(provider)?.reveal()?.let(PairedNode::fromSecret) ?: return null
        val tag = "${provider.displayName} · activity"
        val agent = NodeAgent(paired.transport(JvmNodeCrypto, connect = nodes.connector(provider.routing, tag)), tag)
        return agent.activity(JvmNodeCrypto, paired.name, paired.receiptKey)
    }
}

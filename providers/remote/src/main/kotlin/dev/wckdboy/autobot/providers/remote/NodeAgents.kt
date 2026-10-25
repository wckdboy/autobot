package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.shared.invincible.JvmNodeCrypto
import dev.wckdboy.autobot.shared.invincible.NodeActivity
import dev.wckdboy.autobot.shared.invincible.NodeAgent
import dev.wckdboy.autobot.shared.invincible.NodeBotsRepository
import dev.wckdboy.autobot.shared.invincible.NodeDigest
import dev.wckdboy.autobot.shared.invincible.NodeDigestRecipe
import dev.wckdboy.autobot.shared.invincible.NodeDigests
import dev.wckdboy.autobot.shared.invincible.PairedNode
import dev.wckdboy.autobot.shared.invincible.NodeReceiptLog
import dev.wckdboy.autobot.shared.recipes.NodeRecipeTarget
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
        return NodeAgent(paired.transport(JvmNodeCrypto, connect = nodes.connector(provider.routing, tag)), tag, paired.capabilities) to paired.deviceName
    }

    /** The bots on [provider]'s node (bots mode); null when it is not a usable pairing. */
    suspend fun bots(provider: Provider): NodeBotsRepository? = forProvider(provider)?.let { NodeBotsRepository(it.first) }

    /**
     * What [provider]'s node and its agent did and know (spec/audit.md): every receipt checked
     * against the key pinned at pairing. Null when it is not a usable pairing.
     */
    /** [provider]'s node as recipes use it: its agent, and its receipts checked against the pinned key. */
    suspend fun recipeTarget(provider: Provider): NodeRecipeTarget? {
        if (provider.kind != ProviderKind.INVINCIBLE_NODE) return null
        val paired = providers.apiKey(provider)?.reveal()?.let(PairedNode::fromSecret) ?: return null
        val tag = "${provider.displayName} · recipes"
        val transport = paired.transport(JvmNodeCrypto, connect = nodes.connector(provider.routing, tag))
        return NodeRecipeTarget(NodeAgent(transport, tag, paired.capabilities), paired.name) { id ->
            NodeReceiptLog.fetch(JvmNodeCrypto, transport, paired.name, paired.receiptKey, id)
        }
    }

    suspend fun activity(provider: Provider): NodeActivity? {
        if (provider.kind != ProviderKind.INVINCIBLE_NODE) return null
        val paired = providers.apiKey(provider)?.reveal()?.let(PairedNode::fromSecret) ?: return null
        val tag = "${provider.displayName} · activity"
        val agent = NodeAgent(paired.transport(JvmNodeCrypto, connect = nodes.connector(provider.routing, tag)), tag, paired.capabilities)
        return agent.activity(JvmNodeCrypto, paired.name, paired.receiptKey)
    }

    /**
     * "While you were away" for [provider]'s node: what it did after [since] (Unix seconds), with
     * [recipes] for the result chats; this device's own requests left out. Null when it is not a usable pairing.
     */
    suspend fun digest(provider: Provider, since: Long, recipes: List<NodeDigestRecipe>): NodeDigest? {
        if (provider.kind != ProviderKind.INVINCIBLE_NODE) return null
        val paired = providers.apiKey(provider)?.reveal()?.let(PairedNode::fromSecret) ?: return null
        val tag = "${provider.displayName} · digest"
        val agent = NodeAgent(paired.transport(JvmNodeCrypto, connect = nodes.connector(provider.routing, tag)), tag, paired.capabilities)
        return NodeDigests.fetch(agent, JvmNodeCrypto, paired.name, paired.receiptKey, provider.id, since, recipes, exceptDevice = paired.deviceName)
    }
}

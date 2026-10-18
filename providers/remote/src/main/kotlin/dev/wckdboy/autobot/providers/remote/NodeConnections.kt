package dev.wckdboy.autobot.providers.remote

import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.shared.invincible.JvmNodeConnector
import dev.wckdboy.autobot.shared.invincible.NodeLink
import dev.wckdboy.autobot.shared.invincible.NodeReceiptLog
import dev.wckdboy.autobot.shared.invincible.NodePolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connections to paired Invincible nodes under the live network policy: the kill switch, the
 * global mode and the provider's routing override, every attempt in the audit log.
 */
@Singleton
class NodeConnections @Inject constructor(
    private val policy: NetworkPolicy,
    private val auditLog: AuditLog,
) {
    /** The nodes' own receipts for agent turns, read by the privacy-receipt plugin. */
    val receipts = NodeReceiptLog()

    /** Opens a connection to one of a node's addresses, tagged [tag] in the audit log. */
    fun connector(routing: ProviderRouting, tag: String): suspend (List<String>) -> NodeLink {
        val connector = JvmNodeConnector(
            policy = {
                NodePolicy(policy.mode.value, policy.socksFallback.value, policy.allowLoopbackWhenOffline.value, routing.toOverride())
            },
            audit = auditLog,
            tag = tag,
        )
        return { addresses -> connector.open(addresses) }
    }
}

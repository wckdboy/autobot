package dev.wckdboy.autobot.shared.invincible

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A node this device paired with: everything a "My node" provider needs, including this device's
 * private key. [toSecret] is one string, stored encrypted where a provider's API key is (never
 * logged, never exported in clear, wiped with the provider or a panic wipe).
 */
class PairedNode(
    /** The node's name, as it told us when pairing. */
    val name: String,
    /** The name the node pinned this device under. */
    val deviceName: String,
    val nodeKey: ByteArray,
    val addresses: List<String>,
    /** The node's receipt key (spec/receipts.md), pinned at pairing; null for a node without one. */
    val receiptKey: String?,
    val device: NodeKeyPair,
) {
    val fingerprint: String get() = fingerprint(nodeKey)

    @OptIn(ExperimentalEncodingApi::class)
    fun toSecret(): String = json.encodeToString(
        Stored.serializer(),
        Stored(0, name, deviceName, b64.encode(nodeKey), addresses, receiptKey, b64.encode(device.privateKey), b64.encode(device.publicKey)),
    )

    /**
     * An [NodeHttpTransport] to this node; [connect] opens connections under the network policy.
     * With [receipts], the node's receipts for agent turns are kept for those turns' privacy receipts;
     * [privateTurns] are sent as private (the node keeps them on its own models).
     */
    fun transport(
        crypto: NodeCrypto,
        receipts: NodeReceiptLog? = null,
        privateTurns: PrivateTurns? = null,
        connect: suspend (List<String>) -> NodeLink,
    ): NodeHttpTransport {
        val plain = NodeHttpTransport(crypto, nodeKey, device, { connect(addresses) })
        if (receipts == null && privateTurns == null) return plain
        // Answers made for agent turns: their signed receipts are fetched (over [plain]) and checked
        // with the pinned key when the turn's receipt is built.
        val onRequestId: (suspend (dev.wckdboy.autobot.agent.core.TurnTrace, String) -> Unit)? = receipts?.let { log ->
            { trace, id -> log.record(trace) { NodeReceiptLog.fetch(crypto, plain, name, receiptKey, id) } }
        }
        return NodeHttpTransport(crypto, nodeKey, device, { connect(addresses) }, onRequestId = onRequestId, privateTurns = privateTurns)
    }

    override fun toString(): String = "PairedNode($name, $fingerprint, $addresses)"

    @Serializable
    private class Stored(
        val v: Int,
        val name: String,
        val device_name: String,
        val node_key: String,
        val addresses: List<String>,
        val receipt_key: String?,
        val device_private: String,
        val device_public: String,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @OptIn(ExperimentalEncodingApi::class)
        private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        /** Reads [toSecret]'s output; null when it is not one. */
        @OptIn(ExperimentalEncodingApi::class)
        fun fromSecret(secret: String): PairedNode? = runCatching {
            val s = json.decodeFromString(Stored.serializer(), secret)
            require(s.v == 0)
            val nodeKey = b64.decode(s.node_key).also { require(it.size == 32) }
            val private = b64.decode(s.device_private).also { require(it.size == 32) }
            val public = b64.decode(s.device_public).also { require(it.size == 32) }
            PairedNode(s.name, s.device_name, nodeKey, s.addresses, s.receipt_key, NodeKeyPair(private, public))
        }.getOrNull()

        /**
         * Pairs with the node in [code] (from a QR code or a pasted / deep-linked
         * `invincible://pair?…`) under [deviceName], with a fresh device key. [connect] opens a
         * connection to one of the node's addresses under the network policy.
         */
        suspend fun pair(crypto: NodeCrypto, code: PairingCode, deviceName: String, connect: suspend (List<String>) -> NodeLink): PairedNode {
            val device = NodeKeyPair.generate(crypto)
            val paired = InvincibleNode.pair(crypto, connect(code.addresses), code, device, deviceName)
            paired.channel.close()
            return PairedNode(paired.node, paired.device, code.nodeKey, code.addresses, paired.receiptKey, device)
        }
    }
}

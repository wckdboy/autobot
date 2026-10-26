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
    /**
     * What the node granted this device at pairing (spec/pairing.md, Capabilities: `shard`, `agent`,
     * `audit`, `dispatch`); null when unknown: a node from before capabilities says nothing, and a
     * pairing stored before Autobot read them has none. The operator can change the set later
     * without a new pairing, so it only words a refusal; it never decides what to ask for. The
     * spec reads a reply without the field as `shard`; here it stays unknown, because such a node
     * has no `--capabilities` to grant with, and the refusal says to update it.
     */
    val capabilities: List<String>? = null,
) {
    val fingerprint: String get() = fingerprint(nodeKey)

    @OptIn(ExperimentalEncodingApi::class)
    fun toSecret(): String = json.encodeToString(
        Stored.serializer(),
        Stored(0, name, deviceName, b64.encode(nodeKey), addresses, receiptKey, b64.encode(device.privateKey), b64.encode(device.publicKey), capabilities),
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
        val plain = NodeHttpTransport(crypto, nodeKey, device, { connect(addresses) }, node = name, addresses = addresses)
        if (receipts == null && privateTurns == null) return plain
        // Answers made for agent turns: their signed receipts are fetched (over [plain]) and checked
        // with the pinned key when the turn's receipt is built.
        val onRequestId: (suspend (dev.wckdboy.autobot.agent.core.TurnTrace, String) -> Unit)? = receipts?.let { log ->
            { trace, id -> log.record(trace) { NodeReceiptLog.fetch(crypto, plain, name, receiptKey, id) } }
        }
        return NodeHttpTransport(
            crypto, nodeKey, device, { connect(addresses) },
            onRequestId = onRequestId, privateTurns = privateTurns, node = name, addresses = addresses,
        )
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
        // Added within version 0: absent from older pairings (unknown), ignored by older apps.
        val capabilities: List<String>? = null,
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
            PairedNode(s.name, s.device_name, nodeKey, s.addresses, s.receipt_key, NodeKeyPair(private, public), s.capabilities)
        }.getOrNull()

        /**
         * Pairs with the node in [code] (from a QR code or a pasted / deep-linked
         * `invincible://pair?…`) under [deviceName], with a fresh device key, or [previous]'s when
         * it is a pairing with the same node. [connect] opens a connection to one of the node's
         * addresses under the network policy. Failures: [NodeBlockedException],
         * [NodeConnectException], [NodeRefusedException], [NodeTimeoutException] ([NodeErrors.pairing] words them).
         */
        suspend fun pair(
            crypto: NodeCrypto,
            code: PairingCode,
            deviceName: String,
            previous: PairedNode? = null,
            connect: suspend (List<String>) -> NodeLink,
        ): PairedNode {
            // Pairing again with a node this device has: the same key, so the node keeps one entry
            // for it (renamed) instead of a second one.
            val device = previous?.takeIf { it.nodeKey.contentEquals(code.nodeKey) }?.device ?: NodeKeyPair.generate(crypto)
            val link = open(code.addresses, connect)
            val paired = try {
                InvincibleNode.pair(crypto, link, code, device, deviceName)
            } catch (e: Exception) {
                link.close()
                throw e
            }
            paired.channel.close()
            return PairedNode(paired.node, paired.device, code.nodeKey, code.addresses, paired.receiptKey, device, paired.capabilities)
        }

        /** Whether [secret] (a stored pairing) is with the node [code] names: pairing again replaces it. */
        fun sameNode(secret: String?, code: PairingCode): Boolean =
            secret?.let(::fromSecret)?.nodeKey?.contentEquals(code.nodeKey) == true

        /** [connect], with a failure that is not the network policy's as [NodeConnectException]. */
        internal suspend fun open(addresses: List<String>, connect: suspend (List<String>) -> NodeLink): NodeLink = try {
            connect(addresses)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: NodeBlockedException) {
            throw e
        } catch (e: NodeConnectException) {
            throw e
        } catch (e: Exception) {
            throw NodeConnectException(e.message ?: "could not reach the node")
        }
    }
}

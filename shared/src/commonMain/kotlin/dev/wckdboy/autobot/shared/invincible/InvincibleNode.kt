package dev.wckdboy.autobot.shared.invincible

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Talking to an Invincible node, Autobot's own hardware tier ("My node"), as the node's
 * `spec/pairing.md` defines it: a pairing code from a QR code, a Noise handshake that pins both
 * keys, then HTTP/1.1 to the node's OpenAI-compatible API inside the encrypted channel.
 */

/** What a node shows as a QR code (spec/pairing.md, Pairing code). */
class PairingCode(val nodeKey: ByteArray, val secret: ByteArray, val addresses: List<String>) {
    override fun toString(): String = "PairingCode(node=${fingerprint(nodeKey)}, addresses=$addresses)"

    companion object {
        private const val PREFIX = "invincible://pair?"

        /** Parses a code; null when it is not a valid version-0 code. */
        @OptIn(ExperimentalEncodingApi::class)
        fun parse(text: String): PairingCode? {
            val query = text.trim().removePrefix(PREFIX).takeIf { it.length < text.trim().length } ?: return null
            val fields = HashMap<String, String>()
            for (pair in query.split('&')) {
                val eq = pair.indexOf('=')
                val key = if (eq > 0) pair.substring(0, eq) else continue
                if (key !in fields) fields[key] = pair.substring(eq + 1)
            }
            if (fields["v"] != "0") return null
            fun key32(name: String): ByteArray? {
                val value = fields[name] ?: return null
                if (value.endsWith('=')) return null
                return runCatching { Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).decode(value) }.getOrNull()?.takeIf { it.size == 32 }
            }
            val addresses = fields["a"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (addresses.isEmpty()) return null
            return PairingCode(key32("k") ?: return null, key32("s") ?: return null, addresses)
        }
    }
}

/** A byte stream to the node (a TCP connection, routed by the platform through the network policy). */
interface NodeLink {
    /** Exactly [count] bytes; throws at end of stream. */
    suspend fun readExactly(count: Int): ByteArray

    suspend fun write(bytes: ByteArray)

    fun close()
}

/** u16 big-endian length, then the bytes (spec/pairing.md, Connection). */
internal suspend fun NodeLink.readFrame(): ByteArray {
    val header = readExactly(2)
    val length = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
    return if (length == 0) ByteArray(0) else readExactly(length)
}

internal suspend fun NodeLink.writeFrame(frame: ByteArray) {
    require(frame.size <= MAX_FRAME) { "frame too large" }
    write(byteArrayOf((frame.size ushr 8).toByte(), frame.size.toByte()) + frame)
}

private const val MAX_FRAME = 65535
private const val MAX_PLAIN = MAX_FRAME - NoiseHandshake.TAG_LEN
private const val MODE_PAIR: Byte = 1
private const val MODE_SESSION: Byte = 2
private val PAIR_PROLOGUE = "invincible/0 pair".encodeToByteArray()
private val SESSION_PROLOGUE = "invincible/0 session".encodeToByteArray()

/** An encrypted channel to a node; the bytes inside are HTTP/1.1. */
class NodeChannel internal constructor(private val link: NodeLink, private val transport: NoiseTransport) {
    /** Sends [bytes], in as many frames as it takes. */
    suspend fun send(bytes: ByteArray) {
        var at = 0
        while (at < bytes.size) {
            val end = minOf(at + MAX_PLAIN, bytes.size)
            link.writeFrame(transport.encrypt(bytes.copyOfRange(at, end)))
            at = end
        }
    }

    /** The next chunk of bytes from the node. Throws when the stream ends or a frame does not authenticate. */
    suspend fun receive(): ByteArray = transport.decrypt(link.readFrame())

    fun close() = link.close()
}

/** What pairing told the device. Pin [nodeKey] and [receiptKey]. */
class Paired(val node: String, val device: String, val nodeKey: ByteArray, val receiptKey: String?, val channel: NodeChannel)

object InvincibleNode {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Pairs [device] with the node in [code] over [link] (connected to one of `code.addresses`),
     * under [name]. Throws [NodeRefusedException] when the code is wrong, used or expired, or the
     * node is not the one the code names; [NodeTimeoutException] past [timeoutMillis].
     */
    suspend fun pair(
        crypto: NodeCrypto,
        link: NodeLink,
        code: PairingCode,
        device: NodeKeyPair,
        name: String,
        timeoutMillis: Long = NodeErrors.HANDSHAKE_MILLIS,
    ): Paired {
        val handshake = NoiseHandshake.initiator(crypto, device, code.nodeKey, PAIR_PROLOGUE, psk = code.secret)
        val hello = buildJsonObject { put("name", name) }.toString().encodeToByteArray()
        // A wrong, used or expired code: the node closes the connection without a reply.
        val reply = link.handshake(timeoutMillis) {
            link.write(byteArrayOf(MODE_PAIR))
            link.writeFrame(handshake.write(hello))
            json.parseToJsonElement(handshake.read(link.readFrame()).decodeToString()) as JsonObject
        }
        return Paired(
            node = reply["node"]?.jsonPrimitive?.content ?: "node",
            device = reply["device"]?.jsonPrimitive?.content ?: name,
            nodeKey = code.nodeKey,
            receiptKey = reply["receipts"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() },
            channel = NodeChannel(link, handshake.split()),
        )
    }

    /**
     * Opens an encrypted session to a node whose key [device] pinned when it paired. Throws
     * [NodeRefusedException] when the node no longer knows [device], [NodeTimeoutException] past [timeoutMillis].
     */
    suspend fun session(
        crypto: NodeCrypto,
        link: NodeLink,
        nodeKey: ByteArray,
        device: NodeKeyPair,
        timeoutMillis: Long = NodeErrors.HANDSHAKE_MILLIS,
    ): NodeChannel {
        val handshake = NoiseHandshake.initiator(crypto, device, nodeKey, SESSION_PROLOGUE)
        // A device the node no longer knows: it closes the connection without a reply.
        link.handshake(timeoutMillis) {
            link.write(byteArrayOf(MODE_SESSION))
            link.writeFrame(handshake.write(ByteArray(0)))
            handshake.read(link.readFrame())
        }
        return NodeChannel(link, handshake.split())
    }
}

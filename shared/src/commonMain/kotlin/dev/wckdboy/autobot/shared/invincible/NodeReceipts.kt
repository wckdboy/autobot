package dev.wckdboy.autobot.shared.invincible

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A node's signed receipts (the node's spec/receipts.md): verified against the receipt key pinned
 * at pairing before Autobot shows anything a receipt claims.
 */
object NodeReceipts {
    private val PREFIX = "invincible-receipt/0\n".encodeToByteArray()

    @OptIn(ExperimentalEncodingApi::class)
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** Whether [signature] is [publicKey]'s signature of [receipt]. */
    @OptIn(ExperimentalEncodingApi::class)
    fun verify(crypto: NodeCrypto, publicKey: String, receipt: JsonElement, signature: String): Boolean {
        val key = runCatching { base64.decode(publicKey) }.getOrNull()?.takeIf { it.size == 32 } ?: return false
        val sig = runCatching { base64.decode(signature) }.getOrNull()?.takeIf { it.size == 64 } ?: return false
        val canonical = canonical(receipt) ?: return false
        return runCatching { crypto.ed25519Verify(key, PREFIX + canonical.encodeToByteArray(), sig) }.getOrDefault(false)
    }

    /**
     * RFC 8785 canonical JSON for what receipts hold (integers, strings, booleans, null, arrays,
     * objects with ASCII keys); null for anything else (a floating-point number, a non-ASCII key).
     */
    fun canonical(element: JsonElement): String? {
        val out = StringBuilder()
        fun write(e: JsonElement): Boolean = when (e) {
            JsonNull -> out.append("null").let { true }
            is JsonPrimitive -> when {
                e.isString -> string(e.content, out).let { true }
                e.content == "true" || e.content == "false" -> out.append(e.content).let { true }
                e.content.toLongOrNull() != null -> out.append(e.content.toLong()).let { true }
                else -> false
            }
            is JsonArray -> {
                out.append('[')
                var ok = true
                e.forEachIndexed { i, item -> if (i > 0) out.append(','); ok = ok && write(item) }
                out.append(']')
                ok
            }
            is JsonObject -> {
                if (e.keys.any { k -> k.any { it.code > 127 } }) {
                    false
                } else {
                    out.append('{')
                    var ok = true
                    e.keys.sorted().forEachIndexed { i, k ->
                        if (i > 0) out.append(',')
                        string(k, out)
                        out.append(':')
                        ok = ok && write(e.getValue(k))
                    }
                    out.append('}')
                    ok
                }
            }
        }
        return if (write(element)) out.toString() else null
    }

    /** A JSON string escaped as JCS does: the short escapes, other control characters as `\u00xx`. */
    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) out.append("\\u00").append(c.code.toString(16).padStart(2, '0')) else out.append(c)
            }
        }
        out.append('"')
    }
}

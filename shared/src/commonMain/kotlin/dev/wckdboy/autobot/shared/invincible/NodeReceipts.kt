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
    /** One per receipt version, picked by the receipt's own `version` (spec/receipts.md, Signature). */
    private val PREFIXES = listOf("invincible-receipt/0\n", "invincible-receipt/1\n").map { it.encodeToByteArray() }

    /**
     * A JSON number (RFC 8259, section 6). The tree parser also lets through unquoted literals JSON
     * does not have (`1f`, `+1`, `01`), which the node never writes, so they are refused.
     */
    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /** What a version 1 receipt must carry, or it is not what it says (the node's `receipts::check`). */
    private val BOUND = listOf("at", "caller", "request_sha256", "answer_sha256", "answer_bytes", "answer_complete")

    @OptIn(ExperimentalEncodingApi::class)
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /**
     * Whether [signature] is [publicKey]'s signature of [receipt], as the node checks it: a version
     * it knows (0 or 1; no other, and none without one), naming [publicKey] as its node, signed
     * under that version's prefix, and for version 1 carrying the fields that bind the exchange.
     * The request and answer hashes are not recomputed here.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun verify(crypto: NodeCrypto, publicKey: String, receipt: JsonElement, signature: String): Boolean {
        val fields = receipt as? JsonObject ?: return false
        val version = (fields["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.content
            ?.takeIf(NUMBER::matches)?.toIntOrNull() ?: return false
        val prefix = PREFIXES.getOrNull(version) ?: return false
        if ((fields["node"] as? JsonPrimitive)?.takeIf { it.isString }?.content != publicKey) return false
        if (version >= 1 && BOUND.any { it !in fields }) return false
        val key = runCatching { base64.decode(publicKey) }.getOrNull()?.takeIf { it.size == 32 } ?: return false
        val sig = runCatching { base64.decode(signature) }.getOrNull()?.takeIf { it.size == 64 } ?: return false
        val canonical = canonical(receipt) ?: return false
        return runCatching { crypto.ed25519Verify(key, prefix + canonical.encodeToByteArray(), sig) }.getOrDefault(false)
    }

    /**
     * RFC 8785 (JCS) canonical JSON, as the node writes it: keys sorted by UTF-16 code units,
     * minimal string escapes, integers plainly and other numbers as ECMAScript writes them. Null
     * for what JSON cannot carry (a number that is not finite, a lone surrogate).
     */
    fun canonical(element: JsonElement): String? {
        val out = StringBuilder()
        fun write(e: JsonElement): Boolean = when (e) {
            JsonNull -> out.append("null").let { true }
            is JsonPrimitive -> when {
                e.isString -> string(e.content, out)
                e.content == "true" || e.content == "false" -> out.append(e.content).let { true }
                else -> number(e.content)?.let { out.append(it) } != null
            }
            is JsonArray -> {
                out.append('[')
                var ok = true
                e.forEachIndexed { i, item -> if (i > 0) out.append(','); ok = ok && write(item) }
                out.append(']')
                ok
            }
            is JsonObject -> {
                out.append('{')
                var ok = true
                // String order is UTF-16 code unit order: what JCS sorts by, not UTF-8 bytes.
                e.keys.sorted().forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    ok = ok && string(k, out)
                    out.append(':')
                    ok = ok && write(e.getValue(k))
                }
                out.append('}')
                ok
            }
        }
        return if (write(element)) out.toString() else null
    }

    /**
     * A JSON string escaped as JCS does: the short escapes, other control characters as `\u00xx`;
     * false for a lone surrogate, which has no UTF-8 form.
     */
    private fun string(s: String, out: StringBuilder): Boolean {
        out.append('"')
        for ((i, c) in s.withIndex()) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (c.isHighSurrogate() && s.getOrNull(i + 1)?.isLowSurrogate() != true) return false
                    if (c.isLowSurrogate() && s.getOrNull(i - 1)?.isHighSurrogate() != true) return false
                    if (c.code < 0x20) out.append("\\u00").append(c.code.toString(16).padStart(2, '0')) else out.append(c)
                }
            }
        }
        out.append('"')
        return true
    }

    /**
     * A JSON number from its [literal], as the node writes it: one that fits a 64-bit integer
     * plainly (`-2`, `4096`), any other as ECMAScript's Number::toString of the double it parses to
     * (`0.1`, `2.5`, `1e+21`, `1e-7`, `-0` → `0`). Null for one that is not finite, or not JSON.
     */
    internal fun number(literal: String): String? {
        if (!NUMBER.matches(literal)) return null
        literal.toLongOrNull()?.let { return it.toString() }
        literal.toULongOrNull()?.let { return it.toString() }
        val x = literal.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        if (x == 0.0) return "0"
        val (digits, n) = Shortest.of(kotlin.math.abs(x))
        val k = digits.length
        val body = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val mantissa = if (k > 1) digits.substring(0, 1) + "." + digits.substring(1) else digits
                mantissa + "e" + (if (n > 0) "+" else "-") + kotlin.math.abs(n - 1)
            }
        }
        return if (x < 0) "-$body" else body
    }

    /**
     * The shortest decimal that reads back as a double, and of those the closest (an even last
     * digit on a tie): ECMAScript's `k`, `n` and `s`. Worked out exactly from the double's binary
     * value and the bounds of the numbers that round to it, so it does not depend on how a
     * platform prints or parses doubles.
     */
    private object Shortest {
        /** `0.digits × 10^point`, with no leading or trailing zeros in [digits]. */
        private class Decimal(val digits: String, val point: Int) : Comparable<Decimal> {
            override fun compareTo(other: Decimal): Int {
                if (point != other.point) return point.compareTo(other.point)
                for (i in 0 until maxOf(digits.length, other.digits.length)) {
                    val a = digits.getOrElse(i) { '0' }
                    val b = other.digits.getOrElse(i) { '0' }
                    if (a != b) return a.compareTo(b)
                }
                return 0
            }

            companion object {
                fun of(digits: String, point: Int): Decimal {
                    val lead = digits.indexOfFirst { it != '0' }
                    return Decimal(digits.substring(lead).trimEnd('0'), point - lead)
                }
            }
        }

        /** [digits] and `n`, for a positive finite [x]: `x` is `0.digits × 10^n`. */
        fun of(x: Double): Pair<String, Int> {
            val bits = x.toRawBits()
            val biased = ((bits ushr 52) and 0x7ff).toInt()
            val fraction = bits and ((1L shl 52) - 1)
            val m = if (biased == 0) fraction else fraction or (1L shl 52)
            val e = if (biased == 0) -1074 else biased - 1075
            val exact = exact(m, e)
            // Halfway to the neighbours: below is half as far when x is a power of two (not the least normal).
            val high = exact(2 * m + 1, e - 1)
            val low = if (fraction == 0L && biased > 1) exact(4 * m - 1, e - 2) else exact(2 * m - 1, e - 1)
            // A decimal exactly halfway reads back as the double with the even significand.
            val inclusive = m % 2 == 0L
            fun reads(d: Decimal) = (d > low && d < high) || (inclusive && (d.compareTo(low) == 0 || d.compareTo(high) == 0))
            val d = exact.digits
            for (k in 1 until d.length) {
                val down = Decimal.of(d.substring(0, k), exact.point)
                val up = increment(d.substring(0, k)).let { Decimal.of(it, exact.point + it.length - k) }
                val pick = when {
                    reads(down) && reads(up) -> {
                        // The closer one; on a tie, the even one.
                        val rest = d.substring(k)
                        val half = "5".padEnd(rest.length, '0')
                        when {
                            rest > half -> up
                            rest < half -> down
                            (d[k - 1] - '0') % 2 == 0 -> down
                            else -> up
                        }
                    }
                    reads(down) -> down
                    reads(up) -> up
                    else -> null
                }
                if (pick != null) return pick.digits to pick.point
            }
            return d to exact.point
        }

        /** [s] (decimal digits) plus one in its last place. */
        private fun increment(s: String): String {
            val chars = s.toCharArray()
            var i = chars.size - 1
            while (i >= 0 && chars[i] == '9') chars[i--] = '0'
            return if (i < 0) "1" + chars.concatToString() else chars.also { it[i] = it[i] + 1 }.concatToString()
        }

        /** `mantissa × 2^exponent` exactly, in decimal (`× 5^-e / 10^-e` for a negative exponent). */
        private fun exact(mantissa: Long, exponent: Int): Decimal {
            // Little-endian limbs of nine decimal digits.
            val limbs = mutableListOf(mantissa % BASE, mantissa / BASE % BASE, mantissa / BASE / BASE)
            fun times(factor: Long, count: Int) {
                repeat(count) {
                    var carry = 0L
                    for (i in limbs.indices) {
                        val v = limbs[i] * factor + carry
                        limbs[i] = v % BASE
                        carry = v / BASE
                    }
                    while (carry > 0) {
                        limbs.add(carry % BASE)
                        carry /= BASE
                    }
                }
            }
            if (exponent >= 0) times(2, exponent) else times(5, -exponent)
            val text = limbs.asReversed().joinToString("") { it.toString().padStart(9, '0') }
            return Decimal.of(text, text.length - maxOf(0, -exponent))
        }

        private const val BASE = 1_000_000_000L
    }
}

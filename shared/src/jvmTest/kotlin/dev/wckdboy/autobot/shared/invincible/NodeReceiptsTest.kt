package dev.wckdboy.autobot.shared.invincible

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.bouncycastle.math.ec.rfc8032.Ed25519
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NodeReceipts] beyond the shared vectors: the version picks the prefix, and anything the node
 * would refuse is refused here too (spec/receipts.md, Signature; the node's `receipts::check`).
 * Receipts are signed here with the vectors' seed, so each case fails for its own reason.
 */
@OptIn(ExperimentalEncodingApi::class)
class NodeReceiptsTest {
    private val crypto = JvmNodeCrypto
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    private val seed = b64.decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
    private val key = b64.encode(ByteArray(Ed25519.PUBLIC_KEY_SIZE).also { Ed25519.generatePublicKey(seed, 0, it, 0) })

    private fun receipt(version: String?, vararg drop: String): JsonObject {
        val v1 = """{"request_id":"00000000000000000000000000000000","node":"$key","at":1761123456,"api":"chat",
            "caller":{"kind":"device","name":"phone","key":"q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s","fingerprint":"abab-abab-abab-abab"},
            "request_sha256":"8fd894a4f83a9479632168ec4cea1c18aeef1f38d7ce48f7051da3e6dd024a6d",
            "answer_sha256":"13256b6f8950957a6e138301d7b53690fc274816b01ee38c009476538a039ddd","answer_bytes":125,"answer_complete":true,
            "decision":{"action":"run","model":"qwen3-0.6b","tier":"node","reason":"requested"},"egress":[]${version?.let { ""","version":$it""" } ?: ""}}"""
        return JsonObject(Json.parseToJsonElement(v1).jsonObject - drop.toSet())
    }

    /** The node's signature of [receipt] under [prefix]. */
    private fun sign(receipt: JsonObject, prefix: String): String {
        val message = prefix.encodeToByteArray() + NodeReceipts.canonical(receipt)!!.encodeToByteArray()
        return b64.encode(ByteArray(Ed25519.SIGNATURE_SIZE).also { Ed25519.sign(seed, 0, message, 0, message.size, it, 0) })
    }

    @Test
    fun theVersionPicksThePrefix() {
        val v1 = receipt("1")
        assertTrue(NodeReceipts.verify(crypto, key, v1, sign(v1, "invincible-receipt/1\n")))
        assertFalse("a version 1 receipt signed as version 0", NodeReceipts.verify(crypto, key, v1, sign(v1, "invincible-receipt/0\n")))
        val v0 = receipt("0")
        assertTrue(NodeReceipts.verify(crypto, key, v0, sign(v0, "invincible-receipt/0\n")))
        assertFalse("a version 0 receipt signed as version 1", NodeReceipts.verify(crypto, key, v0, sign(v0, "invincible-receipt/1\n")))
    }

    @Test
    fun aVersion0ReceiptCannotBeRelabelledAsVersion1() {
        val v0 = receipt("0")
        val signature = sign(v0, "invincible-receipt/0\n")
        assertFalse(NodeReceipts.verify(crypto, key, JsonObject(v0 + ("version" to JsonPrimitive(1))), signature))
    }

    @Test
    fun aVersionItDoesNotKnowOrNoneIsRefused() {
        for (version in listOf("2", "-1", "\"1\"", "1.0", "null")) {
            val r = receipt(version)
            assertFalse(version, NodeReceipts.verify(crypto, key, r, sign(r, "invincible-receipt/$version\n")))
            assertFalse(version, NodeReceipts.verify(crypto, key, r, sign(r, "invincible-receipt/1\n")))
        }
        val none = receipt(null)
        assertFalse("no version", NodeReceipts.verify(crypto, key, none, sign(none, "invincible-receipt/0\n")))
    }

    @Test
    fun aVersion1ReceiptWithoutItsBindingsIsRefusedEvenWhenSigned() {
        for (field in listOf("at", "caller", "request_sha256", "answer_sha256", "answer_bytes", "answer_complete")) {
            val stripped = receipt("1", field)
            assertFalse(field, NodeReceipts.verify(crypto, key, stripped, sign(stripped, "invincible-receipt/1\n")))
        }
        // Version 0 never carried them.
        val v0 = receipt("0", "at", "caller", "request_sha256", "answer_sha256", "answer_bytes", "answer_complete")
        assertTrue(NodeReceipts.verify(crypto, key, v0, sign(v0, "invincible-receipt/0\n")))
    }

    @Test
    fun aReceiptMustNameTheKeyItIsCheckedWith() {
        val other = receipt("1") + ("node" to JsonPrimitive("A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbX"))
        assertFalse(NodeReceipts.verify(crypto, key, JsonObject(other), sign(JsonObject(other), "invincible-receipt/1\n")))
    }

    @Test
    fun numbersAreWrittenAsTheNodeWritesThem() {
        val cases = mapOf(
            "4096" to "4096", "-2" to "-2", "-0" to "0", "-0.0" to "0", "1.0" to "1", "1e2" to "100",
            // Integers that fit 64 bits stay exact (the node's i64 and u64); past that, a double.
            "9007199254740993" to "9007199254740993", "18446744073709551615" to "18446744073709551615",
            "18446744073709551616" to "18446744073709552000", "-9223372036854775809" to "-9223372036854776000",
            "0.1" to "0.1", "2.5" to "2.5", "0.30000000000000004" to "0.30000000000000004",
            "1e21" to "1e+21", "1E21" to "1e+21", "1e-7" to "1e-7", "0.000001" to "0.000001", "0.0000001" to "1e-7",
            "123e-20" to "1.23e-18", "100000000000000000000000" to "1e+23", "123456789012345680000" to "123456789012345680000",
            "5e-324" to "5e-324", "1.7976931348623157e308" to "1.7976931348623157e+308", "2.2250738585072014e-308" to "2.2250738585072014e-308",
            // A tie at 17 digits: the even one, as ECMAScript says.
            "-1.1496366673247973e+15" to "-1149636667324797.2",
        )
        val wrong = cases.mapNotNull { (literal, want) -> NodeReceipts.number(literal).takeIf { it != want }?.let { "$literal: want $want, got $it" } }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
        assertNull("not finite", NodeReceipts.number("1e400"))
    }

    @Test
    fun aNumberJsonDoesNotHaveIsRefused() {
        // The tree parser lets unquoted literals through; a receipt holding one is not the node's.
        for (literal in listOf("1f", "1d", "+1", "01", "-01", "1.", ".5", "1e", "1e+", "0x10", "1_000", "Infinity", "NaN", "--1", "1.0f")) {
            assertNull(literal, NodeReceipts.number(literal))
        }
        assertNull(NodeReceipts.canonical(Json.parseToJsonElement("""{"bytes_sent":1f}""")))
        val r = JsonObject(receipt("1") + ("answer_bytes" to Json.parseToJsonElement("""{"n":125f}""").jsonObject.getValue("n")))
        assertFalse("answer_bytes 125f", NodeReceipts.verify(crypto, key, r, sign(receipt("1"), "invincible-receipt/1\n")))
        for (version in listOf("+1", "01", "+0")) {
            val odd = receipt(version)
            assertFalse(version, NodeReceipts.verify(crypto, key, odd, sign(receipt("1"), "invincible-receipt/1\n")))
            assertFalse(version, NodeReceipts.verify(crypto, key, odd, sign(receipt("0"), "invincible-receipt/0\n")))
        }
    }

    @Test
    fun keysSortByUtf16AndALoneSurrogateIsRefused() {
        val value = Json.parseToJsonElement("""{"":1,"😀":2,"é":3,"z":4}""")
        assertEquals("""{"z":4,"é":3,"😀":2,"${''}":1}""", NodeReceipts.canonical(value))
        assertNull(NodeReceipts.canonical(JsonPrimitive("a\ud800b")))
        assertNull(NodeReceipts.canonical(JsonObject(mapOf("\udc00" to JsonPrimitive(1)))))
    }
}

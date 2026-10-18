package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.shared.redaction.RedactionVault
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conformance vectors Autobot shares with Invincible (`resources/invincible`, copied by
 * `scripts/sync-invincible-spec.sh`; `SOURCE` names the Invincible commit). Each test reports every
 * failing case, not just the first.
 */
@OptIn(ExperimentalEncodingApi::class)
class InvincibleSpecTest {
    private val crypto = JvmNodeCrypto
    private val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private fun cases(name: String): List<JsonObject> {
        val text = checkNotNull(javaClass.getResource("/invincible/$name.json")) { "$name.json" }.readText()
        val file = Json.parseToJsonElement(text).jsonObject
        assertEquals(name, file["spec"]!!.jsonPrimitive.content)
        return file["cases"]!!.jsonArray.map { it.jsonObject }.also { assertTrue("no cases in $name", it.isNotEmpty()) }
    }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun check(failures: List<String>, total: Int, what: String) =
        assertTrue("${failures.size} of $total $what cases failed:\n${failures.joinToString("\n")}", failures.isEmpty())

    @Test
    fun pairingCodes() {
        val cases = cases("pairing-code")
        val failures = cases.mapNotNull { case ->
            val code = PairingCode.parse(case.str("input"))
            val expect = case.getValue("expect").jsonObject
            val ok = if (expect.containsKey("error")) {
                code == null
            } else {
                code != null &&
                    b64.encode(code.nodeKey) == expect.str("node_key") &&
                    b64.encode(code.secret) == expect.str("secret") &&
                    code.addresses == expect.getValue("addrs").jsonArray.map { it.jsonPrimitive.content }
            }
            if (ok) null else "${case.str("name")}: got $code"
        }
        check(failures, cases.size, "pairing-code")
    }

    private fun text(name: String) = checkNotNull(javaClass.getResource("/invincible/$name.json")) { name }.readText()

    @Test
    fun noiseTranscriptsMatchTheNodeByteForByte() {
        val failures = NodeConformance.noise(crypto, text("noise"))
        check(failures, cases("noise").size, "noise")
    }

    @Test
    fun aWrongPairingSecretDoesNotAuthenticate() {
        val node = NodeKeyPair.generate(crypto)
        val device = NodeKeyPair.generate(crypto)
        val prologue = "invincible/0 pair".encodeToByteArray()
        val initiator = NoiseHandshake.initiator(crypto, device, node.publicKey, prologue, psk = ByteArray(32) { 1 })
        val responder = NoiseHandshake.responder(crypto, node, prologue, psk = ByteArray(32) { 2 })
        val failed = runCatching { responder.read(initiator.write("{}".encodeToByteArray())) }.isFailure
        assertTrue("the node must not read a first message made with another secret", failed)
    }

    @Test
    fun receipts() {
        check(NodeConformance.receipts(crypto, text("receipts")), cases("receipts").size, "receipt")
    }

    /**
     * The node's redaction is a port of [RedactionVault]: the same cases must give the same text
     * here, or the phone and the node would hide different things.
     */
    @Test
    fun redactionMatchesTheNode() {
        val cases = cases("redaction")
        val failures = cases.mapNotNull { case ->
            val vault = RedactionVault()
            val input = case.str("input")
            val redacted = vault.redact(input)
            val found = redacted.placeholders.mapNotNull { vault.kindOf(it)?.tag }.groupingBy { it }.eachCount()
            val expect = case.getValue("expect").jsonObject
            val wantFound = expect.getValue("found").jsonObject.mapValues { (it.value as JsonPrimitive).content.toInt() }
            if (redacted.text == expect.str("text") && found == wantFound) null
            else "${case.str("name")}\n  expected ${expect.str("text")} $wantFound\n  got      ${redacted.text} $found"
        }
        check(failures, cases.size, "redaction")
    }
}

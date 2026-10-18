package dev.wckdboy.autobot.shared.invincible

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs the node's conformance vectors (`noise.json`, `receipts.json`) with a platform's
 * [NodeCrypto], so each platform's crypto is checked against the node: JVM tests with
 * javax.crypto, iOS tests with CryptoKit. Returns the failures, empty when everything matches.
 */
object NodeConformance {
    private fun cases(json: String): List<JsonObject> =
        Json.parseToJsonElement(json).jsonObject.getValue("cases").jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /** Full pairing and session transcripts, byte for byte, in both roles. */
    fun noise(crypto: NodeCrypto, vectors: String): List<String> = cases(vectors).flatMap { case ->
        val input = case.getValue("input").jsonObject
        val expect = case.getValue("expect").jsonObject
        val messages = expect.getValue("messages").jsonArray.map { it.jsonObject }
        val psk = input["psk"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.let(::hex)
        val prologue = hex(input.str("prologue"))
        val problems = ArrayList<String>()
        fun same(what: String, want: String, got: ByteArray) {
            if (hex(got) != want) problems += "${case.str("name")}: $what\n  expected $want\n  got      ${hex(got)}"
        }
        runCatching {
            val device = NodeKeyPair.of(crypto, hex(input.str("device_static")))
            val node = NodeKeyPair.of(crypto, hex(input.str("node_static")))
            same("device public key", expect.str("device_static_public"), device.publicKey)
            same("node public key", input.str("node_static_public"), node.publicKey)
            val initiator = NoiseHandshake.initiator(crypto, device, node.publicKey, prologue, psk, hex(input.str("device_ephemeral")))
            val responder = NoiseHandshake.responder(crypto, node, prologue, psk, hex(input.str("node_ephemeral")))
            val m1 = initiator.write(hex(messages[0].str("payload")))
            same("handshake message 1", messages[0].str("ciphertext"), m1)
            same("payload 1 as the node reads it", messages[0].str("payload"), responder.read(m1))
            same("the device key the node learns", expect.str("device_static_public"), responder.remoteStaticKey!!)
            val m2 = responder.write(hex(messages[1].str("payload")))
            same("handshake message 2", messages[1].str("ciphertext"), m2)
            same("payload 2 as the device reads it", messages[1].str("payload"), initiator.read(m2))
            val toNode = initiator.split()
            val toDevice = responder.split()
            val t1 = toNode.encrypt(hex(messages[2].str("payload")))
            same("transport device → node", messages[2].str("ciphertext"), t1)
            same("transport device → node, decrypted", messages[2].str("payload"), toDevice.decrypt(t1))
            val t2 = toDevice.encrypt(hex(messages[3].str("payload")))
            same("transport node → device", messages[3].str("ciphertext"), t2)
            same("transport node → device, decrypted", messages[3].str("payload"), toNode.decrypt(t2))
        }.onFailure { problems += "${case.str("name")}: ${it.message}" }
        problems
    }

    /** Canonical JSON and signature checks of the node's receipts. */
    fun receipts(crypto: NodeCrypto, vectors: String): List<String> = cases(vectors).mapNotNull { case ->
        val input = case.getValue("input").jsonObject
        val expect = case.getValue("expect").jsonObject
        val receipt = input.getValue("receipt")
        if (input.str("kind") == "sign") {
            val canonical = NodeReceipts.canonical(receipt)
            val valid = NodeReceipts.verify(crypto, expect.str("public_key"), receipt, expect.str("signature"))
            if (canonical == expect.str("canonical") && valid) null
            else "${case.str("name")}: canonical ${canonical == expect.str("canonical")}, valid $valid"
        } else {
            val valid = NodeReceipts.verify(crypto, input.str("public_key"), receipt, input.str("signature"))
            if (valid == expect.getValue("valid").jsonPrimitive.boolean) null else "${case.str("name")}: valid $valid"
        }
    }
}

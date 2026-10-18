package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import dev.wckdboy.autobot.shared.session.Receipts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The node's signed receipts in the phone's turn receipt: fetched over the node transport,
 * checked with the pinned key (Invincible's own receipt vectors), kept per turn.
 */
class NodeReceiptLogTest {
    private val vectors = Json.parseToJsonElement(javaClass.getResource("/invincible/receipts.json")!!.readText()).jsonObject["cases"]!!.jsonArray
        .map { it.jsonObject }
        .filter { it["input"]!!.jsonObject["kind"]!!.jsonPrimitive.content == "sign" }

    private fun case(name: String) = vectors.first { it["name"]!!.jsonPrimitive.content.startsWith(name) }

    /** Serves `GET /v1/receipts/{id}` with [envelopes] by id; 404 for others. */
    private class Node(private val envelopes: Map<String, String>) : HttpTransport {
        val asked = mutableListOf<String>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            val id = request.url.substringAfterLast('/')
            asked += request.url
            val body = envelopes[id]
            if (body == null) {
                sink.onResponse(404, emptyMap(), """{"error":{"message":"no such receipt"}}""")
            } else {
                sink.onResponse(200, emptyMap(), null)
                sink.onLine(body)
                sink.onComplete()
            }
            return Cancellable {}
        }
    }

    private fun envelope(case: JsonObject, tamper: Boolean = false): Pair<String, String> {
        val receipt = case["input"]!!.jsonObject["receipt"]!!.jsonObject
        val id = receipt["request_id"]!!.jsonPrimitive.content
        var text = """{"receipt":$receipt,"signature":"${case["expect"]!!.jsonObject["signature"]!!.jsonPrimitive.content}"}"""
        if (tamper) text = text.replace("\"egress\":[{", "\"egress\":[{\"x\":1,").replace("\"egress\":[]", "\"egress\":[{\"host\":\"evil.example\",\"route\":\"direct\",\"bytes_sent\":1,\"bytes_received\":0,\"ok\":true}]")
        return id to text
    }

    private fun key(case: JsonObject) = case["expect"]!!.jsonObject["public_key"]!!.jsonPrimitive.content

    @Test
    fun aSignedReceiptVerifiesWithThePinnedKey() = runBlocking {
        val local = case("a local run")
        val cloud = case("a cloud run")
        val node = Node(mapOf(envelope(local), envelope(cloud)))
        val kept = NodeReceiptLog.fetch(JvmNodeCrypto, node, "studio", key(local), "00000000000000000000000000000000")
        assertTrue(kept.verified)
        assertEquals("qwen3-0.6b", kept.model)
        assertEquals("node", kept.tier)
        assertTrue(kept.egress.isEmpty())

        val sent = NodeReceiptLog.fetch(JvmNodeCrypto, node, "studio", key(cloud), "9f2c9f2c9f2c9f2c9f2c9f2c9f2c9f2c")
        assertTrue(sent.verified)
        assertEquals("cloud", sent.tier)
        assertEquals(listOf(ReceiptHost("api.anthropic.com", "tor", 1, 14200, 3900)), sent.egress)
        assertEquals(mapOf("PERSON" to 2, "EMAIL" to 1), sent.redacted)
    }

    @Test
    fun anythingElseIsUnverifiedOrMissing() = runBlocking {
        val local = case("a local run")
        val other = case("a failed cloud attempt")
        val id = "00000000000000000000000000000000"
        // Tampered: an egress entry added after signing.
        val tampered = NodeReceiptLog.fetch(JvmNodeCrypto, Node(mapOf(envelope(local, tamper = true))), "studio", key(local), id)
        assertFalse(tampered.verified)
        assertEquals("evil.example", tampered.egress.single().host)
        // Signed, but by another node's key than the one pinned.
        assertFalse(NodeReceiptLog.fetch(JvmNodeCrypto, Node(mapOf(envelope(local))), "studio", key(other), id).verified)
        // A node paired without a receipt key verifies nothing.
        assertFalse(NodeReceiptLog.fetch(JvmNodeCrypto, Node(mapOf(envelope(local))), "studio", null, id).verified)
        // Asked for one id, answered with another request's receipt.
        val swapped = Node(mapOf("ffffffffffffffffffffffffffffffff" to envelope(local).second))
        assertFalse(NodeReceiptLog.fetch(JvmNodeCrypto, swapped, "studio", key(local), "ffffffffffffffffffffffffffffffff").verified)
        // Gone from the node.
        val gone = NodeReceiptLog.fetch(JvmNodeCrypto, Node(emptyMap()), "studio", key(local), id)
        assertFalse(gone.verified)
        assertEquals("the node no longer has this receipt", gone.missing)
    }

    @Test
    fun receiptsAreKeptPerTurnAndTakenOnce() = runBlocking {
        val local = case("a local run")
        val node = Node(mapOf(envelope(local)))
        val log = NodeReceiptLog()
        val id = "00000000000000000000000000000000"
        log.record(TurnTrace("chat", 1)) { NodeReceiptLog.fetch(JvmNodeCrypto, node, "studio", key(local), id) }
        log.record(TurnTrace("chat", 2)) { error("connection refused") }
        log.record(TurnTrace("other", 1)) { NodeReceiptLog.fetch(JvmNodeCrypto, node, "studio", key(local), id) }
        assertTrue(node.asked.isEmpty()) // fetched only when the turn's receipt is built
        val first = log.take("chat", 1)
        assertEquals(1, first.size)
        assertTrue(first.single().verified)
        assertTrue(log.take("chat", 1).isEmpty())
        assertEquals("connection refused", log.take("chat", 2).single().missing)
        assertEquals(1, log.take("other", 1).size)
    }

    @Test
    fun theReceiptLineSaysWhereItRan() = runBlocking {
        val local = case("a local run")
        val cloud = case("a cloud run")
        val node = Node(mapOf(envelope(local), envelope(cloud)))
        val kept = NodeReceiptLog.fetch(JvmNodeCrypto, node, "studio", key(local), "00000000000000000000000000000000")
        val receipt = Receipts.summarize(SessionEvent.TurnReceipt(turn = 1, network = emptyList(), nodes = listOf(kept)))
        assertTrue(receipt.line, receipt.line.contains("kept on studio ✓"))
        val info = receipt.nodes.single()
        assertTrue(info.verified)
        assertEquals(listOf("qwen3-0.6b"), info.models)

        val sent = NodeReceiptLog.fetch(JvmNodeCrypto, node, "studio", key(cloud), "9f2c9f2c9f2c9f2c9f2c9f2c9f2c9f2c")
        val both = Receipts.summarize(SessionEvent.TurnReceipt(turn = 1, network = emptyList(), nodes = listOf(kept, sent)))
        assertTrue(both.line, both.line.contains("studio → 1 host"))
        assertEquals(listOf("EMAIL", "PERSON").sorted(), both.nodes.single().redactions.map { it.kind }.sorted())
        assertTrue(both.nodes.single().cloud)

        val unverified = Receipts.summarize(SessionEvent.TurnReceipt(turn = 1, nodes = listOf(kept.copy(verified = false))))
        assertTrue(unverified.line, unverified.line.contains("studio unverified"))
        assertNull(Receipts.summarize(SessionEvent.TurnReceipt(turn = 1)).nodes.firstOrNull())
    }
}

package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a node granted this device at pairing (Invincible's spec/pairing.md, Capabilities), and
 * what a 404 from a route it did not grant says: how to grant it, not "update Invincible".
 */
class NodeCapabilitiesTest {
    private val crypto = JvmNodeCrypto
    private val node = NodeKeyPair.generate(crypto)
    private val secret = ByteArray(32) { 5 }
    private val code = PairingCode(node.publicKey, secret, listOf("127.0.0.1:7778"))

    /** The node's 404 for a route the device was not granted: its fallback's, byte for byte. */
    private val notGranted = """{"error":{"message":"no such endpoint","type":"not_found","code":"not_found"}}"""

    /** Two ends of an in-memory stream. */
    private class Pipe {
        val chunks = Channel<ByteArray>(Channel.UNLIMITED)
        var held = ByteArray(0)
    }

    private class MemoryLink(private val input: Pipe, private val output: Pipe) : NodeLink {
        override suspend fun readExactly(count: Int): ByteArray {
            while (input.held.size < count) input.held += input.chunks.receiveCatching().getOrNull() ?: throw IOException("closed")
            return input.held.copyOfRange(0, count).also { input.held = input.held.copyOfRange(count, input.held.size) }
        }

        override suspend fun write(bytes: ByteArray) {
            output.chunks.trySend(bytes)
        }

        override fun close() {
            output.chunks.close()
            input.chunks.close()
        }
    }

    /** Pairs with a node whose reply is [reply] (spec/pairing.md, Pairing step 3). */
    private fun pairWith(reply: JsonObject): PairedNode = runBlocking(Dispatchers.Default) {
        val toNode = Pipe()
        val toDevice = Pipe()
        val nodeSide = MemoryLink(toNode, toDevice)
        launch {
            nodeSide.readExactly(1)
            val handshake = NoiseHandshake.responder(crypto, node, "invincible/0 pair".encodeToByteArray(), psk = secret)
            handshake.read(nodeSide.readFrame())
            nodeSide.writeFrame(handshake.write(reply.toString().encodeToByteArray()))
        }
        withTimeout(10_000) { PairedNode.pair(crypto, code, "phone") { MemoryLink(toDevice, toNode) } }
    }

    private fun reply(capabilities: List<String>?) = buildJsonObject {
        put("node", "studio")
        put("device", "phone")
        put("receipts", "key")
        if (capabilities != null) put("capabilities", JsonArray(capabilities.map(::JsonPrimitive)))
    }

    /** Answers every request with [status] and [body]. */
    private class Answering(private val status: Int, private val body: String) : HttpTransport {
        val urls = mutableListOf<String>()

        override fun send(request: HttpRequestSpec, sink: HttpSink): Cancellable {
            urls += request.url
            sink.onResponse(status, emptyMap(), body)
            return Cancellable {}
        }
    }

    @Test
    fun theSetANodeGrantsIsKeptWithThePairing() {
        val paired = pairWith(reply(listOf("shard", "agent")))
        assertEquals(listOf("shard", "agent"), paired.capabilities)
        assertEquals(listOf("shard", "agent"), PairedNode.fromSecret(paired.toSecret())!!.capabilities)
        assertEquals(listOf("shard"), pairWith(reply(listOf("shard"))).capabilities)
    }

    @Test
    fun aNodeFromBeforeCapabilitiesSaysNothingAndAnOlderPairingHasNone() {
        assertNull(pairWith(reply(null)).capabilities)
        // A pairing stored before Autobot read capabilities has no such field.
        val stored = PairedNode("studio", "phone", node.publicKey, listOf("10.0.0.2:7778"), "key", NodeKeyPair.generate(crypto)).toSecret()
        assertFalse(stored, "capabilities" in stored)
        assertNull(PairedNode.fromSecret(stored)!!.capabilities)
    }

    @Test
    fun anAgentTheNodeDidNotGrantSaysHowToGrantIt() = runBlocking {
        val agent = NodeAgent(Answering(404, notGranted), "studio · agent", capabilities = listOf("shard"))
        val e = runCatching { agent.info() }.exceptionOrNull() as NodeAgentException
        assertEquals(404, e.status)
        assertEquals(NodeAgent.NO_AGENT, e.type)
        assertEquals(NodeErrors.noAgent("studio", listOf("shard")), e.message)
        assertTrue(e.message!!, "`invincible pair --capabilities shard,agent,audit`" in e.message!!)
        assertFalse(e.message!!, "invincible update" in e.message!!)
    }

    @Test
    fun withoutAKnownSetTheWordsCoverAnOlderNodeToo() = runBlocking {
        val e = runCatching { NodeAgent(Answering(404, notGranted), "studio · agent").info() }.exceptionOrNull() as NodeAgentException
        assertEquals(NodeErrors.noAgent("studio", null), e.message)
        assertTrue(e.message!!, "`invincible pair --capabilities shard,agent,audit`" in e.message!!)
        assertTrue(e.message!!, "`invincible update`" in e.message!!)
    }

    @Test
    fun anAuditLogTheNodeDidNotGrantSaysHowToGrantIt() = runBlocking {
        val transport = Answering(404, notGranted)
        val e = runCatching {
            NodeAgent(transport, "studio · agent", capabilities = listOf("shard", "agent")).requests(crypto, "studio", "key")
        }.exceptionOrNull() as NodeAgentException
        assertEquals("http://invincible/v1/audit?limit=100", transport.urls.single())
        assertEquals(NodeAgent.NO_AUDIT, e.type)
        assertEquals(NodeErrors.noAudit("studio", listOf("shard", "agent")), e.message)
        assertTrue(e.message!!, "`invincible pair --capabilities shard,agent,audit`" in e.message!!)
    }

    @Test
    fun theAdvisedGrantKeepsChat() {
        // Pairing again with a code replaces what the device held (spec/pairing.md, Capabilities):
        // a code that leaves out `shard` takes chat, models and receipts away from the phone.
        val granted = NodeErrors.GRANT.substringAfter("--capabilities ").split(',')
        assertTrue(NodeErrors.GRANT, granted.containsAll(listOf("shard", "agent", "audit")))
    }

    @Test
    fun theAdvisedGrantKeepsWhatTheDeviceHolds() = runBlocking {
        // A phone that also dispatches keeps `dispatch` when it follows the advice.
        val e = runCatching {
            NodeAgent(Answering(404, notGranted), "studio · agent", capabilities = listOf("shard", "dispatch")).info()
        }.exceptionOrNull() as NodeAgentException
        assertTrue(e.message!!, "`invincible pair --capabilities shard,agent,audit,dispatch`" in e.message!!)
        assertEquals(NodeErrors.GRANT, NodeErrors.grant(listOf("shard", "agent", "audit")))
        assertEquals(NodeErrors.GRANT, NodeErrors.grant(null))
    }

    @Test
    fun theAdviceAlsoNamesTheGrantThatNeedsNoNewPairing() = runBlocking {
        // `invincible devices grant <name> <codes>` (0.0.22 and later) replaces the set of the device
        // the node pinned under that name; a node without the command still takes a new code.
        val agent = NodeAgent(Answering(404, notGranted), "studio · agent", capabilities = listOf("shard", "dispatch"), device = "My-Phone")
        val noAgent = runCatching { agent.info() }.exceptionOrNull() as NodeAgentException
        assertEquals(
            "studio hasn't given this phone its Autobot Agent. On the node, run `invincible devices grant My-Phone shard,agent,audit,dispatch`. " +
                "If it doesn't know that command, run `invincible pair --capabilities shard,agent,audit,dispatch` and pair this phone again with that code.",
            noAgent.message,
        )
        val noAudit = runCatching { agent.requests(crypto, "studio", "key") }.exceptionOrNull() as NodeAgentException
        assertEquals(NodeErrors.noAudit("studio", listOf("shard", "dispatch"), "My-Phone"), noAudit.message)
        assertTrue(noAudit.message!!, "`invincible devices grant My-Phone shard,agent,audit,dispatch`" in noAudit.message!!)
        assertTrue(noAudit.message!!, "`invincible pair --capabilities shard,agent,audit,dispatch`" in noAudit.message!!)
        // A pairing stored before capabilities: the node may have been updated since, or may need it.
        val unknown = NodeErrors.noAgent("studio", null, "My-Phone")
        assertTrue(unknown, "`invincible devices grant My-Phone shard,agent,audit`" in unknown)
        assertTrue(unknown, "`invincible update`" in unknown)
        // Without the name, the code alone.
        assertFalse(NodeErrors.noAgent("studio", listOf("shard")), "devices grant" in NodeErrors.noAgent("studio", listOf("shard")))
    }

    @Test
    fun aNotFoundOfTheAgentsOwnIsNotWordedAsAMissingGrant() = runBlocking {
        // The agent answers `GET /v1/agent` 404 `unknown_bot` when it has no bot to answer with
        // (crates/autobot/src/api.rs): the route was reached, so the grant is not what is missing.
        val noBot = """{"error":{"message":"no bot \"\" here","type":"unknown_bot","code":"unknown_bot"}}"""
        val e = runCatching { NodeAgent(Answering(404, noBot), "studio · agent", capabilities = listOf("shard", "agent")).info() }
            .exceptionOrNull() as NodeAgentException
        assertEquals(404, e.status)
        assertEquals("unknown_bot", e.type)
        assertEquals("unknown_bot", e.code)
        assertEquals("no bot \"\" here", e.message)
        // The gate's answer is the fallback's body exactly: the agent route's own `not_found` (the
        // proxy's, for a path it will not pass on) is not it, and is passed on as the node said it.
        val proxy = """{"error":{"message":"no such agent path","type":"not_found","code":"not_found"}}"""
        val refused = runCatching { NodeAgent(Answering(404, proxy), "studio · agent", capabilities = listOf("shard")).info() }
            .exceptionOrNull() as NodeAgentException
        assertEquals("not_found", refused.type)
        assertEquals("no such agent path", refused.message)
    }

    @Test
    fun aNodeWhoseRouterAnswersAnUnknownPathWithNoBodyIsReadAsTheSame() = runBlocking {
        // 0.0.1 and 0.0.2 had no fallback: a path they do not have is a bare 404.
        val agent = NodeAgent(Answering(404, ""), "studio · agent")
        val noAgent = runCatching { agent.info() }.exceptionOrNull() as NodeAgentException
        assertEquals(NodeAgent.NO_AGENT, noAgent.type)
        assertEquals(NodeErrors.noAgent("studio", null), noAgent.message)
        val noAudit = runCatching { agent.requests(crypto, "studio", "key") }.exceptionOrNull() as NodeAgentException
        assertEquals(NodeAgent.NO_AUDIT, noAudit.type)
    }

    @Test
    fun otherFailuresAreNotWordedAsAMissingGrant() = runBlocking {
        val stopped = Answering(503, """{"error":{"message":"x","type":"agent_unavailable"}}""")
        val e = runCatching { NodeAgent(stopped, "studio · agent", capabilities = listOf("shard")).info() }.exceptionOrNull() as NodeAgentException
        assertEquals("agent_unavailable", e.type)
        assertTrue(e.message!!, "autobot serve" in e.message!!)
    }
}

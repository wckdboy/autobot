package dev.wckdboy.autobot.shared.invincible

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Autobot's node client against a real Invincible node: pairs, talks HTTP through the encrypted
 * channel, verifies a receipt with the pinned key, and is refused after unpairing.
 *
 * Runs only when `INVINCIBLE_BIN` points at an `invincible` binary (built from
 * github.com/wckdboy/invincible); the node starts with no models, so chat requests are answered
 * with a signed "block" receipt, which is all this needs.
 */
class InvincibleLiveTest {
    private val bin = System.getenv("INVINCIBLE_BIN")
    /** Optional: a GGUF model (and llama-server on the PATH) for a real streamed answer. */
    private val model = System.getenv("INVINCIBLE_MODEL")
    private lateinit var home: File
    private var node: Process? = null
    private var apiPort = 0
    private var remotePort = 0
    private val http = HttpClient.newHttpClient()
    private val crypto = JvmNodeCrypto

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Before
    fun startNode() {
        assumeTrue("set INVINCIBLE_BIN to run against a real node", bin != null && File(bin).canExecute())
        home = kotlin.io.path.createTempDirectory("invincible-live").toFile()
        apiPort = freePort()
        remotePort = freePort()
        File(home, "config.toml").writeText(
            """
            listen = "127.0.0.1:$apiPort"
            [remote]
            name = "live-node"
            listen = ["127.0.0.1:$remotePort"]
            """.trimIndent() + (model?.let { "\n\n[[models]]\nid = \"live\"\npath = \"$it\"\ncontext = 4096\n" } ?: ""),
        )
        node = ProcessBuilder(bin, "serve").apply {
            environment()["INVINCIBLE_HOME"] = home.path
            redirectErrorStream(true)
            redirectOutput(File(home, "node.log"))
        }.start()
        repeat(100) {
            if (runCatching { Socket("127.0.0.1", apiPort).close() }.isSuccess) return
            Thread.sleep(100)
        }
        error("the node did not start: ${File(home, "node.log").readText()}")
    }

    @After
    fun stopNode() {
        node?.destroy()
        node?.waitFor()
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun admin(method: String, path: String): JsonObject {
        val token = File(home, "token").readText().trim()
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$apiPort$path"))
            .header("Authorization", "Bearer $token")
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        return if (body.isBlank()) JsonObject(emptyMap()) else Json.parseToJsonElement(body).jsonObject
    }

    private class HttpAnswer(val status: Int, val headers: Map<String, String>, val body: String)

    /** One HTTP/1.1 exchange through [channel] (`Connection: close`: the answer ends with the stream). */
    private suspend fun exchange(channel: NodeChannel, method: String, path: String, body: String = ""): HttpAnswer {
        val request = buildString {
            append("$method $path HTTP/1.1\r\nhost: node\r\nconnection: close\r\n")
            if (body.isNotEmpty()) append("content-type: application/json\r\ncontent-length: ${body.encodeToByteArray().size}\r\n")
            append("\r\n").append(body)
        }
        channel.send(request.encodeToByteArray())
        val bytes = ArrayList<Byte>()
        while (true) {
            val chunk = runCatching { channel.receive() }.getOrNull() ?: break
            bytes += chunk.toList()
        }
        channel.close()
        val text = bytes.toByteArray().decodeToString()
        val head = text.substringBefore("\r\n\r\n")
        val lines = head.split("\r\n")
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        var payload = text.substringAfter("\r\n\r\n")
        if (headers["transfer-encoding"] == "chunked") payload = dechunk(payload)
        return HttpAnswer(lines[0].split(' ')[1].toInt(), headers, payload)
    }

    private fun dechunk(s: String): String {
        val out = StringBuilder()
        var rest = s
        while (true) {
            val size = rest.substringBefore("\r\n").trim().toInt(16)
            if (size == 0) return out.toString()
            rest = rest.substringAfter("\r\n")
            out.append(rest, 0, size)
            rest = rest.substring(size + 2)
        }
    }

    private fun connect() = SocketNodeLink(Socket("127.0.0.1", remotePort))

    /** Autobot's own provider code ([TransportChatProvider]) streaming from the node's real model. */
    @Test
    fun theProviderStreamsARealAnswerFromTheNode() = runBlocking {
        assumeTrue("set INVINCIBLE_MODEL to a GGUF file for a real answer", model != null && File(model).isFile)
        val code = PairingCode.parse(admin("POST", "/v1/admin/pairing")["code"]!!.jsonPrimitive.content)!!
        // As the app does it: pair, keep only the stored secret, rebuild from it.
        val secret = PairedNode.pair(crypto, code, "provider") { connect() }.toSecret()
        val transport = PairedNode.fromSecret(secret)!!.transport(crypto) { connect() }
        val provider = dev.wckdboy.autobot.shared.TransportChatProvider("http://invincible/v1", null, transport, "node")
        // The engine loads the model in the background; wait until the node lists it healthy.
        repeat(120) {
            val models = provider.listModels()
            if (models is dev.wckdboy.autobot.providers.remote.ModelListResult.Success && models.models.any { it.id == "live" }) {
                val healthy = admin("GET", "/v1/models")["data"]!!.jsonArray.any {
                    it.jsonObject["invincible"]!!.jsonObject["healthy"]!!.jsonPrimitive.content == "true"
                }
                if (healthy) return@repeat
            }
            Thread.sleep(500)
        }
        val request = dev.wckdboy.autobot.providers.remote.ChatRequest(
            "live",
            listOf(dev.wckdboy.autobot.providers.remote.ChatMessage(dev.wckdboy.autobot.providers.remote.ChatRole.USER, "Reply with exactly: node ok /no_think")),
            maxTokens = 20,
        )
        val events = kotlinx.coroutines.withTimeout(120_000) { provider.streamChat(request).toList() }
        val text = events.filterIsInstance<dev.wckdboy.autobot.providers.remote.ChatEvent.ContentDelta>().joinToString("") { it.text }
        assertTrue("events: $events", events.last() == dev.wckdboy.autobot.providers.remote.ChatEvent.Done)
        assertTrue("answer: $text", text.contains("node ok", ignoreCase = true))
        println("node answered through Autobot's provider: ${text.trim()}")
    }

    @Test
    fun pairsTalksVerifiesAndIsCutOff() = runBlocking {
        val code = PairingCode.parse(admin("POST", "/v1/admin/pairing")["code"]!!.jsonPrimitive.content)
        assertNotNull(code)
        val device = NodeKeyPair.generate(crypto)
        val paired = InvincibleNode.pair(crypto, connect(), code!!, device, "Autobot test")
        paired.channel.close()
        assertEquals("live-node", paired.node)
        assertEquals("Autobot-test", paired.device)
        val receiptKey = assertNotNull(paired.receiptKey).let { paired.receiptKey!! }
        assertTrue(admin("GET", "/v1/admin/devices")["devices"]!!.jsonArray.any { it.jsonObject["name"]!!.jsonPrimitive.content == "Autobot-test" })

        // The same code does not pair twice.
        assertTrue(runCatching { InvincibleNode.pair(crypto, connect(), code, NodeKeyPair.generate(crypto), "other") }.isFailure)

        // A session, then the node's API inside it.
        val info = exchange(InvincibleNode.session(crypto, connect(), code.nodeKey, device), "GET", "/v1/node")
        assertEquals(200, info.status)
        assertEquals(receiptKey, Json.parseToJsonElement(info.body).jsonObject["receipt_key"]!!.jsonPrimitive.content)

        val chat = exchange(
            InvincibleNode.session(crypto, connect(), code.nodeKey, device),
            "POST",
            "/v1/chat/completions",
            """{"messages":[{"role":"user","content":"hi"}]}""",
        )
        assertEquals(503, chat.status) // no models on this node
        val id = assertNotNull(chat.headers["invincible-request-id"]).let { chat.headers["invincible-request-id"]!! }
        val envelope = Json.parseToJsonElement(
            exchange(InvincibleNode.session(crypto, connect(), code.nodeKey, device), "GET", "/v1/receipts/$id").body,
        ).jsonObject
        val receipt = envelope["receipt"]!!
        assertTrue("the receipt verifies with the pinned key", NodeReceipts.verify(crypto, receiptKey, receipt, envelope["signature"]!!.jsonPrimitive.content))
        assertEquals("block", receipt.jsonObject["decision"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals(0, receipt.jsonObject["egress"]!!.jsonArray.size)

        // A stranger gets no session; after unpairing, neither does the device.
        assertTrue(runCatching { InvincibleNode.session(crypto, connect(), code.nodeKey, NodeKeyPair.generate(crypto)) }.isFailure)
        admin("DELETE", "/v1/admin/devices/Autobot-test")
        assertTrue(runCatching { InvincibleNode.session(crypto, connect(), code.nodeKey, device) }.isFailure)
    }
}

package dev.wckdboy.autobot.shared.invincible

import dev.wckdboy.autobot.agent.core.TurnTrace
import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.ReceiptNode
import dev.wckdboy.autobot.shared.HttpRequestSpec
import dev.wckdboy.autobot.shared.HttpSink
import dev.wckdboy.autobot.shared.HttpTransport
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The requests paired nodes answered during agent turns, so each turn's privacy receipt can include
 * what the node itself signed: where the request ran there and what left the node (Invincible's
 * spec/receipts.md). Ids are recorded as answers arrive; the receipts are fetched and verified only
 * when the turn's receipt is built.
 */
class NodeReceiptLog(private val maxPending: Int = 512) {
    private class Pending(val session: String, val turn: Int, val fetch: suspend () -> ReceiptNode)

    private val lock = Mutex()
    private val pending = ArrayDeque<Pending>()

    suspend fun record(trace: TurnTrace, fetch: suspend () -> ReceiptNode) = lock.withLock {
        pending.addLast(Pending(trace.sessionId, trace.turn, fetch))
        while (pending.size > maxPending) pending.removeFirst()
    }

    /** The node receipts of [turn] in [session], fetched and verified now; they are forgotten here. */
    suspend fun take(session: String, turn: Int): List<ReceiptNode> {
        val mine = lock.withLock {
            val (taken, kept) = pending.partition { it.session == session && it.turn == turn }
            pending.clear()
            pending.addAll(kept)
            taken
        }
        return mine.map { p ->
            try {
                withTimeoutOrNull(FETCH_TIMEOUT_MS) { p.fetch() } ?: ReceiptNode("", "", verified = false, missing = "the node did not answer in time")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ReceiptNode("", "", verified = false, missing = e.message ?: "could not fetch it")
            }
        }
    }

    companion object {
        private const val FETCH_TIMEOUT_MS = 15_000L
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Fetches receipt [requestId] from [node] over [transport] and checks it with [receiptKey]
         * (pinned at pairing; null for a node that had none, so nothing verifies).
         */
        suspend fun fetch(crypto: NodeCrypto, transport: HttpTransport, node: String, receiptKey: String?, requestId: String): ReceiptNode {
            val text = transport.get("http://invincible/v1/receipts/$requestId")
                ?: return ReceiptNode(node, requestId, verified = false, missing = "the node no longer has this receipt")
            val envelope = json.parseToJsonElement(text) as? JsonObject ?: error("not a receipt")
            val receipt = envelope["receipt"] as? JsonObject ?: error("not a receipt")
            val signature = (envelope["signature"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            // The receipt must be for this request and signed by the key we pinned.
            val verified = receiptKey != null &&
                receipt.text("request_id") == requestId &&
                receipt.text("node") == receiptKey &&
                NodeReceipts.verify(crypto, receiptKey, receipt, signature)
            return parse(node, requestId, verified, receipt)
        }

        fun parse(node: String, requestId: String, verified: Boolean, receipt: JsonObject): ReceiptNode {
            val decision = receipt["decision"] as? JsonObject
            val egress = (receipt["egress"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                ReceiptHost(
                    host = o.text("host") ?: return@mapNotNull null,
                    route = o.text("route") ?: "direct",
                    requests = 1,
                    bytesSent = (o["bytes_sent"] as? JsonPrimitive)?.longOrNull ?: 0,
                    bytesReceived = (o["bytes_received"] as? JsonPrimitive)?.longOrNull ?: 0,
                    tags = if ((o["ok"] as? JsonPrimitive)?.booleanOrNull == false) listOf("failed") else emptyList(),
                )
            }
            val redacted = (receipt["redactions"] as? JsonObject).orEmpty().mapNotNull { (kind, n) ->
                (n as? JsonPrimitive)?.intOrNull?.let { kind to it }
            }.toMap()
            return ReceiptNode(
                node = node,
                requestId = requestId,
                verified = verified,
                model = decision?.text("model"),
                tier = decision?.text("tier"),
                dataPolicy = receipt.text("data_policy"),
                egress = egress,
                redacted = redacted,
            )
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

        /** A GET's body; null for 404; other failures throw. */
        private suspend fun HttpTransport.get(url: String): String? = suspendCancellableCoroutine { cont ->
            val body = StringBuilder()
            val running = send(HttpRequestSpec("GET", url, mapOf("Accept" to "application/json"), null, "node receipts"), object : HttpSink {
                private var done = false

                override fun onResponse(status: Int, headers: Map<String, String>, errorBody: String?) {
                    if (status == 404) {
                        done = true
                        cont.resume(null)
                    } else if (status !in 200..299) {
                        done = true
                        cont.resumeWith(Result.failure(IllegalStateException("the node answered $status")))
                    }
                }

                override fun onLine(line: String) {
                    body.append(line).append('\n')
                }

                override fun onComplete() {
                    if (!done) cont.resume(body.toString())
                }

                override fun onFailure(blocked: Boolean, message: String) {
                    cont.resumeWith(Result.failure(IllegalStateException(message)))
                }
            })
            cont.invokeOnCancellation { running.cancel() }
        }
    }
}

package dev.wckdboy.autobot.shared.session

import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.agent.core.session.ReceiptModel
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TurnEndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The user-facing words for failed turns, and receipts as the chat shows them. */
class FailureAndReceiptTest {

    @Test
    fun everyTurnFailureDetailTheAgentWritesIsExplained() {
        val cases = mapOf(
            "AUTH: bad key" to "The provider rejected the API key. Check it in Remote › Providers (bad key)",
            "RATE_LIMIT: slow down" to "The provider is rate limiting requests. Wait a moment and send again (slow down)",
            "TRANSPORT: connection reset" to "Couldn't reach the provider (connection reset)",
            "TIMEOUT: " to "The provider didn't answer in time",
            "SERVER: 502" to "The provider had a server error (502)",
            "BLOCKED: network is off" to "Blocked by the network mode. Change it in the Privacy Center, or pick an on-device model (network is off)",
            "CONTEXT_WINDOW_EXCEEDED: prompt is too long" to "The conversation is too long for this model. Run /compact or start a new chat (prompt is too long)",
            "EMPTY_RESPONSE: The model returned an empty response" to "The model returned an empty answer. Send again (The model returned an empty response)",
            "PROTOCOL: the provider's content filter cut the answer off" to "The provider's content filter cut the answer off",
            "PROTOCOL: the model stopped mid-answer (on-device: out of memory, or the app was in the background)" to
                "The model stopped mid-answer (on-device: out of memory, or the app was in the background)",
            "BAD_REQUEST: Model provider 'x' no longer exists" to "Model provider 'x' no longer exists",
            // Not CODE: message (a pre-step block, a tool-side exception): shown as written.
            "daily budget used up" to "daily budget used up",
            "socket closed: by peer" to "socket closed: by peer",
        )
        for ((detail, expected) in cases) assertEquals(detail, expected, explainFailure(detail))
        assertEquals("The turn failed", explainFailure(null))
        assertEquals("The turn failed", explainFailure("  "))
        assertTrue(explainFailure("QUOTA: out of credit").endsWith(QUOTA_ADVICE))
    }

    @Test
    fun failedTurnsBecomeErrorNoticesAndStopsBecomeWarnings() {
        fun notice(reason: TurnEndReason, detail: String? = null): SessionItem.Notice? {
            val entries = listOf(SessionEvent.TurnStart(1), SessionEvent.TurnEnd(1, reason, detail))
                .mapIndexed { i, e -> dev.wckdboy.autobot.agent.core.session.LogEntry(i + 1L, 0, e) }
            return projectSession(entries, emptySet(), emptySet()) { n, _ -> n }.items.filterIsInstance<SessionItem.Notice>().singleOrNull()
        }
        assertEquals(null, notice(TurnEndReason.COMPLETED))
        assertEquals(SessionItem.Notice("e2", "stopped", NoticeKind.WARN), notice(TurnEndReason.ABORTED, "Cancelled by user"))
        assertEquals(NoticeKind.WARN, notice(TurnEndReason.MAX_TOKENS)!!.kind)
        assertEquals(NoticeKind.WARN, notice(TurnEndReason.MAX_STEPS)!!.kind)
        assertEquals(SessionItem.Notice("e2", "Couldn't reach the provider (reset)", NoticeKind.ERROR), notice(TurnEndReason.ERROR, "TRANSPORT: reset"))
        assertTrue(notice(TurnEndReason.BLOCKED, "BLOCKED: off")!!.text.startsWith("Blocked by the network mode"))
    }

    private fun receipt(network: List<ReceiptHost>?, vararg models: ReceiptModel) =
        Receipts.summarize(SessionEvent.TurnReceipt(3, models.toList(), listOf("read", "web_fetch", "mcp__gh__search"), network = network))

    @Test
    fun anOnDeviceTurnSaysSoAndCountsOnlyRefusedRequests() {
        val local = ReceiptModel("local", "qwen", onDevice = true, policy = "on device")
        val r = receipt(emptyList(), local)
        assertTrue(r.onDevice)
        assertEquals("on device", r.line)
        assertEquals(0, r.bytesSent)

        val blocked = receipt(listOf(ReceiptHost("api.example", "direct", 2, bytesSent = 900, blocked = 2)), local)
        assertTrue("refused before any I/O: nothing left", blocked.onDevice)
        assertEquals("on device · 2 blocked", blocked.line)
        assertEquals(0, blocked.bytesSent)

        val loopback = receipt(listOf(ReceiptHost("127.0.0.1", "loopback", 4, bytesSent = 50_000)), local)
        assertTrue(loopback.onDevice)
    }

    @Test
    fun aCloudTurnListsHostsBytesOutRoutesAndThePolicy() {
        val cloud = ReceiptModel("p", "gpt", name = "Example AI", policy = "kept 30 days")
        val r = receipt(
            listOf(
                ReceiptHost("api.example", "direct", 3, bytesSent = 10_240, bytesReceived = 4_000, tags = listOf("Example AI")),
                ReceiptHost("search.example", "tor", 1, bytesSent = 2_048, tags = listOf("agent:web_search")),
                ReceiptHost("127.0.0.1", "loopback", 1, bytesSent = 99),
            ),
            cloud,
        )
        assertFalse(r.onDevice)
        assertEquals(12_288, r.bytesSent)
        assertEquals(listOf("direct", "tor"), r.routes)
        assertEquals("2 hosts · 12 KB out · tor · kept 30 days", r.line)
        assertEquals(listOf(true, true), r.tools.filter { it.name != "read" }.map { it.network })
        assertFalse(r.tools.single { it.name == "read" }.network)
    }

    @Test
    fun anUntrackedTurnNeverClaimsToBeOnDevice() {
        val r = receipt(null, ReceiptModel("local", "qwen", onDevice = true))
        assertFalse(r.tracked)
        assertFalse(r.onDevice)
        assertEquals("receipt", r.line)
    }

    @Test
    fun theLeastPrivateModelSetsThePolicyWord() {
        val r = receipt(
            listOf(ReceiptHost("a", "direct", 1, bytesSent = 1)),
            ReceiptModel("p", "a", policy = "no retention"),
            ReceiptModel("p", "b", policy = "kept 90 days"),
            ReceiptModel("p", "c", policy = "kept 30 days"),
        )
        assertTrue(r.line, r.line.endsWith("kept 90 days"))
        val trains = receipt(listOf(ReceiptHost("a", "direct", 1)), ReceiptModel("p", "a", policy = "no retention"), ReceiptModel("p", "b", policy = "trains on prompts"))
        assertTrue(trains.line.endsWith("trains on prompts"))
    }
}

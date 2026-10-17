package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.approval.PrivateSource
import dev.wckdboy.autobot.agent.core.approval.PrivateTerm
import dev.wckdboy.autobot.agent.core.approval.TaintTracker
import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.loop.chooseRedaction
import dev.wckdboy.autobot.agent.core.loop.chosenRedaction
import dev.wckdboy.autobot.agent.core.loop.restoreRedaction
import dev.wckdboy.autobot.agent.core.policy.Compactor
import dev.wckdboy.autobot.agent.core.session.InMemorySessionStore
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import java.util.Collections
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** The agent-core side of the redaction gateway: the chat switch, memory terms, and every request through `llm/stream`. */
@OptIn(ExperimentalCoroutinesApi::class)
class RedactionHooksTest {

    @Test
    fun theChatsRedactionChoiceIsRememberedAndRestored() = runTest(timeout = 10.seconds) {
        val store = InMemorySessionStore()
        val rig = Rig(this, FakeModel())
        val agent = rig.open(store = store)
        assertNull(agent.config.value.redaction)
        agent.chooseRedaction(false)
        agent.chooseRedaction(false)
        assertEquals(false, agent.config.value.redaction)
        assertEquals(1, agent.all<SessionEvent.RedactionChanged>().size)
        agent.dispose()

        // Reopened: the choice comes back from the log.
        val again = rig.open(store = store)
        assertNull(again.config.value.redaction)
        again.restoreRedaction()
        assertEquals(false, again.config.value.redaction)
        // Back to "follow Settings".
        again.chooseRedaction(null)
        assertNull(again.chosenRedaction())
        assertNull(again.config.value.redaction)
        again.dispose()
        rig.job.cancel()
    }

    @Test
    fun receiptCountsSurviveTheLogAndOldReceiptsDecode() {
        val receipt = SessionEvent.TurnReceipt(turn = 1, redacted = mapOf("PERSON" to 2))
        val line = SessionJson.encodeToString(LogEntry.serializer(), LogEntry(1, 0, receipt))
        assertEquals(receipt, SessionJson.decodeFromString(LogEntry.serializer(), line).event)
        val old = SessionJson.decodeFromString(LogEntry.serializer(), """{"seq":1,"time":0,"event":{"type":"turn/receipt","turn":1}}""").event
        assertNull((old as SessionEvent.TurnReceipt).redacted)
        val change = SessionJson.decodeFromString(LogEntry.serializer(), """{"seq":2,"time":0,"event":{"type":"agent/redaction"}}""").event
        assertEquals(SessionEvent.RedactionChanged(null), change)
    }

    @Test
    fun theTaintIndexHandsOutMemoryTermsForTheGateway() = runTest(timeout = 10.seconds) {
        val memory = FakeTool("memory_search", privateSource = PrivateSource("memory", names = true)) { _, _ ->
            ToolOutput("The user works at Siemens as Quentin Larousse, mail q.larousse@posteo.de, locker A7X92K, PIN 4821.")
        }
        val file = FakeTool("read", privateSource = PrivateSource("workspace file")) { _, _ -> ToolOutput("PORT=8080 TOKEN=zz99yy88") }
        val rig = Rig(this, FakeModel(replies = listOf(call("c1", "memory_search"), call("c2", "read"), say("ok"))), memory, file)
        val agent = rig.open()
        agent.followup("what do you know")
        advanceUntilIdle()
        val tracker = agent.ctx.require(TaintTracker.Key)
        val terms = tracker.privateTerms(agent.id) { it == "memory" }
        val byKind = terms.groupBy({ it.kind }, { it.text })
        assertEquals(listOf("q.larousse@posteo.de"), byKind[PrivateTerm.Kind.EMAIL])
        assertTrue(byKind.toString(), "4821" in byKind[PrivateTerm.Kind.NUMBER].orEmpty())
        assertTrue(byKind.toString(), "A7X92K" in byKind[PrivateTerm.Kind.ID].orEmpty())
        assertTrue(byKind.toString(), byKind[PrivateTerm.Kind.NAME].orEmpty().containsAll(listOf("Siemens", "Quentin", "Larousse")))
        // Only the sources asked for: the workspace file's token stays out.
        assertTrue(terms.none { "zz99yy88" in it.text || it.source != "memory" })
        assertTrue(tracker.privateTerms(agent.id).any { "zz99yy88" in it.text })
        agent.dispose()
        rig.job.cancel()
    }

    @Test
    fun compactionSummariesGoThroughTheStreamHook() = runTest(timeout = 10.seconds) {
        val model = FakeModel(replies = listOf(say("first answer"), say("the summary")))
        val rig = Rig(this, model)
        val seen = Collections.synchronizedList(mutableListOf<LlmCall>())
        rig.harness.root.intercept(Hooks.LlmStream) { call, next ->
            seen += call
            next(call)
        }
        val agent = rig.open()
        agent.followup("hello")
        advanceUntilIdle()
        agent.ctx.require(Compactor.Key).compact(agent, "manual")
        advanceUntilIdle()
        assertEquals(2, model.calls.size)
        // The summary request went through `llm/stream` too, where a request plugin (redaction) sees it.
        assertEquals(2, seen.size)
        assertTrue(seen.last().isSummary)
        agent.dispose()
        rig.job.cancel()
    }
}

package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search text grows by appending: the stored text is read once per chat (not per message),
 * bursts are written once, and the appending caller never waits for the index. Before, every
 * message did a read and a full rewrite of the chat's text under one global lock, inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchIndexerTest {
    private class Store {
        val texts = HashMap<String, String>()
        val log = HashMap<String, MutableList<LogEntry>>()
        var reads = 0
        var writes = 0
        var logLoads = 0
    }

    private fun entry(seq: Long, text: String) = LogEntry(seq, seq, if (seq % 2 == 0L) SessionEvent.UserMessage(text) else SessionEvent.AssistantMessage(0, 0, text))

    @Test
    fun readsOncePerChatAndKeepsEveryMessage() = runTest {
        val store = Store().apply { texts["c"] = "" }
        val indexer = SearchIndexer(
            backgroundScope,
            read = { store.reads++; store.texts[it] },
            write = { id, text -> store.writes++; store.texts[id] = text },
            loadLog = { store.logLoads++; store.log[it].orEmpty() },
        )
        // Worst case for coalescing: the index catches up after every message.
        repeat(200) { i ->
            indexer.enqueue("c", listOf(entry(i.toLong() + 1, "message $i")))
            runCurrent()
        }
        assertFalse(indexer.busy)
        assertEquals(1, store.reads)
        assertEquals(200, store.writes)
        assertEquals((0 until 200).joinToString("\n") { "message $it" }, store.texts["c"])
        println("SearchIndexerTest: 200 messages, one at a time -> ${store.reads} read(s), ${store.writes} writes (before: 200 reads, 200 writes)")
    }

    @Test
    fun aBurstIsWrittenOnceWithoutBlockingTheAppender() = runTest {
        val store = Store().apply { texts["c"] = "" }
        val indexer = SearchIndexer(backgroundScope, { store.reads++; store.texts[it] }, { id, t -> store.writes++; store.texts[id] = t }, { store.log[it].orEmpty() })
        repeat(200) { i -> indexer.enqueue("c", listOf(entry(i.toLong() + 1, "message $i"))) }
        // Nothing ran on the appender's coroutine.
        assertEquals(0, store.writes)
        runCurrent()
        assertEquals(1, store.reads)
        assertEquals(1, store.writes)
        assertTrue(store.texts["c"]!!.endsWith("message 199"))
        println("SearchIndexerTest: burst of 200 -> ${store.reads} read, ${store.writes} write (before: 200 reads, 200 writes)")
    }

    @Test
    fun aNewChatIsIndexedFromItsLogOnceWithoutDuplicates() = runTest {
        val store = Store()
        val indexer = SearchIndexer(backgroundScope, { store.texts[it] }, { id, t -> store.texts[id] = t }, { store.logLoads++; store.log[it].orEmpty() })
        val first = listOf(entry(2, "hello"), entry(3, "hi there"))
        store.log["n"] = first.toMutableList()
        indexer.enqueue("n", first)
        // Appended to the log before the worker loaded it, but queued separately.
        val second = entry(4, "again")
        store.log.getValue("n") += second
        indexer.enqueue("n", listOf(second))
        runCurrent()
        assertEquals(1, store.logLoads)
        assertEquals("hello\nhi there\nagain", store.texts["n"])
    }

    @Test
    fun entriesWithoutTextAreIgnored() = runTest {
        val store = Store()
        val indexer = SearchIndexer(backgroundScope, { store.reads++; null }, { _, _ -> store.writes++ }, { emptyList() })
        indexer.enqueue("c", listOf(LogEntry(1, 1, SessionEvent.UserMessage("ctx", kind = dev.wckdboy.autobot.agent.core.session.UserMessageKind.CONTEXT))))
        runCurrent()
        assertEquals(0, store.reads)
        assertEquals(0, store.writes)
    }
}

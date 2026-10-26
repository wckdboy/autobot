package dev.wckdboy.autobot.feature.home.node

import dev.wckdboy.autobot.shared.invincible.BoardTask
import dev.wckdboy.autobot.shared.invincible.NodeDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The "While you were away" card's logic: when it shows, where it starts, and that seeing it sticks. */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeDigestViewModelTest {
    private class Source(var paired: Pair<String, String>? = "n1" to "studio-mac", var fail: Boolean = false) : NodeDigestSource {
        val asked = mutableListOf<Long>()
        /** What the node did, as a function of where the window starts (this phone's requests alone are no news). */
        var answer: (Long) -> NodeDigest = { since -> NodeDigest(since, requests = 3, onNode = 3, actions = 1, autoAllowed = 1) }

        override suspend fun node() = paired

        override suspend fun digest(providerId: String, since: Long): NodeDigest {
            if (fail) error("The node did not answer")
            asked += since
            return answer(since)
        }
    }

    private class Seen : NodeSeenStore {
        val map = mutableMapOf<String, Long>()
        override fun lastSeen(providerId: String) = map[providerId]
        override fun markSeen(providerId: String, at: Long) {
            map[providerId] = at
        }
    }

    private var clock = 1_000_000L

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    @Test
    fun theFirstLookCoversADayAndShowsTheCard() = runTest {
        val source = Source()
        val vm = NodeDigestViewModel(source, Seen()) { clock }
        vm.refresh()
        assertEquals(listOf(clock - 86_400), source.asked)
        assertEquals("studio-mac", vm.state.value!!.node)
        assertEquals(3, vm.state.value!!.digest.requests)
    }

    @Test
    fun dismissingMarksSeenAndTheNextLookStartsThere() = runTest {
        val source = Source()
        val seen = Seen()
        val vm = NodeDigestViewModel(source, seen) { clock }
        vm.refresh()
        clock += 60
        vm.markSeen()
        assertNull(vm.state.value)
        assertEquals(1_000_060L, seen.map["n1"])

        // Nothing new since: no card.
        source.answer = { since -> NodeDigest(since) }
        vm.refresh()
        assertEquals(1_000_060L, source.asked.last())
        assertNull(vm.state.value)
    }

    @Test
    fun noNodeOrNoAnswerIsNoCard() = runTest {
        val vm = NodeDigestViewModel(Source(paired = null), Seen()) { clock }
        vm.refresh()
        assertNull(vm.state.value)
        val failing = NodeDigestViewModel(Source(fail = true), Seen()) { clock }
        failing.refresh()
        assertNull(failing.state.value)
    }

    @Test
    fun aQuestionSeenAlreadyDoesNotBringTheCardBack() = runTest {
        val source = Source()
        val seen = Seen().apply { map["n1"] = 500 }
        val old = BoardTask("b1", "p1", "writer", "Write", "blocked", emptyList(), null, "Markdown?", 400)
        source.answer = { since -> NodeDigest(since, waiting = listOf(old)) }
        val vm = NodeDigestViewModel(source, seen) { clock }
        vm.refresh()
        assertNull(vm.state.value)
        source.answer = { since -> NodeDigest(since, waiting = listOf(old.copy(updated = 600))) }
        vm.refresh()
        assertEquals(1, vm.state.value!!.digest.waiting.size)
    }

    @Test
    fun markingSeenWithoutACardDoesNothing() {
        val seen = Seen()
        NodeDigestViewModel(Source(), seen) { clock }.markSeen()
        assertEquals(emptyMap<String, Long>(), seen.map)
    }
}

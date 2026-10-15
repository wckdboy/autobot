package dev.wckdboy.autobot.feature.chat.conversation

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The live frame of a streaming reply reaches the UI at most ~10 times a second, never stale. */
class ThrottleLatestTest {
    @Test
    fun firstValueGoesOutAtOnceThenTheLatestPerPeriod() = runTest {
        val out = flow {
            for (i in 1..30) {
                emit(i)
                delay(10) // 100 chunks a second
            }
        }.throttleLatest(100).toList()
        assertEquals(1, out.first())
        assertEquals(30, out.last())
        // 300 ms of input → about one frame per 100 ms, not 30.
        assertEquals(true, out.size in 3..5)
    }

    @Test
    fun aQuietStreamIsNotDelayed() = runTest {
        val out = flow {
            emit("a")
            delay(500)
            emit("b")
        }.throttleLatest(100).toList()
        assertEquals(listOf("a", "b"), out)
    }

    @Test
    fun urgentValuesBypassTheThrottle() = runTest {
        val seen = mutableListOf<Pair<Long, String?>>()
        flow {
            emit("x")
            delay(10)
            emit("xy")
            delay(10)
            emit(null) // the reply finished: the live frame must disappear now
        }.throttleLatest(100) { it == null }.collect { seen += testScheduler.currentTime to it }
        assertEquals(listOf<String?>("x", null), seen.map { it.second })
        assertEquals(20L, seen.last().first)
    }
}

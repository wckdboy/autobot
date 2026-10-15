package dev.wckdboy.autobot.core.data.diagnostics

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Background failures are recorded (without their messages) instead of crashing the app. */
class ErrorLogTest {
    @Test
    fun theApplicationScopeHandlerRecordsInsteadOfCrashing() = runTest {
        val log = ErrorLog()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + log.handler("background"))
        scope.launch { throw IllegalStateException("secret prompt text") }
        scope.launch { /* siblings keep running */ }
        testScheduler.advanceUntilIdle()
        val entry = log.entries.value.single()
        assertTrue(entry.startsWith("background: IllegalStateException"))
        assertFalse("messages can hold private text", "secret" in entry)
    }

    @Test
    fun keepsTheLatestEntriesOnly() {
        val log = ErrorLog()
        repeat(ErrorLog.MAX_ENTRIES + 5) { log.record("x$it", IOException()) }
        assertEquals(ErrorLog.MAX_ENTRIES, log.entries.value.size)
        assertTrue(log.entries.value.last().startsWith("x${ErrorLog.MAX_ENTRIES + 4}:"))
    }

    @Test
    fun launchSafelyReportsFailuresToTheCaller() = runTest {
        var seen: Throwable? = null
        launchSafely({ seen = it }) { throw IOException("disk full") }.join()
        assertTrue(seen is IOException)
        assertEquals("Storage is full", userMessage(IOException("write failed: ENOSPC (No space left on device)")))
    }
}

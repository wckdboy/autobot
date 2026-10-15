package dev.wckdboy.autobot.core.data.diagnostics

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Errors that escaped to a background boundary (the application scope, a ViewModel action),
 * kept in memory for the diagnostics report. Entries carry the place and the exception type
 * with the first app frame, never the message: messages can quote prompts, paths or keys.
 */
@Singleton
class ErrorLog @Inject constructor() {
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    fun record(where: String, error: Throwable) {
        if (error is CancellationException) return
        runCatching { Log.e(TAG, "$where failed", error) }
        _entries.update { (it + describe(where, error)).takeLast(MAX_ENTRIES) }
    }

    /** Logs and records anything a coroutine without its own handling throws. */
    fun handler(where: String): CoroutineExceptionHandler = CoroutineExceptionHandler { _, e -> record(where, e) }

    companion object {
        const val MAX_ENTRIES = 20
        private const val TAG = "Autobot"
        private const val APP_PACKAGE = "dev.wckdboy.autobot"

        /** `where: IllegalStateException @ ChatViewModel.kt:358` — no message, no identifiers. */
        fun describe(where: String, error: Throwable): String {
            val frame = error.stackTrace.firstOrNull { it.className.startsWith(APP_PACKAGE) }
            val at = frame?.let { " @ ${it.fileName ?: it.className.substringAfterLast('.')}:${it.lineNumber}" }.orEmpty()
            val cause = error.cause?.let { " (cause ${it.javaClass.simpleName})" }.orEmpty()
            return "$where: ${error.javaClass.simpleName}$at$cause"
        }
    }
}

/**
 * Launches a user action that must not crash the app: anything but cancellation is passed to
 * [onError] (typically a notice on screen). Use at ViewModel boundaries instead of a bare
 * `launch` when the work touches storage, the network or an engine.
 */
fun CoroutineScope.launchSafely(onError: (Throwable) -> Unit, block: suspend CoroutineScope.() -> Unit): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e)
    } catch (e: OutOfMemoryError) {
        onError(e)
    }
}

/** A short, user-facing reason for [error] ("Storage is full", the exception's message, …). */
fun userMessage(error: Throwable): String = when {
    error is OutOfMemoryError -> "Not enough memory"
    error.javaClass.simpleName == "SQLiteFullException" || error.message?.contains("ENOSPC") == true ||
        error.message?.contains("No space left", ignoreCase = true) == true -> "Storage is full"
    else -> error.message?.takeIf { it.isNotBlank() }?.take(200) ?: error.javaClass.simpleName
}

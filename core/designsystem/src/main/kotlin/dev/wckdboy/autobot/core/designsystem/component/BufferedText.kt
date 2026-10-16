package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * Text a field shows while its owner's copy catches up.
 *
 * Most editors hold their text in a ViewModel `StateFlow` built with `combine(...).stateIn(...)`, so
 * an edit reaches the field again one dispatch later. Fed straight into a `TextField`, the field
 * re-applies those stale values while the user keeps typing: fast typing (or a paste through the
 * IME) loses or reorders characters. This buffer shows the latest edit at once, recognizes the
 * owner's late echoes of earlier edits and ignores them, and adopts any other value the owner sets
 * (clearing a field after send, loading a saved draft, normalizing input).
 */
class TextEchoBuffer(initial: String) {
    /** What the field shows. */
    var text: String by mutableStateOf(initial)
        private set

    private var lastExternal = initial
    private val inFlight = ArrayDeque<String>()

    /** The owner's current value; call on every composition. */
    fun sync(external: String) {
        if (external == lastExternal) return
        lastExternal = external
        val echo = inFlight.indexOf(external)
        if (echo >= 0) {
            // The owner caught up to one of our edits: everything up to it has been seen.
            repeat(echo + 1) { inFlight.removeFirst() }
            if (inFlight.isEmpty()) text = external
        } else {
            // Set by the owner itself, not an echo: it wins.
            inFlight.clear()
            text = external
        }
    }

    /** A user edit: shown at once, then handed to the owner. */
    fun edit(value: String) {
        text = value
        inFlight.addLast(value)
        while (inFlight.size > MAX_IN_FLIGHT) inFlight.removeFirst()
    }

    private companion object {
        const val MAX_IN_FLIGHT = 256
    }
}

/**
 * [value] / [onValueChange] for a text field whose owner updates asynchronously (see
 * [TextEchoBuffer]). Returns the text to show and the change handler to give the field.
 */
@Composable
fun rememberBufferedText(value: String, onValueChange: (String) -> Unit): Pair<String, (String) -> Unit> {
    val buffer = remember { TextEchoBuffer(value) }
    buffer.sync(value)
    val latest by rememberUpdatedState(onValueChange)
    val change = remember(buffer) {
        { v: String ->
            buffer.edit(v)
            latest(v)
        }
    }
    return buffer.text to change
}

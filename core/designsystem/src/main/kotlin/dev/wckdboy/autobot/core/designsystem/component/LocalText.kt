package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * A text field's text held in the composition, so typing never waits for a round trip through a
 * ViewModel flow (which drops characters and moves the cursor when the echo arrives late).
 * Every edit is still pushed to the owner; a value the owner sets itself (a reset, a reused
 * prompt) is taken over, while late echoes of what was typed are ignored.
 */
@Stable
class LocalText internal constructor(initial: String, private val push: (String) -> Unit) {
    var text: String by mutableStateOf(initial)
        private set

    /** Edits not yet seen coming back from the owner, oldest first. */
    private val sent = ArrayDeque<String>()

    /** The field's `onValueChange`. */
    fun edit(value: String) {
        if (value == text) return
        text = value
        sent.addLast(value)
        while (sent.size > MAX_PENDING) sent.removeFirst()
        push(value)
    }

    /** The owner's current value: a late echo of an edit is ignored, anything else replaces the text. */
    fun sync(external: String) {
        when {
            external == text -> sent.clear()
            external in sent -> Unit
            else -> {
                text = external
                sent.clear()
            }
        }
    }

    private companion object {
        const val MAX_PENDING = 64
    }
}

/** [LocalText] following [value] (the owner's state) and pushing edits to [onChange]. */
@Composable
fun rememberLocalText(value: String, onChange: (String) -> Unit): LocalText {
    val latest by rememberUpdatedState(onChange)
    val state = remember { LocalText(value) { latest(it) } }
    LaunchedEffect(value) { state.sync(value) }
    return state
}

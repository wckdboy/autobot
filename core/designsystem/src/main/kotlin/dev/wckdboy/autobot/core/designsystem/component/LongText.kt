package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Limits for model or tool output shown verbatim. A single 40 000-character line laid out
 * without wrapping (sideways scroll) or a megabyte of text exceeds what a layout can measure
 * ("Can't represent a size in Constraints"), so such text is wrapped and shown a page at a time.
 */
object LongText {
    /** Lines up to this long scroll sideways; a longer one makes the block wrap instead. */
    const val MAX_SCROLL_LINE = 1_000

    /** Characters shown at first, and added by each "show more". */
    const val PAGE = 10_000

    /** Whether [text] has a line longer than [limit] characters. */
    fun hasLongLine(text: String, limit: Int = MAX_SCROLL_LINE): Boolean {
        var run = 0
        for (c in text) {
            if (c == '\n') run = 0 else if (++run > limit) return true
        }
        return false
    }

    /** The first [shown] characters of [text] (all of it when shorter). */
    fun clip(text: String, shown: Int): String = if (text.length <= shown) text else text.take(shown)
}

/**
 * Verbatim text (code, tool output, a reasoning trace): code scrolls sideways unless a line is
 * too long ([wrap] text always wraps), and [LongText.PAGE] characters show at a time with a
 * "show more" row.
 */
@Composable
fun VerbatimText(text: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, wrap: Boolean = false) {
    var shown by rememberSaveable { mutableIntStateOf(LongText.PAGE) }
    val visible = remember(text, shown) { LongText.clip(text, shown) }
    val longLine = remember(visible) { LongText.hasLongLine(visible) }
    val wrapped = wrap || longLine
    Column {
        Text(
            text = visible,
            modifier = (if (wrapped) Modifier else Modifier.horizontalScroll(rememberScrollState())).then(modifier),
            style = style,
            color = color,
            softWrap = wrapped,
        )
        if (visible.length < text.length) {
            Text(
                "show more · ${text.length - visible.length} characters left",
                modifier = Modifier
                    .clickable(role = Role.Button) { shown += LongText.PAGE }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                style = style,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

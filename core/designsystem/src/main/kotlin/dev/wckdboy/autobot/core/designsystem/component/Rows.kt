package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/** Side gutter of list rows (on the 4/8 dp grid). */
val RowGutter = 16.dp

/** Rows are at least this tall: a 48 dp touch target plus breathing room. */
val RowMinHeight = 56.dp

/** The ⓘ glyph's visual size (its touch target stays 48 dp). */
val InfoIconSize = 18.dp

/**
 * ⓘ: the explanation behind a setting or a screen, shown on demand in a small popover instead of
 * as a subtitle. A 48 dp target, described for screen readers as "About [title]".
 */
@Composable
fun InfoButton(title: String, text: String, modifier: Modifier = Modifier) {
    var shown by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        // A 48 dp target around an 18 dp glyph: the icon reads as a footnote to the title, not a button.
        IconButton(onClick = { shown = true }) {
            Icon(Icons.Outlined.Info, contentDescription = "About $title", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(InfoIconSize))
        }
        if (shown) {
            Popup(
                popupPositionProvider = BelowAnchor,
                onDismissRequest = { shown = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.small,
                    shadowElevation = 4.dp,
                    modifier = Modifier.widthIn(max = 300.dp).clickable { shown = false },
                ) {
                    Text(text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** Below its anchor (above it when there is no room), kept inside the window with a 16 dp margin. */
private object BelowAnchor : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = (windowSize.width / 1080f * 48).toInt().coerceAtLeast(16)
        val x = anchorBounds.left.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val below = anchorBounds.bottom
        val y = if (below + popupContentSize.height <= windowSize.height) below else (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}

/**
 * A list row: a one- or two-word [title], an optional [value] on the trailing side that carries
 * state ("On", "3", "Offline"), an optional [info] behind ⓘ, and a chevron when it opens something.
 * Never a paragraph: explanations go in [info].
 */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    info: String? = null,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    chevron: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = RowGutter, end = if (chevron) 8.dp else RowGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ⓘ sits right after the title on every row, so the icons line up with the text, not the trailing controls.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (info != null) InfoButton(title, info)
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A switch row: [title], an optional one-line [state] under it only when it carries state
 * ("Needs a screen lock"), an optional [info] behind ⓘ, and the switch.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    state: String? = null,
    info: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(horizontal = RowGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            // ⓘ right after the title and centred on it (not on the title + state block).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (info != null) InfoButton(title, info)
            }
            if (state != null) Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

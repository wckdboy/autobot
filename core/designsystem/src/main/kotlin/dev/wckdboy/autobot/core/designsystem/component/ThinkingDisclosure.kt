package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme

/**
 * Collapsible reasoning trace: an ultraviolet rule with a `∴ reasoning` header. Collapsed it
 * shows the last line as a ticker while [isThinking]; expanded it shows the full trace.
 */
@Composable
fun ThinkingDisclosure(
    reasoning: String,
    modifier: Modifier = Modifier,
    isThinking: Boolean = false,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val accent = MaterialTheme.colorScheme.secondary
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(accent.copy(alpha = 0.6f)))
        Column(Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide reasoning" else "Show reasoning") { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isThinking) StatusDot(accent, live = true, size = 6.dp)
                MicroLabel(if (isThinking) "∴ thinking" else "∴ reasoning", color = accent)
                MicroLabel("${reasoning.length / 4} tok", color = MaterialTheme.colorScheme.outline)
                Box(Modifier.weight(1f))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp).rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!expanded && isThinking) {
                Text(
                    reasoning.trimEnd().substringAfterLast('\n').takeLast(160),
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                    style = AutobotTheme.styles.codeBlock,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Text(
                    text = reasoning,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    style = AutobotTheme.styles.codeBlock,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(name = "ThinkingDisclosure", showBackground = true, backgroundColor = 0xFF09090B, widthDp = 360)
@Composable
private fun ThinkingDisclosurePreview() {
    AutobotTheme {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThinkingDisclosure("The user wants a reversed list…\nConsider asReversed().", isThinking = true)
            ThinkingDisclosure("First consider immutability. `reversed()` returns a new list.", initiallyExpanded = true)
        }
    }
}

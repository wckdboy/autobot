package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme

/**
 * Collapsible reasoning trace: a tonal surfaceContainerLow card in the medium shape with a
 * `∴ Thinking · 412 tokens` header (a small loading indicator while [isThinking]). Collapsed it
 * shows the last line as a ticker while [isThinking]; expanded it shows the full trace. [tokens]
 * is the engine's count (null: an estimate from the text, marked `≈`); [stop] says when the engine
 * ended the reasoning early ("budget", "loop").
 */
@Composable
fun ThinkingDisclosure(
    reasoning: String,
    modifier: Modifier = Modifier,
    isThinking: Boolean = false,
    initiallyExpanded: Boolean = false,
    tokens: Int? = null,
    stop: String? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val rotation = animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Hide reasoning" else "Show reasoning") { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isThinking) InlineLoading(Modifier.size(20.dp), color = MaterialTheme.colorScheme.secondary, contentDescription = null)
                Text(
                    labelCase(if (isThinking) "∴ thinking" else "∴ thought"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = linesFor(1, 2),
                )
                Text(
                    thinkingMeta(reasoning, tokens, stop),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = linesFor(1, 3),
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    // Read in the layer, so the chevron turns without recomposing the header.
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation.value },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!expanded && isThinking) {
                Text(
                    reasoning.trimEnd().substringAfterLast('\n').takeLast(160),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    style = AutobotTheme.styles.codeBlock,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                VerbatimText(
                    reasoning,
                    AutobotTheme.styles.codeBlock,
                    Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    wrap = true,
                )
            }
        }
    }
}

/** `412 tokens · stopped at budget`; `≈103 tok` when only the text length is known. */
fun thinkingMeta(reasoning: String, tokens: Int?, stop: String?): String = buildString {
    if (tokens != null && tokens > 0) append(tokens).append(if (tokens == 1) " token" else " tokens") else append("≈${reasoning.length / 4} tok")
    when (stop) {
        "budget" -> append(" · stopped at budget")
        "loop" -> append(" · stopped: repeating")
    }
}

@Preview(name = "ThinkingDisclosure", showBackground = true, backgroundColor = 0xFF09090B, widthDp = 360)
@Composable
private fun ThinkingDisclosurePreview() {
    AutobotTheme {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThinkingDisclosure("The user wants a reversed list…\nConsider asReversed().", isThinking = true)
            ThinkingDisclosure("First consider immutability. `reversed()` returns a new list.", initiallyExpanded = true)
            ThinkingDisclosure("14:35 plus two hours is 16:35…", tokens = 140, stop = "budget")
        }
    }
}

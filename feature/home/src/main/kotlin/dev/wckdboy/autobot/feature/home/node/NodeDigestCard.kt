package dev.wckdboy.autobot.feature.home.node

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.NodeMark
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.text.style.TextAlign
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.invincible.NodeDigestLine
import dev.wckdboy.autobot.shared.invincible.NodeDigestWords

/**
 * "While you were away": what the paired node did since the owner last looked, in a few plain
 * lines, with what needs them first as the primary action. Invincible's i marks it as the node's.
 */
@Composable
fun NodeDigestCard(
    state: NodeDigestCardState,
    onNeedsYou: () -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val digest = state.digest
    val needsYou = NodeDigestWords.needsYou(digest)
    OutlinedCard(modifier.testTag("node-digest").fillMaxWidth()) {
        Column(
            Modifier
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)
                // Title and lines read as one; the buttons stay their own.
                .semantics(mergeDescendants = true) {},
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // On the title's first line, however it wraps.
                NodeMark(Modifier.padding(top = 12.dp))
                Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    Text(NodeDigestWords.TITLE, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    // The node's name as it is (not sentence-cased: it is a name).
                    Text(state.node, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss: mark as seen", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(
                Modifier.padding(end = 8.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NodeDigestWords.lines(digest).forEach { DigestLine(it) }
            }
            val details = @Composable {
                TextButton(onClick = onDetails, modifier = Modifier.semantics { contentDescription = "Details: the node's activity" }) {
                    Text("Details")
                }
            }
            if (isLargeText() || (needsYou?.length ?: 0) > STACK_AT) {
                // Large text, or a long action ("2 approvals, 1 question waiting"): it gets the whole width, Details goes under it.
                Column(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (needsYou != null) Button(onClick = onNeedsYou, modifier = Modifier.fillMaxWidth()) { Text(needsYou, textAlign = TextAlign.Center) }
                    details()
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (needsYou != null) Button(onClick = onNeedsYou, modifier = Modifier.weight(1f)) { Text(needsYou, textAlign = TextAlign.Center) }
                    details()
                }
            }
        }
    }
}

/** Words first; an error is in the error color and stands out, a warning's `!` is amber, a check stays quiet. */
@Composable
private fun DigestLine(line: NodeDigestLine) {
    val scheme = MaterialTheme.colorScheme
    // Read without the glyph: the words carry it (an error says "failed").
    val spoken = Modifier.semantics { contentDescription = NodeDigestWords.unmarked(line.text) }
    when (line.tone) {
        NodeDigestLine.Tone.ERROR -> Text(line.text, spoken, style = MaterialTheme.typography.bodyMedium, color = scheme.error, fontWeight = FontWeight.Medium)
        NodeDigestLine.Tone.OK -> Text(line.text, spoken, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        NodeDigestLine.Tone.WARNING -> {
            val words = line.text.removeSuffix(" !")
            val amber: Color = AutobotTheme.inks.amber
            Text(
                buildAnnotatedString {
                    append(words)
                    append(' ')
                    withStyle(SpanStyle(color = amber, fontWeight = FontWeight.Bold)) { append('!') }
                },
                spoken,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurface,
            )
        }
        NodeDigestLine.Tone.PLAIN -> Text(line.text, spoken, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
}

/** An action label longer than this takes the card's whole width (it would be cut beside Details). */
private const val STACK_AT = 20

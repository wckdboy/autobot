package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * Notices, empty states and loading, the kit's feedback pieces (see Standard.kt).
 */

/**
 * Why something is empty or not working and the one tap that fixes it, as a tonal card
 * ("Offline mode is on · Network", "Search failed · Retry"). [tone] picks the container
 * (secondary, tertiary, error). At a large font the sentence takes the card's width and the
 * action moves under it.
 */
@Composable
fun NoticeCard(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val large = isLargeText()
    val (container, content) = when (tone) {
        NoticeTone.INFO -> scheme.secondaryContainer to scheme.onSecondaryContainer
        NoticeTone.WARNING -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        NoticeTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    val hasAction = action != null && onAction != null
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (tone == NoticeTone.INFO) Icons.Outlined.Info else Icons.Outlined.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
            if (!large && hasAction) TextButton(onClick = onAction!!) { Text(action!!) }
        }
        if (large && hasAction) {
            TextButton(onClick = onAction!!, modifier = Modifier.align(Alignment.End).padding(end = 8.dp, bottom = 4.dp)) { Text(action!!) }
        }
    }
}

/** Nothing here yet: the pixel a, one plain sentence and at most one action (a tonal button). */
@Composable
fun EmptyContent(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AutobotMark(size = 32.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

/** Material's expressive loading indicator, centred, with an optional caption. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Loading(modifier: Modifier = Modifier, caption: String? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LoadingIndicator()
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** A button's label, or a small loading indicator in its place while [busy]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BusyLabel(text: String, busy: Boolean) {
    if (busy) LoadingIndicator(Modifier.size(24.dp), color = LocalContentColor.current) else Text(text)
}

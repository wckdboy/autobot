package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/*
 * Notices, empty states, loading and progress, the kit's feedback pieces (see Standard.kt).
 */

/** A [NoticeCard]'s container: secondary (information), tertiary (a warning), error. */
enum class NoticeTone { INFO, WARNING, ERROR }

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

/** A small loading indicator in a row of text ("composing", "decoding"), in [color]. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun InlineLoading(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, contentDescription: String? = "working") {
    LoadingIndicator(
        modifier.size(24.dp).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        color = color,
    )
}

/** A job's progress state for [JobProgress]. */
enum class JobState { Running, Idle, Paused, Error }

/**
 * A job's progress: Material's wavy linear indicator while it runs ([progress] null: indeterminate),
 * the flat one when it is idle or paused (paused dims it), and the error colour when it failed.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun JobProgress(progress: Float?, modifier: Modifier = Modifier, state: JobState = JobState.Running) {
    val scheme = MaterialTheme.colorScheme
    val color = when (state) {
        JobState.Error -> scheme.error
        JobState.Paused -> scheme.outline
        else -> scheme.primary
    }
    val track = if (state == JobState.Error) scheme.errorContainer else scheme.secondaryContainer
    val fill = (progress ?: if (state == JobState.Error) 1f else 0f).coerceIn(0f, 1f)
    when {
        state == JobState.Running && progress == null -> LinearWavyProgressIndicator(modifier, color = color, trackColor = track)
        state == JobState.Running -> LinearWavyProgressIndicator(progress = { fill }, modifier = modifier, color = color, trackColor = track)
        else -> LinearProgressIndicator(progress = { fill }, modifier = modifier, color = color, trackColor = track)
    }
}

/**
 * Where nothing has been made yet (an image canvas, a preview): a surfaceContainer area in the
 * large shape with the pixel a and an optional one-line [hint].
 */
@Composable
fun EmptyPicture(modifier: Modifier = Modifier, hint: String? = null) {
    Surface(modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AutobotMark(size = 24.dp)
                if (hint != null) {
                    Text(
                        hint,
                        Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

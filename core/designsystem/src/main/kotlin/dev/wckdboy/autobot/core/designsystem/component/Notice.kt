package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import java.util.Locale

enum class NoticeTone { INFO, WARNING, ERROR }

/**
 * Why something is empty or not working, and the one tap that fixes it ("Offline mode is on ·
 * NETWORK", "Search failed · RETRY"). Use it in place of a silent empty list or an endless spinner.
 */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val accent = when (tone) {
        NoticeTone.INFO -> MaterialTheme.colorScheme.primary
        NoticeTone.WARNING -> AutobotColors.Amber
        NoticeTone.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 44.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        Text(text, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action.uppercase(Locale.ROOT), color = accent, style = MaterialTheme.typography.labelMedium) }
        } else {
            Spacer(Modifier.width(2.dp))
        }
    }
}

package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.util.Locale

/** Network privacy state shown to the user. */
enum class PrivacyStatus(val label: String, val description: String) {
    OFFLINE("Offline", "No network access"),
    DIRECT("Direct", "Direct internet connection"),
    TOR("Tor", "Routed through Tor"),
    PROXY("SOCKS5", "Routed through a SOCKS5 proxy"),
}

/** Inference backend that produced (or will produce) a response. */
enum class Accelerator(val label: String) { NPU("NPU"), GPU("GPU"), CPU("CPU"), REMOTE("Remote") }

@Composable
private fun PrivacyStatus.color(): Color = when (this) {
    PrivacyStatus.OFFLINE -> AutobotColors.Purple
    PrivacyStatus.DIRECT -> AutobotTheme.inks.amber
    PrivacyStatus.TOR -> AutobotTheme.inks.uv
    PrivacyStatus.PROXY -> AutobotTheme.inks.cyan
}

/** Compact hairline tag with a status dot, e.g. "● Offline": the dot carries the colour, the word stays onSurface. Clickable when [onClick] is set. */
@Composable
fun PrivacyStatusPill(
    status: PrivacyStatus,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val accent = status.color()
    val semanticsModifier = modifier.semantics { contentDescription = "Network: ${status.description}" }
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Text(status.label, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    val shape = MaterialTheme.shapes.medium
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick != null) {
        // 48 dp touch target around the tag.
        Box(semanticsModifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Surface(onClick = onClick, shape = shape, color = Color.Transparent, border = border, content = content)
        }
    } else {
        Surface(modifier = semanticsModifier, shape = shape, color = Color.Transparent, border = border, content = content)
    }
}

/** Small outlined chip naming the compute backend. */
@Composable
fun AcceleratorChip(accelerator: Accelerator, modifier: Modifier = Modifier) {
    // A quiet hairline tag, like the route tags: where it runs is information, not a signal.
    Surface(
        modifier = modifier.semantics { contentDescription = "Runs on ${accelerator.label}" },
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            text = accelerator.label.lowercase(Locale.ROOT),
            modifier = Modifier.padding(PaddingValues(horizontal = 8.dp, vertical = 3.dp)),
            style = AutobotTheme.styles.micro,
        )
    }
}

/** "12.4 tok/s · 256 tokens". Either part may be omitted. */
@Composable
fun TokenRateLabel(tokensPerSecond: Float?, totalTokens: Int?, modifier: Modifier = Modifier) {
    val parts = buildList {
        tokensPerSecond?.let { add(String.format(Locale.ROOT, "%.1f tok/s", it)) }
        totalTokens?.let { add(counted(it, "token")) }
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        modifier = modifier,
        style = AutobotTheme.styles.micro,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Preview(name = "PrivacyStatusPill", showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun PrivacyStatusPillPreview() {
    AutobotTheme {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrivacyStatus.entries.forEach { PrivacyStatusPill(it) }
        }
    }
}

@Preview(name = "AcceleratorChip", showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun AcceleratorChipPreview() {
    AutobotTheme {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Accelerator.entries.forEach { AcceleratorChip(it) }
        }
    }
}

@Preview(name = "TokenRateLabel", showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun TokenRateLabelPreview() {
    AutobotTheme {
        Column(Modifier.padding(12.dp)) {
            TokenRateLabel(tokensPerSecond = 18.7f, totalTokens = 412)
            TokenRateLabel(tokensPerSecond = null, totalTokens = 96)
        }
    }
}

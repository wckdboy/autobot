package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.background
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

private fun PrivacyStatus.color(): Color = when (this) {
    PrivacyStatus.OFFLINE -> AutobotColors.Green
    PrivacyStatus.DIRECT -> AutobotColors.Amber
    PrivacyStatus.TOR -> AutobotColors.Violet
    PrivacyStatus.PROXY -> AutobotColors.Cyan
}

/** Compact pill with a status dot, e.g. "● Offline". Clickable when [onClick] is set. */
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
            Text(status.label, style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.SemiBold)
        }
    }
    val shape = CircleShape
    val container = accent.copy(alpha = 0.12f)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = semanticsModifier, shape = shape, color = container, content = content)
    } else {
        Surface(modifier = semanticsModifier, shape = shape, color = container, content = content)
    }
}

/** Small outlined chip naming the compute backend. */
@Composable
fun AcceleratorChip(accelerator: Accelerator, modifier: Modifier = Modifier) {
    val accent = when (accelerator) {
        Accelerator.NPU -> AutobotColors.Violet
        Accelerator.GPU -> AutobotColors.Cyan
        Accelerator.CPU -> MaterialTheme.colorScheme.onSurfaceVariant
        Accelerator.REMOTE -> AutobotColors.Amber
    }
    Surface(
        modifier = modifier.semantics { contentDescription = "Runs on ${accelerator.label}" },
        shape = MaterialTheme.shapes.small,
        color = accent.copy(alpha = 0.10f),
        contentColor = accent,
    ) {
        Text(
            text = accelerator.label.uppercase(Locale.ROOT),
            modifier = Modifier.padding(PaddingValues(horizontal = 8.dp, vertical = 3.dp)),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** "12.4 tok/s · 256 tokens". Either part may be omitted. */
@Composable
fun TokenRateLabel(tokensPerSecond: Float?, totalTokens: Int?, modifier: Modifier = Modifier) {
    val parts = buildList {
        tokensPerSecond?.let { add(String.format(Locale.ROOT, "%.1f tok/s", it)) }
        totalTokens?.let { add("$it tokens") }
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
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

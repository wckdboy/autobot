package dev.wckdboy.autobot.feature.chat.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.agent.core.session.ReceiptHost
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.motion.inkStagger
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.session.Receipt
import dev.wckdboy.autobot.shared.session.Receipts

/**
 * The receipt under a turn's last answer: `ON DEVICE` or `2 HOSTS · 14 KB OUT · NO RETENTION`.
 * It stamps in when the turn ends; a tap opens [ReceiptSheet].
 */
@Composable
internal fun ReceiptLine(receipt: Receipt?, onOpen: (Receipt) -> Unit) {
    Box(Modifier.stamp(receipt?.line, enabled = receipt != null)) {
        if (receipt != null) {
            val accent = when {
                receipt.onDevice -> MaterialTheme.colorScheme.primary
                receipt.models.any { it.policy == TRAINS } -> AutobotTheme.inks.amber
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Tag("▤ ${receipt.line}", accent = accent, filled = receipt.onDevice, onClick = { onOpen(receipt) })
        }
    }
}

private const val TRAINS = "trains on prompts"

/** Everything one turn shared, and with whom. Built on this phone; never sent anywhere. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReceiptSheet(receipt: Receipt, onDismiss: () -> Unit, onOpenUsage: (() -> Unit)? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("receipt · turn ${receipt.turn}", Modifier.inkStagger(0))
            Text(
                when {
                    receipt.onDevice -> "Nothing left this phone"
                    !receipt.tracked -> "Traffic not tracked for this turn"
                    else -> "${Receipts.formatBytes(receipt.bytesSent)} sent · ${Receipts.formatBytes(receipt.bytesReceived)} received"
                },
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.inkStagger(1),
            )

            if (receipt.hosts.isNotEmpty()) {
                Hairline(Modifier.padding(vertical = 2.dp))
                MicroLabel("hosts")
                receipt.hosts.forEach { HostRow(it) }
            }

            if (receipt.models.isNotEmpty()) {
                Hairline(Modifier.padding(vertical = 2.dp))
                MicroLabel("model")
                receipt.models.forEach { m ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(m.model, style = AutobotTheme.styles.code, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            m.policy?.let { Tag(it, accent = if (it == TRAINS) AutobotTheme.inks.amber else if (m.onDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(
                            listOfNotNull(m.name, m.policyNote ?: "data policy not documented".takeIf { m.policy == null }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        receipt.usageRows.firstOrNull { it.model == m.model }?.let { MicroLabel(it.line) }
                    }
                }
            }

            if (receipt.redacted) {
                Hairline(Modifier.padding(vertical = 2.dp))
                MicroLabel("redacted")
                if (receipt.redactions.isEmpty()) {
                    Text("Nothing personal found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        receipt.redactions.forEach { Tag(it.label, accent = MaterialTheme.colorScheme.primary) }
                    }
                    Text(
                        "The provider saw placeholders like [PERSON_1]. The real values were put back on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            receipt.usage?.let { usage ->
                Hairline(Modifier.padding(vertical = 2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MicroLabel("tokens", Modifier.weight(1f))
                    onOpenUsage?.let { Tag("usage", accent = MaterialTheme.colorScheme.onSurfaceVariant, onClick = it) }
                }
                Text(usage, style = AutobotTheme.styles.code, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Counted from the provider's own usage report. Cost uses your price table; models without a price stay unknown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (receipt.tools.isNotEmpty()) {
                Hairline(Modifier.padding(vertical = 2.dp))
                MicroLabel("tools")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    receipt.tools.forEach { t ->
                        Tag(if (t.network) "${t.name} · net" else t.name, accent = if (t.network) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (receipt.fallbacks.isNotEmpty()) {
                Hairline(Modifier.padding(vertical = 2.dp))
                MicroLabel("fallbacks")
                receipt.fallbacks.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = AutobotTheme.inks.amber) }
            }

            Hairline(Modifier.padding(vertical = 2.dp))
            Text(
                "Built on this phone from the network audit log. Receipts stay in this chat and are never sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.inkStagger(2),
            )
        }
    }
}

/** `api.deepseek.com   TOR · 2 REQ · 14 KB OUT · 30 KB IN` */
@Composable
private fun HostRow(host: ReceiptHost) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(host.host, style = AutobotTheme.styles.code, maxLines = 1, overflow = TextOverflow.Ellipsis)
        MicroLabel(
            listOfNotNull(
                host.route,
                "${host.requests} req",
                "${Receipts.formatBytes(host.bytesSent)} out",
                "${Receipts.formatBytes(host.bytesReceived)} in",
                host.blocked.takeIf { it > 0 }?.let { "$it blocked" },
                host.tags.joinToString(", ").ifEmpty { null },
            ).joinToString(" · "),
            color = if (host.blocked == host.requests) AutobotTheme.inks.amber else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

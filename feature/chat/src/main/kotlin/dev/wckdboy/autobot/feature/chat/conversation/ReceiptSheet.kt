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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.shared.session.Receipt
import dev.wckdboy.autobot.shared.session.Receipts

/**
 * The receipt under a turn's last answer: `On device` or `2 hosts · 14 KB out · no retention`, as
 * an assist chip. It stamps in when the turn ends; a tap opens [ReceiptSheet].
 */
@Composable
internal fun ReceiptLine(receipt: Receipt?, onOpen: (Receipt) -> Unit) {
    Box(Modifier.stamp(receipt?.line, enabled = receipt != null)) {
        if (receipt != null) {
            // A model that trains on prompts is the one thing here worth a warning colour.
            val trains = receipt.models.any { it.policy == TRAINS }
            AssistChip(
                onClick = { onOpen(receipt) },
                label = { Text(receipt.line) },
                colors = if (trains) AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error) else AssistChipDefaults.assistChipColors(),
            )
        }
    }
}

private const val TRAINS = "trains on prompts"

/** Everything one turn shared, and with whom. Built on this phone; never sent anywhere. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ReceiptSheet(receipt: Receipt, onDismiss: () -> Unit, onOpenUsage: (() -> Unit)? = null) {
    val caution = MaterialTheme.colorScheme.error
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header("Receipt · turn ${receipt.turn}")
            Text(
                when {
                    receipt.onDevice -> "Nothing left this phone"
                    !receipt.tracked -> "Traffic not tracked for this turn"
                    else -> "${Receipts.formatBytes(receipt.bytesSent)} sent · ${Receipts.formatBytes(receipt.bytesReceived)} received"
                },
                style = MaterialTheme.typography.headlineSmall,
            )

            if (receipt.hosts.isNotEmpty()) {
                Section("Hosts")
                receipt.hosts.forEach { HostRow(it) }
            }

            receipt.nodes.forEach { node ->
                Section("On ${node.node}")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        node.models.joinToString(", ").ifEmpty { "no model ran" },
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (node.verified) {
                        TonalLabel("signed ✓")
                    } else {
                        TonalLabel("not verified", containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Text(
                    when {
                        !node.verified && node.missing > 0 -> "${node.node} could not give ${node.missing} of its ${node.requests} receipts, so this part is unchecked."
                        !node.verified -> "The receipt's signature does not match the key pinned when you paired. Treat what it says as unchecked."
                        node.egress.isEmpty() -> "Nothing left ${node.node}: its signed receipt lists no outside host."
                        else -> "${node.node} sent part of this turn on, as its signed receipt shows:"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                node.egress.forEach { HostRow(it) }
                if (node.redactions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        node.redactions.forEach { TonalLabel("${it.label} hidden by ${node.node}") }
                    }
                }
            }

            if (receipt.models.isNotEmpty()) {
                Section("Model")
                receipt.models.forEach { m ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(m.model, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            m.policy?.let {
                                if (it == TRAINS) {
                                    TonalLabel(it, containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer)
                                } else {
                                    TonalLabel(it)
                                }
                            }
                        }
                        Text(
                            listOfNotNull(m.name, m.policyNote ?: "data policy not documented".takeIf { m.policy == null }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        receipt.usageRows.firstOrNull { it.model == m.model }?.let { Detail(it.line) }
                    }
                }
            }

            receipt.cascade?.let { cascade ->
                Section(if (cascade.role == "cloud") "Phone first · ${cascade.followUpTarget} follow-up" else "Phone first")
                Text(cascade.summary, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    cascade.details.forEach { TonalLabel(it) }
                }
            }

            if (receipt.redacted) {
                Section("Redacted")
                if (receipt.redactions.isEmpty()) {
                    Text("Nothing personal found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        receipt.redactions.forEach { TonalLabel(it.label) }
                    }
                    Text(
                        "The provider saw placeholders like [PERSON_1]. The real values were put back on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            receipt.usage?.let { usage ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Header("Tokens", Modifier.weight(1f))
                    onOpenUsage?.let { TextButton(onClick = it) { Text("Usage") } }
                }
                Text(usage, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Counted from the provider's own usage report. Cost uses your price table; models without a price stay unknown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (receipt.tools.isNotEmpty()) {
                Section("Tools")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    receipt.tools.forEach { t -> TonalLabel(if (t.network) "${t.name} · net" else t.name) }
                }
            }

            if (receipt.fallbacks.isNotEmpty()) {
                Section("Fallbacks")
                receipt.fallbacks.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = caution) }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                "Built on this phone from the network audit log. Receipts stay in this chat and are never sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A divider and a section title. */
@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Header(title)
}

@Composable
private fun Header(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Detail(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

/** `api.deepseek.com` over `tor · 2 req · 14 KB out · 30 KB in` */
@Composable
private fun HostRow(host: ReceiptHost) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(host.host, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Detail(
            listOfNotNull(
                host.route,
                "${host.requests} req",
                "${Receipts.formatBytes(host.bytesSent)} out",
                "${Receipts.formatBytes(host.bytesReceived)} in",
                host.blocked.takeIf { it > 0 }?.let { "$it blocked" },
                host.tags.joinToString(", ").ifEmpty { null },
            ).joinToString(" · "),
            color = if (host.blocked == host.requests) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

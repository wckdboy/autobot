package dev.wckdboy.autobot.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.InfoButton
import dev.wckdboy.autobot.core.models.DownloadQueue
import dev.wckdboy.autobot.core.models.DownloadsState
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.models.formatBytes

/**
 * The downloads panel: the running download (speed, time left), the queue in the order it runs,
 * paused downloads with their reason, what blocks the queue (kill switch, no network, waiting
 * for Wi-Fi) and the "Wi-Fi only" switch. Downloads keep running after you leave the app.
 */
@Composable
internal fun DownloadsPanel(state: DownloadsState, actions: ModelsActions) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", style = MaterialTheme.typography.titleSmall)
                InfoButton("Downloads", "Downloads keep going when you leave Autobot. Pause and cancel them from the notification.", Modifier.weight(1f))
                FilterChip(
                    selected = state.wifiOnly,
                    onClick = { actions.setWifiOnly(!state.wifiOnly) },
                    label = { Text(if (state.wifiOnly) "Wi-Fi only" else "Any network") },
                )
            }
            state.block?.let { Text(it.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) }
            state.active?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { actions.pause(p.id) }) { Text("Pause") }
                }
                if (p.total > 0) {
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(
                    listOfNotNull(
                        "${formatBytes(p.downloaded)} / ${formatBytes(p.total)}",
                        "${formatBytes(p.bytesPerSecond)}/s".takeIf { p.bytesPerSecond > 0 },
                        p.etaSeconds?.let { "${DownloadQueue.formatEta(it)} left" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.queued.forEachIndexed { i, m -> QueueRow(m, "queued · ${i + 1}", actions) }
            state.paused.forEach { m ->
                val reason = DownloadQueue.pausedReason(m)?.label ?: m.error ?: if (m.status == ModelStatus.FAILED) "failed" else "paused"
                QueueRow(m, reason, actions)
            }
        }
    }
}

@Composable
private fun QueueRow(model: InstalledModel, status: String, actions: ModelsActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(model.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    status,
                    "${formatBytes(model.downloadedBytes)} / ${formatBytes(model.totalBytes)}".takeIf { model.totalBytes > 0 },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (model.status) {
            ModelStatus.QUEUED -> TextButton(onClick = { actions.pause(model.id) }) { Text("Pause") }
            ModelStatus.PAUSED -> TextButton(onClick = { actions.resume(model.id) }) { Text("Resume") }
            ModelStatus.FAILED -> TextButton(onClick = { actions.resume(model.id) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Retry") }
            else -> Unit
        }
        TextButton(onClick = { actions.remove(model.id) }) { Text("Cancel") }
    }
}

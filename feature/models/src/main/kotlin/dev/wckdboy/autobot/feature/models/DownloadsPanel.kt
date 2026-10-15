package dev.wckdboy.autobot.feature.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
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
internal fun DownloadsPanel(state: DownloadsState, actions: ModelsViewModel) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicroLabel("downloads", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
            Tag(
                if (state.wifiOnly) "wi-fi only" else "any network",
                accent = if (state.wifiOnly) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                filled = state.wifiOnly,
                onClick = { actions.setWifiOnly(!state.wifiOnly) },
            )
        }
        state.block?.let { MicroLabel(it.label, color = AutobotColors.Amber) }
        state.active?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { actions.pause(p.id) }) { Text("PAUSE") }
            }
            InkProgressBar(p.fraction, Modifier.fillMaxWidth().height(3.dp))
            MicroLabel(
                listOfNotNull(
                    "${formatBytes(p.downloaded)} / ${formatBytes(p.total)}",
                    "${formatBytes(p.bytesPerSecond)}/s".takeIf { p.bytesPerSecond > 0 },
                    p.etaSeconds?.let { "${DownloadQueue.formatEta(it)} left" },
                ).joinToString(" · "),
            )
        }
        state.queued.forEachIndexed { i, m -> QueueRow(m, "queued · ${i + 1}", actions) }
        state.paused.forEach { m ->
            val reason = DownloadQueue.pausedReason(m)?.label ?: m.error ?: if (m.status == ModelStatus.FAILED) "failed" else "paused"
            QueueRow(m, reason, actions)
        }
        if (state.active != null || state.queued.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            MicroLabel("keeps going when you leave autobot · pause and cancel from the notification")
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun QueueRow(model: InstalledModel, status: String, actions: ModelsViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(model.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MicroLabel(
                listOfNotNull(
                    status,
                    "${formatBytes(model.downloadedBytes)} / ${formatBytes(model.totalBytes)}".takeIf { model.totalBytes > 0 },
                ).joinToString(" · "),
            )
        }
        when (model.status) {
            ModelStatus.QUEUED -> Tag("pause", onClick = { actions.pause(model.id) })
            ModelStatus.PAUSED -> Tag("resume", accent = MaterialTheme.colorScheme.primary, onClick = { actions.resume(model.id) })
            ModelStatus.FAILED -> Tag("retry", accent = MaterialTheme.colorScheme.error, onClick = { actions.resume(model.id) })
            else -> Unit
        }
        Tag("cancel", onClick = { actions.remove(model.id) })
    }
}

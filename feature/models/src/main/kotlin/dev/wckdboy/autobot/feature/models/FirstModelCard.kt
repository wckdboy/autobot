package dev.wckdboy.autobot.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.models.Catalog
import dev.wckdboy.autobot.core.models.DownloadsState
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FirstModel
import dev.wckdboy.autobot.core.models.FirstModelStep
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelPlan

/** The first-run card's inputs, worked out from the screen state (pure, for tests). */
internal data class FirstModelCardState(val plan: ModelPlan?, val model: InstalledModel?, val step: FirstModelStep, val downloaded: Long, val total: Long)

/** Nothing but [id] downloads, waits or is paused (the first-run card shows it, so the panel need not). */
internal fun DownloadsState.onlyFor(id: String): Boolean =
    (active?.id ?: id) == id && queued.all { it.id == id } && paused.all { it.id == id } && block == null

internal fun firstModelCard(state: ModelsUiState): FirstModelCardState? {
    val device = state.device ?: return null
    val plan = FirstModel.pickId(device.capabilities)?.let { id -> Catalog.chatModels.firstOrNull { it.id == id } }
    val model = plan?.let { p -> state.installed.firstOrNull { it.id == p.id } }
    val localChat = state.installed.any { it.isReady && it.engine == EngineKind.LLAMA && (it.kind == ModelKind.CHAT || it.kind == ModelKind.CODE) }
    val step = FirstModel.step(picked = plan != null, status = model?.status?.name, localChat = localChat, hasChats = state.hasChats)
    if (step == FirstModelStep.HIDDEN) return null
    val active = state.downloads.active?.takeIf { it.id == plan?.id }
    val total = active?.total?.takeIf { it > 0 } ?: model?.totalBytes?.takeIf { it > 0 } ?: plan?.totalBytes ?: 0
    return FirstModelCardState(plan, model, step, active?.downloaded ?: model?.downloadedBytes ?: 0, total)
}

/**
 * "Start here": the one chat model that fits this phone, with its size, why it is private, and the
 * one action of its step (Download → Pause / Resume / Retry → Start a chat). Offline mode, the
 * default, is said in one sentence and passed with one tap.
 */
@Composable
internal fun FirstModelCard(card: FirstModelCardState, ramBytes: Long, offline: Boolean, actions: ModelsActions, onInstall: (ModelPlan) -> Unit) {
    val plan = card.plan
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(FirstModel.TITLE, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (plan == null) {
                Text(FirstModel.NONE_FITS, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(plan.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(FirstModel.sizeLine(plan.totalBytes, ramBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val needsNetwork = offline && card.step in setOf(FirstModelStep.OFFER, FirstModelStep.FAILED, FirstModelStep.QUEUED, FirstModelStep.PAUSED)
            Text(
                if (card.step == FirstModelStep.READY) FirstModel.readyLine(plan.title) else if (needsNetwork) FirstModel.OFFLINE_FIRST else FirstModel.OFFLINE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (card.step) {
                FirstModelStep.QUEUED, FirstModelStep.DOWNLOADING, FirstModelStep.PAUSED -> {
                    if (card.total > 0) {
                        LinearProgressIndicator(progress = { (card.downloaded.toFloat() / card.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Text(FirstModel.progressLine(card.step, card.downloaded, card.total), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FirstModelStep.FAILED -> card.model?.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                else -> Unit
            }
            val model = card.model
            when (card.step) {
                FirstModelStep.OFFER -> Button(onClick = {
                    if (offline) actions.allowNetwork()
                    onInstall(plan)
                }, modifier = Modifier.fillMaxWidth()) { Text(if (offline) FirstModel.ALLOW_AND_DOWNLOAD else FirstModel.DOWNLOAD) }
                FirstModelStep.QUEUED, FirstModelStep.DOWNLOADING -> if (offline) {
                    Button(onClick = actions::allowNetwork, modifier = Modifier.fillMaxWidth()) { Text(FirstModel.ALLOW_AND_DOWNLOAD) }
                } else {
                    OutlinedButton(onClick = { actions.pause(plan.id) }) { Text(FirstModel.PAUSE) }
                }
                FirstModelStep.PAUSED, FirstModelStep.FAILED -> Button(
                    onClick = {
                        if (offline) actions.allowNetwork()
                        actions.resume(plan.id)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (offline) FirstModel.ALLOW_AND_DOWNLOAD else if (card.step == FirstModelStep.PAUSED) FirstModel.RESUME else FirstModel.RETRY) }
                FirstModelStep.READY -> {
                    if (model != null) Button(onClick = { actions.use(model) }, modifier = Modifier.fillMaxWidth()) { Text(FirstModel.START_CHAT) }
                    if (!offline) {
                        TextButton(onClick = actions::offlineAgain, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(FirstModel.BACK_OFFLINE) }
                    }
                }
                FirstModelStep.NONE_FITS, FirstModelStep.HIDDEN -> Unit
            }
        }
    }
}

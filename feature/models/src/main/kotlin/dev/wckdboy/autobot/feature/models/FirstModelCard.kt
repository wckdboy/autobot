package dev.wckdboy.autobot.feature.models

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.background
import dev.wckdboy.autobot.core.designsystem.component.ActionText
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
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
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.extraSmall)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(FirstModel.TITLE)
        if (plan == null) {
            Text(FirstModel.NONE_FITS, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Text(plan.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        MicroLabel(FirstModel.sizeLine(plan.totalBytes, ramBytes))
        val needsNetwork = offline && card.step in setOf(FirstModelStep.OFFER, FirstModelStep.FAILED, FirstModelStep.QUEUED, FirstModelStep.PAUSED)
        Text(
            if (card.step == FirstModelStep.READY) FirstModel.readyLine(plan.title) else if (needsNetwork) FirstModel.OFFLINE_FIRST else FirstModel.OFFLINE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (card.step) {
            FirstModelStep.QUEUED, FirstModelStep.DOWNLOADING, FirstModelStep.PAUSED -> {
                InkProgressBar(if (card.total > 0) (card.downloaded.toFloat() / card.total).coerceIn(0f, 1f) else null, Modifier.fillMaxWidth().height(3.dp))
                MicroLabel(FirstModel.progressLine(card.step, card.downloaded, card.total))
            }
            FirstModelStep.FAILED -> card.model?.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            else -> Unit
        }
        val model = card.model
        when (card.step) {
            FirstModelStep.OFFER -> PrimaryButton(if (offline) FirstModel.ALLOW_AND_DOWNLOAD else FirstModel.DOWNLOAD, {
                if (offline) actions.allowNetwork()
                onInstall(plan)
            }, Modifier.fillMaxWidth())
            FirstModelStep.QUEUED, FirstModelStep.DOWNLOADING -> if (offline) {
                PrimaryButton(FirstModel.ALLOW_AND_DOWNLOAD, actions::allowNetwork, Modifier.fillMaxWidth())
            } else {
                TextButton(onClick = { actions.pause(plan.id) }, modifier = Modifier.align(Alignment.Start)) { ActionText(FirstModel.PAUSE) }
            }
            FirstModelStep.PAUSED, FirstModelStep.FAILED -> PrimaryButton(
                if (offline) FirstModel.ALLOW_AND_DOWNLOAD else if (card.step == FirstModelStep.PAUSED) FirstModel.RESUME else FirstModel.RETRY,
                {
                    if (offline) actions.allowNetwork()
                    actions.resume(plan.id)
                },
                Modifier.fillMaxWidth(),
            )
            FirstModelStep.READY -> {
                if (model != null) PrimaryButton(FirstModel.START_CHAT, { actions.use(model) }, Modifier.fillMaxWidth())
                if (!offline) {
                    TextButton(onClick = actions::offlineAgain, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        ActionText(FirstModel.BACK_OFFLINE, MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            FirstModelStep.NONE_FITS, FirstModelStep.HIDDEN -> Unit
        }
    }
    Spacer(Modifier.height(14.dp))
}

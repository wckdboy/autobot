package dev.wckdboy.autobot.feature.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.DevelopingHalftone
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.InkBarState
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.PrintReveal
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.motion.inkStagger
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.shared.studio.JobState
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioJob

/**
 * The queue strip: one small plate per job. A queued plate is blank paper, a running one develops
 * its halftone (the engine's live preview when it sends one) with an ink bar, a finished one prints
 * its result in, a failed one is stamped. Tap a plate for its details (cancel, retry).
 */
@Composable
fun QueueStrip(jobs: List<StudioJob>, assets: Map<String, StudioAsset>, actions: ShellActions, modifier: Modifier = Modifier) {
    if (jobs.isEmpty()) return
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val active = jobs.count { !it.state.isFinished }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            MicroLabel(if (active > 0) "queue · $active working" else "queue · done", Modifier.weight(1f))
            if (jobs.any { it.state.isFinished }) Tag("clear", onClick = actions::clearFinished)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(jobs.takeLast(MAX_PLATES), key = { it.id }) { job ->
                Plate(job, job.outputs.firstOrNull()?.let(assets::get), Modifier.clickable { open = job.id })
            }
        }
    }
    open?.let { id -> jobs.firstOrNull { it.id == id } }?.let { job ->
        JobSheet(job, actions, onDismiss = { open = null })
    }
}

@Composable
private fun Plate(job: StudioJob, output: StudioAsset?, modifier: Modifier = Modifier) {
    val description = when (job.state) {
        JobState.QUEUED -> "Queued: ${job.label ?: "job"}"
        JobState.RUNNING -> "Running ${(job.progress * 100).toInt()} percent: ${job.label ?: "job"}"
        JobState.DONE -> "Done: ${job.label ?: "job"}"
        JobState.FAILED -> "Failed: ${job.error?.let(::jobError).orEmpty()}"
        JobState.CANCELLED -> "Cancelled"
    }
    Column(modifier.size(width = 56.dp, height = 64.dp).semantics { contentDescription = description }) {
        Box(Modifier.size(56.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            when (job.state) {
                JobState.RUNNING -> {
                    val preview = rememberDecoded(job.preview?.bytes, 256)
                    DevelopingHalftone(
                        preview = preview,
                        modifier = Modifier.fillMaxSize(),
                        cell = 3.dp,
                        progress = job.progress,
                        loading = job.steps == 0,
                        seed = job.createdAt.toFloat() % 97f,
                    )
                    if (job.steps == 0) InkLoader(Modifier.align(Alignment.Center), contentDescription = null)
                }
                JobState.DONE -> if (output != null) {
                    AssetPlate(output, Modifier.fillMaxSize())
                } else {
                    Text("✓", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
                }
                JobState.FAILED -> Text(
                    "✕",
                    Modifier.align(Alignment.Center).stamp(job.id),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.titleLarge,
                )
                JobState.CANCELLED -> Text("—", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
                JobState.QUEUED -> Text(
                    "…",
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = AutobotTheme.styles.micro,
                )
            }
        }
        InkProgressBar(
            progress = when (job.state) {
                JobState.RUNNING -> if (job.steps > 0) job.progress else null
                JobState.DONE -> 1f
                else -> 0f
            },
            modifier = Modifier.fillMaxWidth().height(2.dp).padding(top = 0.dp),
            state = when (job.state) {
                JobState.RUNNING -> InkBarState.Running
                JobState.FAILED -> InkBarState.Error
                JobState.QUEUED -> InkBarState.Paused
                else -> InkBarState.Idle
            },
        )
    }
}

/** A finished plate prints its result in (once); audio shows its waveform. */
@Composable
private fun AssetPlate(asset: StudioAsset, modifier: Modifier) {
    if (asset.type == dev.wckdboy.autobot.shared.studio.AssetType.AUDIO) {
        AssetThumb(asset, modifier)
        return
    }
    val loader = LocalAssetLoader.current
    val image by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, asset.id) { value = loader.thumbnail(asset.id) }
    val img = image
    if (img != null) PrintReveal(img, modifier, contentDescription = null, key = asset.id, cell = 3.dp) else AssetThumb(asset, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobSheet(job: StudioJob, actions: ShellActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("job · ${job.state.name.lowercase()}", Modifier.inkStagger(0))
            Text(
                job.label ?: StudioRecipes.title(job.recipe).ifBlank { job.recipe.engine },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.inkStagger(1),
            )
            MicroLabel(StudioRecipes.summary(job.recipe), Modifier.inkStagger(2))
            job.status?.let { MicroLabel(it) }
            if (job.state == JobState.RUNNING && job.steps > 0) MicroLabel("step ${job.step} / ${job.steps}")
            job.error?.let { Text(jobError(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!job.state.isFinished) {
                    GhostButton("Cancel", { actions.cancelJob(job.id); onDismiss() }, Modifier.weight(1f), accent = MaterialTheme.colorScheme.primary)
                }
                if (job.state == JobState.FAILED || job.state == JobState.CANCELLED) {
                    PrimaryButton("Retry", { actions.retryJob(job.id); onDismiss() }, Modifier.weight(1f))
                }
            }
        }
    }
}

private const val MAX_PLATES = 40

/** A job's failure as the user reads it: a full disk says so instead of the raw `ENOSPC` error. */
internal fun jobError(raw: String): String =
    if ("ENOSPC" in raw || raw.contains("No space left", ignoreCase = true)) "Storage is full — free some space on this phone, then retry" else raw

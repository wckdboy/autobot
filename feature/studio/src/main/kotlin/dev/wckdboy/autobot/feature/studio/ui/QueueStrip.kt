package dev.wckdboy.autobot.feature.studio.ui

import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import dev.wckdboy.autobot.core.designsystem.component.Caption
import dev.wckdboy.autobot.core.designsystem.component.InlineLoading
import dev.wckdboy.autobot.core.designsystem.component.JobProgress
import dev.wckdboy.autobot.core.designsystem.component.JobState as BarState
import dev.wckdboy.autobot.core.designsystem.component.SheetLabel
import dev.wckdboy.autobot.core.designsystem.component.labelCase
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
import dev.wckdboy.autobot.core.designsystem.component.DevelopingPreview
import dev.wckdboy.autobot.core.designsystem.component.PrintReveal
import dev.wckdboy.autobot.core.designsystem.motion.inkStagger
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.shared.studio.JobState
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioJob

/**
 * The queue strip: one small plate per job. A queued plate is blank, a running one shows the
 * engine's live preview (when it sends one) over Material's wavy progress, a finished one fades its
 * result in, a failed one is stamped. Tap a plate for its details (cancel, retry).
 */
@Composable
fun QueueStrip(jobs: List<StudioJob>, assets: Map<String, StudioAsset>, actions: ShellActions, modifier: Modifier = Modifier) {
    if (jobs.isEmpty()) return
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val active = jobs.count { !it.state.isFinished }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                labelCase(if (active > 0) "queue · $active working" else "queue · done"),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (jobs.any { it.state.isFinished }) TextButton(onClick = actions::clearFinished) { Text("Clear") }
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
        Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            when (job.state) {
                JobState.RUNNING -> {
                    val preview = rememberDecoded(job.preview?.bytes, 256)
                    DevelopingPreview(preview, Modifier.fillMaxSize())
                    if (job.steps == 0) InlineLoading(Modifier.align(Alignment.Center), contentDescription = null)
                }
                JobState.DONE -> if (output != null) {
                    AssetPlate(output, Modifier.fillMaxSize())
                } else {
                    Text("✓", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        JobProgress(
            progress = when (job.state) {
                JobState.RUNNING -> if (job.steps > 0) job.progress else null
                JobState.DONE -> 1f
                else -> 0f
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            state = when (job.state) {
                JobState.RUNNING -> BarState.Running
                JobState.FAILED -> BarState.Error
                JobState.QUEUED -> BarState.Paused
                else -> BarState.Idle
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
    if (img != null) PrintReveal(img, modifier, contentDescription = null, key = asset.id) else AssetThumb(asset, modifier)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobSheet(job: StudioJob, actions: ShellActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetLabel("job · ${job.state.name.lowercase()}", Modifier.inkStagger(0))
            Text(
                job.label ?: StudioRecipes.title(job.recipe).ifBlank { job.recipe.engine },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.inkStagger(1),
            )
            Caption(StudioRecipes.summary(job.recipe), Modifier.inkStagger(2))
            job.status?.let { Caption(it) }
            if (job.state == JobState.RUNNING && job.steps > 0) Caption("step ${job.step} / ${job.steps}")
            job.error?.let { Text(jobError(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!job.state.isFinished) {
                    OutlinedButton(onClick = { actions.cancelJob(job.id); onDismiss() }, Modifier.weight(1f)) { Text("Cancel") }
                }
                if (job.state == JobState.FAILED || job.state == JobState.CANCELLED) {
                    Button(onClick = { actions.retryJob(job.id); onDismiss() }, Modifier.weight(1f)) { Text("Retry") }
                }
            }
        }
    }
}

private const val MAX_PLATES = 40

/** A job's failure as the user reads it: a full disk says so instead of the raw `ENOSPC` error. */
internal fun jobError(raw: String): String =
    if ("ENOSPC" in raw || raw.contains("No space left", ignoreCase = true)) "Storage is full — free some space on this phone, then retry" else raw

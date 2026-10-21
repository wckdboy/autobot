package dev.wckdboy.autobot.feature.studio.ui.video

import dev.wckdboy.autobot.shared.studio.StudioRoomWords
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import dev.wckdboy.autobot.core.designsystem.component.Caption
import dev.wckdboy.autobot.core.designsystem.component.InlineLoading
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.DevelopingPreview
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.feature.studio.ui.AssetThumb
import dev.wckdboy.autobot.feature.studio.ui.ChipRow
import dev.wckdboy.autobot.feature.studio.ui.ControlSheet
import dev.wckdboy.autobot.feature.studio.ui.EmptyRoom
import dev.wckdboy.autobot.feature.studio.ui.ErrorLine
import dev.wckdboy.autobot.feature.studio.ui.LocalAssetLoader
import dev.wckdboy.autobot.feature.studio.ui.ParamControls
import dev.wckdboy.autobot.feature.studio.ui.RunsOn
import dev.wckdboy.autobot.feature.studio.ui.StudioShellState
import dev.wckdboy.autobot.feature.studio.ui.Transport
import dev.wckdboy.autobot.feature.studio.ui.VideoSurface
import dev.wckdboy.autobot.feature.studio.ui.rememberBytePlayer
import dev.wckdboy.autobot.feature.studio.ui.rememberDecoded
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.JobState
import dev.wckdboy.autobot.shared.studio.StudioAsset

/** The video room (darkroom): the clip plays as the hero; controls come from the workflow. */
@Composable
fun VideoRoom(state: VideoRoomState, shell: StudioShellState, actions: VideoActions, onOpenWorkflows: () -> Unit, modifier: Modifier = Modifier) {
    var controls by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val assets = shell.assets.associateBy { it.id }
    val jobs = state.jobIds.mapNotNull { id -> shell.jobs.firstOrNull { it.id == id } }
    val clips = (jobs.flatMap { it.outputs }.mapNotNull(assets::get).reversed() + shell.assets.filter { it.type == AssetType.VIDEO }).distinctBy { it.id }
    val running = jobs.firstOrNull { it.state == JobState.RUNNING }
    val clip = clips.firstOrNull { it.id == selected } ?: clips.firstOrNull()

    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.message?.let { ErrorLine(it, actions::dismiss) }
            when {
                state.loaded && state.backends.isEmpty() -> EmptyRoom(StudioRoomWords.videoNoPc) {
                    Button(onClick = onOpenWorkflows, Modifier.fillMaxWidth()) { Text(StudioRoomWords.videoNoPc.action!!) }
                }
                state.loaded && state.workflows.isEmpty() -> EmptyRoom(StudioRoomWords.videoNoWorkflow) {
                    Button(onClick = onOpenWorkflows, Modifier.fillMaxWidth()) { Text(StudioRoomWords.videoNoWorkflow.action!!) }
                }
            }
            if (running != null) {
                val preview = rememberDecoded(running.preview?.bytes)
                Column(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large)) {
                    DevelopingPreview(preview, Modifier.fillMaxSize())
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running.steps == 0) InlineLoading(contentDescription = null)
                    Caption(running.status ?: "queued on ${state.backend?.name}")
                }
            }
            if (clip != null) Player(clip, actions)
            if (clips.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    clips.take(20).forEach { a ->
                        AssetThumb(a, Modifier.size(width = 96.dp, height = 54.dp).then(if (a.id == clip?.id) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier).clickable { selected = a.id })
                    }
                }
            }
        }
        val workflow = state.workflow
        if (workflow != null && state.backends.isNotEmpty()) {
            ControlSheet(modifier = Modifier.weight(1.5f, fill = false), expanded = controls, onToggle = { controls = !controls }, summary = "${workflow.name} · ${counted(workflow.params.size, "param")}") {
                if (state.workflows.size > 1) ChipRow(state.workflows, workflow, { actions.selectWorkflow(it.id) }, { it.name })
                if (state.backends.size > 1) ChipRow(state.backends, state.backend, { actions.selectBackend(it.id) }, { it.name })
                ParamControls(workflow.params, state.values, actions::setValue, shell.assets)
                Row(
                    Modifier.fillMaxWidth().toggleable(state.randomizeSeed, role = Role.Switch, onValueChange = actions::setRandomizeSeed),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("New seed each run", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = state.randomizeSeed, onCheckedChange = null)
                }
            }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RunsOn(state.backend?.name)
                Button(onClick = actions::run, Modifier.fillMaxWidth(), enabled = state.backend != null) { Text("Run on ${state.backend?.name ?: "PC"}") }
            }
        }
    }
}

@Composable
private fun Player(clip: StudioAsset, actions: VideoActions) {
    val loader = LocalAssetLoader.current
    val player = rememberBytePlayer()
    val bytes by produceState<ByteArray?>(null, clip.id) { value = loader.bytes(clip.id) }
    val data = bytes
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (data == null) {
            AssetThumb(clip, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        } else {
            VideoSurface(data, clip.mimeType, player, Modifier.fillMaxWidth().semantics { contentDescription = "Video ${clip.label ?: ""}" })
        }
        if (!clip.mimeType.startsWith("image/")) {
            Transport(player, extra = { TextButton(onClick = { actions.exportFrame(clip, player.positionMs) }) { Text("Frame") } })
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Caption("animated ${clip.mimeType.substringAfter('/')} · loops", Modifier.weight(1f))
                OutlinedButton(onClick = { actions.exportFrame(clip, 0) }) { Text("First frame") }
            }
        }
        Row(Modifier.height(4.dp)) {}
    }
}

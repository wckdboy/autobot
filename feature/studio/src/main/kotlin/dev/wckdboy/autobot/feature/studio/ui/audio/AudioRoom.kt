package dev.wckdboy.autobot.feature.studio.ui.audio

import dev.wckdboy.autobot.shared.studio.StudioRoomWords
import dev.wckdboy.autobot.core.designsystem.component.DevelopingPreview
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.wckdboy.autobot.core.designsystem.component.Caption
import dev.wckdboy.autobot.core.designsystem.component.Choices
import dev.wckdboy.autobot.core.designsystem.component.DropdownField
import dev.wckdboy.autobot.core.designsystem.component.Field
import dev.wckdboy.autobot.core.designsystem.component.JobProgress
import dev.wckdboy.autobot.core.designsystem.component.SheetLabel
import dev.wckdboy.autobot.core.designsystem.component.SliderField
import dev.wckdboy.autobot.core.designsystem.component.StepperField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.core.models.StableAudioCatalog
import dev.wckdboy.autobot.feature.studio.ui.ControlSheet
import dev.wckdboy.autobot.feature.studio.ui.rememberControlsOpen
import dev.wckdboy.autobot.feature.studio.ui.EmptyRoom
import dev.wckdboy.autobot.feature.studio.ui.ErrorLine
import dev.wckdboy.autobot.feature.studio.ui.LocalAssetLoader
import dev.wckdboy.autobot.feature.studio.ui.RunsOn
import dev.wckdboy.autobot.feature.studio.ui.SeedControl
import dev.wckdboy.autobot.feature.studio.ui.StudioShellState
import dev.wckdboy.autobot.feature.studio.ui.Transport
import dev.wckdboy.autobot.feature.studio.ui.Waveform
import dev.wckdboy.autobot.feature.studio.ui.WildcardPreview
import dev.wckdboy.autobot.feature.studio.ui.duration
import dev.wckdboy.autobot.feature.studio.ui.rememberBytePlayer
import dev.wckdboy.autobot.shared.audiogen.StableAudio
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.JobState
import dev.wckdboy.autobot.shared.studio.StudioAsset
import java.util.Locale

/**
 * The audio room: the clip is the canvas (waveform, loop playback, trim), the controls fold
 * away. Sound runs Stable Audio Open Small on the phone ("Powered by Stability AI", as its licence
 * asks); voiceover reads text with a Kokoro voice.
 */
@Composable
fun AudioRoom(state: AudioRoomState, shell: StudioShellState, actions: AudioActions, onOpenModels: () -> Unit, onOpenWorkflows: () -> Unit, modifier: Modifier = Modifier) {
    var controls by rememberControlsOpen()
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val assets = shell.assets.associateBy { it.id }
    val jobs = state.jobIds.mapNotNull { id -> shell.jobs.firstOrNull { it.id == id } }
    val clips = jobs.flatMap { it.outputs }.mapNotNull(assets::get).reversed()
    val recent = clips.ifEmpty { shell.assets.filter { it.type == AssetType.AUDIO }.take(8) }
    val running = jobs.firstOrNull { it.state == JobState.RUNNING }
    val clip = recent.firstOrNull { it.id == selected } ?: recent.firstOrNull()

    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Choices(AudioTab.entries, state.tab, actions::setTab, Modifier.fillMaxWidth()) { it.label }
            state.error?.let { ErrorLine(it, actions::dismiss) }
            state.message?.let { ErrorLine(it, actions::dismiss) }
            if (state.tab == AudioTab.SOUND) {
                PoweredByStability()
                if (state.loaded && !state.hasAudioModel) {
                    EmptyRoom(StudioRoomWords.audioNoModel) { Button(onClick = onOpenModels, Modifier.fillMaxWidth()) { Text(StudioRoomWords.audioNoModel.action!!) } }
                }
            } else if (state.loaded && !state.hasVoice) {
                EmptyRoom(StudioRoomWords.voiceNoModel) {
                    Button(onClick = onOpenModels, Modifier.fillMaxWidth()) { Text(StudioRoomWords.voiceNoModel.action!!) }
                }
            }
            if (running != null) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevelopingPreview(null, Modifier.fillMaxWidth().height(120.dp).clip(MaterialTheme.shapes.large))
                    JobProgress(if (running.steps > 0) running.progress else null, Modifier.fillMaxWidth())
                    Caption(running.status ?: "making ${running.label ?: "audio"}")
                }
            }
            if (clip != null) ClipPlayer(clip, actions)
            if (recent.size > 1) {
                SheetLabel("clips")
                Column(Modifier.selectableGroup()) {
                    recent.take(12).forEach { a ->
                        val isSelected = a.id == clip?.id
                        ListItem(
                            headlineContent = {
                                Text(
                                    a.label ?: dev.wckdboy.autobot.feature.studio.engine.StudioRecipes.title(a.recipe),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingContent = { dev.wckdboy.autobot.feature.studio.ui.AssetThumb(a, Modifier.size(width = 72.dp, height = 32.dp)) },
                            trailingContent = { Text(duration(a.durationMs), style = MaterialTheme.typography.labelLarge) },
                            colors = ListItemDefaults.colors(containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                            modifier = Modifier.clip(MaterialTheme.shapes.medium).selectable(selected = isSelected, onClick = { selected = a.id }),
                        )
                    }
                }
            }
            if (state.audioWorkflows.isNotEmpty()) {
                Caption("${state.audioWorkflows.size} audio workflow${if (state.audioWorkflows.size == 1) "" else "s"} for your PC (ComfyUI)")
                OutlinedButton(onClick = onOpenWorkflows) { Text("Open workflows") }
            }
        }
        ControlSheet(
            modifier = Modifier.weight(1.5f, fill = false),
            expanded = controls,
            onToggle = { controls = !controls },
            summary = if (state.tab == AudioTab.SOUND) "${state.seconds} s · ${counted(state.steps, "step")} · cfg ${state.cfg}" else "${state.voice} · ×${state.speed}",
        ) {
            if (state.tab == AudioTab.SOUND) SoundControls(state, actions) else VoiceControls(state, actions)
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RunsOn(null)
            if (state.tab == AudioTab.SOUND) {
                Button(onClick = actions::generate, Modifier.fillMaxWidth(), enabled = state.canGenerate) { Text(if (state.batch > 1) "Queue ${state.batch} clips" else "Make the sound") }
            } else {
                Button(onClick = actions::speak, Modifier.fillMaxWidth(), enabled = state.canSpeak) { Text("Read it") }
            }
        }
    }
}

/** The licence's attribution, shown prominently wherever Stable Audio runs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoweredByStability(modifier: Modifier = Modifier) {
    OutlinedCard(modifier.semantics(mergeDescendants = true) { contentDescription = StableAudioCatalog.ATTRIBUTION }) {
        FlowRow(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(StableAudioCatalog.ATTRIBUTION, Modifier.stamp("stability"), style = MaterialTheme.typography.titleSmall)
            Text("· community licence", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ClipPlayer(clip: StudioAsset, actions: AudioActions) {
    val loader = LocalAssetLoader.current
    val player = rememberBytePlayer()
    val bytes by produceState<ByteArray?>(null, clip.id) { value = loader.bytes(clip.id) }
    val peaks by produceState<FloatArray?>(null, clip.id) { value = loader.peaks(clip.id) }
    LaunchedEffect(bytes) { bytes?.let(player::load) }
    var trim by remember(clip.id) { mutableStateOf(0f..1f) }
    val total = clip.durationMs.takeIf { it > 0 } ?: player.durationMs
    LaunchedEffect(trim, total) { player.range = if (trim == 0f..1f || total <= 0) null else (trim.start * total).toLong()..(trim.endInclusive * total).toLong() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SheetLabel(clip.label ?: dev.wckdboy.autobot.feature.studio.engine.StudioRecipes.title(clip.recipe).ifBlank { "clip" }, Modifier.semantics { heading() })
        Waveform(
            peaks,
            Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "Waveform, ${duration(total)}" },
            progress = if (total > 0) player.positionMs.toFloat() / total else -1f,
            trim = trim,
        )
        RangeSlider(
            value = trim,
            onValueChange = { trim = it },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Trim range" },
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Caption("trim ${duration((trim.start * total).toLong())} – ${duration((trim.endInclusive * total).toLong())}", Modifier.weight(1f))
            OutlinedButton(
                onClick = { actions.trim(clip, (trim.start * total).toLong(), (trim.endInclusive * total).toLong()) },
                enabled = trim != 0f..1f && clip.mimeType.contains("wav"),
            ) { Text("Save trim") }
        }
        Transport(player)
    }
}

@Composable
private fun SoundControls(state: AudioRoomState, actions: AudioActions) {
    Field(state.prompt, actions::setPrompt, Modifier.fillMaxWidth(), label = "Describe the sound", placeholder = "rain on a tin roof, distant thunder, 90 bpm", minLines = 2, maxLines = 5)
    WildcardPreview(state.prompt, state.batch)
    SliderField("Seconds", state.seconds, actions::setSeconds, 0.5f..StableAudio.MAX_SECONDS.toFloat(), format = { String.format(Locale.ROOT, "%.1f s", it) })
    SliderField("Steps", state.steps.toFloat(), { actions.setSteps(it.toInt()) }, 1f..32f, format = { it.toInt().toString() })
    SliderField("CFG", state.cfg, actions::setCfg, 1f..10f, format = { String.format(Locale.ROOT, "%.1f", it) })
    if (state.cfg > 1f) Caption("cfg above 1 runs the model twice per step (the distilled model is made for 1)")
    SeedControl(state.seed, state.seedMode, actions::setSeed, actions::setSeedMode)
    StepperField("Batch", state.batch, actions::setBatch, 1..8)
}

@Composable
private fun VoiceControls(state: AudioRoomState, actions: AudioActions) {
    Field(state.text, actions::setText, Modifier.fillMaxWidth(), label = "Text to read", minLines = 3, maxLines = 10)
    DropdownField("Voice", state.voice, state.voices.ifEmpty { listOf(state.voice) }, actions::setVoice, Modifier.fillMaxWidth())
    SliderField("Speed", state.speed, actions::setSpeed, 0.5f..2f, format = { "×" + String.format(Locale.ROOT, "%.2f", it) })
}


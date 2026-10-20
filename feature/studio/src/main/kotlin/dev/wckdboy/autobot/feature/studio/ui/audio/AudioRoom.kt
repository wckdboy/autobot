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
import androidx.compose.material3.SliderDefaults
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
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.SelectField
import dev.wckdboy.autobot.core.designsystem.component.Stepper
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.ValueSlider
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.models.StableAudioCatalog
import dev.wckdboy.autobot.feature.studio.ui.ControlSheet
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
    var controls by rememberSaveable { mutableStateOf(true) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val assets = shell.assets.associateBy { it.id }
    val jobs = state.jobIds.mapNotNull { id -> shell.jobs.firstOrNull { it.id == id } }
    val clips = jobs.flatMap { it.outputs }.mapNotNull(assets::get).reversed()
    val recent = clips.ifEmpty { shell.assets.filter { it.type == AssetType.AUDIO }.take(8) }
    val running = jobs.firstOrNull { it.state == JobState.RUNNING }
    val clip = recent.firstOrNull { it.id == selected } ?: recent.firstOrNull()

    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(AudioTab.entries, state.tab, actions::setTab, { it.label }, Modifier.fillMaxWidth())
            state.error?.let { ErrorLine(it, actions::dismiss) }
            state.message?.let { ErrorLine(it, actions::dismiss) }
            if (state.tab == AudioTab.SOUND) {
                PoweredByStability()
                if (state.loaded && !state.hasAudioModel) {
                    EmptyRoom(StudioRoomWords.audioNoModel) { PrimaryButton(StudioRoomWords.audioNoModel.action!!, onOpenModels, Modifier.fillMaxWidth()) }
                }
            } else if (state.loaded && !state.hasVoice) {
                EmptyRoom(StudioRoomWords.voiceNoModel) {
                    PrimaryButton(StudioRoomWords.voiceNoModel.action!!, onOpenModels, Modifier.fillMaxWidth())
                }
            }
            if (running != null) {
                Column(Modifier.fillMaxWidth().height(140.dp)) {
                    DevelopingPreview(null, Modifier.fillMaxWidth().weight(1f))
                    InkProgressBar(if (running.steps > 0) running.progress else null, Modifier.fillMaxWidth().height(3.dp))
                    MicroLabel(running.status ?: "making ${running.label ?: "audio"}", Modifier.padding(top = 4.dp))
                }
            }
            if (clip != null) ClipPlayer(clip, actions)
            if (recent.size > 1) {
                MicroLabel("clips")
                recent.take(12).forEach { a ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (a.id == clip?.id) Modifier.border(1.dp, MaterialTheme.colorScheme.primary) else Modifier)
                            .clickable { selected = a.id }
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        dev.wckdboy.autobot.feature.studio.ui.AssetThumb(a, Modifier.height(28.dp).weight(0.4f))
                        Text(
                            a.label ?: dev.wckdboy.autobot.feature.studio.engine.StudioRecipes.title(a.recipe),
                            Modifier.weight(0.6f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(duration(a.durationMs), style = AutobotTheme.styles.readout)
                    }
                }
            }
            if (state.audioWorkflows.isNotEmpty()) {
                MicroLabel("${state.audioWorkflows.size} audio workflow${if (state.audioWorkflows.size == 1) "" else "s"} for your PC (ComfyUI)")
                Tag("open workflows →", onClick = onOpenWorkflows)
            }
        }
        ControlSheet(
            expanded = controls,
            onToggle = { controls = !controls },
            summary = if (state.tab == AudioTab.SOUND) "${state.seconds} s · ${counted(state.steps, "step")} · cfg ${state.cfg}" else "${state.voice} · ×${state.speed}",
        ) {
            if (state.tab == AudioTab.SOUND) SoundControls(state, actions) else VoiceControls(state, actions)
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            RunsOn(null)
            if (state.tab == AudioTab.SOUND) {
                PrimaryButton(if (state.batch > 1) "Queue ${state.batch} clips" else "Make the sound", actions::generate, Modifier.fillMaxWidth(), enabled = state.canGenerate)
            } else {
                PrimaryButton("Read it", actions::speak, Modifier.fillMaxWidth(), enabled = state.canSpeak)
            }
        }
    }
}

/** The licence's attribution, shown prominently wherever Stable Audio runs. */
@Composable
fun PoweredByStability(modifier: Modifier = Modifier) {
    Row(
        modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = StableAudioCatalog.ATTRIBUTION },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(StableAudioCatalog.ATTRIBUTION, Modifier.stamp("stability"), style = AutobotTheme.styles.action, color = MaterialTheme.colorScheme.onBackground)
        Text("· community licence", style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
        SectionLabel(clip.label ?: dev.wckdboy.autobot.feature.studio.engine.StudioRecipes.title(clip.recipe).ifBlank { "clip" }, Modifier.semantics { heading() })
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
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MicroLabel("trim ${duration((trim.start * total).toLong())} – ${duration((trim.endInclusive * total).toLong())}", Modifier.weight(1f))
            GhostButton(
                "Save trim",
                { actions.trim(clip, (trim.start * total).toLong(), (trim.endInclusive * total).toLong()) },
                enabled = trim != 0f..1f && clip.mimeType.contains("wav"),
            )
        }
        Transport(player)
    }
}

@Composable
private fun SoundControls(state: AudioRoomState, actions: AudioActions) {
    ConsoleTextField(state.prompt, actions::setPrompt, Modifier.fillMaxWidth(), label = "Describe the sound", placeholder = "rain on a tin roof, distant thunder, 90 bpm", minLines = 2, maxLines = 5)
    WildcardPreview(state.prompt, state.batch)
    ValueSlider("Seconds", state.seconds, actions::setSeconds, 0.5f..StableAudio.MAX_SECONDS.toFloat(), format = { String.format(Locale.ROOT, "%.1f s", it) })
    ValueSlider("Steps", state.steps.toFloat(), { actions.setSteps(it.toInt()) }, 1f..32f, format = { it.toInt().toString() })
    ValueSlider("CFG", state.cfg, actions::setCfg, 1f..10f, format = { String.format(Locale.ROOT, "%.1f", it) })
    if (state.cfg > 1f) MicroLabel("cfg above 1 runs the model twice per step (the distilled model is made for 1)")
    SeedControl(state.seed, state.seedMode, actions::setSeed, actions::setSeedMode)
    Stepper("Batch", state.batch, actions::setBatch, 1..8)
}

@Composable
private fun VoiceControls(state: AudioRoomState, actions: AudioActions) {
    ConsoleTextField(state.text, actions::setText, Modifier.fillMaxWidth(), label = "Text to read", minLines = 3, maxLines = 10)
    SelectField("Voice", state.voice, state.voices.ifEmpty { listOf(state.voice) }, actions::setVoice, Modifier.fillMaxWidth())
    ValueSlider("Speed", state.speed, actions::setSpeed, 0.5f..2f, format = { "×" + String.format(Locale.ROOT, "%.2f", it) })
}


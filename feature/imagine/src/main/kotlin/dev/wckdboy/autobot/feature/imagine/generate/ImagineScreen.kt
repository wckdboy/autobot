package dev.wckdboy.autobot.feature.imagine.generate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.BigReadout
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.HalftoneField
import dev.wckdboy.autobot.core.designsystem.component.HalftoneImage
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.PromptKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.designsystem.component.RouteTag
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.SelectField
import dev.wckdboy.autobot.core.designsystem.component.Stepper
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.ValueSlider
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.MaskEncoding
import dev.wckdboy.autobot.core.diffusion.PromptSyntax
import dev.wckdboy.autobot.core.diffusion.engine.LocalSseEngine
import dev.wckdboy.autobot.core.diffusion.engine.OnDeviceEngine
import dev.wckdboy.autobot.feature.imagine.mask.MaskEditorDialog
import dev.wckdboy.autobot.feature.imagine.mask.MaskOverlay
import java.util.Locale
import kotlinx.coroutines.delay

/** One-shot instruction from the gallery (reuse parameters / use as init). */
data class GalleryHandoff(val galleryId: String, val asInit: Boolean)

@Composable
fun ImagineRoute(
    onBack: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenBackends: () -> Unit,
    onOpenModels: () -> Unit,
    handoff: GalleryHandoff? = null,
    viewModel: ImagineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(handoff) {
        handoff ?: return@LaunchedEffect
        viewModel.reuseParams(handoff.galleryId)
        if (handoff.asInit) viewModel.useAsInit(handoff.galleryId)
    }
    ImagineScreen(state, viewModel, onBack, onOpenGallery, onOpenBackends, onOpenModels)
}

private val ASPECTS = listOf("1:1" to (1 to 1), "2:3" to (2 to 3), "3:2" to (3 to 2), "3:4" to (3 to 4), "4:3" to (4 to 3), "9:16" to (9 to 16), "16:9" to (16 to 9))
private val BASES = listOf(512, 768, 1024)
private val DEFAULT_SAMPLERS = listOf("DPM++ 2M", "DPM++ SDE", "Euler a", "Euler", "LCM", "DDIM", "UniPC")

/** The image studio (mockup 04): paper surface, big prompt, model chips, readouts, 2×2 proofs. */
@Composable
fun ImagineScreen(
    state: ImagineUiState,
    actions: ImagineViewModel,
    onBack: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenBackends: () -> Unit,
    onOpenModels: () -> Unit,
) {
    var showTune by rememberSaveable { mutableStateOf(false) }
    var showMask by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val request = state.request
    val onDevice = state.backend?.kind == BackendKind.ON_DEVICE
    val route = state.backend?.let { if (onDevice || it.isLoopback) Route.LOCAL_CPU else Route.PC_LAN }

    Scaffold(
        topBar = {
            AutobotTopBar(
                title = "Image",
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    route?.let { RouteTag(it) }
                    IconButton(onClick = onOpenGallery) { Icon(AutobotIcons.Image, contentDescription = "Gallery") }
                },
            )
        },
        bottomBar = { StudioBar(state, actions, onShowTune = { showTune = true }) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.padding(padding).imePadding().fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.error?.let { ErrorLine(it, actions::dismissError) }
            if (state.loaded && state.backends.isEmpty()) {
                SectionLabel("no image engine yet")
                Text(
                    "Download an image model to run on this phone, or point Autobot at a PC (A1111 / Forge / Local Dream host).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Get a model", onOpenModels, Modifier.weight(1f))
                    GhostButton("Add a pc", onOpenBackends, Modifier.weight(1f))
                }
            }

            PromptField(request.prompt, actions::setPrompt, state.tokenEstimate)

            // Model chips: on-device models and each PC backend's checkpoints.
            if (state.chips.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val current = state.selectedChip
                    state.chips.forEach { chip ->
                        val active = chip == current
                        Text(
                            chip.label.uppercase(Locale.ROOT),
                            modifier = Modifier
                                .background(if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.background, MaterialTheme.shapes.extraSmall)
                                .border(1.dp, MaterialTheme.colorScheme.onBackground, MaterialTheme.shapes.extraSmall)
                                .clickable { actions.selectChip(chip) }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            style = AutobotTheme.styles.micro,
                            color = if (active) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                        )
                    }
                    Text(
                        "+ MODEL",
                        modifier = Modifier.clickable(onClick = onOpenModels).padding(horizontal = 6.dp, vertical = 5.dp),
                        style = AutobotTheme.styles.micro,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Hairline(color = MaterialTheme.colorScheme.onBackground)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BigReadout("Steps", request.steps.toString(), onClick = { showTune = true })
                BigReadout("Seed", if (request.seed < 0) "rnd" else request.seed.toString().takeLast(6), onClick = { showTune = true })
                BigReadout("Size", sizeLabel(request.width, request.height), onClick = { showTune = true })
                BigReadout("Batch", request.batchCount.toString(), onClick = { showTune = true })
            }
            Hairline(color = MaterialTheme.colorScheme.onBackground)

            if (request.mode != DiffusionMode.TXT2IMG) {
                InitStrip(state, actions, onEditMask = { showMask = true })
            }

            ProofGrid(state, selected, onSelect = { selected = it })

            state.results.getOrNull(selected.coerceAtLeast(0))?.takeIf { state.running == null }?.let { result ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MicroLabel("seed ${result.seed} · ${result.width}×${result.height} · ${String.format(Locale.ROOT, "%.1f", result.durationMs / 1000f)} s", Modifier.weight(1f))
                    Tag("↺ seed", accent = MaterialTheme.colorScheme.primary, onClick = { actions.setSeed(result.seed) })
                    Tag("→ img2img", onClick = { actions.useAsInit(result.galleryId) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showTune) TuneSheet(state, actions, onDismiss = { showTune = false }, onOpenBackends = onOpenBackends)
    if (showMask && state.initImage != null) {
        MaskEditorDialog(
            image = state.initImage,
            mask = state.mask,
            onStroke = actions::addStroke,
            onUndo = actions::undoStroke,
            onRedo = actions::redoStroke,
            onInvert = actions::invertMask,
            onClear = actions::clearMask,
            onDismiss = { showMask = false },
        )
    }
}

private fun sizeLabel(w: Int, h: Int) = if (w == h) "$w²" else "${w}×$h"

/** The prompt, set in the display face with a red caret (mockup 04). */
@Composable
private fun PromptField(value: String, onChange: (String) -> Unit, tokens: Int) {
    Column {
        NoPersonalizedLearning {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onBackground),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = PromptKeyboardOptions,
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) {
                            Text("Describe an image…", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.outline)
                        }
                        inner()
                    }
                },
            )
        }
        MicroLabel("~$tokens / 75 tokens", color = if (tokens > 75) AutobotColors.RedDeep else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 2×2 proofs: halftone while sampling, the image when done. */
@Composable
private fun ProofGrid(state: ImagineUiState, selected: Int, onSelect: (Int) -> Unit) {
    val running = state.running
    val count = if (running != null) running.batch.coerceAtLeast(1) else state.results.size
    val slots = count.coerceAtLeast(1).coerceAtMost(4)
    val ratio = (state.request.width.toFloat() / state.request.height).coerceIn(0.5f, 2f)
    val cols = if (slots == 1) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until (slots + cols - 1) / cols) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (col in 0 until cols) {
                    val i = row * cols + col
                    if (i >= slots) {
                        Spacer(Modifier.weight(1f))
                        continue
                    }
                    val result = state.results.getOrNull(i)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(ratio)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .then(if (i == selected && result != null) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
                            .clickable(enabled = result != null) { onSelect(i) },
                    ) {
                        val preview = running?.preview
                        when {
                            result != null -> Image(result.bitmap, contentDescription = "Result ${i + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            running != null && i == running.image && preview != null -> HalftoneImage(preview, Modifier.fillMaxSize(), cell = 5.dp)
                            running != null -> HalftoneField(Modifier.fillMaxSize(), cell = 7.dp, dot = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), phase = i.toFloat())
                            else -> HalftoneField(Modifier.fillMaxSize(), cell = 9.dp, dot = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (running != null && i == running.image) {
                            Tag(
                                if (running.totalSteps > 0) "step ${running.step}/${running.totalSteps}" else "loading",
                                Modifier.align(Alignment.BottomStart).padding(6.dp),
                                accent = MaterialTheme.colorScheme.primary,
                                filled = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioBar(state: ImagineUiState, actions: ImagineViewModel, onShowTune: () -> Unit) {
    val running = state.running
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running?.startedAt) {
        while (running != null) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    running != null && running.byAgent -> "Agent is generating · ${(now - running.startedAt) / 1000} s"
                    running != null -> "Running ${if (state.backend?.kind == BackendKind.ON_DEVICE) "on device" else "on ${state.backend?.name}"} · ${(now - running.startedAt) / 1000} s"
                    state.results.isNotEmpty() -> "Done · ${state.results.size} image${if (state.results.size == 1) "" else "s"}"
                    else -> "${state.request.mode.label} · ${state.request.sampler.ifBlank { "default sampler" }} · cfg ${state.request.cfgScale}"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (running != null) {
                Text("${state.results.size}/${running.batch}", style = AutobotTheme.styles.readout)
            } else {
                TextButton(onClick = onShowTune) { Text("TUNE", style = MaterialTheme.typography.labelLarge) }
            }
        }
        LinearProgressIndicator(
            progress = { running?.let { if (it.totalSteps > 0) (it.image + it.step.toFloat() / it.totalSteps) / it.batch.coerceAtLeast(1) else 0f } ?: 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.outlineVariant,
            drawStopIndicator = {},
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                running != null -> GhostButton("Cancel", actions::cancel, Modifier.fillMaxWidth(), accent = MaterialTheme.colorScheme.primary, enabled = !running.byAgent)
                state.results.isNotEmpty() -> {
                    GhostButton("Variations", actions::variations, Modifier.weight(1f), enabled = state.canGenerate)
                    PrimaryButton("Run again", actions::runAgain, Modifier.weight(1f), enabled = state.canGenerate)
                }
                else -> PrimaryButton("Generate", actions::generate, Modifier.fillMaxWidth(), enabled = state.canGenerate)
            }
        }
    }
}

@Composable
private fun InitStrip(state: ImagineUiState, actions: ImagineViewModel, onEditMask: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(actions::setInitImage) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.size(72.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            contentAlignment = Alignment.Center,
        ) {
            val init: ImageBitmap? = state.initImage
            if (init != null) {
                Image(init, contentDescription = "Init image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (state.request.mode == DiffusionMode.INPAINT) MaskOverlay(state.mask, Modifier.fillMaxSize())
            } else {
                Icon(AutobotIcons.Image, contentDescription = "Pick an image", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MicroLabel(if (state.request.mode == DiffusionMode.INPAINT) "inpaint · ${if (state.mask.isEmpty) "no mask" else "${state.mask.strokes.size} strokes"}" else "img2img")
            ValueSlider("Denoise", state.request.denoiseStrength, actions::setDenoise, 0.05f..1f)
        }
        if (state.request.mode == DiffusionMode.INPAINT && state.initImage != null) {
            IconButton(onClick = onEditMask) { Icon(AutobotIcons.Brush, contentDescription = "Edit mask") }
        }
    }
}

@Composable
private fun ErrorLine(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.weight(1f))
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp)) }
    }
}

/** Every parameter, out of the way: mode, negative, sampler, cfg, size, seed, batch, LoRA. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TuneSheet(state: ImagineUiState, actions: ImagineViewModel, onDismiss: () -> Unit, onOpenBackends: () -> Unit) {
    val request = state.request
    val caps = state.capabilities
    var showLoras by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("tune", Modifier.weight(1f))
                Tag("backends", onClick = { onDismiss(); onOpenBackends() })
            }
            val modes = caps?.modes?.let { m -> DiffusionMode.entries.filter { it in m } } ?: DiffusionMode.entries
            Segmented(modes, request.mode, actions::setMode, { it.label }, Modifier.fillMaxWidth())

            ConsoleTextField(request.negativePrompt, actions::setNegative, Modifier.fillMaxWidth(), label = "Negative", minLines = 2, maxLines = 5, mono = true)

            val (samplers, value) = when (state.backend?.kind) {
                BackendKind.ON_DEVICE -> OnDeviceEngine.SAMPLERS to OnDeviceEngine.samplerId(request.sampler).ifBlank { "model default" }
                BackendKind.LOCAL_SSE -> LocalSseEngine.SCHEDULERS to LocalSseEngine.schedulerId(request.sampler, request.scheduler)
                else -> state.catalog.samplers.ifEmpty { DEFAULT_SAMPLERS } to request.sampler
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectField("Sampler", value, samplers, { actions.setSampler(it, if (state.backend?.kind == BackendKind.SD_API) request.scheduler else null) }, Modifier.weight(1f))
                val schedulers = when (state.backend?.kind) {
                    BackendKind.ON_DEVICE -> OnDeviceEngine.SCHEDULERS
                    BackendKind.SD_API -> state.catalog.schedulers
                    else -> emptyList()
                }
                if (schedulers.isNotEmpty()) {
                    SelectField("Schedule", request.scheduler ?: "default", schedulers, { actions.setScheduler(it) }, Modifier.weight(1f))
                }
            }
            ValueSlider("Steps", request.steps.toFloat(), { actions.setSteps(it.toInt()) }, 1f..(caps?.maxSteps ?: 150).toFloat(), format = { it.toInt().toString() })
            ValueSlider("CFG", request.cfgScale, actions::setCfg, 1f..20f, format = { String.format(Locale.ROOT, "%.1f", it) })

            MicroLabel("size · ${request.width}×${request.height}")
            var base by rememberSaveable { mutableIntStateOf(512) }
            Segmented(BASES, base, { base = it }, { "$it" }, Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ASPECTS.forEach { (label, ratio) ->
                    val (w, h) = aspectSize(base, ratio)
                    val active = w == request.width && h == request.height
                    Tag(label, accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, filled = active, onClick = { actions.setSize(w, h) })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    MicroLabel("seed")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (request.seed < 0) "random" else request.seed.toString(), style = AutobotTheme.styles.readout, modifier = Modifier.weight(1f))
                        IconButton(onClick = actions::randomizeSeed) { Icon(AutobotIcons.Dice, "Fixed random seed") }
                        if (request.seed >= 0) TextButton(onClick = actions::setRandomSeed) { Text("RANDOM") }
                    }
                }
                Stepper("Batch", request.batchCount, actions::setBatch, 1..ImagineViewModel.MAX_BATCH)
            }

            if (request.mode == DiffusionMode.INPAINT && caps?.maskEncoding == MaskEncoding.OPAQUE_BLACK_WHITE && state.backend?.kind == BackendKind.SD_API) {
                ValueSlider("Mask blur", request.maskBlur.toFloat(), { actions.setMaskBlur(it.toInt()) }, 0f..64f, format = { "${it.toInt()} px" })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Only masked area", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(checked = request.inpaintOnlyMasked, onCheckedChange = actions::setOnlyMasked)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                MicroLabel("lora", Modifier.weight(1f))
                if (caps?.runtimeLora == true) Tag("+ add", accent = MaterialTheme.colorScheme.primary, onClick = { showLoras = true })
            }
            if (caps?.runtimeLora == false) MicroLabel("this backend runs a fixed model; merge loras before conversion")
            request.loras.forEach { lora ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ValueSlider(lora.name, lora.weight, { actions.setLoraWeight(lora.name, it) }, -1f..2f, Modifier.weight(1f))
                    IconButton(onClick = { actions.removeLora(lora.name) }) { Icon(Icons.Filled.Close, contentDescription = "Remove ${lora.name}", modifier = Modifier.size(18.dp)) }
                }
            }
            if (request.cfgScale <= 1f) MicroLabel("cfg 1 skips the unconditional pass — right for turbo / lcm / distilled models")
        }
    }
    if (showLoras) {
        LoraSheet(state.catalog.loras, request.loras.map { it.name }.toSet(), onPick = { actions.addLora(it); showLoras = false }, onDismiss = { showLoras = false })
    }
}

private fun aspectSize(base: Int, ratio: Pair<Int, Int>): Pair<Int, Int> {
    val (rw, rh) = ratio
    val area = base.toDouble() * base
    val w = kotlin.math.sqrt(area * rw / rh)
    val h = area / w
    return PromptSyntax.snap(w.toInt()) to PromptSyntax.snap(h.toInt())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoraSheet(available: List<String>, selected: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("lora library · ${available.size}")
            ConsoleTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "filter", singleLine = true, mono = true)
            val filtered = available.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            if (filtered.isEmpty()) MicroLabel(if (available.isEmpty()) "no loras — download some from civitai in the models tab" else "no match")
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                filtered.forEach { name ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = name !in selected) { onPick(name) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AutobotIcons.Layers, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(10.dp))
                        Text(name, style = AutobotTheme.styles.code, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (name in selected) MicroLabel("added", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

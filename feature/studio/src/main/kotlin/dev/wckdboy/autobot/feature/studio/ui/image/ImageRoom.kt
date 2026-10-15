package dev.wckdboy.autobot.feature.studio.ui.image

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.DevelopingHalftone
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.HalftoneField
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.PrintReveal
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.SelectField
import dev.wckdboy.autobot.core.designsystem.component.Stepper
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.ValueSlider
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.motion.inkStagger
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.engine.LocalSseEngine
import dev.wckdboy.autobot.core.diffusion.engine.OnDeviceEngine
import dev.wckdboy.autobot.feature.imagine.mask.MaskEditorDialog
import dev.wckdboy.autobot.feature.imagine.mask.MaskOverlay
import dev.wckdboy.autobot.feature.studio.ui.AssetThumb
import dev.wckdboy.autobot.feature.studio.ui.ChipRow
import dev.wckdboy.autobot.feature.studio.ui.ControlSheet
import dev.wckdboy.autobot.feature.studio.ui.EmptyRoom
import dev.wckdboy.autobot.feature.studio.ui.ErrorLine
import dev.wckdboy.autobot.feature.studio.ui.GridBuilderSheet
import dev.wckdboy.autobot.feature.studio.ui.GridResults
import dev.wckdboy.autobot.feature.studio.ui.LocalAssetLoader
import dev.wckdboy.autobot.feature.studio.ui.NegativePresets
import dev.wckdboy.autobot.feature.studio.ui.PromptEditor
import dev.wckdboy.autobot.feature.studio.ui.PromptLibrarySheet
import dev.wckdboy.autobot.feature.studio.ui.RunsOn
import dev.wckdboy.autobot.feature.studio.ui.SeedControl
import dev.wckdboy.autobot.feature.studio.ui.StudioShellState
import dev.wckdboy.autobot.feature.studio.ui.WildcardPreview
import dev.wckdboy.autobot.feature.studio.ui.rememberDecoded
import dev.wckdboy.autobot.shared.studio.JobState
import dev.wckdboy.autobot.shared.studio.SeedMode
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.Wildcards
import java.util.Locale

private val ASPECTS = listOf("1:1" to (1 to 1), "2:3" to (2 to 3), "3:2" to (3 to 2), "3:4" to (3 to 4), "4:3" to (4 to 3), "9:16" to (9 to 16), "16:9" to (16 to 9))
private val BASES = listOf(512, 768, 1024)
private val DEFAULT_SAMPLERS = listOf("DPM++ 2M", "DPM++ SDE", "Euler a", "Euler", "LCM", "DDIM", "UniPC")

/**
 * The image room (darkroom): the canvas first — the newest result, the developing plate of the
 * running job, or the grid — and a collapsible control sheet with every parameter the selected
 * backend supports.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageRoom(
    state: ImageRoomState,
    shell: StudioShellState,
    actions: ImageActions,
    onOpenModels: () -> Unit,
    onOpenBackends: () -> Unit,
    onOpenAsset: (StudioAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var controls by rememberSaveable { mutableStateOf(true) }
    var showGrid by rememberSaveable { mutableStateOf(false) }
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var showMask by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var cursor by rememberSaveable { mutableIntStateOf(0) }
    val assetsById = shell.assets.associateBy { it.id }
    val myJobs = state.jobIds.mapNotNull { id -> shell.jobs.firstOrNull { it.id == id } }
    val results = myJobs.flatMap { it.outputs }.mapNotNull(assetsById::get).reversed()
    val running = myJobs.firstOrNull { it.state == JobState.RUNNING }
    val pending = myJobs.count { !it.state.isFinished }
    val current = results.firstOrNull { it.id == selected } ?: results.firstOrNull()
    val ratio = (state.request.width.toFloat() / state.request.height).coerceIn(0.5f, 2f)

    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.error?.let { ErrorLine(it, actions::dismissError) }
            state.catalogError?.let { ErrorLine("${state.backend?.name}: $it", actions::dismissError) }
            if (state.loaded && state.backends.isEmpty()) {
                EmptyRoom(
                    "no image engine yet",
                    "Download an image model to run on this phone, or add a PC (A1111 / Forge / Local SSE). ComfyUI workflows run from the Workflows room.",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton("Get a model", onOpenModels, Modifier.weight(1f))
                        GhostButton("Add a pc", onOpenBackends, Modifier.weight(1f))
                    }
                }
            }
            val grid = state.grid
            if (grid != null) {
                GridResults(
                    grid.columns, grid.rows, grid.xLabel, grid.yLabel,
                    cell = { r, c -> grid.cells[r to c]?.let { id -> shell.jobs.firstOrNull { it.id == id } }?.outputs?.firstOrNull()?.let(assetsById::get) },
                    onOpen = onOpenAsset,
                )
            } else {
                Canvas(state, current, running, ratio)
            }
            if (results.size > 1 && grid == null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    results.take(24).forEach { a ->
                        AssetThumb(
                            a,
                            Modifier
                                .size(56.dp)
                                .then(if (a.id == current?.id) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier)
                                .clickable { selected = a.id }
                                .semantics { contentDescription = "Result ${a.label ?: ""}" },
                        )
                    }
                }
            }
            if (current != null && running == null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MicroLabel("${current.width}×${current.height}" + (current.recipe.seed?.let { " · seed $it" } ?: ""), Modifier.padding(top = 4.dp))
                    current.recipe.seed?.let { seed -> Tag("↺ seed", accent = MaterialTheme.colorScheme.primary, onClick = { actions.setSeedMode(SeedMode.LOCKED); actions.setSeed(seed) }) }
                    Tag("→ input", onClick = { actions.useAsInput(current.id) })
                    Tag("upscale ×2", onClick = { actions.upscale(current.id) })
                    Tag("details", onClick = { onOpenAsset(current) })
                }
            }
        }
        ControlSheet(
            expanded = controls,
            onToggle = { controls = !controls },
            summary = "${state.mode.label} · ${state.request.steps} steps · cfg ${state.request.cfgScale} · ${state.request.width}×${state.request.height}",
        ) {
            Controls(state, shell, actions, onOpenModels, onOpenBackends, onEditMask = { showMask = true }, onOpenLibrary = { showLibrary = true }, onCursor = { cursor = it })
        }
        RunBar(state, pending, actions::generate, onGrid = { showGrid = true })
    }

    if (showGrid) GridBuilderSheet(state.gridParams, actions::queueGrid, onDismiss = { showGrid = false })
    if (showLibrary) {
        PromptLibrarySheet(
            state.snippets, state.request.prompt,
            onInsert = { actions.insertSnippet(it, cursor) },
            onApplyStyle = actions::applyStyle,
            onSave = actions::saveSnippet,
            onDelete = actions::deleteSnippet,
            onDismiss = { showLibrary = false },
        )
    }
    val input = state.inputImage
    if (showMask && input != null) {
        MaskEditorDialog(
            image = input,
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

/** The hero: the selected result printed in, the running job developing, or blank paper waiting. */
@Composable
private fun Canvas(state: ImageRoomState, current: StudioAsset?, running: dev.wckdboy.autobot.shared.studio.StudioJob?, ratio: Float) {
    val loader = LocalAssetLoader.current
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (current != null && current.height > 0 && running == null) (current.width.toFloat() / current.height).coerceIn(0.5f, 2f) else ratio)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        when {
            running != null -> {
                val preview = rememberDecoded(running.preview?.bytes)
                DevelopingHalftone(
                    preview = preview,
                    modifier = Modifier.fillMaxSize(),
                    progress = running.progress,
                    loading = running.steps == 0,
                    seed = (running.createdAt % 997).toFloat(),
                )
                if (running.steps > 0) {
                    Tag("step ${running.step}/${running.steps}", Modifier.align(Alignment.BottomStart).padding(8.dp), accent = MaterialTheme.colorScheme.primary, filled = true)
                } else {
                    InkLoader(Modifier.align(Alignment.BottomStart).padding(10.dp), contentDescription = running.status ?: "Loading the model")
                }
            }
            current != null -> {
                val full by produceState<ImageBitmap?>(null, current.id) {
                    value = loader.bytes(current.id)?.let { b ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            dev.wckdboy.autobot.feature.imagine.generate.ImageCodec.decode(b, 1536)?.let { it.asImageBitmap() }
                        }
                    } ?: loader.thumbnail(current.id)
                }
                full?.let { PrintReveal(it, Modifier.fillMaxSize(), contentDescription = current.recipe.string("prompt") ?: "Result", contentScale = ContentScale.Fit, key = current.id) }
            }
            state.inputImage != null -> {
                Image(state.inputImage, contentDescription = "Input image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (state.inpaint) MaskOverlay(state.mask, Modifier.fillMaxSize())
            }
            else -> HalftoneField(Modifier.fillMaxSize(), cell = 9.dp, dot = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RunBar(state: ImageRoomState, pending: Int, onRun: () -> Unit, onGrid: () -> Unit) {
    val jobs = (Wildcards.expand(state.request.prompt).prompts.size.coerceAtLeast(1) * state.batch).coerceAtMost(Wildcards.DEFAULT_CAP)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RunsOn(state.backend?.takeIf { !it.onDevice }?.name, Modifier.weight(1f))
            if (pending > 0) MicroLabel("$pending in queue", color = MaterialTheme.colorScheme.primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("X/Y grid", onGrid, Modifier.weight(1f), enabled = state.canRun)
            PrimaryButton(if (jobs > 1) "Queue $jobs" else "Generate", onRun, Modifier.weight(1.4f), enabled = state.canRun)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    state: ImageRoomState,
    shell: StudioShellState,
    actions: ImageActions,
    onOpenModels: () -> Unit,
    onOpenBackends: () -> Unit,
    onEditMask: () -> Unit,
    onOpenLibrary: () -> Unit,
    onCursor: (Int) -> Unit,
) {
    val request = state.request
    val caps = state.capabilities
    var showLoras by rememberSaveable { mutableStateOf(false) }
    PromptEditor(request.prompt, actions::setPrompt, "Describe an image… {red|blue} makes one per choice", Modifier.inkStagger(0), onOpenLibrary = onOpenLibrary, cursor = onCursor)
    WildcardPreview(request.prompt, state.batch)

    if (state.backends.size > 1) {
        ChipRow(state.backends, state.backend, { actions.selectBackend(it.id) }, { it.name }, trailing = {
            Tag("+ pc", accent = MaterialTheme.colorScheme.primary, onClick = onOpenBackends)
        })
    }
    if (state.models.isNotEmpty()) {
        ChipRow(state.models, state.models.firstOrNull { it.id == request.model } ?: state.models.firstOrNull { it.id == state.catalog.currentModel }, { actions.selectModel(it.id) }, { it.label }, trailing = {
            Tag("+ model", accent = MaterialTheme.colorScheme.primary, onClick = onOpenModels)
        })
    }

    ConsoleTextField(request.negativePrompt, actions::setNegative, Modifier.fillMaxWidth(), label = "Negative", minLines = 1, maxLines = 4, mono = true)
    NegativePresets(request.negativePrompt, actions::setNegative)

    InputStrip(state, shell, actions, onEditMask)

    SectionLabel("sampling")
    val kind = state.backend
    val (samplers, value) = when {
        kind?.onDevice == true -> OnDeviceEngine.SAMPLERS to OnDeviceEngine.samplerId(request.sampler).ifBlank { "model default" }
        kind?.kind == dev.wckdboy.autobot.core.diffusion.BackendKind.LOCAL_SSE -> LocalSseEngine.SCHEDULERS to LocalSseEngine.schedulerId(request.sampler, request.scheduler)
        else -> state.catalog.samplers.ifEmpty { DEFAULT_SAMPLERS } to request.sampler
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectField("Sampler", value, samplers, actions::setSampler, Modifier.weight(1f))
        val schedulers = if (kind?.onDevice == true) OnDeviceEngine.SCHEDULERS else state.catalog.schedulers
        if (schedulers.isNotEmpty()) SelectField("Schedule", request.scheduler ?: "default", schedulers, { actions.setScheduler(it) }, Modifier.weight(1f))
    }
    ValueSlider("Steps", request.steps.toFloat(), { actions.setSteps(it.toInt()) }, 1f..(caps?.maxSteps ?: 150).toFloat(), format = { it.toInt().toString() })
    ValueSlider("CFG", request.cfgScale, actions::setCfg, 1f..20f, format = { String.format(Locale.ROOT, "%.1f", it) })
    if (request.cfgScale <= 1f) MicroLabel("cfg 1 skips the unconditional pass — right for turbo / lcm / distilled models")

    SizeControls(state, actions)

    SectionLabel("seed · batch")
    SeedControl(state.seed, state.seedMode, actions::setSeed, actions::setSeedMode)
    Stepper("Batch", state.batch, actions::setBatch, 1..ImageRoomViewModel.MAX_BATCH)

    if (caps?.hires == true && state.mode == DiffusionMode.TXT2IMG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hires pass", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = state.hires, onCheckedChange = actions::setHires)
        }
        val hires = request.hires
        if (state.hires && hires != null) {
            ValueSlider("Upscale by", hires.scale, actions::setHiresScale, 1.25f..4f, format = { "×" + String.format(Locale.ROOT, "%.2f", it) })
            SelectField("Upscaler", hires.upscaler, state.catalog.upscalers.ifEmpty { listOf("Latent") }, actions::setHiresUpscaler, Modifier.fillMaxWidth())
            ValueSlider("Hires denoise", hires.denoise, actions::setHiresDenoise, 0.05f..1f)
        }
    } else if (state.mode == DiffusionMode.TXT2IMG) {
        MicroLabel("hires: this backend has no second pass — use upscale ×2 on a result")
    }
    if (caps?.vae == true) {
        SelectField("VAE", request.vae ?: "automatic", listOf("automatic") + state.catalog.vaes, { actions.setVae(it.takeIf { v -> v != "automatic" }) }, Modifier.fillMaxWidth())
        Stepper("Clip skip", request.clipSkip ?: 1, { actions.setClipSkip(it.takeIf { v -> v > 1 }) }, 1..4)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("lora stack", Modifier.weight(1f))
        if (caps?.runtimeLora == true) Tag("+ add", accent = MaterialTheme.colorScheme.primary, onClick = { showLoras = true })
    }
    if (caps?.runtimeLora == false) MicroLabel("this backend runs a fixed model; merge loras before conversion")
    request.loras.forEach { lora ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            ValueSlider(lora.name, lora.weight, { actions.setLoraWeight(lora.name, it) }, -1f..2f, Modifier.weight(1f))
            IconButton(onClick = { actions.removeLora(lora.name) }) { Icon(Icons.Filled.Close, contentDescription = "Remove ${lora.name}", modifier = Modifier.size(18.dp)) }
        }
    }
    MicroLabel("controlnet and face / detail passes: import a ComfyUI workflow in Workflows")
    Spacer(Modifier.size(4.dp))
    if (showLoras) LoraSheet(state.catalog.loras, request.loras.map { it.name }.toSet(), { actions.addLora(it); showLoras = false }, { showLoras = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizeControls(state: ImageRoomState, actions: ImageActions) {
    val request = state.request
    val fixed = state.capabilities?.fixedSizes.orEmpty()
    MicroLabel("size · ${request.width}×${request.height}")
    if (fixed.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fixed.forEach { (w, h) ->
                val active = w == request.width && h == request.height
                Tag("${w}×$h", accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, filled = active, onClick = { actions.setSize(w, h) })
            }
        }
        return
    }
    var base by rememberSaveable { mutableIntStateOf(512) }
    dev.wckdboy.autobot.core.designsystem.component.Segmented(BASES, base, { base = it }, { "$it" }, Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ASPECTS.forEach { (label, ratio) ->
            val (w, h) = aspectSize(base, ratio, state.capabilities?.sizeMultiple ?: 8)
            val active = w == request.width && h == request.height
            Tag(label, accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, filled = active, onClick = { actions.setSize(w, h) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stepper("W", request.width, { actions.setSize(it, request.height) }, 64..2048, Modifier.weight(1f))
        Stepper("H", request.height, { actions.setSize(request.width, it) }, 64..2048, Modifier.weight(1f))
    }
}

private fun aspectSize(base: Int, ratio: Pair<Int, Int>, multiple: Int): Pair<Int, Int> {
    val (rw, rh) = ratio
    val area = base.toDouble() * base
    val w = kotlin.math.sqrt(area * rw / rh)
    val h = area / w
    return dev.wckdboy.autobot.core.diffusion.PromptSyntax.snap(w.toInt(), multiple) to dev.wckdboy.autobot.core.diffusion.PromptSyntax.snap(h.toInt(), multiple)
}

/** The input: pick from the library or the phone; img2img strength, inpaint mask, extend canvas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputStrip(state: ImageRoomState, shell: StudioShellState, actions: ImageActions, onEditMask: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(actions::pickInput) }
    var choose by rememberSaveable { mutableStateOf(false) }
    SectionLabel("input · ${state.mode.label}")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
            val input = state.inputImage
            if (input != null) {
                Image(input, contentDescription = "Input image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (state.inpaint) MaskOverlay(state.mask, Modifier.fillMaxSize())
            } else {
                Icon(AutobotIcons.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag("library", onClick = { choose = !choose })
            Tag("phone", onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            if (state.input != null) {
                val inpaint = state.capabilities?.modes?.contains(DiffusionMode.INPAINT) == true
                if (inpaint) Tag(if (state.inpaint) "mask · ${state.mask.strokes.size}" else "inpaint", accent = MaterialTheme.colorScheme.primary, filled = state.inpaint, onClick = { actions.setInpaint(true); onEditMask() })
                Tag("clear", onClick = actions::clearInput)
            }
        }
    }
    if (choose) {
        val images = shell.assets.filter { it.type == dev.wckdboy.autobot.shared.studio.AssetType.IMAGE }.take(30)
        if (images.isEmpty()) MicroLabel("no images in this project yet")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            images.forEach { a -> AssetThumb(a, Modifier.size(56.dp).clickable { actions.useAsInput(a.id); choose = false }.semantics { contentDescription = "Use as input" }) }
        }
    }
    if (state.input != null) {
        ValueSlider("Denoise", state.request.denoiseStrength, actions::setDenoise, 0.05f..1f)
        if (state.capabilities?.modes?.contains(DiffusionMode.INPAINT) == true) {
            MicroLabel("outpaint · extend the canvas (px)")
            val e = state.extend
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("←" to 0, "↑" to 1, "→" to 2, "↓" to 3).forEach { (glyph, side) ->
                    val v = when (side) { 0 -> e.left; 1 -> e.top; 2 -> e.right; else -> e.bottom }
                    Tag(
                        "$glyph ${v}",
                        accent = if (v > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        filled = v > 0,
                        onClick = {
                            val next = if (v >= 256) 0 else v + 128
                            actions.setExtend(when (side) { 0 -> e.copy(left = next); 1 -> e.copy(top = next); 2 -> e.copy(right = next); else -> e.copy(bottom = next) })
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoraSheet(available: List<String>, selected: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("lora library · ${available.size}", Modifier.inkStagger(0))
            ConsoleTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "filter", singleLine = true, mono = true)
            val filtered = available.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            if (filtered.isEmpty()) MicroLabel(if (available.isEmpty()) "no loras on this backend" else "no match")
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                filtered.forEach { name ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = name !in selected) { onPick(name) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = AutobotTheme.styles.code, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (name in selected) MicroLabel("added", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

package dev.wckdboy.autobot.feature.imagine.generate

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Panel
import dev.wckdboy.autobot.core.designsystem.component.Readout
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.SelectField
import dev.wckdboy.autobot.core.designsystem.component.StatusDot
import dev.wckdboy.autobot.core.designsystem.component.Stepper
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.ValueSlider
import dev.wckdboy.autobot.core.designsystem.component.hairlineEdge
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.engine.LocalSseEngine
import dev.wckdboy.autobot.feature.imagine.mask.MaskEditorDialog
import dev.wckdboy.autobot.feature.imagine.mask.MaskOverlay
import java.util.Locale
import kotlinx.coroutines.delay

/** One-shot instruction from the gallery (reuse parameters / use as init). */
data class GalleryHandoff(val galleryId: String, val asInit: Boolean)

@Composable
fun ImagineRoute(
    onOpenGallery: () -> Unit,
    onOpenBackends: () -> Unit,
    handoff: GalleryHandoff? = null,
    viewModel: ImagineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(handoff) {
        handoff ?: return@LaunchedEffect
        viewModel.reuseParams(handoff.galleryId)
        if (handoff.asInit) viewModel.useAsInit(handoff.galleryId)
    }
    ImagineScreen(state, viewModel, onOpenGallery, onOpenBackends)
}

private val ASPECTS = listOf("1:1" to (1 to 1), "2:3" to (2 to 3), "3:2" to (3 to 2), "3:4" to (3 to 4), "4:3" to (4 to 3), "9:16" to (9 to 16), "16:9" to (16 to 9))
private val BASES = listOf(512, 768, 1024)
private val DEFAULT_SAMPLERS = listOf("DPM++ 2M", "DPM++ SDE", "DPM++ 2M SDE", "Euler a", "Euler", "LCM", "DDIM", "UniPC")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImagineScreen(
    state: ImagineUiState,
    actions: ImagineViewModel,
    onOpenGallery: () -> Unit,
    onOpenBackends: () -> Unit,
) {
    var showMask by rememberSaveable { mutableStateOf(false) }
    var showLoras by rememberSaveable { mutableStateOf(false) }
    var showNegative by rememberSaveable { mutableStateOf(false) }
    var selectedResult by rememberSaveable { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(actions::setInitImage) }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    val request = state.request
    val caps = state.capabilities

    LaunchedEffect(state.results.size) { selectedResult = (state.results.size - 1).coerceAtLeast(0) }

    Scaffold(
        topBar = { ImagineTopBar(state, actions::selectBackend, actions::refreshCatalog, onOpenGallery, onOpenBackends) },
        bottomBar = { GenerateBar(state, actions::generate, actions::cancel) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .imePadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loaded && state.backends.isEmpty()) NoBackendCard(onOpenBackends)
            state.error?.let { ErrorStrip(it, actions::dismissError) }
            state.catalogError?.let { MicroLabel("backend: $it", color = MaterialTheme.colorScheme.error) }

            val modes = caps?.modes?.let { m -> DiffusionMode.entries.filter { it in m } } ?: DiffusionMode.entries
            Segmented(modes, request.mode, actions::setMode, { it.label }, Modifier.fillMaxWidth())

            CanvasPanel(
                state = state,
                selected = selectedResult,
                onTap = {
                    when {
                        request.mode == DiffusionMode.TXT2IMG -> Unit
                        state.initImage == null -> pick()
                        request.mode == DiffusionMode.INPAINT -> showMask = true
                    }
                },
            )

            if (state.results.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(state.results, key = { _, r -> r.galleryId }) { i, r ->
                        Image(
                            r.bitmap,
                            contentDescription = "Result ${i + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .border(
                                    if (i == selectedResult) 2.dp else 1.dp,
                                    if (i == selectedResult) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    MaterialTheme.shapes.extraSmall,
                                )
                                .clickable { selectedResult = i },
                        )
                    }
                }
            }
            state.results.getOrNull(selectedResult)?.let { result ->
                ResultActions(
                    result,
                    onReuseSeed = { actions.setSeed(result.seed) },
                    onUseAsInit = { actions.useAsInit(result.galleryId) },
                    onOpenGallery = onOpenGallery,
                )
            }

            // Prompt
            val tokens = state.tokenEstimate
            Panel(
                title = "Prompt",
                accent = AutobotColors.Acid,
                trailing = {
                    Text(
                        "~$tokens/75",
                        style = AutobotTheme.styles.readout,
                        color = if (tokens > 75) AutobotColors.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            ) {
                ConsoleTextField(
                    value = request.prompt,
                    onValueChange = actions::setPrompt,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "subject, medium, style, lighting, lens…",
                    minLines = 3,
                    maxLines = 10,
                    mono = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showNegative = !showNegative }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Text(if (showNegative) "− NEGATIVE" else "+ NEGATIVE", style = MaterialTheme.typography.labelMedium)
                    }
                    if (!showNegative && request.negativePrompt.isNotBlank()) {
                        Text(
                            request.negativePrompt,
                            style = AutobotTheme.styles.codeBlock,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showNegative) {
                    ConsoleTextField(
                        value = request.negativePrompt,
                        onValueChange = actions::setNegative,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = "lowres, blurry, watermark…",
                        minLines = 2,
                        maxLines = 6,
                        mono = true,
                    )
                }
            }

            // Init image controls
            if (request.mode != DiffusionMode.TXT2IMG) {
                Panel(title = if (request.mode == DiffusionMode.INPAINT) "Inpaint" else "Img2img", accent = AutobotColors.Uv) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { pick() }, shape = MaterialTheme.shapes.small) {
                            Icon(AutobotIcons.Image, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.initImage == null) "LOAD IMAGE" else "REPLACE")
                        }
                        if (state.initImage != null) {
                            TextButton(onClick = actions::clearInitImage) { Text("CLEAR") }
                        }
                        if (request.mode == DiffusionMode.INPAINT && state.initImage != null) {
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = { showMask = true }, shape = MaterialTheme.shapes.small) {
                                Icon(AutobotIcons.Brush, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("MASK")
                            }
                        }
                    }
                    ValueSlider("Denoise", request.denoiseStrength, actions::setDenoise, 0.05f..1f)
                    if (request.mode == DiffusionMode.INPAINT) {
                        ValueSlider("Mask blur", request.maskBlur.toFloat(), { actions.setMaskBlur(it.toInt()) }, 0f..64f, format = { "${it.toInt()} px" })
                        if (caps?.maskEncoding == dev.wckdboy.autobot.core.diffusion.MaskEncoding.OPAQUE_BLACK_WHITE) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    MicroLabel("Only masked area")
                                    Text("Repaint at full res around the mask", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = request.inpaintOnlyMasked, onCheckedChange = actions::setOnlyMasked)
                            }
                        }
                    }
                }
            }

            // LoRA
            Panel(
                title = "LoRA",
                accent = AutobotColors.Uv,
                trailing = {
                    if (caps?.runtimeLora == true) {
                        Tag("+ add", accent = MaterialTheme.colorScheme.primary, onClick = { showLoras = true })
                    }
                },
            ) {
                if (caps?.runtimeLora == false) {
                    Text(
                        "This backend runs a fixed model; merge LoRAs into it before conversion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (request.loras.isEmpty()) {
                    Text("None. Stack adapters and tune each weight.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                request.loras.forEach { lora ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ValueSlider(lora.name, lora.weight, { actions.setLoraWeight(lora.name, it) }, -1f..2f, Modifier.weight(1f))
                        IconButton(onClick = { actions.removeLora(lora.name) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove ${lora.name}", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // Sampling
            Panel(title = "Sampler", accent = AutobotColors.Cyan) {
                if (caps?.modelSwitching == true && state.catalog.models.isNotEmpty()) {
                    SelectField(
                        "Checkpoint",
                        request.model ?: state.catalog.currentModel.orEmpty(),
                        state.catalog.models,
                        { actions.setModel(it) },
                        Modifier.fillMaxWidth(),
                    )
                }
                val sseBackend = caps?.runtimeLora == false
                val samplers = if (sseBackend) LocalSseEngine.SCHEDULERS else state.catalog.samplers.ifEmpty { DEFAULT_SAMPLERS }
                val samplerValue = if (sseBackend) LocalSseEngine.schedulerId(request.sampler, request.scheduler) else request.sampler
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectField(
                        if (sseBackend) "Scheduler" else "Sampler",
                        samplerValue,
                        samplers,
                        { actions.setSampler(it, if (sseBackend) null else request.scheduler) },
                        Modifier.weight(1f),
                    )
                    if (!sseBackend && state.catalog.schedulers.isNotEmpty()) {
                        SelectField("Schedule", request.scheduler ?: "Automatic", state.catalog.schedulers, { actions.setScheduler(it) }, Modifier.weight(1f))
                    }
                }
                ValueSlider(
                    "Steps",
                    request.steps.toFloat(),
                    { actions.setSteps(it.toInt()) },
                    1f..(caps?.maxSteps ?: 150).toFloat(),
                    format = { it.toInt().toString() },
                )
                ValueSlider("CFG", request.cfgScale, actions::setCfg, 1f..20f, format = { String.format(Locale.ROOT, "%.1f", it) })
                if (request.cfgScale <= 1f) MicroLabel("cfg 1 skips the unconditional pass — use with LCM / turbo models", color = AutobotColors.Amber)
            }

            // Size, seed, batch
            Panel(title = "Canvas", accent = AutobotColors.Cyan, trailing = { Readout("", "${request.width}×${request.height}", valueColor = MaterialTheme.colorScheme.primary) }) {
                val fixed = caps?.fixedSizes.orEmpty()
                if (fixed.isNotEmpty()) {
                    SelectField("Size", "${request.width}×${request.height}", fixed, { actions.setSize(it.first, it.second) }, optionLabel = { "${it.first}×${it.second}" })
                } else {
                    var base by rememberSaveable { mutableIntStateOf(512) }
                    Segmented(BASES, base, { base = it; actions.setSize(scaled(it, request.width, request.height).first, scaled(it, request.width, request.height).second) }, { "$it" }, Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ASPECTS.forEach { (label, ratio) ->
                            val (w, h) = aspectSize(base, ratio)
                            val active = w == request.width && h == request.height
                            Tag(label, accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, filled = active, onClick = { actions.setSize(w, h) })
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MicroLabel("Seed")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (request.seed < 0) "random" else request.seed.toString(),
                                style = AutobotTheme.styles.readout,
                                color = if (request.seed < 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.widthIn(min = 92.dp),
                            )
                            IconButton(onClick = actions::randomizeSeed) { Icon(AutobotIcons.Dice, "New fixed seed", Modifier.size(20.dp)) }
                            if (request.seed >= 0) TextButton(onClick = actions::setRandomSeed) { Text("RANDOM") }
                        }
                    }
                    Stepper("Batch", request.batchCount, actions::setBatch, 1..ImagineViewModel.MAX_BATCH)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

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
    if (showLoras) {
        LoraSheet(
            available = state.catalog.loras,
            selected = request.loras.map { it.name }.toSet(),
            onPick = { actions.addLora(it); showLoras = false },
            onDismiss = { showLoras = false },
        )
    }
}

private fun aspectSize(base: Int, ratio: Pair<Int, Int>): Pair<Int, Int> {
    val (rw, rh) = ratio
    val area = base.toDouble() * base
    val w = kotlin.math.sqrt(area * rw / rh)
    val h = area / w
    return dev.wckdboy.autobot.core.diffusion.PromptSyntax.snap(w.toInt()) to dev.wckdboy.autobot.core.diffusion.PromptSyntax.snap(h.toInt())
}

private fun scaled(base: Int, width: Int, height: Int): Pair<Int, Int> {
    val gcd = generateSequence(width to height) { (a, b) -> if (b == 0) null else b to a % b }.last().first.coerceAtLeast(1)
    return aspectSize(base, width / gcd to height / gcd)
}

@Composable
private fun ImagineTopBar(
    state: ImagineUiState,
    onSelectBackend: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenBackends: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            Modifier
                .fillMaxWidth()
                .hairlineEdge()
                .statusBarsPadding()
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("IMAGINE", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            val dot = when (state.catalogStatus) {
                CatalogStatus.READY -> AutobotColors.Acid
                CatalogStatus.ERROR -> AutobotColors.Signal
                CatalogStatus.LOADING -> AutobotColors.Amber
                CatalogStatus.IDLE -> MaterialTheme.colorScheme.outline
            }
            Row(
                Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Reconnect", onClick = onRefresh),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusDot(dot, live = state.catalogStatus == CatalogStatus.LOADING)
                if (state.backends.size > 1) {
                    SelectField("", state.backend?.name.orEmpty(), state.backends, { onSelectBackend(it.id) }, optionLabel = { it.name })
                } else {
                    MicroLabel(state.backend?.name ?: "no backend")
                }
            }
            IconButton(onClick = onOpenBackends) { Icon(AutobotIcons.Chip, contentDescription = "Image backends") }
            IconButton(onClick = onOpenGallery) { Icon(AutobotIcons.Image, contentDescription = "Gallery") }
        }
    }
}

@Composable
private fun CanvasPanel(state: ImagineUiState, selected: Int, onTap: () -> Unit) {
    val request = state.request
    val ratio = (request.width.toFloat() / request.height).coerceIn(0.4f, 2.5f)
    val running = state.running
    val result = state.results.getOrNull(selected)
    val showInit = request.mode != DiffusionMode.TXT2IMG && state.initImage != null && running == null && (state.preferInit || result == null)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val maxH = maxWidth * 1.25f
        Box(
            Modifier
                .heightIn(max = maxH)
                .aspectRatio(ratio)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            val image: ImageBitmap? = running?.preview ?: if (showInit) state.initImage else result?.bitmap
            if (image != null) {
                Image(image, contentDescription = "Canvas", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                if (showInit && request.mode == DiffusionMode.INPAINT) MaskOverlay(state.mask, Modifier.fillMaxSize())
            } else {
                GridBackdrop()
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${request.width} × ${request.height}", style = AutobotTheme.styles.readout, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MicroLabel(
                        when {
                            request.mode != DiffusionMode.TXT2IMG && state.initImage == null -> "tap to load an init image"
                            else -> "${request.mode.label} · ${request.steps} steps · cfg ${request.cfgScale}"
                        },
                    )
                }
            }
            if (running != null) {
                RunningOverlay(running, Modifier.fillMaxSize())
            } else if (showInit && request.mode == DiffusionMode.INPAINT) {
                Tag(if (state.mask.isEmpty) "tap to paint mask" else "tap to edit mask", Modifier.align(Alignment.BottomCenter).padding(8.dp), accent = AutobotColors.Acid, filled = true)
            } else if (result != null && !showInit) {
                Row(Modifier.align(Alignment.BottomStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag("seed ${result.seed}", accent = Color.White, filled = true)
                    Tag(String.format(Locale.ROOT, "%.1fs", result.durationMs / 1000f), accent = Color.White, filled = true)
                }
            }
        }
    }
}

@Composable
private fun RunningOverlay(running: RunningUi, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    Box(modifier) {
        Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag(if (running.totalSteps > 0) "step ${running.step}/${running.totalSteps}" else "warming up", accent = AutobotColors.Acid, filled = true)
            if (running.batch > 1) Tag("img ${running.image + 1}/${running.batch}", accent = Color.White, filled = true)
            if (running.byAgent) Tag("agent", accent = AutobotColors.Uv, filled = true)
        }
        Tag(
            String.format(Locale.ROOT, "%.1fs", (now - running.startedAt) / 1000f),
            Modifier.align(Alignment.TopEnd).padding(8.dp),
            accent = Color.White,
            filled = true,
        )
        val fraction = if (running.totalSteps > 0) running.step.toFloat() / running.totalSteps else 0f
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.Black.copy(alpha = 0.4f))) {
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(AutobotColors.Acid))
        }
    }
}

@Composable
private fun GridBackdrop() {
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    Canvas(Modifier.fillMaxSize()) {
        val step = 24.dp.toPx()
        var x = step
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
        }
        var y = step
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
    }
}

@Composable
private fun ResultActions(result: ResultUi, onReuseSeed: () -> Unit, onUseAsInit: () -> Unit, onOpenGallery: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Tag("↺ seed", accent = MaterialTheme.colorScheme.primary, onClick = onReuseSeed)
        Tag("→ img2img", accent = AutobotColors.Uv, onClick = onUseAsInit)
        Spacer(Modifier.weight(1f))
        Tag("gallery", accent = MaterialTheme.colorScheme.onSurfaceVariant, onClick = onOpenGallery)
    }
}

@Composable
private fun GenerateBar(state: ImagineUiState, onGenerate: () -> Unit, onCancel: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().hairlineEdge(top = true, bottom = false)) {
            val running = state.running
            if (running != null) {
                LinearProgressIndicator(
                    progress = { if (running.totalSteps > 0) running.step.toFloat() / running.totalSteps else 0f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                    drawStopIndicator = {},
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MicroLabel("${state.request.mode.label} · ${state.request.width}×${state.request.height} · ×${state.request.batchCount}")
                    MicroLabel(
                        listOfNotNull(state.request.sampler, state.request.scheduler, "${state.request.steps} st").joinToString(" · "),
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (running != null) {
                    OutlinedButton(
                        onClick = onCancel,
                        enabled = !running.byAgent,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        modifier = Modifier.height(48.dp),
                    ) { Text("CANCEL", color = MaterialTheme.colorScheme.error) }
                } else {
                    Button(
                        onClick = onGenerate,
                        enabled = state.canGenerate,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(AutobotIcons.Spark, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("GENERATE")
                    }
                }
            }
        }
    }
}

@Composable
private fun NoBackendCard(onOpenBackends: () -> Unit) {
    Panel(title = "No image backend", accent = AutobotColors.Amber) {
        Text(
            "Point Autobot at a diffusion server: the on-device engine sidecar or Local Dream host (SSE, :8081), or an " +
                "A1111/Forge API (:7860, via Termux on loopback or HTTPS on your network).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenBackends, shape = MaterialTheme.shapes.small) { Text("ADD BACKEND") }
    }
}

@Composable
private fun ErrorStrip(message: String, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.small) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoraSheet(available: List<String>, selected: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MicroLabel("// LoRA library · ${available.size}", color = MaterialTheme.colorScheme.primary)
            ConsoleTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "filter", singleLine = true, mono = true)
            Hairline()
            val filtered = available.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            if (filtered.isEmpty()) MicroLabel(if (available.isEmpty()) "backend reported no loras" else "no match")
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                filtered.forEach { name ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = name !in selected) { onPick(name) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AutobotIcons.Layers, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(10.dp))
                        Text(name, style = AutobotTheme.styles.code, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (name in selected) MicroLabel("added", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

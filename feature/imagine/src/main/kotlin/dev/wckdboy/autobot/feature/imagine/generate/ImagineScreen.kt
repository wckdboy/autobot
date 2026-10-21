package dev.wckdboy.autobot.feature.imagine.generate

import dev.wckdboy.autobot.core.designsystem.component.NoticeTone
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import dev.wckdboy.autobot.core.designsystem.component.AppBar
import dev.wckdboy.autobot.core.designsystem.component.Caption
import dev.wckdboy.autobot.core.designsystem.component.Centered
import dev.wckdboy.autobot.core.designsystem.component.Choices
import dev.wckdboy.autobot.core.designsystem.component.DropdownField
import dev.wckdboy.autobot.core.designsystem.component.EmptyPicture
import dev.wckdboy.autobot.core.designsystem.component.Field
import dev.wckdboy.autobot.core.designsystem.component.InlineLoading
import dev.wckdboy.autobot.core.designsystem.component.JobProgress
import dev.wckdboy.autobot.core.designsystem.component.JobState
import dev.wckdboy.autobot.core.designsystem.component.NoticeCard
import dev.wckdboy.autobot.core.designsystem.component.ReadingMaxWidth
import dev.wckdboy.autobot.core.designsystem.component.RouteChip
import dev.wckdboy.autobot.core.designsystem.component.SearchField
import dev.wckdboy.autobot.core.designsystem.component.SheetLabel
import dev.wckdboy.autobot.core.designsystem.component.SliderField
import dev.wckdboy.autobot.core.designsystem.component.StepperField
import dev.wckdboy.autobot.core.designsystem.component.SwitchItem
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.component.clearListItem
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.core.designsystem.component.readingWidth
import dev.wckdboy.autobot.core.designsystem.component.DevelopingPreview
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.linesFor
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.PrintReveal
import dev.wckdboy.autobot.core.designsystem.component.PromptKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.component.rememberLocalText
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.motion.inkStagger
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.diffusion.Aspect
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.MaskEncoding
import dev.wckdboy.autobot.core.diffusion.PromptSyntax
import dev.wckdboy.autobot.core.diffusion.engine.LocalSseEngine
import dev.wckdboy.autobot.core.diffusion.engine.OnDeviceEngine
import dev.wckdboy.autobot.feature.imagine.mask.MaskEditorDialog
import dev.wckdboy.autobot.feature.imagine.viewer.ImageViewer
import dev.wckdboy.autobot.feature.imagine.viewer.ViewerAction
import androidx.compose.ui.platform.LocalContext
import dev.wckdboy.autobot.feature.imagine.mask.MaskOverlay
import dev.wckdboy.autobot.shared.panic.IncognitoImages
import dev.wckdboy.autobot.shared.panic.PanicCopy
import dev.wckdboy.autobot.shared.studio.PromptLibrary
import dev.wckdboy.autobot.shared.studio.PromptSnippet
import java.util.Locale
import kotlinx.coroutines.delay

/** One-shot instruction from the gallery or a viewer elsewhere (reuse settings, img2img, inpaint, vary…). */
data class GalleryHandoff(
    val galleryId: String,
    val asInit: Boolean = false,
    val action: ViewerAction = if (asInit) ViewerAction.IMG2IMG else ViewerAction.SAME_SEED,
)

@Composable
fun ImagineRoute(
    onBack: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenBackends: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenStudio: () -> Unit = {},
    handoff: GalleryHandoff? = null,
    modelId: String? = null,
    prompt: String? = null,
    viewModel: ImagineViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Once per screen: coming back from the gallery must not overwrite the user's edits.
    var promptApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(prompt) {
        if (prompt != null && !promptApplied) viewModel.setPrompt(prompt)
        promptApplied = true
    }
    // Once per screen, like the prompt: coming back from the gallery must not apply it again.
    var handoffApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(handoff) {
        if (handoff != null && !handoffApplied) viewModel.handoff(handoff.galleryId, handoff.action)
        handoffApplied = true
    }
    LaunchedEffect(modelId) { modelId?.let(viewModel::useModel) }
    ImagineScreen(state, viewModel, onBack, onOpenGallery, onOpenBackends, onOpenModels, onOpenStudio)
}

private val IMAGE_NEGATIVES = setOf("neg-general", "neg-anatomy", "neg-photo")
private val DEFAULT_SAMPLERS = listOf("DPM++ 2M", "DPM++ SDE", "Euler a", "Euler", "LCM", "DDIM", "UniPC")

/** The image room: a big prompt, model chips, the settings at a glance, 2×2 proofs. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ImagineScreen(
    state: ImagineUiState,
    actions: ImagineViewModel,
    onBack: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenBackends: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenStudio: () -> Unit = {},
) {
    var showTune by rememberSaveable { mutableStateOf(false) }
    var showMask by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.openMask) {
        if (state.openMask) {
            showMask = true
            actions.maskOpened()
        }
    }
    val context = LocalContext.current
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val request = state.request
    // The engine the selected model actually runs on (the shown chip, also before one is tapped).
    val route = imageRoute(state.backend, state.selectedChip?.takeIf { it.onDevice }?.accelerator)
    val large = isLargeText()

    Scaffold(
        topBar = {
            AppBar("Image", onBack = onBack) {
                // A large font leaves the title no room beside the route: it moves into the page.
                if (!large) route?.let { RouteChip(it) }
                IconButton(onClick = onOpenGallery) { Icon(AutobotIcons.Image, contentDescription = "Gallery") }
                // The full image room (queue, grids, workflows) is in Studio mode.
                TextButton(onClick = onOpenStudio) { Text("Studio") }
            }
        },
        bottomBar = { StudioBar(state, actions, onShowTune = { showTune = true }) },
    ) { padding ->
        Centered(Modifier.padding(padding), maxWidth = ReadingMaxWidth) { width ->
            Column(
                width
                    // The bottom bar rides above the keyboard (below), so the scaffold padding already clears it.
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (large) route?.let { RouteChip(it) }
                state.error?.let { ErrorLine(it, actions::dismissError) }
                if (state.loaded && state.backends.isEmpty()) {
                    // One short sentence, one action; a PC is the secondary path (as on iOS).
                    Text("No image model yet.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onOpenModels, modifier = Modifier.fillMaxWidth()) { Text("Get a model") }
                    TextButton(onClick = onOpenBackends, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use a PC instead") }
                }

                // The keyboard waits for a tap on the prompt: arriving here shows the whole room, not a third of it.
                PromptField(request.prompt, actions::setPrompt, state.tokenEstimate, state.wildcards)
                // Always there, disabled while it cannot apply: the row coming and going with the first
                // letter typed and with each run moved everything under it (the proofs jumped).
                StyleRow(actions::applyStyle, enabled = request.prompt.isNotBlank() && state.running == null)

                // Model chips: on-device models and each PC backend's checkpoints.
                if (state.chips.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val current = state.selectedChip
                        state.chips.forEach { chip ->
                            FilterChip(
                                selected = chip == current,
                                onClick = { actions.selectChip(chip) },
                                label = { Text(chip.label) },
                                modifier = Modifier.semantics { role = Role.RadioButton },
                            )
                        }
                        TextButton(onClick = onOpenModels) { Text("+ Model") }
                    }
                }

                // Wraps at a large font size: four numbers ("1024×576", a six-digit seed) overran the
                // row and the last ones were squeezed to nothing.
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Setting("Steps", request.steps.toString(), onClick = { showTune = true })
                    Setting("Seed", if (request.seed < 0) "rnd" else request.seed.toString().takeLast(6), onClick = { showTune = true })
                    Setting("Size", state.outputSize.let { (w, h) -> sizeLabel(w, h) }, onClick = { showTune = true })
                    Setting("Batch", request.batchCount.toString(), onClick = { showTune = true })
                }

                if (request.mode != DiffusionMode.TXT2IMG) {
                    InitStrip(state, actions, onEditMask = { showMask = true })
                }

                // A tap opens the result full size (zoom, swipe through the batch, img2img and more).
                ProofGrid(state, selected, onSelect = { i ->
                    selected = i
                    state.results.getOrNull(i)?.let { actions.openViewer(it.galleryId) }
                })

                state.results.getOrNull(selected.coerceAtLeast(0))?.takeIf { state.running == null }?.let { result ->
                    Text(
                        "seed ${result.seed} · ${result.width}×${result.height} · ${String.format(Locale.ROOT, "%.1f", result.durationMs / 1000f)} s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { actions.openViewer(result.galleryId) }, label = { Text("View") })
                        AssistChip(onClick = { actions.setSeed(result.seed) }, label = { Text("↺ Seed") })
                        AssistChip(onClick = { actions.handoff(result.galleryId, ViewerAction.IMG2IMG) }, label = { Text("→ img2img") })
                    }
                    if (IncognitoImages.isUnsaved(result.galleryId)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (state.saved.containsKey(result.galleryId)) {
                                Caption(PanicCopy.SAVED, Modifier.weight(1f))
                            } else {
                                Caption(PanicCopy.NOT_SAVED, Modifier.weight(1f))
                                AssistChip(onClick = { actions.saveResult(result.galleryId) }, label = { Text(labelCase(PanicCopy.SAVE)) })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showTune) TuneSheet(state, actions, onDismiss = { showTune = false }, onOpenBackends = onOpenBackends)
    state.viewing?.let { id ->
        ImageViewer(
            items = state.resultItems,
            initialId = id,
            loadImage = actions.images::image,
            onAction = { action, item -> actions.viewerAction(action, item) { intent -> context.startActivity(intent) } },
            onDismiss = actions::closeViewer,
            actionsFor = { ViewerAction.forImage(it.id, state.canUpscale) },
            params = actions.images::params,
            busy = state.viewerBusy,
        )
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
}

private fun sizeLabel(w: Int, h: Int) = if (w == h) "$w²" else "${w}×$h"

/** A setting at a glance (`Steps` over `30`) on a tonal surface; a tap opens the tune sheet. */
@Composable
private fun Setting(label: String, value: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.semantics { contentDescription = "$label $value" },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(labelCase(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        }
    }
}

/** The prompt, large, in a borderless field: the room is about what it says. */
@Composable
private fun PromptField(value: String, onChange: (String) -> Unit, tokens: Int, wildcards: String?) {
    // The text lives here: typing never waits on the studio's state round trip.
    val prompt = rememberLocalText(value, onChange)
    Column {
        NoPersonalizedLearning {
            BasicTextField(
                value = prompt.text,
                onValueChange = prompt::edit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(top = 8.dp),
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = PromptKeyboardOptions,
                decorationBox = { inner ->
                    Box {
                        if (prompt.text.isEmpty()) {
                            Text("Describe an image…", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    }
                },
            )
        }
        if (wildcards != null) {
            Caption(wildcards)
        } else {
            Caption("~$tokens / 75 tokens", color = if (tokens > 75) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Built-in styles: one tap wraps the prompt (and adds the style's negatives). Disabled unless [enabled]. */
@Composable
private fun StyleRow(onApply: (PromptSnippet) -> Unit, enabled: Boolean) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Style", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PromptLibrary.builtInStyles.forEach { style ->
            SuggestionChip(
                onClick = { onApply(style) },
                enabled = enabled,
                label = { Text(style.name) },
                modifier = Modifier.semantics { contentDescription = "Apply the ${style.name} style" },
            )
        }
    }
}

/** 2×2 proofs: the engine's preview (when it sends one) while sampling, the image when done. */
@Composable
private fun ProofGrid(state: ImagineUiState, selected: Int, onSelect: (Int) -> Unit) {
    val running = state.running
    val count = if (running != null) running.batch.coerceAtLeast(1) else state.results.size
    val slots = count.coerceAtLeast(1).coerceAtMost(4)
    // The shape of what is shown: the results as they came out, else what the next run makes.
    val shown = state.results.firstOrNull()?.takeIf { running == null }?.let { it.width to it.height } ?: state.outputSize
    val ratio = (shown.first.toFloat() / shown.second.coerceAtLeast(1)).coerceIn(0.5f, 2f)
    val cols = if (slots == 1) 1 else 2
    val shape = MaterialTheme.shapes.medium
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in 0 until (slots + cols - 1) / cols) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .then(if (i == selected && result != null) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                            .clickable(enabled = result != null, onClickLabel = "View full size") { onSelect(i) },
                    ) {
                        // Results that arrive while we watch fade in; ones already there just show.
                        val sampled = remember { booleanArrayOf(false) }
                        if (running != null && result == null) sampled[0] = true
                        val live = running != null && i == running.image
                        when {
                            result != null -> PrintReveal(
                                result.bitmap,
                                Modifier.fillMaxSize(),
                                animate = sampled[0],
                                contentDescription = "Result ${i + 1}",
                                key = result.galleryId,
                            )
                            running != null -> DevelopingPreview(if (live) running.preview else null, Modifier.fillMaxSize())
                            else -> EmptyPicture(Modifier.fillMaxSize(), hint = "Describe an image, then Generate.".takeIf { i == 0 })
                        }
                        if (running != null && i == running.image) {
                            if (running.totalSteps > 0 && running.phase == null) {
                                TonalLabel(
                                    "step ${running.step}/${running.totalSteps}",
                                    Modifier.align(Alignment.BottomStart).padding(8.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                            } else {
                                // The model is still loading: no label, just the loading indicator.
                                InlineLoading(Modifier.align(Alignment.BottomStart).padding(8.dp), contentDescription = running.phase ?: "Loading the model")
                            }
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
        // The bar shows whole seconds: wake once a second, just after each one ticks over.
        while (running != null) {
            now = System.currentTimeMillis()
            delay(1_000 - (now - running.startedAt).mod(1_000L) + 5)
        }
    }
    // Above the keyboard while typing a prompt: Generate stays one tap away.
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.readingWidth().fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            running?.phase != null -> "${running.phase} · ${(now - running.startedAt) / 1000} s"
                            running != null && running.byAgent -> "Agent is generating · ${(now - running.startedAt) / 1000} s"
                            running != null -> "Running ${if (state.backend?.kind == BackendKind.ON_DEVICE) "on device" else "on ${state.backend?.name}"} · ${(now - running.startedAt) / 1000} s"
                            state.results.isNotEmpty() -> "Done · ${state.results.size} image${if (state.results.size == 1) "" else "s"}"
                            else -> "${if (state.incognito) "${PanicCopy.INCOGNITO_IMAGES_TITLE} · " else ""}${state.request.mode.label} · ${state.request.sampler.ifBlank { "default sampler" }} · CFG ${state.request.cfgScale}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = linesFor(1, 3),
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (running != null) {
                        Text("${state.results.size}/${running.batch}", style = MaterialTheme.typography.labelLarge)
                    } else {
                        TextButton(onClick = onShowTune) { Text("Tune") }
                    }
                }
                JobProgress(
                    // Indeterminate while the model loads (no steps yet).
                    progress = if (running == null) 0f else if (running.totalSteps > 0 && running.phase == null) (running.image + running.step.toFloat() / running.totalSteps) / running.batch.coerceAtLeast(1) else null,
                    modifier = Modifier.fillMaxWidth(),
                    state = if (running != null) JobState.Running else JobState.Idle,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        running != null -> OutlinedButton(onClick = actions::cancel, modifier = Modifier.fillMaxWidth(), enabled = !running.byAgent) { Text("Cancel") }
                        state.results.isNotEmpty() -> {
                            OutlinedButton(onClick = actions::variations, modifier = Modifier.weight(1f), enabled = state.canGenerate) { Text("Variations", maxLines = linesFor(1, 2)) }
                            Button(onClick = actions::runAgain, modifier = Modifier.weight(1f), enabled = state.canGenerate) { Text("Run again", maxLines = linesFor(1, 2)) }
                        }
                        else -> Button(onClick = actions::generate, modifier = Modifier.fillMaxWidth(), enabled = state.canGenerate) { Text("Generate") }
                    }
                }
            }
        }
    }
}

@Composable
private fun InitStrip(state: ImagineUiState, actions: ImagineViewModel, onEditMask: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(actions::setInitImage) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(72.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainer).clickable {
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
            Text(
                if (state.request.mode == DiffusionMode.INPAINT) "Inpaint · ${if (state.mask.isEmpty) "no mask" else counted(state.mask.strokes.size, "stroke")}" else "Img2img",
                style = MaterialTheme.typography.titleSmall,
            )
            SliderField("Denoise", state.request.denoiseStrength, actions::setDenoise, 0.05f..1f)
        }
        if (state.request.mode == DiffusionMode.INPAINT && state.initImage != null) {
            IconButton(onClick = onEditMask) { Icon(AutobotIcons.Brush, contentDescription = "Edit mask") }
        }
    }
}

@Composable
private fun ErrorLine(message: String, onDismiss: () -> Unit) {
    NoticeCard(message, tone = NoticeTone.ERROR, action = "Dismiss", onAction = onDismiss)
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(Modifier.inkStagger(0), verticalAlignment = Alignment.CenterVertically) {
                SheetLabel("tune", Modifier.weight(1f))
                AssistChip(onClick = { onDismiss(); onOpenBackends() }, label = { Text("Backends") })
            }
            SwitchItem(
                PanicCopy.INCOGNITO_IMAGES_TITLE,
                checked = state.incognito,
                onChange = actions::setIncognito,
                state = PanicCopy.INCOGNITO_IMAGES_INFO,
                modifier = Modifier.clip(MaterialTheme.shapes.medium),
            )
            val traits = state.traits
            val modes = DiffusionMode.entries.filter { it in traits.modes }
            Choices(modes, request.mode, actions::setMode, Modifier.fillMaxWidth().inkStagger(1)) { it.label }

            Field(request.negativePrompt, actions::setNegative, Modifier.fillMaxWidth().inkStagger(2), label = "Negative", minLines = 2, maxLines = 5, mono = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Presets", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Video and audio presets belong to the Studio's other rooms.
                PromptLibrary.negativePresets.filter { it.id in IMAGE_NEGATIVES }.forEach { preset ->
                    SuggestionChip(onClick = { actions.addNegative(preset) }, label = { Text(preset.name) })
                }
            }

            val (samplers, value) = when (state.backend?.kind) {
                BackendKind.ON_DEVICE -> OnDeviceEngine.SAMPLERS to OnDeviceEngine.samplerId(request.sampler).ifBlank { "model default" }
                BackendKind.LOCAL_SSE -> LocalSseEngine.SCHEDULERS to LocalSseEngine.schedulerId(request.sampler, request.scheduler)
                else -> state.catalog.samplers.ifEmpty { DEFAULT_SAMPLERS } to request.sampler
            }
            val schedulers = when (state.backend?.kind) {
                BackendKind.ON_DEVICE -> OnDeviceEngine.SCHEDULERS
                BackendKind.SD_API -> state.catalog.schedulers
                else -> emptyList()
            }
            // Side by side on a phone; one under the other at a large font, where half a row cuts the names.
            FlowRow(Modifier.inkStagger(3).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = if (isLargeText()) 1 else 2) {
                DropdownField("Sampler", value, samplers, { actions.setSampler(it, if (state.backend?.kind == BackendKind.SD_API) request.scheduler else null) }, Modifier.weight(1f))
                if (schedulers.isNotEmpty()) {
                    DropdownField("Schedule", request.scheduler ?: "default", schedulers, { actions.setScheduler(it) }, Modifier.weight(1f))
                }
            }
            SliderField("Steps", request.steps.toFloat(), { actions.setSteps(it.toInt()) }, 1f..(caps?.maxSteps ?: 150).toFloat(), Modifier.inkStagger(4), format = { it.toInt().toString() })
            SliderField("CFG", request.cfgScale, actions::setCfg, 1f..20f, Modifier.inkStagger(5), format = { String.format(Locale.ROOT, "%.1f", it) })

            SizeControls(state, actions)

            FlowRow(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).widthIn(min = 160.dp)) {
                    Text("Seed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (request.seed < 0) "random" else request.seed.toString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                        IconButton(onClick = actions::randomizeSeed) { Icon(AutobotIcons.Dice, "Fixed random seed") }
                        if (request.seed >= 0) TextButton(onClick = actions::setRandomSeed) { Text("Random") }
                    }
                }
                StepperField("Batch", request.batchCount, actions::setBatch, 1..ImagineViewModel.MAX_BATCH)
            }

            if (request.mode == DiffusionMode.INPAINT && caps?.maskEncoding == MaskEncoding.OPAQUE_BLACK_WHITE && state.backend?.kind == BackendKind.SD_API) {
                SliderField("Mask blur", request.maskBlur.toFloat(), { actions.setMaskBlur(it.toInt()) }, 0f..64f, format = { "${it.toInt()} px" })
                SwitchItem("Only masked area", checked = request.inpaintOnlyMasked, onChange = actions::setOnlyMasked)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LoRA", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (traits.runtimeLora) AssistChip(onClick = { showLoras = true }, label = { Text("+ Add") })
            }
            if (!traits.runtimeLora) {
                Caption(if (request.loras.isEmpty()) "this model runs a fixed graph; merge loras before conversion" else "this model runs a fixed graph: the loras below are not applied")
            }
            request.loras.forEach { lora ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SliderField(lora.name, lora.weight, { actions.setLoraWeight(lora.name, it) }, -1f..2f, Modifier.weight(1f))
                    IconButton(onClick = { actions.removeLora(lora.name) }) { Icon(Icons.Filled.Close, contentDescription = "Remove ${lora.name}") }
                }
            }
            if (request.cfgScale <= 1f) Caption("cfg 1 skips the unconditional pass — right for turbo / lcm / distilled models")
        }
    }
    if (showLoras) {
        LoraSheet(state.catalog.loras, request.loras.map { it.name }.toSet(), onPick = { actions.addLora(it); showLoras = false }, onDismiss = { showLoras = false })
    }
}

/**
 * Pixel budget and shape. Every shape chip names the size it makes on this model, the budget
 * follows the current request, and a model compiled for one square says so (other shapes are cut
 * from it) instead of quietly rendering the square.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SizeControls(state: ImagineUiState, actions: ImagineViewModel) {
    val request = state.request
    val traits = state.traits
    val sizes = traits.sizes
    val (outW, outH) = state.outputSize
    Text(
        "Size · ${outW}×$outH" + if (outW != request.width || outH != request.height) " (asked ${request.width}×${request.height})" else "",
        style = MaterialTheme.typography.titleSmall,
    )
    if (sizes.bases.size > 1) {
        Choices(sizes.bases, sizes.baseOf(request.width, request.height), actions::setBase, Modifier.fillMaxWidth()) { "~$it²" }
    }
    val current = Aspect.of(outW, outH)
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Aspect.ALL.forEach { aspect ->
            val (w, h) = sizes.size(sizes.baseOf(request.width, request.height), aspect)
            FilterChip(
                selected = aspect == current,
                onClick = { actions.setAspect(aspect) },
                label = { Text("${aspect.label} · ${w}×$h") },
                modifier = Modifier.semantics {
                    contentDescription = "${aspect.label}, $w by $h pixels"
                    role = Role.RadioButton
                },
            )
        }
    }
    traits.sizeNote?.let { Caption(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoraSheet(available: List<String>, selected: Set<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetLabel("lora library · ${available.size}", Modifier.padding(horizontal = 16.dp).inkStagger(0))
            SearchField(query, { query = it }, "Filter", Modifier.fillMaxWidth().padding(horizontal = 16.dp).inkStagger(1))
            val filtered = available.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
            if (filtered.isEmpty()) {
                Text(
                    if (available.isEmpty()) "No LoRAs yet. Get some from Civitai in the Models tab." else "No match",
                    Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                filtered.forEach { name ->
                    ListItem(
                        headlineContent = { Text(name, style = AutobotTheme.styles.code, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(AutobotIcons.Layers, null) },
                        trailingContent = if (name in selected) ({ TonalLabel("added") }) else null,
                        colors = clearListItem(),
                        modifier = Modifier.clickable(enabled = name !in selected) { onPick(name) },
                    )
                }
            }
        }
    }
}

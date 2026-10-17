package dev.wckdboy.autobot.feature.models

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Notice
import dev.wckdboy.autobot.core.designsystem.component.NoticeTone
import dev.wckdboy.autobot.core.designsystem.component.PromptKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.RunRow
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.inkGround
import dev.wckdboy.autobot.core.designsystem.motion.inkDissolve
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkDissolve
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.models.CivitaiModel
import dev.wckdboy.autobot.core.models.Backend
import dev.wckdboy.autobot.core.models.EngineKind
import java.util.Locale
import dev.wckdboy.autobot.core.models.Fit
import dev.wckdboy.autobot.core.models.HfRepo
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelPlan
import dev.wckdboy.autobot.core.models.LocalDream
import dev.wckdboy.autobot.core.models.NpuOffer
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.models.ModelTask
import dev.wckdboy.autobot.core.models.formatBytes
import dev.wckdboy.autobot.core.models.launchOf

@Composable
fun ModelsRoute(
    onOpenAccounts: () -> Unit,
    onOpenCompute: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenImagine: (modelId: String) -> Unit,
    onOpenGallery: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenVoice: () -> Unit = {},
    viewModel: ModelsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { event ->
            when (event) {
                is ModelsEvent.OpenSession -> onOpenSession(event.conversationId)
                is ModelsEvent.OpenImagine -> onOpenImagine(event.modelId)
                ModelsEvent.OpenGallery -> onOpenGallery()
                ModelsEvent.OpenVoice -> onOpenVoice()
            }
        }
    }
    ModelsScreen(state, viewModel, onOpenAccounts, onOpenCompute, onOpenNetwork)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(state: ModelsUiState, actions: ModelsActions, onOpenAccounts: () -> Unit, onOpenCompute: () -> Unit, onOpenNetwork: () -> Unit = {}) {
    modelsCompositionProbe?.invoke("screen")
    val snackbar = remember { SnackbarHostState() }
    val dissolve = rememberInkDissolve()
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            actions.consumeMessage()
        }
    }
    // The download progress notification needs this on Android 13+; downloads work without it.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) actions.notificationsDenied()
    }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    fun askNotifications() {
        if (!askedNotifications && Build.VERSION.SDK_INT >= 33) {
            askedNotifications = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun install(plan: ModelPlan) {
        askNotifications()
        actions.install(plan)
    }

    Scaffold(
        topBar = {
            AutobotTopBar(title = "Models", actions = { TextButton(onClick = onOpenCompute) { Text("COMPUTE", style = MaterialTheme.typography.labelMedium) } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                state.device?.let { d ->
                    // Two short lines under the label instead of one that runs off the screen.
                    val (chip, specs) = deviceLines(d.socModel, d.socName, d.cores, d.supportsI8mm, d.totalRamBytes, d.freeStorageBytes)
                    SectionLabel(chip)
                    Spacer(Modifier.height(4.dp))
                    MicroLabel(specs.first)
                    MicroLabel(specs.second)
                }
                Spacer(Modifier.height(14.dp))
            }

            if (!state.downloads.isEmpty) {
                item(key = "downloads") { DownloadsPanel(state.downloads, actions) }
            }

            if (state.installed.isNotEmpty()) {
                item { SectionLabel("installed · ${state.installed.size}") }
                items(state.installed, key = { "i-${it.id}" }) { model ->
                    // Removing a model dissolves its row into halftone before it goes.
                    Column(Modifier.inkDissolve(dissolve, model.id)) {
                        InstalledRow(model, actions, onRemove = { dissolve.dissolve(model.id) { actions.remove(model.id) } })
                    }
                }
                item { Spacer(Modifier.height(18.dp)) }
            }

            item {
                Segmented(ModelsTab.entries, state.tab, actions::selectTab, { it.label }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }

            when (state.tab) {
                ModelsTab.RECOMMENDED -> {
                    val installed = state.installedIds()
                    val arch = state.device?.htpArch
                    listOf("image · npu / gpu" to actions.npuImages, "upscale · npu" to actions.npuUpscalers).forEach { (label, offers) ->
                        item(key = "h-$label") {
                            SectionLabel(label + (arch?.let { " · hexagon $it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(offers, key = { "n-${it.id}" }) { offer ->
                            NpuOfferRow(offer, arch, installed = installed.any { it.startsWith(offer.id + ":") }) {
                                askNotifications()
                                actions.installNpuOffer(offer)
                            }
                        }
                        item(key = "s-$label") { Spacer(Modifier.height(14.dp)) }
                    }
                    listOf(
                        "chat" to actions.recommended.filter { it.kind == ModelKind.CHAT },
                        "code" to actions.recommended.filter { it.kind == ModelKind.CODE },
                        "image · cpu" to actions.recommended.filter { it.kind == ModelKind.IMAGE },
                        "speech · dictation" to actions.recommended.filter { it.kind == ModelKind.SPEECH },
                        "voice · read aloud" to actions.recommended.filter { it.kind == ModelKind.VOICE },
                        "audio · studio" to actions.recommended.filter { it.kind == ModelKind.AUDIO },
                        "embedding · vault" to actions.recommended.filter { it.kind == ModelKind.EMBEDDING },
                    ).forEach { (label, plans) ->
                        item(key = "h-$label") {
                            SectionLabel(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(plans, key = { "c-${it.id}" }) { plan ->
                            val fit = state.device?.fit(plan.kind, plan.totalBytes, plan.engine)
                            CatalogRow(plan, fit, installed = plan.id in installed, onGet = { install(plan) })
                        }
                        item(key = "s-$label") { Spacer(Modifier.height(14.dp)) }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                ModelsTab.HUGGING_FACE -> {
                    item { HfSearch(state, actions, onOpenAccounts) }
                    if (state.hf.results.isEmpty() && !state.hf.loading) {
                        item(key = "hf-empty") {
                            SearchEmpty("Hugging Face", state.offline, state.hf.searched, state.hf.failure, state.hf.query, onOpenNetwork, actions::searchHf)
                        }
                        item(key = "hf-paper") { EmptyResults() }
                    }
                    items(state.hf.results, key = { "hf-${it.id}" }) { m ->
                        RunRow(
                            title = m.id.substringAfter('/'),
                            subtitle = "${m.id.substringBefore('/')} · ${m.downloads} dl${m.license?.let { " · $it" } ?: ""}",
                            onClick = { actions.openRepo(m.id) },
                        ) { if (m.gated) Tag("gated", accent = AutobotColors.Amber) }
                    }
                }
                ModelsTab.CIVITAI -> {
                    item { CivitaiSearch(state, actions, onOpenAccounts) }
                    if (state.civitai.results.isEmpty() && !state.civitai.loading) {
                        item(key = "cv-empty") {
                            SearchEmpty("Civitai", state.offline, state.civitai.searched, state.civitai.failure, state.civitai.query, onOpenNetwork) { actions.searchCivitai(reset = true) }
                        }
                        item(key = "cv-paper") { EmptyResults() }
                    }
                    items(state.civitai.results, key = { "cv-${it.id}" }) { m -> CivitaiRow(m, actions, state.installedIds()) { actions.installCivitai(m) } }
                    // A failed "more" says so under the results it could not extend.
                    if (state.civitai.results.isNotEmpty() && state.civitai.failure != null) {
                        item(key = "cv-more-failed") {
                            SearchEmpty("Civitai", state.offline, true, state.civitai.failure, state.civitai.query, onOpenNetwork) { actions.searchCivitai(reset = false) }
                        }
                    }
                    if (state.civitai.nextCursor != null) {
                        item { TextButton(onClick = { actions.searchCivitai(reset = false) }) { Text("MORE") } }
                    }
                }
            }
        }
    }

    state.hf.openRepo?.let { repo ->
        ModalBottomSheet(onDismissRequest = actions::closeRepo, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            RepoSheet(repo, state, actions)
        }
    }
}

/**
 * No search results yet: a patch of living paper under the notice (as on iOS). Not the whole list:
 * the ground ran behind the device header, the installed rows and the search field's text.
 */
@Composable
private fun EmptyResults() {
    Spacer(Modifier.fillMaxWidth().height(180.dp).inkGround())
}

/**
 * What an empty hub tab means: offline mode or the kill switch (one tap to the network setting),
 * a failed request (retry), no matches, or nothing searched yet.
 */
@Composable
private fun SearchEmpty(
    hub: String,
    offline: Boolean,
    searched: Boolean,
    failure: SearchFailure?,
    query: String,
    onOpenNetwork: () -> Unit,
    onRetry: () -> Unit,
) {
    val modifier = Modifier.padding(vertical = 12.dp)
    when {
        offline -> Notice(
            "Offline mode is on, so the kill switch blocks $hub. Choose a network mode to search and download models.",
            modifier,
            tone = NoticeTone.WARNING,
            action = "network",
            onAction = onOpenNetwork,
        )
        failure?.blocked == true -> Notice(
            "The kill switch blocked $hub: ${failure.message}",
            modifier,
            tone = NoticeTone.WARNING,
            action = "network",
            onAction = onOpenNetwork,
        )
        failure != null -> Notice("$hub search failed: ${failure.message}", modifier, tone = NoticeTone.ERROR, action = "retry", onAction = onRetry)
        searched -> Notice(
            if (query.isBlank()) "$hub returned no models for this filter." else "No models on $hub match “${query.trim()}”. Try another name or filter.",
            modifier,
        )
        else -> Notice("Search $hub above, or tap search to list popular models.", modifier, action = "search", onAction = onRetry)
    }
}

/**
 * The Models header: `this phone · SM8850`, then `Snapdragon 8 Elite Gen 5 · 8 cores · i8mm` and
 * `15.8 GB ram · 143 GB free`, each short enough for one line.
 */
internal fun deviceLines(
    socModel: String,
    socName: String?,
    cores: Int,
    i8mm: Boolean,
    ramBytes: Long,
    freeBytes: Long,
): Pair<String, Pair<String, String>> {
    val label = listOfNotNull("this phone", socModel.takeIf { it.isNotBlank() }).joinToString(" · ")
    val chip = listOfNotNull(socName?.takeIf { it.isNotBlank() }, "$cores cores", if (i8mm) "i8mm" else null).joinToString(" · ")
    val memory = "${formatBytes(ramBytes)} ram · ${compactGb(freeBytes)} free"
    return label to (chip to memory)
}

/** `143 GB` from 10 GB up, else [formatBytes]. */
internal fun compactGb(bytes: Long): String =
    if (bytes >= 10_000_000_000L) "${(bytes + 500_000_000L) / 1_000_000_000L} GB" else formatBytes(bytes)

/** Size readout for an installed row: its size on disk (progress is in the downloads panel, once). */
internal fun installedSize(model: InstalledModel): String? = model.totalBytes.takeIf { it > 0 }?.let(::formatBytes)

/**
 * Where an installed model runs, in one word: NPU or GPU for image packages (QNN / MNN), CPU for
 * stable-diffusion.cpp, the user's pick (or "auto") for llama.cpp models, the engine otherwise.
 */
internal fun installedEngine(model: InstalledModel): String = when (model.engine) {
    EngineKind.NPU -> when (model.manifest.npu?.runtime) {
        "qnn" -> "NPU"
        "mnn" -> "GPU"
        else -> "NPU"
    }
    EngineKind.DIFFUSION -> "CPU"
    EngineKind.LLAMA -> if (model.backend == Backend.AUTO) model.format.label else model.backend.label.uppercase(Locale.ROOT)
    EngineKind.NONE -> "not runnable here"
    else -> model.engine.label
}

/** One clean line for an installed row: `image · NPU · 4.5 GB`. */
internal fun installedSubtitle(model: InstalledModel): String =
    listOfNotNull(model.kind.label, installedEngine(model), installedSize(model)).joinToString(" · ")

/** Tests: counts compositions ("screen", "installedRow", "catalogRow") to check what download ticks and list re-reads redraw. */
internal var modelsCompositionProbe: ((String) -> Unit)? = null

@Composable
private fun InstalledRow(model: InstalledModel, actions: ModelsActions, onRemove: () -> Unit = { actions.remove(model.id) }) {
    modelsCompositionProbe?.invoke("installedRow")
    var confirm by remember { mutableStateOf(false) }
    // Tapping a ready model opens the task it runs (the × tag still removes it).
    val task = remember(model.kind, model.engine, model.isReady) { launchOf(model.kind, model.engine, model.isReady).task }
    RunRow(
        title = model.title,
        subtitle = installedSubtitle(model),
        onClick = if (task != ModelTask.NONE) ({ actions.use(model) }) else null,
    ) {
        // "ready" is stamped in when a download finishes while you watch.
        Box(Modifier.stamp(model.status, enabled = model.status == ModelStatus.READY)) {
            when (model.status) {
                ModelStatus.READY -> Tag("ready", accent = MaterialTheme.colorScheme.primary, filled = true)
                // Progress is shown once, in the downloads panel; the row offers what a tap does.
                ModelStatus.DOWNLOADING -> Tag("pause", accent = MaterialTheme.colorScheme.primary, onClick = { actions.pause(model.id) })
                ModelStatus.QUEUED -> Tag("queued", onClick = { actions.pause(model.id) })
                ModelStatus.PAUSED -> Tag("resume", accent = MaterialTheme.colorScheme.primary, onClick = { actions.resume(model.id) })
                ModelStatus.FAILED -> Tag("retry", accent = MaterialTheme.colorScheme.error, onClick = { actions.resume(model.id) })
            }
        }
        Tag(if (confirm) "sure?" else "×", accent = MaterialTheme.colorScheme.onSurfaceVariant, onClick = {
            if (confirm) onRemove() else confirm = true
        })
    }
    model.error?.let { MicroLabel(it, Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun CatalogRow(plan: ModelPlan, fit: Fit?, installed: Boolean, onGet: () -> Unit) {
    modelsCompositionProbe?.invoke("catalogRow")
    RunRow(
        title = plan.title,
        subtitle = listOfNotNull(formatBytes(plan.totalBytes), plan.subtitle).joinToString(" · "),
    ) {
        fit?.let {
            Tag(
                it.label,
                accent = when (it) {
                    Fit.GOOD -> MaterialTheme.colorScheme.onSurfaceVariant
                    Fit.TIGHT -> AutobotColors.Amber
                    Fit.TOO_BIG, Fit.UNSUPPORTED -> MaterialTheme.colorScheme.error
                },
            )
        }
        if (installed) {
            Tag("added", accent = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Tag("get", accent = MaterialTheme.colorScheme.primary, filled = true, onClick = onGet)
        }
    }
}

@Composable
private fun HfSearch(state: ModelsUiState, actions: ModelsActions, onOpenAccounts: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicroLabel(state.accounts.huggingFaceUser?.let { "signed in · $it" } ?: "anonymous · gated models need a token", Modifier.weight(1f))
            Tag("account", onClick = onOpenAccounts)
        }
        Segmented(HfScope.entries, state.hf.scope, actions::setHfScope, { it.label }, Modifier.fillMaxWidth())
        ConsoleTextField(
            state.hf.query,
            actions::setHfQuery,
            Modifier.fillMaxWidth(),
            placeholder = "search gguf repos (qwen, gemma, phi…)",
            singleLine = true,
            mono = true,
            keyboardOptions = PromptKeyboardOptions.copy(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.searchHf() }),
        )
        if (state.hf.loading || state.hf.openLoading) InkLoader(contentDescription = "Searching")
        Hairline()
    }
}

@Composable
private fun RepoSheet(repo: HfRepo, state: ModelsUiState, actions: ModelsActions) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(repo.id)
        Text(
            listOfNotNull(repo.license, if (repo.gated) "gated — accept on huggingface.co" else null, repo.commit?.take(10)).joinToString(" · "),
            style = AutobotTheme.styles.micro,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val files = repo.files.filter { f ->
            val n = f.path.lowercase()
            (n.endsWith(".gguf") || n.endsWith(".safetensors") || n.endsWith(".ckpt") || n.endsWith(".zip")) && "mmproj" !in n
        }
        if (files.isEmpty()) MicroLabel("no model files in this repo")
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(files, key = { it.path }) { f ->
                val kind = state.hf.scope.kind
                val fit = state.device?.fit(kind, f.sizeBytes, dev.wckdboy.autobot.core.models.engineFor(kind, dev.wckdboy.autobot.core.models.formatOf(f.path, repo.id.substringBefore('/'))))
                RunRow(f.path.substringAfterLast('/'), "${formatBytes(f.sizeBytes)}${if (f.sha256 != null) " · sha-256 known" else ""}") {
                    fit?.let { Tag(it.label, accent = if (it == Fit.GOOD) MaterialTheme.colorScheme.onSurfaceVariant else AutobotColors.Amber) }
                    Tag("get", accent = MaterialTheme.colorScheme.primary, filled = true, onClick = { actions.installHfFile(repo, f.path) })
                }
            }
        }
        if (files.any { dev.wckdboy.autobot.core.models.formatOf(it.path, repo.id.substringBefore('/')) == ModelFormat.LOCAL_DREAM }) {
            MicroLabel("local dream packages can't run on autobot's engines — use local dream's host mode as an image backend", color = AutobotColors.Amber)
        }
    }
}

@Composable
private fun CivitaiSearch(state: ModelsUiState, actions: ModelsActions, onOpenAccounts: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicroLabel(
                listOfNotNull(
                    state.accounts.civitaiHost.host,
                    state.accounts.civitaiUser?.let { "signed in · $it" } ?: "no api key",
                    if (state.accounts.showMature) "mature on" else "sfw",
                ).joinToString(" · "),
                Modifier.weight(1f),
            )
            Tag("account", onClick = onOpenAccounts)
        }
        Segmented(CivitaiScope.entries, state.civitai.scope, actions::setCivitaiScope, { it.label }, Modifier.fillMaxWidth())
        ConsoleTextField(
            state.civitai.query,
            actions::setCivitaiQuery,
            Modifier.fillMaxWidth(),
            placeholder = "search sd 1.5 / sdxl / pony / illustrious",
            singleLine = true,
            mono = true,
            keyboardOptions = PromptKeyboardOptions.copy(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.searchCivitai() }),
        )
        if (state.civitai.loading) InkLoader(contentDescription = "Searching")
        Hairline()
    }
}

@Composable
private fun CivitaiRow(model: CivitaiModel, actions: ModelsActions, installed: Set<String>, onGet: () -> Unit) {
    val version = model.versions.firstOrNull()
    val file = version?.files?.firstOrNull { it.primary } ?: version?.files?.firstOrNull()
    val preview by produceState<ImageBitmap?>(null, version?.previewUrl) { value = version?.previewUrl?.let { actions.preview(it) } }
    RunRow(
        title = model.name,
        subtitle = listOfNotNull(version?.baseModel, file?.let { formatBytes(it.sizeBytes) }, model.creator, "${model.downloads} dl").joinToString(" · "),
        leading = {
            Box(Modifier.size(52.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall)) {
                preview?.let {
                    Image(
                        it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().then(if (version?.previewMature == true) Modifier.blur(12.dp) else Modifier),
                    )
                }
            }
        },
    ) {
        when {
            version?.earlyAccess == true -> Tag("early access", accent = AutobotColors.Amber)
            version != null && "civitai:${version.id}" in installed -> Tag("added")
            else -> Tag("get", accent = MaterialTheme.colorScheme.primary, filled = true, onClick = onGet)
        }
    }
}


@Composable
private fun NpuOfferRow(offer: NpuOffer, arch: String?, installed: Boolean, onGet: () -> Unit) {
    val file = offer.fileFor(arch)
    RunRow(
        title = offer.title,
        subtitle = listOfNotNull(formatBytes(offer.approxBytes), offer.subtitle).joinToString(" · "),
    ) {
        when {
            installed -> Tag("added", accent = MaterialTheme.colorScheme.onSurfaceVariant)
            file == null -> Tag("no build", accent = MaterialTheme.colorScheme.error)
            else -> Tag("get", accent = MaterialTheme.colorScheme.primary, filled = true, onClick = onGet)
        }
    }
}

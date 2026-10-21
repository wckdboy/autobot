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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.PromptKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.core.models.Backend
import dev.wckdboy.autobot.core.models.CivitaiModel
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.Fit
import dev.wckdboy.autobot.core.models.HfRepo
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelPlan
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.models.ModelTask
import dev.wckdboy.autobot.core.models.NpuOffer
import dev.wckdboy.autobot.core.models.formatBytes
import dev.wckdboy.autobot.core.models.launchOf
import dev.wckdboy.autobot.core.designsystem.component.AppBar
import dev.wckdboy.autobot.core.designsystem.component.Centered
import dev.wckdboy.autobot.core.designsystem.component.ChoiceChips
import dev.wckdboy.autobot.core.designsystem.component.Field
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.component.ActionListItem
import dev.wckdboy.autobot.core.designsystem.component.NoticeCard
import dev.wckdboy.autobot.core.designsystem.component.Subheader
import dev.wckdboy.autobot.core.designsystem.component.NoticeTone
import java.util.Locale

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
            AppBar("Models", onBack = null, actions = { TextButton(onClick = onOpenCompute) { Text("Compute") } })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Centered(Modifier.padding(padding)) { width ->
            LazyColumn(width.fillMaxHeight(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    state.device?.let { d ->
                        // Two short lines under the label instead of one that runs off the screen.
                        val (chip, specs) = deviceLines(d.socModel, d.socName, d.cores, d.supportsI8mm, d.totalRamBytes, d.freeStorageBytes)
                        ListItem(
                            headlineContent = { Text(labelCase(chip)) },
                            supportingContent = { Text("${labelCase(specs.first)}\n${labelCase(specs.second)}") },
                        )
                    }
                }

                val card = firstModelCard(state)
                card?.let {
                    item(key = "first-model") { FirstModelCard(it, state.device?.totalRamBytes ?: 0, state.offline, actions, ::install) }
                }
                // The card already shows its model's download and its way in: no second and third copy below.
                val carded = card?.plan?.id
                val downloads = state.downloads
                if (!downloads.isEmpty && !(carded != null && downloads.onlyFor(carded))) {
                    item(key = "downloads") { DownloadsPanel(downloads, actions) }
                }

                val listed = state.installed.filter { it.id != carded }
                if (listed.isNotEmpty()) {
                    item { Subheader(labelCase("installed · ${listed.size}")) }
                    items(listed, key = { "i-${it.id}" }) { model ->
                        // Removing a model fades its row out and closes the gap.
                        Box(Modifier.animateItem()) { InstalledRow(model, actions) }
                    }
                }

                item {
                    SecondaryScrollableTabRow(
                        selectedTabIndex = state.tab.ordinal,
                        edgePadding = 0.dp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    ) {
                        ModelsTab.entries.forEach { tab ->
                            Tab(selected = tab == state.tab, onClick = { actions.selectTab(tab) }, text = { Text(tab.label) })
                        }
                    }
                }

                when (state.tab) {
                    ModelsTab.RECOMMENDED -> {
                        val installed = state.installedIds()
                        val arch = state.device?.htpArch
                        fun catalog(sections: List<Pair<String, List<ModelPlan>>>) = sections.forEach { (label, plans) ->
                            item(key = "h-$label") { Subheader(labelCase(label)) }
                            items(plans, key = { "c-${it.id}" }) { plan ->
                                val fit = state.device?.fit(plan.kind, plan.totalBytes, plan.engine)
                                CatalogRow(plan, fit, installed = plan.id in installed, onGet = { install(plan) })
                            }
                        }
                        // What most people come for first: something to chat with (as on iOS).
                        catalog(
                            listOf(
                                "chat" to actions.recommended.filter { it.kind == ModelKind.CHAT },
                                "code" to actions.recommended.filter { it.kind == ModelKind.CODE },
                            ),
                        )
                        // NPU packages only on a Snapdragon with a known Hexagon NPU: elsewhere every row would say "no build".
                        if (arch != null) {
                            listOf("image · npu / gpu" to actions.npuImages, "upscale · npu" to actions.npuUpscalers).forEach { (label, offers) ->
                                item(key = "h-$label") { Subheader(labelCase("$label · hexagon $arch")) }
                                items(offers, key = { "n-${it.id}" }) { offer ->
                                    NpuOfferRow(offer, arch, installed = installed.any { it.startsWith(offer.id + ":") }) {
                                        askNotifications()
                                        actions.installNpuOffer(offer)
                                    }
                                }
                            }
                        }
                        catalog(listOf(
                            "image · cpu" to actions.recommended.filter { it.kind == ModelKind.IMAGE },
                            "speech · dictation" to actions.recommended.filter { it.kind == ModelKind.SPEECH },
                            "voice · read aloud" to actions.recommended.filter { it.kind == ModelKind.VOICE },
                            "audio · studio" to actions.recommended.filter { it.kind == ModelKind.AUDIO },
                            "embedding · vault" to actions.recommended.filter { it.kind == ModelKind.EMBEDDING },
                        ))
                    }
                    ModelsTab.HUGGING_FACE -> {
                        item { HfSearch(state, actions, onOpenAccounts) }
                        if (state.hf.results.isEmpty() && !state.hf.loading) {
                            item(key = "hf-empty") {
                                SearchEmpty("Hugging Face", state.offline, state.hf.searched, state.hf.failure, state.hf.query, onOpenNetwork, actions::searchHf)
                            }
                        }
                        items(state.hf.results, key = { "hf-${it.id}" }) { m ->
                            ActionListItem(
                                title = m.id.substringAfter('/'),
                                subtitle = "${m.id.substringBefore('/')} · ${m.downloads} dl${m.license?.let { " · $it" } ?: ""}",
                                onClick = { actions.openRepo(m.id) },
                            ) { if (m.gated) TonalLabel("gated", containerColor = MaterialTheme.colorScheme.tertiaryContainer) }
                        }
                    }
                    ModelsTab.CIVITAI -> {
                        item { CivitaiSearch(state, actions, onOpenAccounts) }
                        if (state.civitai.results.isEmpty() && !state.civitai.loading) {
                            item(key = "cv-empty") {
                                SearchEmpty("Civitai", state.offline, state.civitai.searched, state.civitai.failure, state.civitai.query, onOpenNetwork) { actions.searchCivitai(reset = true) }
                            }
                        }
                        items(state.civitai.results, key = { "cv-${it.id}" }) { m -> CivitaiRow(m, actions, state.installedIds()) { actions.installCivitai(m) } }
                        // A failed "more" says so under the results it could not extend.
                        if (state.civitai.results.isNotEmpty() && state.civitai.failure != null) {
                            item(key = "cv-more-failed") {
                                SearchEmpty("Civitai", state.offline, true, state.civitai.failure, state.civitai.query, onOpenNetwork) { actions.searchCivitai(reset = false) }
                            }
                        }
                        if (state.civitai.nextCursor != null) {
                            item { TextButton(onClick = { actions.searchCivitai(reset = false) }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("More") } }
                        }
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
    val modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    when {
        offline -> NoticeCard(
            "Offline mode is on, so the kill switch blocks $hub. Choose a network mode to search and download models.",
            modifier,
            tone = NoticeTone.WARNING,
            action = "Network",
            onAction = onOpenNetwork,
        )
        failure?.blocked == true -> NoticeCard(
            "The kill switch blocked $hub: ${failure.message}",
            modifier,
            tone = NoticeTone.WARNING,
            action = "Network",
            onAction = onOpenNetwork,
        )
        failure != null -> NoticeCard("$hub search failed: ${failure.message}", modifier, tone = NoticeTone.ERROR, action = "Retry", onAction = onRetry)
        searched -> NoticeCard(
            if (query.isBlank()) "$hub returned no models for this filter." else "No models on $hub match “${query.trim()}”. Try another name or filter.",
            modifier,
        )
        else -> NoticeCard("Search $hub above, or tap Search to list popular models.", modifier, action = "Search", onAction = onRetry)
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

/** A label for how well a model fits this phone: quiet when it fits, tertiary when tight, error when it does not. */
@Composable
private fun FitLabel(fit: Fit) {
    val scheme = MaterialTheme.colorScheme
    TonalLabel(
        fit.label,
        containerColor = when (fit) {
            Fit.GOOD -> scheme.secondaryContainer
            Fit.TIGHT -> scheme.tertiaryContainer
            Fit.TOO_BIG, Fit.UNSUPPORTED -> scheme.errorContainer
        },
    )
}

/** The row's one action ("Get", "Pause", "Resume"): a compact tonal button. */
@Composable
private fun RowAction(text: String, onClick: () -> Unit, color: Color = Color.Unspecified) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = ButtonDefaults.SmallContentPadding,
        colors = if (color == Color.Unspecified) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.filledTonalButtonColors(containerColor = color),
    ) { Text(text) }
}

@Composable
private fun InstalledRow(model: InstalledModel, actions: ModelsActions) {
    modelsCompositionProbe?.invoke("installedRow")
    var confirm by remember { mutableStateOf(false) }
    // Tapping a ready model opens the task it runs (the × still removes it).
    val task = remember(model.kind, model.engine, model.isReady) { launchOf(model.kind, model.engine, model.isReady).task }
    ActionListItem(
        title = model.title,
        subtitle = installedSubtitle(model),
        onClick = if (task != ModelTask.NONE) ({ actions.use(model) }) else null,
        extra = model.error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
    ) {
        when (model.status) {
            ModelStatus.READY -> TonalLabel("ready")
            // Progress is shown once, in the downloads panel; the row offers what a tap does.
            ModelStatus.DOWNLOADING, ModelStatus.QUEUED -> RowAction("Pause", { actions.pause(model.id) })
            ModelStatus.PAUSED -> RowAction("Resume", { actions.resume(model.id) })
            ModelStatus.FAILED -> RowAction("Retry", { actions.resume(model.id) }, MaterialTheme.colorScheme.errorContainer)
        }
        if (confirm) {
            TextButton(onClick = { actions.remove(model.id) }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove?") }
        } else {
            IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Close, contentDescription = "Remove ${model.title}") }
        }
    }
}

@Composable
private fun CatalogRow(plan: ModelPlan, fit: Fit?, installed: Boolean, onGet: () -> Unit) {
    modelsCompositionProbe?.invoke("catalogRow")
    ActionListItem(
        title = plan.title,
        subtitle = listOfNotNull(formatBytes(plan.totalBytes), plan.subtitle).joinToString(" · "),
    ) {
        fit?.let { FitLabel(it) }
        if (installed) TonalLabel("added") else RowAction("Get", onGet)
    }
}

/** The hub's account line and its way to the Accounts screen. */
@Composable
private fun AccountLine(text: String, onOpenAccounts: () -> Unit) {
    Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenAccounts) { Text("Account") }
    }
}

/** The hub's search field: the code face (repo names), private keyboard, Search on the keyboard. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, onSearch: () -> Unit) {
    Field(
        query,
        onQuery,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = placeholder,
        singleLine = true,
        mono = true,
        keyboardOptions = PromptKeyboardOptions.copy(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        trailingIcon = { IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Searching() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        LoadingIndicator(Modifier.semantics { contentDescription = "Searching" })
    }
}

@Composable
private fun HfSearch(state: ModelsUiState, actions: ModelsActions, onOpenAccounts: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccountLine(labelCase(state.accounts.huggingFaceUser?.let { "signed in · $it" } ?: "anonymous · gated models need a token"), onOpenAccounts)
        ChoiceChips(HfScope.entries, state.hf.scope, actions::setHfScope, { it.label }, Modifier.padding(horizontal = 16.dp))
        SearchField(state.hf.query, actions::setHfQuery, "Search GGUF repos (qwen, gemma, phi…)") { actions.searchHf() }
        if (state.hf.loading || state.hf.openLoading) Searching()
    }
}

@Composable
private fun RepoSheet(repo: HfRepo, state: ModelsUiState, actions: ModelsActions) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(repo.id, style = MaterialTheme.typography.titleMedium)
            Text(
                listOfNotNull(repo.license, if (repo.gated) "gated — accept on huggingface.co" else null, repo.commit?.take(10)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val files = repo.files.filter { f ->
            val n = f.path.lowercase()
            (n.endsWith(".gguf") || n.endsWith(".safetensors") || n.endsWith(".ckpt") || n.endsWith(".zip")) && "mmproj" !in n
        }
        if (files.isEmpty()) Text("No model files in this repo.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(files, key = { it.path }) { f ->
                val kind = state.hf.scope.kind
                val fit = state.device?.fit(kind, f.sizeBytes, dev.wckdboy.autobot.core.models.engineFor(kind, dev.wckdboy.autobot.core.models.formatOf(f.path, repo.id.substringBefore('/'))))
                ActionListItem(f.path.substringAfterLast('/'), "${formatBytes(f.sizeBytes)}${if (f.sha256 != null) " · sha-256 known" else ""}") {
                    fit?.let { FitLabel(it) }
                    RowAction("Get", { actions.installHfFile(repo, f.path) })
                }
            }
        }
        if (files.any { dev.wckdboy.autobot.core.models.formatOf(it.path, repo.id.substringBefore('/')) == ModelFormat.LOCAL_DREAM }) {
            NoticeCard(
                "Local Dream packages can't run on Autobot's engines. Use Local Dream's host mode as an image backend.",
                Modifier.padding(horizontal = 16.dp),
                tone = NoticeTone.WARNING,
            )
        }
    }
}

@Composable
private fun CivitaiSearch(state: ModelsUiState, actions: ModelsActions, onOpenAccounts: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccountLine(
            listOfNotNull(
                state.accounts.civitaiHost.host,
                state.accounts.civitaiUser?.let { "signed in · $it" } ?: "no api key",
                if (state.accounts.showMature) "mature on" else "sfw",
            ).joinToString(" · "),
            onOpenAccounts,
        )
        ChoiceChips(CivitaiScope.entries, state.civitai.scope, actions::setCivitaiScope, { it.label }, Modifier.padding(horizontal = 16.dp))
        SearchField(state.civitai.query, actions::setCivitaiQuery, "Search SD 1.5, SDXL, Pony, Illustrious") { actions.searchCivitai() }
        if (state.civitai.loading) Searching()
    }
}

@Composable
private fun CivitaiRow(model: CivitaiModel, actions: ModelsActions, installed: Set<String>, onGet: () -> Unit) {
    val version = model.versions.firstOrNull()
    val file = version?.files?.firstOrNull { it.primary } ?: version?.files?.firstOrNull()
    val preview by produceState<ImageBitmap?>(null, version?.previewUrl) { value = version?.previewUrl?.let { actions.preview(it) } }
    ActionListItem(
        title = model.name,
        subtitle = listOfNotNull(version?.baseModel, file?.let { formatBytes(it.sizeBytes) }, model.creator, "${model.downloads} dl").joinToString(" · "),
        leading = {
            Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
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
            version?.earlyAccess == true -> TonalLabel("early access", containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            version != null && "civitai:${version.id}" in installed -> TonalLabel("added")
            else -> RowAction("Get", onGet)
        }
    }
}

@Composable
private fun NpuOfferRow(offer: NpuOffer, arch: String?, installed: Boolean, onGet: () -> Unit) {
    val file = offer.fileFor(arch)
    ActionListItem(
        title = offer.title,
        subtitle = listOfNotNull(formatBytes(offer.approxBytes), offer.subtitle).joinToString(" · "),
    ) {
        when {
            installed -> TonalLabel("added")
            file == null -> TonalLabel("no build")
            else -> RowAction("Get", onGet)
        }
    }
}

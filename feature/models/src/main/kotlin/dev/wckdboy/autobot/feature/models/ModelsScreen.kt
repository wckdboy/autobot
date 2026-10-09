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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.PromptKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.RunRow
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.models.CivitaiModel
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.Fit
import dev.wckdboy.autobot.core.models.HfRepo
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelFormat
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelPlan
import dev.wckdboy.autobot.core.models.ModelStatus
import dev.wckdboy.autobot.core.models.formatBytes

@Composable
fun ModelsRoute(onOpenAccounts: () -> Unit, viewModel: ModelsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ModelsScreen(state, viewModel, onOpenAccounts)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(state: ModelsUiState, actions: ModelsViewModel, onOpenAccounts: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            actions.consumeMessage()
        }
    }
    // The download progress notification needs this on Android 13+; downloads work without it.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    fun install(plan: ModelPlan) {
        if (!askedNotifications && Build.VERSION.SDK_INT >= 33) {
            askedNotifications = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        actions.install(plan)
    }

    Scaffold(
        topBar = { AutobotTopBar(title = "Models") },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                state.device?.let { d ->
                    SectionLabel("this phone · ${d.label}")
                    Spacer(Modifier.height(4.dp))
                    MicroLabel("${formatBytes(d.totalRamBytes)} ram · ${formatBytes(d.freeStorageBytes)} free · ${d.cores} cores${if (d.supportsI8mm) " · i8mm" else ""}")
                }
                Spacer(Modifier.height(14.dp))
            }

            state.progress?.let { p ->
                item(key = "progress") {
                    Column(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                MicroLabel("downloading", color = MaterialTheme.colorScheme.primary)
                                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = { actions.pause(p.id) }) { Text("PAUSE") }
                        }
                        LinearProgressIndicator(
                            progress = { p.fraction },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            drawStopIndicator = {},
                        )
                        MicroLabel("${formatBytes(p.downloaded)} / ${formatBytes(p.total)} · ${formatBytes(p.bytesPerSecond)}/s")
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }

            if (state.installed.isNotEmpty()) {
                item { SectionLabel("installed · ${state.installed.size}") }
                items(state.installed, key = { "i-${it.id}" }) { model -> InstalledRow(model, actions) }
                item { Spacer(Modifier.height(18.dp)) }
            }

            item {
                Segmented(ModelsTab.entries, state.tab, actions::selectTab, { it.label }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }

            when (state.tab) {
                ModelsTab.RECOMMENDED -> {
                    val installed = state.installedIds()
                    listOf(
                        "chat" to actions.recommended.filter { it.kind == ModelKind.CHAT },
                        "code" to actions.recommended.filter { it.kind == ModelKind.CODE },
                        "image" to actions.recommended.filter { it.kind == ModelKind.IMAGE },
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
                    item {
                        MicroLabel("all models run on this phone's cpu · weights are verified with sha-256 after download")
                        Spacer(Modifier.height(24.dp))
                    }
                }
                ModelsTab.HUGGING_FACE -> {
                    item { HfSearch(state, actions, onOpenAccounts) }
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
                    items(state.civitai.results, key = { "cv-${it.id}" }) { m -> CivitaiRow(m, actions, state.installedIds()) { actions.installCivitai(m) } }
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

@Composable
private fun InstalledRow(model: InstalledModel, actions: ModelsViewModel) {
    var confirm by remember { mutableStateOf(false) }
    RunRow(
        title = model.title,
        subtitle = listOfNotNull(
            model.kind.label,
            model.format.label,
            formatBytes(model.totalBytes).takeIf { model.totalBytes > 0 },
            model.engine.label.takeIf { model.engine != EngineKind.NONE } ?: "not runnable here",
        ).joinToString(" · "),
    ) {
        when (model.status) {
            ModelStatus.READY -> Tag("ready", accent = MaterialTheme.colorScheme.primary, filled = true)
            ModelStatus.DOWNLOADING -> Tag("${(model.fraction * 100).toInt()}%", accent = MaterialTheme.colorScheme.primary)
            ModelStatus.QUEUED -> Tag("queued")
            ModelStatus.PAUSED -> Tag("resume", accent = MaterialTheme.colorScheme.primary, onClick = { actions.resume(model.id) })
            ModelStatus.FAILED -> Tag("retry", accent = MaterialTheme.colorScheme.error, onClick = { actions.resume(model.id) })
        }
        Tag(if (confirm) "sure?" else "×", accent = MaterialTheme.colorScheme.onSurfaceVariant, onClick = {
            if (confirm) actions.remove(model.id) else confirm = true
        })
    }
    model.error?.let { MicroLabel(it, Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun CatalogRow(plan: ModelPlan, fit: Fit?, installed: Boolean, onGet: () -> Unit) {
    RunRow(
        title = plan.title,
        subtitle = listOfNotNull(formatBytes(plan.totalBytes), plan.subtitle, plan.license).joinToString(" · "),
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
private fun HfSearch(state: ModelsUiState, actions: ModelsViewModel, onOpenAccounts: () -> Unit) {
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
        if (state.hf.loading || state.hf.openLoading) MicroLabel("loading…")
        Hairline()
    }
}

@Composable
private fun RepoSheet(repo: HfRepo, state: ModelsUiState, actions: ModelsViewModel) {
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
private fun CivitaiSearch(state: ModelsUiState, actions: ModelsViewModel, onOpenAccounts: () -> Unit) {
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
        if (state.civitai.loading) MicroLabel("loading…")
        Hairline()
    }
}

@Composable
private fun CivitaiRow(model: CivitaiModel, actions: ModelsViewModel, installed: Set<String>, onGet: () -> Unit) {
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


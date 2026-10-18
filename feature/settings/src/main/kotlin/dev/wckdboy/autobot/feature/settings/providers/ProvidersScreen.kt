package dev.wckdboy.autobot.feature.settings.providers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.InkBusyLabel
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.SecretKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.motion.inkDissolve
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkDissolve
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.providers.remote.ModelCache
import dev.wckdboy.autobot.providers.remote.ModelDataPolicies
import dev.wckdboy.autobot.providers.remote.ModelLists
import dev.wckdboy.autobot.providers.remote.ModelsApi
import dev.wckdboy.autobot.providers.remote.PresetGroup
import dev.wckdboy.autobot.providers.remote.ProviderPresets
import dev.wckdboy.autobot.providers.remote.RemoteModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop

@Composable
fun ProvidersRoute(onBack: () -> Unit, viewModel: ProvidersViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProvidersScreen(
        state = state,
        actions = object : ProvidersActions {
            override fun back() = onBack()
            override fun add() = viewModel.startAdd()
            override fun search(query: String) = viewModel.search(query)
            override fun choosePreset(id: String) = viewModel.choosePreset(id)
            override fun dismissPicker() = viewModel.dismissPicker()
            override fun edit(id: String) = viewModel.startEdit(id)
            override fun delete(id: String) = viewModel.delete(id)
            override fun change(transform: (ProviderDraft) -> ProviderDraft) = viewModel.updateDraft(transform)
            override fun refreshModels() = viewModel.refreshModels()
            override fun save() = viewModel.save()
            override fun test() = viewModel.testConnection()
            override fun dismissEditor() = viewModel.dismissEditor()
        },
    )
}

interface ProvidersActions {
    fun back()
    fun add()
    fun search(query: String)
    fun choosePreset(id: String)
    fun dismissPicker()
    fun edit(id: String)
    fun delete(id: String)
    fun change(transform: (ProviderDraft) -> ProviderDraft)
    fun refreshModels()
    fun save()
    fun test()
    fun dismissEditor()
}

private val ProviderRouting.label: String
    get() = when (this) {
        ProviderRouting.INHERIT -> "Inherit"
        ProviderRouting.DIRECT -> "Direct"
        ProviderRouting.TOR -> "Tor"
        ProviderRouting.SOCKS5 -> "SOCKS5"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(state: ProvidersUiState, actions: ProvidersActions) {
    var deleting by remember { mutableStateOf<ProviderRow?>(null) }
    val dissolve = rememberInkDissolve()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Providers") },
                navigationIcon = {
                    IconButton(onClick = actions::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = actions::add) { Icon(Icons.Filled.Add, contentDescription = "Add provider") }
        },
    ) { padding ->
        if (state.providers.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No providers yet. Tap + to pick one of ${ProviderPresets.all.size - 2} providers or local servers, or any OpenAI-compatible endpoint.\n" +
                        "API keys are encrypted with a hardware-backed key.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(state.providers, key = { it.id }, contentType = { "provider" }) { row ->
                    ListItem(
                        modifier = Modifier.inkDissolve(dissolve, row.id).clickable { actions.edit(row.id) },
                        headlineContent = { Text(row.name) },
                        supportingContent = {
                            Column {
                                Row {
                                    Text(
                                        "${row.presetName} · ${row.model} · ${row.routing.label}" + if (row.hasKey) " · key ✓" else "",
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (row.requiresKey && !row.hasKey) Text(" · no key", color = MaterialTheme.colorScheme.error, maxLines = 1)
                                }
                                row.models?.let { MicroLabel(it) }
                                row.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            }
                        },
                        overlineContent = { Text(row.baseUrl, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            IconButton(onClick = { deleting = row }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${row.name}")
                            }
                        },
                    )
                }
            }
        }
    }

    state.pickerQuery?.let { query -> PresetPickerSheet(query, actions) }
    state.editor?.let { draft -> ProviderEditorSheet(draft, state, actions) }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${row.name}?") },
            text = { Text(deleteMessage(row, state.providers)) },
            confirmButton = {
                TextButton(onClick = {
                    dissolve.dissolve(row.id) { actions.delete(row.id) }
                    deleting = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Its API key goes with it; chats on it move to the next provider (`ChatRoutes`). */
private fun deleteMessage(row: ProviderRow, providers: List<ProviderRow>): String {
    val next = providers.firstOrNull { it.id != row.id }
    val used = if (row.chats == 1) "1 chat uses it" else "${row.chats} chats use it"
    return "Its API key is deleted from this phone." + when {
        row.chats == 0 -> ""
        next != null -> " $used and will switch to ${next.name}."
        else -> " $used; it will have no provider until you add one."
    }
}

/** "Add provider": every preset, searchable, grouped Cloud · Open-model hosts · Local servers · This phone. */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
private fun PresetPickerSheet(initialQuery: String, actions: ProvidersActions) {
    // The search text lives in the sheet: filtering ~100 presets is local, and the screen's state
    // (which only remembers the text) hears about it once typing pauses.
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    LaunchedEffect(Unit) { snapshotFlow { query }.drop(1).debounce(300).collect(actions::search) }
    val groups = remember(query) { ProviderPresets.search(query) }
    ModalBottomSheet(onDismissRequest = actions::dismissPicker, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding().padding(horizontal = 16.dp)) {
            SectionLabel("add provider")
            Spacer(Modifier.height(8.dp))
            ConsoleTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = "Search providers or hosts", singleLine = true)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (groups.isEmpty()) item { MicroLabel("no match · try “Other OpenAI-compatible”", Modifier.padding(vertical = 16.dp)) }
                groups.forEach { (group, presets) ->
                    item(key = "g-${group.name}") { MicroLabel("// ${group.label} · ${presets.size}", Modifier.padding(top = 14.dp, bottom = 4.dp), MaterialTheme.colorScheme.primary) }
                    if (group == PresetGroup.LOCAL_SERVERS) {
                        item(key = "n-${group.name}") {
                            Text(
                                "127.0.0.1 is this phone: these presets only reach a server running on the phone. For a server on your PC or network use its https address (Tailscale or a reverse proxy) — plain http only works on the phone itself.",
                                modifier = Modifier.padding(bottom = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(presets, key = { it.id }) { preset ->
                        val selectable = preset.group != PresetGroup.THIS_PHONE
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .then(if (selectable) Modifier.clickable { actions.choosePreset(preset.id) } else Modifier)
                                .padding(vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(preset.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                                if (preset.kind == ProviderKind.ANTHROPIC) Tag("native")
                                if (!preset.requiresApiKey && selectable) Tag("no key")
                            }
                            MicroLabel(if (selectable) preset.host else "added automatically")
                            preset.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Hairline()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProviderEditorSheet(draft: ProviderDraft, state: ProvidersUiState, actions: ProvidersActions) {
    val preset = draft.preset
    val test = state.test
    val models = state.models
    ModalBottomSheet(onDismissRequest = actions::dismissEditor, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        NoPersonalizedLearning {
            Column(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionLabel(if (draft.isNew) "add provider" else "edit provider")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(preset.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Tag(preset.group.label)
                }
                preset.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                ConsoleTextField(draft.displayName, { v -> actions.change { it.copy(displayName = v) } }, Modifier.fillMaxWidth(), label = "Name", singleLine = true)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ConsoleTextField(
                        draft.baseUrl, { v -> actions.change { it.copy(baseUrl = v) } }, Modifier.fillMaxWidth(),
                        label = "Base URL", singleLine = true, mono = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    )
                    val urlProblem = if (draft.baseUrl.isNotBlank() && !draft.baseUrlValid) "Not a valid URL — start with https://" else draft.cleartextProblem
                    // A problem wraps (a one-line label would cut the fix off); the hint stays a label.
                    if (urlProblem != null) {
                        Text(urlProblem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        MicroLabel("cleartext http only for 127.0.0.1 / localhost")
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ConsoleTextField(
                        draft.apiKeyInput, { v -> actions.change { it.copy(apiKeyInput = v, clearStoredKey = false) } }, Modifier.fillMaxWidth(),
                        label = if (preset.requiresApiKey) "API key" else "API key (optional)",
                        placeholder = if (draft.hasStoredKey && !draft.clearStoredKey) "•••••••• saved — type to replace" else null,
                        singleLine = true,
                        keyboardOptions = SecretKeyboardOptions,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (draft.missingKey) MicroLabel("This provider needs an API key", color = MaterialTheme.colorScheme.error)
                    draft.keyProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    MicroLabel("encrypted with android keystore · sent only to ${preset.host.ifBlank { "this host" }}")
                    if (draft.hasStoredKey && draft.apiKeyInput.isEmpty()) {
                        TextButton(onClick = { actions.change { it.copy(clearStoredKey = !it.clearStoredKey) } }) {
                            Text(if (draft.clearStoredKey) "KEEP SAVED KEY" else "REMOVE SAVED KEY")
                        }
                    }
                }

                ModelPicker(draft, models, state.now, preset.modelsApi != ModelsApi.NONE, actions)
                HelperModelField(draft, models, actions)

                MicroLabel("routing")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderRouting.entries.forEach { routing ->
                        val active = draft.routing == routing
                        Tag(routing.label, accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, filled = active, onClick = {
                            actions.change { it.copy(routing = routing) }
                        })
                    }
                }
                Text(
                    "Offline mode always blocks remote requests. Direct cannot bypass a global Tor/SOCKS5 mode.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preset.privacyNote?.let { note ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        MicroLabel("privacy")
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        preset.privacyUrl?.let { MicroLabel(it) }
                    }
                }

                TestResult(test)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (!state.saving) draft.saveProblem?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }

                var confirmNoKey by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton("Test connection", actions::test, Modifier.weight(1f), enabled = draft.baseUrlValid, busy = test == TestState.Running)
                    PrimaryButton("Save", { if (draft.missingKey) confirmNoKey = true else actions.save() }, Modifier.weight(1f), enabled = draft.canSave || state.saving, busy = state.saving)
                }
                if (confirmNoKey) {
                    AlertDialog(
                        onDismissRequest = { confirmNoKey = false },
                        title = { Text("Save without an API key?") },
                        text = { Text("${preset.displayName} needs an API key; requests will be refused until you add one.") },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmNoKey = false
                                actions.save()
                            }) { Text("Save anyway") }
                        },
                        dismissButton = { TextButton(onClick = { confirmNoKey = false }) { Text("Cancel") } },
                    )
                }
                TextButton(onClick = actions::dismissEditor, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("CANCEL") }
            }
        }
    }
}

/** Default model: a field that also searches the fetched (or offline) list below it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelPicker(draft: ProviderDraft, models: EditorModels, now: Long, canFetch: Boolean, actions: ProvidersActions) {
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ConsoleTextField(
            draft.defaultModel, { v -> actions.change { it.copy(defaultModel = v) } }, Modifier.fillMaxWidth(),
            label = "Default model", singleLine = true, mono = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val status = when {
                models.loading -> "fetching models"
                models.error != null -> models.error
                models.fetchedAt != null -> "${counted(models.models.size, "model")} · " + ModelCache(models.fetchedAt, emptyList()).ageLabel(now)
                canFetch -> "offline list · refresh to fetch"
                else -> "no models endpoint · documented list"
            }
            MicroLabel(status, Modifier.weight(1f), if (models.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (canFetch) {
                TextButton(onClick = actions::refreshModels, enabled = draft.baseUrlValid && !models.loading) { InkBusyLabel(models.loading) { Text("REFRESH") } }
            }
        }
        val query = if (models.models.any { it.id == draft.defaultModel }) "" else draft.defaultModel
        val shown = ModelLists.filter(models.models, query, limit = 24)
        Column {
            shown.forEach { m ->
                ModelRow(m, draft.presetId, active = m.id == draft.defaultModel) {
                    if (ModelDataPolicies.trains(draft.presetId, m.id)) confirm = m.id else actions.change { it.copy(defaultModel = m.id) }
                }
            }
            val more = ModelLists.filter(models.models, query, limit = Int.MAX_VALUE).size - shown.size
            if (more > 0) MicroLabel("+$more more · type to search")
        }
    }
    confirm?.let { id -> TrainsDialog(id, onConfirm = { actions.change { it.copy(defaultModel = id) } }, onDismiss = { confirm = null }) }
}

/** Helper model: optional; typing searches the same list (a few matches), picking fills it in. */
@Composable
private fun HelperModelField(draft: ProviderDraft, models: EditorModels, actions: ProvidersActions) {
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ConsoleTextField(
            draft.helperModel, { v -> actions.change { it.copy(helperModel = v) } }, Modifier.fillMaxWidth(),
            label = "Helper model (optional)", singleLine = true, mono = true,
        )
        val helper = draft.helperModel.trim()
        val chosen = models.models.firstOrNull { it.id == helper }
        chosen?.let { ModelDataPolicies.modelCaption(draft.presetId, it).ifEmpty { null } }?.let { MicroLabel(it) }
        Text(HELPER_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (helper.isNotEmpty() && chosen == null) {
            ModelLists.filter(models.models, helper, limit = 5).forEach { m ->
                ModelRow(m, draft.presetId, active = false) {
                    if (ModelDataPolicies.trains(draft.presetId, m.id)) confirm = m.id else actions.change { it.copy(helperModel = m.id) }
                }
            }
        }
    }
    confirm?.let { id -> TrainsDialog(id, onConfirm = { actions.change { it.copy(helperModel = id) } }, onDismiss = { confirm = null }) }
}

/** One model of a list: its id, then context, price and (per-model) data policy. */
@Composable
private fun ModelRow(m: RemoteModel, presetId: String, active: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Text(
            (if (active) "● " else "") + m.id,
            style = AutobotTheme.styles.code,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ModelDataPolicies.modelCaption(presetId, m).takeIf { it.isNotEmpty() }?.let {
            MicroLabel(it, color = if (ModelDataPolicies.trains(presetId, m.id)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Asks before choosing a model whose provider trains on prompts. */
@Composable
private fun TrainsDialog(model: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Use $model?") },
        text = { Text(ModelDataPolicies.TRAINS_WARNING + " Anything you send with it may end up in a future model.") },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text("Use anyway") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val HELPER_HINT = "Used for subagents and context summaries, to save quota. Empty: the chat's model."

@Composable
private fun TestResult(test: TestState) {
    when (test) {
        TestState.Idle -> Unit
        // The test button carries the ink loader while it runs.
        TestState.Running -> Unit
        is TestState.Success -> Text(test.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        is TestState.Failure -> Text(test.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private object NoActions : ProvidersActions {
    override fun back() = Unit
    override fun add() = Unit
    override fun search(query: String) = Unit
    override fun choosePreset(id: String) = Unit
    override fun dismissPicker() = Unit
    override fun edit(id: String) = Unit
    override fun delete(id: String) = Unit
    override fun change(transform: (ProviderDraft) -> ProviderDraft) = Unit
    override fun refreshModels() = Unit
    override fun save() = Unit
    override fun test() = Unit
    override fun dismissEditor() = Unit
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun ProvidersScreenPreview() {
    AutobotTheme {
        ProvidersScreen(
            state = ProvidersUiState(
                providers = listOf(
                    ProviderRow("1", "DeepSeek", ProviderKind.DEEPSEEK, "DeepSeek", "https://api.deepseek.com", "deepseek-flash", ProviderRouting.INHERIT, true, "2 models · fetched 3 min ago"),
                    ProviderRow("2", "Ollama", ProviderKind.OLLAMA, "Ollama", "http://127.0.0.1:11434/v1", "gpt-oss:20b", ProviderRouting.DIRECT, false),
                ),
            ),
            actions = NoActions,
        )
    }
}

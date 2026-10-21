package dev.wckdboy.autobot.feature.studio.ui.workflows

import dev.wckdboy.autobot.shared.studio.StudioRoomWords
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import dev.wckdboy.autobot.core.designsystem.component.BusyLabel
import dev.wckdboy.autobot.core.designsystem.component.Caption
import dev.wckdboy.autobot.core.designsystem.component.DropdownField
import dev.wckdboy.autobot.core.designsystem.component.Field
import dev.wckdboy.autobot.core.designsystem.component.NoticeCard
import dev.wckdboy.autobot.core.designsystem.component.NoticeTone
import dev.wckdboy.autobot.core.designsystem.component.SearchField
import dev.wckdboy.autobot.core.designsystem.component.SectionCard
import dev.wckdboy.autobot.core.designsystem.component.SheetLabel
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.component.clearListItem
import dev.wckdboy.autobot.feature.studio.ui.ActionChip
import dev.wckdboy.autobot.feature.studio.ui.PickChip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.motion.inkStagger
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.feature.studio.engine.AuthUpdate
import dev.wckdboy.autobot.feature.studio.engine.ComfyBackendConfig
import dev.wckdboy.autobot.feature.studio.engine.StudioSettings
import dev.wckdboy.autobot.feature.studio.ui.AssetThumb
import dev.wckdboy.autobot.feature.studio.ui.ChipRow
import dev.wckdboy.autobot.feature.studio.ui.EmptyRoom
import dev.wckdboy.autobot.feature.studio.ui.ErrorLine
import dev.wckdboy.autobot.feature.studio.ui.ParamControls
import dev.wckdboy.autobot.feature.studio.ui.RoomColumn
import dev.wckdboy.autobot.feature.studio.ui.RunsOn
import dev.wckdboy.autobot.feature.studio.ui.StudioShellState
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.Pipeline
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.WorkflowImport

/** The Workflows room: backends, import, saved workflows (open → review / run) and pipelines. */
@Composable
fun WorkflowsRoom(
    state: WorkflowsState,
    shell: StudioShellState,
    actions: WorkflowsActions,
    newBackend: (String) -> ComfyBackendConfig,
    auth: (String) -> AuthUpdate,
    onOpenAsset: (StudioAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<ComfyBackendConfig?>(null) }
    val draft = state.draft
    RoomColumn(modifier.imePadding()) {
        state.message?.let { ErrorLine(it, actions::dismiss) }
        if (draft != null) {
            WorkflowDetail(state, shell, actions, onOpenAsset)
        } else {
            Backends(state, actions, onEdit = { editing = it }, onAdd = { editing = newBackend(it) })
            Import(state, actions)
            Saved(state, actions)
            Pipelines(state, shell, actions)
        }
        Spacer(Modifier.size(12.dp))
    }
    editing?.let { config ->
        BackendSheet(
            config,
            isNew = state.backends.none { it.id == config.id },
            onSave = { c, value, clear -> actions.saveBackend(c, if (clear) AuthUpdate.Clear else auth(value)); editing = null },
            onDelete = { actions.deleteBackend(config.id); editing = null },
            onTest = actions::testBackend,
            testResult = state.tests[config.id],
            testing = state.testing == config.id,
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Backends(state: WorkflowsState, actions: WorkflowsActions, onEdit: (ComfyBackendConfig) -> Unit, onAdd: (String) -> Unit) {
    SheetLabel("comfyui backends")
    if (state.loaded && state.backends.isEmpty()) {
        EmptyRoom(StudioRoomWords.workflowsNoPc)
    }
    state.backends.forEach { b ->
        val result = state.tests[b.id]
        val inUse = b.id == state.backend?.id
        SectionCard(
            title = b.name,
            trailing = {
                if (inUse) TonalLabel("in use", containerColor = MaterialTheme.colorScheme.primaryContainer)
                TonalLabel(if (b.isLoopback) "loopback" else "network", containerColor = if (b.isLoopback) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer)
            },
        ) {
            // Breaks only after "/" and "." when it wraps (a narrow window, a large font).
            Text(
                b.baseUrl.replace("/", "/\u200B").replace(".", ".\u200B"),
                Modifier.clickable { onEdit(b) }.semantics { contentDescription = b.baseUrl },
                style = AutobotTheme.styles.code,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { if (state.testing != b.id) actions.testBackend(b) }, enabled = state.testing == null || state.testing == b.id) { BusyLabel("Test", state.testing == b.id) }
                OutlinedButton(onClick = { onEdit(b) }) { Text("Edit") }
                if (!inUse) TextButton(onClick = { actions.selectBackend(b.id) }) { Text("Use") }
            }
            result?.let { Caption(it, color = if (it.startsWith("ok")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioSettings.PRESETS.forEach { url -> AssistChip(onClick = { onAdd(url) }, label = { Text("+ $url") }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackendSheet(
    initial: ComfyBackendConfig,
    isNew: Boolean,
    onSave: (ComfyBackendConfig, String, Boolean) -> Unit,
    onDelete: () -> Unit,
    onTest: (ComfyBackendConfig) -> Unit,
    testResult: String?,
    testing: Boolean,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var routing by remember { mutableStateOf(runCatching { RouteOverride.valueOf(initial.routing) }.getOrDefault(RouteOverride.INHERIT)) }
    var header by remember { mutableStateOf(initial.authHeader ?: "") }
    var secret by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    val draft = initial.copy(name = name.trim().ifBlank { "ComfyUI" }, baseUrl = url.trim(), routing = routing.name, authHeader = header.trim().ifBlank { null })
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SheetLabel(if (isNew) "new comfyui backend" else "edit comfyui backend")
            Field(name, { name = it }, Modifier.fillMaxWidth(), label = "Name", singleLine = true)
            Field(
                url, { url = it }, Modifier.fillMaxWidth(), label = "Base URL", singleLine = true, mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            if (draft.cleartextRemote) {
                Caption("cleartext http is only allowed to loopback — use https (a reverse proxy or tailnet name)", color = MaterialTheme.colorScheme.error)
            } else {
                Caption(if (draft.isLoopback) "loopback: keeps working in Offline mode (a tunnel to your pc)" else "progress uses wss:// on the same host")
            }
            DropdownField("Route", routing.name.lowercase(), RouteOverride.entries, { routing = it }, Modifier.fillMaxWidth(), optionLabel = { it.name.lowercase() })
            Field(header, { header = it }, Modifier.fillMaxWidth(), label = "Auth header (optional)", placeholder = "Authorization", singleLine = true, mono = true)
            if (header.isNotBlank()) {
                Field(
                    secret, { secret = it; clear = false }, Modifier.fillMaxWidth(),
                    label = if (initial.authRef != null) "Value (stored; type to replace)" else "Value",
                    singleLine = true, mono = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                if (initial.authRef != null) PickChip(if (clear) "Will remove the stored value" else "Remove stored value", selected = clear, onClick = { clear = !clear })
                Caption("the value is kept in the encrypted secret store and never exported")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { if (!testing) onTest(draft) }, enabled = url.isNotBlank() && !draft.cleartextRemote) { BusyLabel("Test", testing) }
                testResult?.let { Caption(it, Modifier.weight(1f), color = if (it.startsWith("ok")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onSave(draft, secret, clear) }, enabled = url.isNotBlank() && !draft.cleartextRemote) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Import(state: WorkflowsState, actions: WorkflowsActions) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(actions::importFile) }
    SheetLabel("import a workflow")
    Caption("api format: in comfyui, workflow → export (api). share a .json to autobot, pick a file, or paste it")
    Field(state.importName, actions::setImportName, Modifier.fillMaxWidth(), label = "Name", singleLine = true)
    Field(state.importText, actions::setImportText, Modifier.fillMaxWidth(), label = "Workflow JSON", minLines = 3, maxLines = 8, mono = true)
    state.importError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = if (it == WorkflowImport.UI_FORMAT_MESSAGE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.weight(1f)) { Text("Choose file") }
        Button(onClick = actions::review, Modifier.weight(1f), enabled = state.importText.isNotBlank()) { Text("Review") }
    }
}

@Composable
private fun Saved(state: WorkflowsState, actions: WorkflowsActions) {
    SheetLabel("saved · ${state.workflows.size}")
    if (state.workflows.isEmpty()) Caption("nothing saved yet")
    state.workflows.forEachIndexed { i, w ->
        ListItem(
            headlineContent = { Text(w.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text("${counted(w.params.size, "param")} · v${w.version}") },
            trailingContent = { TonalLabel(w.output.name.lowercase()) },
            colors = clearListItem(),
            modifier = Modifier.inkStagger(i).clickable { actions.open(w) },
        )
    }
}

/** A workflow open: where it runs, its validation, its controls, and the schema review. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkflowDetail(state: WorkflowsState, shell: StudioShellState, actions: WorkflowsActions, onOpenAsset: (StudioAsset) -> Unit) {
    val draft = state.draft ?: return
    val w = draft.workflow
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(w.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = actions::close) { Text("Close") }
    }
    Caption("makes ${w.output.name.lowercase()} · v${w.version}" + if (!draft.saved) " · not saved yet" else "")
    if (state.backends.isEmpty()) {
        Caption("add a comfyui backend to run it", color = MaterialTheme.colorScheme.error)
    } else {
        ChipRow(state.backends, state.backend, { actions.selectBackend(it.id) }, { it.name })
        RunsOn(state.backend?.name)
    }
    draft.validation?.let { NoticeCard(it, tone = NoticeTone.WARNING) }

    SheetLabel("run")
    ParamControls(w.params, state.values, actions::setValue, shell.assets)
    Row(
        Modifier.fillMaxWidth().toggleable(state.randomizeSeed, role = Role.Switch, onValueChange = actions::setRandomizeSeed),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("New seed each run", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = state.randomizeSeed, onCheckedChange = null)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!draft.saved) OutlinedButton(onClick = actions::saveDraft, Modifier.weight(1f)) { Text("Save") }
        Button(onClick = actions::run, Modifier.weight(1f), enabled = state.backend != null) { Text("Run") }
    }
    val outputs = state.jobIds.mapNotNull { id -> shell.jobs.firstOrNull { it.id == id } }.filter { it.recipe.workflowId == w.id }.flatMap { it.outputs }
        .mapNotNull { id -> shell.assets.firstOrNull { it.id == id } }
    if (outputs.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            outputs.reversed().forEach { a -> AssetThumb(a, Modifier.size(72.dp).clickable { onOpenAsset(a) }) }
        }
    }

    SheetLabel("parameters · review")
    Caption("rename what you see, move up, hide under more, or remove")
    val labels = remember(w.id) { mutableStateMapOf<String, String>() }
    w.params.forEach { p ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Field(labels[p.name] ?: p.label, { labels[p.name] = it }, Modifier.fillMaxWidth(), label = "${p.name} · ${p.type.name.lowercase()}${p.role?.let { " · $it" }.orEmpty()}", singleLine = true)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels[p.name]?.takeIf { it != p.label }?.let { l -> ActionChip("rename", onClick = { actions.rename(p.name, l); labels.remove(p.name) }) }
                ActionChip("↑", onClick = { actions.moveUp(p.name) }, Modifier.semantics { contentDescription = "Move ${p.label} up" })
                PickChip("Advanced", selected = p.advanced, onClick = { actions.toggleAdvanced(p.name) })
                ActionChip("remove", onClick = { actions.remove(p.name) })
            }
        }
    }
    if (draft.exposable.isNotEmpty()) {
        var filter by rememberSaveable { mutableStateOf("") }
        SheetLabel("expose an input · ${draft.exposable.size}")
        SearchField(filter, { filter = it }, "Filter by node or input", Modifier.fillMaxWidth())
        draft.exposable.filter { filter.isBlank() || "${it.nodeTitle} ${it.input}".contains(filter, ignoreCase = true) }.take(40).forEach { e ->
            ListItem(
                headlineContent = { Text("${e.nodeTitle} · ${e.input}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${e.type.name.lowercase()} = ${e.value.content.take(40)}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Text("+ Expose", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                colors = clearListItem(),
                modifier = Modifier.clickable { actions.expose(e, "${e.nodeTitle} ${e.input.replace('_', ' ')}") },
            )
        }
    }
    if (draft.saved) {
        OutlinedButton(
            onClick = { actions.deleteWorkflow(w.id) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Delete workflow") }
    }
}

@Composable
private fun Pipelines(state: WorkflowsState, shell: StudioShellState, actions: WorkflowsActions) {
    SheetLabel("built-in pipelines")
    Caption("multi-step recipes on this phone's engines; every step's asset lands in the project")
    state.pipelines.forEach { p -> PipelineCard(p, shell, actions) }
    state.pipelineRuns.reversed().forEach { r ->
        Caption(
            "${r.name} · " + when {
                !r.finished -> "${r.done} step${if (r.done == 1) "" else "s"} done…"
                r.error != null -> "failed: ${r.error}"
                else -> "done · ${r.assets.size} asset${if (r.assets.size == 1) "" else "s"}"
            },
            color = if (r.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PipelineCard(pipeline: Pipeline, shell: StudioShellState, actions: WorkflowsActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    val vars = remember(pipeline.id) { mutableStateMapOf<String, String>() }
    var input by rememberSaveable { mutableStateOf<String?>(null) }
    SectionCard(title = pipeline.name, trailing = { TextButton(onClick = { open = !open }) { Text(if (open) "Close" else "Open") } }) {
        Caption(pipeline.description)
        if (open) {
            pipeline.variables.forEach { v ->
                Field(
                    vars[v.name] ?: v.default, { vars[v.name] = it }, Modifier.fillMaxWidth(), label = v.label + if (v.required) "" else " (optional)",
                    minLines = if (v.multiline) 2 else 1, maxLines = if (v.multiline) 6 else 1, singleLine = !v.multiline,
                )
            }
            if (pipeline.input != null) {
                val type = pipeline.input ?: AssetType.IMAGE
                val choices = shell.assets.filter { it.type == type }.take(20)
                Caption(if (choices.isEmpty()) "needs a ${type.name.lowercase()} in this project" else "input")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { a ->
                        AssetThumb(
                            a,
                            Modifier.size(56.dp).clickable { input = a.id }.then(
                                if (input == a.id) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.small) else Modifier,
                            ),
                        )
                    }
                }
            }
            Button(onClick = { actions.runPipeline(pipeline, vars.toMap(), input) }, Modifier.fillMaxWidth()) { Text("Run") }
        }
    }
}


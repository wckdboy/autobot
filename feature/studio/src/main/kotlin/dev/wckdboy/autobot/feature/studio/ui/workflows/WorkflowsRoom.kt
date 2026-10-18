package dev.wckdboy.autobot.feature.studio.ui.workflows

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
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
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Panel
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.SelectField
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.motion.inkStagger
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
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

@Composable
private fun Backends(state: WorkflowsState, actions: WorkflowsActions, onEdit: (ComfyBackendConfig) -> Unit, onAdd: (String) -> Unit) {
    SectionLabel("comfyui backends", Modifier.semantics { heading() })
    if (state.loaded && state.backends.isEmpty()) {
        EmptyRoom(
            "workflows run on your pc",
            "ComfyUI workflows (video with LTX-Video / Wan, audio with Stable Audio / ACE-Step, ControlNet, face and detail passes) run on a PC " +
                "with a GPU. Run ComfyUI there, then reach it over https (a reverse proxy or a tailnet name), or over loopback through a tunnel. " +
                "Cleartext http only works to loopback. Requests follow your network mode, kill switch and route, and land in the audit log.",
        )
    }
    state.backends.forEach { b ->
        val result = state.tests[b.id]
        Panel(
            title = b.name,
            accent = if (b.id == state.backend?.id) MaterialTheme.colorScheme.primary else AutobotTheme.inks.cyan,
            trailing = { Tag(if (b.isLoopback) "loopback" else "network", accent = if (b.isLoopback) MaterialTheme.colorScheme.primary else AutobotTheme.inks.amber) },
        ) {
            Text(b.baseUrl, Modifier.clickable { onEdit(b) }, style = AutobotTheme.styles.codeBlock, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Test", { actions.testBackend(b) }, busy = state.testing == b.id, enabled = state.testing == null)
                GhostButton("Edit", { onEdit(b) })
                if (b.id != state.backend?.id) Tag("use", onClick = { actions.selectBackend(b.id) })
            }
            result?.let { MicroLabel(it, color = if (it.startsWith("ok")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioSettings.PRESETS.forEach { url -> Tag("+ $url", accent = MaterialTheme.colorScheme.primary, onClick = { onAdd(url) }) }
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
            MicroLabel(if (isNew) "// new comfyui backend" else "// edit comfyui backend", color = MaterialTheme.colorScheme.primary)
            ConsoleTextField(name, { name = it }, Modifier.fillMaxWidth(), label = "Name", singleLine = true)
            ConsoleTextField(
                url, { url = it }, Modifier.fillMaxWidth(), label = "Base URL", singleLine = true, mono = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
            if (draft.cleartextRemote) {
                MicroLabel("cleartext http is only allowed to loopback — use https (a reverse proxy or tailnet name)", color = MaterialTheme.colorScheme.error)
            } else {
                MicroLabel(if (draft.isLoopback) "loopback: keeps working in Offline mode (a tunnel to your pc)" else "progress uses wss:// on the same host")
            }
            SelectField("Route", routing.name.lowercase(), RouteOverride.entries, { routing = it }, optionLabel = { it.name.lowercase() })
            ConsoleTextField(header, { header = it }, Modifier.fillMaxWidth(), label = "Auth header (optional)", placeholder = "Authorization", singleLine = true, mono = true)
            if (header.isNotBlank()) {
                ConsoleTextField(
                    secret, { secret = it; clear = false }, Modifier.fillMaxWidth(),
                    label = if (initial.authRef != null) "Value (stored; type to replace)" else "Value",
                    singleLine = true, mono = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                )
                if (initial.authRef != null) Tag(if (clear) "will remove the stored value" else "remove stored value", onClick = { clear = !clear })
                MicroLabel("the value is kept in the encrypted secret store and never exported")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Test", { onTest(draft) }, busy = testing, enabled = url.isNotBlank() && !draft.cleartextRemote)
                testResult?.let { MicroLabel(it, Modifier.weight(1f), color = if (it.startsWith("ok")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) GhostButton("Delete", onDelete, accent = MaterialTheme.colorScheme.error)
                Spacer(Modifier.weight(1f))
                PrimaryButton("Save", { onSave(draft, secret, clear) }, enabled = url.isNotBlank() && !draft.cleartextRemote)
            }
        }
    }
}

@Composable
private fun Import(state: WorkflowsState, actions: WorkflowsActions) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(actions::importFile) }
    SectionLabel("import a workflow", Modifier.semantics { heading() })
    MicroLabel("api format: in comfyui, workflow → export (api). share a .json to autobot, pick a file, or paste it")
    ConsoleTextField(state.importName, actions::setImportName, Modifier.fillMaxWidth(), label = "Name", singleLine = true)
    ConsoleTextField(state.importText, actions::setImportText, Modifier.fillMaxWidth(), label = "Workflow JSON", minLines = 3, maxLines = 8, mono = true)
    state.importError?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = if (it == WorkflowImport.UI_FORMAT_MESSAGE) AutobotTheme.inks.amber else MaterialTheme.colorScheme.error)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        GhostButton("Choose file", { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.weight(1f))
        PrimaryButton("Review", actions::review, Modifier.weight(1f), enabled = state.importText.isNotBlank())
    }
}

@Composable
private fun Saved(state: WorkflowsState, actions: WorkflowsActions) {
    SectionLabel("saved · ${state.workflows.size}", Modifier.semantics { heading() })
    if (state.workflows.isEmpty()) MicroLabel("nothing saved yet")
    state.workflows.forEachIndexed { i, w ->
        Row(Modifier.fillMaxWidth().clickable { actions.open(w) }.padding(vertical = 8.dp).inkStagger(i), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(w.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MicroLabel("${counted(w.params.size, "param")} · v${w.version}")
            }
            Tag(w.output.name.lowercase(), accent = MaterialTheme.colorScheme.primary)
        }
    }
}

/** A workflow open: where it runs, its validation, its controls, and the schema review. */
@Composable
private fun WorkflowDetail(state: WorkflowsState, shell: StudioShellState, actions: WorkflowsActions, onOpenAsset: (StudioAsset) -> Unit) {
    val draft = state.draft ?: return
    val w = draft.workflow
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(w.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Tag("close", onClick = actions::close)
    }
    MicroLabel("makes ${w.output.name.lowercase()} · v${w.version}" + if (!draft.saved) " · not saved yet" else "")
    if (state.backends.isEmpty()) {
        MicroLabel("add a comfyui backend to run it", color = MaterialTheme.colorScheme.error)
    } else {
        ChipRow(state.backends, state.backend, { actions.selectBackend(it.id) }, { it.name })
        RunsOn(state.backend?.name)
    }
    draft.validation?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AutobotTheme.inks.amber) }

    SectionLabel("run")
    ParamControls(w.params, state.values, actions::setValue, shell.assets)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("New seed each run", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(checked = state.randomizeSeed, onCheckedChange = actions::setRandomizeSeed)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!draft.saved) GhostButton("Save", actions::saveDraft, Modifier.weight(1f))
        PrimaryButton("Run", actions::run, Modifier.weight(1f), enabled = state.backend != null)
    }
    val outputs = state.jobIds.mapNotNull { id -> shell.jobs.firstOrNull { it.id == id } }.filter { it.recipe.workflowId == w.id }.flatMap { it.outputs }
        .mapNotNull { id -> shell.assets.firstOrNull { it.id == id } }
    if (outputs.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            outputs.reversed().forEach { a -> AssetThumb(a, Modifier.size(72.dp).clickable { onOpenAsset(a) }) }
        }
    }

    SectionLabel("parameters · review")
    MicroLabel("rename what you see, move up, hide under more, or remove")
    val labels = remember(w.id) { mutableStateMapOf<String, String>() }
    w.params.forEach { p ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ConsoleTextField(labels[p.name] ?: p.label, { labels[p.name] = it }, Modifier.fillMaxWidth(), label = "${p.name} · ${p.type.name.lowercase()}${p.role?.let { " · $it" }.orEmpty()}", singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                labels[p.name]?.takeIf { it != p.label }?.let { l -> Tag("rename", accent = MaterialTheme.colorScheme.primary, onClick = { actions.rename(p.name, l); labels.remove(p.name) }) }
                Tag("↑", onClick = { actions.moveUp(p.name) })
                Tag(if (p.advanced) "advanced ✓" else "advanced", onClick = { actions.toggleAdvanced(p.name) })
                Tag("remove", onClick = { actions.remove(p.name) })
            }
        }
    }
    if (draft.exposable.isNotEmpty()) {
        var filter by rememberSaveable { mutableStateOf("") }
        SectionLabel("expose an input · ${draft.exposable.size}")
        ConsoleTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = "filter by node or input", singleLine = true, mono = true)
        draft.exposable.filter { filter.isBlank() || "${it.nodeTitle} ${it.input}".contains(filter, ignoreCase = true) }.take(40).forEach { e ->
            Row(Modifier.fillMaxWidth().clickable { actions.expose(e, "${e.nodeTitle} ${e.input.replace('_', ' ')}") }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${e.nodeTitle} · ${e.input}", style = AutobotTheme.styles.code, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    MicroLabel("${e.type.name.lowercase()} = ${e.value.content.take(40)}")
                }
                Tag("+ expose", accent = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (draft.saved) GhostButton("Delete workflow", { actions.deleteWorkflow(w.id) }, accent = MaterialTheme.colorScheme.error)
}

@Composable
private fun Pipelines(state: WorkflowsState, shell: StudioShellState, actions: WorkflowsActions) {
    SectionLabel("built-in pipelines", Modifier.semantics { heading() })
    MicroLabel("multi-step recipes on this phone's engines; every step's asset lands in the project")
    state.pipelines.forEach { p -> PipelineCard(p, shell, actions) }
    state.pipelineRuns.reversed().forEach { r ->
        MicroLabel(
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
    Panel(title = pipeline.name, trailing = { Tag(if (open) "close" else "open", onClick = { open = !open }) }) {
        MicroLabel(pipeline.description)
        if (open) {
            pipeline.variables.forEach { v ->
                ConsoleTextField(
                    vars[v.name] ?: v.default, { vars[v.name] = it }, Modifier.fillMaxWidth(), label = v.label + if (v.required) "" else " (optional)",
                    minLines = if (v.multiline) 2 else 1, maxLines = if (v.multiline) 6 else 1, singleLine = !v.multiline,
                )
            }
            if (pipeline.input != null) {
                val type = pipeline.input ?: AssetType.IMAGE
                val choices = shell.assets.filter { it.type == type }.take(20)
                MicroLabel(if (choices.isEmpty()) "needs a ${type.name.lowercase()} in this project" else "input")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { a ->
                        AssetThumb(
                            a,
                            Modifier.size(56.dp).clickable { input = a.id }.then(
                                if (input == a.id) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier,
                            ),
                        )
                    }
                }
            }
            PrimaryButton("Run", { actions.runPipeline(pipeline, vars.toMap(), input) }, Modifier.fillMaxWidth())
        }
    }
}


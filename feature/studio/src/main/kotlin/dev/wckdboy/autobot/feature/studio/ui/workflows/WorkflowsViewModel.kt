package dev.wckdboy.autobot.feature.studio.ui.workflows

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.diagnostics.userMessage
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.feature.studio.StudioInbox
import dev.wckdboy.autobot.feature.studio.WorkflowShare
import dev.wckdboy.autobot.feature.studio.engine.AuthUpdate
import dev.wckdboy.autobot.feature.studio.engine.ComfyBackendConfig
import dev.wckdboy.autobot.feature.studio.engine.PipelineRun
import dev.wckdboy.autobot.feature.studio.engine.StudioHost
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.BuiltInPipelines
import dev.wckdboy.autobot.shared.studio.ExposableInput
import dev.wckdboy.autobot.shared.studio.ParamValue
import dev.wckdboy.autobot.shared.studio.Pipeline
import dev.wckdboy.autobot.shared.studio.PipelineException
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioWorkflow
import dev.wckdboy.autobot.shared.studio.WorkflowException
import dev.wckdboy.autobot.shared.studio.WorkflowImport
import dev.wckdboy.autobot.shared.studio.WorkflowSchema
import dev.wckdboy.autobot.shared.studio.WorkflowValidator
import dev.wckdboy.autobot.shared.studio.comfy.ObjectInfo
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A workflow being imported: reviewed (params renamed / exposed / removed) before it is saved. */
@Immutable
data class WorkflowDraft(val workflow: StudioWorkflow, val exposable: List<ExposableInput>, val validation: String?, val saved: Boolean = false)

@Immutable
data class WorkflowsState(
    val loaded: Boolean = false,
    val backends: List<ComfyBackendConfig> = emptyList(),
    val testing: String? = null,
    val tests: Map<String, String> = emptyMap(),
    val workflows: List<StudioWorkflow> = emptyList(),
    /** The workflow open for review / running (a draft while importing). */
    val draft: WorkflowDraft? = null,
    val backendId: String? = null,
    val values: Map<String, ParamValue> = emptyMap(),
    val randomizeSeed: Boolean = true,
    val importText: String = "",
    val importName: String = "",
    val importError: String? = null,
    val pipelineRuns: List<PipelineRun> = emptyList(),
    val jobIds: List<String> = emptyList(),
    val message: String? = null,
) {
    val backend: ComfyBackendConfig? get() = backends.firstOrNull { it.id == backendId } ?: backends.firstOrNull()
    val pipelines: List<Pipeline> get() = BuiltInPipelines.all
}

interface WorkflowsActions {
    fun saveBackend(config: ComfyBackendConfig, auth: AuthUpdate)
    fun deleteBackend(id: String)
    fun testBackend(config: ComfyBackendConfig)
    fun selectBackend(id: String)
    fun setImportText(text: String)
    fun setImportName(name: String)
    fun importFile(uri: Uri)
    fun review()
    fun open(workflow: StudioWorkflow)
    fun close()
    fun rename(name: String, label: String)
    fun remove(name: String)
    fun moveUp(name: String)
    fun expose(input: ExposableInput, label: String)
    fun toggleAdvanced(name: String)
    fun saveDraft()
    fun deleteWorkflow(id: String)
    fun setValue(name: String, value: ParamValue)
    fun setRandomizeSeed(enabled: Boolean)
    fun run()
    fun runPipeline(pipeline: Pipeline, vars: Map<String, String>, input: String?)
    fun dismiss()
}

/**
 * The Workflows room: ComfyUI backends (same https / loopback rules as every backend, through the
 * kill switch and route), importing API-format workflows (paste, file, share sheet) with the
 * detected parameters to review, validation against the server's `/object_info`, running a saved
 * workflow from controls rendered from its schema, and the built-in multi-step pipelines.
 */
@HiltViewModel
class WorkflowsViewModel @Inject constructor(
    application: Application,
    private val host: StudioHost,
    private val inbox: StudioInbox,
) : AndroidViewModel(application), WorkflowsActions {
    private val local = MutableStateFlow(WorkflowsState())

    /** Work on storage or an engine: a failure ("Storage is full") is shown, never a crash. */
    private fun safely(block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launchSafely({ e -> local.update { it.copy(message = userMessage(e)) } }, block)

    private val objectInfo = HashMap<String, ObjectInfo>()

    val state: StateFlow<WorkflowsState> = combine(local, host.settings.comfyBackends, host.store.observeWorkflows(), host.pipelineRuns) { l, b, w, runs ->
        l.copy(loaded = true, backends = b, workflows = w, pipelineRuns = runs, backendId = l.backendId ?: b.firstOrNull()?.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkflowsState())

    init {
        safely {
            inbox.pending.collect { share ->
                if (share != null) takeShare()
            }
        }
    }

    private fun takeShare() {
        when (val share = inbox.take()) {
            is WorkflowShare.Text -> local.update { it.copy(importText = share.json, importError = null) }
            is WorkflowShare.File -> importFile(Uri.parse(share.uri))
            null -> Unit
        }
    }

    // ---------------------------------------------------------------------------------------
    // Backends

    override fun saveBackend(config: ComfyBackendConfig, auth: AuthUpdate) {
        viewModelScope.launchSafely({ e -> local.update { it.copy(message = "Couldn't save ${config.name}: ${userMessage(e)}") } }) {
            val saved = host.settings.saveComfy(config, auth)
            objectInfo.remove(saved.id)
            local.update { it.copy(backendId = saved.id) }
        }
    }

    override fun deleteBackend(id: String) {
        viewModelScope.launchSafely({ e -> local.update { it.copy(message = "Couldn't delete the server: ${userMessage(e)}") } }) { host.settings.deleteComfy(id) }
    }

    /** Asks the server for its version, GPU and node count — through the network policy. */
    override fun testBackend(config: ComfyBackendConfig) {
        local.update { it.copy(testing = config.id) }
        safely {
            val result = try {
                val client = host.handle(config).client
                val stats = client.systemStats()
                val info = client.objectInfo().also { objectInfo[config.id] = it }
                listOfNotNull(
                    "ok",
                    stats.comfyVersion?.let { "comfyui $it" },
                    stats.devices.firstOrNull()?.let { d -> d.name.substringBefore(" :").take(28) + if (d.vramTotal > 0) " · ${d.vramTotal / (1 shl 30)} GB" else "" },
                    counted(info.nodes.size, "node"),
                ).joinToString(" · ")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "fail · ${userMessage(e)}"
            }
            local.update { it.copy(testing = null, tests = it.tests + (config.id to result)) }
        }
    }

    override fun selectBackend(id: String) {
        local.update { it.copy(backendId = id) }
        local.value.draft?.let { d -> safely { validate(d.workflow) } }
    }

    /** Why the selected server's node list could not be read (unreachable, blocked by the kill switch). */
    private var infoFailure: String? = null

    private suspend fun info(): ObjectInfo? {
        val backend = state.value.backend ?: return null
        objectInfo[backend.id]?.let { return it }
        return try {
            (host.comfy.value[backend.id]?.client?.objectInfo() ?: host.handle(backend).client.objectInfo()).also {
                objectInfo[backend.id] = it
                infoFailure = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            infoFailure = userMessage(e)
            null
        }
    }

    // ---------------------------------------------------------------------------------------
    // Import and review

    override fun setImportText(text: String) = local.update { it.copy(importText = text, importError = null) }
    override fun setImportName(name: String) = local.update { it.copy(importName = name) }

    override fun importFile(uri: Uri) {
        safely {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytes(MAX_WORKFLOW_BYTES + 1)
                        if (bytes.size > MAX_WORKFLOW_BYTES) null else bytes.decodeToString()
                    }
                }.getOrNull()
            }
            if (text == null) {
                local.update { it.copy(importError = "Couldn't read that file (workflows are JSON, at most 8 MB)") }
                return@safely
            }
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty()
            local.update { it.copy(importText = text, importName = it.importName.ifBlank { name }, importError = null) }
            review()
        }
    }

    override fun review() {
        val s = local.value
        safely {
            val info = info()
            try {
                val workflow = WorkflowImport.create(
                    name = s.importName.ifBlank { "Workflow" },
                    json = s.importText,
                    info = info,
                    now = System.currentTimeMillis(),
                    backendId = state.value.backend?.id,
                )
                local.update { it.copy(draft = draftOf(workflow, info, saved = false), values = emptyMap(), importError = null) }
            } catch (e: WorkflowException) {
                local.update { it.copy(importError = e.message) }
            }
        }
    }

    private fun draftOf(workflow: StudioWorkflow, info: ObjectInfo?, saved: Boolean): WorkflowDraft {
        val refreshed = info?.let { WorkflowSchema.refreshOptions(workflow, it) } ?: workflow
        return WorkflowDraft(
            refreshed,
            WorkflowSchema.exposable(refreshed, info),
            info?.let { WorkflowValidator.validate(refreshed.graph, it).message } ?: if (state.value.backends.isEmpty()) {
                null
            } else {
                "Not checked: ${state.value.backend?.name} didn't answer" + (infoFailure?.let { " ($it)" } ?: "")
            },
            saved,
        )
    }

    private suspend fun validate(workflow: StudioWorkflow) {
        val info = info()
        local.update { s -> s.draft?.takeIf { it.workflow.id == workflow.id }?.let { s.copy(draft = draftOf(it.workflow, info, it.saved)) } ?: s }
    }

    override fun open(workflow: StudioWorkflow) {
        local.update { it.copy(draft = WorkflowDraft(workflow, emptyList(), null, saved = true), values = emptyMap()) }
        safely { validate(workflow) }
    }

    override fun close() = local.update { it.copy(draft = null, values = emptyMap()) }

    private fun editDraft(change: (StudioWorkflow) -> StudioWorkflow) {
        val draft = local.value.draft ?: return
        val now = System.currentTimeMillis()
        val next = try {
            change(draft.workflow).copy(updatedAt = now)
        } catch (e: Exception) {
            local.update { it.copy(message = e.message) }
            return
        }
        local.update { it.copy(draft = draft.copy(workflow = next, exposable = WorkflowSchema.exposable(next, objectInfo[it.backend?.id]))) }
        if (draft.saved) safely { host.store.putWorkflow(next) }
    }

    override fun rename(name: String, label: String) = editDraft { WorkflowSchema.rename(it, name, label) }
    override fun remove(name: String) = editDraft { WorkflowSchema.remove(it, name) }
    override fun moveUp(name: String) = editDraft { w -> WorkflowSchema.move(w, name, (w.params.indexOfFirst { it.name == name } - 1).coerceAtLeast(0)) }
    override fun expose(input: ExposableInput, label: String) = editDraft { WorkflowSchema.expose(it, input.nodeId, input.input, label, objectInfo[state.value.backend?.id]) }
    override fun toggleAdvanced(name: String) = editDraft { w -> w.param(name)?.let { WorkflowSchema.update(w, it.copy(advanced = !it.advanced)) } ?: w }

    override fun saveDraft() {
        val draft = local.value.draft ?: return
        safely {
            host.store.putWorkflow(draft.workflow)
            local.update { it.copy(draft = draft.copy(saved = true), importText = "", importName = "", message = "Saved \"${draft.workflow.name}\"") }
        }
    }

    override fun deleteWorkflow(id: String) {
        safely {
            host.store.deleteWorkflow(id)
            local.update { s -> if (s.draft?.workflow?.id == id) s.copy(draft = null) else s }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Run

    override fun setValue(name: String, value: ParamValue) = local.update { it.copy(values = it.values + (name to value)) }
    override fun setRandomizeSeed(enabled: Boolean) = local.update { it.copy(randomizeSeed = enabled) }

    override fun run() {
        val s = state.value
        val draft = s.draft ?: return
        val backend = s.backend ?: run {
            local.update { it.copy(message = "Add a ComfyUI backend first") }
            return
        }
        safely {
            try {
                if (!draft.saved) host.store.putWorkflow(draft.workflow)
                val job = host.submitWorkflow(draft.workflow, s.values, backend.id, s.randomizeSeed)
                local.update { it.copy(jobIds = (it.jobIds + job.id).takeLast(32), draft = it.draft?.copy(saved = true), message = "Queued on ${backend.name}") }
            } catch (e: WorkflowException) {
                local.update { it.copy(message = e.message) }
            }
        }
    }

    override fun runPipeline(pipeline: Pipeline, vars: Map<String, String>, input: String?) {
        try {
            host.pipelines.check(pipeline, vars, listOfNotNull(input))
        } catch (e: PipelineException) {
            local.update { it.copy(message = e.message) }
            return
        }
        host.runPipeline(pipeline, vars, listOfNotNull(input))
        local.update { it.copy(message = "${pipeline.name}: queued") }
    }

    override fun dismiss() = local.update { it.copy(message = null, importError = null) }

    /** Jobs of a run started here (for the result list). */
    fun jobsOf(all: List<StudioJob>): List<StudioJob> = local.value.jobIds.mapNotNull { id -> all.firstOrNull { it.id == id } }

    fun outputType(workflow: StudioWorkflow): AssetType = workflow.output

    fun newBackend(url: String) = ComfyBackendConfig(host.settings.newId(), "ComfyUI", url)

    fun auth(value: String): AuthUpdate = if (value.isBlank()) AuthUpdate.Keep else AuthUpdate.Set(Secret(value.trim()))

    companion object {
        private const val MAX_WORKFLOW_BYTES = 8 * 1024 * 1024
    }
}

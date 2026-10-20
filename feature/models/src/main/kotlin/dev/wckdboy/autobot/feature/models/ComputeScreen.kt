package dev.wckdboy.autobot.feature.models

import dev.wckdboy.autobot.core.designsystem.component.ActionText
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.diagnostics.ErrorLog
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.BigReadout
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Panel
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.diffusion.ComputeBench
import dev.wckdboy.autobot.core.diffusion.ComputeDevices
import dev.wckdboy.autobot.core.models.Backend
import dev.wckdboy.autobot.core.models.Benchmark
import dev.wckdboy.autobot.core.models.ComputeProfile
import dev.wckdboy.autobot.core.models.DeviceProfile
import dev.wckdboy.autobot.core.models.DeviceProfiler
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.core.models.SpeculativeBenchmark
import dev.wckdboy.autobot.core.models.SpeculativeDrafts
import dev.wckdboy.autobot.core.models.SpeculativeMode
import dev.wckdboy.autobot.core.models.SystemModel
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.providers.remote.SystemAvailability
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.shared.diagnostics.AcceleratorStatus
import dev.wckdboy.autobot.shared.diagnostics.BenchmarkLine
import dev.wckdboy.autobot.shared.diagnostics.DeviceInfo
import dev.wckdboy.autobot.shared.diagnostics.DiagnosticsReport
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
data class ComputeModel(
    val model: InstalledModel,
    /** Newest successful result per backend. */
    val results: Map<Backend, Benchmark>,
    val lastError: String?,
    /** What AUTO resolves to right now. */
    val fastest: Backend?,
    /** The newest speculative decoding benchmark (this session), if any. */
    val speculative: SpeculativeBenchmark? = null,
    /** Draft models that speed this model up, by title; empty: none in the catalog. */
    val drafts: List<String> = emptyList(),
    /** Whether one of [drafts] is installed. */
    val draftInstalled: Boolean = false,
)

@Immutable
data class ComputeUiState(
    val device: DeviceProfile? = null,
    val devices: ComputeDevices? = null,
    val models: List<ComputeModel> = emptyList(),
    val running: String? = null,
    val speculativeMode: SpeculativeMode = SpeculativeMode.AUTO,
)

@HiltViewModel
class ComputeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: ModelLibrary,
    private val profile: ComputeProfile,
    private val bench: ComputeBench,
    private val network: NetworkPolicy,
    private val systemModel: SystemModel,
    private val errorLog: ErrorLog,
    private val settings: SettingsRepository,
    profiler: DeviceProfiler,
) : ViewModel() {
    /** The OS's model; null in builds without one (the default, GMS-free build). */
    val system: StateFlow<SystemModelStatus?> = systemModel.status

    private val devices = MutableStateFlow<ComputeDevices?>(null)
    private val running = MutableStateFlow<String?>(null)
    private val device = MutableStateFlow<DeviceProfile?>(null)

    private val speculation = combine(profile.speculative, settings.settings.map { it.speculativeMode }) { results, mode -> results to mode }

    val uiState: StateFlow<ComputeUiState> = combine(library.models, profile.benchmarks, devices, running, combine(device, speculation) { p, s -> p to s }) { models, results, d, r, (p, spec) ->
        val (specResults, mode) = spec
        val runnable = models.filter { it.isReady && (it.isLocalChat || (it.engine == EngineKind.NPU && it.manifest.npu?.arch != "upscaler")) }
        val ready = models.filter { it.isLocalChat }.associateBy { it.id }
        val titles = models.associate { it.id to it.title }
        ComputeUiState(
            device = p,
            devices = d,
            running = r,
            speculativeMode = mode,
            models = runnable.map { m ->
                val mine = results.filter { it.modelId == m.id }
                val drafts = SpeculativeDrafts.draftsFor(m.id)
                ComputeModel(
                    model = m,
                    results = mine.filter { it.ok }.groupBy { it.backend }.mapValues { (_, runs) -> runs.maxBy { it.createdAt } },
                    lastError = mine.maxByOrNull { it.createdAt }?.error,
                    fastest = ComputeProfile.fastest(mine),
                    speculative = specResults[m.id],
                    drafts = drafts.map { id -> titles[id] ?: draftTitle(id) },
                    draftInstalled = drafts.any { it in ready },
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComputeUiState())

    fun setSpeculativeMode(mode: SpeculativeMode) {
        viewModelScope.launch { settings.setSpeculativeMode(mode) }
    }

    private var devicesJob: Job? = null

    init {
        // /proc/cpuinfo, StatFs and the engine's device enumeration (a binder call into native code) block: never on Main.
        viewModelScope.launch { device.value = withContext(Dispatchers.IO) { runCatching { profiler.profile() }.getOrNull() } }
        // No refresh() here: ComputeRoute refreshes on every visit, the first one included.
    }

    fun refresh() {
        if (devicesJob?.isActive != true) {
            devicesJob = viewModelScope.launch {
                devices.value = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(DEVICES_TIMEOUT_MS) { runCatching { bench.devices() }.getOrNull() }
                } ?: ComputeDevices()
            }
        }
        viewModelScope.launch { runCatching { systemModel.refresh() } }
    }

    /** Asks the OS to fetch its model (Gemini Nano is downloaded by AICore, not by Autobot). */
    fun downloadSystemModel() {
        val llm = systemModel.llm ?: return
        viewModelScope.launch {
            runCatching { llm.requestDownload() }
            systemModel.refresh()
        }
    }

    fun setBackend(model: InstalledModel, backend: Backend) {
        viewModelScope.launch { profile.setBackend(model.id, backend) }
    }

    /**
     * Builds the report after a live NPU check: the engine status is refreshed and, when the HTP
     * opened, the smallest installed QNN binary is loaded once, so one tap on the user's phone tells
     * whether (and why not) models load there.
     */
    fun report(onReady: (String) -> Unit) {
        if (running.value != null) return
        viewModelScope.launch {
            running.value = "testing the npu"
            val probe = try {
                val d = withContext(Dispatchers.IO) { withTimeoutOrNull(DEVICES_TIMEOUT_MS) { runCatching { bench.devices() }.getOrNull() } }
                if (d != null) devices.value = d
                if (d?.npu != null) {
                    withTimeoutOrNull(PROBE_TIMEOUT_MS) { bench.npuProbe(d.npuArch) } ?: "context load test: timed out"
                } else {
                    null
                }
            } finally {
                running.value = null
            }
            onReady(report(probe))
        }
    }

    /** The shared diagnostics report for what this screen shows (no prompts, paths or identifiers). */
    fun report(npuProbe: String? = null): String {
        val state = uiState.value
        val p = state.device
        val d = state.devices
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return DiagnosticsReport(
            app = "Autobot ${version?.versionName ?: "?"} (${version?.longVersionCode ?: 0})",
            os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            device = DeviceInfo(
                label = "${Build.MANUFACTURER} ${Build.MODEL}",
                soc = p?.label?.takeIf { p.socModel.isNotBlank() },
                accelerator = p?.htpArch?.let { "Hexagon $it" },
                ramBytes = p?.totalRamBytes ?: 0,
                freeStorageBytes = p?.freeStorageBytes ?: 0,
                cores = p?.cores ?: 0,
                cpuFeatures = p?.cpuFeatures?.filter { it in REPORTED_CPU_FEATURES }.orEmpty(),
            ),
            engineDevices = d?.engineDevices.orEmpty(),
            imageAccelerator = d?.let { AcceleratorStatus("QNN HTP", it.npu != null, it.npu ?: it.npuError) },
            imageAcceleratorDetails = d?.npuDetails.orEmpty() + listOfNotNull(npuProbe),
            benchmarks = state.models.flatMap { cm ->
                cm.results.values.map { b ->
                    BenchmarkLine(cm.model.title, b.backend.label, b.promptTps, b.generationTps, b.stepMs, b.loadMs)
                } + listOfNotNull(
                    cm.speculative?.let { s ->
                        BenchmarkLine(
                            cm.model.title, "${s.backend.label} + draft", generationTps = s.speculativeTps.takeIf { s.error == null },
                            error = s.error, draft = s.draftTitle, acceptance = s.acceptance.takeIf { s.error == null }, speedup = s.speedup.takeIf { s.error == null },
                        )
                    },
                    cm.lastError?.let { BenchmarkLine(cm.model.title, "last run", error = it) },
                )
            },
            errors = listOfNotNull(d?.npuError) + errorLog.entries.value,
            // The kind only (no proxy host); not ::class.simpleName, which R8 renames in release builds.
            networkMode = NetworkMode.encode(network.mode.value).substringBefore(':'),
            systemModel = system.value?.let { s ->
                val detail = when (s.availability) {
                    SystemAvailability.AVAILABLE -> null
                    SystemAvailability.UNAVAILABLE -> s.detail
                    else -> s.summary
                }
                AcceleratorStatus(s.title, s.usable, detail)
            },
        ).render()
    }

    fun benchmark(model: InstalledModel) {
        if (running.value != null) return
        viewModelScope.launch {
            val backends = devices.value?.llmBackends ?: setOf(Backend.CPU)
            running.value = "starting"
            try {
                bench.run(model, backends) { running.value = it }
            } finally {
                running.value = null
            }
        }
    }
}

@Composable
fun ComputeRoute(onBack: () -> Unit, openReport: Boolean = false, viewModel: ComputeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val system by viewModel.system.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf<String?>(null) }
    // Opened for its report (About → Copy diagnostics): build it once, not again on coming back.
    var reportRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.refresh()
        if (openReport && !reportRequested) {
            reportRequested = true
            viewModel.report { report = it }
        }
    }
    report?.let { text -> ReportDialog(text, onDismiss = { report = null }) }
    Scaffold(
        topBar = {
            AutobotTopBar(
                title = "Compute",
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    Tag(
                        if (state.running != null) "busy" else "Report",
                        Modifier.padding(end = 12.dp),
                        accent = MaterialTheme.colorScheme.onSurface,
                        onClick = { viewModel.report { report = it } }.takeIf { state.running == null },
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionLabel("this phone" + (state.device?.let { " · ${it.label}" } ?: ""))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DeviceCard("cpu", state.device?.let { "${it.cores} cores" }, true, Modifier.weight(1f))
                    DeviceCard("gpu", state.devices?.gpu ?: "none", state.devices?.gpu != null, Modifier.weight(1f))
                    DeviceCard("npu", state.devices?.npu ?: "none", state.devices?.npu != null, Modifier.weight(1f))
                }
                state.devices?.npuError?.let { MicroLabel("npu: $it", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error, maxLines = WRAP) }
                if (state.devices?.npu != null && Backend.NPU !in state.devices!!.llmBackends) {
                    MicroLabel("chat on the npu: the hexagon backend did not start on this phone · images still run on the npu", Modifier.padding(top = 6.dp), maxLines = WRAP)
                }
            }
            system?.let { s -> item { SystemModelRow(s, onDownload = viewModel::downloadSystemModel) } }
            item {
                state.running?.let {
                    MicroLabel("benchmarking · $it")
                    // The benchmark reports phases, not a fraction: the indeterminate ink bar.
                    InkProgressBar(null, Modifier.fillMaxWidth().padding(vertical = 6.dp).height(2.dp))
                }
                SectionLabel("models · auto picks the fastest measured backend", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.models.any { it.drafts.isNotEmpty() }) {
                item { SpeculativeRow(state.speculativeMode, viewModel::setSpeculativeMode) }
            }
            items(state.models, key = { it.model.id }) { cm -> ModelCompute(cm, state, viewModel) }
            if (state.models.isEmpty()) {
                item { MicroLabel("No runnable models yet. Get one in the Models tab.", maxLines = WRAP) }
            }
        }
    }
}

/** Speculative decoding: auto / on / off (shown when an installed model has a draft in the catalog). */
@Composable
private fun SpeculativeRow(mode: SpeculativeMode, onMode: (SpeculativeMode) -> Unit) {
    Panel(title = "speculative decoding") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Segmented(SpeculativeMode.entries, mode, onMode, { it.label }, Modifier.fillMaxWidth())
            MicroLabel(
                when (mode) {
                    SpeculativeMode.AUTO -> "a small model of the same family drafts, the chat model checks · same replies, faster · when the pair fits"
                    SpeculativeMode.ON -> "always draft when the pair fits at all, even when it does not pay"
                    SpeculativeMode.OFF -> "no draft model is loaded"
                },
                maxLines = WRAP,
            )
        }
    }
}

/** Shows the report before it is copied: the user sees exactly what they are about to share. */
@Composable
private fun ReportDialog(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnostics report") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MicroLabel("no prompts, file paths or identifiers · nothing is sent", maxLines = WRAP)
                Text(
                    text,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    style = AutobotTheme.styles.codeBlock,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(text))
                onDismiss()
            }) { ActionText("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { ActionText("Close") } },
    )
}

/** The OS's own chat model: used for "This phone" when no downloaded model can run. */
@Composable
private fun SystemModelRow(status: SystemModelStatus, onDownload: () -> Unit) {
    Panel(
        title = "system model · ${status.title}",
        accent = if (status.usable) MaterialTheme.colorScheme.primary else null,
        trailing = {
            if (status.availability == SystemAvailability.DOWNLOADABLE) {
                Tag("Download", accent = MaterialTheme.colorScheme.onSurface, onClick = onDownload)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(status.summary, style = MaterialTheme.typography.bodySmall)
            MicroLabel("fallback when no downloaded model can run · ~4k tokens, no tools", maxLines = WRAP)
            if (status.availability == SystemAvailability.DOWNLOADABLE) {
                MicroLabel("the download is done by the system (AICore), outside autobot's network controls", maxLines = WRAP)
            }
        }
    }
}

@Composable
private fun DeviceCard(label: String, value: String?, present: Boolean, modifier: Modifier = Modifier) {
    Panel(modifier, title = label) {
        // Still probing the device: an inline loader instead of a placeholder.
        if (value == null) InkLoader(Modifier.padding(vertical = 2.dp)) else Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = if (present) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCompute(cm: ComputeModel, state: ComputeUiState, actions: ComputeViewModel) {
    val m = cm.model
    val isText = m.engine == EngineKind.LLAMA
    Panel(title = m.title, trailing = {
        Tag(if (state.running != null) "busy" else "Benchmark", accent = MaterialTheme.colorScheme.onSurface, onClick = { actions.benchmark(m) }.takeIf { state.running == null })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isText) {
                val options = listOf(Backend.AUTO, Backend.CPU, Backend.GPU, Backend.NPU) +
                    listOfNotNull(Backend.NPU_CPU.takeIf { it in state.devices?.llmBackends.orEmpty() || m.backend == it })
                // Five segments leave no room for "auto · npu+cpu": what auto resolves to goes below.
                Segmented(options, m.backend, { actions.setBackend(m, it) }, { b ->
                    if (b == Backend.AUTO && cm.fastest != null && options.size <= 4) "auto · ${cm.fastest.label}" else b.label
                }, Modifier.fillMaxWidth())
                if (options.size > 4 && m.backend == Backend.AUTO && cm.fastest != null) MicroLabel("auto runs on ${cm.fastest.label}")
            } else {
                val pkg = m.manifest.npu
                MicroLabel(if (pkg?.runtime == "qnn") "runs on the npu (hexagon ${pkg.htpArch.orEmpty()})" else "runs on the gpu (opencl) or cpu")
            }
            if (cm.results.isNotEmpty()) {
                // Wraps: four text backends do not fit one row on a phone.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    cm.results.entries.sortedBy { it.key.ordinal }.forEach { (backend, r) ->
                        val value = if (isText) r.generationTps?.let { fmt(it) } ?: "–" else r.stepMs?.let { fmt(it / 1000) + "s" } ?: "–"
                        // Each backend's prompt speed under its own readout (one line for four got cut off).
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            BigReadout(backend.label + if (isText) " · tok/s" else " · step", value)
                            r.promptTps?.let { MicroLabel("prefill ${fmt(it)}") }
                        }
                    }
                }
            } else {
                MicroLabel("not measured yet")
            }
            if (isText && cm.drafts.isNotEmpty()) SpeculativeLine(cm, state.speculativeMode)
            cm.lastError?.let { MicroLabel("last run: $it", color = MaterialTheme.colorScheme.error, maxLines = WRAP) }
        }
    }
}

@Composable
private fun SpeculativeLine(cm: ComputeModel, mode: SpeculativeMode) {
    val s = cm.speculative
    val text = when {
        mode == SpeculativeMode.OFF -> "speculative: off"
        !cm.draftInstalled -> "speculative: install ${cm.drafts.first()} to speed this model up"
        s == null -> "speculative: benchmark to measure (draft ${cm.drafts.first()})"
        s.error != null -> "speculative: ${s.error}"
        else -> "speculative · ${fmt(s.speedup)}x · ${fmt(s.speculativeTps)} tok/s · ${(s.acceptance * 100).toInt()}% of drafts kept · ${s.draftTitle}"
    }
    MicroLabel(text, color = if (s?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = WRAP)
}

/** A catalog model's title when it is not installed (catalog ids only). */
private fun draftTitle(id: String): String =
    dev.wckdboy.autobot.core.models.Catalog.chatModels.firstOrNull { it.id == id }?.title ?: id.substringAfter(':')

/** Hints and errors here are sentences: they wrap instead of losing their end at "…". */
private const val WRAP = 4

private fun fmt(v: Double): String = String.format(Locale.ROOT, if (v >= 100) "%.0f" else "%.1f", v)

/** CPU features worth reporting (they decide which llama.cpp CPU variant loads). */
/** Starting the engine process can take a while on first use; past this, show what is known. */
private const val DEVICES_TIMEOUT_MS = 30_000L

/** Loading even an SDXL VAE context takes seconds; a hung DSP must not hold the report forever. */
private const val PROBE_TIMEOUT_MS = 60_000L

private val REPORTED_CPU_FEATURES = setOf("asimddp", "i8mm", "sve", "sve2", "bf16", "sme", "fphp", "asimdhp")

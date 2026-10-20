package dev.wckdboy.autobot.feature.models

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.graphics.Color
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.feature.models.ui.AppBar
import dev.wckdboy.autobot.feature.models.ui.Centered
import dev.wckdboy.autobot.feature.models.ui.ChoiceChips
import dev.wckdboy.autobot.feature.models.ui.EmptyMessage
import dev.wckdboy.autobot.feature.models.ui.Label
import dev.wckdboy.autobot.feature.models.ui.SectionCard
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
            AppBar("Compute", onBack = onBack, actions = {
                TextButton(onClick = { viewModel.report { report = it } }, enabled = state.running == null) {
                    Text(if (state.running != null) "Busy" else "Report")
                }
            })
        },
    ) { padding ->
        Centered(Modifier.padding(padding)) { width ->
            LazyColumn(
                width.fillMaxHeight(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        labelCase("this phone" + (state.device?.let { " · ${it.label}" } ?: "")),
                        Modifier.padding(bottom = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeviceCard("CPU", state.device?.let { "${it.cores} cores" }, true, Modifier.weight(1f).fillMaxHeight())
                        DeviceCard("GPU", state.devices?.gpu ?: "none", state.devices?.gpu != null, Modifier.weight(1f).fillMaxHeight())
                        DeviceCard("NPU", state.devices?.npu ?: "none", state.devices?.npu != null, Modifier.weight(1f).fillMaxHeight())
                    }
                    // No Snapdragon NPU is not a failure (the "none" card says it); on one, a short
                    // "needs a look" line, the full error in the report.
                    if (state.device?.htpArch != null) {
                        state.devices?.npuError?.let {
                            Hint("The NPU did not start: ${it.substringBefore(" (").take(80)}. Details in the report.", Modifier.padding(top = 8.dp), MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    if (state.devices?.npu != null && Backend.NPU !in state.devices!!.llmBackends) {
                        Hint("Chat on the NPU: the Hexagon backend did not start on this phone. Images still run on the NPU.", Modifier.padding(top = 8.dp))
                    }
                }
                system?.let { s -> item { SystemModelRow(s, onDownload = viewModel::downloadSystemModel) } }
                item {
                    state.running?.let {
                        Hint(labelCase("benchmarking · $it"))
                        // The benchmark reports phases, not a fraction: an indeterminate bar.
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    }
                    Text(
                        labelCase("models · auto picks the fastest measured backend"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.models.any { it.drafts.isNotEmpty() }) {
                    item { SpeculativeRow(state.speculativeMode, viewModel::setSpeculativeMode) }
                }
                items(state.models, key = { it.model.id }) { cm -> ModelCompute(cm, state, viewModel) }
                if (state.models.isEmpty()) {
                    item { EmptyMessage("No runnable models yet. Get one in the Models tab.") }
                }
            }
        }
    }
}

/** A sentence of help or state under a section; wraps rather than losing its end. */
@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = color)
}

/** Speculative decoding: auto / on / off (shown when an installed model has a draft in the catalog). */
@Composable
private fun SpeculativeRow(mode: SpeculativeMode, onMode: (SpeculativeMode) -> Unit) {
    SectionCard(title = "Speculative decoding") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SpeculativeMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = m == mode,
                    onClick = { onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, SpeculativeMode.entries.size),
                ) { Text(labelCase(m.label)) }
            }
        }
        Hint(
            when (mode) {
                SpeculativeMode.AUTO -> "A small model of the same family drafts, the chat model checks: same replies, faster, when the pair fits."
                SpeculativeMode.ON -> "Always draft when the pair fits at all, even when it does not pay."
                SpeculativeMode.OFF -> "No draft model is loaded."
            },
        )
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
                Hint("No prompts, file paths or identifiers. Nothing is sent.")
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
            }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The OS's own chat model: used for "This phone" when no downloaded model can run. */
@Composable
private fun SystemModelRow(status: SystemModelStatus, onDownload: () -> Unit) {
    SectionCard(
        title = "System model · ${status.title}",
        trailing = {
            if (status.availability == SystemAvailability.DOWNLOADABLE) {
                FilledTonalButton(onClick = onDownload) { Text("Download") }
            } else if (status.usable) {
                Label("ready")
            }
        },
    ) {
        Text(status.summary, style = MaterialTheme.typography.bodyMedium)
        Hint("Fallback when no downloaded model can run: about 4k tokens, no tools.")
        if (status.availability == SystemAvailability.DOWNLOADABLE) {
            Hint("The download is done by the system (AICore), outside Autobot's network controls.")
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DeviceCard(label: String, value: String?, present: Boolean, modifier: Modifier = Modifier) {
    OutlinedCard(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Still probing the device: a small loading indicator instead of a placeholder.
            if (value == null) {
                LoadingIndicator(Modifier.size(32.dp))
            } else {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = if (present) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCompute(cm: ComputeModel, state: ComputeUiState, actions: ComputeViewModel) {
    val m = cm.model
    val isText = m.engine == EngineKind.LLAMA
    SectionCard(title = m.title, trailing = {
        FilledTonalButton(onClick = { actions.benchmark(m) }, enabled = state.running == null) { Text(if (state.running != null) "Busy" else "Benchmark") }
    }) {
        if (isText) {
            val options = listOf(Backend.AUTO, Backend.CPU, Backend.GPU, Backend.NPU) +
                listOfNotNull(Backend.NPU_CPU.takeIf { it in state.devices?.llmBackends.orEmpty() || m.backend == it })
            // Chips wrap; what auto resolves to rides on its own chip.
            ChoiceChips(options, m.backend, { actions.setBackend(m, it) }, { b ->
                labelCase(if (b == Backend.AUTO && cm.fastest != null) "auto · ${cm.fastest.label}" else b.label)
            })
        } else {
            val pkg = m.manifest.npu
            Hint(if (pkg?.runtime == "qnn") "Runs on the NPU (Hexagon ${pkg.htpArch.orEmpty()})" else "Runs on the GPU (OpenCL) or CPU")
        }
        if (cm.results.isNotEmpty()) {
            // Wraps: four text backends do not fit one row on a phone.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cm.results.entries.sortedBy { it.key.ordinal }.forEach { (backend, r) ->
                    val value = if (isText) r.generationTps?.let { fmt(it) } ?: "–" else r.stepMs?.let { fmt(it / 1000) + "s" } ?: "–"
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(labelCase(backend.label + if (isText) " · tok/s" else " · step"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.headlineSmall)
                        r.promptTps?.let { Text("Prefill ${fmt(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        } else {
            Hint("Not measured yet")
        }
        if (isText && cm.drafts.isNotEmpty()) SpeculativeLine(cm, state.speculativeMode)
        cm.lastError?.let { Hint("Last run: $it", color = MaterialTheme.colorScheme.error) }
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
    Hint(labelCase(text), color = if (s?.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A catalog model's title when it is not installed (catalog ids only). */
private fun draftTitle(id: String): String =
    dev.wckdboy.autobot.core.models.Catalog.chatModels.firstOrNull { it.id == id }?.title ?: id.substringAfter(':')

private fun fmt(v: Double): String = String.format(Locale.ROOT, if (v >= 100) "%.0f" else "%.1f", v)

/** CPU features worth reporting (they decide which llama.cpp CPU variant loads). */
/** Starting the engine process can take a while on first use; past this, show what is known. */
private const val DEVICES_TIMEOUT_MS = 30_000L

/** Loading even an SDXL VAE context takes seconds; a hung DSP must not hold the report forever. */
private const val PROBE_TIMEOUT_MS = 60_000L

private val REPORTED_CPU_FEATURES = setOf("asimddp", "i8mm", "sve", "sve2", "bf16", "sme", "fphp", "asimdhp")

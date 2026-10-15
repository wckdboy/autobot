package dev.wckdboy.autobot.feature.models

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.BigReadout
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
import dev.wckdboy.autobot.core.models.SystemModel
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.providers.remote.SystemAvailability
import dev.wckdboy.autobot.providers.remote.SystemModelStatus
import dev.wckdboy.autobot.shared.diagnostics.AcceleratorStatus
import dev.wckdboy.autobot.shared.diagnostics.BenchmarkLine
import dev.wckdboy.autobot.shared.diagnostics.DeviceInfo
import dev.wckdboy.autobot.shared.diagnostics.DiagnosticsReport
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ComputeModel(
    val model: InstalledModel,
    /** Newest successful result per backend. */
    val results: Map<Backend, Benchmark>,
    val lastError: String?,
    /** What AUTO resolves to right now. */
    val fastest: Backend?,
)

@Immutable
data class ComputeUiState(
    val device: DeviceProfile? = null,
    val devices: ComputeDevices? = null,
    val models: List<ComputeModel> = emptyList(),
    val running: String? = null,
)

@HiltViewModel
class ComputeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: ModelLibrary,
    private val profile: ComputeProfile,
    private val bench: ComputeBench,
    private val network: NetworkPolicy,
    private val systemModel: SystemModel,
    profiler: DeviceProfiler,
) : ViewModel() {
    /** The OS's model; null in builds without one (the default, GMS-free build). */
    val system: StateFlow<SystemModelStatus?> = systemModel.status

    private val devices = MutableStateFlow<ComputeDevices?>(null)
    private val running = MutableStateFlow<String?>(null)
    private val device = MutableStateFlow<DeviceProfile?>(null)

    val uiState: StateFlow<ComputeUiState> = combine(library.models, profile.benchmarks, devices, running, device) { models, results, d, r, p ->
        val runnable = models.filter { it.isReady && (it.engine == EngineKind.LLAMA || (it.engine == EngineKind.NPU && it.manifest.npu?.arch != "upscaler")) }
        ComputeUiState(
            device = p,
            devices = d,
            running = r,
            models = runnable.map { m ->
                val mine = results.filter { it.modelId == m.id }
                ComputeModel(
                    model = m,
                    results = mine.filter { it.ok }.groupBy { it.backend }.mapValues { (_, runs) -> runs.maxBy { it.createdAt } },
                    lastError = mine.maxByOrNull { it.createdAt }?.error,
                    fastest = ComputeProfile.fastest(mine),
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ComputeUiState())

    init {
        viewModelScope.launch { device.value = runCatching { profiler.profile() }.getOrNull() }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { devices.value = runCatching { bench.devices() }.getOrNull() ?: ComputeDevices() }
        viewModelScope.launch { systemModel.refresh() }
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

    /** The shared diagnostics report for what this screen shows (no prompts, paths or identifiers). */
    fun report(): String {
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
            benchmarks = state.models.flatMap { cm ->
                cm.results.values.map { b ->
                    BenchmarkLine(cm.model.title, b.backend.label, b.promptTps, b.generationTps, b.stepMs, b.loadMs)
                } + listOfNotNull(cm.lastError?.let { BenchmarkLine(cm.model.title, "last run", error = it) })
            },
            errors = listOfNotNull(d?.npuError),
            networkMode = network.mode.value::class.simpleName,
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
            running.value = "starting…"
            try {
                bench.run(model, backends) { running.value = it }
            } finally {
                running.value = null
            }
        }
    }
}

@Composable
fun ComputeRoute(onBack: () -> Unit, viewModel: ComputeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val system by viewModel.system.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    report?.let { text -> ReportDialog(text, onDismiss = { report = null }) }
    Scaffold(
        topBar = {
            AutobotTopBar(
                title = "Compute",
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { Tag("report", Modifier.padding(end = 12.dp), onClick = { report = viewModel.report() }) },
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
                    DeviceCard("cpu", state.device?.let { "${it.cores} cores" } ?: "…", true, Modifier.weight(1f))
                    DeviceCard("gpu", state.devices?.gpu ?: "none", state.devices?.gpu != null, Modifier.weight(1f))
                    DeviceCard("npu", state.devices?.npu ?: "none", state.devices?.npu != null, Modifier.weight(1f))
                }
                state.devices?.npuError?.let { MicroLabel("npu: $it", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.error) }
                if (state.devices?.npu != null && Backend.NPU !in state.devices!!.llmBackends) {
                    MicroLabel("chat on the npu: the hexagon backend did not start on this phone · images still run on the npu", Modifier.padding(top = 6.dp))
                }
            }
            system?.let { s -> item { SystemModelRow(s, onDownload = viewModel::downloadSystemModel) } }
            item {
                state.running?.let { MicroLabel("benchmarking · $it", color = MaterialTheme.colorScheme.primary) }
                SectionLabel("models · auto picks the fastest measured backend", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.models, key = { it.model.id }) { cm -> ModelCompute(cm, state, viewModel) }
            if (state.models.isEmpty()) {
                item { MicroLabel("no runnable models yet — get one in the Models tab") }
            }
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
                MicroLabel("no prompts, file paths or identifiers · nothing is sent")
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
    Panel(
        title = "system model · ${status.title}",
        accent = if (status.usable) MaterialTheme.colorScheme.primary else null,
        trailing = {
            if (status.availability == SystemAvailability.DOWNLOADABLE) {
                Tag("download", accent = MaterialTheme.colorScheme.primary, onClick = onDownload)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(status.summary, style = MaterialTheme.typography.bodySmall)
            MicroLabel("fallback when no downloaded model can run · ~4k tokens, no tools")
            if (status.availability == SystemAvailability.DOWNLOADABLE) {
                MicroLabel("the download is done by the system (AICore), outside autobot's network controls")
            }
        }
    }
}

@Composable
private fun DeviceCard(label: String, value: String, present: Boolean, modifier: Modifier = Modifier) {
    Panel(modifier, title = label, accent = if (present) MaterialTheme.colorScheme.primary else null) {
        Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = if (present) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ModelCompute(cm: ComputeModel, state: ComputeUiState, actions: ComputeViewModel) {
    val m = cm.model
    val isText = m.engine == EngineKind.LLAMA
    Panel(title = m.title, trailing = {
        Tag(if (state.running != null) "busy" else "benchmark", accent = MaterialTheme.colorScheme.primary, filled = state.running == null, onClick = { actions.benchmark(m) }.takeIf { state.running == null })
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (isText) {
                val options = listOf(Backend.AUTO, Backend.CPU, Backend.GPU, Backend.NPU)
                Segmented(options, m.backend, { actions.setBackend(m, it) }, { b ->
                    if (b == Backend.AUTO && cm.fastest != null) "auto · ${cm.fastest.label}" else b.label
                }, Modifier.fillMaxWidth())
            } else {
                val pkg = m.manifest.npu
                MicroLabel(if (pkg?.runtime == "qnn") "runs on the npu (hexagon ${pkg.htpArch.orEmpty()})" else "runs on the gpu (opencl) or cpu")
            }
            if (cm.results.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Top) {
                    cm.results.entries.sortedBy { it.key.ordinal }.forEach { (backend, r) ->
                        val value = if (isText) r.generationTps?.let { fmt(it) } ?: "–" else r.stepMs?.let { fmt(it / 1000) + "s" } ?: "–"
                        BigReadout(backend.label + if (isText) " · tok/s" else " · step", value)
                    }
                }
                cm.results.values.firstOrNull { it.promptTps != null }?.let {
                    MicroLabel(cm.results.entries.sortedBy { e -> e.key.ordinal }.joinToString("  ") { (b, r) -> "${b.label} prefill ${r.promptTps?.let(::fmt) ?: "–"} tok/s" })
                }
            } else {
                MicroLabel("not measured yet")
            }
            cm.lastError?.let { MicroLabel("last run: $it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun fmt(v: Double): String = String.format(Locale.ROOT, if (v >= 100) "%.0f" else "%.1f", v)

/** CPU features worth reporting (they decide which llama.cpp CPU variant loads). */
private val REPORTED_CPU_FEATURES = setOf("asimddp", "i8mm", "sve", "sve2", "bf16", "sme", "fphp", "asimdhp")

package dev.wckdboy.autobot.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.BigReadout
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Panel
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.Tag
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
    private val library: ModelLibrary,
    private val profile: ComputeProfile,
    private val bench: ComputeBench,
    profiler: DeviceProfiler,
) : ViewModel() {
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
    }

    fun setBackend(model: InstalledModel, backend: Backend) {
        viewModelScope.launch { profile.setBackend(model.id, backend) }
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
    LaunchedEffect(Unit) { viewModel.refresh() }
    Scaffold(
        topBar = {
            AutobotTopBar(
                title = "Compute",
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
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
                    MicroLabel("chat on the npu needs this build's hexagon kernels (not included yet) · images run on the npu", Modifier.padding(top = 6.dp))
                }
            }
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

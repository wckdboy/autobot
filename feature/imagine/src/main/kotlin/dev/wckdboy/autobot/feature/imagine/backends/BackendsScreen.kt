package dev.wckdboy.autobot.feature.imagine.backends

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Panel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.SelectField
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.diffusion.DiffusionBackendRepository
import dev.wckdboy.autobot.core.diffusion.DiffusionEngineFactory
import dev.wckdboy.autobot.core.network.Loopback
import dev.wckdboy.autobot.core.network.RouteOverride
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class BackendsUiState(
    val backends: List<DiffusionBackend> = emptyList(),
    val defaultId: String? = null,
    val testing: String? = null,
    val testResults: Map<String, String> = emptyMap(),
)

@HiltViewModel
class BackendsViewModel @Inject constructor(
    private val repo: DiffusionBackendRepository,
    private val engines: DiffusionEngineFactory,
) : ViewModel() {
    private val tests = MutableStateFlow(Pair<String?, Map<String, String>>(null, emptyMap()))

    val uiState: StateFlow<BackendsUiState> = combine(repo.backends, repo.defaultBackendId, tests) { b, d, (testing, results) ->
        BackendsUiState(b, d, testing, results)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackendsUiState())

    fun newBackend(kind: BackendKind, url: String) =
        DiffusionBackend(repo.newId(), kind, if (kind == BackendKind.LOCAL_SSE) "On-device" else "Forge", url)

    fun save(backend: DiffusionBackend) = viewModelScope.launch { repo.save(backend) }
    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun setDefault(id: String) = viewModelScope.launch { repo.setDefault(id) }

    /** Probes the backend (health / catalog) through the network policy. */
    fun test(backend: DiffusionBackend) {
        tests.update { it.copy(first = backend.id) }
        viewModelScope.launch {
            val result = runCatching { engines.create(backend).catalog() }.fold(
                onSuccess = { c ->
                    listOfNotNull(
                        "ok",
                        c.models.size.takeIf { it > 0 }?.let { "$it models" },
                        c.loras.size.takeIf { it > 0 }?.let { "$it loras" },
                        c.samplers.size.takeIf { it > 0 }?.let { "$it samplers" },
                    ).joinToString(" · ")
                },
                onFailure = { "fail · ${it.message ?: it.javaClass.simpleName}" },
            )
            tests.update { (_, r) -> null to (r + (backend.id to result)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendsRoute(onBack: () -> Unit, viewModel: BackendsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<DiffusionBackend?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text("IMAGE BACKENDS", style = MaterialTheme.typography.titleMedium) },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.backends.forEach { backend ->
                val result = state.testResults[backend.id]
                Panel(
                    title = backend.kind.label,
                    accent = if (backend.kind == BackendKind.LOCAL_SSE) AutobotColors.Uv else AutobotColors.Cyan,
                    trailing = {
                        Tag(if (backend.isLoopback) "loopback" else "network", accent = if (backend.isLoopback) AutobotColors.Acid else AutobotColors.Amber)
                    },
                ) {
                    Row(Modifier.fillMaxWidth().clickable { editing = backend }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = backend.id == state.defaultId, onClick = { viewModel.setDefault(backend.id) })
                        Column(Modifier.weight(1f)) {
                            Text(backend.name, style = MaterialTheme.typography.titleSmall)
                            Text(backend.baseUrl, style = AutobotTheme.styles.codeBlock, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.test(backend) }, enabled = state.testing == null, shape = MaterialTheme.shapes.small) {
                            Text(if (state.testing == backend.id) "TESTING…" else "TEST")
                        }
                        result?.let {
                            MicroLabel(it, Modifier.weight(1f), color = if (it.startsWith("ok")) AutobotColors.Acid else MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Panel(title = "Add backend", accent = AutobotColors.Acid) {
                Text(
                    "Loopback backends keep working in Offline mode. Anything else must be HTTPS and follows your " +
                        "network mode and kill switch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DiffusionBackendRepository.PRESETS.forEach { (kind, url) ->
                    OutlinedButton(onClick = { editing = viewModel.newBackend(kind, url) }, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                        Text("${kind.label}  ·  $url", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    editing?.let { backend ->
        ModalBottomSheet(onDismissRequest = { editing = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            BackendEditor(
                initial = backend,
                isNew = state.backends.none { it.id == backend.id },
                onSave = {
                    viewModel.save(it)
                    editing = null
                },
                onDelete = {
                    viewModel.delete(backend.id)
                    editing = null
                },
            )
        }
    }
}

@Composable
private fun BackendEditor(initial: DiffusionBackend, isNew: Boolean, onSave: (DiffusionBackend) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.baseUrl) }
    var kind by remember { mutableStateOf(initial.kind) }
    var routing by remember { mutableStateOf(runCatching { RouteOverride.valueOf(initial.routing) }.getOrDefault(RouteOverride.INHERIT)) }
    val cleartextRemote = url.trim().startsWith("http://", ignoreCase = true) && !Loopback.isLoopbackUrl(url)

    Column(
        Modifier.fillMaxWidth().imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MicroLabel(if (isNew) "// new backend" else "// edit backend", color = MaterialTheme.colorScheme.primary)
        Segmented(listOf(BackendKind.SD_API, BackendKind.LOCAL_SSE), kind, { kind = it }, { if (it == BackendKind.SD_API) "A1111 API" else "Local SSE" }, Modifier.fillMaxWidth())
        ConsoleTextField(name, { name = it }, Modifier.fillMaxWidth(), label = "Name", singleLine = true)
        ConsoleTextField(
            url,
            { url = it },
            Modifier.fillMaxWidth(),
            label = "Base URL",
            singleLine = true,
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
        if (cleartextRemote) MicroLabel("cleartext http is only allowed to loopback — use https", color = MaterialTheme.colorScheme.error)
        SelectField("Route", routing.name.lowercase(), RouteOverride.entries, { routing = it }, optionLabel = { it.name.lowercase() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isNew) TextButton(onClick = onDelete) { Text("DELETE", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { onSave(initial.copy(name = name.trim().ifBlank { kind.label }, baseUrl = url.trim(), kind = kind, routing = routing.name)) },
                enabled = url.isNotBlank() && !cleartextRemote,
                shape = MaterialTheme.shapes.small,
            ) { Text("SAVE") }
        }
        Spacer(Modifier.size(4.dp))
    }
}

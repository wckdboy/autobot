package dev.wckdboy.autobot.feature.settings.backup

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.backup.BackupService
import dev.wckdboy.autobot.core.designsystem.component.InkProgressBar
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface BackupState {
    data object Idle : BackupState
    data class Working(val status: String) : BackupState
    data class Done(val message: String) : BackupState
    data class Failed(val message: String) : BackupState
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backups: BackupService,
) : ViewModel() {
    private val _state = MutableStateFlow<BackupState>(BackupState.Idle)
    val state: StateFlow<BackupState> = _state.asStateFlow()

    fun export(uri: Uri, passphrase: String) = run("export") {
        val version = context.packageManager.getPackageInfo(context.packageName, 0)
        context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
            backups.export(out, passphrase, "Autobot ${version.versionName} (${version.longVersionCode})") { _state.value = BackupState.Working(it) }
        }
        "Exported. Keep the passphrase: without it the file cannot be opened."
    }

    fun import(uri: Uri, passphrase: String) = run("import") {
        val added = context.contentResolver.openInputStream(uri)!!.use { input ->
            backups.import(input, passphrase) { _state.value = BackupState.Working(it) }
        }
        "Imported ${added.conversations} sessions, ${added.images} images, ${added.providers} providers, ${added.memories} memory notes, ${added.studioAssets} studio assets. " +
            "Add API keys again under Model providers."
    }

    fun reset() {
        _state.value = BackupState.Idle
    }

    private fun run(what: String, block: suspend () -> String) {
        if (_state.value is BackupState.Working) return
        _state.value = BackupState.Working(what)
        viewModelScope.launch {
            _state.value = try {
                BackupState.Done(block())
            } catch (e: Exception) {
                BackupState.Failed(e.message ?: "$what failed")
            } catch (e: OutOfMemoryError) {
                BackupState.Failed("Not enough memory to $what this file")
            }
        }
    }
}

/**
 * Export and import of the passphrase-sealed `.autobot` bundle (the same file the iOS app reads).
 * Files go through the system document picker, so no storage permission is needed.
 */
@Composable
fun BackupDialog(onDismiss: () -> Unit, viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var passphrase by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val exportable = passphrase.length >= MIN_PASSPHRASE && passphrase == confirm
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.export(uri, passphrase)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri, passphrase)
    }
    val working = state is BackupState.Working
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss().also { viewModel.reset() } },
        title = { Text("Export & import") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Sessions, gallery, attachments, providers (without API keys) and appearance settings, sealed with a " +
                        "passphrase (AES-256-GCM). The iPhone app opens the same file. Models are not included.",
                    style = MaterialTheme.typography.bodySmall,
                )
                NoPersonalizedLearning {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        enabled = !working,
                    )
                }
                NoPersonalizedLearning {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Repeat (export only)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        enabled = !working,
                    )
                }
                MicroLabel("at least $MIN_PASSPHRASE characters · it cannot be recovered")
                when (val s = state) {
                    is BackupState.Working -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        MicroLabel(s.status.removeSuffix("…"), color = MaterialTheme.colorScheme.primary)
                        // Export/import report phases, not a fraction: the indeterminate ink bar.
                        InkProgressBar(null, Modifier.fillMaxWidth().height(2.dp))
                    }
                    is BackupState.Done -> Text(s.message, style = MaterialTheme.typography.bodySmall)
                    is BackupState.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    BackupState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { exporter.launch("autobot-${LocalDate.now()}.autobot") }, enabled = exportable && !working) { Text("Export") }
        },
        dismissButton = {
            TextButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = passphrase.isNotEmpty() && !working) { Text("Import") }
        },
    )
}

private const val MIN_PASSPHRASE = 10

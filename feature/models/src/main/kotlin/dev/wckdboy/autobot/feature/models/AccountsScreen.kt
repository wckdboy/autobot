package dev.wckdboy.autobot.feature.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.Role
import dev.wckdboy.autobot.core.designsystem.component.Choices
import dev.wckdboy.autobot.core.designsystem.component.AppBar
import dev.wckdboy.autobot.core.designsystem.component.BusyLabel
import dev.wckdboy.autobot.core.designsystem.component.Centered
import dev.wckdboy.autobot.core.designsystem.component.Field
import dev.wckdboy.autobot.core.designsystem.component.NoticeCard
import dev.wckdboy.autobot.core.designsystem.component.SectionCard
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.designsystem.component.InfoButton
import dev.wckdboy.autobot.core.designsystem.component.SecretKeyboardOptions
import dev.wckdboy.autobot.core.models.AccountState
import dev.wckdboy.autobot.core.models.CivitaiClient
import dev.wckdboy.autobot.core.models.CivitaiHost
import dev.wckdboy.autobot.core.models.HubException
import dev.wckdboy.autobot.core.models.HuggingFaceClient
import dev.wckdboy.autobot.core.models.ModelAccounts
import dev.wckdboy.autobot.core.security.Secret
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class AccountsUiState(val accounts: AccountState = AccountState(), val busy: Boolean = false, val message: String? = null)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accounts: ModelAccounts,
    private val hub: HuggingFaceClient,
    private val civitai: CivitaiClient,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AccountsUiState> = combine(accounts.state, busy, message) { a, b, m -> AccountsUiState(a, b, m) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    /** Validates the token against the Hub before storing it. */
    fun saveHuggingFace(token: String) = run {
        val secret = Secret(token.trim())
        val user = hub.whoami(secret)
        accounts.setHuggingFace(secret, user.name)
        "Signed in to Hugging Face as ${user.name}"
    }

    fun saveCivitai(key: String) = run {
        val secret = Secret(key.trim())
        val user = civitai.me(secret)
        accounts.setCivitai(secret, user)
        "Civitai key saved for $user"
    }

    fun signOutHuggingFace() = viewModelScope.launch { accounts.clearHuggingFace() }
    fun signOutCivitai() = viewModelScope.launch { accounts.clearCivitai() }
    fun setHost(host: CivitaiHost) = viewModelScope.launch { accounts.setCivitaiHost(host) }
    fun setMature(show: Boolean) = viewModelScope.launch { accounts.setShowMature(show) }
    fun consume() { message.value = null }

    private fun run(block: suspend () -> String) {
        viewModelScope.launch {
            busy.value = true
            message.value = try {
                block()
            } catch (e: HubException) {
                when (e.code) {
                    HubException.Code.UNAUTHORIZED -> "That token was rejected"
                    HubException.Code.BLOCKED -> "Network is off — allow a network mode in Privacy Center first"
                    else -> e.message
                }
            }
            busy.value = false
        }
    }
}

@Composable
fun AccountsRoute(onBack: () -> Unit, viewModel: AccountsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var hfToken by rememberSaveable { mutableStateOf("") }
    var civitaiKey by rememberSaveable { mutableStateOf("") }
    // Which "Verify & save" shows the loading indicator while the token is checked.
    var verifying by rememberSaveable { mutableStateOf<String?>(null) }
    val hfBusy = state.busy && verifying == "hf"
    val civitaiBusy = state.busy && verifying == "civitai"
    var confirmMature by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { AppBar("Accounts", onBack = onBack) }) { padding ->
        Centered(Modifier.padding(padding)) { width ->
            // A focused token field scrolls into view above the keyboard.
            Column(
                width.fillMaxHeight().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Model hubs · encrypted", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                state.message?.let { NoticeCard(it, action = "OK", onAction = viewModel::consume) }

                SectionCard(title = "Hugging Face") {
                    val user = state.accounts.huggingFaceUser
                    // One line and an ⓘ, as on iOS: how to get a token is one tap away.
                    PanelLine(if (user != null) "Signed in as $user" else "Optional read token", "Hugging Face", HF_INFO)
                    if (user == null) {
                        Field(
                            hfToken,
                            { hfToken = it },
                            Modifier.fillMaxWidth(),
                            placeholder = "hf_…",
                            singleLine = true,
                            mono = true,
                            keyboardOptions = SecretKeyboardOptions,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Button(
                            onClick = { if (!hfBusy) { verifying = "hf"; viewModel.saveHuggingFace(hfToken); hfToken = "" } },
                            enabled = (hfToken.isNotBlank() && !state.busy) || hfBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { BusyLabel("Verify & save", hfBusy) }
                    } else {
                        OutlinedButton(onClick = viewModel::signOutHuggingFace) { Text("Sign out") }
                    }
                }

                SectionCard(title = "Civitai") {
                    val user = state.accounts.civitaiUser
                    PanelLine(if (user != null) "Key saved · $user" else "Optional API key", "Civitai", CIVITAI_INFO)
                    if (user == null) {
                        Field(
                            civitaiKey,
                            { civitaiKey = it },
                            Modifier.fillMaxWidth(),
                            placeholder = "API key",
                            singleLine = true,
                            mono = true,
                            keyboardOptions = SecretKeyboardOptions,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Button(
                            onClick = { if (!civitaiBusy) { verifying = "civitai"; viewModel.saveCivitai(civitaiKey); civitaiKey = "" } },
                            enabled = (civitaiKey.isNotBlank() && !state.busy) || civitaiBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { BusyLabel("Verify & save", civitaiBusy) }
                    } else {
                        OutlinedButton(onClick = viewModel::signOutCivitai) { Text("Remove key") }
                    }
                    Text("Host", style = MaterialTheme.typography.labelLarge)
                    Choices(CivitaiHost.entries, state.accounts.civitaiHost, viewModel::setHost, Modifier.fillMaxWidth()) { it.host }
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = state.accounts.showMature,
                            role = Role.Switch,
                            onValueChange = { if (it) confirmMature = true else viewModel.setMature(false) },
                        ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Mature content", style = MaterialTheme.typography.bodyLarge)
                            Text("18+ · previews blurred", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = state.accounts.showMature, onCheckedChange = null)
                    }
                }
            }
        }
    }

    if (confirmMature) {
        AlertDialog(
            onDismissRequest = { confirmMature = false },
            title = { Text("Are you 18 or older?") },
            text = { Text("Mature models and their (blurred) previews will appear in Civitai results. Content flagged as depicting minors or real people is never shown.") },
            confirmButton = { TextButton(onClick = { confirmMature = false; viewModel.setMature(true) }) { Text("I am 18+") } },
            dismissButton = { TextButton(onClick = { confirmMature = false }) { Text("Cancel") } },
        )
    }
}

/** A card's one line of state, and the how-to behind its ⓘ. */
@Composable
private fun PanelLine(text: String, title: String, info: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        InfoButton(title, info)
    }
}

private const val HF_INFO = "A read token lifts rate limits and unlocks gated models (Llama, Gemma 3…) once you accept their terms on huggingface.co. " +
    "Create one at huggingface.co/settings/tokens (fine-grained, read access to public gated repos)."
private const val CIVITAI_INFO = "Some creators require login to download. Create a key in civitai.com → Account settings → API keys."

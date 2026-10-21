package dev.wckdboy.autobot.feature.settings.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.data.settings.WebSearchRepository
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.feature.settings.ui.SheetLabel
import dev.wckdboy.autobot.core.designsystem.component.SecretKeyboardOptions
import dev.wckdboy.autobot.core.network.RouteOverride
import dev.wckdboy.autobot.core.security.Secret
import dev.wckdboy.autobot.shared.search.SearchProvider
import dev.wckdboy.autobot.shared.search.WebSearchCodec
import dev.wckdboy.autobot.shared.search.WebSearchConfig
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What the dialog edits. [apiKey] is only what the user typed now; the stored key is never shown. */
data class WebSearchDraft(
    val provider: SearchProvider = SearchProvider.NONE,
    val baseUrl: String = "",
    val route: RouteOverride = RouteOverride.INHERIT,
    val apiKey: String = "",
    val hasStoredKey: Boolean = false,
) {
    /** Why the draft cannot be saved, or null. */
    val problem: String?
        get() = when (provider) {
            SearchProvider.NONE -> null
            else -> WebSearchCodec.problem(
                WebSearchConfig(provider, baseUrl, apiKey.ifBlank { if (hasStoredKey) "stored" else "" }, route.name),
            )
        }
}

@HiltViewModel
class WebSearchViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val search: WebSearchRepository,
) : ViewModel() {
    suspend fun load(): WebSearchDraft {
        val s = settings.settings.first()
        return WebSearchDraft(s.searchProvider, s.searchBaseUrl, s.searchRoute, hasStoredKey = search.hasApiKey())
    }

    /** Saves [draft]; [onResult] gets null, or why the key could not be stored (the dialog stays open). */
    fun save(draft: WebSearchDraft, onResult: (String?) -> Unit) = viewModelScope.launch {
        val failure = try {
            search.save(
                provider = draft.provider,
                baseUrl = draft.baseUrl,
                route = draft.route,
                apiKey = draft.apiKey.takeIf { it.isNotBlank() }?.let(::Secret),
                // Turning search off also forgets the Brave key.
                clearKey = draft.provider == SearchProvider.NONE,
            )
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "Couldn't store the key: ${e.message ?: e::class.simpleName}"
        }
        onResult(failure)
    }
}

/**
 * Web search setup: off by default; SearXNG (instance URL) or Brave Search (API key in the
 * encrypted secret store). Only queries go to the provider, through the chosen route.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WebSearchDialog(onDismiss: () -> Unit, viewModel: WebSearchViewModel = hiltViewModel()) {
    var draft by remember { mutableStateOf<WebSearchDraft?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { draft = viewModel.load() }
    val d = draft ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Web search") },
        text = {
            NoPersonalizedLearning {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Gives the agent a web_search tool. Each search sends only the query to the provider you choose, through " +
                            "your network route, and needs approval like web_fetch.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SearchProvider.entries.forEach { p ->
                            FilterChip(selected = d.provider == p, onClick = { draft = d.copy(provider = p) }, label = { Text(p.label) })
                        }
                    }
                    when (d.provider) {
                        SearchProvider.SEARXNG -> OutlinedTextField(
                            value = d.baseUrl,
                            onValueChange = { draft = d.copy(baseUrl = it) },
                            label = { Text("Instance URL") },
                            placeholder = { Text("https://searx.example.org") },
                            singleLine = true,
                            supportingText = { Text("The instance must allow the JSON format (search.formats: json).") },
                        )
                        SearchProvider.BRAVE -> OutlinedTextField(
                            value = d.apiKey,
                            onValueChange = { draft = d.copy(apiKey = it) },
                            label = { Text("API key") },
                            placeholder = { Text(if (d.hasStoredKey) "Stored — leave empty to keep" else "Subscription token") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = SecretKeyboardOptions,
                        )
                        SearchProvider.NONE -> Unit
                    }
                    if (d.provider != SearchProvider.NONE) {
                        SheetLabel("Route")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RouteOverride.entries.forEach { r ->
                                FilterChip(
                                    selected = d.route == r,
                                    onClick = { draft = d.copy(route = r) },
                                    label = { Text(r.name.lowercase().replaceFirstChar(Char::titlecase)) },
                                )
                            }
                        }
                    }
                    d.problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = d.problem == null, onClick = {
                viewModel.save(d) { failure ->
                    error = failure
                    if (failure == null) onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

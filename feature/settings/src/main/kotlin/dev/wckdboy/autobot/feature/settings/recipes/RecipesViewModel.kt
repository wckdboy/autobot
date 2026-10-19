package dev.wckdboy.autobot.feature.settings.recipes

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.agent.runtime.recipes.RecipeBook
import dev.wckdboy.autobot.agent.runtime.recipes.RecipeModelChoice
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipeRunStatus
import dev.wckdboy.autobot.shared.recipes.RecipeTemplates
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone

/** One recipe as the list shows it. */
@Immutable
data class RecipeRowUi(
    val id: String,
    val name: String,
    val schedule: String,
    /** "Next: today 07:00 · Done yesterday 07:02" ([RecipeCopy.status]). */
    val status: String,
    val enabled: Boolean,
    val running: Boolean,
    val lastStatus: RecipeRunStatus?,
    val lastSummary: String?,
    val notes: List<String>,
    /** The last run's chat, to open the result. */
    val lastChat: String?,
)

@Immutable
data class RecipesUiState(
    val loaded: Boolean = false,
    val recipes: List<RecipeRowUi> = emptyList(),
    val models: List<RecipeModelChoice> = emptyList(),
    /** Paired nodes a recipe can run on. */
    val nodes: List<RecipeModelChoice> = emptyList(),
    /** Why the last save could not reach the recipe's node (it is tried again later). */
    val notice: String? = null,
    val summaryInNotifications: Boolean = false,
    /** The recipe being edited (a draft), with whether it is new. */
    val editor: Recipe? = null,
    val editorIsNew: Boolean = false,
    val problems: List<String> = emptyList(),
)

/** The Recipes screen: list, editor, enable, Run now, the notification summary switch. */
@HiltViewModel
class RecipesViewModel @Inject constructor(private val book: RecipeBook) : ViewModel() {
    private val editor = MutableStateFlow<Pair<Recipe, Boolean>?>(null)
    private val summary = MutableStateFlow(book.summaryInNotifications)
    private val stored = MutableStateFlow<List<Recipe>>(emptyList())
    private val notice = MutableStateFlow<String?>(null)
    private val extras = combine(book.nodeChoices, notice) { n, x -> n to x }

    /** Re-renders the "Next: …" lines as time passes. */
    private val minute = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    val uiState: StateFlow<RecipesUiState> = combine(book.recipes, combine(book.models, extras) { m, e -> m to e }, book.running, editor, combine(summary, minute) { s, m -> s to m }) { recipes, (models, extra), running, edit, (summaryOn, now) ->
        val (nodes, noticeText) = extra
        val names = nodes.associate { it.id to it.title }
        stored.value = recipes
        val zone = TimeZone.currentSystemDefault()
        RecipesUiState(
            loaded = true,
            recipes = recipes.map { r -> r.toRow(now, zone, r.id in running, r.runsOn?.let { names[it] ?: "my node" }) },
            models = models,
            nodes = nodes,
            notice = noticeText,
            summaryInNotifications = summaryOn,
            editor = edit?.first,
            editorIsNew = edit?.second == true,
            problems = edit?.first?.problems().orEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUiState())

    fun startAdd() {
        editor.value = RecipeTemplates.blank(UUID.randomUUID().toString(), System.currentTimeMillis()) to true
    }

    fun startEdit(id: String) {
        stored.value.firstOrNull { it.id == id }?.let { editor.value = it to false }
    }

    fun updateDraft(transform: (Recipe) -> Recipe) {
        editor.value = editor.value?.let { (r, isNew) -> transform(r) to isNew }
    }

    fun dismissEditor() {
        editor.value = null
    }

    fun save() {
        val draft = editor.value?.first ?: return
        if (draft.problems().isNotEmpty()) return
        editor.value = null
        launch { notice.value = book.save(draft)?.let { "${draft.name}: $it" } }
    }

    fun delete(id: String) {
        editor.value = null
        launch { book.delete(id) }
    }

    fun setEnabled(id: String, enabled: Boolean) = launch { book.setEnabled(id, enabled) }

    fun runNow(id: String) = launch { book.runNow(id) }

    fun setSummaryInNotifications(on: Boolean) {
        book.summaryInNotifications = on
        summary.value = on
    }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launchSafely({ Log.w("Recipes", "cannot change a recipe", it) }) { block() }
    }
}

internal fun Recipe.toRow(now: Long, zone: TimeZone, running: Boolean, node: String? = null) = RecipeRowUi(
    id = id,
    name = name,
    schedule = RecipeCopy.where(schedule.label(), node),
    status = if (running) "Running…" else RecipeCopy.status(this, now, zone, approximate = false),
    enabled = enabled,
    running = running,
    lastStatus = state.lastRun?.status,
    lastSummary = state.lastRun?.summary?.takeIf { it.isNotBlank() },
    notes = state.lastRun?.notes.orEmpty(),
    lastChat = state.lastRun?.sessionId,
)

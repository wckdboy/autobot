package dev.wckdboy.autobot.feature.studio.ui.library

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.diagnostics.launchSafely
import dev.wckdboy.autobot.core.data.diagnostics.userMessage
import dev.wckdboy.autobot.feature.studio.engine.StudioHost
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.feature.studio.media.StudioExport
import dev.wckdboy.autobot.shared.studio.AssetFilter
import dev.wckdboy.autobot.shared.studio.AssetType
import dev.wckdboy.autobot.shared.studio.EngineKinds
import dev.wckdboy.autobot.shared.studio.StudioAsset
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

enum class LibraryView { GRID, LIST }

@Immutable
data class LibraryState(
    val assets: List<StudioAsset> = emptyList(),
    val types: Set<AssetType> = AssetType.entries.toSet(),
    val favoritesOnly: Boolean = false,
    val allProjects: Boolean = false,
    val view: LibraryView = LibraryView.GRID,
    /** Two assets side by side (A/B). */
    val compare: Pair<String, String>? = null,
    /** The first pick of a comparison waiting for its second. */
    val comparePick: String? = null,
    val message: String? = null,
)

interface LibraryActions {
    fun toggleType(type: AssetType)
    fun setFavoritesOnly(enabled: Boolean)
    fun setAllProjects(enabled: Boolean)
    fun setView(view: LibraryView)
    fun toggleFavorite(asset: StudioAsset)
    fun delete(asset: StudioAsset)
    fun export(asset: StudioAsset)
    fun runAgain(asset: StudioAsset)
    fun compareWith(asset: StudioAsset)
    fun closeCompare()
    fun dismiss()
}

/**
 * The library (paper): the project's assets, newest first, by type and favourites, as a grid or a
 * list; compare two (A/B slider for images, A/B playback for audio); use one as an input, reuse or
 * rerun its recipe; export a decrypted copy only when the user confirms.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    application: Application,
    private val host: StudioHost,
) : AndroidViewModel(application), LibraryActions {
    private val local = MutableStateFlow(LibraryState())

    /** Work on storage or an engine: a failure ("Storage is full") is shown, never a crash. */
    private fun safely(block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launchSafely({ e -> local.update { it.copy(message = userMessage(e)) } }, block)

    val state: StateFlow<LibraryState> = combine(local, host.store.observeAssets(), host.settings.currentProjectId) { l, all, project ->
        val filter = AssetFilter(projectId = if (l.allProjects) null else project, types = l.types, favoritesOnly = l.favoritesOnly)
        l.copy(assets = all.filter { filter.matches(it) && it.recipe.engine !in StudioRecipes.HIDDEN_ENGINES })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    override fun toggleType(type: AssetType) = local.update {
        val next = if (type in it.types) it.types - type else it.types + type
        it.copy(types = next.ifEmpty { AssetType.entries.toSet() })
    }

    override fun setFavoritesOnly(enabled: Boolean) = local.update { it.copy(favoritesOnly = enabled) }
    override fun setAllProjects(enabled: Boolean) = local.update { it.copy(allProjects = enabled) }
    override fun setView(view: LibraryView) = local.update { it.copy(view = view) }

    override fun toggleFavorite(asset: StudioAsset) {
        safely { host.store.setFavorite(asset.id, !asset.favorite) }
    }

    override fun delete(asset: StudioAsset) {
        safely {
            host.store.deleteAsset(asset.id)
            local.update { s -> if (s.compare?.toList()?.contains(asset.id) == true) s.copy(compare = null) else s }
        }
    }

    override fun export(asset: StudioAsset) {
        safely {
            val ok = withContext(Dispatchers.IO) {
                val bytes = host.store.bytes(asset.id) ?: return@withContext false
                runCatching { StudioExport.export(getApplication(), asset, bytes) }.getOrDefault(false)
            }
            local.update { it.copy(message = if (ok) "Exported to ${StudioExport.destination(asset)}" else "Export failed") }
        }
    }

    override fun runAgain(asset: StudioAsset) {
        if (asset.recipe.engine == StudioRecipes.IMPORT_ENGINE) return
        safely {
            host.queue.submit(asset.projectId, asset.recipe, asset.label ?: "again", asset.parentId)
            local.update { it.copy(message = "Queued the same recipe again") }
        }
    }

    override fun compareWith(asset: StudioAsset) = local.update { s ->
        val first = s.comparePick
        when {
            first == null -> s.copy(comparePick = asset.id, message = "Pick a second ${asset.type.name.lowercase()} to compare")
            first == asset.id -> s.copy(comparePick = null, message = null)
            else -> s.copy(compare = first to asset.id, comparePick = null, message = null)
        }
    }

    override fun closeCompare() = local.update { it.copy(compare = null, comparePick = null) }
    override fun dismiss() = local.update { it.copy(message = null) }

    companion object {
        /** Whether the image room can load this recipe for editing. */
        fun editable(asset: StudioAsset): Boolean = asset.recipe.engine == EngineKinds.DIFFUSION
    }
}

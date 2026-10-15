package dev.wckdboy.autobot.feature.studio.ui

import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.wckdboy.autobot.core.data.StudioRepository
import dev.wckdboy.autobot.feature.imagine.generate.ImageCodec
import dev.wckdboy.autobot.feature.studio.engine.StudioHost
import dev.wckdboy.autobot.feature.studio.engine.StudioRecipes
import dev.wckdboy.autobot.feature.studio.media.WavFile
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioProject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decrypted previews for the screens, cached in memory only. */
@Singleton
class StudioAssetLoader @Inject constructor(private val store: StudioRepository) : AssetLoader {
    private val thumbs = LruCache<String, ImageBitmap>(96)
    private val waves = LruCache<String, FloatArray>(96)

    override suspend fun thumbnail(id: String): ImageBitmap? {
        thumbs.get(id)?.let { return it }
        val bytes = store.preview(id) ?: store.bytes(id)?.let { full -> withContext(Dispatchers.Default) { ImageCodec.thumbnail(full) } } ?: return null
        return withContext(Dispatchers.Default) { ImageCodec.decode(bytes, 512)?.asImageBitmap() }?.also { thumbs.put(id, it) }
    }

    override suspend fun peaks(id: String): FloatArray? {
        waves.get(id)?.let { return it }
        val peaks = store.preview(id)?.let(WavFile::decodePeaks)
            ?: store.bytes(id)?.let { bytes -> withContext(Dispatchers.Default) { WavFile.decode(bytes)?.let { WavFile.peaks(it) } } }
        return peaks?.also { waves.put(id, it) }
    }

    override suspend fun bytes(id: String): ByteArray? = store.bytes(id)
}

/** The shell: projects, the queue strip and the current project's assets (for input pickers). */
@Immutable
data class StudioShellState(
    val projects: List<StudioProject> = emptyList(),
    val projectId: String? = null,
    val jobs: List<StudioJob> = emptyList(),
    val assets: List<StudioAsset> = emptyList(),
) {
    val project: StudioProject? get() = projects.firstOrNull { it.id == projectId }
}

interface ShellActions {
    fun selectProject(id: String)
    fun createProject(name: String)
    fun renameProject(id: String, name: String)
    fun deleteProject(id: String)
    fun cancelJob(id: String)
    fun retryJob(id: String)
    fun clearFinished()
}

@HiltViewModel
class StudioViewModel @Inject constructor(
    private val host: StudioHost,
    val loader: StudioAssetLoader,
) : ViewModel(), ShellActions {

    init {
        host.start()
        viewModelScope.launch { host.currentProject() }
    }

    val state: StateFlow<StudioShellState> = combine(
        host.store.observeProjects(),
        host.settings.currentProjectId,
        host.queue.jobs,
        host.store.observeAssets(),
    ) { projects, current, jobs, assets ->
        val id = current?.takeIf { c -> projects.any { it.id == c } } ?: projects.firstOrNull()?.id
        StudioShellState(projects, id, jobs, assets.filter { it.projectId == id && it.recipe.engine !in StudioRecipes.HIDDEN_ENGINES })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StudioShellState())

    override fun selectProject(id: String) {
        viewModelScope.launch { host.settings.setCurrentProject(id) }
    }

    override fun createProject(name: String) {
        viewModelScope.launch { host.createProject(name) }
    }

    override fun renameProject(id: String, name: String) {
        viewModelScope.launch {
            val p = host.store.projects().firstOrNull { it.id == id } ?: return@launch
            host.store.putProject(p.copy(name = name.trim().ifEmpty { p.name }, updatedAt = System.currentTimeMillis()))
        }
    }

    override fun deleteProject(id: String) {
        viewModelScope.launch {
            host.store.deleteProject(id)
            host.currentProject()
        }
    }

    override fun cancelJob(id: String) {
        host.queue.cancel(id)
    }

    override fun retryJob(id: String) {
        host.queue.retry(id)
    }

    override fun clearFinished() = host.queue.clearFinished()
}

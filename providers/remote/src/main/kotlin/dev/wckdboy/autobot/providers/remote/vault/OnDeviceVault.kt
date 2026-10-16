package dev.wckdboy.autobot.providers.remote.vault

import android.net.Uri
import dev.wckdboy.autobot.core.data.VaultRepository
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelKind
import dev.wckdboy.autobot.core.models.ModelLibrary
import dev.wckdboy.autobot.engine.llama.LocalLlm
import dev.wckdboy.autobot.shared.vault.AddStatus
import dev.wckdboy.autobot.shared.vault.Embedder
import dev.wckdboy.autobot.shared.vault.Vault
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The newest ready GGUF embedding model from the Models tab, run by llama.cpp in the `:llm`
 * engine process. Nothing leaves the phone.
 */
class LocalEmbedder(private val library: ModelLibrary, private val engine: LocalLlm) : Embedder {
    override suspend fun modelId(): String? = current(library.all())?.id

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        val model = current(library.all()) ?: error(Vault.NO_MODEL)
        val path = model.paths[FileRole.MODEL] ?: error("The embedding model's file is missing; download it again in Models")
        return engine.embed(path, texts)
    }

    companion object {
        fun current(models: List<InstalledModel>): InstalledModel? = models
            .filter { it.isReady && it.kind == ModelKind.EMBEDDING && it.engine == EngineKind.LLAMA && it.paths[FileRole.MODEL] != null }
            .maxByOrNull { it.addedAt }
    }
}

/** Indexing as the Vault screen shows it: the file being embedded and what did not go in. */
data class IndexState(
    val running: Boolean = false,
    val name: String? = null,
    val done: Int = 0,
    val total: Int = 0,
    /** Files that were skipped or failed, one line each, from the last run. */
    val problems: List<String> = emptyList(),
) {
    val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
}

/**
 * The app's one [Vault] (encrypted tables + on-device embeddings), shared by the agent and the
 * Vault screen. Indexing runs on its own scope, so it continues when the screen closes.
 */
@Singleton
class OnDeviceVault @Inject constructor(
    val repository: VaultRepository,
    private val library: ModelLibrary,
    private val importer: VaultImporter,
    engine: LocalLlm,
) {
    val vault = Vault(repository, LocalEmbedder(library, engine))

    /** The ready embedding model, or null. */
    val model: Flow<InstalledModel?> = library.models.map { LocalEmbedder.current(it) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private val _state = MutableStateFlow(IndexState())
    val state: StateFlow<IndexState> = _state.asStateFlow()

    /** Reads and indexes [uris] one after another (after any running job). */
    fun add(uris: List<Uri>) = launch { problems ->
        for (uri in uris) {
            when (val read = importer.read(uri)) {
                is VaultImporter.Read.Skipped -> problems += "${read.name}: ${read.reason}"
                is VaultImporter.Read.Ok -> {
                    val name = read.text.name
                    _state.update { it.copy(name = name, done = 0, total = 0) }
                    val result = vault.add(read.text) { d, t -> _state.update { it.copy(done = d, total = t) } }
                    when (result.status) {
                        AddStatus.ADDED, AddStatus.UPDATED, AddStatus.UNCHANGED -> Unit
                        else -> problems += "$name: ${result.message ?: result.status.name.lowercase()}"
                    }
                    if (result.status == AddStatus.NO_MODEL) break
                }
            }
        }
    }

    /** Re-embeds documents indexed with an earlier embedding model. */
    fun reindex() = launch { _ ->
        vault.reindexStale { name, d, t -> _state.update { it.copy(name = name, done = d, total = t) } }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismissProblems() = _state.update { it.copy(problems = emptyList()) }

    private fun launch(block: suspend (MutableList<String>) -> Unit) {
        val previous = job
        job = scope.launch {
            previous?.join()
            val problems = ArrayList<String>()
            _state.value = IndexState(running = true)
            try {
                block(problems)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The embedding model failed to load or the disk is full: say so, don't crash the app.
                problems += "Indexing stopped: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _state.value = IndexState(problems = problems)
            }
        }
    }
}

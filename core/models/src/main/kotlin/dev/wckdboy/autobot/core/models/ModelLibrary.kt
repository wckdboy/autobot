package dev.wckdboy.autobot.core.models

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.ModelRowRepository
import dev.wckdboy.autobot.core.data.di.ApplicationScope
import dev.wckdboy.autobot.core.data.model.ModelRow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Registry of installed and in-progress models. Files live in app-private storage:
 * `files/models/<id>/<role>-<name>` (never on shared storage), so uninstall or panic wipe
 * removes them.
 */
@Singleton
class ModelLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rows: ModelRowRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    private val rootDir: File by lazy { File(context.filesDir, DIR) }

    /** The models folder, created if missing (a panic wipe removes it). */
    val root: File get() = rootDir.apply { if (!isDirectory) mkdirs() }

    /** [id]'s folder; not created here (the downloader creates it with the first file). */
    fun dirOf(id: String): File = File(rootDir, safeName(id))

    fun fileOf(id: String, file: ModelFile): File =
        File(dirOf(id), if (file.keepName) safeFileName(file.name) else "${file.role.name.lowercase()}-${safeName(file.name)}")

    private val decoder = ModelRowDecoder()

    /**
     * Every model, decoded off the main thread and shared: the many screens and services that
     * watch the registry share one database query and one decode per change (progress writes
     * during a download change it every second).
     */
    val models: Flow<List<InstalledModel>> = sharedModels(rows.observe(), scope, Dispatchers.Default) { list -> list.mapNotNull { it.toModel() } }

    suspend fun get(id: String): InstalledModel? = rows.get(id)?.toModel()

    suspend fun all(): List<InstalledModel> = rows.all().mapNotNull { it.toModel() }

    /** Registers [plan] as queued (keeps progress if it already exists and is not failed). */
    suspend fun add(plan: ModelPlan): InstalledModel {
        val existing = rows.get(plan.id)
        val now = System.currentTimeMillis()
        val row = ModelRow(
            id = plan.id,
            kind = plan.kind.name,
            source = plan.source.name,
            title = plan.title,
            subtitle = plan.subtitle,
            format = plan.format.name,
            engine = plan.engine.name,
            baseModel = plan.baseModel,
            license = plan.license,
            nsfw = plan.nsfw,
            totalBytes = plan.totalBytes,
            downloadedBytes = existing?.downloadedBytes ?: 0,
            // A ready model stays ready, and one downloading right now keeps downloading (queued
            // again, it would be fetched twice).
            status = existing?.status?.takeIf { it == ModelStatus.READY.name || it == ModelStatus.DOWNLOADING.name } ?: ModelStatus.QUEUED.name,
            error = null,
            manifestJson = ManifestJson.encodeToString(ModelManifest.serializer(), plan.manifest),
            addedAt = existing?.addedAt ?: now,
            updatedAt = now,
            backend = existing?.backend ?: Backend.AUTO.id,
        )
        rows.upsert(row)
        return row.toModel()!!
    }

    /** Stores what the server taught about the files (sizes, hashes); never brings back a removed model. */
    internal suspend fun update(model: InstalledModel, manifest: ModelManifest, totalBytes: Long) =
        rows.updateManifest(model.id, ManifestJson.encodeToString(ModelManifest.serializer(), manifest), totalBytes)

    internal suspend fun progress(id: String, downloaded: Long, status: ModelStatus, error: String? = null) =
        rows.updateProgress(id, downloaded, status.name, error)

    /** Moves [id] from [from] to [to], only if nothing (a pause, a removal) changed it meanwhile. */
    internal suspend fun transition(id: String, from: ModelStatus, to: ModelStatus, downloaded: Long, error: String? = null): Boolean =
        rows.transition(id, from.name, to.name, downloaded, error)

    /** [id] starts downloading, if it is still queued (not paused or removed meanwhile). */
    internal suspend fun start(id: String, downloaded: Long): Boolean =
        rows.transition(id, ModelStatus.QUEUED.name, ModelStatus.DOWNLOADING.name, downloaded, null)

    /** The running download's byte count; a model paused or removed meanwhile is left alone. */
    internal suspend fun downloaded(id: String, bytes: Long) = rows.updateBytes(id, bytes, ModelStatus.DOWNLOADING.name)

    /** Deletes the files and the registry entry. */
    suspend fun remove(id: String) {
        forget(id)
        deleteFiles(id)
    }

    /** Deletes the registry entry only: from here on the model is gone, whatever happens to its files. */
    internal suspend fun forget(id: String) = rows.delete(id)

    internal suspend fun deleteFiles(id: String) = withContext(Dispatchers.IO) { dirOf(id).deleteRecursively() }

    /**
     * Deletes model folders no registry entry owns (a removal interrupted after its entry was
     * deleted). Run before anything downloads in this process. Returns the folders deleted.
     */
    internal suspend fun sweepOrphans(): List<String> {
        val owned = rows.all().mapTo(HashSet()) { safeName(it.id) }
        return withContext(Dispatchers.IO) {
            rootDir.listFiles().orEmpty().filter { it.isDirectory && it.name !in owned }
                .onEach { it.deleteRecursively() }
                .map { it.name }
        }
    }

    /** The files of a ready model that are gone from disk (deleted outside the app); empty when all are there. */
    suspend fun missingFiles(model: InstalledModel): List<ModelFile> = withContext(Dispatchers.IO) {
        if (!model.isReady) emptyList() else model.manifest.files.filter { !fileOf(model.id, it).isFile }
    }

    /** Bytes on disk for [id] (finished files plus partial downloads). */
    fun bytesOnDisk(id: String): Long = dirOf(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun ModelRow.toModel(): InstalledModel? {
        val manifest = decoder.manifest(id, manifestJson) ?: return null
        val status = runCatching { ModelStatus.valueOf(status) }.getOrDefault(ModelStatus.FAILED)
        return InstalledModel(
            id = id,
            kind = runCatching { ModelKind.valueOf(kind) }.getOrDefault(ModelKind.OTHER),
            source = runCatching { ModelSource.valueOf(source) }.getOrDefault(ModelSource.HUGGING_FACE),
            title = title,
            subtitle = subtitle,
            format = runCatching { ModelFormat.valueOf(format) }.getOrDefault(ModelFormat.OTHER),
            engine = runCatching { EngineKind.valueOf(engine) }.getOrDefault(EngineKind.NONE),
            baseModel = baseModel,
            license = license,
            nsfw = nsfw,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            status = status,
            error = error,
            manifest = manifest,
            addedAt = addedAt,
            paths = if (status == ModelStatus.READY) manifest.files.associate { it.role to fileOf(id, it).path } else emptyMap(),
            backend = Backend.of(backend),
        )
    }

    companion object {
        private const val DIR = "models"

        private val Unsafe = Regex("[^A-Za-z0-9._-]")

        internal val ManifestJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        }

        /** File-system-safe, collision-resistant name for an id. */
        fun safeName(id: String): String {
            val cleaned = id.replace(Unsafe, "_").take(80)
            val hash = Integer.toHexString(id.hashCode())
            return "${cleaned}_$hash"
        }

        /** A plain file name (no hash suffix), for files other files refer to by name. */
        fun safeFileName(name: String): String {
            val base = name.substringAfterLast('/').replace(Unsafe, "_").take(120)
            return base.trimStart('.').ifEmpty { "file" }
        }
    }
}

/**
 * Decodes manifests once per (id, JSON): a progress write changes a row's bytes, not its
 * manifest, so the per-second re-emissions during a download reuse the decoded one.
 */
internal class ModelRowDecoder(private val capacity: Int = 256) {
    private val cache = object : LinkedHashMap<String, Pair<String, ModelManifest>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, ModelManifest>>?) = size > capacity
    }

    /** How many manifests were actually parsed (tests). */
    @Volatile
    var decoded = 0
        private set

    fun manifest(id: String, json: String): ModelManifest? {
        synchronized(cache) { cache[id]?.takeIf { it.first == json }?.let { return it.second } }
        decoded++
        val manifest = runCatching { ModelLibrary.ManifestJson.decodeFromString(ModelManifest.serializer(), json) }.getOrNull() ?: return null
        synchronized(cache) { cache[id] = json to manifest }
        return manifest
    }
}

/**
 * [rows] mapped by [decode] on [dispatcher], repeated values dropped, shared by every collector
 * (one upstream query while anyone watches, kept 5 s past the last one, latest value replayed).
 * A failing query ends the upstream (logged); the next subscriber starts it again.
 */
internal fun <T> sharedModels(rows: Flow<List<ModelRow>>, scope: CoroutineScope, dispatcher: CoroutineDispatcher, decode: (List<ModelRow>) -> List<T>): Flow<List<T>> =
    rows.distinctUntilChanged()
        .map(decode)
        .distinctUntilChanged()
        .flowOn(dispatcher)
        .catch { e -> Log.w("ModelLibrary", "model registry query failed", e) }
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)

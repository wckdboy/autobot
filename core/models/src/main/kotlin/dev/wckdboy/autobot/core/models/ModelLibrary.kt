package dev.wckdboy.autobot.core.models

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.ModelRowRepository
import dev.wckdboy.autobot.core.data.model.ModelRow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Registry of installed and in-progress models. Files live in app-private storage:
 * `files/models/<id>/<role>-<name>` (never on shared storage), so uninstall or panic wipe
 * removes them.
 */
@Singleton
class ModelLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rows: ModelRowRepository,
) {
    val root: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    fun dirOf(id: String): File = File(root, safeName(id))

    fun fileOf(id: String, file: ModelFile): File = File(dirOf(id), "${file.role.name.lowercase()}-${safeName(file.name)}")

    val models: Flow<List<InstalledModel>> = rows.observe().map { list -> list.mapNotNull { it.toModel() } }

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
            status = if (existing?.status == ModelStatus.READY.name) ModelStatus.READY.name else ModelStatus.QUEUED.name,
            error = null,
            manifestJson = ManifestJson.encodeToString(ModelManifest.serializer(), plan.manifest),
            addedAt = existing?.addedAt ?: now,
            updatedAt = now,
        )
        rows.upsert(row)
        return row.toModel()!!
    }

    internal suspend fun update(model: InstalledModel, manifest: ModelManifest, totalBytes: Long) {
        rows.get(model.id)?.let { row ->
            rows.upsert(
                row.copy(
                    manifestJson = ManifestJson.encodeToString(ModelManifest.serializer(), manifest),
                    totalBytes = totalBytes,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    internal suspend fun progress(id: String, downloaded: Long, status: ModelStatus, error: String? = null) =
        rows.updateProgress(id, downloaded, status.name, error)

    /** Deletes the files and the registry entry. */
    suspend fun remove(id: String) {
        withContext(Dispatchers.IO) { dirOf(id).deleteRecursively() }
        rows.delete(id)
    }

    /** Bytes on disk for [id] (finished files plus partial downloads). */
    fun bytesOnDisk(id: String): Long = dirOf(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun ModelRow.toModel(): InstalledModel? {
        val manifest = runCatching { ManifestJson.decodeFromString(ModelManifest.serializer(), manifestJson) }.getOrNull() ?: return null
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
        )
    }

    companion object {
        private const val DIR = "models"

        /** File-system-safe, collision-resistant name for an id. */
        fun safeName(id: String): String {
            val cleaned = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            val hash = Integer.toHexString(id.hashCode())
            return "${cleaned}_$hash"
        }
    }
}

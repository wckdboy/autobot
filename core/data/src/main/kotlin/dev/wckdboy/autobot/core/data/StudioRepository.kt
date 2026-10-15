package dev.wckdboy.autobot.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.StudioAssetRow
import dev.wckdboy.autobot.core.data.model.StudioJobRow
import dev.wckdboy.autobot.core.data.model.StudioProjectRow
import dev.wckdboy.autobot.core.data.model.StudioSnippetRow
import dev.wckdboy.autobot.core.data.model.StudioWorkflowRow
import dev.wckdboy.autobot.core.security.KeyManager
import dev.wckdboy.autobot.core.security.crypto.AesGcmEnvelope
import dev.wckdboy.autobot.shared.studio.AssetFilter
import dev.wckdboy.autobot.shared.studio.JobPersistence
import dev.wckdboy.autobot.shared.studio.PromptSnippet
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.StudioCodec
import dev.wckdboy.autobot.shared.studio.StudioJob
import dev.wckdboy.autobot.shared.studio.StudioProject
import dev.wckdboy.autobot.shared.studio.StudioStore
import dev.wckdboy.autobot.shared.studio.StudioWorkflow
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The Creative Studio's store on Android: projects, asset records (with their recipes), saved
 * workflows, the prompt library and the job queue in the encrypted database; asset bytes as
 * AES-256-GCM sealed files under `files/studio/` with the media key (the asset id plus the file's
 * role as associated data, like the gallery), together with an encrypted preview per asset (a
 * thumbnail for images and videos, waveform peaks for audio). Nothing reaches shared storage
 * unless the user exports an asset.
 */
@Singleton
class StudioRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DatabaseHolder,
    private val keyManager: KeyManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StudioStore, JobPersistence {
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    private fun dao() = database.get().studioDao()

    // -----------------------------------------------------------------------------------------
    // Observed for the screens

    fun observeProjects(): Flow<List<StudioProject>> =
        flow { emitAll(dao().observeProjects()) }.map { rows -> rows.mapNotNull { decode { StudioCodec.decodeProject(it.json) } } }.flowOn(io)

    fun observeAssets(): Flow<List<StudioAsset>> =
        flow { emitAll(dao().observeAssets()) }.map { rows -> rows.mapNotNull { decode { StudioCodec.decodeAsset(it.json) } } }.flowOn(io)

    fun observeWorkflows(): Flow<List<StudioWorkflow>> =
        flow { emitAll(dao().observeWorkflows()) }.map { rows -> rows.mapNotNull { decode { StudioCodec.decodeWorkflow(it.json) } } }.flowOn(io)

    fun observeSnippets(): Flow<List<PromptSnippet>> =
        flow { emitAll(dao().observeSnippets()) }.map { rows -> rows.mapNotNull { decode { StudioCodec.decodeSnippet(it.json) } } }.flowOn(io)

    // -----------------------------------------------------------------------------------------
    // StudioStore

    override suspend fun projects(): List<StudioProject> = withContext(io) { dao().projects().mapNotNull { decode { StudioCodec.decodeProject(it.json) } } }

    override suspend fun putProject(project: StudioProject) = withContext(io) {
        dao().upsertProject(StudioProjectRow(project.id, project.name, project.updatedAt, StudioCodec.encodeProject(project)))
    }

    override suspend fun deleteProject(id: String) = withContext(io) {
        val ids = dao().assetIds(id)
        dao().deleteProject(id)
        ids.forEach(::deleteFiles)
    }

    override suspend fun assets(filter: AssetFilter): List<StudioAsset> = withContext(io) {
        val rows = filter.projectId?.let { dao().assetsOf(it) } ?: dao().assets()
        rows.mapNotNull { decode { StudioCodec.decodeAsset(it.json) } }.filter(filter::matches)
    }

    override suspend fun asset(id: String): StudioAsset? = withContext(io) { dao().asset(id)?.let { decode { StudioCodec.decodeAsset(it.json) } } }

    override suspend fun putAsset(asset: StudioAsset) = withContext(io) { dao().upsertAsset(asset.toRow()) }

    override suspend fun deleteAsset(id: String) = withContext(io) {
        dao().deleteAsset(id)
        deleteFiles(id)
    }

    override suspend fun workflows(): List<StudioWorkflow> = withContext(io) { dao().workflows().mapNotNull { decode { StudioCodec.decodeWorkflow(it.json) } } }

    override suspend fun putWorkflow(workflow: StudioWorkflow) = withContext(io) {
        dao().upsertWorkflow(StudioWorkflowRow(workflow.id, workflow.name, workflow.updatedAt, StudioCodec.encodeWorkflow(workflow)))
    }

    override suspend fun deleteWorkflow(id: String) = withContext(io) { dao().deleteWorkflow(id) }

    override suspend fun snippets(): List<PromptSnippet> = withContext(io) { dao().snippets().mapNotNull { decode { StudioCodec.decodeSnippet(it.json) } } }

    override suspend fun putSnippet(snippet: PromptSnippet) = withContext(io) { dao().upsertSnippet(StudioSnippetRow(snippet.id, StudioCodec.encodeSnippet(snippet))) }

    override suspend fun deleteSnippet(id: String) = withContext(io) { dao().deleteSnippet(id) }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        val asset = asset(id) ?: return
        putAsset(asset.copy(favorite = favorite))
    }

    // -----------------------------------------------------------------------------------------
    // JobPersistence

    override suspend fun load(): List<StudioJob> = withContext(io) { dao().jobs().mapNotNull { decode { StudioCodec.decodeJob(it.json) } } }

    override suspend fun save(job: StudioJob) = withContext(io) { dao().upsertJob(StudioJobRow(job.id, job.createdAt, StudioCodec.encodeJob(job))) }

    override suspend fun remove(id: String) = withContext(io) { dao().deleteJob(id) }

    // -----------------------------------------------------------------------------------------
    // Sealed files

    /** Stores an asset's bytes (encrypted). */
    suspend fun writeBytes(id: String, bytes: ByteArray) = withContext(io) { write(file(id, DATA), id + DATA, bytes) }

    /** An asset's bytes, or null when the file is missing or cannot be opened. */
    suspend fun bytes(id: String): ByteArray? = withContext(io) { read(file(id, DATA), id + DATA) }

    /** Stores an asset's preview: a JPEG thumbnail (image, video) or waveform peaks (audio). */
    suspend fun writePreview(id: String, bytes: ByteArray) = withContext(io) { write(file(id, PREVIEW), id + PREVIEW, bytes) }

    suspend fun preview(id: String): ByteArray? = withContext(io) { read(file(id, PREVIEW), id + PREVIEW) }

    private fun deleteFiles(id: String) {
        if (!id.matches(SAFE_ID)) return
        file(id, DATA).delete()
        file(id, PREVIEW).delete()
    }

    private fun file(id: String, suffix: String): File {
        require(id.matches(SAFE_ID)) { "Invalid asset id" }
        return File(dir, id + suffix)
    }

    private fun write(file: File, aad: String, bytes: ByteArray) {
        val sealed = AesGcmEnvelope.seal(keyManager.mediaKey(), bytes, aad.encodeToByteArray())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not store ${file.name}")
        }
    }

    private fun read(file: File, aad: String): ByteArray? {
        if (!file.isFile) return null
        return try {
            AesGcmEnvelope.open(keyManager.mediaKey(), file.readBytes(), aad.encodeToByteArray())
        } catch (_: Exception) {
            null
        }
    }

    private inline fun <T> decode(block: () -> T): T? = try {
        block()
    } catch (_: Exception) { // a damaged or unknown record is skipped, never a crash
        null
    }

    companion object {
        private const val DIR = "studio"
        private const val DATA = ".bin"
        private const val PREVIEW = ".preview"
        val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")

        fun StudioAsset.toRow() = StudioAssetRow(id, projectId, type.name, createdAt, favorite, durationMs, StudioCodec.encodeAsset(this))
    }
}

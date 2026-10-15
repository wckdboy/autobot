package dev.wckdboy.autobot.core.data.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.core.data.AttachmentRepository
import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.data.MemoryRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.SessionEventRepository
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
import dev.wckdboy.autobot.core.data.model.ChatFolderRow
import dev.wckdboy.autobot.core.data.model.Conversation
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.data.model.Provider
import dev.wckdboy.autobot.core.data.model.ProviderKind
import dev.wckdboy.autobot.core.data.model.ProviderRouting
import dev.wckdboy.autobot.core.data.model.SessionEventRow
import dev.wckdboy.autobot.core.data.settings.SettingsRepository
import dev.wckdboy.autobot.core.data.settings.SettingsSchema
import dev.wckdboy.autobot.shared.backup.BackupConversation
import dev.wckdboy.autobot.shared.backup.BackupException
import dev.wckdboy.autobot.shared.backup.BackupFolder
import dev.wckdboy.autobot.shared.backup.FolderImport
import dev.wckdboy.autobot.shared.backup.BackupImage
import dev.wckdboy.autobot.shared.backup.BackupManifest
import dev.wckdboy.autobot.shared.backup.BackupProvider
import dev.wckdboy.autobot.shared.backup.BackupReader
import dev.wckdboy.autobot.shared.backup.BackupWriter
import dev.wckdboy.autobot.shared.backup.JvmBackupCrypto
import dev.wckdboy.autobot.shared.backup.MANIFEST
import dev.wckdboy.autobot.shared.memory.MemoryRules
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import okio.sink
import okio.source
import okio.utf8Size

/** What an import added (existing items with the same id are kept, not overwritten). */
data class ImportSummary(val conversations: Int, val providers: Int, val images: Int, val attachments: Int, val memories: Int = 0)

/**
 * Passphrase-sealed export and import of everything the user owns, in the shared `.autobot`
 * format (see `shared/.../backup/Backup.kt`), so a user can move between Android and iOS.
 *
 * Not exported: API keys, incognito sessions, downloaded models (re-download them) and the app's
 * own keys. On import, security settings (network mode, loopback, app lock, lock timeout) keep
 * this device's values: a file must never switch the app online or unlock it. The same goes for
 * the memory switch and the web search setup; memory notes themselves are merged.
 */
@Singleton
class BackupService @Inject constructor(
    private val database: DatabaseHolder,
    private val providers: ProviderRepository,
    private val sessions: SessionEventRepository,
    private val gallery: GalleryRepository,
    private val attachments: AttachmentRepository,
    private val settings: SettingsRepository,
    private val memory: MemoryRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun export(out: OutputStream, passphrase: String, app: String, onProgress: (String) -> Unit = {}) = withContext(io) {
        val db = database.get()
        val conversations = db.conversationDao().observeAll().first()
        val images = db.galleryDao().observeAll().first()
        // One conversation's log in memory at a time: here for its attachments, below to write it.
        val attachmentIds = LinkedHashSet<String>()
        conversations.forEach { c ->
            sessions.load(c.id).forEach { row ->
                runCatching { SessionJson.decodeFromString(LogEntry.serializer(), row.json) }.getOrNull()
                    ?.let { (it.event as? SessionEvent.UserMessage)?.images }?.let(attachmentIds::addAll)
            }
        }
        val manifest = BackupManifest(
            settings = SettingsSchema.encode(settings.settings.first()),
            providers = providers.observeProviders().first().map {
                BackupProvider(it.id, it.kind.name, it.displayName, it.baseUrl, it.defaultModel, it.routing.name, it.presetId, it.helperModel)
            },
            conversations = conversations.map {
                BackupConversation(it.id, it.title, it.providerId, it.model, it.createdAt, it.updatedAt, it.pinned, it.archived, it.folderId)
            },
            folders = db.chatFolderDao().all().map { BackupFolder(it.id, it.name, it.createdAt) },
            gallery = images.map { it.toBackup() },
            attachments = attachmentIds.toList(),
            memory = memory.all(),
        )
        val sink = out.sink().buffer()
        onProgress("deriving key…")
        val writer = BackupWriter(sink, JvmBackupCrypto, passphrase, app, "android", System.currentTimeMillis())
        writer.entry(MANIFEST, BackupManifest.encode(manifest))
        conversations.forEachIndexed { i, c ->
            onProgress("chats ${i + 1}/${conversations.size}")
            val rows = sessions.load(c.id)
            writer.entry(BackupManifest.sessionEntry(c.id), rows.sumOf { it.json.utf8Size() + 1 }, RowsSource(rows))
        }
        images.forEachIndexed { i, item ->
            onProgress("images ${i + 1}/${images.size}")
            gallery.image(item.id)?.let { writer.entry(imageEntry(item.id), it) }
        }
        attachmentIds.forEach { id -> attachments.bytes(id)?.let { writer.entry(BackupManifest.attachmentEntry(id), it) } }
        writer.finish()
        sink.flush()
    }

    suspend fun import(input: InputStream, passphrase: String, onProgress: (String) -> Unit = {}): ImportSummary = withContext(io) {
        val db = database.get()
        val reader = BackupReader(input.source().buffer(), JvmBackupCrypto)
        onProgress("deriving key…")
        var manifest: BackupManifest? = null
        var added = ImportSummary(0, 0, 0, 0)
        val newConversations = HashSet<String>()
        reader.entries(passphrase) { name, size, data ->
            // Entry callbacks are synchronous; the repositories are suspend, so bridge per entry.
            runBlocking {
                when {
                    name == MANIFEST -> {
                        val m = BackupManifest.decode(data.readByteArray(size)).also { manifest = it }
                        applySettings(m.settings)
                        m.providers.forEach { p ->
                            if (providers.getProvider(p.id) == null) {
                                providers.save(p.toProvider())
                                added = added.copy(providers = added.providers + 1)
                            }
                        }
                        val folders = importFolders(m)
                        m.conversations.forEach { c ->
                            if (db.conversationDao().get(c.id) == null) {
                                db.conversationDao().upsert(
                                    Conversation(c.id, c.title, c.providerId, c.model, c.createdAt, c.updatedAt, c.pinned, c.archived, folders.localId(c.folderId)),
                                )
                                newConversations += c.id
                            }
                        }
                        added = added.copy(conversations = newConversations.size, memories = importMemory(m))
                    }
                    manifest == null -> throw BackupException("Damaged export: manifest missing")
                    name.startsWith("sessions/") -> {
                        val id = name.removePrefix("sessions/").removeSuffix(".jsonl")
                        if (id in newConversations) importSession(id, size, data)
                    }
                    name.startsWith("gallery/") -> {
                        val item = manifest!!.gallery.firstOrNull { it.file == name }
                        if (item != null && SAFE_ID.matches(item.id) && gallery.get(item.id) == null) {
                            val bytes = data.readByteArray(size)
                            gallery.save(item.toItem(), bytes, thumbnail(bytes) ?: bytes)
                            added = added.copy(images = added.images + 1)
                            onProgress("images ${added.images}/${manifest!!.gallery.size}")
                        }
                    }
                    name.startsWith("attachments/") -> {
                        val id = name.removePrefix("attachments/")
                        if (SAFE_ID.matches(id) && attachments.bytes(id) == null) {
                            attachments.restore(id, data.readByteArray(size))
                            added = added.copy(attachments = added.attachments + 1)
                        }
                    }
                }
            }
        }
        if (manifest == null) throw BackupException("Damaged export: manifest missing")
        added
    }

    /** Reads one conversation's log line by line and stores it in batches (a long chat need not fit in memory at once). */
    private suspend fun importSession(id: String, size: Long, data: BufferedSource) {
        var remaining = size
        val batch = ArrayList<SessionEventRow>(SESSION_BATCH)
        while (remaining > 0) {
            val newline = data.indexOf('\n'.code.toByte(), 0, remaining)
            val length = if (newline < 0) remaining else newline + 1
            val line = data.readUtf8(length).trimEnd('\n')
            remaining -= length
            if (line.isBlank()) continue
            runCatching { SessionJson.decodeFromString(LogEntry.serializer(), line) }.getOrNull()
                ?.let { batch += SessionEventRow(id, it.seq, it.time, line) }
            if (batch.size >= SESSION_BATCH) {
                sessions.append(batch.toList())
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) sessions.append(batch)
    }

    /** Streams log rows as JSON lines without building the whole text. */
    private class RowsSource(rows: List<SessionEventRow>) : Source {
        private val iterator = rows.iterator()
        private val pending = Buffer()

        override fun read(sink: Buffer, byteCount: Long): Long {
            while (pending.size == 0L) {
                if (!iterator.hasNext()) return -1
                pending.writeUtf8(iterator.next().json).writeByte('\n'.code)
            }
            return pending.read(sink, byteCount)
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }

    /** Adds the export's folders, merging those that exist by id or name ([FolderImport]). */
    private suspend fun importFolders(manifest: BackupManifest): FolderImport {
        val dao = database.get().chatFolderDao()
        val plan = FolderImport.plan(dao.all().map { BackupFolder(it.id, it.name, it.createdAt) }, manifest.folders)
        plan.create.forEach { dao.upsert(ChatFolderRow(it.id, it.name, it.createdAt)) }
        return plan
    }

    /** Adds notes whose id is new, up to the memory cap; existing notes are kept as they are. */
    private suspend fun importMemory(manifest: BackupManifest): Int {
        val existing = memory.all()
        val ids = existing.mapTo(HashSet()) { it.id }
        var count = existing.size
        var added = 0
        manifest.memory.forEach { incoming ->
            val note = MemoryRules.sanitize(incoming) ?: return@forEach
            if (note.id in ids || count >= MemoryRules.MAX_NOTES) return@forEach
            memory.upsert(note)
            ids += note.id
            count++
            added++
        }
        return added
    }

    /** Applies appearance and behaviour settings; security-relevant ones keep this device's values. */
    private suspend fun applySettings(values: Map<String, String>) {
        if (values.isEmpty()) return
        val incoming = SettingsSchema.decode(values)
        settings.setThemeMode(incoming.themeMode)
        settings.setAmoled(incoming.amoled)
        settings.setDynamicColor(incoming.dynamicColor)
        settings.setIncognitoByDefault(incoming.incognitoByDefault)
    }

    private fun GalleryItem.toBackup() = BackupImage(
        id, imageEntry(id), createdAt, mode, prompt, negativePrompt, width, height, seed, steps, cfgScale, sampler, engine, model,
        durationMs, paramsJson, favorite,
    )

    private fun BackupImage.toItem() = GalleryItem(
        id, createdAt, mode, prompt, negativePrompt, width, height, seed, steps, cfgScale, sampler, engine, model, durationMs, paramsJson, favorite,
    )

    private fun BackupProvider.toProvider() = Provider(
        id = id,
        kind = ProviderKind.entries.firstOrNull { it.name == kind } ?: ProviderKind.OPENAI_COMPATIBLE,
        displayName = displayName,
        baseUrl = baseUrl,
        defaultModel = defaultModel,
        routing = ProviderRouting.entries.firstOrNull { it.name == routing } ?: ProviderRouting.INHERIT,
        presetId = presetId,
        helperModel = helperModel?.takeIf { it.isNotBlank() },
    )

    private fun imageEntry(id: String) = "gallery/$id.png"

    /** JPEG thumbnail whose longer side is 384 px (the gallery grid), decoded subsampled. */
    private fun thumbnail(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 384) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = 384f / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")

        /** Log rows stored per insert while importing. */
        const val SESSION_BATCH = 500
    }
}

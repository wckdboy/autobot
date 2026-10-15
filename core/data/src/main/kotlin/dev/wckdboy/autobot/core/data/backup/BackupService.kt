package dev.wckdboy.autobot.core.data.backup

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.wckdboy.autobot.agent.core.session.LogEntry
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.SessionJson
import dev.wckdboy.autobot.core.data.AttachmentRepository
import dev.wckdboy.autobot.core.data.GalleryRepository
import dev.wckdboy.autobot.core.data.ProviderRepository
import dev.wckdboy.autobot.core.data.SessionEventRepository
import dev.wckdboy.autobot.core.data.db.DatabaseHolder
import dev.wckdboy.autobot.core.data.di.IoDispatcher
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
import dev.wckdboy.autobot.shared.backup.BackupImage
import dev.wckdboy.autobot.shared.backup.BackupManifest
import dev.wckdboy.autobot.shared.backup.BackupProvider
import dev.wckdboy.autobot.shared.backup.BackupReader
import dev.wckdboy.autobot.shared.backup.BackupWriter
import dev.wckdboy.autobot.shared.backup.JvmBackupCrypto
import dev.wckdboy.autobot.shared.backup.MANIFEST
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source

/** What an import added (existing items with the same id are kept, not overwritten). */
data class ImportSummary(val conversations: Int, val providers: Int, val images: Int, val attachments: Int)

/**
 * Passphrase-sealed export and import of everything the user owns, in the shared `.autobot`
 * format (see `shared/.../backup/Backup.kt`), so a user can move between Android and iOS.
 *
 * Not exported: API keys, incognito sessions, downloaded models (re-download them) and the app's
 * own keys. On import, security settings (network mode, loopback, app lock, lock timeout) keep
 * this device's values: a file must never switch the app online or unlock it.
 */
@Singleton
class BackupService @Inject constructor(
    private val database: DatabaseHolder,
    private val providers: ProviderRepository,
    private val sessions: SessionEventRepository,
    private val gallery: GalleryRepository,
    private val attachments: AttachmentRepository,
    private val settings: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun export(out: OutputStream, passphrase: String, app: String, onProgress: (String) -> Unit = {}) = withContext(io) {
        val db = database.get()
        val conversations = db.conversationDao().observeAll().first()
        val images = db.galleryDao().observeAll().first()
        val logs = conversations.associate { c -> c.id to sessions.load(c.id) }
        val attachmentIds = logs.values.flatten()
            .mapNotNull { row -> runCatching { SessionJson.decodeFromString(LogEntry.serializer(), row.json) }.getOrNull() }
            .flatMap { (it.event as? SessionEvent.UserMessage)?.images.orEmpty() }
            .distinct()
        val manifest = BackupManifest(
            settings = SettingsSchema.encode(settings.settings.first()),
            providers = providers.observeProviders().first().map {
                BackupProvider(it.id, it.kind.name, it.displayName, it.baseUrl, it.defaultModel, it.routing.name)
            },
            conversations = conversations.map { BackupConversation(it.id, it.title, it.providerId, it.model, it.createdAt, it.updatedAt) },
            gallery = images.map { it.toBackup() },
            attachments = attachmentIds,
        )
        val sink = out.sink().buffer()
        onProgress("deriving key…")
        val writer = BackupWriter(sink, JvmBackupCrypto, passphrase, app, "android", System.currentTimeMillis())
        writer.entry(MANIFEST, BackupManifest.encode(manifest))
        logs.forEach { (id, rows) ->
            writer.entry(BackupManifest.sessionEntry(id), rows.joinToString("") { it.json + "\n" }.encodeToByteArray())
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
                        m.conversations.forEach { c ->
                            if (db.conversationDao().get(c.id) == null) {
                                db.conversationDao().upsert(Conversation(c.id, c.title, c.providerId, c.model, c.createdAt, c.updatedAt))
                                newConversations += c.id
                            }
                        }
                        added = added.copy(conversations = newConversations.size)
                    }
                    manifest == null -> throw BackupException("Damaged export: manifest missing")
                    name.startsWith("sessions/") -> {
                        val id = name.removePrefix("sessions/").removeSuffix(".jsonl")
                        if (id in newConversations) {
                            val rows = data.readUtf8(size).lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                                runCatching { SessionJson.decodeFromString(LogEntry.serializer(), line) }.getOrNull()
                                    ?.let { SessionEventRow(id, it.seq, it.time, line) }
                            }.toList()
                            sessions.append(rows)
                        }
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
    )

    private fun imageEntry(id: String) = "gallery/$id.png"

    /** JPEG thumbnail whose longer side is 384 px (the gallery grid). */
    private fun thumbnail(bytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = 384f / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}

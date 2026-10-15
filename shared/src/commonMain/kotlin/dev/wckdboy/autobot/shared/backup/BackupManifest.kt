package dev.wckdboy.autobot.shared.backup

import dev.wckdboy.autobot.shared.chats.ChatNames
import dev.wckdboy.autobot.shared.memory.MemoryNote
import kotlinx.serialization.Serializable

/**
 * The first entry of an export ([MANIFEST]). Everything is platform-neutral, so an export made on
 * Android imports on iOS and back. API keys are never exported: providers come back without one.
 *
 * Other entries:
 * - `sessions/<conversationId>.jsonl`: the session log, one `LogEntry` JSON per line
 * - `gallery/<id>.png` (or `.jpg`, `.webp`): the image as generated
 * - `attachments/<id>`: images attached to chat messages (referenced by id from session events)
 */
@Serializable
class BackupManifest(
    /** [dev.wckdboy.autobot.core.data.settings.SettingsSchema] string form. */
    val settings: Map<String, String> = emptyMap(),
    val providers: List<BackupProvider> = emptyList(),
    val conversations: List<BackupConversation> = emptyList(),
    val gallery: List<BackupImage> = emptyList(),
    val attachments: List<String> = emptyList(),
    /** Local memory notes (absent in exports made before 1.4, which import with none). */
    val memory: List<MemoryNote> = emptyList(),
    /** Chat folders (absent in exports made before 1.5, which import with none). */
    val folders: List<BackupFolder> = emptyList(),
) {
    companion object {
        fun sessionEntry(conversationId: String) = "sessions/$conversationId.jsonl"

        fun attachmentEntry(id: String) = "attachments/$id"

        fun encode(manifest: BackupManifest): ByteArray = BackupJson.encodeToString(serializer(), manifest).encodeToByteArray()

        fun decode(bytes: ByteArray): BackupManifest =
            runCatching { BackupJson.decodeFromString(serializer(), bytes.decodeToString()) }.getOrElse { throw BackupException("Damaged export manifest") }
    }
}

/**
 * A provider without its API key. [kind] is a `ProviderKind` name, [routing]
 * `INHERIT`/`DIRECT`/`TOR`/`SOCKS5`, [presetId] a `ProviderPresets` id and [helperModel] its
 * helper model (both absent in older files).
 */
@Serializable
class BackupProvider(
    val id: String,
    val kind: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val routing: String = "INHERIT",
    val presetId: String? = null,
    val helperModel: String? = null,
)

/**
 * A chat. [pinned], [archived] and [folderId] (a [BackupFolder] id) are absent in files made
 * before 1.5; older apps ignore them.
 */
@Serializable
class BackupConversation(
    val id: String,
    val title: String,
    val providerId: String? = null,
    val model: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val folderId: String? = null,
)

/** A chat folder. */
@Serializable
class BackupFolder(
    val id: String,
    val name: String,
    val createdAt: Long = 0,
)

/**
 * How imported folders join the existing ones: a folder whose id or name (ignoring case) exists is
 * merged into it; the rest are [create]d. [ids] maps every imported folder id to the local one.
 */
class FolderImport(val create: List<BackupFolder>, val ids: Map<String, String>) {
    /** The local folder for an imported chat's folder id (null: none, or unknown). */
    fun localId(importedId: String?): String? = importedId?.let { ids[it] }

    companion object {
        fun plan(existing: List<BackupFolder>, incoming: List<BackupFolder>): FolderImport {
            val known = existing.toMutableList()
            val create = ArrayList<BackupFolder>()
            val ids = HashMap<String, String>()
            for (f in incoming) {
                val name = ChatNames.folderName(f.name)
                if (name.isEmpty() || f.id in ids) continue
                val match = known.firstOrNull { it.id == f.id } ?: known.firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (match != null) {
                    ids[f.id] = match.id
                } else {
                    val folder = BackupFolder(f.id, name, f.createdAt)
                    create += folder
                    known += folder
                    ids[f.id] = f.id
                }
            }
            return FolderImport(create, ids)
        }
    }
}

/** A gallery image; [file] is its entry name. Generation parameters as the gallery stores them. */
@Serializable
class BackupImage(
    val id: String,
    val file: String,
    val createdAt: Long,
    val mode: String,
    val prompt: String,
    val negativePrompt: String = "",
    val width: Int,
    val height: Int,
    val seed: Long,
    val steps: Int,
    val cfgScale: Float,
    val sampler: String,
    val engine: String,
    val model: String? = null,
    val durationMs: Long = 0,
    val paramsJson: String = "{}",
    val favorite: Boolean = false,
)

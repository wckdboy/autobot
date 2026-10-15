package dev.wckdboy.autobot.shared.backup

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
) {
    companion object {
        fun sessionEntry(conversationId: String) = "sessions/$conversationId.jsonl"

        fun attachmentEntry(id: String) = "attachments/$id"

        fun encode(manifest: BackupManifest): ByteArray = BackupJson.encodeToString(serializer(), manifest).encodeToByteArray()

        fun decode(bytes: ByteArray): BackupManifest =
            runCatching { BackupJson.decodeFromString(serializer(), bytes.decodeToString()) }.getOrElse { throw BackupException("Damaged export manifest") }
    }
}

/** A provider without its API key. [kind] is a `ProviderKind` name, [routing] `INHERIT`/`DIRECT`/`TOR`/`SOCKS5`. */
@Serializable
class BackupProvider(
    val id: String,
    val kind: String,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val routing: String = "INHERIT",
)

@Serializable
class BackupConversation(
    val id: String,
    val title: String,
    val providerId: String? = null,
    val model: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

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

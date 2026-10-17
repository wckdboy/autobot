package dev.wckdboy.autobot.core.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.wckdboy.autobot.providers.remote.ProviderPreset
import dev.wckdboy.autobot.providers.remote.ProviderPresets

/**
 * A chat thread. [providerId]/[model] record the last selection for this conversation. [pinned]
 * chats float to the top of the list, [archived] ones only show in the archive; [folderId] is a
 * [ChatFolderRow] (null: no folder).
 */
@Entity(tableName = "conversations", indices = [Index("updatedAt")])
data class Conversation(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String?,
    val model: String?,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val folderId: String? = null,
)

/** A folder chats can be moved into. Deleting it moves its chats out (they are kept). */
@Entity(tableName = "chat_folders")
data class ChatFolderRow(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
)

/**
 * A chat's searchable text (its prompts and replies, see `:shared` `ChatSearch`), denormalized
 * from the session log as it is written. Deleted with its conversation.
 */
@Entity(
    tableName = "conversation_text",
    foreignKeys = [
        ForeignKey(entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ConversationTextRow(
    @PrimaryKey val conversationId: String,
    val text: String,
)

/** A search candidate: a chat whose title or text contains the pre-filter term. */
data class ConversationSearchRow(
    val id: String,
    val title: String,
    val text: String?,
)

enum class MessageRole { SYSTEM, USER, ASSISTANT }

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId"), Index(value = ["conversationId", "createdAt"])],
)
data class Message(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    val createdAt: Long,
    val tokenCount: Int? = null,
)

/** Per-provider network routing. [INHERIT] follows the global network mode. */
enum class ProviderRouting { DIRECT, TOR, SOCKS5, INHERIT }

/**
 * A configured remote model provider. The API key itself is never stored here; [apiKeyRef] is
 * an opaque reference into the encrypted SecretStore. [presetId] names its `ProviderPresets`
 * entry; rows from before 1.4 have none and are matched by kind and host. [helperModel] runs
 * subagents and compaction summaries (null: the chat's own model).
 */
@Entity(tableName = "providers")
data class Provider(
    @PrimaryKey val id: String,
    val kind: ProviderKind,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val routing: ProviderRouting = ProviderRouting.INHERIT,
    val apiKeyRef: String? = null,
    @ColumnInfo(defaultValue = "NULL") val presetId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val helperModel: String? = null,
) {
    /** Its preset (by id, else by kind and host for older rows). */
    fun preset(): ProviderPreset = ProviderPresets.resolve(presetId, kind, baseUrl)
}

/** A provider's fetched model list ([json] is a shared `ModelCache`), deleted with the provider. */
@Entity(
    tableName = "provider_models",
    foreignKeys = [
        ForeignKey(entity = Provider::class, parentColumns = ["id"], childColumns = ["providerId"], onDelete = ForeignKey.CASCADE),
    ],
)
data class ProviderModelsRow(
    @PrimaryKey val providerId: String,
    val fetchedAt: Long,
    val json: String,
)

/**
 * A configured MCP server (Streamable HTTP). The bearer token is never stored here; [tokenRef]
 * is an opaque reference into the encrypted SecretStore. Untrusted servers ask before every tool
 * call; [updatedAt] changes on every save so the agent reconnects with the new settings.
 */
@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val routing: ProviderRouting = ProviderRouting.INHERIT,
    val enabled: Boolean = true,
    val trusted: Boolean = false,
    val tokenRef: String? = null,
    val updatedAt: Long = 0,
)

/**
 * One agent session log row (see `:agent:core` `LogEntry`). The JSON payload is opaque to this
 * layer. Rows cascade with their conversation. The primary key (`sessionId`, `seq`) already
 * indexes `sessionId` for the cascade and per-session reads (a separate index was dropped in 11).
 */
@Entity(
    tableName = "session_events",
    primaryKeys = ["sessionId", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionEventRow(
    val sessionId: String,
    val seq: Long,
    val time: Long,
    val json: String,
)

/**
 * A generated image. Pixels live in an encrypted file (`files/gallery/<id>.bin`); this row holds
 * the searchable parameters. [paramsJson] keeps the full request for "reuse parameters".
 */
@Entity(tableName = "gallery_items", indices = [Index("createdAt"), Index("favorite")])
data class GalleryItem(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val mode: String,
    val prompt: String,
    val negativePrompt: String,
    val width: Int,
    val height: Int,
    val seed: Long,
    val steps: Int,
    val cfgScale: Float,
    val sampler: String,
    val engine: String,
    val model: String?,
    val durationMs: Long,
    val paramsJson: String,
    val favorite: Boolean = false,
)

/**
 * An installed (or installing) model. Queryable fields are columns; the file list and engine
 * hints live in [manifestJson] (owned by `:core:models`).
 */
@Entity(tableName = "models", indices = [Index("kind"), Index("status")])
data class ModelRow(
    @PrimaryKey val id: String,
    val kind: String,
    val source: String,
    val title: String,
    val subtitle: String,
    val format: String,
    val engine: String,
    val baseModel: String?,
    val license: String?,
    val nsfw: Boolean,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String,
    val error: String?,
    val manifestJson: String,
    val addedAt: Long,
    val updatedAt: Long,
    /** Where to run it: `auto`, `cpu`, `gpu` or `npu` (set by the user or the benchmark). */
    @ColumnInfo(defaultValue = "auto") val backend: String = "auto",
)

/** One benchmark run of a model on one backend; the newest successful run per backend wins. */
@Entity(tableName = "benchmarks", indices = [Index("modelId")])
data class BenchmarkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelId: String,
    /** `cpu`, `gpu` or `npu`: where it actually ran (an accelerator can fall back to the CPU). */
    val backend: String,
    /** Text models: prompt processing and generation speed in tokens per second. */
    val promptTps: Double?,
    val generationTps: Double?,
    /** Image models: average time of one denoising step, in milliseconds. */
    val stepMs: Double?,
    val loadMs: Long?,
    val error: String?,
    val createdAt: Long,
)

/**
 * A local memory note (see `:shared` `MemoryNote`). [tags] are normalised (no commas), stored
 * comma-joined. Notes are independent of conversations: deleting a chat keeps what it taught.
 */
@Entity(tableName = "memory_notes", indices = [Index("updatedAt")])
data class MemoryNoteRow(
    @PrimaryKey val id: String,
    val text: String,
    val tags: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sessionId: String?,
)

/**
 * A usage bucket of the spend and quota board (see `:shared` `UsageRecord`): tokens one provider
 * and model used in one time bucket. No prompts, no chat ids.
 */
@Entity(tableName = "usage_records", indices = [Index("start")])
data class UsageRecordRow(
    @PrimaryKey val id: String,
    val start: Long,
    val span: Long,
    val provider: String,
    val model: String,
    val providerName: String?,
    val presetId: String?,
    val onDevice: Boolean,
    val input: Long,
    val output: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val reasoning: Long,
    val requests: Int,
    val unreported: Int,
)

/** The board's price table and subscription windows (`:shared` `UsagePlan` JSON), one row. */
@Entity(tableName = "usage_plan")
data class UsagePlanRow(@PrimaryKey val id: String, val json: String)

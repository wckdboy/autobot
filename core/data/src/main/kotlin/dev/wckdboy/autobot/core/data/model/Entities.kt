package dev.wckdboy.autobot.core.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A chat thread. [providerId]/[model] record the last selection for this conversation. */
@Entity(tableName = "conversations", indices = [Index("updatedAt")])
data class Conversation(
    @PrimaryKey val id: String,
    val title: String,
    val providerId: String?,
    val model: String?,
    val createdAt: Long,
    val updatedAt: Long,
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

/** [LOCAL] runs on this phone (llama.cpp engine); its base URL is unused and never dialled. */
enum class ProviderKind { DEEPSEEK, OPENAI_COMPATIBLE, OPENROUTER, OLLAMA, LOCAL }

/** Per-provider network routing. [INHERIT] follows the global network mode. */
enum class ProviderRouting { DIRECT, TOR, SOCKS5, INHERIT }

/**
 * A configured remote model provider. The API key itself is never stored here; [apiKeyRef] is
 * an opaque reference into the encrypted SecretStore.
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
)

/**
 * One agent session log row (see `:agent:core` `LogEntry`). The JSON payload is opaque to this
 * layer. Rows cascade with their conversation.
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
    indices = [Index("sessionId")],
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

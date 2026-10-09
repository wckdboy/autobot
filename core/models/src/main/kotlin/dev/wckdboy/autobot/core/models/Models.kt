package dev.wckdboy.autobot.core.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a model is for. Drives which engine and screen use it. */
@Serializable
enum class ModelKind(val label: String) {
    CHAT("chat"),
    CODE("code"),
    IMAGE("image"),
    LORA("lora"),
    VAE("vae"),
    EMBEDDING("embedding"),
    UPSCALER("upscaler"),
    OTHER("other"),
}

@Serializable
enum class ModelFormat(val label: String) {
    GGUF("GGUF"),
    SAFETENSORS("safetensors"),
    CKPT("ckpt"),

    /** Local Dream packages (QNN context binaries / MNN graphs). Not runnable by Autobot engines. */
    LOCAL_DREAM("Local Dream"),
    OTHER("file"),
}

/** Which built-in engine runs the model, if any. */
@Serializable
enum class EngineKind(val label: String) { LLAMA("llama.cpp"), DIFFUSION("sd.cpp"), NONE("none") }

@Serializable
enum class ModelSource(val label: String) { CATALOG("catalog"), HUGGING_FACE("hugging face"), CIVITAI("civitai") }

enum class ModelStatus { QUEUED, DOWNLOADING, PAUSED, READY, FAILED }

/** Role of a file within a model bundle (maps onto stable-diffusion.cpp / llama.cpp inputs). */
@Serializable
enum class FileRole { MODEL, DIFFUSION_MODEL, LLM, CLIP_L, CLIP_G, T5XXL, VAE, TAESD, MMPROJ, OTHER }

/** Which account's credentials a download URL may receive. */
@Serializable
enum class AuthHost { HUGGING_FACE, CIVITAI }

@Serializable
data class ModelFile(
    val role: FileRole,
    val name: String,
    val url: String,
    /** Expected size, or 0 when unknown until the server reports it. */
    val sizeBytes: Long = 0,
    /** Lower-case hex SHA-256 when known up front; otherwise adopted from the server. */
    val sha256: String? = null,
    val auth: AuthHost? = null,
)

/** Engine defaults the model was made for (applied when it is selected). */
@Serializable
data class Recommended(
    val steps: Int? = null,
    val cfg: Float? = null,
    val sampler: String? = null,
    val scheduler: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val contextLength: Int? = null,
    val negativePrompt: String? = null,
)

@Serializable
data class ModelManifest(
    val files: List<ModelFile>,
    val pageUrl: String? = null,
    val recommended: Recommended? = null,
    val trainedWords: List<String> = emptyList(),
    val gated: Boolean = false,
    val notes: String? = null,
)

/** Everything needed to install a model. */
data class ModelPlan(
    val id: String,
    val kind: ModelKind,
    val source: ModelSource,
    val title: String,
    val subtitle: String,
    val format: ModelFormat,
    val engine: EngineKind,
    val baseModel: String? = null,
    val license: String? = null,
    val nsfw: Boolean = false,
    val manifest: ModelManifest,
) {
    val totalBytes: Long get() = manifest.files.sumOf { it.sizeBytes }
}

/** A model in the registry (installed or in progress). */
data class InstalledModel(
    val id: String,
    val kind: ModelKind,
    val source: ModelSource,
    val title: String,
    val subtitle: String,
    val format: ModelFormat,
    val engine: EngineKind,
    val baseModel: String?,
    val license: String?,
    val nsfw: Boolean,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: ModelStatus,
    val error: String?,
    val manifest: ModelManifest,
    val addedAt: Long,
    /** Absolute paths of finished files by role (empty until READY). */
    val paths: Map<FileRole, String>,
) {
    val isReady: Boolean get() = status == ModelStatus.READY
    val fraction: Float get() = if (totalBytes <= 0) 0f else (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    val runnable: Boolean get() = isReady && engine != EngineKind.NONE
}

internal val ManifestJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/** Formats bytes as `2.4 GB` / `512 MB`. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
    bytes >= 1_000_000L -> String.format(java.util.Locale.ROOT, "%d MB", bytes / 1_000_000)
    bytes >= 1_000L -> String.format(java.util.Locale.ROOT, "%d KB", bytes / 1_000)
    else -> "$bytes B"
}

/** Guesses the file format from its name (and the repo owner, for Local Dream packages). */
fun formatOf(name: String, owner: String? = null): ModelFormat {
    val n = name.lowercase()
    return when {
        owner.equals("xororz", ignoreCase = true) || "_qnn" in n || n.endsWith(".mnn") -> ModelFormat.LOCAL_DREAM
        n.endsWith(".gguf") -> ModelFormat.GGUF
        n.endsWith(".safetensors") -> ModelFormat.SAFETENSORS
        n.endsWith(".ckpt") || n.endsWith(".pt") || n.endsWith(".pth") -> ModelFormat.CKPT
        else -> ModelFormat.OTHER
    }
}

/** The built-in engine that can run [kind] in [format] (GGUF is used by both engines). */
fun engineFor(kind: ModelKind, format: ModelFormat): EngineKind = when {
    format == ModelFormat.LOCAL_DREAM || format == ModelFormat.OTHER -> EngineKind.NONE
    kind == ModelKind.CHAT || kind == ModelKind.CODE -> if (format == ModelFormat.GGUF) EngineKind.LLAMA else EngineKind.NONE
    kind == ModelKind.IMAGE || kind == ModelKind.LORA || kind == ModelKind.VAE -> EngineKind.DIFFUSION
    else -> EngineKind.NONE
}

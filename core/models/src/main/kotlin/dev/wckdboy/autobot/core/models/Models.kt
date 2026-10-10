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
    SPEECH("speech"),
    OTHER("other"),
}

@Serializable
enum class ModelFormat(val label: String) {
    GGUF("GGUF"),

    /** whisper.cpp model (`ggml-*.bin`). */
    GGML("ggml"),
    SAFETENSORS("safetensors"),
    CKPT("ckpt"),

    /** Precompiled Hexagon NPU package (QNN context binaries + MNN text encoders), Local Dream layout. */
    QNN("QNN"),

    /** MNN graphs for the GPU (OpenCL) or CPU, Local Dream layout. */
    MNN("MNN"),

    /** Local Dream package Autobot cannot run (e.g. an architecture it does not support yet). */
    LOCAL_DREAM("Local Dream"),
    OTHER("file"),
}

/** Which built-in engine runs the model, if any. */
@Serializable
enum class EngineKind(val label: String) { LLAMA("llama.cpp"), DIFFUSION("sd.cpp"), NPU("qnn · mnn"), WHISPER("whisper.cpp"), NONE("none") }

@Serializable
enum class ModelSource(val label: String) { CATALOG("catalog"), HUGGING_FACE("hugging face"), CIVITAI("civitai") }

enum class ModelStatus { QUEUED, DOWNLOADING, PAUSED, READY, FAILED }

/** Where a model runs. [AUTO] lets the benchmark (or the engine: NPU → GPU → CPU) decide. */
enum class Backend(val id: String, val label: String) {
    AUTO("auto", "auto"),
    CPU("cpu", "cpu"),
    GPU("gpu", "gpu"),
    NPU("npu", "npu"),
    ;

    companion object {
        fun of(id: String?): Backend = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Role of a file within a model bundle (maps onto stable-diffusion.cpp / llama.cpp inputs). */
@Serializable
enum class FileRole {
    MODEL, DIFFUSION_MODEL, LLM, CLIP_L, CLIP_G, T5XXL, VAE, TAESD, MMPROJ, OTHER,

    // NPU / MNN packages (Local Dream layout)
    TOKENIZER, TOKEN_EMB, POS_EMB, TEXT_ENCODER, TOKEN_EMB_2, POS_EMB_2, TEXT_ENCODER_2, UNET, VAE_DECODER, VAE_ENCODER,

    /** Companion data of another file (an MNN `.weight` file), kept next to it under its own name. */
    WEIGHTS,
}

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
    /** Set when the file is one entry of a remote zip: it is fetched by byte range and inflated. */
    val zipEntry: ZipEntryRef? = null,
    /** Store under [name] itself instead of a role-prefixed name (MNN finds `.weight` files by name). */
    val keepName: Boolean = false,
)

/**
 * Where an entry lives inside a remote zip. [entry] is resolved against the central directory at
 * download time; the other fields are filled in then.
 */
@Serializable
data class ZipEntryRef(
    val entry: String,
    val dataOffset: Long = -1,
    val compressedSize: Long = 0,
    val method: Int = 0,
    val crc32: Long = 0,
)

/** How the NPU engine runs a package. */
@Serializable
data class NpuPackage(
    /** `sd15` or `sdxl`. */
    val arch: String,
    /** `qnn` (Hexagon NPU) or `mnn` (GPU / CPU). */
    val runtime: String,
    /** v-prediction model (e.g. NoobAI v-pred). */
    val vpred: Boolean = false,
    /** Hexagon generation the binaries were compiled for (`v69`…`v79`), when known. */
    val htpArch: String? = null,
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
    /** Set for NPU/MNN image packages. */
    val npu: NpuPackage? = null,
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
    /** The user's choice for this model ([Backend.AUTO] unless they picked one). */
    val backend: Backend = Backend.AUTO,
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
        "_qnn" in n || n.endsWith(".bin") && owner.equals("xororz", ignoreCase = true) -> ModelFormat.QNN
        n.endsWith(".mnn") || owner.equals("xororz", ignoreCase = true) && n.endsWith(".zip") -> ModelFormat.MNN
        owner.equals("xororz", ignoreCase = true) -> ModelFormat.LOCAL_DREAM
        n.endsWith(".gguf") -> ModelFormat.GGUF
        n.startsWith("ggml-") && n.endsWith(".bin") -> ModelFormat.GGML
        n.endsWith(".safetensors") -> ModelFormat.SAFETENSORS
        n.endsWith(".ckpt") || n.endsWith(".pt") || n.endsWith(".pth") -> ModelFormat.CKPT
        else -> ModelFormat.OTHER
    }
}

/** The built-in engine that can run [kind] in [format] (GGUF is used by both engines). */
fun engineFor(kind: ModelKind, format: ModelFormat): EngineKind = when {
    kind == ModelKind.SPEECH -> if (format == ModelFormat.GGML) EngineKind.WHISPER else EngineKind.NONE
    format == ModelFormat.LOCAL_DREAM || format == ModelFormat.OTHER -> EngineKind.NONE
    format == ModelFormat.QNN || format == ModelFormat.MNN -> if (kind == ModelKind.IMAGE || kind == ModelKind.UPSCALER) EngineKind.NPU else EngineKind.NONE
    kind == ModelKind.CHAT || kind == ModelKind.CODE -> if (format == ModelFormat.GGUF) EngineKind.LLAMA else EngineKind.NONE
    kind == ModelKind.IMAGE || kind == ModelKind.LORA || kind == ModelKind.VAE -> EngineKind.DIFFUSION
    else -> EngineKind.NONE
}

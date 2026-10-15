package dev.wckdboy.autobot.shared.studio

import kotlin.random.Random
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * The Creative Studio's data model: projects, assets and the recipe that made each asset. All of
 * it is plain kotlinx.serialization JSON ([StudioJson]); fields are only ever added (with
 * defaults), so records written by an older app and `.autobot` exports keep decoding.
 */

/** What an asset is. */
@Serializable
enum class AssetType { IMAGE, AUDIO, VIDEO }

/** Engine kinds a [Recipe] can name (strings, so a newer engine kind still decodes). */
object EngineKinds {
    /** Text/image-to-image diffusion (on-device Core ML / sd.cpp / QNN / MNN, or an SD API backend). */
    const val DIFFUSION = "diffusion"

    /** Image upscaler. */
    const val UPSCALE = "upscale"

    /** Text-to-audio (Stable Audio Open Small on device). */
    const val AUDIO = "audio"

    /** Text-to-speech (Kokoro voiceover). */
    const val SPEECH = "speech"

    /** A saved ComfyUI workflow on a PC backend. */
    const val COMFYUI = "comfyui"
}

/** A reference to an asset used as an input; [role] says how (`image`, `mask`, `audio`, …). */
@Serializable
data class AssetRef(val assetId: String, val role: String = "image")

/**
 * A typed parameter value. JSON: `{"type":"int","value":20}`, `float`, `string`, `bool`, or
 * `{"type":"asset","ref":{…}}` for an input asset.
 */
@Serializable
sealed class ParamValue {
    @Serializable
    @SerialName("int")
    data class IntValue(val value: Long) : ParamValue()

    @Serializable
    @SerialName("float")
    data class FloatValue(val value: Double) : ParamValue()

    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : ParamValue()

    @Serializable
    @SerialName("bool")
    data class BoolValue(val value: Boolean) : ParamValue()

    @Serializable
    @SerialName("asset")
    data class AssetValue(val ref: AssetRef) : ParamValue()

    /** The value as a number (ints and floats), else null. */
    fun asDouble(): Double? = when (this) {
        is IntValue -> value.toDouble()
        is FloatValue -> value
        is StringValue -> value.toDoubleOrNull()
        else -> null
    }

    fun asLong(): Long? = when (this) {
        is IntValue -> value
        is FloatValue -> value.toLong()
        is StringValue -> value.toLongOrNull()
        else -> null
    }

    /** A short display form for labels (`7.5`, `euler`, `true`). */
    fun display(): String = when (this) {
        is IntValue -> value.toString()
        is FloatValue -> formatNumber(value)
        is StringValue -> value
        is BoolValue -> value.toString()
        is AssetValue -> ref.assetId
    }

    companion object {
        fun of(value: Int): ParamValue = IntValue(value.toLong())
        fun of(value: Long): ParamValue = IntValue(value)
        fun of(value: Double): ParamValue = FloatValue(value)
        fun of(value: String): ParamValue = StringValue(value)
        fun of(value: Boolean): ParamValue = BoolValue(value)

        internal fun formatNumber(value: Double): String {
            if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
            val s = ((value * 10000).let { kotlin.math.round(it) } / 10000).toString()
            return s
        }
    }
}

/**
 * Everything needed to make an asset again: the engine kind ([EngineKinds]), the backend
 * ([backendId] null = on device), the model, every parameter, the input assets, the workflow
 * (for ComfyUI) and the seed. Well-known param names: `prompt`, `negative`, `steps`, `cfg`,
 * `sampler`, `scheduler`, `width`, `height`, `strength`, `seconds`, `frames`, `fps`, `voice`,
 * `speed`, `text`; workflows use the names of their [ParamSchema].
 */
@Serializable
data class Recipe(
    val engine: String,
    val backendId: String? = null,
    val model: String? = null,
    val params: Map<String, ParamValue> = emptyMap(),
    val inputs: List<AssetRef> = emptyList(),
    val workflowId: String? = null,
    val workflowVersion: Int? = null,
    val seed: Long? = null,
    /** The kind of asset this recipe makes. */
    val output: AssetType = AssetType.IMAGE,
) {
    /** This recipe with [name] set; `seed` and `model` set the dedicated fields. */
    fun with(name: String, value: ParamValue): Recipe = when (name) {
        SEED -> copy(seed = value.asLong())
        MODEL -> copy(model = value.display())
        else -> copy(params = params + (name to value))
    }

    /** The value of [name] (also `seed` and `model`), or null. */
    fun value(name: String): ParamValue? = when (name) {
        SEED -> seed?.let { ParamValue.IntValue(it) }
        MODEL -> model?.let { ParamValue.StringValue(it) }
        else -> params[name]
    }

    fun string(name: String): String? = (params[name] as? ParamValue.StringValue)?.value

    /** "Use as input": this recipe with [asset] as its input in [role] (replacing that role). */
    fun withInput(asset: String, role: String = "image"): Recipe =
        copy(inputs = inputs.filter { it.role != role } + AssetRef(asset, role))

    companion object {
        const val SEED = "seed"
        const val MODEL = "model"
        const val PROMPT = "prompt"
        const val NEGATIVE = "negative"
    }
}

/** A Studio project: a named group of assets. */
@Serializable
data class StudioProject(
    val id: String,
    val name: String,
    val coverAssetId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One generation (image, audio clip, video) in a project, with its full [recipe]. The bytes live
 * in the platform's encrypted gallery store under [id]; [parentId] is the asset it was made from
 * ("use as input").
 */
@Serializable
data class StudioAsset(
    val id: String,
    val projectId: String,
    val type: AssetType,
    val recipe: Recipe,
    val createdAt: Long,
    /** `image/png`, `audio/flac`, `video/mp4`, … */
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    /** Audio and video length; 0 for images. */
    val durationMs: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val favorite: Boolean = false,
    val parentId: String? = null,
    /** Where it came from in a batch or grid, e.g. `steps=20 · cfg=7`. */
    val label: String? = null,
)

/** Library filter: by project, type and favourites; newest first. */
data class AssetFilter(
    val projectId: String? = null,
    val types: Set<AssetType> = AssetType.entries.toSet(),
    val favoritesOnly: Boolean = false,
) {
    fun matches(asset: StudioAsset): Boolean =
        (projectId == null || asset.projectId == projectId) && asset.type in types && (!favoritesOnly || asset.favorite)
}

/** The Studio's JSON: stable field names, unknown fields ignored, defaults written. */
val StudioJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Encode/decode helpers for platform stores that keep JSON strings (and for Swift). */
object StudioCodec {
    fun encodeProject(value: StudioProject): String = StudioJson.encodeToString(StudioProject.serializer(), value)
    fun decodeProject(json: String): StudioProject = StudioJson.decodeFromString(StudioProject.serializer(), json)
    fun encodeAsset(value: StudioAsset): String = StudioJson.encodeToString(StudioAsset.serializer(), value)
    fun decodeAsset(json: String): StudioAsset = StudioJson.decodeFromString(StudioAsset.serializer(), json)
    fun encodeRecipe(value: Recipe): String = StudioJson.encodeToString(Recipe.serializer(), value)
    fun decodeRecipe(json: String): Recipe = StudioJson.decodeFromString(Recipe.serializer(), json)
    fun encodeWorkflow(value: StudioWorkflow): String = StudioJson.encodeToString(StudioWorkflow.serializer(), value)
    fun decodeWorkflow(json: String): StudioWorkflow = StudioJson.decodeFromString(StudioWorkflow.serializer(), json)
    fun encodeSnippet(value: PromptSnippet): String = StudioJson.encodeToString(PromptSnippet.serializer(), value)
    fun decodeSnippet(json: String): PromptSnippet = StudioJson.decodeFromString(PromptSnippet.serializer(), json)
    fun encodeJob(value: StudioJob): String = StudioJson.encodeToString(StudioJob.serializer(), value)
    fun decodeJob(json: String): StudioJob = StudioJson.decodeFromString(StudioJob.serializer(), json)
    fun encodeAssets(values: List<StudioAsset>): String = StudioJson.encodeToString(ListSerializer(StudioAsset.serializer()), values)
    fun decodeAssets(json: String): List<StudioAsset> = StudioJson.decodeFromString(ListSerializer(StudioAsset.serializer()), json)

    /** File extension for [mimeType] (export names, backup entries). */
    fun extension(mimeType: String): String = when (mimeType.substringBefore(';').trim().lowercase()) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "audio/flac" -> "flac"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/mpeg" -> "mp3"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/mp4", "audio/aac" -> "m4a"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/quicktime" -> "mov"
        else -> "bin"
    }

    /** MIME type and asset type for a file name (ComfyUI outputs). */
    fun typeOf(fileName: String): Pair<String, AssetType> = when (fileName.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png" to AssetType.IMAGE
        "jpg", "jpeg" -> "image/jpeg" to AssetType.IMAGE
        "webp" -> "image/webp" to AssetType.IMAGE
        "gif" -> "image/gif" to AssetType.VIDEO
        "flac" -> "audio/flac" to AssetType.AUDIO
        "wav" -> "audio/wav" to AssetType.AUDIO
        "mp3" -> "audio/mpeg" to AssetType.AUDIO
        "ogg", "opus" -> "audio/ogg" to AssetType.AUDIO
        "m4a", "aac" -> "audio/mp4" to AssetType.AUDIO
        "mp4" -> "video/mp4" to AssetType.VIDEO
        "webm" -> "video/webm" to AssetType.VIDEO
        "mov" -> "video/quicktime" to AssetType.VIDEO
        else -> "application/octet-stream" to AssetType.IMAGE
    }
}

/** Random ids (`p-…` projects, `a-…` assets, `j-…` jobs, `w-…` workflows). */
object StudioIds {
    private const val HEX = "0123456789abcdef"

    fun new(prefix: String, random: Random = Random.Default): String =
        "$prefix-" + (1..12).map { HEX[random.nextInt(HEX.length)] }.joinToString("")
}

/**
 * Where Studio records live: the platform's encrypted gallery store (projects, asset metadata,
 * saved workflows, prompt library). Asset bytes are written by the platform's [OutputSink].
 * Implementations may block; callers run them on an I/O dispatcher.
 */
interface StudioStore {
    suspend fun projects(): List<StudioProject>
    suspend fun putProject(project: StudioProject)

    /** Removes the project and all its assets. */
    suspend fun deleteProject(id: String)

    suspend fun assets(filter: AssetFilter = AssetFilter()): List<StudioAsset>
    suspend fun asset(id: String): StudioAsset?
    suspend fun putAsset(asset: StudioAsset)
    suspend fun deleteAsset(id: String)

    suspend fun workflows(): List<StudioWorkflow>
    suspend fun putWorkflow(workflow: StudioWorkflow)
    suspend fun deleteWorkflow(id: String)

    suspend fun snippets(): List<PromptSnippet>
    suspend fun putSnippet(snippet: PromptSnippet)
    suspend fun deleteSnippet(id: String)
}

/**
 * The Swift-facing form of [StudioStore]: blocking calls with JSON records ([StudioCodec]),
 * called on a background thread. Wrap with [BridgedStudioStore].
 */
interface StudioStoreBridge {
    fun records(kind: String): List<String>
    fun put(kind: String, id: String, json: String)
    fun remove(kind: String, id: String)

    companion object {
        const val PROJECTS = "projects"
        const val ASSETS = "assets"
        const val WORKFLOWS = "workflows"
        const val SNIPPETS = "snippets"
    }
}

/** [StudioStore] over a host [StudioStoreBridge]; damaged records are skipped. */
class BridgedStudioStore(private val bridge: StudioStoreBridge) : StudioStore {
    private fun <T> all(kind: String, decode: (String) -> T): List<T> =
        bridge.records(kind).mapNotNull { runCatching { decode(it) }.getOrNull() }

    override suspend fun projects() = all(StudioStoreBridge.PROJECTS, StudioCodec::decodeProject)
    override suspend fun putProject(project: StudioProject) = bridge.put(StudioStoreBridge.PROJECTS, project.id, StudioCodec.encodeProject(project))
    override suspend fun deleteProject(id: String) {
        assets(AssetFilter(projectId = id)).forEach { bridge.remove(StudioStoreBridge.ASSETS, it.id) }
        bridge.remove(StudioStoreBridge.PROJECTS, id)
    }

    override suspend fun assets(filter: AssetFilter) =
        all(StudioStoreBridge.ASSETS, StudioCodec::decodeAsset).filter(filter::matches).sortedByDescending { it.createdAt }

    override suspend fun asset(id: String) = all(StudioStoreBridge.ASSETS, StudioCodec::decodeAsset).firstOrNull { it.id == id }
    override suspend fun putAsset(asset: StudioAsset) = bridge.put(StudioStoreBridge.ASSETS, asset.id, StudioCodec.encodeAsset(asset))
    override suspend fun deleteAsset(id: String) = bridge.remove(StudioStoreBridge.ASSETS, id)
    override suspend fun workflows() = all(StudioStoreBridge.WORKFLOWS, StudioCodec::decodeWorkflow)
    override suspend fun putWorkflow(workflow: StudioWorkflow) = bridge.put(StudioStoreBridge.WORKFLOWS, workflow.id, StudioCodec.encodeWorkflow(workflow))
    override suspend fun deleteWorkflow(id: String) = bridge.remove(StudioStoreBridge.WORKFLOWS, id)
    override suspend fun snippets() = all(StudioStoreBridge.SNIPPETS, StudioCodec::decodeSnippet)
    override suspend fun putSnippet(snippet: PromptSnippet) = bridge.put(StudioStoreBridge.SNIPPETS, snippet.id, StudioCodec.encodeSnippet(snippet))
    override suspend fun deleteSnippet(id: String) = bridge.remove(StudioStoreBridge.SNIPPETS, id)
}

/** A [StudioStore] in memory (tests, previews). */
class InMemoryStudioStore : StudioStore {
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val projects = LinkedHashMap<String, StudioProject>()
    private val assets = LinkedHashMap<String, StudioAsset>()
    private val workflows = LinkedHashMap<String, StudioWorkflow>()
    private val snippets = LinkedHashMap<String, PromptSnippet>()

    private suspend fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    override suspend fun projects() = locked { projects.values.sortedByDescending { it.updatedAt } }
    override suspend fun putProject(project: StudioProject) = locked { projects[project.id] = project }
    override suspend fun deleteProject(id: String) = locked {
        projects.remove(id)
        assets.values.removeAll { it.projectId == id }
        Unit
    }

    override suspend fun assets(filter: AssetFilter) = locked { assets.values.filter(filter::matches).sortedByDescending { it.createdAt } }
    override suspend fun asset(id: String) = locked { assets[id] }
    override suspend fun putAsset(asset: StudioAsset) = locked { assets[asset.id] = asset }
    override suspend fun deleteAsset(id: String) = locked { assets.remove(id).let { } }
    override suspend fun workflows() = locked { workflows.values.toList() }
    override suspend fun putWorkflow(workflow: StudioWorkflow) = locked { workflows[workflow.id] = workflow }
    override suspend fun deleteWorkflow(id: String) = locked { workflows.remove(id).let { } }
    override suspend fun snippets() = locked { snippets.values.toList() }
    override suspend fun putSnippet(snippet: PromptSnippet) = locked { snippets[snippet.id] = snippet }
    override suspend fun deleteSnippet(id: String) = locked { snippets.remove(id).let { } }
}

/** An input asset's bytes, as the platform's encrypted store returns them. */
class AssetBytes(val name: String, val mimeType: String, val bytes: ByteArray)

/** Reads an asset's bytes (to upload it as a workflow input); null when it is gone. */
fun interface AssetReader {
    suspend fun read(assetId: String): AssetBytes?
}

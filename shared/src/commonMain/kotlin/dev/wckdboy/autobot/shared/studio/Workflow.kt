package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.shared.studio.comfy.InputSpec
import dev.wckdboy.autobot.shared.studio.comfy.ObjectInfo
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Saved ComfyUI workflows: import of the API-format graph, automatic parameter detection, the
 * typed parameter schema the UIs render, schema editing (expose / rename / remove / reorder),
 * validation against the server's /object_info, and applying values back into the graph.
 */

/** Control type of a workflow parameter. */
@Serializable
enum class ParamType { INT, FLOAT, STRING, ENUM, IMAGE, AUDIO, SEED, BOOL }

/** A node input a parameter writes to. */
@Serializable
data class InputTarget(val nodeId: String, val input: String)

/**
 * One parameter of a workflow, as the UI renders it. [targets] are the graph inputs it sets (one
 * value can feed several nodes, e.g. a seed shared by two samplers). [role] marks the detected
 * well-known parameters (`prompt`, `negative`, `seed`, `steps`, `cfg`, `sampler`, `scheduler`,
 * `denoise`, `width`, `height`, `batch`, `frames`, `fps`, `seconds`, `checkpoint`, `lora`,
 * `lora_strength`, `image`, `audio`); null for inputs the user exposed.
 */
@Serializable
data class WorkflowParam(
    val name: String,
    val label: String,
    val type: ParamType,
    val targets: List<InputTarget>,
    val default: ParamValue? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    /** Choices of an [ParamType.ENUM] (from /object_info when known). */
    val options: List<String> = emptyList(),
    /** A long text (prompt fields). */
    val multiline: Boolean = false,
    val role: String? = null,
    /** Shown under "More" rather than up front. */
    val advanced: Boolean = false,
)

/**
 * A saved workflow: the API-format [graph] (node id → `{class_type, inputs}`), its parameter
 * schema, what it makes and which nodes make it. [version] goes up with every edit; assets record
 * the version that made them.
 */
@Serializable
data class StudioWorkflow(
    val id: String,
    val name: String,
    val graph: JsonObject,
    val params: List<WorkflowParam>,
    val output: AssetType,
    val outputNodes: List<String>,
    val version: Int = 1,
    /** The ComfyUI backend it was imported for (any ComfyUI backend can run it). */
    val backendId: String? = null,
    val description: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun param(name: String): WorkflowParam? = params.firstOrNull { it.name == name }
}

/** A problem with a workflow or its values, said plainly. */
class WorkflowException(message: String) : Exception(message)

/** Result of [WorkflowImport.parse]. */
sealed interface ImportResult {
    class Graph(val graph: JsonObject) : ImportResult
    class Invalid(val message: String) : ImportResult
}

/** Reading a workflow file. */
object WorkflowImport {
    const val UI_FORMAT_MESSAGE =
        "This is a ComfyUI editor file, not an API workflow. In ComfyUI, enable the dev mode options, then use " +
            "Workflow → Export (API) (\"Save (API Format)\") and import that file."

    /**
     * Accepts the API format (node id → `{class_type, inputs}`), also wrapped as `{"prompt": …}`
     * (a `/prompt` body or a history entry). The editor (UI) format (`nodes` + `links`) is refused
     * with [UI_FORMAT_MESSAGE].
     */
    fun parse(json: String): ImportResult {
        val root = runCatching { StudioJson.parseToJsonElement(json) }.getOrNull() as? JsonObject
            ?: return ImportResult.Invalid("This isn't a workflow: the file is not a JSON object")
        if (root["nodes"] is JsonArray && root.containsKey("links")) return ImportResult.Invalid(UI_FORMAT_MESSAGE)
        val graph = (root["prompt"] as? JsonObject)?.takeIf { isGraph(it) } ?: root
        if (graph.isEmpty()) return ImportResult.Invalid("The workflow has no nodes")
        val bad = graph.entries.firstOrNull { (_, v) -> (v as? JsonObject)?.let { n -> n["class_type"].let { it is JsonPrimitive && it.isString } && n["inputs"] is JsonObject } != true }
        if (bad != null) return ImportResult.Invalid("Node \"${bad.key}\" has no class_type/inputs: is this an API-format workflow?")
        return ImportResult.Graph(graph)
    }

    private fun isGraph(o: JsonObject) = o.isNotEmpty() && o.values.all { (it as? JsonObject)?.get("class_type") != null }

    /** Imports [json] as a new workflow with detected parameters (throws [WorkflowException]). */
    fun create(
        name: String,
        json: String,
        info: ObjectInfo? = null,
        id: String = StudioIds.new("w"),
        now: Long = 0,
        backendId: String? = null,
    ): StudioWorkflow {
        val graph = when (val r = parse(json)) {
            is ImportResult.Graph -> r.graph
            is ImportResult.Invalid -> throw WorkflowException(r.message)
        }
        val d = WorkflowDetector.detect(graph, info)
        if (d.outputNodes.isEmpty()) throw WorkflowException("The workflow has no output node (SaveImage, SaveAudio, a video save node…)")
        return StudioWorkflow(id, name.trim().ifEmpty { "Workflow" }, graph, d.params, d.output, d.outputNodes, 1, backendId, "", now, now)
    }
}

/** What [WorkflowDetector.detect] found. */
class Detection(val params: List<WorkflowParam>, val output: AssetType, val outputNodes: List<String>)

/** A literal node input in an API graph. */
internal class GraphView(val graph: JsonObject) {
    val ids: List<String> = graph.keys.sortedWith(compareBy<String>({ it.substringBefore(':').toLongOrNull() ?: Long.MAX_VALUE }, { it }))

    fun classOf(id: String): String? = ((graph[id] as? JsonObject)?.get("class_type") as? JsonPrimitive)?.contentOrNull
    fun inputs(id: String): JsonObject = (graph[id] as? JsonObject)?.get("inputs") as? JsonObject ?: JsonObject(emptyMap())
    fun title(id: String): String? = (((graph[id] as? JsonObject)?.get("_meta") as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull

    /** `[nodeId, slot]` link, or null for a literal. */
    fun link(value: JsonElement?): Pair<String, Int>? {
        val a = value as? JsonArray ?: return null
        if (a.size != 2) return null
        val node = (a[0] as? JsonPrimitive)?.content ?: return null
        val slot = (a[1] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
        return node to slot.toInt()
    }

    fun literal(id: String, input: String): JsonPrimitive? =
        (inputs(id)[input] as? JsonPrimitive)?.takeUnless { it is JsonNull }

    fun nodesOf(vararg classes: String): List<String> = ids.filter { classOf(it) in classes }
}

/**
 * Finds the parameters people change in an API graph: prompt and negative (the text encoders
 * wired to the sampler's positive / negative), sampler settings, size / batch / frames / fps /
 * seconds, input images and audio, checkpoint and LoRAs; and the output nodes.
 */
object WorkflowDetector {
    private val SAMPLERS = listOf("KSampler", "KSamplerAdvanced", "SamplerCustom", "CFGGuider", "BasicGuider", "DualCFGGuider")
    private val SEED_NODES = mapOf(
        "KSampler" to "seed", "KSamplerAdvanced" to "noise_seed", "SamplerCustom" to "noise_seed", "RandomNoise" to "noise_seed",
    )
    private val TEXT_INPUTS = mapOf(
        "CLIPTextEncode" to listOf("text"),
        "CLIPTextEncodeSDXL" to listOf("text_g", "text_l"),
        "CLIPTextEncodeSDXLRefiner" to listOf("text"),
        "CLIPTextEncodeFlux" to listOf("t5xxl", "clip_l"),
        "CLIPTextEncodeSD3" to listOf("t5xxl", "clip_g", "clip_l"),
        "CLIPTextEncodeHunyuanDiT" to listOf("bert", "mt5xl"),
        "TextEncodeAceStepAudio" to listOf("tags"),
        "TextEncodeHunyuanVideo_ImageToVideo" to listOf("prompt"),
    )
    private val STRING_SOURCES = listOf("value", "string", "text", "String")
    private val LATENT = Regex("(?i)latent|imagetovideo|imgtovideo|img2vid|tovideo")
    private val IMAGE_OUTPUTS = setOf("SaveImage", "PreviewImage", "Image Save", "SaveImageWebsocket")
    private val AUDIO_OUTPUTS = setOf("SaveAudio", "SaveAudioMP3", "SaveAudioOpus", "PreviewAudio")
    private val VIDEO_OUTPUTS = setOf("SaveAnimatedWEBP", "SaveAnimatedPNG", "VHS_VideoCombine", "SaveVideo", "SaveWEBM")
    private val CHECKPOINTS = mapOf(
        "CheckpointLoaderSimple" to "ckpt_name", "CheckpointLoader" to "ckpt_name", "ImageOnlyCheckpointLoader" to "ckpt_name",
        "unCLIPCheckpointLoader" to "ckpt_name",
    )

    val DEFAULT_SAMPLERS = listOf(
        "euler", "euler_ancestral", "heun", "dpm_2", "dpm_2_ancestral", "lms", "dpm_fast", "dpm_adaptive", "dpmpp_2s_ancestral",
        "dpmpp_sde", "dpmpp_2m", "dpmpp_2m_sde", "dpmpp_3m_sde", "ddim", "uni_pc", "lcm",
    )
    val DEFAULT_SCHEDULERS = listOf("normal", "karras", "exponential", "sgm_uniform", "simple", "ddim_uniform", "beta")

    private class Range(val min: Double, val max: Double, val step: Double)

    private val FALLBACK = mapOf(
        "steps" to Range(1.0, 150.0, 1.0),
        "cfg" to Range(0.0, 30.0, 0.5),
        "denoise" to Range(0.0, 1.0, 0.01),
        "width" to Range(64.0, 8192.0, 8.0),
        "height" to Range(64.0, 8192.0, 8.0),
        "batch" to Range(1.0, 64.0, 1.0),
        "frames" to Range(1.0, 1024.0, 1.0),
        "fps" to Range(1.0, 120.0, 1.0),
        "seconds" to Range(1.0, 600.0, 0.1),
        "lora_strength" to Range(-10.0, 10.0, 0.05),
        "seed" to Range(0.0, Seeds.MAX.toDouble(), 1.0),
    )

    fun detect(graph: JsonObject, info: ObjectInfo? = null): Detection {
        val g = GraphView(graph)
        val params = ArrayList<WorkflowParam>()
        val taken = HashSet<InputTarget>()
        fun add(p: WorkflowParam?) {
            if (p == null) return
            val fresh = p.targets.filter { it !in taken }
            if (fresh.isEmpty() || params.any { it.name == p.name }) return
            taken += fresh
            params += p.copy(targets = fresh)
        }

        // Prompt and negative, traced from the samplers' conditioning inputs.
        val samplers = g.nodesOf(*SAMPLERS.toTypedArray())
        val positive = samplers.firstNotNullOfOrNull { s -> (g.inputs(s)["positive"] ?: g.inputs(s)["conditioning"]).let { g.link(it) }?.let { traceText(g, it, 0) } }
            ?: g.nodesOf(*TEXT_INPUTS.keys.toTypedArray()).firstOrNull()?.let { textTargets(g, it) }
        val negative = samplers.firstNotNullOfOrNull { s -> g.link(g.inputs(s)["negative"])?.let { traceText(g, it, 0) } }
        positive?.takeIf { it.isNotEmpty() }?.let { t ->
            add(make(g, info, "prompt", "Prompt", ParamType.STRING, t, role = "prompt").copy(multiline = true))
        }
        negative?.takeIf { it.isNotEmpty() && it != positive }?.let { t ->
            add(make(g, info, "negative", "Negative prompt", ParamType.STRING, t, role = "negative").copy(multiline = true))
        }

        // Input media.
        g.nodesOf("LoadImage", "LoadImageMask", "LoadImageOutput").forEachIndexed { i, id ->
            if (g.literal(id, "image") == null) return@forEachIndexed
            val n = if (i == 0) "image" else "image_${i + 1}"
            add(make(g, info, n, g.title(id)?.takeUnless { it == g.classOf(id) } ?: if (i == 0) "Input image" else "Input image ${i + 1}", ParamType.IMAGE, listOf(InputTarget(id, "image")), role = "image"))
        }
        g.nodesOf("LoadAudio").forEachIndexed { i, id ->
            if (g.literal(id, "audio") == null) return@forEachIndexed
            add(make(g, info, if (i == 0) "audio" else "audio_${i + 1}", if (i == 0) "Input audio" else "Input audio ${i + 1}", ParamType.AUDIO, listOf(InputTarget(id, "audio")), role = "audio"))
        }

        // Models.
        val ckpt = g.ids.filter { g.classOf(it) in CHECKPOINTS && g.literal(it, CHECKPOINTS.getValue(g.classOf(it)!!)) != null }
            .map { InputTarget(it, CHECKPOINTS.getValue(g.classOf(it)!!)) }
        if (ckpt.isNotEmpty()) add(make(g, info, "checkpoint", "Checkpoint", ParamType.ENUM, ckpt.take(1), role = "checkpoint"))
        g.nodesOf("UNETLoader").firstOrNull { g.literal(it, "unet_name") != null }?.let {
            add(make(g, info, "unet", "Diffusion model", ParamType.ENUM, listOf(InputTarget(it, "unet_name")), role = "unet", advanced = true))
        }
        g.nodesOf("LoraLoader", "LoraLoaderModelOnly").forEachIndexed { i, id ->
            val n = if (i == 0) "lora" else "lora_${i + 1}"
            if (g.literal(id, "lora_name") != null) add(make(g, info, n, if (i == 0) "LoRA" else "LoRA ${i + 1}", ParamType.ENUM, listOf(InputTarget(id, "lora_name")), role = "lora"))
            val strengths = listOf("strength_model", "strength_clip").filter { g.literal(id, it) != null }.map { InputTarget(id, it) }
            if (strengths.isNotEmpty()) add(make(g, info, "${n}_strength", if (i == 0) "LoRA strength" else "LoRA ${i + 1} strength", ParamType.FLOAT, strengths, role = "lora_strength"))
        }

        // Sampling.
        val seeds = g.ids.mapNotNull { id -> SEED_NODES[g.classOf(id)]?.takeIf { g.literal(id, it) != null }?.let { InputTarget(id, it) } }
        if (seeds.isNotEmpty()) add(make(g, info, "seed", "Seed", ParamType.SEED, seeds, role = "seed"))
        fun firstLiteral(input: String, classes: List<String>) =
            g.ids.firstOrNull { g.classOf(it) in classes && g.literal(it, input) != null }?.let { listOf(InputTarget(it, input)) }
        val stepNodes = listOf("KSampler", "KSamplerAdvanced", "BasicScheduler", "LTXVScheduler", "Flux2Scheduler")
        firstLiteral("steps", stepNodes)?.let { add(make(g, info, "steps", "Steps", ParamType.INT, it, role = "steps")) }
        firstLiteral("cfg", listOf("KSampler", "KSamplerAdvanced", "SamplerCustom", "CFGGuider"))?.let { add(make(g, info, "cfg", "CFG", ParamType.FLOAT, it, role = "cfg")) }
        firstLiteral("sampler_name", listOf("KSampler", "KSamplerAdvanced", "KSamplerSelect"))?.let { add(make(g, info, "sampler", "Sampler", ParamType.ENUM, it, role = "sampler", advanced = true)) }
        firstLiteral("scheduler", listOf("KSampler", "KSamplerAdvanced", "BasicScheduler"))?.let { add(make(g, info, "scheduler", "Scheduler", ParamType.ENUM, it, role = "scheduler", advanced = true)) }
        firstLiteral("denoise", listOf("KSampler", "BasicScheduler"))?.let { add(make(g, info, "denoise", "Denoise", ParamType.FLOAT, it, role = "denoise", advanced = true)) }

        // Size, batch, frames, seconds (latent and image-to-video nodes), fps anywhere.
        val latents = g.ids.filter { LATENT.containsMatchIn(g.classOf(it).orEmpty()) }
        fun latentInputs(vararg names: String) = latents.flatMap { id -> names.filter { g.literal(id, it) != null }.map { InputTarget(id, it) } }
        latentInputs("width").takeIf { it.isNotEmpty() }?.let { add(make(g, info, "width", "Width", ParamType.INT, it, role = "width")) }
        latentInputs("height").takeIf { it.isNotEmpty() }?.let { add(make(g, info, "height", "Height", ParamType.INT, it, role = "height")) }
        latentInputs("length", "num_frames", "video_frames", "frames").takeIf { it.isNotEmpty() }?.let { add(make(g, info, "frames", "Frames", ParamType.INT, it, role = "frames")) }
        latentInputs("batch_size").takeIf { it.isNotEmpty() }?.let { add(make(g, info, "batch", "Batch size", ParamType.INT, it, role = "batch", advanced = true)) }
        val seconds = latentInputs("seconds") + g.nodesOf("ConditioningStableAudio").filter { g.literal(it, "seconds_total") != null }.map { InputTarget(it, "seconds_total") }
        if (seconds.isNotEmpty()) add(make(g, info, "seconds", "Seconds", ParamType.FLOAT, seconds, role = "seconds"))
        val fps = g.ids.flatMap { id -> listOf("fps", "frame_rate").filter { g.literal(id, it) != null }.map { InputTarget(id, it) } }
        if (fps.isNotEmpty()) {
            val integer = fps.all { t -> g.literal(t.nodeId, t.input)?.let { !it.isString && it.content.toLongOrNull() != null } == true }
            add(make(g, info, "fps", "FPS", if (integer) ParamType.INT else ParamType.FLOAT, fps, role = "fps"))
        }

        // Outputs.
        val video = g.ids.filter { g.classOf(it) in VIDEO_OUTPUTS }
        val audio = g.ids.filter { g.classOf(it) in AUDIO_OUTPUTS }
        val image = g.ids.filter { g.classOf(it) in IMAGE_OUTPUTS || (info?.get(g.classOf(it).orEmpty())?.outputNode == true && g.classOf(it) !in VIDEO_OUTPUTS && g.classOf(it) !in AUDIO_OUTPUTS) }
        val (output, nodes) = when {
            video.isNotEmpty() -> AssetType.VIDEO to video
            audio.isNotEmpty() -> AssetType.AUDIO to audio
            else -> AssetType.IMAGE to image
        }
        return Detection(params, output, nodes)
    }

    private fun textTargets(g: GraphView, id: String): List<InputTarget>? {
        val names = TEXT_INPUTS[g.classOf(id)] ?: listOf("text").takeIf { g.inputs(id)["text"] != null } ?: return null
        val out = ArrayList<InputTarget>()
        for (name in names) {
            val v = g.inputs(id)[name] ?: continue
            if (v is JsonPrimitive && v.isString) {
                out += InputTarget(id, name)
            } else {
                // Text fed from a primitive / string node.
                val (src, _) = g.link(v) ?: continue
                STRING_SOURCES.firstOrNull { g.literal(src, it)?.isString == true }?.let { out += InputTarget(src, it) }
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Follows a conditioning link back to the text inputs that produce it. */
    private fun traceText(g: GraphView, link: Pair<String, Int>, depth: Int): List<InputTarget>? {
        if (depth > 16) return null
        val (id, slot) = link
        textTargets(g, id)?.let { return it }
        val inputs = g.inputs(id)
        val next = when {
            slot == 1 && inputs["negative"] != null -> inputs["negative"]
            inputs["positive"] != null -> inputs["positive"]
            inputs["conditioning"] != null -> inputs["conditioning"]
            inputs["conditioning_to"] != null -> inputs["conditioning_to"]
            inputs["conditioning_1"] != null -> inputs["conditioning_1"]
            else -> inputs.entries.firstOrNull { (k, v) -> "cond" in k.lowercase() && g.link(v) != null }?.value
        }
        return g.link(next)?.let { traceText(g, it, depth + 1) }
    }

    internal fun make(
        g: GraphView,
        info: ObjectInfo?,
        name: String,
        label: String,
        type: ParamType,
        targets: List<InputTarget>,
        role: String? = null,
        advanced: Boolean = false,
    ): WorkflowParam {
        val first = targets.first()
        val spec = g.classOf(first.nodeId)?.let { info?.input(it, first.input) }
        val literal = g.literal(first.nodeId, first.input)
        val fallback = FALLBACK[role ?: name]
        val options = when {
            spec != null && spec.options.isNotEmpty() -> spec.options
            role == "sampler" -> DEFAULT_SAMPLERS
            role == "scheduler" -> DEFAULT_SCHEDULERS
            else -> emptyList()
        }
        return WorkflowParam(
            name = name,
            label = label,
            type = type,
            targets = targets,
            default = literal?.let { valueOf(it, type) },
            min = spec?.min ?: fallback?.min,
            max = spec?.max?.let { if (type == ParamType.SEED) minOf(it, 1.8446744073709552E19) else it } ?: fallback?.max,
            step = spec?.step?.takeIf { type != ParamType.INT && type != ParamType.SEED || it >= 1 } ?: fallback?.step,
            // Without /object_info the graph's own choice stays valid.
            options = if (type != ParamType.ENUM) emptyList() else if (spec == null && options.isNotEmpty() && literal != null && literal.content !in options) options + literal.content else options,
            multiline = spec?.multiline == true,
            role = role,
            advanced = advanced,
        )
    }

    /** A graph literal as a typed value. */
    internal fun valueOf(p: JsonPrimitive, type: ParamType): ParamValue? = when (type) {
        ParamType.INT, ParamType.SEED -> p.longOrNull?.let { ParamValue.IntValue(it) } ?: p.doubleOrNull?.let { ParamValue.IntValue(it.toLong()) }
        ParamType.FLOAT -> p.doubleOrNull?.let { ParamValue.FloatValue(it) }
        ParamType.BOOL -> p.booleanOrNull?.let { ParamValue.BoolValue(it) }
        ParamType.STRING, ParamType.ENUM, ParamType.IMAGE, ParamType.AUDIO -> ParamValue.StringValue(p.content)
    }

    /** The control type for an exposed input: from /object_info, else from the literal. */
    internal fun typeOf(spec: InputSpec?, literal: JsonPrimitive): ParamType = when (spec?.type) {
        "INT" -> if (spec.name == "seed" || spec.name == "noise_seed") ParamType.SEED else ParamType.INT
        "FLOAT" -> ParamType.FLOAT
        "BOOLEAN" -> ParamType.BOOL
        "COMBO" -> if (spec.upload) ParamType.IMAGE else ParamType.ENUM
        "STRING" -> ParamType.STRING
        else -> when {
            literal.isString -> ParamType.STRING
            literal.booleanOrNull != null -> ParamType.BOOL
            literal.longOrNull != null -> ParamType.INT
            else -> ParamType.FLOAT
        }
    }
}

/** An input the user could expose as a parameter. */
class ExposableInput(val nodeId: String, val classType: String, val nodeTitle: String, val input: String, val value: JsonPrimitive, val type: ParamType)

/** Editing a workflow's parameter schema; every edit returns a new workflow with [StudioWorkflow.version] + 1. */
object WorkflowSchema {
    /** Literal inputs no parameter sets yet, in node order. */
    fun exposable(workflow: StudioWorkflow, info: ObjectInfo? = null): List<ExposableInput> {
        val g = GraphView(workflow.graph)
        val used = workflow.params.flatMap { it.targets }.toSet()
        return g.ids.flatMap { id ->
            val cls = g.classOf(id).orEmpty()
            g.inputs(id).mapNotNull { (input, v) ->
                val p = (v as? JsonPrimitive)?.takeUnless { it is JsonNull } ?: return@mapNotNull null
                if (InputTarget(id, input) in used) return@mapNotNull null
                ExposableInput(id, cls, g.title(id) ?: cls, input, p, WorkflowDetector.typeOf(info?.input(cls, input), p))
            }
        }
    }

    /** Exposes `nodeId.input` as a parameter (appended; [name] made unique from [label]). */
    fun expose(workflow: StudioWorkflow, nodeId: String, input: String, label: String? = null, info: ObjectInfo? = null, now: Long = workflow.updatedAt): StudioWorkflow {
        val g = GraphView(workflow.graph)
        val literal = g.literal(nodeId, input) ?: throw WorkflowException("Node $nodeId has no plain value \"$input\" to expose")
        val target = InputTarget(nodeId, input)
        if (workflow.params.any { target in it.targets }) throw WorkflowException("$input of node $nodeId is already a parameter")
        val spec = g.classOf(nodeId)?.let { info?.input(it, input) }
        val text = label?.trim()?.takeIf { it.isNotEmpty() } ?: "${g.title(nodeId) ?: g.classOf(nodeId)} ${input.replace('_', ' ')}"
        val param = WorkflowDetector.make(g, info, uniqueName(workflow, slug(text)), text, WorkflowDetector.typeOf(spec, literal), listOf(target))
        return edited(workflow, workflow.params + param, now)
    }

    fun rename(workflow: StudioWorkflow, name: String, label: String, now: Long = workflow.updatedAt): StudioWorkflow {
        require(workflow.param(name) != null) { "No parameter $name" }
        return edited(workflow, workflow.params.map { if (it.name == name) it.copy(label = label.trim().ifEmpty { it.label }) else it }, now)
    }

    fun remove(workflow: StudioWorkflow, name: String, now: Long = workflow.updatedAt): StudioWorkflow =
        edited(workflow, workflow.params.filterNot { it.name == name }, now)

    /** Moves parameter [name] to [index] in the list the UI renders. */
    fun move(workflow: StudioWorkflow, name: String, index: Int, now: Long = workflow.updatedAt): StudioWorkflow {
        val p = workflow.param(name) ?: return workflow
        val rest = workflow.params.filterNot { it.name == name }.toMutableList()
        rest.add(index.coerceIn(0, rest.size), p)
        return edited(workflow, rest, now)
    }

    /** Replaces a parameter's presentation (default, range, options, advanced); targets and name stay. */
    fun update(workflow: StudioWorkflow, param: WorkflowParam, now: Long = workflow.updatedAt): StudioWorkflow {
        val old = workflow.param(param.name) ?: throw WorkflowException("No parameter ${param.name}")
        return edited(workflow, workflow.params.map { if (it.name == param.name) param.copy(targets = old.targets) else it }, now)
    }

    /** Fills ENUM options and ranges from a server's /object_info (after connecting a backend). */
    fun refreshOptions(workflow: StudioWorkflow, info: ObjectInfo): StudioWorkflow {
        val g = GraphView(workflow.graph)
        val params = workflow.params.map { p ->
            val t = p.targets.first()
            val spec = g.classOf(t.nodeId)?.let { info.input(it, t.input) } ?: return@map p
            p.copy(
                options = if (p.type == ParamType.ENUM && spec.options.isNotEmpty()) spec.options else p.options,
                min = spec.min ?: p.min,
                max = if (p.type == ParamType.SEED) p.max else spec.max ?: p.max,
            )
        }
        return if (params == workflow.params) workflow else workflow.copy(params = params)
    }

    private fun edited(w: StudioWorkflow, params: List<WorkflowParam>, now: Long) = w.copy(params = params, version = w.version + 1, updatedAt = now)

    private fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "param" }.take(40)

    private fun uniqueName(w: StudioWorkflow, base: String): String {
        var name = base
        var i = 2
        while (w.param(name) != null) name = "${base}_${i++}"
        return name
    }
}

/** A value outside what the server offers. */
class MissingValue(val nodeId: String, val classType: String, val input: String, val value: String) {
    /** True for model files (checkpoints, LoRAs, VAEs, …) rather than other choices. */
    val isModel: Boolean get() = MODEL_EXT.containsMatchIn(value) || input.endsWith("_name") && input != "sampler_name"

    private companion object {
        val MODEL_EXT = Regex("(?i)\\.(safetensors|ckpt|pt|pth|bin|gguf|sft)$")
    }
}

/** What [WorkflowValidator.validate] found. */
class Validation(val missingNodes: List<String>, val missingValues: List<MissingValue>) {
    val ok: Boolean get() = missingNodes.isEmpty() && missingValues.isEmpty()

    /** One message for the UI; null when everything is there. */
    val message: String?
        get() {
            if (ok) return null
            val parts = ArrayList<String>()
            if (missingNodes.isNotEmpty()) parts += "Missing nodes: ${missingNodes.joinToString(", ")} (install the custom nodes on the server)"
            val models = missingValues.filter { it.isModel }
            if (models.isNotEmpty()) parts += "Missing models: ${models.joinToString(", ") { "${it.value} (${it.classType}.${it.input})" }}"
            val other = missingValues.filterNot { it.isModel }
            if (other.isNotEmpty()) parts += "Unknown values: ${other.joinToString(", ") { "${it.input}=${it.value} (${it.classType})" }}"
            return parts.joinToString(". ")
        }
}

object WorkflowValidator {
    /** Checks every node class exists on the server and every choice (model files etc.) is offered. */
    fun validate(graph: JsonObject, info: ObjectInfo): Validation {
        val g = GraphView(graph)
        val missingNodes = g.ids.mapNotNull { g.classOf(it) }.filter { info[it] == null }.distinct()
        val missingValues = g.ids.flatMap { id ->
            val cls = g.classOf(id) ?: return@flatMap emptyList()
            val node = info[cls] ?: return@flatMap emptyList()
            g.inputs(id).mapNotNull { (input, v) ->
                val p = (v as? JsonPrimitive)?.takeIf { it.isString } ?: return@mapNotNull null
                val spec = node.inputs[input] ?: return@mapNotNull null
                if (spec.type != "COMBO" || spec.options.isEmpty() || spec.upload || p.content in spec.options) null
                else MissingValue(id, cls, input, p.content)
            }
        }
        return Validation(missingNodes, missingValues)
    }
}

/** A graph ready to queue and the values it was made with (the seed actually used included). */
class AppliedWorkflow(val graph: JsonObject, val values: Map<String, ParamValue>) {
    val seed: Long? get() = values.entries.firstOrNull { it.key == "seed" }?.value?.asLong()
}

object WorkflowApplier {
    /**
     * Writes [values] (missing ones take the parameter's default) into a copy of the workflow's
     * graph. Seeds are drawn from [random] when [randomizeSeed] is set or no seed is given (or it
     * is negative). IMAGE / AUDIO parameters take a server file name (a string value, or the name
     * an asset was uploaded as in [uploads]). Throws [WorkflowException] for unknown names, values
     * of the wrong type, or choices the parameter does not offer.
     */
    fun apply(
        workflow: StudioWorkflow,
        values: Map<String, ParamValue>,
        randomizeSeed: Boolean = false,
        random: Random = Random.Default,
        uploads: Map<String, String> = emptyMap(),
    ): AppliedWorkflow {
        values.keys.firstOrNull { workflow.param(it) == null }?.let { unknown ->
            throw WorkflowException("\"${workflow.name}\" has no parameter \"$unknown\"; it takes: ${workflow.params.joinToString(", ") { it.name }}")
        }
        val nodes = workflow.graph.toMutableMap()
        val effective = LinkedHashMap<String, ParamValue>()
        for (p in workflow.params) {
            var v = values[p.name] ?: p.default
            if (p.type == ParamType.SEED && (randomizeSeed || v == null || (v.asLong() ?: -1) < 0)) v = ParamValue.IntValue(Seeds.random(random))
            if (v == null) continue
            val json = encode(p, v, uploads)
            effective[p.name] = if (p.type == ParamType.IMAGE || p.type == ParamType.AUDIO) v else decodeBack(p, json)
            for (t in p.targets) {
                val node = nodes[t.nodeId] as? JsonObject ?: throw WorkflowException("Node ${t.nodeId} of parameter ${p.name} is missing from the graph")
                val inputs = (node["inputs"] as? JsonObject).orEmpty()
                nodes[t.nodeId] = JsonObject(node + ("inputs" to JsonObject(inputs + (t.input to json))))
            }
        }
        return AppliedWorkflow(JsonObject(nodes), effective)
    }

    private fun decodeBack(p: WorkflowParam, json: JsonPrimitive): ParamValue = WorkflowDetector.valueOf(json, p.type) ?: ParamValue.StringValue(json.content)

    private fun encode(p: WorkflowParam, v: ParamValue, uploads: Map<String, String>): JsonPrimitive {
        fun wrong(): Nothing = throw WorkflowException("${p.label} expects ${p.type.name.lowercase()}, got ${v.display()}")
        return when (p.type) {
            ParamType.INT, ParamType.SEED -> {
                val n = v.asLong() ?: wrong()
                if (p.type == ParamType.INT) JsonPrimitive(clamp(n.toDouble(), p).toLong()) else JsonPrimitive(n.coerceAtLeast(0))
            }
            ParamType.FLOAT -> JsonPrimitive(clamp(v.asDouble() ?: wrong(), p))
            ParamType.BOOL -> when (v) {
                is ParamValue.BoolValue -> JsonPrimitive(v.value)
                is ParamValue.StringValue -> JsonPrimitive(v.value.toBooleanStrictOrNull() ?: wrong())
                else -> wrong()
            }
            ParamType.STRING -> JsonPrimitive(if (v is ParamValue.StringValue) v.value else v.display())
            ParamType.ENUM -> {
                val s = (v as? ParamValue.StringValue)?.value ?: v.display()
                if (p.options.isNotEmpty() && s !in p.options) throw WorkflowException("${p.label} \"$s\" isn't available; choose one of: ${p.options.take(12).joinToString(", ")}")
                JsonPrimitive(s)
            }
            ParamType.IMAGE, ParamType.AUDIO -> when (v) {
                is ParamValue.StringValue -> JsonPrimitive(v.value)
                is ParamValue.AssetValue -> JsonPrimitive(uploads[p.name] ?: uploads[v.ref.assetId] ?: throw WorkflowException("${p.label}: the asset was not uploaded"))
                else -> wrong()
            }
        }
    }

    private fun clamp(x: Double, p: WorkflowParam): Double {
        var v = x
        p.min?.let { v = maxOf(v, it) }
        p.max?.let { v = minOf(v, it) }
        return v
    }
}

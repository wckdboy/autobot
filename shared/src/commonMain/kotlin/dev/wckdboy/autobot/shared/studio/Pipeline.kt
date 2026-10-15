package dev.wckdboy.autobot.shared.studio

import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/*
 * Recipes in the Studio sense: multi-step pipelines (txt2img → upscale, image → variations, …)
 * stored as data. Each step names an engine kind and params; its inputs bind to the outputs of
 * earlier steps (or the run's input asset). Steps run as ordinary queue jobs, so each step's
 * assets land in the project, and independent steps run side by side on their own slots.
 */

/**
 * Where a step's input comes from: the outputs of step [from], or the run's input assets when
 * [from] is [RUN_INPUT]. [each] runs the step once per asset; otherwise it takes the first.
 */
@Serializable
data class StepInput(val from: String, val role: String = "image", val each: Boolean = false) {
    companion object {
        const val RUN_INPUT = "\$input"
    }
}

/**
 * One step. String params may use `{{name}}` placeholders for the run's variables. [repeat] runs
 * it several times (seeds by [seedMode]).
 */
@Serializable
data class PipelineStep(
    val id: String,
    val label: String,
    val engine: String,
    val output: AssetType = AssetType.IMAGE,
    val params: Map<String, ParamValue> = emptyMap(),
    val model: String? = null,
    val backendId: String? = null,
    val workflowId: String? = null,
    val inputs: List<StepInput> = emptyList(),
    val repeat: Int = 1,
    val seedMode: SeedMode = SeedMode.WALK,
)

/** A value the run asks for (`prompt`, `text`, `voice`). */
@Serializable
data class PipelineVariable(val name: String, val label: String, val required: Boolean = true, val default: String = "", val multiline: Boolean = false)

@Serializable
data class Pipeline(
    val id: String,
    val name: String,
    val description: String = "",
    val variables: List<PipelineVariable> = emptyList(),
    /** The kind of input asset the run needs ("image → variations"), or null. */
    val input: AssetType? = null,
    val steps: List<PipelineStep>,
)

/** A pipeline that cannot run as given. */
class PipelineException(message: String) : Exception(message)

@Serializable
enum class StepState { DONE, FAILED, SKIPPED, CANCELLED }

data class StepResult(val stepId: String, val state: StepState, val jobIds: List<String>, val assetIds: List<String>, val error: String? = null)

data class PipelineResult(val steps: List<StepResult>) {
    val ok: Boolean get() = steps.all { it.state == StepState.DONE }
    val assetIds: List<String> get() = steps.flatMap { it.assetIds }
    val error: String? get() = steps.firstNotNullOfOrNull { it.error }
}

/** Runs pipelines on a [JobQueue]. */
class PipelineRunner(private val queue: JobQueue, private val random: Random = Random.Default) {

    /** Checks references and variables; throws [PipelineException]. */
    fun check(pipeline: Pipeline, vars: Map<String, String>, input: List<String>) {
        val seen = HashSet<String>()
        for (step in pipeline.steps) {
            if (!seen.add(step.id)) throw PipelineException("Step id ${step.id} is used twice")
            for (i in step.inputs) {
                if (i.from == StepInput.RUN_INPUT) {
                    if (input.isEmpty()) throw PipelineException("${pipeline.name} needs an input ${pipeline.input?.name?.lowercase() ?: "asset"}")
                } else if (i.from !in seen || i.from == step.id) {
                    throw PipelineException("Step ${step.id} takes input from ${i.from}, which does not run before it")
                }
            }
        }
        pipeline.variables.filter { it.required && vars[it.name].isNullOrBlank() && it.default.isBlank() }.firstOrNull()?.let {
            throw PipelineException("${pipeline.name} needs ${it.label.lowercase()}")
        }
    }

    /**
     * Runs [pipeline] in [projectId] with [vars] and the run's [input] asset ids; [onStep] hears
     * each step as it ends. Cancelling the call cancels the jobs it queued.
     */
    suspend fun run(
        pipeline: Pipeline,
        projectId: String,
        vars: Map<String, String> = emptyMap(),
        input: List<String> = emptyList(),
        seed: Long? = null,
        onStep: (StepResult) -> Unit = {},
    ): PipelineResult {
        check(pipeline, vars, input)
        val values = pipeline.variables.associate { it.name to (vars[it.name]?.takeIf { v -> v.isNotBlank() } ?: it.default) } + vars.filterKeys { k -> pipeline.variables.none { it.name == k } }
        val results = pipeline.steps.associate { it.id to CompletableDeferred<StepResult>() }
        val base = seed ?: Seeds.random(random)
        coroutineScope {
            for (step in pipeline.steps) launch {
                val result = runStep(pipeline, step, projectId, values, input, base, results)
                results.getValue(step.id).complete(result)
                onStep(result)
            }
        }
        return PipelineResult(pipeline.steps.map { results.getValue(it.id).await() })
    }

    private suspend fun runStep(
        pipeline: Pipeline,
        step: PipelineStep,
        projectId: String,
        vars: Map<String, String>,
        input: List<String>,
        baseSeed: Long,
        results: Map<String, CompletableDeferred<StepResult>>,
    ): StepResult {
        // Inputs: wait for the steps they come from.
        val bound = ArrayList<Pair<StepInput, List<String>>>()
        for (i in step.inputs) {
            val assets = if (i.from == StepInput.RUN_INPUT) {
                input
            } else {
                val dep = results.getValue(i.from).await()
                if (dep.state != StepState.DONE || dep.assetIds.isEmpty()) {
                    return StepResult(step.id, StepState.SKIPPED, emptyList(), emptyList(), "Skipped: ${i.from} made nothing")
                }
                dep.assetIds
            }
            bound += i to assets
        }
        // One run per asset of an `each` input (the first such input), times repeat.
        val fanOut = bound.firstOrNull { it.first.each }
        val variants: List<List<AssetRef>> = (fanOut?.second ?: listOf(null)).map { eachAsset ->
            bound.map { (i, assets) -> AssetRef(if (i === fanOut?.first && eachAsset != null) eachAsset else assets.first(), i.role) }
        }
        val count = variants.size * step.repeat.coerceIn(1, 16)
        val seeds = Seeds.plan(step.seedMode, baseSeed, count, random)
        val params = step.params.mapValues { (_, v) -> if (v is ParamValue.StringValue) ParamValue.StringValue(fill(v.value, vars)) else v }
        val jobs = ArrayList<StudioJob>()
        try {
            for (k in 0 until count) {
                val refs = variants[k / step.repeat.coerceIn(1, 16)]
                val recipe = Recipe(step.engine, step.backendId, step.model, params, refs, step.workflowId, null, seeds[k], step.output)
                val label = if (count > 1) "${pipeline.name} · ${step.label} ${k + 1}/$count" else "${pipeline.name} · ${step.label}"
                jobs += queue.submit(projectId, recipe, label, refs.firstOrNull()?.assetId)
            }
            val finished = jobs.map { queue.await(it.id) }
            val assets = finished.flatMap { it?.outputs.orEmpty() }
            val error = finished.firstNotNullOfOrNull { it?.error }
            val state = when {
                assets.isNotEmpty() -> StepState.DONE
                finished.all { it?.state == JobState.CANCELLED } -> StepState.CANCELLED
                else -> StepState.FAILED
            }
            return StepResult(step.id, state, jobs.map { it.id }, assets, if (state == StepState.DONE) null else error ?: "${step.label} made nothing")
        } catch (e: CancellationException) {
            withContext(NonCancellable) { jobs.forEach { queue.cancel(it.id) } }
            throw e
        }
    }

    private fun fill(template: String, vars: Map<String, String>): String =
        PLACEHOLDER.replace(template) { m -> vars[m.groupValues[1]].orEmpty() }.trim().trim(',').trim()

    private companion object {
        val PLACEHOLDER = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}")
    }
}

/** The built-in pipelines (data; the user's saved pipelines use the same shape). */
object BuiltInPipelines {
    private val prompt = PipelineVariable("prompt", "Prompt", multiline = true)
    private val negative = PipelineVariable("negative", "Negative prompt", required = false)

    val TXT2IMG_UPSCALE = Pipeline(
        id = "builtin-txt2img-upscale",
        name = "Text → image → upscale",
        description = "Generates an image, then upscales it ×2.",
        variables = listOf(prompt, negative),
        steps = listOf(
            PipelineStep("image", "Image", EngineKinds.DIFFUSION, params = mapOf("prompt" to ParamValue.of("{{prompt}}"), "negative" to ParamValue.of("{{negative}}"))),
            PipelineStep("upscale", "Upscale", EngineKinds.UPSCALE, params = mapOf("scale" to ParamValue.of(2)), inputs = listOf(StepInput("image", each = true))),
        ),
    )

    val IMAGE_VARIATIONS = Pipeline(
        id = "builtin-image-variations",
        name = "Image → 4 variations",
        description = "Four img2img variations of an image, seeds walking from one base.",
        variables = listOf(prompt.copy(required = false)),
        input = AssetType.IMAGE,
        steps = listOf(
            PipelineStep(
                "variations", "Variation", EngineKinds.DIFFUSION,
                params = mapOf("prompt" to ParamValue.of("{{prompt}}"), "strength" to ParamValue.of(0.55)),
                inputs = listOf(StepInput(StepInput.RUN_INPUT)),
                repeat = 4,
                seedMode = SeedMode.WALK,
            ),
        ),
    )

    val IMAGE_WITH_AMBIENCE = Pipeline(
        id = "builtin-image-ambience",
        name = "Prompt → image + ambient audio",
        description = "An image and a matching ambient loop from one prompt.",
        variables = listOf(prompt),
        steps = listOf(
            PipelineStep("image", "Image", EngineKinds.DIFFUSION, params = mapOf("prompt" to ParamValue.of("{{prompt}}"))),
            PipelineStep(
                "ambience", "Ambience", EngineKinds.AUDIO, AssetType.AUDIO,
                params = mapOf("prompt" to ParamValue.of("ambient soundscape, {{prompt}}"), "seconds" to ParamValue.of(10.0)),
            ),
        ),
    )

    val VOICEOVER = Pipeline(
        id = "builtin-voiceover",
        name = "Text → voiceover",
        description = "Reads a text aloud with a Kokoro voice.",
        variables = listOf(PipelineVariable("text", "Text", multiline = true), PipelineVariable("voice", "Voice", required = false, default = "af_heart")),
        steps = listOf(
            PipelineStep(
                "voice", "Voiceover", EngineKinds.SPEECH, AssetType.AUDIO,
                params = mapOf("text" to ParamValue.of("{{text}}"), "voice" to ParamValue.of("{{voice}}"), "speed" to ParamValue.of(1.0)),
            ),
        ),
    )

    val all: List<Pipeline> = listOf(TXT2IMG_UPSCALE, IMAGE_VARIATIONS, IMAGE_WITH_AMBIENCE, VOICEOVER)
}

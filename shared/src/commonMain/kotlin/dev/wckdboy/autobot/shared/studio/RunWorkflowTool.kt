package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.currentTimeMillis
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.string
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A saved workflow as the agent sees it. */
class WorkflowSummary(
    val id: String,
    val name: String,
    val description: String,
    val output: AssetType,
    val params: List<WorkflowParam>,
    /** "runs on <label>" (a remote backend), null on device. */
    val backendLabel: String?,
)

/** What a workflow run made. */
class WorkflowRunResult(
    val assetIds: List<String>,
    val output: AssetType,
    val seed: Long? = null,
    val backendLabel: String? = null,
    /** Set when the run failed (or was cancelled). */
    val error: String? = null,
)

/**
 * Runs saved workflows for the agent; the platform injects it (usually [QueueStudioRunner]).
 * [run] waits until the job finished.
 */
interface StudioRunner {
    suspend fun workflows(): List<WorkflowSummary>

    suspend fun run(workflowId: String, values: Map<String, ParamValue>, randomizeSeed: Boolean): WorkflowRunResult
}

/** Converting agent JSON into typed values. */
object WorkflowValues {
    private val ASSET_ID = Regex("^a-[0-9a-f]{12}$")

    /** [value] as the value of [param]; throws [WorkflowException] when it does not fit. */
    fun fromJson(param: WorkflowParam, value: JsonElement): ParamValue {
        val p = value as? JsonPrimitive ?: throw WorkflowException("${param.name} must be a single value")
        fun wrong(): Nothing = throw WorkflowException("${param.name} expects ${param.type.name.lowercase()}, got ${p.content}")
        return when (param.type) {
            ParamType.INT, ParamType.SEED -> ParamValue.IntValue(p.longOrNull ?: p.doubleOrNull?.toLong() ?: p.content.trim().toLongOrNull() ?: wrong())
            ParamType.FLOAT -> ParamValue.FloatValue(p.doubleOrNull ?: p.content.trim().toDoubleOrNull() ?: wrong())
            ParamType.BOOL -> ParamValue.BoolValue(p.booleanOrNull ?: wrong())
            ParamType.STRING, ParamType.ENUM -> ParamValue.StringValue(p.content)
            ParamType.IMAGE, ParamType.AUDIO ->
                if (ASSET_ID.matches(p.content)) ParamValue.AssetValue(AssetRef(p.content, param.role ?: param.name)) else ParamValue.StringValue(p.content)
        }
    }
}

/**
 * `run_workflow`: runs one of the user's saved Studio workflows by name with parameter values.
 * GENERATE, so it asks like `generate_image`; a workflow on a remote backend also goes through
 * that backend's network rules (kill switch, route, audit), and the card says where it runs.
 */
class RunWorkflowTool(private val runner: StudioRunner) : ToolDefinition {
    override val name = "run_workflow"
    override val description =
        "Run one of the user's saved Studio workflows (image, audio or video generation, often on their own PC through " +
            "ComfyUI) by name, with parameter values such as prompt, negative, seed, steps, width, height, frames or seconds. " +
            "Call without parameters to learn a workflow's parameters; an unknown name lists the saved workflows. " +
            "Omit seed for a random one. The results are saved to the Studio library."
    override val kind = ToolKind.GENERATE
    override val timeoutMs: Long = 30 * 60 * 1000L

    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("workflow") {
                put("type", "string")
                put("description", "Name of the saved workflow")
            }
            putJsonObject("params") {
                put("type", "object")
                put("description", "Parameter values by name, e.g. {\"prompt\": \"…\", \"steps\": 30}")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("workflow")) })
        put("additionalProperties", false)
    }

    override fun describeCall(args: JsonObject): String {
        val workflow = args.string("workflow").orEmpty()
        val params = (args["params"] as? JsonObject).orEmpty()
        val prompt = (params["prompt"] as? JsonPrimitive)?.content?.replace('\n', ' ')?.take(60)
        val rest = params.filterKeys { it != "prompt" }.entries.take(4).joinToString(" ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.content ?: v}" }
        return listOfNotNull(workflow, prompt?.let { "\"$it\"" }, rest.ifEmpty { null }).joinToString(" · ")
    }

    /**
     * A workflow on a remote backend sends its values (the prompt included) off the phone, so
     * taint tracking checks them like a network tool's. Unknown: assume it does.
     */
    override suspend fun reachesNetwork(args: JsonObject): Boolean {
        val all = try {
            runner.workflows()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return true
        }
        val workflow = find(all, args.string("workflow")?.trim().orEmpty()) ?: return false
        return workflow.backendLabel != null
    }

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
        val name = args.string("workflow")?.trim().orEmpty()
        val raw = args["params"] as? JsonObject
        val all = try {
            runner.workflows()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolOutput.error("Can't read the saved workflows: ${e.message}")
        }
        if (all.isEmpty()) return ToolOutput.error("The user has no saved Studio workflows. They can import one in Studio → Workflows.", "NOT_FOUND")
        val workflow = find(all, name)
            ?: return ToolOutput.error("No workflow named \"$name\". Saved workflows: ${all.joinToString(", ") { "\"${it.name}\"" }}", "NOT_FOUND")
        if (raw == null) return ToolOutput(describe(workflow))
        val values = try {
            raw.mapNotNull { (key, value) ->
                val param = workflow.params.firstOrNull { it.name == key } ?: workflow.params.firstOrNull { it.name.equals(key, true) || it.label.equals(key, true) }
                    ?: throw WorkflowException("\"${workflow.name}\" has no parameter \"$key\". ${describe(workflow)}")
                if (value is JsonPrimitive && value.content.isEmpty() && !value.isString) null else param.name to WorkflowValues.fromJson(param, value)
            }.toMap()
        } catch (e: WorkflowException) {
            return ToolOutput.error(e.message ?: "Invalid parameters", "INVALID_ARGS")
        }
        val result = try {
            runner.run(workflow.id, values, randomizeSeed = workflow.params.any { it.type == ParamType.SEED } && values.keys.none { k -> workflow.params.any { it.name == k && it.type == ParamType.SEED } })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ToolOutput.error(e.message ?: "The workflow failed")
        }
        result.error?.let { return ToolOutput.error(it) }
        val noun = when (result.output) {
            AssetType.IMAGE -> if (result.assetIds.size == 1) "image" else "images"
            AssetType.AUDIO -> if (result.assetIds.size == 1) "audio clip" else "audio clips"
            AssetType.VIDEO -> if (result.assetIds.size == 1) "video" else "videos"
        }
        val text = buildString {
            append("Made ${result.assetIds.size} $noun with \"${workflow.name}\"")
            result.backendLabel?.let { append(" on $it") }
            result.seed?.let { append(" (seed $it)") }
            append(". They are in the Studio library; the user can see them below.")
        }
        val meta = buildJsonObject {
            put("studio", buildJsonObject {
                put("type", result.output.name.lowercase())
                put("assets", JsonArray(result.assetIds.map(::JsonPrimitive)))
            })
            if (result.output == AssetType.IMAGE) put("gallery", JsonArray(result.assetIds.map(::JsonPrimitive)))
        }
        return ToolOutput(text, meta = meta)
    }

    private fun find(all: List<WorkflowSummary>, name: String): WorkflowSummary? =
        all.firstOrNull { it.name == name } ?: all.firstOrNull { it.name.equals(name, true) }
            ?: all.filter { it.name.contains(name, true) }.singleOrNull().takeIf { name.isNotEmpty() }

    private fun describe(w: WorkflowSummary): String = buildString {
        append("\"${w.name}\" makes ${w.output.name.lowercase()}")
        w.backendLabel?.let { append(" and runs on $it") }
        append(". Parameters: ")
        append(
            w.params.joinToString("; ") { p ->
                buildString {
                    append(p.name).append(" (").append(p.type.name.lowercase())
                    if (p.options.isNotEmpty()) append(": ").append(p.options.take(8).joinToString("|"))
                    if (p.min != null && p.max != null && p.type != ParamType.SEED) append(", ${ParamValue.formatNumber(p.min)}–${ParamValue.formatNumber(p.max)}")
                    append(")")
                    p.default?.let { append(" default ").append(it.display().replace('\n', ' ').take(60)) }
                }
            },
        )
    }
}

/**
 * The usual [StudioRunner]: saved workflows from [store], run as ComfyUI jobs on [queue] in the
 * project [projectId] returns (e.g. an "Agent" project), waiting for the job. [backendLabel]
 * names a backend id for the summaries.
 */
class QueueStudioRunner(
    private val store: StudioStore,
    private val queue: JobQueue,
    private val projectId: suspend () -> String,
    private val backendLabel: (String?) -> String? = { it },
    private val random: Random = Random.Default,
) : StudioRunner {
    override suspend fun workflows(): List<WorkflowSummary> =
        store.workflows().sortedBy { it.name.lowercase() }.map { WorkflowSummary(it.id, it.name, it.description, it.output, it.params, backendLabel(it.backendId)) }

    override suspend fun run(workflowId: String, values: Map<String, ParamValue>, randomizeSeed: Boolean): WorkflowRunResult {
        val workflow = store.workflows().firstOrNull { it.id == workflowId } ?: return WorkflowRunResult(emptyList(), AssetType.IMAGE, error = "The workflow is no longer saved")
        val seedParam = workflow.params.firstOrNull { it.type == ParamType.SEED }
        val seed = when {
            seedParam == null -> null
            randomizeSeed -> Seeds.random(random)
            else -> values[seedParam.name]?.asLong() ?: seedParam.default?.asLong()
        }
        val params = if (seedParam != null && seed != null) values + (seedParam.name to ParamValue.IntValue(seed)) else values
        // Fail fast on bad values, before anything is queued.
        WorkflowApplier.apply(workflow, params, random = random, uploads = params.filterValues { it is ParamValue.AssetValue }.mapValues { "pending" })
        val recipe = Recipe(EngineKinds.COMFYUI, workflow.backendId, null, params, emptyList(), workflow.id, workflow.version, seed, workflow.output)
        val job = queue.submit(projectId(), recipe, workflow.name)
        val done = try {
            queue.await(job.id)
        } catch (e: CancellationException) {
            // The agent stopped (cancelled turn, tool timeout): the job it started stops with it.
            queue.cancel(job.id)
            throw e
        } ?: return WorkflowRunResult(emptyList(), workflow.output, seed, error = "The job was removed")
        return when (done.state) {
            JobState.DONE -> WorkflowRunResult(done.outputs, workflow.output, seed, backendLabel(workflow.backendId))
            JobState.CANCELLED -> WorkflowRunResult(emptyList(), workflow.output, seed, error = "The job was cancelled")
            else -> WorkflowRunResult(emptyList(), workflow.output, seed, error = done.error ?: "The workflow failed")
        }
    }

    companion object {
        /** The project the agent's generations go to, created on first use. */
        suspend fun agentProject(store: StudioStore, name: String = "Agent", now: Long = currentTimeMillis()): String {
            store.projects().firstOrNull { it.name == name }?.let { return it.id }
            val project = StudioProject(StudioIds.new("p"), name, null, now, now)
            store.putProject(project)
            return project.id
        }
    }
}

/** `run_workflow` for every session; load it while the Studio is set up (workflows saved). */
fun studioPlugin(runner: StudioRunner): Plugin = plugin("studio", ToolRegistry.Key) { ctx ->
    val tools = ctx.require(ToolRegistry.Key)
    ctx.effect { tools.register(RunWorkflowTool(runner)) }
}

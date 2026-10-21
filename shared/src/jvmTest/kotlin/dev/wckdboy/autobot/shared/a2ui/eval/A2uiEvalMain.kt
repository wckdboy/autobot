package dev.wckdboy.autobot.shared.a2ui.eval

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * An OpenAI-compatible `/chat/completions` client (Ollama, LM Studio, llama.cpp server, a cloud
 * API): one non-streaming request per step, tools offered with `tool_choice: auto`.
 * [extra] is merged into every request body (e.g. `{"reasoning_effort":"none"}`).
 */
class OpenAiEvalClient(
    endpoint: String,
    private val model: String,
    private val apiKey: String?,
    private val temperature: Double?,
    private val maxTokens: Int,
    private val extra: JsonObject,
    timeoutSeconds: Long,
) : A2uiEvalClient {
    private val url = endpoint.trimEnd('/') + "/chat/completions"
    private val timeout = Duration.ofSeconds(timeoutSeconds)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun complete(messages: JsonArray, tools: JsonArray): A2uiEvalCompletion {
        val body = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("tools", tools)
            put("tool_choice", "auto")
            put("stream", false)
            put("max_tokens", maxTokens)
            temperature?.let { put("temperature", it) }
            for ((k, v) in extra) put(k, v)
        }
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
            .header("Content-Type", "application/json")
            .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
            .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val start = System.nanoTime()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            return A2uiEvalCompletion(error = "${e::class.simpleName}: ${e.message}", latencyMs = (System.nanoTime() - start) / 1_000_000)
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        if (response.statusCode() !in 200..299) {
            val body = response.body().take(500)
            // llama.cpp's server answers 500 when the model's tool call does not parse.
            val malformed = "tool call" in body || "expected peg-native format" in body
            return A2uiEvalCompletion(error = (if (malformed) "${A2uiEvalRunner.MALFORMED} " else "") + "HTTP ${response.statusCode()}: $body", latencyMs = ms)
        }
        return parse(response.body(), ms)
    }

    companion object {
        /** A chat-completions response body as an [A2uiEvalCompletion]. */
        fun parse(body: String, latencyMs: Long): A2uiEvalCompletion {
            val root = try {
                Json.parseToJsonElement(body) as JsonObject
            } catch (e: Exception) {
                return A2uiEvalCompletion(error = "bad response: ${body.take(300)}", latencyMs = latencyMs)
            }
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: return A2uiEvalCompletion(error = "no choices: ${body.take(300)}", latencyMs = latencyMs)
            val msg = choice["message"] as? JsonObject ?: JsonObject(emptyMap())
            val calls = (msg["tool_calls"] as? JsonArray).orEmpty().mapIndexedNotNull { i, t ->
                val o = t as? JsonObject ?: return@mapIndexedNotNull null
                val fn = o["function"] as? JsonObject ?: return@mapIndexedNotNull null
                val args = fn["arguments"].let { a -> if (a is JsonPrimitive && a.isString) a.content else a?.toString() ?: "" }
                A2uiEvalToolCall(o.str("id") ?: "call_$i", fn.str("name") ?: "", args)
            }
            val usage = root["usage"] as? JsonObject
            return A2uiEvalCompletion(
                content = msg.str("content"),
                toolCalls = calls,
                finishReason = choice.str("finish_reason"),
                promptTokens = (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull,
                completionTokens = (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull,
                latencyMs = latencyMs,
            )
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

private const val USAGE = """
A2UI show_ui eval. Options (as --name=value, or -Pname=value through Gradle):
  --model=a,b        model id(s) as the endpoint names them (required)
  --endpoint=URL     OpenAI-compatible base URL (default http://localhost:11434/v1, Ollama)
  --key-env=NAME     environment variable holding the API key (none by default)
  --tasks=FILE       tasks JSON (default: the bundled a2ui/eval/tasks.json)
  --only=id,id       run only these task ids
  --temperature=X    sampling temperature (default 0; 'none' leaves the server default)
  --max-tokens=N     max tokens per response (default 4096)
  --timeout=S        seconds per request (default 600)
  --extra=JSON       merged into every request body, e.g. {"reasoning_effort":"none"}
  --salvage=false    don't recover show_ui calls written as text (counted as no call)
  --args=array       experiment: declare messages as a JSON array instead of a string
  --out=DIR          output folder (default build/a2ui-eval)
"""

fun main(argv: Array<String>) {
    val opts = argv.mapNotNull { a -> a.removePrefix("--").split("=", limit = 2).takeIf { it.size == 2 && it[1].isNotEmpty() }?.let { it[0] to it[1] } }.toMap()
    val models = opts["model"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    if (models.isEmpty()) {
        System.err.println(USAGE.trim())
        kotlin.system.exitProcess(2)
    }
    val endpoint = opts["endpoint"] ?: "http://localhost:11434/v1"
    val key = opts["key-env"]?.let { System.getenv(it) ?: error("environment variable $it is not set") }
    val set = opts["tasks"]?.let { A2uiEvalSet.parse(File(it).readText()) } ?: A2uiEvalSet.bundled()
    val only = opts["only"]?.split(',')?.map { it.trim() }?.toSet()
    val tasks = set.tasks.filter { only == null || it.id in only }
    val temperature = opts["temperature"].let { if (it == "none") null else it?.toDouble() ?: 0.0 }
    val extra = opts["extra"]?.let { Json.parseToJsonElement(it) as JsonObject } ?: JsonObject(emptyMap())
    val out = File(opts["out"] ?: "build/a2ui-eval").apply { mkdirs() }
    val json = Json { encodeDefaults = false }
    File(out, "system-prompt.txt").writeText(A2uiEvalRunner.SYSTEM_PROMPT)

    val byModel = LinkedHashMap<String, List<A2uiTaskResult>>()
    for (model in models) {
        val client = OpenAiEvalClient(endpoint, model, key, temperature, opts["max-tokens"]?.toInt() ?: 4096, extra, opts["timeout"]?.toLong() ?: 600)
        val arrayArgs = opts["args"] == "array"
        val label = if (arrayArgs) "$model (array args)" else model
        val runner = A2uiEvalRunner(client, label, salvage = opts["salvage"] != "false", arrayArgs = arrayArgs)
        val slug = label.replace(Regex("[^A-Za-z0-9._-]+"), "_")
        val results = ArrayList<A2uiTaskResult>()
        File(out, "$slug.attempts.jsonl").printWriter().use { attemptsOut ->
            File(out, "$slug.results.jsonl").printWriter().use { resultsOut ->
                for ((i, task) in tasks.withIndex()) {
                    val attempts = ArrayList<A2uiEvalAttempt>()
                    val r = runner.run(task, attempts)
                    attempts.forEach { attemptsOut.println(json.encodeToString(A2uiEvalAttempt.serializer(), it)) }
                    resultsOut.println(json.encodeToString(A2uiTaskResult.serializer(), r))
                    attemptsOut.flush()
                    resultsOut.flush()
                    results += r
                    val checks = if (r.checks.isEmpty()) "" else " checks ${r.checks.count { it.pass }}/${r.checks.size}"
                    println("[$label ${i + 1}/${tasks.size}] ${task.id}: ${r.outcome}, tries ${r.tries}$checks, ${r.latencyMs / 1000}s")
                }
            }
        }
        byModel[label] = results
        File(out, "$slug.md").writeText(A2uiEvalReport.markdown(endpoint, mapOf(label to results)))
    }
    // report.md covers every model with results in the folder, so separate runs (one server per
    // model) add up to one table.
    val all = LinkedHashMap<String, List<A2uiTaskResult>>()
    out.listFiles { f -> f.name.endsWith(".results.jsonl") }.orEmpty().sortedBy { it.lastModified() }.forEach { f ->
        val rs = f.readLines().filter { it.isNotBlank() }.map { json.decodeFromString(A2uiTaskResult.serializer(), it) }
        rs.firstOrNull()?.let { all[it.model] = rs }
    }
    all.putAll(byModel)
    val report = A2uiEvalReport.markdown(endpoint, all)
    File(out, "report.md").writeText(report)
    println()
    println(A2uiEvalReport.summaryTable(byModel.map { (m, r) -> A2uiModelSummary.of(m, r) }))
    println("Written to ${out.absolutePath}")
}

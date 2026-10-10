package dev.wckdboy.autobot.core.diffusion

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.wckdboy.autobot.core.diffusion.engine.NpuImages
import dev.wckdboy.autobot.core.models.Backend
import dev.wckdboy.autobot.core.models.Benchmark
import dev.wckdboy.autobot.core.models.ComputeProfile
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.FileRole
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.engine.llama.LocalLlm
import dev.wckdboy.autobot.engine.llama.LocalLlmEvent
import dev.wckdboy.autobot.engine.npu.LocalNpu
import dev.wckdboy.autobot.engine.npu.NpuEvent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/** What the phone can compute on: CPU always, plus GPU (OpenCL) and NPU (Hexagon) when present. */
data class ComputeDevices(
    val gpu: String? = null,
    val npu: String? = null,
    val npuArch: String? = null,
    val npuError: String? = null,
    val llmBackends: Set<Backend> = setOf(Backend.CPU),
)

/**
 * Measures models on each backend so [ComputeProfile] can pick the fastest automatically.
 * Text models: prompt processing and generation speed. Image models: time per denoising step.
 */
@Singleton
class ComputeBench @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llm: LocalLlm,
    private val npu: LocalNpu,
    private val profile: ComputeProfile,
) {
    suspend fun devices(): ComputeDevices {
        val list = runCatching { JSONArray(llm.devices()) }.getOrNull()
        var gpu: String? = null
        val backends = mutableSetOf(Backend.CPU)
        if (list != null) {
            for (i in 0 until list.length()) {
                val d = list.getJSONObject(i)
                when (d.optString("kind")) {
                    "gpu" -> {
                        gpu = gpu ?: d.optString("description").ifBlank { d.optString("name") }
                        backends += Backend.GPU
                    }
                    "npu" -> backends += Backend.NPU
                }
            }
        }
        val status = runCatching { JSONObject(npu.status()) }.getOrNull()
        val npuOk = status?.optBoolean("npu") == true
        val arch = status?.optString("arch")?.takeIf { it.isNotBlank() }
        return ComputeDevices(
            gpu = gpu,
            npu = if (npuOk) "Hexagon ${arch.orEmpty()}".trim() else null,
            npuArch = arch,
            npuError = status?.optString("error")?.takeIf { !npuOk && it.isNotBlank() },
            llmBackends = backends,
        )
    }

    /** Benchmarks [model] on each backend in [backends]; results are recorded and returned. */
    suspend fun run(model: InstalledModel, backends: Set<Backend>, onStatus: (String) -> Unit): List<Benchmark> {
        val results = mutableListOf<Benchmark>()
        when (model.engine) {
            EngineKind.LLAMA -> for (backend in backends.filter { it != Backend.AUTO }) {
                onStatus("${model.title} on ${backend.label}…")
                results += text(model, backend)
            }
            EngineKind.NPU -> {
                onStatus("${model.title} on ${if (model.manifest.npu?.runtime == "qnn") "npu" else "gpu"}…")
                results += image(model)
            }
            else -> Unit
        }
        results.forEach { profile.record(it) }
        return results
    }

    private suspend fun text(model: InstalledModel, backend: Backend): Benchmark {
        val path = model.paths[FileRole.MODEL] ?: return Benchmark(model.id, backend, error = "not installed")
        val request = JSONObject()
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", PROMPT)))
            .put("max_tokens", 96)
            .put("temperature", 0.0)
            .put("enable_thinking", false)
        val started = System.currentTimeMillis()
        var result: String? = null
        runCatching {
            llm.chat(path, 2048, backend.id, request.toString()).collect { e -> if (e is LocalLlmEvent.Result) result = e.json }
        }.onFailure { return Benchmark(model.id, backend, error = it.message ?: "engine stopped") }
        val json = runCatching { JSONObject(result ?: "{}") }.getOrDefault(JSONObject())
        json.optJSONObject("error")?.let { return Benchmark(model.id, backend, error = it.optString("message")) }
        val usage = json.optJSONObject("usage") ?: return Benchmark(model.id, backend, error = "no usage reported")
        val promptMs = usage.optDouble("prompt_ms", 0.0)
        val genMs = usage.optDouble("generation_ms", 0.0)
        val promptTokens = usage.optInt("prompt_tokens") - usage.optInt("cached_tokens")
        return Benchmark(
            modelId = model.id,
            backend = Backend.of(usage.optString("backend", backend.id)),
            promptTps = if (promptMs > 0) promptTokens / promptMs * 1000 else null,
            generationTps = if (genMs > 0) usage.optInt("completion_tokens") / genMs * 1000 else null,
            loadMs = System.currentTimeMillis() - started - (promptMs + genMs).toLong(),
        )
    }

    private suspend fun image(model: InstalledModel): Benchmark {
        val pkg = model.manifest.npu ?: return Benchmark(model.id, Backend.NPU, error = "not an NPU package")
        val dir = File(context.cacheDir, "npu-io").apply { mkdirs() }
        val request = JSONObject()
            .put("op", "generate")
            .put("arch", pkg.arch)
            .put("runtime", pkg.runtime)
            .put("gpu", true)
            .put("vpred", pkg.vpred)
            .put("files", NpuImages.files(model))
            .put("prompt", "a lighthouse on a cliff at dusk")
            .put("steps", 4)
            .put("cfg", 1.0)
            .put("seed", 1)
            .put("sampler", "euler")
            .put("width", 512)
            .put("height", 512)
            .put("out_dir", dir.path)
        var result: String? = null
        runCatching {
            npu.generate(request.toString()).collect { e ->
                when (e) {
                    is NpuEvent.Image -> File(e.path).delete()
                    is NpuEvent.Result -> result = e.json
                    else -> Unit
                }
            }
        }.onFailure { return Benchmark(model.id, Backend.NPU, error = it.message ?: "engine stopped") }
        val json = runCatching { JSONObject(result ?: "{}") }.getOrDefault(JSONObject())
        json.optJSONObject("error")?.let { return Benchmark(model.id, Backend.NPU, error = it.optString("message")) }
        val timings = json.optJSONObject("timings")
        return Benchmark(
            modelId = model.id,
            backend = Backend.of(json.optString("backend", "npu")),
            stepMs = timings?.optDouble("step_ms"),
            loadMs = timings?.optDouble("load_ms")?.toLong(),
        )
    }

    private companion object {
        /** ~120 prompt tokens: enough to measure prompt processing without taking long. */
        const val PROMPT = "You are benchmarking a phone. Summarise in two sentences why on-device inference helps privacy, " +
            "battery life and latency compared with cloud inference, and mention one trade-off such as model size, memory " +
            "bandwidth or thermal throttling that limits sustained performance on a mobile system on a chip."
    }
}

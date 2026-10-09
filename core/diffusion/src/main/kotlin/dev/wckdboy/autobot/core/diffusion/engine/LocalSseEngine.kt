package dev.wckdboy.autobot.core.diffusion.engine

import dev.wckdboy.autobot.core.diffusion.DiffusionEngine
import dev.wckdboy.autobot.core.diffusion.DiffusionEvent
import dev.wckdboy.autobot.core.diffusion.DiffusionException
import dev.wckdboy.autobot.core.diffusion.DiffusionInputs
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.EngineCapabilities
import dev.wckdboy.autobot.core.diffusion.EngineCatalog
import dev.wckdboy.autobot.core.diffusion.MaskEncoding
import dev.wckdboy.autobot.core.diffusion.PromptSyntax
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for a local diffusion server speaking the "generate over SSE" protocol:
 *
 * - `GET /health` → 200 when ready.
 * - `POST /generate` (JSON) → `text/event-stream` with `progress`, `complete` and `error`
 *   events; dropping the connection cancels the job.
 *
 * This is the protocol of Autobot's on-device engine sidecar (loopback, so it keeps working in
 * Offline mode) and is interoperable with Local Dream's backend host mode. Written from the
 * protocol description only. The model is fixed by whatever the server loaded; LoRAs must be
 * merged into it, so LoRA tags are stripped from the prompt.
 */
class LocalSseEngine(
    baseUrl: String,
    private val clientFactory: () -> OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiffusionEngine {

    private val base: HttpUrl? = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()

    override val capabilities = EngineCapabilities(
        modes = DiffusionMode.entries.toSet(),
        runtimeLora = false,
        modelSwitching = false,
        livePreview = true,
        maskEncoding = MaskEncoding.ALPHA_WHITE,
        maxSteps = 50,
    )

    override suspend fun catalog(): EngineCatalog = withContext(Dispatchers.IO) {
        try {
            clientFactory().newCall(Request.Builder().url(endpoint("health")).get().build()).execute().use { response ->
                if (!response.isSuccessful) throw DiffusionException("Backend not ready (HTTP ${response.code})", DiffusionException.Code.BACKEND)
            }
        } catch (e: NetworkBlockedException) {
            throw DiffusionException(e.message ?: "Blocked by network policy", DiffusionException.Code.BLOCKED)
        } catch (e: IOException) {
            throw DiffusionException(e.message ?: "Backend unreachable", DiffusionException.Code.UNREACHABLE)
        }
        EngineCatalog(samplers = SCHEDULERS)
    }

    override fun generate(request: DiffusionRequest, inputs: DiffusionInputs): Flow<DiffusionEvent> = channelFlow {
        if (request.mode != DiffusionMode.TXT2IMG && inputs.initImage == null) {
            throw DiffusionException("${request.mode.label} needs an input image", DiffusionException.Code.UNSUPPORTED)
        }
        if (request.mode == DiffusionMode.INPAINT && inputs.mask == null) throw DiffusionException("Paint a mask first", DiffusionException.Code.UNSUPPORTED)

        val client = clientFactory()
        val current = AtomicReference<Call?>(null)
        launch(Dispatchers.IO) {
            for (index in 0 until request.batchCount.coerceAtLeast(1)) {
                // A fixed seed advances per image so a batch is not N copies of one picture.
                val seed = if (request.seed >= 0) request.seed + index else -1
                val call = client.newCall(
                    Request.Builder()
                        .url(endpoint("generate"))
                        .header("Accept", "text/event-stream")
                        .post(requestBody(request, inputs, seed).toString().toRequestBody(JSON))
                        .build(),
                )
                current.set(call)
                val started = clock()
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) {
                            throw DiffusionException("HTTP ${response.code}: ${SdApiEngine.errorDetail(response.body.string())}")
                        }
                        val source = response.body.source()
                        var event: String? = null
                        val data = StringBuilder()
                        var done = false
                        while (!done) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> {
                                    if (data.isNotEmpty()) done = handle(event, data.toString(), index, request, started)
                                    event = null
                                    data.setLength(0)
                                }
                                line.startsWith(":") -> Unit
                                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                            }
                        }
                        if (!done && data.isNotEmpty()) done = handle(event, data.toString(), index, request, started)
                        if (!done) throw DiffusionException("Stream ended before the image was complete", DiffusionException.Code.PROTOCOL)
                    }
                } catch (e: NetworkBlockedException) {
                    throw DiffusionException(e.message ?: "Blocked by network policy", DiffusionException.Code.BLOCKED)
                } catch (e: IOException) {
                    if (call.isCanceled()) return@launch
                    throw DiffusionException(e.message ?: "Backend unreachable", DiffusionException.Code.UNREACHABLE)
                }
            }
            channel.close()
        }
        // Dropping the connection is the protocol's cancel.
        awaitClose { current.get()?.cancel() }
    }

    /** Returns `true` when the image is complete. */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<DiffusionEvent>.handle(
        event: String?,
        data: String,
        index: Int,
        request: DiffusionRequest,
        started: Long,
    ): Boolean {
        val obj = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return false
        when (event ?: obj["type"]?.jsonPrimitive?.contentOrNull) {
            "progress" -> send(
                DiffusionEvent.Progress(
                    image = index,
                    step = obj["step"]?.jsonPrimitive?.intOrNull ?: 0,
                    totalSteps = obj["total_steps"]?.jsonPrimitive?.intOrNull ?: request.steps,
                    preview = obj["image"]?.jsonPrimitive?.contentOrNull?.let(SdApiEngine::decodeBase64),
                ),
            )
            "complete" -> {
                val image = obj["image"]?.jsonPrimitive?.contentOrNull?.let(SdApiEngine::decodeBase64)
                    ?: throw DiffusionException("Completed without an image", DiffusionException.Code.PROTOCOL)
                send(
                    DiffusionEvent.Result(
                        index = index,
                        image = image,
                        seed = obj["seed"]?.jsonPrimitive?.longOrNull ?: request.seed,
                        width = obj["width"]?.jsonPrimitive?.intOrNull ?: request.width,
                        height = obj["height"]?.jsonPrimitive?.intOrNull ?: request.height,
                        durationMs = obj["generation_time_ms"]?.jsonPrimitive?.longOrNull ?: (clock() - started),
                    ),
                )
                return true
            }
            "error" -> throw DiffusionException(obj["message"]?.jsonPrimitive?.contentOrNull ?: "Backend error")
        }
        return false
    }

    internal fun requestBody(request: DiffusionRequest, inputs: DiffusionInputs, seed: Long): JsonObject = buildJsonObject {
        put("prompt", PromptSyntax.stripLoraTags(request.prompt))
        put("negative_prompt", request.negativePrompt)
        put("steps", request.steps)
        put("cfg", request.cfgScale)
        if (seed >= 0) put("seed", seed)
        put("width", request.width)
        put("height", request.height)
        put("scheduler", schedulerId(request.sampler, request.scheduler))
        put("show_diffusion_process", true)
        put("show_diffusion_stride", 2)
        put("preview_format", "jpeg")
        put("output_format", "png")
        if (request.mode != DiffusionMode.TXT2IMG) {
            put("image", SdApiEngine.encodeBase64(inputs.initImage!!))
            put("denoise_strength", request.denoiseStrength)
        }
        if (request.mode == DiffusionMode.INPAINT) put("mask", SdApiEngine.encodeBase64(inputs.mask!!))
    }

    private fun endpoint(path: String): HttpUrl =
        (base ?: throw DiffusionException("Invalid backend URL", DiffusionException.Code.UNREACHABLE)).newBuilder().addPathSegments(path).build()

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Scheduler ids understood by the protocol. */
        val SCHEDULERS = listOf("dpm", "dpm_karras", "dpm_sde", "dpm_sde_karras", "euler", "euler_karras", "euler_a", "euler_a_karras", "lcm")

        /** Maps A1111-style sampler/scheduler names onto protocol ids (ids pass through). */
        fun schedulerId(sampler: String, scheduler: String?): String {
            if (sampler in SCHEDULERS) return sampler
            val s = sampler.lowercase()
            val karras = scheduler.equals("karras", ignoreCase = true) || "karras" in s
            val base = when {
                "lcm" in s -> return "lcm"
                "sde" in s -> "dpm_sde"
                "dpm" in s -> "dpm"
                "euler a" in s || "euler_a" in s || "ancestral" in s -> "euler_a"
                "euler" in s -> "euler"
                else -> "dpm"
            }
            return if (karras) "${base}_karras" else base
        }
    }
}

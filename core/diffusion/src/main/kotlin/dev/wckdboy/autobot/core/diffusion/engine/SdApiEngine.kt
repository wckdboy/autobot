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
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AUTOMATIC1111-compatible REST backend (`/sdapi/v1`): stable-diffusion-webui, Forge, SD.Next
 * and others that implement the same API.
 *
 * - LoRAs are applied at runtime via `<lora:name:weight>` prompt tags.
 * - Inpainting uses `img2img` with an opaque black/white mask (`inpainting_fill = original`).
 * - Progress (with the backend's live preview) is polled from `/sdapi/v1/progress`.
 * - Cancelling the collector posts `/sdapi/v1/interrupt`.
 *
 * @param clientFactory creates a policy-bound OkHttp client per operation (kill switch, route).
 */
class SdApiEngine(
    baseUrl: String,
    private val clientFactory: () -> OkHttpClient,
    private val progressIntervalMs: Long = 800,
    private val clock: () -> Long = System::currentTimeMillis,
) : DiffusionEngine {

    private val base: HttpUrl? = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()

    override val capabilities = EngineCapabilities(
        modes = DiffusionMode.entries.toSet(),
        runtimeLora = true,
        modelSwitching = true,
        livePreview = true,
        maskEncoding = MaskEncoding.OPAQUE_BLACK_WHITE,
    )

    override suspend fun catalog(): EngineCatalog = withContext(Dispatchers.IO) {
        val client = clientFactory()
        fun names(path: String, key: String): List<String> =
            runCatching { (get(client, path) as JsonArray).mapNotNull { it.jsonObject[key]?.jsonPrimitive?.contentOrNull } }
                .getOrDefault(emptyList())
        // Fail loudly if the backend is unreachable at all; tolerate missing optional endpoints.
        val models = (get(client, "sdapi/v1/sd-models") as JsonArray).mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.contentOrNull }
        val current = runCatching { get(client, "sdapi/v1/options").jsonObject["sd_model_checkpoint"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        EngineCatalog(
            models = models,
            currentModel = current,
            loras = names("sdapi/v1/loras", "name"),
            samplers = names("sdapi/v1/samplers", "name"),
            schedulers = names("sdapi/v1/schedulers", "label"),
        )
    }

    override fun generate(request: DiffusionRequest, inputs: DiffusionInputs): Flow<DiffusionEvent> = channelFlow {
        val img2img = request.mode != DiffusionMode.TXT2IMG
        if (img2img && inputs.initImage == null) throw DiffusionException("${request.mode.label} needs an input image", DiffusionException.Code.UNSUPPORTED)
        if (request.mode == DiffusionMode.INPAINT && inputs.mask == null) throw DiffusionException("Paint a mask first", DiffusionException.Code.UNSUPPORTED)

        val client = clientFactory()
        val body = requestBody(request, inputs)
        val started = clock()
        var finished = false

        val poller = launch(Dispatchers.IO) {
            while (isActive) {
                delay(progressIntervalMs)
                val progress = runCatching { get(client, "sdapi/v1/progress?skip_current_image=false").jsonObject }.getOrNull() ?: continue
                val state = progress["state"] as? JsonObject
                val step = state?.get("sampling_step")?.jsonPrimitive?.intOrNull ?: continue
                val total = state["sampling_steps"]?.jsonPrimitive?.intOrNull ?: request.steps
                val job = state["job_no"]?.jsonPrimitive?.intOrNull ?: 0
                val preview = progress["current_image"]?.jsonPrimitive?.contentOrNull?.let(::decodeBase64)
                if (step > 0) send(DiffusionEvent.Progress(job, step, total, preview))
            }
        }

        try {
            val response = withContext(Dispatchers.IO) {
                post(client, if (img2img) "sdapi/v1/img2img" else "sdapi/v1/txt2img", body)
            }
            poller.cancel()
            val images = (response["images"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.let(::decodeBase64) }
            val info = response["info"]?.jsonPrimitive?.contentOrNull
            val infoJson = info?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            val seeds = (infoJson?.get("all_seeds") as? JsonArray)?.mapNotNull { it.jsonPrimitive.longOrNull }.orEmpty()
            // Some setups prepend a grid image when several images are returned.
            val results = if (seeds.isNotEmpty() && images.size == seeds.size + 1) images.drop(1) else images
            if (results.isEmpty()) throw DiffusionException("Backend returned no images", DiffusionException.Code.PROTOCOL)
            val elapsed = clock() - started
            results.forEachIndexed { i, png ->
                send(
                    DiffusionEvent.Result(
                        index = i,
                        image = png,
                        seed = seeds.getOrNull(i) ?: infoJson?.get("seed")?.jsonPrimitive?.longOrNull ?: request.seed,
                        width = infoJson?.get("width")?.jsonPrimitive?.intOrNull ?: request.width,
                        height = infoJson?.get("height")?.jsonPrimitive?.intOrNull ?: request.height,
                        durationMs = elapsed,
                        info = info,
                    ),
                )
            }
            finished = true
        } finally {
            poller.cancel()
            if (!finished) {
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching {
                        clientFactory().newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
                            .newCall(Request.Builder().url(endpoint("sdapi/v1/interrupt")).post(EMPTY).build())
                            .execute().close()
                    }
                }
            }
        }
    }

    internal fun requestBody(request: DiffusionRequest, inputs: DiffusionInputs): JsonObject = buildJsonObject {
        put("prompt", PromptSyntax.withLoraTags(request.prompt, request.loras))
        put("negative_prompt", request.negativePrompt)
        put("steps", request.steps)
        put("cfg_scale", request.cfgScale)
        put("width", request.width)
        put("height", request.height)
        put("seed", request.seed)
        put("sampler_name", request.sampler)
        request.scheduler?.let { put("scheduler", it) }
        put("batch_size", 1)
        put("n_iter", request.batchCount.coerceAtLeast(1))
        put("send_images", true)
        put("save_images", false)
        put(
            "override_settings",
            buildJsonObject {
                request.model?.let { put("sd_model_checkpoint", it) }
                request.clipSkip?.let { put("CLIP_stop_at_last_layers", it) }
                put("return_grid", false)
            },
        )
        put("override_settings_restore_afterwards", false)
        if (request.mode != DiffusionMode.TXT2IMG) {
            put("init_images", buildJsonArray { add(JsonPrimitive(encodeBase64(inputs.initImage!!))) })
            put("denoising_strength", request.denoiseStrength)
        }
        if (request.mode == DiffusionMode.INPAINT) {
            put("mask", encodeBase64(inputs.mask!!))
            put("mask_blur", request.maskBlur)
            put("inpainting_fill", 1)
            put("inpaint_full_res", request.inpaintOnlyMasked)
            put("inpaint_full_res_padding", 32)
            put("inpainting_mask_invert", 0)
        }
    }

    private fun endpoint(path: String): HttpUrl {
        val root = base ?: throw DiffusionException("Invalid backend URL", DiffusionException.Code.UNREACHABLE)
        val (p, q) = path.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        return root.newBuilder().addPathSegments(p).apply { if (q != null) encodedQuery(q) }.build()
    }

    private fun get(client: OkHttpClient, path: String): JsonElement = execute(client, Request.Builder().url(endpoint(path)).get().build())

    private fun post(client: OkHttpClient, path: String, body: JsonObject): JsonObject =
        execute(client, Request.Builder().url(endpoint(path)).post(body.toString().toRequestBody(JSON)).build()) as? JsonObject
            ?: throw DiffusionException("Unexpected response from backend", DiffusionException.Code.PROTOCOL)

    private fun execute(client: OkHttpClient, request: Request): JsonElement {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw DiffusionException("HTTP ${response.code}: ${errorDetail(text)}", DiffusionException.Code.BACKEND)
                return runCatching { Json.parseToJsonElement(text) }.getOrElse {
                    throw DiffusionException("Malformed JSON from backend", DiffusionException.Code.PROTOCOL)
                }
            }
        } catch (e: NetworkBlockedException) {
            throw DiffusionException(e.message ?: "Blocked by network policy", DiffusionException.Code.BLOCKED)
        } catch (e: IOException) {
            throw DiffusionException(e.message ?: "Backend unreachable", DiffusionException.Code.UNREACHABLE)
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        internal fun errorDetail(body: String): String {
            val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return body.take(200).ifBlank { "error" }
            val detail = listOf("errors", "error", "detail").firstNotNullOfOrNull { key ->
                when (val v = obj[key]) {
                    is JsonPrimitive -> v.contentOrNull
                    is JsonArray -> v.firstOrNull()?.let { (it as? JsonObject)?.get("msg")?.jsonPrimitive?.contentOrNull ?: it.toString() }
                    else -> null
                }
            }
            return (detail ?: body).take(200)
        }

        fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

        fun decodeBase64(text: String): ByteArray? =
            runCatching { Base64.getDecoder().decode(text.substringAfter("base64,")) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

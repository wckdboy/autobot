package dev.wckdboy.autobot.feature.imagine.generate

import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.tools.int
import dev.wckdboy.autobot.agent.core.tools.long
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import dev.wckdboy.autobot.core.diffusion.DiffusionBackendRepository
import dev.wckdboy.autobot.core.diffusion.DiffusionException
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.Aspect
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.resolveSize
import dev.wckdboy.autobot.shared.panic.IncognitoImages
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lets the agent render images on the user's default image backend. Unspecified parameters
 * come from the last settings used on the Imagine screen, so the user's sampler, model and
 * LoRAs carry over. Results go to the encrypted gallery; the model gets their ids and seeds. In
 * an incognito chat they stay in memory only, unsaved ([IncognitoImages]), until the user saves them.
 */
class GenerateImageTool(
    private val backends: DiffusionBackendRepository,
    private val generator: ImageGenerator,
) : ToolDefinition {
    override val name = "generate_image"
    override val kind = ToolKind.GENERATE
    override val description =
        "Generate images with Stable Diffusion on the user's configured image backend. Write prompts as comma-separated " +
            "visual tags or a short scene description (subject, style, lighting, composition). Omitted parameters reuse " +
            "the user's current Imagine settings. Images are saved to the user's private gallery (in an incognito chat they are only shown, unsaved)."
    override val parameters = schema {
        string("prompt", "Positive prompt; {a|b} makes one image per option (same seed)", required = true)
        string("negative_prompt", "What to avoid")
        string("aspect_ratio", "Shape: 1:1, 2:3, 3:2, 3:4, 4:3, 9:16 or 16:9 (or square / portrait / landscape / wide). Keeps the user's resolution")
        integer("width", "Width in px (multiple of 8); with only aspect_ratio or alone, the height follows the shape", minimum = 256, maximum = 2048)
        integer("height", "Height in px (multiple of 8)", minimum = 256, maximum = 2048)
        integer("steps", "Sampling steps", minimum = 1, maximum = 100)
        integer("seed", "Seed, or -1 for random", minimum = -1)
        integer("count", "Images per prompt (1–4)", minimum = 1, maximum = 4)
    }
    override val timeoutMs: Long = 15 * 60_000L

    override fun describeCall(args: JsonObject): String = args.string("prompt").orEmpty()

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
        val backend = backends.defaultBackend()
            ?: return ToolOutput.error("no image backend is configured (Imagine → Backends)", "NO_BACKEND")
        if (generator.isBusy) return ToolOutput.error("the image backend is busy with another job; try again shortly", "BUSY")
        val base = backends.lastRequest.first()
        val request = agentRequest(base, args)
        if (args.string("aspect_ratio") != null && Aspect.parse(args.string("aspect_ratio")) == null) {
            return ToolOutput.error("aspect_ratio must look like 16:9, 3:2, 1:1 or be square / portrait / landscape", "BAD_ASPECT")
        }
        return try {
            val incognito = IncognitoImages.isIncognitoSession(run.sessionId)
            val images = generator.generate(backend, request.calls(), source = GenerationSource.AGENT, incognitoOwner = run.sessionId.takeIf { incognito })
            ToolOutput(
                content = "Generated ${images.size} image(s) on ${backend.name}: " +
                    images.joinToString { "${if (incognito) "image" else "gallery"}:${it.galleryId} (seed ${it.seed}, ${it.width}×${it.height}, ${it.durationMs / 1000.0}s)" } +
                    if (incognito) ". ${IncognitoImages.toolNote(images.size)}" else ". They are shown to the user in the chat and saved to the gallery.",
                meta = buildJsonObject {
                    put("gallery", buildJsonArray { images.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.galleryId)) } })
                },
            )
        } catch (e: DiffusionException) {
            ToolOutput.error(e.message ?: "generation failed", "IMAGE_${e.code}")
        }
    }

    companion object {
        /**
         * The job for [args] on top of the user's [base] settings. The shape is never lost: an aspect
         * ratio keeps the user's pixel budget, one side alone keeps the shape (asking for width 1344
         * on a 1024² setup used to give 1344×1024).
         */
        fun agentRequest(base: DiffusionRequest, args: JsonObject): DiffusionRequest {
            val aspectText = args.string("aspect_ratio")
            val aspect = Aspect.parse(aspectText)
            val (width, height) = resolveSize(base.width to base.height, args.int("width"), args.int("height"), aspect)
            return base.copy(
                mode = DiffusionMode.TXT2IMG,
                prompt = args.string("prompt").orEmpty(),
                negativePrompt = args.string("negative_prompt") ?: base.negativePrompt,
                width = width,
                height = height,
                steps = args.int("steps")?.coerceIn(1, 100) ?: base.steps,
                seed = args.long("seed")?.coerceAtLeast(-1) ?: -1,
                batchCount = args.int("count")?.coerceIn(1, 4) ?: 1,
            )
        }
    }
}

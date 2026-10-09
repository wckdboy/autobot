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
import dev.wckdboy.autobot.core.diffusion.PromptSyntax
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lets the agent render images on the user's default image backend. Unspecified parameters
 * come from the last settings used on the Imagine screen, so the user's sampler, model and
 * LoRAs carry over. Results go to the encrypted gallery; the model gets their ids and seeds.
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
            "the user's current Imagine settings. Images are saved to the user's private gallery."
    override val parameters = schema {
        string("prompt", "Positive prompt", required = true)
        string("negative_prompt", "What to avoid")
        integer("width", "Width in px (multiple of 8)", minimum = 256, maximum = 2048)
        integer("height", "Height in px (multiple of 8)", minimum = 256, maximum = 2048)
        integer("steps", "Sampling steps", minimum = 1, maximum = 100)
        integer("seed", "Seed, or -1 for random", minimum = -1)
        integer("count", "Number of images (1–4)", minimum = 1, maximum = 4)
    }
    override val timeoutMs: Long = 15 * 60_000L

    override fun describeCall(args: JsonObject): String = args.string("prompt").orEmpty()

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
        val backend = backends.defaultBackend()
            ?: return ToolOutput.error("no image backend is configured (Imagine → Backends)", "NO_BACKEND")
        if (generator.isBusy) return ToolOutput.error("the image backend is busy with another job; try again shortly", "BUSY")
        val base = backends.lastRequest.first()
        val request = base.copy(
            mode = DiffusionMode.TXT2IMG,
            prompt = args.string("prompt").orEmpty(),
            negativePrompt = args.string("negative_prompt") ?: base.negativePrompt,
            width = args.int("width")?.let { PromptSyntax.snap(it) } ?: base.width,
            height = args.int("height")?.let { PromptSyntax.snap(it) } ?: base.height,
            steps = args.int("steps") ?: base.steps,
            seed = args.long("seed") ?: -1,
            batchCount = args.int("count") ?: 1,
        )
        return try {
            val images = generator.generate(backend, request, source = GenerationSource.AGENT)
            ToolOutput(
                content = "Generated ${images.size} image(s) on ${backend.name}: " +
                    images.joinToString { "gallery:${it.galleryId} (seed ${it.seed}, ${it.width}×${it.height}, ${it.durationMs / 1000.0}s)" } +
                    ". They are shown to the user in the chat and saved to the gallery.",
                meta = buildJsonObject {
                    put("gallery", buildJsonArray { images.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.galleryId)) } })
                },
            )
        } catch (e: DiffusionException) {
            ToolOutput.error(e.message ?: "generation failed", "IMAGE_${e.code}")
        }
    }
}

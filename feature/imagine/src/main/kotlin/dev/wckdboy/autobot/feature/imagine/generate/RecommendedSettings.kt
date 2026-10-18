package dev.wckdboy.autobot.feature.imagine.generate

import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import dev.wckdboy.autobot.core.diffusion.SizeSupport
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.Recommended

/**
 * The settings [model] was made for: its manifest's, else (packages installed before manifests
 * carried them) what its NPU package implies — SDXL renders at 1024², DMD2 distillations take 8
 * LCM steps without guidance.
 */
fun recommendedFor(model: InstalledModel): Recommended? {
    model.manifest.recommended?.let { return it }
    val pkg = model.manifest.npu ?: return null
    val side = if (pkg.arch == "sdxl") 1024 else 512
    val dmd2 = "dmd2" in model.id.lowercase() || "dmd2" in model.title.lowercase()
    return if (dmd2) {
        Recommended(steps = 8, cfg = 1f, sampler = "LCM", width = side, height = side)
    } else {
        Recommended(width = side, height = side)
    }
}

/**
 * [request] with [rec] applied for [modelId] (a negative prompt the user wrote is kept). The
 * recommended size sets the pixel budget; a shape the user chose (16:9) is kept at that budget
 * instead of being reset to the model's square. [sizes] are what the model renders.
 */
fun DiffusionRequest.withRecommended(modelId: String, rec: Recommended?, sizes: SizeSupport = SizeSupport()): DiffusionRequest {
    val r = copy(model = modelId)
    if (rec == null) return r
    val (width, height) = recommendedSize(r, rec, sizes)
    return r.copy(
        steps = rec.steps ?: r.steps,
        cfgScale = rec.cfg ?: r.cfgScale,
        sampler = rec.sampler ?: r.sampler,
        scheduler = rec.scheduler ?: if (rec.sampler != null) null else r.scheduler,
        width = width,
        height = height,
        negativePrompt = if (r.negativePrompt.isBlank()) rec.negativePrompt.orEmpty() else r.negativePrompt,
    )
}

/**
 * The size after selecting a model recommending [rec]: the recommended size when the request
 * still has the app's default square, else the request's own shape at the recommended pixel
 * budget. Always one [sizes] renders.
 */
internal fun recommendedSize(r: DiffusionRequest, rec: Recommended, sizes: SizeSupport): Pair<Int, Int> {
    val rw = rec.width
    val rh = rec.height
    if (rw == null || rh == null) return sizes.output(r.width, r.height)
    val untouched = r.width == DEFAULTS.width && r.height == DEFAULTS.height
    if (untouched || r.width == r.height && rw == rh) return sizes.output(rw, rh)
    return sizes.size(kotlin.math.sqrt(rw.toDouble() * rh).toInt(), r.width.toDouble() / r.height)
}

/**
 * Whether opening the studio on [modelId] should apply its recommended settings: the model was
 * never selected explicitly (the stored request names another model, or none), or the sampling
 * settings are still the untouched defaults. Settings the user changed for this model are kept.
 */
fun needsRecommended(request: DiffusionRequest, modelId: String): Boolean =
    request.model != modelId || request.hasDefaultSampling()

private val DEFAULTS = DiffusionRequest()

/** Steps, guidance, sampler and size are all the app's defaults (never tuned). */
internal fun DiffusionRequest.hasDefaultSampling(): Boolean =
    steps == DEFAULTS.steps && cfgScale == DEFAULTS.cfgScale && sampler == DEFAULTS.sampler &&
        width == DEFAULTS.width && height == DEFAULTS.height

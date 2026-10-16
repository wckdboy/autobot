package dev.wckdboy.autobot.feature.imagine.generate

import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
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

/** [request] with [rec] applied for [modelId] (a negative prompt the user wrote is kept). */
fun DiffusionRequest.withRecommended(modelId: String, rec: Recommended?): DiffusionRequest {
    val r = copy(model = modelId)
    if (rec == null) return r
    return r.copy(
        steps = rec.steps ?: r.steps,
        cfgScale = rec.cfg ?: r.cfgScale,
        sampler = rec.sampler ?: r.sampler,
        scheduler = rec.scheduler ?: if (rec.sampler != null) null else r.scheduler,
        width = rec.width ?: r.width,
        height = rec.height ?: r.height,
        negativePrompt = if (r.negativePrompt.isBlank()) rec.negativePrompt.orEmpty() else r.negativePrompt,
    )
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

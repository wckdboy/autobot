package dev.wckdboy.autobot.core.models

/** The screen an installed model opens when it is tapped on the Models screen. */
enum class ModelTask {
    /** A new session on "This phone" with the model selected. */
    CHAT,

    /** The image studio on "This phone", the model selected with its recommended settings. */
    IMAGINE,

    /** The gallery, where upscalers run on saved images. */
    GALLERY,

    /** Nothing opens it (speech, LoRAs, VAEs, or not downloaded yet): the row keeps its tags. */
    NONE,
}

/**
 * What tapping a model does: open [task], or show [problem] (user-facing) when the model can't
 * run here. [tools] starts the session with tools on (code models, as the RUN hub's code mode).
 */
data class ModelLaunch(val task: ModelTask, val problem: String? = null, val tools: Boolean = false) {
    val opens: Boolean get() = task != ModelTask.NONE && problem == null
}

/**
 * Where a [ready] model of [kind], run by [engine], is put to work on [platform]. [fit] is this
 * phone's fit for it (`DeviceCapabilities.fit`, null when unknown); [filesPresent] whether its
 * files are still on disk.
 */
fun launchOf(
    kind: ModelKind,
    engine: EngineKind,
    ready: Boolean,
    fit: Fit? = null,
    filesPresent: Boolean = true,
    platform: AppPlatform = HostPlatform,
): ModelLaunch {
    if (!ready) return ModelLaunch(ModelTask.NONE)
    val task = when (kind) {
        ModelKind.CHAT, ModelKind.CODE -> ModelTask.CHAT
        ModelKind.IMAGE -> ModelTask.IMAGINE
        ModelKind.UPSCALER -> ModelTask.GALLERY
        else -> return ModelLaunch(ModelTask.NONE)
    }
    val problem = when {
        engine == EngineKind.NONE || !platform.runs(engine) -> "No engine on this phone runs this model"
        fit == Fit.UNSUPPORTED -> "This phone can't run this model (${engine.label})"
        !filesPresent -> "This model's files are missing — remove it and download it again"
        else -> null
    }
    return ModelLaunch(task, problem, tools = kind == ModelKind.CODE)
}

package dev.wckdboy.autobot.feature.imagine.generate

import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.diffusion.BackendKind
import dev.wckdboy.autobot.core.diffusion.DiffusionBackend
import dev.wckdboy.autobot.core.models.EngineKind
import dev.wckdboy.autobot.core.models.InstalledModel
import dev.wckdboy.autobot.core.models.ModelKind

/**
 * Where an on-device image model runs, from its package: QNN context binaries on the Hexagon NPU,
 * MNN packages on the GPU (OpenCL), everything else (stable-diffusion.cpp) on the CPU.
 * "npu", "gpu" or null (CPU), as [ModelChip.accelerator].
 */
fun imageAccelerator(model: InstalledModel): String? = when (model.manifest.npu?.runtime) {
    "qnn" -> "npu"
    "mnn" -> "gpu"
    else -> null
}

/** The route tag for an image job on [backend] with on-device [model] (the engine actually used). */
fun imageRoute(backend: DiffusionBackend?, model: InstalledModel?): Route? = imageRoute(backend, model?.let(::imageAccelerator))

/** The route tag for an image job on [backend] whose on-device model runs on [accelerator] ("npu", "gpu", null = CPU). */
fun imageRoute(backend: DiffusionBackend?, accelerator: String?): Route? = when {
    backend == null -> null
    // stable-diffusion.cpp (no accelerator) runs on the CPU.
    backend.kind == BackendKind.ON_DEVICE -> Route.local(accelerator ?: "cpu")
    backend.isLoopback -> Route.LOCAL_CPU
    else -> Route.PC_LAN
}

/** Installed models the on-device studio can run. */
fun isOnDeviceImageModel(model: InstalledModel): Boolean =
    model.isReady && model.kind == ModelKind.IMAGE && (model.engine == EngineKind.DIFFUSION || model.engine == EngineKind.NPU)

/**
 * The on-device model the studio uses: the one last selected ([selected], the stored request's
 * model) when it is still installed, else the first one (the studio's first chip).
 */
fun onDeviceImageModel(models: List<InstalledModel>, selected: String?): InstalledModel? {
    val usable = models.filter(::isOnDeviceImageModel)
    return usable.firstOrNull { it.id == selected } ?: usable.firstOrNull()
}

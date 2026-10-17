package dev.wckdboy.autobot.core.models

/** How well a model fits this phone. */
enum class Fit(val label: String) { GOOD("fits"), TIGHT("tight"), TOO_BIG("too big"), UNSUPPORTED("unsupported") }

/**
 * What a phone offers the fit rules. Android fills it from `ActivityManager`, `StatFs`,
 * `/proc/cpuinfo` and the SoC model; iOS from `ProcessInfo`, the data volume, Metal and the
 * engines package (Neural Engine). Nothing here leaves the device.
 */
data class DeviceCapabilities(
    val platform: AppPlatform,
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    /** ARM feature flags (`asimddp`, `i8mm`…), from `/proc/cpuinfo` on Android. */
    val cpuFeatures: Set<String> = emptySet(),
    /** Snapdragon model number (`SM8850`), when known. */
    val socModel: String? = null,
    /** Apple Neural Engine present (Core ML falls back to the GPU without it). */
    val neuralEngine: Boolean = false,
    /** Metal's recommended working set in bytes, 0 when unknown. */
    val gpuWorkingSetBytes: Long = 0,
) {
    /** Dot-product instructions: required by the image engine build (ARMv8.2+dotprod). */
    val supportsImageEngine: Boolean get() = "asimddp" in cpuFeatures

    /** Hexagon NPU generation (`v73`, `v79`, `v81`…) when this is a known Snapdragon. */
    val htpArch: String? get() = LocalDream.archOfSoc(socModel)

    /**
     * Weights + KV cache / buffers should stay under about half of physical RAM (which also keeps
     * clear of iOS's per-app memory limit). Engines the app does not ship are [Fit.UNSUPPORTED].
     * [contextTokens] adds a chat model's KV cache for that context ([kvCacheBytes]); 0 counts
     * only the usual buffers.
     */
    fun fit(kind: ModelKind, bytes: Long, engine: EngineKind, contextTokens: Int = 0): Fit {
        if (engine == EngineKind.NONE || !platform.runs(engine)) return Fit.UNSUPPORTED
        if (engine == EngineKind.DIFFUSION && !supportsImageEngine) return Fit.UNSUPPORTED
        if (engine == EngineKind.NPU && kind == ModelKind.IMAGE && bytes > 3_000_000_000 && htpArch !in SDXL_NPUS) return Fit.UNSUPPORTED
        val working = when (kind) {
            ModelKind.CHAT, ModelKind.CODE -> chatWorkingBytes(bytes, contextTokens)
            ModelKind.IMAGE -> bytes * 14 / 10
            // The voice's weights plus activations for a sentence and its lexicons in memory.
            ModelKind.VOICE -> bytes * 3 / 2
            // Text to audio: the decoder's activations for 11.9 s of stereo come on top of the weights
            // (measured peak: ~3 GB on ONNX Runtime for 0.5 GB of 8-bit weights; Core ML loads its
            // stages one at a time, the float32 DiT being the largest).
            ModelKind.AUDIO -> bytes + if (engine == EngineKind.ONNX) 2_500_000_000L else 1_000_000_000L
            else -> bytes
        }
        return fitWorking(working)
    }

    /** Weights, buffers and KV cache of a chat model of [bytes] at [contextTokens]. */
    fun chatWorkingBytes(bytes: Long, contextTokens: Int = 0): Long = bytes * 13 / 10 + kvCacheBytes(bytes, contextTokens)

    /** How a working set of [working] bytes fits: within 70% of half the RAM is good, within half tight. */
    fun fitWorking(working: Long): Fit {
        val budget = totalRamBytes / 2
        return when {
            working <= budget * 7 / 10 -> Fit.GOOD
            working <= budget -> Fit.TIGHT
            else -> Fit.TOO_BIG
        }
    }

    /**
     * The longest context (a power of two from [MIN_CONTEXT] to [MAX_CONTEXT]) whose KV cache
     * still lets a chat model of [bytes] fit this phone; [MIN_CONTEXT] when even that is tight.
     */
    fun recommendedContext(bytes: Long, engine: EngineKind = EngineKind.LLAMA): Int {
        var context = MAX_CONTEXT
        while (context > MIN_CONTEXT && fit(ModelKind.CHAT, bytes, engine, context) == Fit.TOO_BIG) context /= 2
        return context
    }

    companion object {
        const val MIN_CONTEXT = 2_048
        const val MAX_CONTEXT = 32_768

        /**
         * Rough KV-cache size of a quantized chat model of [bytes] at [contextTokens]: about
         * 1/20 000 of the weights per token (a 2.5 GB 4-bit 4B model: ~125 KB, 4 GB at 32k).
         */
        fun kvCacheBytes(bytes: Long, contextTokens: Int): Long = bytes / 20_000 * contextTokens.coerceAtLeast(0)

        /** Hexagon generations with room for SDXL packages (Snapdragon 8 Gen 3 and newer). */
        private val SDXL_NPUS = setOf("v75", "v79", "v81")
    }
}

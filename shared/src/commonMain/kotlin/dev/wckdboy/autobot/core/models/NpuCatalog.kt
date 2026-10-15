package dev.wckdboy.autobot.core.models

/**
 * A curated NPU/GPU image package (Local Dream layout, huggingface.co/xororz). The exact files
 * are resolved from the package listing when it is installed (see [HuggingFaceClient.localDreamPlan]).
 */
data class NpuOffer(
    val id: String,
    val title: String,
    val subtitle: String,
    val repo: String,
    /** Package file per chip suffix (`8gen2`, `8gen3`, `min`…) or `any` for GPU packages. */
    val files: Map<String, String>,
    val approxBytes: Long,
    val sdxl: Boolean = false,
    val gpu: Boolean = false,
    val kind: ModelKind = ModelKind.IMAGE,
) {
    /** The package file for a phone with HTP [arch], or null if none runs there. */
    fun fileFor(arch: String?): String? {
        files["any"]?.let { return it }
        return LocalDream.chipOrder(arch).firstNotNullOfOrNull { files[it] }
    }
}

object NpuCatalog {
    private const val GB = 1_000_000_000L

    private fun sd15(name: String, title: String, note: String) = NpuOffer(
        id = "npu:sd15:$name",
        title = title,
        subtitle = "SD 1.5 · NPU · $note",
        repo = "xororz/sd-qnn",
        files = listOf("8gen1", "8gen2", "min").associateWith { "${name}_qnn2.28_$it.zip" },
        approxBytes = 1_100_000_000L,
    )

    private fun sdxl(name: String, title: String, note: String) = NpuOffer(
        id = "npu:sdxl:$name",
        title = title,
        subtitle = "SDXL · NPU · $note",
        repo = "xororz/sdxl-qnn",
        files = mapOf("8gen3" to "${name}_qnn2.28_8gen3.zip"),
        approxBytes = (3.6 * GB).toLong(),
        sdxl = true,
    )

    private fun gpu(name: String, title: String) = NpuOffer(
        id = "npu:mnn:$name",
        title = title,
        subtitle = "SD 1.5 · GPU (OpenCL) · any resolution",
        repo = "xororz/sd-mnn",
        files = mapOf("any" to "$name.zip"),
        approxBytes = 1_200_000_000L,
        gpu = true,
    )

    val image: List<NpuOffer> = listOf(
        sdxl("juggernaut_dmd2", "Juggernaut XL DMD2", "8 steps · photo"),
        sdxl("realvis_xl_v5_dmd2", "RealVisXL V5 DMD2", "8 steps · photo"),
        sdxl("cyber_realistic_v10_dmd2", "CyberRealistic XL DMD2", "8 steps · photo"),
        sdxl("illustrious_v17_dmd2", "Illustrious v1.7 DMD2", "8 steps · anime"),
        sdxl("novaanime_v19_dmd2", "Nova Anime v19 DMD2", "8 steps · anime"),
        sdxl("dreamshaper", "DreamShaper XL", "general"),
        sdxl("ponydiffusion_v6xl", "Pony Diffusion V6 XL", "stylized"),
        sd15("AbsoluteReality", "AbsoluteReality", "photo"),
        sd15("AnythingV5", "Anything V5", "anime"),
        sd15("ChilloutMix", "ChilloutMix", "photo"),
        sd15("MajicmixRealisticV7", "majicMIX realistic v7", "photo"),
        sd15("MeinaMixV12", "MeinaMix v12", "anime"),
        sd15("DreamShaperV8", "DreamShaper 8", "general"),
        gpu("AnythingV5", "Anything V5 (GPU)"),
        gpu("AbsoluteReality", "AbsoluteReality (GPU)"),
    )

    const val UPSCALER_REPO = "xororz/upscaler"

    private fun upscaler(folder: String, title: String, note: String) = NpuOffer(
        id = "npu:upscaler:$folder",
        title = title,
        subtitle = "4× upscaler · NPU · $note",
        repo = UPSCALER_REPO,
        files = listOf("8gen1", "8gen2", "8gen3", "8gen4", "min").associateWith { "$folder/upscaler_$it.bin" },
        approxBytes = 23_000_000L,
        kind = ModelKind.UPSCALER,
    )

    val upscalers: List<NpuOffer> = listOf(
        upscaler("4x_UltraSharpV2_Lite", "4× UltraSharp V2 Lite", "photo"),
        upscaler("realesrgan_x4plus_anime_6b", "Real-ESRGAN 4× anime", "anime"),
    )
}

package dev.wckdboy.autobot.core.models

/**
 * Curated starter models known to run on the built-in engines, with exact Hugging Face files
 * (verified 2026-10). Sizes are approximate; the real size and SHA-256 come from the Hub at
 * download time (`X-Linked-Size` / `X-Linked-ETag`), and the file is verified against them.
 */
object Catalog {
    private const val HF = "https://huggingface.co"

    private fun hf(repo: String, path: String, mib: Long, role: FileRole = FileRole.MODEL) =
        ModelFile(role, path.substringAfterLast('/'), "$HF/$repo/resolve/main/$path", mib * 1_048_576L, auth = AuthHost.HUGGING_FACE)

    private fun chat(
        id: String, title: String, subtitle: String, repo: String, file: String, mib: Long, license: String,
        kind: ModelKind = ModelKind.CHAT, context: Int = 8192, notes: String? = null,
        /** Vision projector (image input) as file name and size in MiB. */
        mmproj: Pair<String, Long>? = null,
    ) = ModelPlan(
        id = "catalog:$id",
        kind = kind,
        source = ModelSource.CATALOG,
        title = title,
        subtitle = subtitle,
        format = ModelFormat.GGUF,
        engine = EngineKind.LLAMA,
        license = license,
        manifest = ModelManifest(
            files = listOfNotNull(hf(repo, file, mib), mmproj?.let { (name, size) -> hf(repo, name, size, FileRole.MMPROJ) }),
            pageUrl = "$HF/$repo",
            recommended = Recommended(contextLength = context),
            notes = notes,
        ),
    )

    val chatModels: List<ModelPlan> = listOf(
        chat("qwen3.5-0.8b-q4_0", "Qwen3.5 0.8B", "Q4_0 · fast, low RAM", "unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_0.gguf", 484, "apache-2.0"),
        chat("qwen3.5-2b-q4_0", "Qwen3.5 2B", "Q4_0 · balanced", "unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_0.gguf", 1159, "apache-2.0"),
        chat("qwen3.5-4b-q4_0", "Qwen3.5 4B", "Q4_0 · best small all-rounder", "unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_0.gguf", 2464, "apache-2.0"),
        chat(
            "qwen3-4b-2507-q4_0", "Qwen3 4B Instruct 2507", "Q4_0 · strong tool calling", "unsloth/Qwen3-4B-Instruct-2507-GGUF",
            "Qwen3-4B-Instruct-2507-Q4_0.gguf", 2266, "apache-2.0", context = 16384,
            notes = "Recommended for agent sessions.",
        ),
        chat("gemma-4-e2b-q4_0", "Gemma 4 E2B", "Q4_0 · Google", "unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_0.gguf", 2900, "apache-2.0"),
        chat("phi-4-mini-q4_0", "Phi-4 mini", "Q4_0 · Microsoft", "bartowski/microsoft_Phi-4-mini-instruct-GGUF", "microsoft_Phi-4-mini-instruct-Q4_0.gguf", 2223, "mit"),
        chat("smollm3-3b-q4_0", "SmolLM3 3B", "Q4_0 · Hugging Face", "unsloth/SmolLM3-3B-GGUF", "SmolLM3-3B-Q4_0.gguf", 1728, "apache-2.0"),
        chat(
            "llama-3.2-3b-q4_0", "Llama 3.2 3B", "Q4_0 · Meta", "bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_0.gguf", 1833,
            "llama3.2", notes = "Llama 3.2 Community License and Acceptable Use Policy apply.",
        ),
        chat(
            "qwen2.5-coder-1.5b-q4_0", "Qwen2.5 Coder 1.5B", "Q4_0 · code", "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
            "qwen2.5-coder-1.5b-instruct-q4_0.gguf", 1017, "apache-2.0", kind = ModelKind.CODE,
        ),
        chat(
            "qwen2.5-coder-7b-q4_k_m", "Qwen2.5 Coder 7B", "Q4_K_M · code · 16 GB+ RAM", "unsloth/Qwen2.5-Coder-7B-Instruct-GGUF",
            "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf", 4466, "apache-2.0", kind = ModelKind.CODE,
        ),
        chat(
            "qwen3-vl-2b-q4_0", "Qwen3-VL 2B", "Q4_0 · vision · reads photos and screenshots", "unsloth/Qwen3-VL-2B-Instruct-GGUF",
            "Qwen3-VL-2B-Instruct-Q4_0.gguf", 1008, "apache-2.0", mmproj = "mmproj-F16.gguf" to 781L,
        ),
        chat(
            "qwen3-vl-4b-q4_0", "Qwen3-VL 4B", "Q4_0 · vision · stronger OCR and reasoning", "unsloth/Qwen3-VL-4B-Instruct-GGUF",
            "Qwen3-VL-4B-Instruct-Q4_0.gguf", 2266, "apache-2.0", mmproj = "mmproj-F16.gguf" to 797L,
        ),
        chat(
            "smolvlm2-2.2b-q4_k_m", "SmolVLM2 2.2B", "Q4_K_M · vision · Hugging Face", "ggml-org/SmolVLM2-2.2B-Instruct-GGUF",
            "SmolVLM2-2.2B-Instruct-Q4_K_M.gguf", 1061, "apache-2.0", mmproj = "mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf" to 565L,
        ),
    )

    val imageModels: List<ModelPlan> = listOf(
        ModelPlan(
            id = "catalog:sd15-q8_0",
            kind = ModelKind.IMAGE,
            source = ModelSource.CATALOG,
            title = "SD 1.5",
            subtitle = "Q8_0 · classic, LoRA-friendly",
            format = ModelFormat.GGUF,
            engine = EngineKind.DIFFUSION,
            baseModel = "SD 1.5",
            license = "creativeml-openrail-m",
            manifest = ModelManifest(
                files = listOf(hf("second-state/stable-diffusion-v1-5-GGUF", "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf", 1682)),
                pageUrl = "$HF/second-state/stable-diffusion-v1-5-GGUF",
                recommended = Recommended(steps = 20, cfg = 7f, sampler = "euler_a", width = 512, height = 512, negativePrompt = "lowres, blurry, watermark"),
            ),
        ),
        ModelPlan(
            id = "catalog:sdxl-turbo",
            kind = ModelKind.IMAGE,
            source = ModelSource.CATALOG,
            title = "SDXL Turbo",
            subtitle = "fp16 · 1–4 steps · non-commercial",
            format = ModelFormat.SAFETENSORS,
            engine = EngineKind.DIFFUSION,
            baseModel = "SDXL Turbo",
            license = "sai-nc-community",
            manifest = ModelManifest(
                files = listOf(hf("stabilityai/sdxl-turbo", "sd_xl_turbo_1.0_fp16.safetensors", 6617)),
                pageUrl = "$HF/stabilityai/sdxl-turbo",
                recommended = Recommended(steps = 4, cfg = 1f, sampler = "euler_a", width = 512, height = 512),
                notes = "Stability AI non-commercial community license.",
            ),
        ),
        ModelPlan(
            id = "catalog:z-image-turbo-q4_0",
            kind = ModelKind.IMAGE,
            source = ModelSource.CATALOG,
            title = "Z-Image Turbo",
            subtitle = "Q4_0 · 8 steps · best quality per GB",
            format = ModelFormat.GGUF,
            engine = EngineKind.DIFFUSION,
            baseModel = "ZImageTurbo",
            license = "apache-2.0",
            manifest = ModelManifest(
                files = listOf(
                    hf("leejet/Z-Image-Turbo-GGUF", "z_image_turbo-Q4_0.gguf", 3513, FileRole.DIFFUSION_MODEL),
                    hf("unsloth/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", 2382, FileRole.LLM),
                    hf("Comfy-Org/z_image_turbo", "split_files/vae/ae.safetensors", 320, FileRole.VAE),
                ),
                pageUrl = "$HF/leejet/Z-Image-Turbo-GGUF",
                recommended = Recommended(steps = 8, cfg = 1f, sampler = "euler", width = 768, height = 768),
            ),
        ),
        ModelPlan(
            id = "catalog:flux2-klein-4b-q4_0",
            kind = ModelKind.IMAGE,
            source = ModelSource.CATALOG,
            title = "FLUX.2 klein 4B",
            subtitle = "Q4_0 · 4 steps · strong prompt following",
            format = ModelFormat.GGUF,
            engine = EngineKind.DIFFUSION,
            baseModel = "Flux.2 Klein 4B",
            license = "apache-2.0",
            manifest = ModelManifest(
                files = listOf(
                    hf("leejet/FLUX.2-klein-4B-GGUF", "flux-2-klein-4b-Q4_0.gguf", 2346, FileRole.DIFFUSION_MODEL),
                    hf("unsloth/Qwen3-4B-GGUF", "Qwen3-4B-Q4_0.gguf", 2266, FileRole.LLM),
                    hf("black-forest-labs/FLUX.2-small-decoder", "full_encoder_small_decoder.safetensors", 238, FileRole.VAE),
                ),
                pageUrl = "$HF/leejet/FLUX.2-klein-4B-GGUF",
                recommended = Recommended(steps = 4, cfg = 1f, sampler = "euler", width = 768, height = 768),
            ),
        ),
    )

    private fun speech(id: String, title: String, subtitle: String, file: String, mib: Long) = ModelPlan(
        id = "catalog:$id",
        kind = ModelKind.SPEECH,
        source = ModelSource.CATALOG,
        title = title,
        subtitle = subtitle,
        format = ModelFormat.GGML,
        engine = EngineKind.WHISPER,
        license = "mit",
        manifest = ModelManifest(files = listOf(hf("ggerganov/whisper.cpp", file, mib)), pageUrl = "$HF/ggerganov/whisper.cpp"),
    )

    /** Dictation (whisper.cpp). Multilingual; the smaller ones are fast enough for live use. */
    val speechModels: List<ModelPlan> = listOf(
        speech("whisper-base-q5_1", "Whisper base", "57 MB · fast dictation · 99 languages", "ggml-base-q5_1.bin", 57),
        speech("whisper-small-q5_1", "Whisper small", "181 MB · better accuracy", "ggml-small-q5_1.bin", 181),
        speech("whisper-large-v3-turbo-q8_0", "Whisper large-v3 turbo", "834 MB · best accuracy · slower", "ggml-large-v3-turbo-q8_0.bin", 834),
    )

    val all: List<ModelPlan> get() = chatModels + imageModels + speechModels
}

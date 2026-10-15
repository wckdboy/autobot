package dev.wckdboy.autobot.core.models

/**
 * Install plans for NPU/GPU image packages in the Local Dream layout (e.g. huggingface.co/xororz).
 * Built from the packages' file lists only: QNN context binaries for the UNet/VAE, MNN text
 * encoders, CLIP tokenizer and embedding tables, optional `SDXL` / `V_PRED` marker files.
 */
object LocalDream {

    /** Package suffixes that can run on a Hexagon generation, best first. */
    fun chipOrder(arch: String?): List<String> = when (arch) {
        "v81", "v79" -> listOf("8gen4", "8gen3", "8gen2")
        "v75" -> listOf("8gen3", "8gen2")
        "v73" -> listOf("8gen2")
        "v69" -> listOf("8gen1", "min")
        else -> listOf("min")
    }

    /** Hexagon generation a package suffix was compiled for. */
    fun archOfChip(chip: String): String? = when (chip) {
        "8gen1" -> "v69"
        "8gen2" -> "v73"
        "8gen3" -> "v75"
        "8gen4" -> "v79"
        "min" -> "v68"
        else -> null
    }

    /** Hexagon generation of a Snapdragon SoC model number, when known. */
    fun archOfSoc(soc: String?): String? = when (soc?.uppercase()?.take(6)) {
        "SM8850" -> "v81"
        "SM8750" -> "v79"
        "SM8650" -> "v75"
        "SM8550" -> "v73"
        "SM8475", "SM8450" -> "v69"
        "SM7675", "SM7635" -> "v73"
        "SM7550" -> "v69"
        else -> null
    }

    /** The chip suffix in a package name such as `juggernaut_dmd2_qnn2.28_8gen3.zip`. */
    fun chipOf(name: String): String? = Regex("_(8gen[1-4]|min)(?:\\.|_|$)").find(name.lowercase())?.groupValues?.get(1)

    /** Whether a package built for [chip] can run on a phone with HTP [arch]. */
    fun runsOn(chip: String?, arch: String?): Boolean = chip == null || arch == null || chip in chipOrder(arch)

    private fun roleOf(base: String): FileRole? = when (base) {
        "tokenizer.json" -> FileRole.TOKENIZER
        "token_emb.bin" -> FileRole.TOKEN_EMB
        "pos_emb.bin" -> FileRole.POS_EMB
        "token_emb_2.bin" -> FileRole.TOKEN_EMB_2
        "pos_emb_2.bin" -> FileRole.POS_EMB_2
        "clip_v2.mnn", "clip.mnn" -> FileRole.TEXT_ENCODER
        "clip_2.mnn" -> FileRole.TEXT_ENCODER_2
        "unet.bin", "unet.mnn" -> FileRole.UNET
        "vae_decoder.bin", "vae_decoder.mnn" -> FileRole.VAE_DECODER
        "vae_encoder.bin", "vae_encoder.mnn" -> FileRole.VAE_ENCODER
        else -> if (base.endsWith(".mnn.weight")) FileRole.WEIGHTS else null
    }

    private fun recommended(sdxl: Boolean, dmd2: Boolean, qnn: Boolean): Recommended {
        val side = if (sdxl) 1024 else 512
        return if (dmd2) {
            Recommended(steps = 8, cfg = 1f, sampler = "LCM", width = side, height = side)
        } else {
            Recommended(steps = if (qnn) 20 else 24, cfg = if (sdxl) 6f else 7f, sampler = "DPM++ 2M", scheduler = "Karras", width = side, height = side)
        }
    }

    private fun title(name: String): String =
        name.substringAfterLast('/').substringBefore("_qnn").substringBeforeLast(".zip").replace('_', ' ').trim()
            .split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /**
     * A plan for one zip package from its resolved entries ([entries] carry data offsets).
     * Returns a non-runnable plan (format LOCAL_DREAM) for layouts Autobot cannot run yet.
     */
    fun planFromZip(repo: String, path: String, url: String, entries: List<Pair<RemoteZipEntry, ZipEntryRef>>): ModelPlan {
        val names = entries.map { it.first.baseName }.toSet()
        val sdxl = "SDXL" in names
        val anima = "ANIMA" in names || "unet_part1.bin" in names
        val vpred = "V_PRED" in names
        val qnn = "unet.bin" in names
        val dmd2 = "dmd2" in path.lowercase()
        val chip = chipOf(path)
        val files = entries.mapNotNull { (entry, ref) ->
            if (entry.isDirectory) return@mapNotNull null
            // SD1.5 zips may hold both CLIP variants; the one fed embeddings is clip_v2.mnn.
            if (entry.baseName == "clip.mnn" && "clip_v2.mnn" in names) return@mapNotNull null
            val role = roleOf(entry.baseName) ?: return@mapNotNull null
            ModelFile(role, entry.baseName, url, entry.size, auth = AuthHost.HUGGING_FACE, zipEntry = ref, keepName = true)
        }
        val runnable = !anima && FileRole.UNET in files.map { it.role } && FileRole.TEXT_ENCODER in files.map { it.role }
        val format = when {
            !runnable -> ModelFormat.LOCAL_DREAM
            qnn -> ModelFormat.QNN
            else -> ModelFormat.MNN
        }
        val arch = if (sdxl) "SDXL" else "SD 1.5"
        return ModelPlan(
            id = "hf:$repo:$path",
            kind = ModelKind.IMAGE,
            source = ModelSource.HUGGING_FACE,
            title = title(path),
            subtitle = listOfNotNull(arch, if (qnn) "NPU" else "GPU", chip, if (dmd2) "8 steps" else null).joinToString(" · "),
            format = format,
            engine = if (runnable) EngineKind.NPU else EngineKind.NONE,
            baseModel = arch,
            license = "see source model",
            manifest = ModelManifest(
                files = if (runnable) files else emptyList(),
                pageUrl = "https://huggingface.co/$repo",
                recommended = recommended(sdxl, dmd2, qnn),
                notes = when {
                    anima -> "Anima packages are not supported yet."
                    !runnable -> "This package layout is not supported."
                    else -> null
                },
                npu = if (runnable) NpuPackage(if (sdxl) "sdxl" else "sd15", if (qnn) "qnn" else "mnn", vpred, chip?.let(::archOfChip)) else null,
            ),
        )
    }

    /**
     * A plan for a per-model repository with one file per chip (`unet_8gen4.bin`, …), picking the
     * best variant for [arch]. Null when the repo has no NPU files for this phone.
     */
    fun planFromRepo(repo: HfRepo, arch: String?, resolveUrl: (String) -> String): ModelPlan? {
        val byName = repo.files.associateBy { it.path }
        val chip = chipOrder(arch).firstOrNull { c -> "unet_$c.bin" in byName && "vae_decoder_$c.bin" in byName } ?: return null
        fun file(role: FileRole, path: String, storeAs: String = path.substringAfterLast('/')): ModelFile? =
            byName[path]?.let { ModelFile(role, storeAs, resolveUrl(it.path), it.sizeBytes, it.sha256, AuthHost.HUGGING_FACE, keepName = true) }
        val text = file(FileRole.TEXT_ENCODER, "clip_v2.mnn") ?: return null
        val files = listOfNotNull(
            file(FileRole.TOKENIZER, "tokenizer.json") ?: return null,
            file(FileRole.TOKEN_EMB, "token_emb.bin") ?: return null,
            file(FileRole.POS_EMB, "pos_emb.bin") ?: return null,
            text,
            byName["clip_v2.mnn.weight"]?.let { file(FileRole.WEIGHTS, "clip_v2.mnn.weight") },
            file(FileRole.UNET, "unet_$chip.bin", "unet.bin"),
            file(FileRole.VAE_DECODER, "vae_decoder_$chip.bin", "vae_decoder.bin"),
            file(FileRole.VAE_ENCODER, "vae_encoder_$chip.bin", "vae_encoder.bin"),
        )
        return ModelPlan(
            id = "hf:${repo.id}:npu-$chip",
            kind = ModelKind.IMAGE,
            source = ModelSource.HUGGING_FACE,
            title = repo.id.substringAfter('/'),
            subtitle = "SD 1.5 · NPU · $chip",
            format = ModelFormat.QNN,
            engine = EngineKind.NPU,
            baseModel = "SD 1.5",
            license = repo.license ?: "see source model",
            manifest = ModelManifest(
                files = files,
                pageUrl = "https://huggingface.co/${repo.id}",
                recommended = recommended(sdxl = false, dmd2 = false, qnn = true),
                npu = NpuPackage("sd15", "qnn", htpArch = archOfChip(chip)),
            ),
        )
    }

    /** NPU upscalers (`<name>/upscaler_<chip>.bin`), one plan per model for [arch]. */
    fun upscalerPlans(repo: HfRepo, arch: String?, resolveUrl: (String) -> String): List<ModelPlan> {
        val byName = repo.files.associateBy { it.path }
        return repo.files.map { it.path.substringBefore('/', "") }.filter { it.isNotEmpty() }.distinct().mapNotNull { name ->
            val chip = chipOrder(arch).plus("min").firstOrNull { "$name/upscaler_$it.bin" in byName } ?: return@mapNotNull null
            val f = byName.getValue("$name/upscaler_$chip.bin")
            ModelPlan(
                id = "hf:${repo.id}:$name:$chip",
                kind = ModelKind.UPSCALER,
                source = ModelSource.HUGGING_FACE,
                title = name.replace('_', ' '),
                subtitle = "4× upscaler · NPU · $chip",
                format = ModelFormat.QNN,
                engine = EngineKind.NPU,
                license = repo.license ?: "see source model",
                manifest = ModelManifest(
                    files = listOf(ModelFile(FileRole.MODEL, "upscaler.bin", resolveUrl(f.path), f.sizeBytes, f.sha256, AuthHost.HUGGING_FACE, keepName = true)),
                    pageUrl = "https://huggingface.co/${repo.id}",
                    npu = NpuPackage("upscaler", "qnn", htpArch = archOfChip(chip)),
                ),
            )
        }
    }
}

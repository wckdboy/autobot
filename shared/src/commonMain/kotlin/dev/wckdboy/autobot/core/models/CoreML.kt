package dev.wckdboy.autobot.core.models

/**
 * Core ML Stable Diffusion for the Neural Engine (the iOS image catalog): Apple's
 * `coreml-stable-diffusion-*` repositories, compiled `split_einsum` resources downloaded file by
 * file into one model folder (no zips). Paths, sizes and LFS SHA-256s verified against the Hub API
 * (2026-10), pinned to a commit. The safety checker is left out (Autobot never filters output on
 * device, as on Android).
 */
object CoreMLCatalog {
    private fun plan(
        id: String, repo: String, revision: String, folder: String, title: String, subtitle: String, baseModel: String, license: String,
        recommended: Recommended, files: List<Triple<String, Long, String?>>,
    ) = ModelPlan(
        id = "catalog:$id",
        kind = ModelKind.IMAGE,
        source = ModelSource.CATALOG,
        title = title,
        subtitle = subtitle,
        format = ModelFormat.COREML,
        engine = EngineKind.COREML,
        baseModel = baseModel,
        license = license,
        manifest = ModelManifest(
            files = files.map { (path, size, sha) ->
                ModelFile(
                    CoreMLPackages.roleOf(path), path, "https://huggingface.co/$repo/resolve/$revision/$folder/$path", size, sha,
                    AuthHost.HUGGING_FACE, keepName = true,
                )
            },
            pageUrl = "https://huggingface.co/$repo",
            recommended = recommended,
        ),
    )

    private fun f(path: String, size: Long, sha: String? = null) = Triple(path, size, sha)

    private val pipeline = Recommended(steps = 25, cfg = 7.5f, sampler = "DPM++ 2M", scheduler = "Karras", width = 512, height = 512)

    val all: List<ModelPlan> = listOf(
        plan(
            "coreml-sd15-palettized", "apple/coreml-stable-diffusion-v1-5-palettized", "04a6a0bdd66fb8da470c14e56d762343ef579d88", "split_einsum_v2/compiled",
            "SD 1.5", "6-bit palettized · neural engine · 512²", "SD 1.5", "creativeml-openrail-m", pipeline,
            listOf(
                f("TextEncoder.mlmodelc/analytics/coremldata.bin", 207, "988cd33580bb5c09498f19a846dba6cd34ac01f41683271dadef857ef74f3a67"),
                f("TextEncoder.mlmodelc/coremldata.bin", 825, "3ed65fe33a7fdc432b88d6ec82934011db501c93ba7b4277a801f2e36e054d8b"),
                f("TextEncoder.mlmodelc/metadata.json", 2771),
                f("TextEncoder.mlmodelc/model.mil", 208229),
                f("TextEncoder.mlmodelc/weights/weight.bin", 139866304, "24f28d2ddfcd5bf5e5484e13e4ea7a2fcf10d3979c65c1c319aa06efbaa209bb"),
                f("Unet.mlmodelc/analytics/coremldata.bin", 207, "492ef32357f349b7e3683e6ae823721dbf3f0461024c8cb9e467de1838395ea3"),
                f("Unet.mlmodelc/coremldata.bin", 1207, "2932318a18ec5ea96e21fdaa24b97a48eb0114230bb41f7f1df973418a1acb42"),
                f("Unet.mlmodelc/metadata.json", 3705),
                f("Unet.mlmodelc/model.mil", 3040467),
                f("Unet.mlmodelc/weights/weight.bin", 645167616, "a278b99498e4f87e32e5e4faf05d576ad768a071bdce16741debf507439cd6dc"),
                f("VAEDecoder.mlmodelc/analytics/coremldata.bin", 207, "1a9d135ee5dbd3b459b0f29834916205c6b4209e48e865837d0d99c221b29702"),
                f("VAEDecoder.mlmodelc/coremldata.bin", 755, "a270b3c9dc565d860744d442e8927cf9c65bc5b17667a0489a7b857620ddbe65"),
                f("VAEDecoder.mlmodelc/metadata.json", 2472),
                f("VAEDecoder.mlmodelc/model.mil", 181386),
                f("VAEDecoder.mlmodelc/weights/weight.bin", 98993280, "bde01e1323a5e756198ef2ae52331f04f9c3043df796b0ccc7ca541321c0fde3"),
                f("VAEEncoder.mlmodelc/analytics/coremldata.bin", 207, "0911ab2348b7f2c228b1ffd421fbf834751773e5d3174a3dffa8584255b31ec7"),
                f("VAEEncoder.mlmodelc/coremldata.bin", 761, "d1038a67b5829a8ab067ccabee5fb542117ba733bd516216225f3dd7e80143d7"),
                f("VAEEncoder.mlmodelc/metadata.json", 2460),
                f("VAEEncoder.mlmodelc/model.mil", 139736),
                f("VAEEncoder.mlmodelc/weights/weight.bin", 68338112, "a1cf43e550e49a14d795b3402f07ece9a7cfbf405734dd73ce30881a8df6d771"),
                f("merges.txt", 524657),
                f("vocab.json", 862328),
            ),
        ),
        plan(
            "coreml-sd21-base-palettized", "apple/coreml-stable-diffusion-2-1-base-palettized", "dca5f2fda9c5143c71aded72bbb4ce39f3c2cc9c", "split_einsum_v2/compiled",
            "SD 2.1 base", "6-bit palettized · neural engine · 512²", "SD 2.1", "openrail++", pipeline,
            listOf(
                f("TextEncoder.mlmodelc/analytics/coremldata.bin", 207, "a26bdd792e919ca3412c0685e74ea507a6de835532f0ec58d780c99b0a7a0193"),
                f("TextEncoder.mlmodelc/coremldata.bin", 839, "2f0484a1a04e023da06787de4140cf215f9ccd67f93d916dc388b846d8607899"),
                f("TextEncoder.mlmodelc/metadata.json", 2792),
                f("TextEncoder.mlmodelc/model.mil", 391100),
                f("TextEncoder.mlmodelc/weights/weight.bin", 318860352, "3be3ff19e2ed53774d8c4c22f58bb4f9d37a44c2d6d34955e72decef86aa8615"),
                f("Unet.mlmodelc/analytics/coremldata.bin", 207, "7432befba40c728344d55ededaedcbeebf04eca023437afb4418e655515c066c"),
                f("Unet.mlmodelc/coremldata.bin", 1221, "5bc742d03b80b46feaa6f9e2159a5c89acda3ed2c1562f2b4f781bb7bfbce110"),
                f("Unet.mlmodelc/metadata.json", 3728),
                f("Unet.mlmodelc/model.mil", 3101782),
                f("Unet.mlmodelc/weights/weight.bin", 649959936, "a4bfeb078744274ac2000593ae80522c8e3363bec8a4cf5e35ef93e3f7b14056"),
                f("VAEDecoder.mlmodelc/analytics/coremldata.bin", 207, "682f30dbd27b8c96fae9dd5d7cec36e85812e370cc2a920d9a6cef7a908e29d3"),
                f("VAEDecoder.mlmodelc/coremldata.bin", 769, "57555bee9ff5d60c5b6fd48564eac43523d954a2d34101273b8e3fa8f1aee3d0"),
                f("VAEDecoder.mlmodelc/metadata.json", 2493),
                f("VAEDecoder.mlmodelc/model.mil", 181387),
                f("VAEDecoder.mlmodelc/weights/weight.bin", 98993280, "2caef4ff7a13ebe71592e39aff32a93ad62f54fb9fd8ea68ce9239550c5ed63b"),
                f("VAEEncoder.mlmodelc/analytics/coremldata.bin", 207, "15f5816e1040cbb9124d5d3d9b17ea7cee1e32adda5322b45d992b072bfa352a"),
                f("VAEEncoder.mlmodelc/coremldata.bin", 775, "a5fc14b860f094c7935a9df0edbd5f792eca65facc9f76fc1d16537ffe2e6405"),
                f("VAEEncoder.mlmodelc/metadata.json", 2481),
                f("VAEEncoder.mlmodelc/model.mil", 139736),
                f("VAEEncoder.mlmodelc/weights/weight.bin", 68338112, "be8ea3259f6c51d5f5159b025fd907d5c0d5f244dbf8f9e40f34b22ab90b83bd"),
                f("merges.txt", 524657),
                f("vocab.json", 862328),
            ),
        ),
        plan(
            "coreml-sdxl-base-ios", "apple/coreml-stable-diffusion-xl-base-ios", "ef92c54fe816c826f5328709b545338518d08db7",
            "coreml-stable-diffusion-xl-base-ios_split_einsum_compiled",
            "SDXL base", "mixed-bit · neural engine · 768² · txt2img only · 8 GB+ RAM", "SDXL 1.0", "openrail++",
            Recommended(steps = 25, cfg = 7f, sampler = "DPM++ 2M", scheduler = "Karras", width = 768, height = 768),
            listOf(
                f("TextEncoder.mlmodelc/analytics/coremldata.bin", 243, "ba0cd4cdb851a15a59e0610f198aeb8dbe24cc758d99922e65955a44f79dfc9f"),
                f("TextEncoder.mlmodelc/coremldata.bin", 860, "dce008301b82a1598022040d02303a414be207b0b12cd4510432281b6678a5f5"),
                f("TextEncoder.mlmodelc/metadata.json", 2804),
                f("TextEncoder.mlmodelc/model.mil", 187532),
                f("TextEncoder.mlmodelc/weights/weight.bin", 246145536, "72a0d48befc2a65462b0c320927d31b4b0c8bdcae383d357f4209de9bbc4d6d5"),
                f("TextEncoder2.mlmodelc/analytics/coremldata.bin", 243, "a6c5f66dbbbb69b15ab440424185a2775026f6661723158140531d0a8caf11a2"),
                f("TextEncoder2.mlmodelc/coremldata.bin", 860, "70b1249456151731abd5a48006cd0d08a424172fdeb104e59099e182882d13d0"),
                f("TextEncoder2.mlmodelc/metadata.json", 2809),
                f("TextEncoder2.mlmodelc/model.mil", 490294),
                f("TextEncoder2.mlmodelc/weights/weight.bin", 1389367424, "73ade7c979ddd33a1af71ecc581bdf4c5a5695f4c6adf6450ad0a914930d1459"),
                f("UnetChunk1.mlmodelc/analytics/coremldata.bin", 243, "b6911510036e3a9f3df8544d6f451625e8faa8dc9f9f4c024ae5e88cf9b170c5"),
                f("UnetChunk1.mlmodelc/coremldata.bin", 743, "4544dad1a05973f5c9298509782061ae5a6ccedf1cfce1dbf18e46735906863e"),
                f("UnetChunk1.mlmodelc/metadata.json", 6996),
                f("UnetChunk1.mlmodelc/model.mil", 6353892),
                f("UnetChunk1.mlmodelc/weights/weight.bin", 653930048, "c3157612374be1fdf8e5e61dbf6b8c2138fdc81dd70c8ed08e785e62657d85fa"),
                f("UnetChunk2.mlmodelc/analytics/coremldata.bin", 243, "338113769e38ebc471eb63a83b2a3a2497b8f0cc059a08c10cb3e6852896c75f"),
                f("UnetChunk2.mlmodelc/coremldata.bin", 672, "1301537bd8ad0f89575f8c2b1398664df2d05032d38113bc3e85288b645ab466"),
                f("UnetChunk2.mlmodelc/metadata.json", 6092),
                f("UnetChunk2.mlmodelc/model.mil", 4107584),
                f("UnetChunk2.mlmodelc/weights/weight.bin", 651783424, "3187aa81e7258dae2d80e0cb1060416f25265ea920e9be72642c24970caf9c52"),
                f("VAEDecoder.mlmodelc/analytics/coremldata.bin", 243, "055c412933973d54894c58716cd08be44f50057a7f37e17be97b2e93b64b6321"),
                f("VAEDecoder.mlmodelc/coremldata.bin", 809, "4492ee202d19fc3ed370a137f408e887fcf7ccf1fe957da9ed97bb7c9f9cab22"),
                f("VAEDecoder.mlmodelc/metadata.json", 2591),
                f("VAEDecoder.mlmodelc/model.mil", 179220),
                f("VAEDecoder.mlmodelc/weights/weight.bin", 98993280, "9de777d8a5be28417a49a2a284749105d97d2a1eb56d2bdb448d511c82061b3e"),
                f("merges.txt", 524657),
                f("vocab.json", 862328),
            ),
        ),
    )

    /**
     * 4× super-resolution on the Neural Engine for gallery images (Android's NPU upscaler).
     * `mlboydaisuke/Real-ESRGAN-x4-CoreML` @ f1693203: a coremltools conversion of xinntao/Real-ESRGAN
     * x4plus (BSD-3-Clause), 512² BGRA in → 2048² out, verified against the Hub API (2026-10). The
     * `.mlpackage` is compiled on the phone on first use.
     */
    val upscalers: List<ModelPlan> = run {
        val repo = "mlboydaisuke/Real-ESRGAN-x4-CoreML"
        val revision = "f1693203d7a0770ca7055cedfcbefb5260ee0561"
        fun file(path: String, size: Long, sha: String? = null) = ModelFile(
            if (path.endsWith(".mlmodel")) FileRole.MODEL else FileRole.OTHER, path, "https://huggingface.co/$repo/resolve/$revision/$path",
            size, sha, AuthHost.HUGGING_FACE, keepName = true,
        )
        listOf(
            ModelPlan(
                id = "catalog:coreml-realesrgan-x4",
                kind = ModelKind.UPSCALER,
                source = ModelSource.CATALOG,
                title = "Real-ESRGAN 4×",
                subtitle = "upscaler · neural engine · tiled 512²",
                format = ModelFormat.COREML,
                engine = EngineKind.COREML,
                license = "bsd-3-clause",
                manifest = ModelManifest(
                    files = listOf(
                        file("RealESRGAN_x4.mlpackage/Manifest.json", 385),
                        file("RealESRGAN_x4.mlpackage/Data/com.apple.CoreML/model.mlmodel", 66_857_221, "6107dc417de87bf974e5b225a2632e2c78f2849265dc897981f482e922050ec9"),
                    ),
                    pageUrl = "https://huggingface.co/$repo",
                ),
            ),
        )
    }

    /** Read-aloud voices on Core ML: Kokoro (see [KokoroCatalog.coreML]). */
    val voices: List<ModelPlan> = listOf(KokoroCatalog.coreML)
}

/** Compiled Core ML Stable Diffusion folders in Hugging Face repos (the iOS image browser). */
object CoreMLPackages {
    internal fun roleOf(path: String): FileRole = when {
        path.startsWith("Unet") -> FileRole.UNET
        path.startsWith("TextEncoder2") -> FileRole.TEXT_ENCODER_2
        path.startsWith("TextEncoder") -> FileRole.TEXT_ENCODER
        path.startsWith("VAEDecoder") -> FileRole.VAE_DECODER
        path.startsWith("VAEEncoder") -> FileRole.VAE_ENCODER
        path == "vocab.json" || path == "merges.txt" -> FileRole.TOKENIZER
        else -> FileRole.OTHER
    }

    /**
     * Compiled Core ML Stable Diffusion folders in [repo] (`…/compiled`, `…_compiled`) that contain
     * everything the pipeline needs, Neural Engine (`split_einsum`) builds first.
     */
    fun bundles(repo: HfRepo): List<String> {
        val dirs = repo.files.mapNotNull { f ->
            val at = f.path.indexOf(".mlmodelc/").takeIf { it >= 0 } ?: return@mapNotNull null
            f.path.substring(0, at).substringBeforeLast('/', "")
        }.toSet()
        return dirs.filter { dir ->
            val prefix = if (dir.isEmpty()) "" else "$dir/"
            fun has(name: String) = repo.files.any { it.path == prefix + name || it.path.startsWith("$prefix$name/") }
            has("VAEDecoder.mlmodelc") && has("vocab.json") && has("merges.txt") &&
                (has("Unet.mlmodelc") || has("UnetChunk1.mlmodelc")) && has("TextEncoder.mlmodelc")
        }.sortedWith(compareBy<String> { "split_einsum" !in it }.thenBy { it })
    }

    /** Bytes of [folder] without the safety checker (what [plan] downloads). */
    fun sizeOf(repo: HfRepo, folder: String): Long = filesOf(repo, folder).sumOf { it.sizeBytes }

    private fun filesOf(repo: HfRepo, folder: String): List<HfFile> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        return repo.files.filter { it.path.startsWith(prefix) && !it.path.removePrefix(prefix).startsWith("SafetyChecker") }
    }

    /** Install plan for a compiled folder: every file of [folder], stored under its relative path in one model directory. */
    fun plan(repo: HfRepo, folder: String): ModelPlan {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val files = filesOf(repo, folder).map { f ->
            ModelFile(
                FileRole.OTHER, f.path.removePrefix(prefix), HuggingFaceApi.resolveUrl(repo.id, repo.commit, f.path), f.sizeBytes, f.sha256,
                AuthHost.HUGGING_FACE, keepName = true,
            )
        }
        val xl = files.any { it.name.startsWith("TextEncoder2.mlmodelc") }
        val size = if (xl) 768 else 512
        return ModelPlan(
            id = "hf:${repo.id}:$folder",
            kind = ModelKind.IMAGE,
            source = ModelSource.HUGGING_FACE,
            title = repo.id.substringAfterLast('/'),
            subtitle = "${repo.id} · ${folder.ifEmpty { "compiled" }}",
            format = ModelFormat.COREML,
            engine = EngineKind.COREML,
            baseModel = if (xl) "SDXL" else null,
            license = repo.license,
            manifest = ModelManifest(
                files = files,
                pageUrl = "https://huggingface.co/${repo.id}",
                recommended = Recommended(steps = 25, cfg = 7.5f, sampler = "DPM++ 2M", scheduler = "Karras", width = size, height = size),
                gated = repo.gated,
            ),
        )
    }
}

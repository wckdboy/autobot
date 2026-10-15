package dev.wckdboy.autobot.core.models

/**
 * Kokoro-82M (hexgrad, Apache-2.0), the on-device neural voice that reads answers aloud: 24 kHz
 * speech from text, English, American and British voices. One plan per app, same text frontend
 * (`dev.wckdboy.autobot.shared.tts`): Android runs the official ONNX export (8-bit) on ONNX
 * Runtime; iOS a Core ML conversion on the GPU. Each bundle also carries Kokoro's `config.json`
 * (phoneme vocabulary), misaki's English lexicons (hexgrad/misaki, Apache-2.0; read in Kotlin, no
 * espeak-ng) and a handful of voices. Files land flat in one folder, the Core ML packages under
 * their own paths. Paths, sizes and SHA-256s verified against the Hub / GitHub (2026-10), pinned.
 */
object KokoroCatalog {
    private const val KOKORO = "hexgrad/Kokoro-82M"
    private const val KOKORO_REVISION = "f3ff3571791e39611d31c381e3a41a3af07b4987"
    private const val ONNX = "onnx-community/Kokoro-82M-v1.0-ONNX"
    private const val ONNX_REVISION = "1939ad2a8e416c0acfeecc08a694d14ef25f2231"
    private const val COREML = "mlboydaisuke/Kokoro-82M-CoreML"
    private const val COREML_REVISION = "4663b41d9babce39fb5b64e04f3fb6361957fe8d"
    private const val MISAKI_REVISION = "fba1236595f2d2bf21d414ba6e57d25256afada3"

    /** Voices shipped with the bundle (`<id>.bin`, 510 × 256 float32 style vectors), best first. */
    val voices: List<Pair<String, String>> = listOf(
        "af_heart" to "Heart · American female",
        "af_bella" to "Bella · American female",
        "af_nicole" to "Nicole · American female, soft",
        "am_michael" to "Michael · American male",
        "am_fenrir" to "Fenrir · American male",
        "bf_emma" to "Emma · British female",
        "bm_george" to "George · British male",
    )

    private val voiceFiles = listOf(
        Triple("af_heart", 522_240L, "d583ccff3cdca2f7fae535cb998ac07e9fcb90f09737b9a41fa2734ec44a8f0b"),
        Triple("af_bella", 522_240L, "f69d836209b78eb8c66e75e3cda491e26ea838a3674257e9d4e5703cbaf55c8b"),
        Triple("af_nicole", 522_240L, "cd2191ab31b914ed7b318416b0e4440fdf392ddad9106a060819aa600a64f59a"),
        Triple("am_michael", 522_240L, "1d1f21dd8da39c30705cd4c75d039d265e9bc4a2a93ed09bc9e1b1225eb95ba1"),
        Triple("am_fenrir", 522_240L, "c27989f741f7ee34d273a39d8a595cc0837d35f5ced9a29b7cc162614616df43"),
        Triple("bf_emma", 522_240L, "669fe0647f9dd04fcab92f1439a40eeb4c8b4ab1f82e4996fe3d918ce4a63b73"),
        Triple("bm_george", 522_240L, "c4b235a4c1f2cd3b939fed08b899ce9385638b763f7b73a59616c4fc9bd6c9bc"),
    )

    /** The text frontend's files and the voices: the same on both platforms. */
    private val common: List<ModelFile> = listOf(
        ModelFile(
            FileRole.TOKENIZER, "config.json", "https://huggingface.co/$KOKORO/resolve/$KOKORO_REVISION/config.json", 2_351,
            "5abb01e2403b072bf03d04fde160443e209d7a0dad49a423be15196b9b43c17f", AuthHost.HUGGING_FACE, keepName = true,
        ),
        misaki("us_gold.json", 3_000_469, "dc414872a49a28ae6c141463d502fd945f3b2fde040484fdc47d00cc4612686f"),
        misaki("us_silver.json", 3_099_517, "de8f67be911bb6c659187b4a65fd966b6a30e56350e0f790d763210b053ac475"),
        misaki("gb_gold.json", 2_838_552, "29e62f4b60261c88f7f3c2c7811ca3825978948090b72d2b27d565b729282f71"),
        misaki("gb_silver.json", 3_663_898, "48131e2d92ccc41655f4543e87e0f938e71463eb5a54be7f0693bb712ebb6bce"),
    ) + voiceFiles.map { (id, size, sha) ->
        ModelFile(FileRole.OTHER, "$id.bin", "https://huggingface.co/$ONNX/resolve/$ONNX_REVISION/voices/$id.bin", size, sha, AuthHost.HUGGING_FACE, keepName = true)
    }

    private fun misaki(name: String, size: Long, sha: String) = ModelFile(
        FileRole.OTHER, name, "https://raw.githubusercontent.com/hexgrad/misaki/$MISAKI_REVISION/misaki/data/$name", size, sha, keepName = true,
    )

    private fun plan(id: String, subtitle: String, format: ModelFormat, engine: EngineKind, files: List<ModelFile>, page: String) = ModelPlan(
        id = "catalog:$id",
        kind = ModelKind.VOICE,
        source = ModelSource.CATALOG,
        title = "Kokoro",
        subtitle = subtitle,
        format = format,
        engine = engine,
        baseModel = "Kokoro-82M",
        license = "apache-2.0",
        manifest = ModelManifest(
            files = files + common,
            pageUrl = page,
            notes = "Voices: " + voices.joinToString { it.first } + ". Lexicons: hexgrad/misaki (Apache-2.0).",
        ),
    )

    /** Android: the 8-bit ONNX export (dynamic shapes, any sentence length up to 510 phonemes). */
    val onnx: ModelPlan = plan(
        "kokoro-82m-onnx-q8", "8-bit · onnx runtime · 7 voices · English", ModelFormat.ONNX, EngineKind.ONNX,
        listOf(
            ModelFile(
                FileRole.MODEL, "model_quantized.onnx", "https://huggingface.co/$ONNX/resolve/$ONNX_REVISION/onnx/model_quantized.onnx", 92_361_116,
                "fbae9257e1e05ffc727e951ef9b9c98418e6d79f1c9b6b13bd59f5c9028a1478", AuthHost.HUGGING_FACE, keepName = true,
            ),
        ),
        "https://huggingface.co/$ONNX",
    )

    /**
     * iOS: two stateless Core ML packages driven from Swift (a predictor for up to 254 phonemes,
     * then a fixed-length decoder: 256 or 512 frames, 6.4 s / 12.8 s of audio), float32 on the
     * CPU and GPU. The `.mlpackage`s are compiled on the phone on first use.
     */
    val coreML: ModelPlan = plan(
        "kokoro-82m-coreml", "float32 · core ml (gpu) · 7 voices · English", ModelFormat.COREML, EngineKind.COREML,
        listOf(
            coreml("Kokoro_Predictor", 228_295, "121fe59f42a48b7fea21f0f6793eaff65ede80447235398ed38c0b291227eda8", 77_881_472,
                "be52a0c0081c8b9ecbeb3726ec21324da607cbd4f319708702a39362ee769299", "8c457861dd7d3f1d409bdec943ec3a77d94dbe968f1b135a39d13fed1aca8aed"),
            coreml("Kokoro_Decoder_256", 352_553, "a634771e002190bdf6c0b646699e9be9020f0ff2d1825d5409bc9757296ce8cf", 252_267_328,
                "7983d82c87d2692de116c3f003a8518437143d6346b04430f737b492d0a5d9b6", "dc4a614b1bf0a5f58055c2c6dbdff295ee016c2e5c97fcdbd0294ef72b30207b"),
            coreml("Kokoro_Decoder_512", 352_553, "b852a96d8d008dc3b3b6fd20d39c8742b650c7aaaaabd0ec09163332ea0062e5", 257_796_928,
                "f423c131650185251fabdf129a27c4cf8a0e870a83e3959e6e3a82446e232194", "6bc62596ae8282b9d41bbe0fb4d1d4450e9f2dd113daee8fd296d1ade2feeaa1"),
        ).flatten(),
        "https://huggingface.co/$COREML",
    )

    private fun coreml(name: String, specSize: Long, specSha: String, weightSize: Long, weightSha: String, manifestSha: String): List<ModelFile> {
        fun file(path: String, size: Long, sha: String) = ModelFile(
            if (path.endsWith(".mlmodel")) FileRole.MODEL else FileRole.OTHER, "$name.mlpackage/$path",
            "https://huggingface.co/$COREML/resolve/$COREML_REVISION/$name.mlpackage/$path", size, sha, AuthHost.HUGGING_FACE, keepName = true,
        )
        return listOf(
            file("Manifest.json", 617, manifestSha),
            file("Data/com.apple.CoreML/model.mlmodel", specSize, specSha),
            file("Data/com.apple.CoreML/weights/weight.bin", weightSize, weightSha),
        )
    }
}

package dev.wckdboy.autobot.core.models

/**
 * Stable Audio Open Small (stabilityai/stable-audio-open-small, 341M; Stability AI Community
 * License), the on-device text-to-audio model of the Studio's audio room: up to 11.9 s of 44.1 kHz
 * stereo from a prompt in 8 steps. Same pipeline on both apps (`dev.wckdboy.autobot.shared.audiogen`),
 * converted weights from two community repositories (Stability's own repository is gated and only
 * has the PyTorch checkpoint; these are not gated, so no token is needed):
 *  - Android: `fernhoof/stable-audio-open-small`, ONNX graphs with 8-bit weight storage (dequantized
 *    in the graph; every op runs in float32) on ONNX Runtime's CPU provider, plus the
 *    `seconds_total` embedder's weights as JSON.
 *  - iOS: `mlboydaisuke/Stable-Audio-Open-Small-CoreML`, four stateless Core ML packages (T5 8-bit,
 *    seconds embedder, DiT float32 on the CPU, VAE decoder float16 on the GPU), compiled on the
 *    phone on first use.
 * Both take the T5 tokenizer from `google-t5/t5-base` (`tokenizer.json`, Apache-2.0). Paths,
 * sizes and SHA-256s verified against the Hub API (2026-10), pinned.
 *
 * Licence: free for research, non-commercial use, and commercial use by anyone (with affiliates)
 * under USD 1M annual revenue — commercial users must register at stability.ai/community-license;
 * above the threshold an enterprise licence is needed. Distributing it, or a product that uses it,
 * requires a copy of the licence, the notice "This Stability AI Model is licensed under the
 * Stability AI Community License, Copyright © Stability AI Ltd. All Rights Reserved" in a Notice
 * file, and "Powered by Stability AI" shown prominently (UI, about page or docs). Outputs belong to
 * the user; they may not be used to train other foundation models; Stability's AUP applies.
 */
object StableAudioCatalog {
    const val LICENSE = "stability-ai-community"
    const val LICENSE_URL = "https://huggingface.co/stabilityai/stable-audio-open-small/blob/main/LICENSE.md"
    const val ATTRIBUTION = "Powered by Stability AI"
    const val NOTICE = "This Stability AI Model is licensed under the Stability AI Community License, Copyright © Stability AI Ltd. All Rights Reserved"

    /** The upstream model card (gated; licence and intended use). */
    const val UPSTREAM_URL = "https://huggingface.co/stabilityai/stable-audio-open-small"
    private const val ONNX = "fernhoof/stable-audio-open-small"
    private const val ONNX_REVISION = "c9c65875efc70cc59f2d666a1acb7d37d202063e"
    private const val COREML = "mlboydaisuke/Stable-Audio-Open-Small-CoreML"
    private const val COREML_REVISION = "8fa80903adebfeb62fc885c45505ac81628f3320"
    private const val T5 = "google-t5/t5-base"
    private const val T5_REVISION = "a9723ea7f1b39c1eae772870f3b547bf6ef7e6c1"

    /** File names the engines look for in the model folder. */
    const val TOKENIZER = "tokenizer.json"
    const val ONNX_TEXT_ENCODER = "t5_text_encoder.onnx"
    const val ONNX_DIT = "dit_step.onnx"
    const val ONNX_DECODER = "vae_decoder.onnx"
    const val SECONDS_EMBEDDER = "seconds_embedder.json"
    const val COREML_TEXT_ENCODER = "StableAudioT5Encoder"
    const val COREML_SECONDS = "StableAudioNumberEmbedder"
    const val COREML_DIT = "StableAudioDiT"
    const val COREML_DECODER = "StableAudioVAEDecoder"

    private val tokenizer = ModelFile(
        FileRole.TOKENIZER, TOKENIZER, "https://huggingface.co/$T5/resolve/$T5_REVISION/tokenizer.json", 1_389_353,
        "d2acde0d8d71dd30a711834b07781b9c89feaac33fd332f60507699282740066", AuthHost.HUGGING_FACE, keepName = true,
    )

    private val recommended = Recommended(steps = 8, cfg = 1f, sampler = "pingpong")

    private val notes = "$ATTRIBUTION. Stability AI Community License: free under USD 1M annual revenue (commercial use needs " +
        "registration at stability.ai/community-license); larger organisations need an enterprise licence. Up to 11.9 s of 44.1 kHz stereo; " +
        "8 steps, no guidance (distilled). Tokenizer: google-t5/t5-base (Apache-2.0)."

    private fun plan(id: String, subtitle: String, format: ModelFormat, engine: EngineKind, files: List<ModelFile>, page: String) = ModelPlan(
        id = "catalog:$id",
        kind = ModelKind.AUDIO,
        source = ModelSource.CATALOG,
        title = "Stable Audio Open Small",
        subtitle = subtitle,
        format = format,
        engine = engine,
        baseModel = "Stable Audio Open Small",
        license = LICENSE,
        manifest = ModelManifest(files = files + tokenizer, pageUrl = page, recommended = recommended, notes = notes),
    )

    private fun onnx(name: String, role: FileRole, size: Long, sha: String) = ModelFile(
        role, name, "https://huggingface.co/$ONNX/resolve/$ONNX_REVISION/$name", size, sha, AuthHost.HUGGING_FACE, keepName = true,
    )

    /** Android: three ONNX graphs with their external weights (ONNX Runtime finds `<graph>.data` next to the graph). */
    val onnx: ModelPlan = plan(
        "stable-audio-open-small-onnx", "text to audio · 8-bit weights · onnx runtime · 11 s stereo", ModelFormat.ONNX, EngineKind.ONNX,
        listOf(
            onnx(ONNX_TEXT_ENCODER, FileRole.TEXT_ENCODER, 330_376, "95d8b885c5d7a855366f5061abc6aafe08a378ab22ecb86c3bad57e9ee673784"),
            onnx("$ONNX_TEXT_ENCODER.data", FileRole.WEIGHTS, 110_216_576, "d48426eaafeacd0e20ebd48b67179a7278ee2df6e6ad3801ee4c2bcdc371941e"),
            onnx(ONNX_DIT, FileRole.DIFFUSION_MODEL, 930_150, "1b52abe1ac0f0b5c8208bed89db538ca860e030ebc09e8a1bb338ea11044531e"),
            onnx("$ONNX_DIT.data", FileRole.WEIGHTS, 341_714_944, "9ccaac2be9c02fe6b320a6d4af2a90509b2b76ed61e70c6519ef298d75fe1bb2"),
            onnx(ONNX_DECODER, FileRole.VAE_DECODER, 250_288, "896a3aa833b84a0a314d11b5b2630c73a1fbe86495e498c375d163d038acafb5"),
            onnx("$ONNX_DECODER.data", FileRole.WEIGHTS, 78_289_664, "a30eded351e419f97a2808d3e7bbe0ab683ba03f864b4dc717abc1798e33761a"),
            onnx(SECONDS_EMBEDDER, FileRole.OTHER, 4_405_016, "0e08dd6200826d602e55ed20655f9fc30cdc46ef831d2dbde9575ce60495e250"),
        ),
        "https://huggingface.co/$ONNX",
    )

    /**
     * iOS: four Core ML packages, run one after another (the DiT holds 1.36 GB of float32 weights;
     * compute units as converted: DiT on the CPU, the others on the CPU and GPU).
     */
    val coreML: ModelPlan = plan(
        "stable-audio-open-small-coreml", "text to audio · core ml · 11 s stereo · 6 GB+ RAM", ModelFormat.COREML, EngineKind.COREML,
        listOf(
            coreml(COREML_TEXT_ENCODER, FileRole.TEXT_ENCODER, "858c9ecc86244b70430c66a0a583ea047ad6b95cfa014ea274fcd56da3cd8117",
                151_822, "7fa150b90c658122539026b45fdb70a58e9f2f28a58e508709a1d0c62037b7ab",
                109_981_696, "0cf433404691a962f6bed30ccc12ac7fae9780bf3d906b69f130bb7bd0b019da"),
            coreml(COREML_SECONDS, FileRole.OTHER, "17f3bcd351b735137d91b3b7a4fdbc2807156fb24546b28bb363994c3bd31c97",
                3_002, "14ecb9a0f6c3d04c3a611352343d7dea0c5b0720c9f8b56d87b8eae794b9c64c",
                396_800, "8ae6ab6d7bf960678564163be68ad300e245030b56eb5db10b2ab3e9090220d8"),
            coreml(COREML_DIT, FileRole.DIFFUSION_MODEL, "d7b51a6d05d6229c7191e46662916fb5db608040f29214a03547db3674d7072b",
                393_090, "a118772c17fb831a6eda6a1ec2527f535d004064e27a8eb4597d5ae6d33a0576",
                1_363_696_704, "c5444973f1a2bb3878a33049a4c559a1097de77fca8dc358aba38ee72033e97d"),
            coreml(COREML_DECODER, FileRole.VAE_DECODER, "c488c7fd8b8ad1ac2c410d96b2994e2be37aa0c7c3cf0a0dc27197251017bbf1",
                92_575, "9614b1ec8ba90995c1b8e797da19ba21fc4acc67b0dfe0bbb28527dcbfc0a262",
                156_191_488, "a8a1bdb84b854f38e2ef1d3adb5860a03303a442f8eaa8d567aeb606e66a4fc8"),
        ).flatten(),
        "https://huggingface.co/$COREML",
    )

    private fun coreml(
        name: String, role: FileRole, manifestSha: String, specSize: Long, specSha: String, weightSize: Long, weightSha: String,
    ): List<ModelFile> {
        fun file(path: String, fileRole: FileRole, size: Long, sha: String) = ModelFile(
            fileRole, "$name.mlpackage/$path", "https://huggingface.co/$COREML/resolve/$COREML_REVISION/$name.mlpackage/$path", size, sha,
            AuthHost.HUGGING_FACE, keepName = true,
        )
        return listOf(
            file("Manifest.json", FileRole.OTHER, 617, manifestSha),
            file("Data/com.apple.CoreML/model.mlmodel", role, specSize, specSha),
            file("Data/com.apple.CoreML/weights/weight.bin", FileRole.WEIGHTS, weightSize, weightSha),
        )
    }
}

package dev.wckdboy.autobot.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableAudioCatalogTest {
    private val android = StableAudioCatalog.onnx
    private val ios = StableAudioCatalog.coreML

    @Test
    fun eachAppGetsItsBundle() {
        assertEquals(listOf(android), Catalog.audioModels)
        assertTrue(android in Catalog.all)
        assertEquals(listOf(ios), CoreMLCatalog.audio)
        assertEquals(EngineKind.ONNX, engineFor(ModelKind.AUDIO, ModelFormat.ONNX, AppPlatform.ANDROID))
        assertEquals(EngineKind.COREML, engineFor(ModelKind.AUDIO, ModelFormat.COREML, AppPlatform.IOS))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.AUDIO, ModelFormat.ONNX, AppPlatform.IOS))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.AUDIO, ModelFormat.COREML, AppPlatform.ANDROID))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.AUDIO, ModelFormat.GGUF, AppPlatform.ANDROID))
        for ((plan, platform) in listOf(android to AppPlatform.ANDROID, ios to AppPlatform.IOS)) {
            assertEquals(ModelKind.AUDIO, plan.kind)
            assertEquals(plan.engine, engineFor(plan.kind, plan.format, platform))
            // The Models screen opens nothing for it yet (the Studio will).
            assertEquals(ModelTask.NONE, launchOf(plan.kind, plan.engine, ready = true, platform = platform).task)
        }
    }

    @Test
    fun fitsPhonesWithRoomForTheDecoder() {
        fun fit(platform: AppPlatform, gb: Long, plan: ModelPlan) =
            DeviceCapabilities(platform, gb * 1_000_000_000L, 0).fit(plan.kind, plan.totalBytes, plan.engine)
        assertEquals(Fit.GOOD, fit(AppPlatform.ANDROID, 12, android))
        assertEquals(Fit.TIGHT, fit(AppPlatform.ANDROID, 8, android))
        assertEquals(Fit.TOO_BIG, fit(AppPlatform.ANDROID, 4, android))
        assertEquals(Fit.GOOD, fit(AppPlatform.IOS, 12, ios))
        assertEquals(Fit.TIGHT, fit(AppPlatform.IOS, 6, ios))
        assertEquals(Fit.UNSUPPORTED, fit(AppPlatform.IOS, 12, android))
    }

    @Test
    fun filesArePinnedAndVerified() {
        for (plan in listOf(android, ios)) {
            val names = plan.manifest.files.map { it.name }
            assertEquals(names.size, names.toSet().size)
            plan.manifest.files.forEach { f ->
                assertTrue(f.url, f.keepName && f.sizeBytes > 0 && f.sha256?.length == 64 && "/main/" !in f.url)
                assertTrue(f.url, Regex("https://huggingface.co/[^/]+/[^/]+/resolve/[0-9a-f]{40}/.+").matches(f.url))
                assertTrue(f.url, f.url.endsWith("/" + f.name))
                assertEquals(AuthHost.HUGGING_FACE, f.auth)
            }
            assertTrue(StableAudioCatalog.TOKENIZER in names)
            assertEquals(StableAudioCatalog.LICENSE, plan.license)
            assertTrue(plan.manifest.notes!!.startsWith(StableAudioCatalog.ATTRIBUTION))
            assertEquals(8, plan.manifest.recommended?.steps)
            assertEquals(1f, plan.manifest.recommended?.cfg)
        }
    }

    @Test
    fun bundlesHoldWhatTheEnginesOpen() {
        val onnx = android.manifest.files.map { it.name }.toSet()
        for (graph in listOf(StableAudioCatalog.ONNX_TEXT_ENCODER, StableAudioCatalog.ONNX_DIT, StableAudioCatalog.ONNX_DECODER)) {
            assertTrue(graph in onnx && "$graph.data" in onnx)
        }
        assertTrue(StableAudioCatalog.SECONDS_EMBEDDER in onnx)
        assertEquals(537_526_367L, android.totalBytes)

        val coreml = ios.manifest.files.map { it.name }.toSet()
        for (pkg in listOf(StableAudioCatalog.COREML_TEXT_ENCODER, StableAudioCatalog.COREML_SECONDS, StableAudioCatalog.COREML_DIT, StableAudioCatalog.COREML_DECODER)) {
            for (part in listOf("Manifest.json", "Data/com.apple.CoreML/model.mlmodel", "Data/com.apple.CoreML/weights/weight.bin")) {
                assertTrue("$pkg/$part", "$pkg.mlpackage/$part" in coreml)
            }
        }
        assertEquals(1_632_298_998L, ios.totalBytes)
    }
}

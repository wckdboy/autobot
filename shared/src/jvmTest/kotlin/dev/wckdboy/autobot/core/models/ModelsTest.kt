package dev.wckdboy.autobot.core.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    @Test
    fun catalogIsConsistent() {
        val ids = (Catalog.all + CoreMLCatalog.all + CoreMLCatalog.upscalers).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Catalog.all.forEach { plan ->
            assertTrue(plan.id, plan.manifest.files.isNotEmpty())
            plan.manifest.files.forEach { f ->
                assertTrue(f.url, f.url.startsWith("https://huggingface.co/") && "/resolve/main/" in f.url)
                assertEquals(AuthHost.HUGGING_FACE, f.auth)
                assertTrue(f.sizeBytes > 0)
            }
            assertEquals(plan.id, engineFor(plan.kind, plan.format, AppPlatform.ANDROID), plan.engine)
        }
        // Multi-file diffusion bundles name every component.
        val zImage = Catalog.imageModels.first { it.id == "catalog:z-image-turbo-q4_0" }
        assertEquals(setOf(FileRole.DIFFUSION_MODEL, FileRole.LLM, FileRole.VAE), zImage.manifest.files.map { it.role }.toSet())
        // iOS runs the chat, code and speech entries, Android all of them.
        val ios = Catalog.all.filter { AppPlatform.IOS.runs(it.engine) }
        assertEquals(Catalog.chatModels + Catalog.speechModels, ios)
        assertTrue(Catalog.all.all { AppPlatform.ANDROID.runs(it.engine) })
    }

    @Test
    fun coreMLCatalogIsComplete() {
        assertEquals(3, CoreMLCatalog.all.size)
        CoreMLCatalog.all.forEach { plan ->
            val names = plan.manifest.files.map { it.name }.toSet()
            assertTrue(plan.engine == EngineKind.COREML && plan.format == ModelFormat.COREML && plan.kind == ModelKind.IMAGE)
            assertEquals(plan.id, EngineKind.COREML, engineFor(plan.kind, plan.format, AppPlatform.IOS))
            assertTrue("vocab.json" in names && "merges.txt" in names)
            assertTrue("VAEDecoder.mlmodelc/weights/weight.bin" in names)
            assertTrue("TextEncoder.mlmodelc/model.mil" in names)
            assertFalse(names.any { it.startsWith("SafetyChecker") })
            assertTrue(plan.manifest.files.all { it.keepName && it.sizeBytes > 0 && "/resolve/" in it.url })
            // Weights are LFS objects with a known SHA-256.
            assertTrue(plan.manifest.files.filter { it.name.endsWith("weight.bin") }.all { it.sha256?.length == 64 })
        }
        val sd15 = CoreMLCatalog.all[0]
        assertTrue(sd15.manifest.files.any { it.name.startsWith("VAEEncoder.mlmodelc") })
        assertEquals(957_337_899L, sd15.totalBytes)
        assertEquals(FileRole.TEXT_ENCODER_2, CoreMLCatalog.all[2].manifest.files.first { it.name.startsWith("TextEncoder2") }.role)

        val upscaler = CoreMLCatalog.upscalers.single()
        assertEquals(ModelKind.UPSCALER, upscaler.kind)
        assertEquals(EngineKind.COREML, engineFor(ModelKind.UPSCALER, ModelFormat.COREML, AppPlatform.IOS))
        assertEquals("bsd-3-clause", upscaler.license)
        val weights = upscaler.manifest.files.first { it.name.endsWith("model.mlmodel") }
        assertEquals(66_857_221L, weights.sizeBytes)
        assertEquals(FileRole.MODEL, weights.role)
    }

    @Test
    fun formatsAndEngines() {
        assertEquals(ModelFormat.GGUF, formatOf("Qwen3-4B-Q4_0.gguf"))
        assertEquals(ModelFormat.SAFETENSORS, formatOf("dreamshaper_8.safetensors"))
        assertEquals(ModelFormat.QNN, formatOf("AnythingV5_qnn2.28_8gen2.zip", "xororz"))
        assertEquals(ModelFormat.GGML, formatOf("ggml-base-q5_1.bin"))
        assertEquals(ModelFormat.COREML, formatOf("Unet.mlmodelc/weights/weight.bin"))

        val android = AppPlatform.ANDROID
        assertEquals(EngineKind.WHISPER, engineFor(ModelKind.SPEECH, ModelFormat.GGML, android))
        assertEquals(EngineKind.NPU, engineFor(ModelKind.IMAGE, ModelFormat.QNN, android))
        assertEquals(EngineKind.LLAMA, engineFor(ModelKind.CHAT, ModelFormat.GGUF, android))
        assertEquals(EngineKind.DIFFUSION, engineFor(ModelKind.IMAGE, ModelFormat.GGUF, android))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.IMAGE, ModelFormat.LOCAL_DREAM, android))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.CHAT, ModelFormat.SAFETENSORS, android))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.IMAGE, ModelFormat.COREML, android))
        assertEquals(EngineKind.DIFFUSION, engineFor(ModelKind.IMAGE, ModelFormat.GGUF))

        val ios = AppPlatform.IOS
        assertEquals(EngineKind.WHISPER, engineFor(ModelKind.SPEECH, ModelFormat.GGML, ios))
        assertEquals(EngineKind.LLAMA, engineFor(ModelKind.CHAT, ModelFormat.GGUF, ios))
        assertEquals(EngineKind.COREML, engineFor(ModelKind.IMAGE, ModelFormat.COREML, ios))
        // Android-only formats have no engine on iOS.
        assertEquals(EngineKind.NONE, engineFor(ModelKind.IMAGE, ModelFormat.QNN, ios))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.IMAGE, ModelFormat.GGUF, ios))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.IMAGE, ModelFormat.SAFETENSORS, ios))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.LORA, ModelFormat.SAFETENSORS, ios))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.CHAT, ModelFormat.SAFETENSORS, ios))
    }

    @Test
    fun formatBytesRules() {
        assertEquals("1.5 GB", formatBytes(1_500_000_000))
        assertEquals("2.4 GB", formatBytes(2_449_999_999))
        assertEquals("10.0 GB", formatBytes(9_999_999_999))
        assertEquals("512 MB", formatBytes(512_000_000))
        assertEquals("2 KB", formatBytes(2_000))
        assertEquals("12 B", formatBytes(12))
    }

    @Test
    fun androidFitRules() {
        val phone = DeviceCapabilities(AppPlatform.ANDROID, 16L shl 30, 200L shl 30, setOf("fp", "asimd", "asimddp", "i8mm"), "SM8850")
        assertEquals("v81", phone.htpArch)
        assertEquals(Fit.GOOD, phone.fit(ModelKind.CHAT, 2_500_000_000, EngineKind.LLAMA))
        assertEquals(Fit.TOO_BIG, phone.fit(ModelKind.CHAT, 9_000_000_000, EngineKind.LLAMA))
        assertEquals(Fit.UNSUPPORTED, phone.fit(ModelKind.IMAGE, 1, EngineKind.NONE))
        assertEquals(Fit.UNSUPPORTED, phone.fit(ModelKind.IMAGE, 1, EngineKind.COREML))
        assertEquals(Fit.UNSUPPORTED, phone.copy(cpuFeatures = setOf("fp", "asimd")).fit(ModelKind.IMAGE, 1_000_000_000, EngineKind.DIFFUSION))
        // SDXL NPU packages need a Snapdragon 8 Gen 3 or newer.
        assertEquals(Fit.GOOD, phone.fit(ModelKind.IMAGE, 3_600_000_000, EngineKind.NPU))
        assertEquals(Fit.UNSUPPORTED, phone.copy(socModel = "SM8550").fit(ModelKind.IMAGE, 3_600_000_000, EngineKind.NPU))
        assertEquals(Fit.GOOD, phone.copy(socModel = "SM8550").fit(ModelKind.IMAGE, 1_100_000_000, EngineKind.NPU))
    }

    private fun iphone(ram: Long) = DeviceCapabilities(AppPlatform.IOS, ram, 200L shl 30, neuralEngine = true, gpuWorkingSetBytes = ram * 3 / 4)

    @Test
    fun iosFitRules() {
        val p = iphone(12L shl 30)
        assertEquals(Fit.GOOD, p.fit(ModelKind.CHAT, 2_500_000_000, EngineKind.LLAMA))
        assertEquals(Fit.TOO_BIG, p.fit(ModelKind.CHAT, 9_000_000_000, EngineKind.LLAMA))
        assertEquals(Fit.UNSUPPORTED, p.fit(ModelKind.IMAGE, 1, EngineKind.NONE))
        assertEquals(Fit.UNSUPPORTED, p.fit(ModelKind.IMAGE, 1_000_000_000, EngineKind.DIFFUSION))
        assertEquals(Fit.UNSUPPORTED, p.fit(ModelKind.IMAGE, 1_000_000_000, EngineKind.NPU))
        assertEquals(Fit.GOOD, p.fit(ModelKind.IMAGE, 957_337_899, EngineKind.COREML))
        // SDXL (3.05 GB): fits 12 GiB, tight on 8 GiB, too big on 6 GiB.
        assertEquals(Fit.GOOD, p.fit(ModelKind.IMAGE, 3_052_951_670, EngineKind.COREML))
        assertEquals(Fit.TIGHT, iphone(8L shl 30).fit(ModelKind.IMAGE, 3_052_951_670, EngineKind.COREML))
        assertEquals(Fit.TOO_BIG, iphone(6L shl 30).fit(ModelKind.IMAGE, 3_052_951_670, EngineKind.COREML))
    }

    private fun civitai(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun civitaiParsingFiltersAndNormalizes() {
        val base = """{"id":1,"name":"M","type":"LORA","nsfw":false,"stats":{"downloadCount":5},
            "modelVersions":[{"id":9,"name":"v1","baseModel":"SD 1.5","files":[{"name":"m.safetensors","sizeKB":1.5,"primary":true,
            "hashes":{"SHA256":"ABCDEF"},"downloadUrl":"https://civitai.com/api/download/models/9"}],
            "images":[{"url":"https://image.civitai.com/x/original=true/1.jpeg","nsfwLevel":4}]}]}"""
        val parsed = parseCivitaiModel(civitai(base), showMature = false)
        assertNotNull(parsed)
        val file = parsed!!.versions.single().files.single()
        assertEquals("abcdef", file.sha256)
        assertEquals(1536L, file.sizeBytes)
        assertTrue(parsed.versions.single().previewMature)
        val plan = civitaiPlan(parsed, parsed.versions.single(), AppPlatform.ANDROID)!!
        assertEquals(ModelKind.LORA, plan.kind)
        assertEquals(EngineKind.DIFFUSION, plan.engine)
        assertEquals("civitai:9", plan.id)
        assertEquals(AuthHost.CIVITAI, plan.manifest.files.single().auth)
        // Checkpoints and LoRAs are for PC backends on iOS.
        assertEquals(EngineKind.NONE, civitaiPlan(parsed, parsed.versions.single(), AppPlatform.IOS)!!.engine)

        assertNull(parseCivitaiModel(civitai(base.replace("\"nsfw\":false", "\"nsfw\":true")), showMature = false))
        assertNotNull(parseCivitaiModel(civitai(base.replace("\"nsfw\":false", "\"nsfw\":true")), showMature = true))
        assertNull(parseCivitaiModel(civitai(base.replace("\"nsfw\":false", "\"nsfw\":false,\"minor\":true")), showMature = true))
        assertNull(parseCivitaiModel(civitai(base.replace("\"nsfw\":false", "\"nsfw\":false,\"poi\":true")), showMature = true))
        // Previews flagged as minors / real people, or X-level, are skipped.
        val hidden = base.replace("\"nsfwLevel\":4", "\"nsfwLevel\":32")
        assertNull(parseCivitaiModel(civitai(hidden), showMature = true)!!.versions.single().previewUrl)
    }

    @Test
    fun coreMLBundlesAreFoundInRepoListings() {
        val files = CoreMLCatalog.all[0].manifest.files.map { HfFile("split_einsum_v2/compiled/" + it.name, it.sizeBytes, it.sha256) } +
            CoreMLCatalog.all[0].manifest.files.map { HfFile("original/compiled/" + it.name, it.sizeBytes, it.sha256) } +
            listOf(
                HfFile("split_einsum_v2/compiled/SafetyChecker.mlmodelc/model.mil", 5, null),
                HfFile("README.md", 1, null),
                HfFile("packages/x.mlpackage/Data/m", 1, null),
            )
        val repo = HfRepo("apple/x", "abc", gated = false, license = "openrail++", files = files)
        assertEquals(listOf("split_einsum_v2/compiled", "original/compiled"), CoreMLPackages.bundles(repo))
        assertEquals(957_337_899L, CoreMLPackages.sizeOf(repo, "split_einsum_v2/compiled"))
        val plan = CoreMLPackages.plan(repo, "split_einsum_v2/compiled")
        assertEquals("hf:apple/x:split_einsum_v2/compiled", plan.id)
        assertEquals(EngineKind.COREML, plan.engine)
        assertEquals(957_337_899L, plan.totalBytes)
        assertTrue(plan.manifest.files.none { it.name.startsWith("SafetyChecker") })
        assertTrue(plan.manifest.files.all { it.keepName && it.url.startsWith("https://huggingface.co/apple/x/resolve/abc/split_einsum_v2/compiled/") })
        assertEquals(512, plan.manifest.recommended?.width)
    }
}

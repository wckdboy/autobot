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
        val ids = Catalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Catalog.all.forEach { plan ->
            assertTrue(plan.id, plan.manifest.files.isNotEmpty())
            plan.manifest.files.forEach { f ->
                assertTrue(f.url, f.url.startsWith("https://huggingface.co/") && "/resolve/main/" in f.url)
                assertEquals(AuthHost.HUGGING_FACE, f.auth)
                assertTrue(f.sizeBytes > 0)
            }
            assertEquals(plan.id, engineFor(plan.kind, plan.format), plan.engine)
        }
        // Multi-file diffusion bundles name every component.
        val zImage = Catalog.imageModels.first { it.id == "catalog:z-image-turbo-q4_0" }
        assertEquals(setOf(FileRole.DIFFUSION_MODEL, FileRole.LLM, FileRole.VAE), zImage.manifest.files.map { it.role }.toSet())
    }

    @Test
    fun formatsAndEngines() {
        assertEquals(ModelFormat.GGUF, formatOf("Qwen3-4B-Q4_0.gguf"))
        assertEquals(ModelFormat.SAFETENSORS, formatOf("dreamshaper_8.safetensors"))
        assertEquals(ModelFormat.QNN, formatOf("AnythingV5_qnn2.28_8gen2.zip", "xororz"))
        assertEquals(ModelFormat.GGML, formatOf("ggml-base-q5_1.bin"))
        assertEquals(EngineKind.WHISPER, engineFor(ModelKind.SPEECH, ModelFormat.GGML))
        assertEquals(EngineKind.NPU, engineFor(ModelKind.IMAGE, ModelFormat.QNN))
        assertEquals(EngineKind.LLAMA, engineFor(ModelKind.CHAT, ModelFormat.GGUF))
        assertEquals(EngineKind.DIFFUSION, engineFor(ModelKind.IMAGE, ModelFormat.GGUF))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.IMAGE, ModelFormat.LOCAL_DREAM))
        assertEquals(EngineKind.NONE, engineFor(ModelKind.CHAT, ModelFormat.SAFETENSORS))
    }

    @Test
    fun deviceProfileFitsAndFeatures() {
        val features = DeviceProfile.parseCpuFeatures("processor\t: 0\nFeatures\t: fp asimd asimddp i8mm sve2\nCPU part\t: 0x001\n")
        assertEquals(setOf("fp", "asimd", "asimddp", "i8mm", "sve2"), features)
        val phone = DeviceProfile("SM8850", DeviceProfile.socName("SM8850"), 16L shl 30, 200L shl 30, features, 8)
        assertEquals("SM8850 · Snapdragon 8 Elite Gen 5", phone.label)
        assertEquals(Fit.GOOD, phone.fit(ModelKind.CHAT, 2_500_000_000, EngineKind.LLAMA))
        assertEquals(Fit.TOO_BIG, phone.fit(ModelKind.CHAT, 9_000_000_000, EngineKind.LLAMA))
        assertEquals(Fit.UNSUPPORTED, phone.fit(ModelKind.IMAGE, 1, EngineKind.NONE))
        val old = phone.copy(cpuFeatures = setOf("fp", "asimd"))
        assertEquals(Fit.UNSUPPORTED, old.fit(ModelKind.IMAGE, 1_000_000_000, EngineKind.DIFFUSION))
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
        val plan = civitaiPlan(parsed, parsed.versions.single())!!
        assertEquals(ModelKind.LORA, plan.kind)
        assertEquals(EngineKind.DIFFUSION, plan.engine)
        assertEquals("civitai:9", plan.id)

        assertNull(parseCivitaiModel(civitai(base.replace("\"nsfw\":false", "\"nsfw\":true")), showMature = false))
        assertNotNull(parseCivitaiModel(civitai(base.replace("\"nsfw\":false", "\"nsfw\":true")), showMature = true))
        assertNull(parseCivitaiModel(civitai(base.replace("\"nsfw\":false", "\"nsfw\":false,\"minor\":true")), showMature = true))
        assertNull(parseCivitaiModel(civitai(base.replace("\"nsfw\":false", "\"nsfw\":false,\"poi\":true")), showMature = true))
        assertFalse(formatBytes(1_500_000_000).isEmpty())
    }
}

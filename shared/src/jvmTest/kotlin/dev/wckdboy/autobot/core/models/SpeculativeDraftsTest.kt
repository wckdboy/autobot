package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.providers.remote.ChatMessage
import dev.wckdboy.autobot.providers.remote.ChatRequest
import dev.wckdboy.autobot.providers.remote.ChatRole
import dev.wckdboy.autobot.providers.remote.LocalLlmCodec
import dev.wckdboy.autobot.shared.diagnostics.BenchmarkLine
import dev.wckdboy.autobot.shared.diagnostics.DeviceInfo
import dev.wckdboy.autobot.shared.diagnostics.DiagnosticsReport
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeculativeDraftsTest {
    private val gb = 1_000_000_000L

    private fun installed(plan: ModelPlan, ready: Boolean = true) = InstalledModel(
        id = plan.id, kind = plan.kind, source = plan.source, title = plan.title, subtitle = plan.subtitle, format = plan.format,
        engine = plan.engine, baseModel = plan.baseModel, license = plan.license, nsfw = false, totalBytes = plan.totalBytes,
        downloadedBytes = if (ready) plan.totalBytes else 0, status = if (ready) ModelStatus.READY else ModelStatus.DOWNLOADING,
        error = null, manifest = plan.manifest, addedAt = 0,
        paths = if (ready) mapOf(FileRole.MODEL to "/models/${plan.manifest.files.first().name}") else emptyMap(),
    )

    private fun plan(id: String) = Catalog.chatModels.first { it.id == id }

    private val qwen4b = installed(plan("catalog:qwen3-4b-2507-q4_0"))
    private val qwen06 = installed(plan("catalog:qwen3-0.6b-q4_0"))

    private fun phone(ramGb: Long) = DeviceCapabilities(AppPlatform.ANDROID, ramGb * gb, 50 * gb)

    @Test
    fun everyPairNamesCatalogChatModelsWithADraftSmallerThanItsTarget() {
        val catalog = Catalog.chatModels.associateBy { it.id }
        assertTrue(SpeculativeDrafts.pairs.isNotEmpty())
        for ((target, drafts) in SpeculativeDrafts.pairs) {
            val t = catalog[target]
            assertNotNull("target $target in the catalog", t)
            for (d in drafts) {
                val draft = catalog[d]
                assertNotNull("draft $d in the catalog", draft)
                assertTrue("$d is smaller than $target", draft!!.totalBytes * 2 < t!!.totalBytes)
                assertEquals(EngineKind.LLAMA, draft.engine)
            }
        }
    }

    @Test
    fun hybridAndVisionModelsAreNotPaired() {
        // Qwen3.5 (recurrent layers) cannot drop rejected drafts; images are not drafted.
        val unpaired = Catalog.chatModels.filter { it.id.contains("qwen3.5") || it.manifest.files.any { f -> f.role == FileRole.MMPROJ } }
        assertTrue(unpaired.isNotEmpty())
        unpaired.forEach { assertTrue(it.id, SpeculativeDrafts.draftsFor(it.id).isEmpty()) }
        assertEquals(listOf("catalog:qwen3-4b-2507-q4_0"), SpeculativeDrafts.targetsOf("catalog:qwen3-0.6b-q4_0"))
    }

    @Test
    fun offPicksNothing() {
        assertNull(SpeculativeDrafts.pick(SpeculativeMode.OFF, qwen4b, listOf(qwen4b, qwen06), phone(16), 8192, Backend.CPU, AppPlatform.ANDROID))
    }

    @Test
    fun autoNeedsTheDraftInstalledAndReady() {
        assertNull(SpeculativeDrafts.pick(SpeculativeMode.AUTO, qwen4b, listOf(qwen4b), phone(16), 8192, Backend.CPU, AppPlatform.ANDROID))
        val downloading = installed(plan("catalog:qwen3-0.6b-q4_0"), ready = false)
        assertNull(SpeculativeDrafts.pick(SpeculativeMode.AUTO, qwen4b, listOf(qwen4b, downloading), phone(16), 8192, Backend.CPU, AppPlatform.ANDROID))
        // The file was deleted outside the app.
        assertNull(SpeculativeDrafts.pick(SpeculativeMode.AUTO, qwen4b, listOf(qwen4b, qwen06), phone(16), 8192, Backend.CPU, AppPlatform.ANDROID) { false })
        val pick = SpeculativeDrafts.pick(SpeculativeMode.AUTO, qwen4b, listOf(qwen4b, qwen06), phone(16), 8192, Backend.CPU, AppPlatform.ANDROID)
        assertEquals(qwen06.id, pick?.modelId)
        assertEquals("/models/Qwen3-0.6B-Q4_0.gguf", pick?.path)
        assertEquals(SpeculativeMode.AUTO, pick?.mode)
    }

    @Test
    fun autoOnlyWhenThePairFitsComfortablyOnDoesWhenItFitsAtAll() {
        val all = listOf(qwen4b, qwen06)
        // 16 GB: the 4B model fits well alone and with its draft.
        assertNotNull(SpeculativeDrafts.pick(SpeculativeMode.AUTO, qwen4b, all, phone(16), 8192, Backend.CPU, AppPlatform.ANDROID))
        // 12 GB: still well alone, the pair is tight: auto drafts.
        assertNotNull(SpeculativeDrafts.pick(SpeculativeMode.AUTO, qwen4b, all, phone(12), 8192, Backend.CPU, AppPlatform.ANDROID))
        // 10 GB: the 4B model alone is already tight: auto leaves the memory to it, on drafts while the pair fits.
        val ten = phone(10)
        assertEquals(Fit.TIGHT, ten.fit(qwen4b.kind, qwen4b.totalBytes, qwen4b.engine, 4096))
        assertEquals(Fit.TIGHT, SpeculativeDrafts.fit(ten, qwen4b.totalBytes, qwen06.totalBytes, 4096))
        assertNull(SpeculativeDrafts.pick(SpeculativeMode.AUTO, qwen4b, all, ten, 4096, Backend.CPU, AppPlatform.ANDROID))
        assertNotNull(SpeculativeDrafts.pick(SpeculativeMode.ON, qwen4b, all, ten, 4096, Backend.CPU, AppPlatform.ANDROID))
        // ... and not when the pair would not fit.
        assertEquals(Fit.TOO_BIG, SpeculativeDrafts.fit(ten, qwen4b.totalBytes, qwen06.totalBytes, 16384))
        assertNull(SpeculativeDrafts.pick(SpeculativeMode.ON, qwen4b, all, ten, 16384, Backend.CPU, AppPlatform.ANDROID))
        // 4 GB: never.
        assertNull(SpeculativeDrafts.pick(SpeculativeMode.ON, qwen4b, all, phone(4), 8192, Backend.CPU, AppPlatform.ANDROID))
    }

    @Test
    fun theDraftAddsItsOwnWeightsAndCacheToTheFit() {
        val device = phone(12)
        val alone = device.chatWorkingBytes(qwen4b.totalBytes, 8192)
        val draft = SpeculativeDrafts.draftWorkingBytes(qwen06.totalBytes, 8192)
        assertTrue(draft > qwen06.totalBytes)
        // The draft's context is a window: a 32k chat does not grow it.
        assertEquals(draft, SpeculativeDrafts.draftWorkingBytes(qwen06.totalBytes, 32768))
        assertEquals(device.fitWorking(alone + draft), SpeculativeDrafts.fit(device, qwen4b.totalBytes, qwen06.totalBytes, 8192))
    }

    @Test
    fun placement() {
        // Android: the CPU whatever the model uses (an accelerator pays per single-token step).
        for (b in Backend.entries) assertEquals("cpu", SpeculativeDrafts.draftBackend(AppPlatform.ANDROID, b))
        // iOS: Metal with the model, the CPU when the model is on the CPU.
        assertEquals("gpu", SpeculativeDrafts.draftBackend(AppPlatform.IOS, Backend.GPU))
        assertEquals("gpu", SpeculativeDrafts.draftBackend(AppPlatform.IOS, Backend.AUTO))
        assertEquals("cpu", SpeculativeDrafts.draftBackend(AppPlatform.IOS, Backend.CPU))
    }

    @Test
    fun theRequestCarriesTheDraftAndTheResultItsStats() {
        val request = ChatRequest(model = qwen4b.id, messages = listOf(ChatMessage(ChatRole.USER, "hi")))
        val plain = LocalLlmCodec.encode(request)
        assertFalse("draft" in plain)
        assertFalse("speculative" in plain)
        val draft = DraftChoice(qwen06.id, qwen06.title, "/m/d.gguf", "cpu", SpeculativeMode.ON)
        val encoded = LocalLlmCodec.encode(request, draft = draft)
        assertEquals("/m/d.gguf", encoded["draft"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("cpu", encoded["draft"]!!.jsonObject["backend"]!!.jsonPrimitive.content)
        assertEquals("on", encoded["speculative"]!!.jsonPrimitive.content)

        val result = """{"content":"x","usage":{"completion_tokens":10,"speculative":{"status":"active","draft":"qwen3 0.6B Q4_0","drafted":40,"accepted":30}}}"""
        val stats = LocalLlmCodec.speculativeStats(result)!!
        assertEquals("active", stats.status)
        assertEquals(0.75, stats.acceptance, 1e-9)
        val refused = LocalLlmCodec.speculativeStats("""{"usage":{"speculative":{"status":"refused","reason":"the draft model's tokenizer differs"}}}""")!!
        assertEquals("refused", refused.status)
        assertTrue(refused.reason!!.contains("tokenizer"))
        assertNull(LocalLlmCodec.speculativeStats("""{"usage":{}}"""))
        assertNull(LocalLlmCodec.speculativeStats("not json"))
        // The usual decoding ignores the extra usage field.
        assertTrue(LocalLlmCodec.decodeResult(result).isNotEmpty())
    }

    @Test
    fun benchmarkSpeedupAndReport() {
        val b = SpeculativeBenchmark(qwen4b.id, qwen06.title, Backend.CPU, plainTps = 10.0, speculativeTps = 15.0, acceptance = 0.7)
        assertEquals(1.5, b.speedup, 1e-9)
        val text = DiagnosticsReport(
            app = "Autobot", os = "test", device = DeviceInfo("phone"),
            benchmarks = listOf(BenchmarkLine(qwen4b.title, "cpu + draft", generationTps = 15.0, draft = qwen06.title, acceptance = 0.7, speedup = 1.5)),
        ).render()
        assertTrue(text, text.contains("speculative 1.5x vs plain"))
        assertTrue(text, text.contains("70% of drafts accepted"))
        assertTrue(text, text.contains("draft Qwen3 0.6B"))
    }
}

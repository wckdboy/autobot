package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.shared.studio.comfy.ObjectInfo
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkflowTest {
    private val info = ObjectInfo.parse(fixture("object_info.json"))

    private fun create(name: String, withInfo: Boolean = true) = WorkflowImport.create(name, fixture(name), if (withInfo) info else null, id = "w-$name", now = 5)

    private fun StudioWorkflow.p(name: String) = param(name) ?: error("no $name in ${params.map { it.name }}")

    private fun JsonObject.input(node: String, name: String) = this[node]!!.jsonObject["inputs"]!!.jsonObject[name]!!.jsonPrimitive

    @Test
    fun sd15DefaultWorkflow() {
        val w = create("sd15_txt2img_api.json")
        assertEquals(
            listOf("prompt", "negative", "checkpoint", "seed", "steps", "cfg", "sampler", "scheduler", "denoise", "width", "height", "batch"),
            w.params.map { it.name },
        )
        assertEquals(listOf(InputTarget("6", "text")), w.p("prompt").targets)
        assertTrue(w.p("prompt").multiline)
        assertEquals(ParamValue.of("text, watermark"), w.p("negative").default)
        assertEquals(ParamType.SEED, w.p("seed").type)
        assertEquals(ParamValue.of(156680208700286L), w.p("seed").default)
        assertEquals(ParamValue.of(20), w.p("steps").default)
        assertEquals(10000.0, w.p("steps").max!!, 0.0)
        assertEquals(ParamType.FLOAT, w.p("cfg").type)
        assertEquals(ParamValue.of(8.0), w.p("cfg").default)
        assertEquals(ParamType.ENUM, w.p("sampler").type)
        assertTrue("from /object_info", "dpmpp_3m_sde_gpu" in w.p("sampler").options)
        assertEquals(listOf("sd_xl_base_1.0.safetensors", "v1-5-pruned-emaonly-fp16.safetensors"), w.p("checkpoint").options)
        assertEquals(8.0, w.p("width").step!!, 0.0)
        assertEquals(AssetType.IMAGE, w.output)
        assertEquals(listOf("9"), w.outputNodes)
        assertTrue(w.p("sampler").advanced)
    }

    @Test
    fun sdxlWithLoraAndSubgraphIds() {
        val w = create("sdxl_lora_api.json")
        assertEquals(listOf(InputTarget("6", "text")), w.p("prompt").targets)
        assertEquals(listOf(InputTarget("7", "text")), w.p("negative").targets)
        assertEquals(listOf(InputTarget("10", "lora_name")), w.p("lora").targets)
        assertEquals(listOf(InputTarget("10", "strength_model"), InputTarget("10", "strength_clip")), w.p("lora_strength").targets)
        assertEquals(ParamValue.of(0.8), w.p("lora_strength").default)
        assertEquals(listOf(InputTarget("10:3", "noise_seed")), w.p("seed").targets)
        assertEquals(ParamValue.of(25), w.p("steps").default)
        assertEquals(ParamValue.of("karras"), w.p("scheduler").default)
        assertNull("KSamplerAdvanced has no denoise", w.param("denoise"))
        assertEquals(ParamValue.of(2), w.p("batch").default)
        assertEquals(listOf("19", "20"), w.outputNodes)
    }

    @Test
    fun ltxVideoTextToVideo() {
        val w = create("ltxv_t2v_api.json", withInfo = false)
        assertEquals(AssetType.VIDEO, w.output)
        assertEquals(listOf("41"), w.outputNodes)
        assertEquals("traced through LTXVConditioning slot 0", listOf(InputTarget("6", "text")), w.p("prompt").targets)
        assertEquals("slot 1 follows the negative input", listOf(InputTarget("7", "text")), w.p("negative").targets)
        assertEquals(listOf(InputTarget("72", "noise_seed")), w.p("seed").targets)
        assertEquals(listOf(InputTarget("71", "steps")), w.p("steps").targets)
        assertEquals(ParamValue.of(3.0), w.p("cfg").default)
        assertEquals(listOf(InputTarget("73", "sampler_name")), w.p("sampler").targets)
        assertTrue("built-in sampler list without /object_info", "euler" in w.p("sampler").options)
        assertEquals(ParamValue.of(768), w.p("width").default)
        assertEquals(ParamValue.of(512), w.p("height").default)
        assertEquals(listOf(InputTarget("70", "length")), w.p("frames").targets)
        assertEquals(ParamValue.of(97), w.p("frames").default)
        assertEquals(ParamType.INT, w.p("fps").type)
        assertEquals(listOf(InputTarget("41", "fps"), InputTarget("69", "frame_rate")), w.p("fps").targets)
        assertEquals(ParamValue.of("ltx-video-2b-v0.9.5.safetensors"), w.p("checkpoint").default)
    }

    @Test
    fun stableAudio() {
        val w = create("stable_audio_api.json", withInfo = false)
        assertEquals(AssetType.AUDIO, w.output)
        assertEquals(listOf("19"), w.outputNodes)
        assertEquals("traced through ConditioningStableAudio", listOf(InputTarget("6", "text")), w.p("prompt").targets)
        assertEquals(listOf(InputTarget("7", "text")), w.p("negative").targets)
        assertEquals(ParamValue.of(""), w.p("negative").default)
        assertEquals(listOf(InputTarget("11", "seconds"), InputTarget("13", "seconds_total")), w.p("seconds").targets)
        assertEquals(ParamValue.of(47.6), w.p("seconds").default)
        assertEquals(ParamValue.of("exponential"), w.p("scheduler").default)
        assertNull(w.param("width"))
        assertNull(w.param("frames"))
    }

    @Test
    fun negativeThatIsJustThePositiveZeroedIsNotAParam() {
        val graph = """{
          "1":{"class_type":"CLIPTextEncode","inputs":{"text":"a cat","clip":["9",1]}},
          "2":{"class_type":"ConditioningZeroOut","inputs":{"conditioning":["1",0]}},
          "3":{"class_type":"KSampler","inputs":{"seed":1,"steps":4,"cfg":1,"sampler_name":"euler","scheduler":"simple","denoise":1,"positive":["1",0],"negative":["2",0],"model":["9",0],"latent_image":["4",0]}},
          "4":{"class_type":"EmptySD3LatentImage","inputs":{"width":1024,"height":1024,"batch_size":1}},
          "5":{"class_type":"PrimitiveStringMultiline","inputs":{"value":"from a primitive"}},
          "6":{"class_type":"CLIPTextEncode","inputs":{"text":["5",0],"clip":["9",1]}},
          "7":{"class_type":"SaveImage","inputs":{"images":["8",0],"filename_prefix":"x"}}
        }"""
        val w = WorkflowImport.create("flux", graph)
        assertEquals(listOf(InputTarget("1", "text")), w.p("prompt").targets)
        assertNull(w.param("negative"))
        assertEquals(ParamValue.of(1024), w.p("width").default)
        // Text fed from a primitive node is found too (when it is the sampler's prompt).
        val viaPrimitive = WorkflowImport.create("p", graph.replace(""""positive":["1",0]""", """"positive":["6",0]"""))
        assertEquals(listOf(InputTarget("5", "value")), viaPrimitive.p("prompt").targets)
    }

    @Test
    fun importRefusesTheEditorFormatAndUnwrapsPromptBodies() {
        val ui = WorkflowImport.parse(fixture("ui_format.json")) as ImportResult.Invalid
        assertEquals(WorkflowImport.UI_FORMAT_MESSAGE, ui.message)
        assertTrue(ui.message.contains("Save (API Format)"))
        val wrapped = """{"prompt":${fixture("sd15_txt2img_api.json")},"client_id":"x"}"""
        assertEquals(7, (WorkflowImport.parse(wrapped) as ImportResult.Graph).graph.size)
        assertTrue(WorkflowImport.parse("[1,2]") is ImportResult.Invalid)
        assertTrue(WorkflowImport.parse("{}") is ImportResult.Invalid)
        val bad = WorkflowImport.parse("""{"1":{"inputs":{}}}""") as ImportResult.Invalid
        assertTrue(bad.message, bad.message.startsWith("Node \"1\" has no class_type"))
        try {
            WorkflowImport.create("x", """{"1":{"class_type":"CLIPTextEncode","inputs":{"text":"a"}}}""")
            fail()
        } catch (e: WorkflowException) {
            assertTrue(e.message!!.contains("no output node"))
        }
    }

    @Test
    fun validationListsMissingNodesAndModels() {
        val ok = WorkflowValidator.validate(create("sd15_txt2img_api.json").graph, info)
        assertTrue(ok.ok)
        assertNull(ok.message)
        val ltx = WorkflowValidator.validate(create("ltxv_t2v_api.json", withInfo = false).graph, info)
        assertEquals(listOf("CLIPLoader", "SaveAnimatedWEBP", "LTXVConditioning", "EmptyLTXVLatentVideo", "LTXVScheduler", "SamplerCustom"), ltx.missingNodes)
        assertEquals(listOf("ltx-video-2b-v0.9.5.safetensors"), ltx.missingValues.map { it.value })
        assertEquals(
            "Missing nodes: CLIPLoader, SaveAnimatedWEBP, LTXVConditioning, EmptyLTXVLatentVideo, LTXVScheduler, SamplerCustom (install the custom nodes on the server). " +
                "Missing models: ltx-video-2b-v0.9.5.safetensors (CheckpointLoaderSimple.ckpt_name)",
            ltx.message,
        )
        val audio = WorkflowValidator.validate(create("stable_audio_api.json", withInfo = false).graph, info)
        assertTrue(audio.missingValues.any { !it.isModel && it.value == "dpmpp_3m_sde_gpu" }.not())
        assertTrue(audio.message!!.contains("stable_audio_open_1.0.safetensors"))
    }

    @Test
    fun applyingValuesIsImmutableAndTyped() {
        val w = create("sd15_txt2img_api.json")
        val applied = WorkflowApplier.apply(
            w,
            mapOf("prompt" to ParamValue.of("a red fox"), "steps" to ParamValue.of(99_999), "cfg" to ParamValue.of("6.5"), "seed" to ParamValue.of(5), "width" to ParamValue.of(768.0)),
        )
        val g = applied.graph
        assertEquals("a red fox", g.input("6", "text").content)
        assertEquals("clamped to /object_info max", "10000", g.input("3", "steps").content)
        assertEquals("6.5", g.input("3", "cfg").content)
        assertEquals("5", g.input("3", "seed").content)
        assertEquals("768", g.input("5", "width").content)
        assertEquals("defaults fill the rest", "text, watermark", g.input("7", "text").content)
        assertEquals(5L, applied.seed)
        assertEquals(ParamValue.of(10000), applied.values["steps"])
        // The saved graph is untouched.
        assertEquals("beautiful scenery nature glass bottle landscape, , purple galaxy bottle,", w.graph.input("6", "text").content)
        assertEquals(JsonPrimitive(20), w.graph.input("3", "steps"))
        // Links survive.
        assertEquals(w.graph["3"]!!.jsonObject["inputs"]!!.jsonObject["model"], g["3"]!!.jsonObject["inputs"]!!.jsonObject["model"])
    }

    @Test
    fun seedsRandomizeOnRequestOrWhenNegative() {
        val w = create("sd15_txt2img_api.json")
        val a = WorkflowApplier.apply(w, emptyMap(), randomizeSeed = true, random = Random(1)).seed!!
        val b = WorkflowApplier.apply(w, emptyMap(), randomizeSeed = true, random = Random(2)).seed!!
        assertNotEquals(a, b)
        assertTrue(a in 0..Seeds.MAX)
        assertEquals(a, WorkflowApplier.apply(w, mapOf("seed" to ParamValue.of(-1)), random = Random(1)).seed)
        assertEquals("the saved default without randomize", 156680208700286L, WorkflowApplier.apply(w, emptyMap()).seed)
    }

    @Test
    fun wrongValuesAreRefusedClearly() {
        val w = create("sd15_txt2img_api.json")
        fun refused(values: Map<String, ParamValue>): String = try {
            WorkflowApplier.apply(w, values)
            fail()
            ""
        } catch (e: WorkflowException) {
            e.message!!
        }
        assertTrue(refused(mapOf("nope" to ParamValue.of(1))).startsWith("\"sd15_txt2img_api.json\" has no parameter \"nope\"; it takes: prompt, negative"))
        assertTrue(refused(mapOf("sampler" to ParamValue.of("magic"))).startsWith("Sampler \"magic\" isn't available; choose one of: euler"))
        assertEquals("Steps expects int, got many", refused(mapOf("steps" to ParamValue.of("many"))))
    }

    @Test
    fun imageParamsTakeUploadedNamesOrServerFiles() {
        val graph = fixture("sd15_txt2img_api.json").trimEnd().removeSuffix("}") + """, "10":{"class_type":"LoadImage","inputs":{"image":"example.png"}}}"""
        val w = WorkflowImport.create("img", graph, info)
        val image = w.p("image")
        assertEquals(ParamType.IMAGE, image.type)
        assertEquals("Input image", image.label)
        assertEquals("server file", "other.png", WorkflowApplier.apply(w, mapOf("image" to ParamValue.of("other.png"))).graph.input("10", "image").content)
        val asset = ParamValue.AssetValue(AssetRef("a-000000000001"))
        assertEquals("autobot/in.png", WorkflowApplier.apply(w, mapOf("image" to asset), uploads = mapOf("image" to "autobot/in.png")).graph.input("10", "image").content)
        try {
            WorkflowApplier.apply(w, mapOf("image" to asset))
            fail()
        } catch (e: WorkflowException) {
            assertEquals("Input image: the asset was not uploaded", e.message)
        }
    }

    @Test
    fun schemaEditsExposeRenameRemoveAndMove() {
        val w = create("sd15_txt2img_api.json")
        val exposable = WorkflowSchema.exposable(w, info)
        assertTrue(exposable.any { it.nodeId == "9" && it.input == "filename_prefix" && it.type == ParamType.STRING })
        assertFalse("already a parameter", exposable.any { it.nodeId == "6" && it.input == "text" })
        assertFalse("links are not exposable", exposable.any { it.input == "model" })

        val exposed = WorkflowSchema.expose(w, "9", "filename_prefix", "File prefix", info, now = 9)
        val p = exposed.p("file_prefix")
        assertEquals("File prefix", p.label)
        assertEquals(ParamValue.of("ComfyUI"), p.default)
        assertEquals(2, exposed.version)
        assertEquals(9, exposed.updatedAt)
        assertEquals("custom", WorkflowApplier.apply(exposed, mapOf("file_prefix" to ParamValue.of("custom"))).graph.input("9", "filename_prefix").content)
        try {
            WorkflowSchema.expose(exposed, "9", "filename_prefix")
            fail()
        } catch (e: WorkflowException) {
            assertTrue(e.message!!.contains("already a parameter"))
        }
        val again = runCatching { WorkflowSchema.expose(exposed, "3", "denoise", null, info) }
        assertTrue("denoise is already the denoise parameter", again.exceptionOrNull() is WorkflowException)
    }

    @Test
    fun schemaRenameRemoveMoveUpdateAndRefresh() {
        val w = create("sd15_txt2img_api.json", withInfo = false)
        val renamed = WorkflowSchema.rename(w, "cfg", "Guidance")
        assertEquals("Guidance", renamed.p("cfg").label)
        val removed = WorkflowSchema.remove(renamed, "denoise")
        assertNull(removed.param("denoise"))
        val moved = WorkflowSchema.move(removed, "seed", 0)
        assertEquals("seed", moved.params.first().name)
        val updated = WorkflowSchema.update(moved, moved.p("steps").copy(max = 60.0, targets = emptyList(), advanced = true))
        assertEquals(60.0, updated.p("steps").max!!, 0.0)
        assertEquals("targets cannot be edited away", listOf(InputTarget("3", "steps")), updated.p("steps").targets)
        assertEquals(5, updated.version)
        val refreshed = WorkflowSchema.refreshOptions(updated, info)
        assertEquals(listOf("sd_xl_base_1.0.safetensors", "v1-5-pruned-emaonly-fp16.safetensors"), refreshed.p("checkpoint").options)
        assertTrue("dpmpp_3m_sde_gpu" in refreshed.p("sampler").options)
        assertEquals(StudioCodec.decodeWorkflow(StudioCodec.encodeWorkflow(refreshed)), refreshed)
    }
}

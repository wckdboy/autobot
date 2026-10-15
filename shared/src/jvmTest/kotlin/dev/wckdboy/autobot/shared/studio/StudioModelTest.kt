package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.shared.backup.BackupManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioModelTest {
    private val recipe = Recipe(
        engine = EngineKinds.COMFYUI,
        backendId = "pc",
        params = mapOf("prompt" to ParamValue.of("a cat"), "steps" to ParamValue.of(20), "cfg" to ParamValue.of(7.5), "hires" to ParamValue.of(true)),
        inputs = listOf(AssetRef("a-000000000001")),
        workflowId = "w-1",
        workflowVersion = 3,
        seed = 42,
    )

    @Test
    fun paramValuesHaveAStableTypedJsonForm() {
        val json = StudioCodec.encodeRecipe(recipe)
        assertTrue(json, json.contains(""""steps":{"type":"int","value":20}"""))
        assertTrue(json, json.contains(""""cfg":{"type":"float","value":7.5}"""))
        assertTrue(json, json.contains(""""prompt":{"type":"string","value":"a cat"}"""))
        assertTrue(json, json.contains(""""hires":{"type":"bool","value":true}"""))
        assertEquals(recipe, StudioCodec.decodeRecipe(json))
    }

    @Test
    fun assetsRoundTripAndOlderRecordsDecodeWithDefaults() {
        val asset = StudioAsset("a-1", "p-1", AssetType.AUDIO, recipe, 5, "audio/flac", 10, 47_000, favorite = true, parentId = "a-0", label = "seed 42")
        assertEquals(asset, StudioCodec.decodeAsset(StudioCodec.encodeAsset(asset)))
        // A record written before durations / labels existed, with a field from a newer app.
        val old = """{"id":"a-2","projectId":"p-1","type":"IMAGE","recipe":{"engine":"diffusion"},"createdAt":1,"futureField":{"x":1}}"""
        val decoded = StudioCodec.decodeAsset(old)
        assertEquals(0L, decoded.durationMs)
        assertFalse(decoded.favorite)
        assertNull(decoded.recipe.seed)
        assertEquals(AssetType.IMAGE, decoded.recipe.output)
    }

    @Test
    fun recipeHelpersSetSeedModelAndInputs() {
        val r = Recipe(EngineKinds.DIFFUSION)
            .with("seed", ParamValue.of(7))
            .with("model", ParamValue.of("sdxl"))
            .with("steps", ParamValue.of(30))
            .withInput("a-1")
            .withInput("a-2")
            .withInput("a-3", "mask")
        assertEquals(7L, r.seed)
        assertEquals("sdxl", r.model)
        assertEquals(ParamValue.of(30), r.value("steps"))
        assertEquals(listOf(AssetRef("a-2"), AssetRef("a-3", "mask")), r.inputs)
    }

    @Test
    fun inMemoryStoreFiltersAndDeletesProjectsWithAssets() = runBlocking {
        val store = InMemoryStudioStore()
        store.putProject(StudioProject("p-1", "One", null, 1, 1))
        store.putProject(StudioProject("p-2", "Two", null, 1, 2))
        store.putAsset(StudioAsset("a-1", "p-1", AssetType.IMAGE, recipe, 1))
        store.putAsset(StudioAsset("a-2", "p-1", AssetType.AUDIO, recipe, 2, favorite = true))
        store.putAsset(StudioAsset("a-3", "p-2", AssetType.IMAGE, recipe, 3))
        assertEquals(listOf("a-2", "a-1"), store.assets(AssetFilter(projectId = "p-1")).map { it.id })
        assertEquals(listOf("a-3", "a-1"), store.assets(AssetFilter(types = setOf(AssetType.IMAGE))).map { it.id })
        assertEquals(listOf("a-2"), store.assets(AssetFilter(favoritesOnly = true)).map { it.id })
        store.deleteProject("p-1")
        assertEquals(listOf("p-2"), store.projects().map { it.id })
        assertEquals(listOf("a-3"), store.assets().map { it.id })
    }

    @Test
    fun bridgedStoreKeepsJsonRecordsAndSkipsDamagedOnes() = runBlocking {
        val rows = HashMap<String, LinkedHashMap<String, String>>()
        val bridge = object : StudioStoreBridge {
            override fun records(kind: String) = rows[kind]?.values?.toList().orEmpty()
            override fun put(kind: String, id: String, json: String) {
                rows.getOrPut(kind) { LinkedHashMap() }[id] = json
            }
            override fun remove(kind: String, id: String) {
                rows[kind]?.remove(id)
            }
        }
        val store = BridgedStudioStore(bridge)
        store.putProject(StudioProject("p-1", "One", null, 1, 1))
        store.putAsset(StudioAsset("a-1", "p-1", AssetType.IMAGE, recipe, 1))
        store.putSnippet(PromptLibrary.builtInStyles.first())
        bridge.put(StudioStoreBridge.ASSETS, "broken", "{not json")
        assertEquals(listOf("a-1"), store.assets().map { it.id })
        assertEquals("style-photo", store.snippets().single().id)
        store.deleteProject("p-1")
        assertTrue(store.assets().isEmpty())
        assertTrue(store.projects().isEmpty())
    }

    @Test
    fun mimeTypesMapToTypesAndExtensions() {
        assertEquals("image/png" to AssetType.IMAGE, StudioCodec.typeOf("ComfyUI_00001_.png"))
        assertEquals("audio/flac" to AssetType.AUDIO, StudioCodec.typeOf("a.FLAC"))
        assertEquals("video/mp4" to AssetType.VIDEO, StudioCodec.typeOf("clip.mp4"))
        assertEquals("webm", StudioCodec.extension("video/webm"))
        assertEquals("jpg", StudioCodec.extension("image/jpeg; q=1"))
    }

    @Test
    fun idsHavePrefixesAndAreDistinct() {
        val ids = (1..200).map { StudioIds.new("a") }
        assertEquals(200, ids.toSet().size)
        assertTrue(ids.all { Regex("a-[0-9a-f]{12}").matches(it) })
    }

    @Test
    fun backupManifestCarriesStudioAdditively() {
        val asset = StudioAsset("a-1", "p-1", AssetType.VIDEO, recipe, 5, "video/mp4")
        val workflow = WorkflowImport.create("LTX", fixture("ltxv_t2v_api.json"), id = "w-1")
        val manifest = BackupManifest(
            studioProjects = listOf(StudioProject("p-1", "One", "a-1", 1, 2)),
            studioAssets = listOf(asset),
            studioWorkflows = listOf(workflow),
            studioSnippets = PromptLibrary.builtInStyles.take(1),
        )
        val back = BackupManifest.decode(BackupManifest.encode(manifest))
        assertEquals(asset, back.studioAssets.single())
        assertEquals(workflow, back.studioWorkflows.single())
        assertEquals("studio/a-1.mp4", BackupManifest.studioEntry(asset))
        // An export made before the Studio imports with none.
        val old = BackupManifest.decode("""{"settings":{},"providers":[],"conversations":[],"gallery":[]}""".encodeToByteArray())
        assertTrue(old.studioAssets.isEmpty() && old.studioProjects.isEmpty() && old.studioWorkflows.isEmpty())
    }
}

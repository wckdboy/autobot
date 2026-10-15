package dev.wckdboy.autobot.shared.studio

import dev.wckdboy.autobot.shared.BinaryHttpTransport
import dev.wckdboy.autobot.shared.Cancellable
import dev.wckdboy.autobot.shared.HttpTransport
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Swift-facing Studio: records and jobs through the host bridge, bytes through the host files. */
class StudioBridgeTest {
    private class Rows : StudioStoreBridge {
        val kinds = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
        override fun records(kind: String): List<String> = kinds[kind]?.values?.toList().orEmpty()
        override fun put(kind: String, id: String, json: String) {
            kinds.getOrPut(kind) { ConcurrentHashMap() }[id] = json
        }

        override fun remove(kind: String, id: String) {
            kinds[kind]?.remove(id)
        }
    }

    private class Files : StudioFiles {
        val bytes = ConcurrentHashMap<String, ByteArray>()
        override fun write(assetId: String, mimeType: String, bytes: ByteArray) {
            this.bytes[assetId] = bytes
        }

        override fun read(assetId: String): ByteArray? = bytes[assetId]
    }

    /** A host engine answering on its own thread, like a Swift engine. */
    private class Host : HostStudioEngine {
        override val capabilities = EngineCapabilities(EngineKinds.AUDIO, null, "This phone", setOf(AssetType.AUDIO), true)

        override fun run(jobJson: String, sink: HostEngineSink): Cancellable {
            val job = StudioCodec.decodeJob(jobJson)
            Thread {
                sink.onProgress(1, 2)
                sink.onOutput(EngineOutput(AssetType.AUDIO, "audio/wav", byteArrayOf(7, 7, job.recipe.seed!!.toByte()), durationMs = 500))
                sink.onComplete()
            }.start()
            return Cancellable { }
        }
    }

    private val http = object : HttpTransport {
        override fun send(request: dev.wckdboy.autobot.shared.HttpRequestSpec, sink: dev.wckdboy.autobot.shared.HttpSink): Cancellable = Cancellable { }
    }
    private val binary = object : BinaryHttpTransport {
        override fun exchange(request: dev.wckdboy.autobot.shared.HttpRequestSpec, body: ByteArray?, sink: dev.wckdboy.autobot.shared.BinarySink): Cancellable {
            sink.onFailure(true, "offline")
            return Cancellable { }
        }
    }

    @Test
    fun hostEngineOutputsLandAsAssetsWithEncryptedFilesAndJobRows() {
        val rows = Rows()
        val files = Files()
        val bridge = StudioBridge(rows, files, http, binary, null)
        bridge.register(Host())
        rows.put(StudioStoreBridge.PROJECTS, "p-1", StudioCodec.encodeProject(StudioProject("p-1", "Sketches", null, 1, 1)))
        val job = bridge.queue.submit("p-1", Recipe(EngineKinds.AUDIO, seed = 5, output = AssetType.AUDIO), "clip", null)
        val latch = CountDownLatch(1)
        var done: StudioJob? = null
        bridge.awaitJob(job.id) { done = it; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.DONE, done!!.state)
        val assetId = done!!.outputs.single()
        val asset = StudioCodec.decodeAsset(rows.kinds[StudioStoreBridge.ASSETS]!![assetId]!!)
        assertEquals(AssetType.AUDIO, asset.type)
        assertEquals(500L, asset.durationMs)
        assertArrayEquals(byteArrayOf(7, 7, 5), files.bytes[assetId])
        // The job row is kept (restored at the next launch) and the project was touched.
        Thread.sleep(100)
        assertNotNull(rows.kinds[StudioBridge.JOBS]?.get(job.id))
        bridge.close()
    }

    @Test
    fun importRefusesTheEditorFormatAndDetectsParamsWithoutAServer() {
        val bridge = StudioBridge(Rows(), Files(), http, binary, null)
        val latch = CountDownLatch(2)
        var refused: String? = null
        var imported: StudioWorkflow? = null
        var checked = true
        bridge.importWorkflow("ui", """{"nodes": [], "links": []}""", null) { _, _, _, error -> refused = error; latch.countDown() }
        bridge.importWorkflow("Portrait", fixture("sd15_txt2img_api.json"), null) { w, _, c, _ -> imported = w; checked = c; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(WorkflowImport.UI_FORMAT_MESSAGE, refused)
        assertNotNull(imported!!.param("prompt"))
        assertEquals(false, checked)
        bridge.close()
    }
}

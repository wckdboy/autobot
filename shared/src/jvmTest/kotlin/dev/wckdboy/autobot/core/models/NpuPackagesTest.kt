package dev.wckdboy.autobot.core.models

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuPackagesTest {

    private fun zipOf(vararg files: Pair<String, ByteArray>, stored: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (name, data) ->
                val e = ZipEntry(name)
                if (name in stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun remoteZipDirectoryAndDataOffsets() = runBlocking {
        val unet = ByteArray(50_000) { (it % 7).toByte() }
        val zip = zipOf("output/qnn_models_sdxl_8gen3/unet.bin" to unet, "output/qnn_models_sdxl_8gen3/SDXL" to ByteArray(0), stored = setOf("output/qnn_models_sdxl_8gen3/SDXL"))
        val tailStart = (zip.size - 4096).coerceAtLeast(0).toLong()
        val dir = RemoteZip.locateDirectory(zip.copyOfRange(tailStart.toInt(), zip.size), tailStart) { from, to -> zip.copyOfRange(from.toInt(), to.toInt() + 1) }
        val entries = RemoteZip.parseCentralDirectory(zip.copyOfRange(dir.offset.toInt(), (dir.offset + dir.size).toInt()), dir.count)
        assertEquals(listOf("unet.bin", "SDXL"), entries.map { it.baseName })
        val e = entries.first()
        assertEquals(8, e.method)
        assertEquals(unet.size.toLong(), e.size)
        assertEquals(CRC32().apply { update(unet) }.value, e.crc32)
        val start = e.localHeaderOffset + RemoteZip.dataStart(zip.copyOfRange(e.localHeaderOffset.toInt(), e.localHeaderOffset.toInt() + 30))
        val inflated = java.util.zip.InflaterInputStream(zip.copyOfRange(start.toInt(), (start + e.compressedSize).toInt()).inputStream(), java.util.zip.Inflater(true)).readBytes()
        assertTrue(inflated.contentEquals(unet))
    }

    private fun entry(name: String, size: Long = 10) = RemoteZipEntry(name, size, size, 0, if (size == 0L) 0 else 8, 1) to ZipEntryRef(name, 100, size, 8, 1)

    @Test
    fun sdxlDmd2ZipPlan() {
        val dir = "output/qnn_models_sdxl_8gen3/"
        val names = listOf("tokenizer.json", "unet.bin", "vae_encoder.bin", "vae_decoder.bin", "clip.mnn", "pos_emb.bin", "token_emb.bin",
            "clip_2.mnn.weight", "clip_2.mnn", "pos_emb_2.bin", "token_emb_2.bin", "config.json")
        val entries = names.map { entry(dir + it) } + entry(dir + "SDXL", 0)
        val plan = LocalDream.planFromZip("xororz/sdxl-qnn", "juggernaut_dmd2_qnn2.28_8gen3.zip", "https://hf/zip", entries)
        assertEquals(EngineKind.NPU, plan.engine)
        assertEquals(ModelFormat.QNN, plan.format)
        val npu = plan.manifest.npu!!
        assertEquals("sdxl", npu.arch)
        assertEquals("qnn", npu.runtime)
        assertEquals("v75", npu.htpArch)
        assertFalse(npu.vpred)
        val roles = plan.manifest.files.associate { it.name to it.role }
        assertEquals(FileRole.TEXT_ENCODER_2, roles["clip_2.mnn"])
        assertEquals(FileRole.WEIGHTS, roles["clip_2.mnn.weight"])
        assertEquals(FileRole.UNET, roles["unet.bin"])
        assertNull(roles["config.json"])
        assertTrue(plan.manifest.files.all { it.keepName && it.zipEntry != null })
        assertEquals(8, plan.manifest.recommended?.steps)
        assertEquals(1f, plan.manifest.recommended?.cfg)
        assertEquals(1024, plan.manifest.recommended?.width)
    }

    @Test
    fun animaAndVpredPackages() {
        val anima = LocalDream.planFromZip("xororz/anima-qnn", "a_qnn2.28_8gen3.zip", "u",
            listOf(entry("o/unet_part1.bin"), entry("o/clip.bin"), entry("o/ANIMA", 0)))
        assertEquals(EngineKind.NONE, anima.engine)
        assertTrue(anima.manifest.files.isEmpty())
        val vpred = LocalDream.planFromZip("xororz/sdxl-qnn", "noobai_vpred_qnn2.28_8gen3.zip", "u",
            listOf("unet.bin", "clip.mnn", "vae_decoder.bin").map { entry("o/$it") } + entry("o/SDXL", 0) + entry("o/V_PRED", 0))
        assertTrue(vpred.manifest.npu!!.vpred)
        val sd15 = LocalDream.planFromZip("xororz/sd-qnn", "AnythingV5_qnn2.28_8gen2.zip", "u",
            listOf("unet.bin", "clip_v2.mnn", "clip.mnn", "vae_decoder.bin", "768.patch").map { entry("output_512/q/$it") })
        assertEquals("sd15", sd15.manifest.npu!!.arch)
        assertEquals(listOf("unet.bin", "clip_v2.mnn", "vae_decoder.bin"), sd15.manifest.files.map { it.name })
        assertEquals(512, sd15.manifest.recommended?.width)
    }

    @Test
    fun chipsForHexagonGenerations() {
        // Local Dream's table: every v73+ phone runs the 8gen2 (v73) builds; SDXL ships 8gen3 (v75) only.
        assertEquals(listOf("8gen2", "8gen3"), LocalDream.chipOrder("v81"))
        assertEquals(listOf("8gen2", "8gen3"), LocalDream.chipOrder("v75"))
        assertEquals(listOf("8gen2"), LocalDream.chipOrder("v73"))
        assertEquals(listOf("8gen1", "min"), LocalDream.chipOrder("v69"))
        assertEquals(listOf("min"), LocalDream.chipOrder(null))
        assertEquals("v81", LocalDream.archOfSoc("SM8850"))
        assertEquals("v81", LocalDream.archOfSoc("SM8850P"))
        assertEquals("8gen3", LocalDream.chipOf("juggernaut_dmd2_qnn2.28_8gen3.zip"))
        assertEquals("min", LocalDream.chipOf("AnythingV5_qnn2.28_min.zip"))
        val sdxl = NpuCatalog.image.first { it.sdxl }
        assertEquals("juggernaut_dmd2_qnn2.28_8gen3.zip", sdxl.fileFor("v81"))
        assertNull(sdxl.fileFor("v73"))
        assertEquals("AnythingV5_qnn2.28_8gen2.zip", NpuCatalog.image.first { it.id == "npu:sd15:AnythingV5" }.fileFor("v79"))
        assertEquals("AnythingV5_qnn2.28_8gen2.zip", NpuCatalog.image.first { it.id == "npu:sd15:AnythingV5" }.fileFor("v81"))
        // Upscalers: the 8gen2 build, as Local Dream installs on an 8 Elite Gen 5, never the v79 one.
        assertEquals("4x_UltraSharpV2_Lite/upscaler_8gen2.bin", NpuCatalog.upscalers.first().fileFor("v81"))
    }

    @Test
    fun snapdragon8EliteGen5RunsOnlyBuildsItCanLoad() {
        // SM8850 (OnePlus 15): Hexagon v81.
        val arch = LocalDream.archOfSoc("SM8850")
        assertTrue(LocalDream.runsOn("8gen2", arch))
        assertTrue(LocalDream.runsOn("8gen3", arch))
        assertFalse(LocalDream.runsOn("8gen4", arch))
        assertFalse(LocalDream.runsOn("8gen1", arch))
        assertNull(LocalDream.incompatibility("v73", arch))
        assertNull(LocalDream.incompatibility("v75", arch))
        val v79 = LocalDream.incompatibility("v79", arch)
        assertNotNull(v79)
        assertTrue(v79!!, "v81" in v79 && "8gen2" in v79)
        // Unknown on either side: no claim either way.
        assertNull(LocalDream.incompatibility(null, arch))
        assertNull(LocalDream.incompatibility("v79", null))
        // Older phones never get a newer generation's binaries.
        assertFalse(LocalDream.runsOn("8gen3", "v73"))
        assertNotNull(LocalDream.incompatibility("v75", "v73"))
    }
}

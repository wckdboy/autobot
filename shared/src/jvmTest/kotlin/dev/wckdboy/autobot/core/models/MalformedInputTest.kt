package dev.wckdboy.autobot.core.models

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server-supplied archives and device numbers that do not add up must fail typed, not crash. */
class MalformedInputTest {

    private fun zip(vararg names: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            names.forEach { name ->
                z.putNextEntry(ZipEntry(name))
                z.write(ByteArray(100) { it.toByte() })
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun truncatedCentralDirectoryIsATypedError() {
        val bytes = zip("pkg/a.bin", "pkg/b.bin", "pkg/" + "long-name-".repeat(20) + ".bin")
        val dir = runBlocking { RemoteZip.locateDirectory(bytes, 0) { from, to -> bytes.copyOfRange(from.toInt(), to.toInt() + 1) } }
        val cd = bytes.copyOfRange(dir.offset.toInt(), (dir.offset + dir.size).toInt())
        assertEquals(3, RemoteZip.parseCentralDirectory(cd, dir.count).size)
        // Every cut of the directory, and counts that do not match it, fail as DownloadException.
        for (cut in 0 until cd.size) {
            for (count in listOf(dir.count, dir.count + 1, 1_000_000L, -1L, Long.MAX_VALUE)) {
                val error = runCatching { RemoteZip.parseCentralDirectory(cd.copyOf(cut), count) }.exceptionOrNull() ?: continue
                assertTrue("cut $cut count $count: $error", error is DownloadException && !error.retryable)
            }
        }
    }

    @Test
    fun truncatedArchiveListsAsATypedError() = runBlocking {
        val bytes = zip("pkg/a.bin")
        for (cut in listOf(10, 30, bytes.size - 40, bytes.size - 10)) {
            val archive = bytes.copyOf(cut)
            val zip = RemoteZip { from, to ->
                val end = minOf(to.toInt() + 1, archive.size)
                (if (from.toInt() < end) archive.copyOfRange(from.toInt(), end) else ByteArray(0)) to archive.size.toLong()
            }
            val error = runCatching { zip.list() }.exceptionOrNull()
            assertTrue("cut $cut: $error", error == null || error is DownloadException)
        }
    }

    @Test
    fun tooBigCountsTheKvCacheAndSuggestsAContext() {
        val phone = DeviceCapabilities(AppPlatform.ANDROID, totalRamBytes = 12_000_000_000, freeStorageBytes = 100_000_000_000)
        assertFalse(phone.fit(ModelKind.CHAT, 2_500_000_000, EngineKind.LLAMA) == Fit.TOO_BIG)
        // Same weights, a 32k context: the KV cache no longer fits half the RAM.
        assertEquals(Fit.TOO_BIG, phone.fit(ModelKind.CHAT, 2_500_000_000, EngineKind.LLAMA, contextTokens = 32_768))
        val context = phone.recommendedContext(2_500_000_000)
        assertTrue(context in DeviceCapabilities.MIN_CONTEXT until DeviceCapabilities.MAX_CONTEXT)
        assertTrue(phone.fit(ModelKind.CHAT, 2_500_000_000, EngineKind.LLAMA, context) != Fit.TOO_BIG)
    }
}

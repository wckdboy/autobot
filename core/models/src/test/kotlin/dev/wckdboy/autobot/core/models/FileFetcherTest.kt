package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkPolicy
import dev.wckdboy.autobot.core.security.Secret
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var fetcher: FileFetcher
    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("fetch").toFile()
        // Offline policy: loopback is allowed, which is all the test needs.
        fetcher = FileFetcher(HttpClientFactory(NetworkPolicy(), AuditLog(capacity = 50, clock = { 0L })))
    }

    @After
    fun tearDown() = server.close()

    private fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build().toString()

    private fun file(sha256: String? = null, auth: AuthHost? = null) =
        ModelFile(FileRole.MODEL, "m.gguf", url("/resolve/main/m.gguf"), 0, sha256, auth)

    @Test
    fun followsRedirectAdoptsHubHashAndVerifies() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(302)
                .setHeader("Location", "/cdn/blob?sig=1")
                .setHeader("X-Linked-ETag", "\"$sha\"")
                .setHeader("X-Linked-Size", content.size.toString())
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(content)).build())
        val target = File(dir, "out.gguf")

        val result = fetcher.fetch(file(auth = AuthHost.HUGGING_FACE), target, Secret("hf_secret")) { _, _ -> }

        assertEquals(sha, result.sha256)
        assertEquals(content.size.toLong(), result.sizeBytes)
        assertTrue(target.readBytes().contentEquals(content))
        assertFalse(File(target.path + ".part").exists())
        // 127.0.0.1 is not huggingface.co: the token must never be sent there.
        assertNull(server.takeRequest().headers["Authorization"])
        val second = server.takeRequest()
        assertEquals("/cdn/blob?sig=1", second.target)
        assertNull(second.headers["Authorization"])
    }

    @Test
    fun resumesWithRangeFromPartialFile() = runBlocking {
        val target = File(dir, "out.gguf")
        File(target.path + ".part").writeBytes(content.copyOfRange(0, 100_000))
        server.enqueue(
            MockResponse.Builder().code(206)
                .setHeader("Content-Range", "bytes 100000-${content.size - 1}/${content.size}")
                .body(Buffer().write(content.copyOfRange(100_000, content.size)))
                .build(),
        )

        val result = fetcher.fetch(file(sha256 = sha).copy(sizeBytes = content.size.toLong()), target, null) { _, _ -> }

        assertEquals("bytes=100000-", server.takeRequest().headers["Range"])
        assertEquals(sha, result.sha256)
        assertTrue(target.readBytes().contentEquals(content))
    }

    @Test
    fun checksumMismatchDeletesTheDownload() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(content)).build())
        val target = File(dir, "out.gguf")
        val error = runCatching { fetcher.fetch(file(sha256 = "0".repeat(64)), target, null) { _, _ -> } }.exceptionOrNull()
        assertTrue(error is DownloadException)
        assertTrue(error!!.message!!.contains("Checksum mismatch"))
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun gatedRepoIsNotRetryable() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).setHeader("X-Error-Code", "GatedRepo").body("restricted").build())
        val error = runCatching { fetcher.fetch(file(), File(dir, "x"), null) { _, _ -> } }.exceptionOrNull() as DownloadException
        assertFalse(error.retryable)
        assertTrue(error.message!!.contains("Gated"))
    }

    @Test
    fun zipEntryIsFetchedByRangeInflatedAndCrcChecked() = runBlocking {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("pkg/unet.bin"))
            z.write(content)
            z.closeEntry()
        }
        val zip = out.toByteArray()
        val cd = RemoteZip.locateDirectory(zip, 0) { from, to -> zip.copyOfRange(from.toInt(), to.toInt() + 1) }
        val entry = RemoteZip.parseCentralDirectory(zip.copyOfRange(cd.offset.toInt(), (cd.offset + cd.size).toInt()), cd.count).single()
        val start = entry.localHeaderOffset + RemoteZip.dataStart(zip.copyOfRange(0, 30))
        val compressed = zip.copyOfRange(start.toInt(), (start + entry.compressedSize).toInt())
        // First attempt is cut short; the second resumes from where the part file ends.
        val half = compressed.size / 2
        server.enqueue(MockResponse.Builder().code(206).body(Buffer().write(compressed.copyOfRange(0, half))).build())
        server.enqueue(MockResponse.Builder().code(206).body(Buffer().write(compressed.copyOfRange(half, compressed.size))).build())
        val ref = ZipEntryRef(entry.name, start, entry.compressedSize, entry.method, entry.crc32)
        val file = ModelFile(FileRole.UNET, "unet.bin", url("/pkg.zip"), entry.size, zipEntry = ref, keepName = true)
        val target = File(dir, "unet.bin")

        val first = runCatching { fetcher.fetch(file, target, null) { _, _ -> } }.exceptionOrNull()
        assertTrue(first is DownloadException)
        val result = fetcher.fetch(file, target, null) { _, _ -> }

        assertEquals("bytes=$start-${start + entry.compressedSize - 1}", server.takeRequest().headers["Range"])
        assertEquals("bytes=${start + half}-${start + entry.compressedSize - 1}", server.takeRequest().headers["Range"])
        assertTrue(target.readBytes().contentEquals(content))
        assertEquals(content.size.toLong(), result.sizeBytes)
        assertFalse(File(target.path + ".z.part").exists())
    }

    @Test
    fun hubHostsAreExact() {
        assertTrue(FileFetcher.isHubHost("huggingface.co", AuthHost.HUGGING_FACE))
        assertFalse(FileFetcher.isHubHost("cdn-lfs.huggingface.co", AuthHost.HUGGING_FACE))
        assertFalse(FileFetcher.isHubHost("evilhuggingface.co", AuthHost.HUGGING_FACE))
        assertTrue(FileFetcher.isHubHost("civitai.red", AuthHost.CIVITAI))
        assertFalse(FileFetcher.isHubHost("civitai-delivery-worker-prod.r2.cloudflarestorage.com", AuthHost.CIVITAI))
    }
}

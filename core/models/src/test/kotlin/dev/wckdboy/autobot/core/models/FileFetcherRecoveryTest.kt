package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.core.network.AuditLog
import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.network.NetworkBlockedException
import dev.wckdboy.autobot.core.network.NetworkMode
import dev.wckdboy.autobot.core.network.NetworkPolicy
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Resume and recovery of one file against a scripted server (no real network): a server that
 * ignores `Range`, a file that changed on the server between two attempts, a truncated body, a
 * stalled connection that a pause must not wait out, the kill switch, and the Hub's refusals.
 * Real sockets need real time, so every test runs under a wall-clock bound.
 */
class FileFetcherRecoveryTest {

    @get:Rule
    val timeout: Timeout = Timeout(60, TimeUnit.SECONDS)

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val policy = NetworkPolicy()
    private val audit = AuditLog(capacity = 50, clock = { 0L })
    private lateinit var fetcher: FileFetcher

    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private val changed = ByteArray(280_000) { (it % 13 + 7).toByte() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = Files.createTempDirectory("fetch-recovery").toFile()
        // Offline policy: loopback is allowed, which is all a scripted server needs.
        fetcher = FileFetcher(HttpClientFactory(policy, audit))
    }

    @After
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun url(path: String) = server.url(path).newBuilder().host("127.0.0.1").build().toString()

    private fun file(sha256: String? = null, size: Long = 0, auth: AuthHost? = null) =
        ModelFile(FileRole.MODEL, "m.gguf", url("/resolve/main/m.gguf"), size, sha256, auth)

    private fun bounded(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(30.seconds) { block() } }

    private fun part(target: File) = File(target.path + ".part")
    private fun validator(target: File) = File(target.path + ".part" + FileFetcher.VALIDATOR)

    @Test
    fun serverIgnoringRangeReplacesThePartInsteadOfAppending() = bounded {
        val target = File(dir, "out.gguf")
        // What is on disk does not even match the file: appending a full body would corrupt it.
        part(target).writeBytes(ByteArray(100_000) { 1 })
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(content)).build())

        val result = fetcher.fetch(file(sha(content), content.size.toLong()), target, null) { _, _ -> }

        assertEquals("bytes=100000-", server.takeRequest().headers["Range"])
        assertEquals(sha(content), result.sha256)
        assertTrue(target.readBytes().contentEquals(content))
        assertFalse(part(target).exists())
    }

    @Test
    fun fileChangedOnTheServerRestartsCleanlyWithIfRange() = bounded {
        val target = File(dir, "out.gguf")
        var current = content
        var etag = "\"v1\""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.headers["Range"]
                val ifRange = request.headers["If-Range"]
                // RFC 9110: a stale validator gets the whole (new) representation.
                if (range != null && ifRange == etag) {
                    val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    return MockResponse.Builder().code(206).setHeader("ETag", etag)
                        .setHeader("Content-Range", "bytes $from-${current.size - 1}/${current.size}")
                        .body(Buffer().write(current.copyOfRange(from, current.size))).build()
                }
                return MockResponse.Builder().code(200).setHeader("ETag", etag).body(Buffer().write(current)).build()
            }
        }
        // First attempt: v1 starts and the connection drops part-way.
        server.dispatcher = DropFirst(server.dispatcher)
        val first = runCatching { fetcher.fetch(file(), target, null) { _, _ -> } }
        assertTrue(first.isFailure)
        val kept = part(target).length()
        assertTrue("some of v1 kept ($kept)", kept in 1 until content.size)
        assertEquals("\"v1\"", validator(target).readText())
        server.takeRequest()

        // The file is replaced on the server before the resume.
        current = changed
        etag = "\"v2\""
        val result = fetcher.fetch(file(), target, null) { _, _ -> }

        val resumed = server.takeRequest()
        assertEquals("bytes=$kept-", resumed.headers["Range"])
        assertEquals("\"v1\"", resumed.headers["If-Range"])
        assertTrue("the new file, not v1's head spliced onto v2", target.readBytes().contentEquals(changed))
        assertEquals(sha(changed), result.sha256)
        assertFalse(validator(target).exists())
        assertFalse(part(target).exists())
    }

    @Test
    fun unchangedFileResumesWithIfRange() = bounded {
        val target = File(dir, "out.gguf")
        part(target).writeBytes(content.copyOfRange(0, 120_000))
        validator(target).writeText("\"same\"")
        server.enqueue(
            MockResponse.Builder().code(206).setHeader("ETag", "\"same\"")
                .setHeader("Content-Range", "bytes 120000-${content.size - 1}/${content.size}")
                .body(Buffer().write(content.copyOfRange(120_000, content.size))).build(),
        )

        val result = fetcher.fetch(file(sha(content), content.size.toLong()), target, null) { _, _ -> }

        val request = server.takeRequest()
        assertEquals("\"same\"", request.headers["If-Range"])
        assertEquals("bytes=120000-", request.headers["Range"])
        assertEquals(sha(content), result.sha256)
        assertFalse(validator(target).exists())
    }

    @Test
    fun weakEtagIsNeverUsedForIfRangeButLastModifiedIs() = bounded {
        val target = File(dir, "out.gguf")
        server.enqueue(
            MockResponse.Builder().code(200).setHeader("ETag", "W/\"weak\"").setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
                .body(Buffer().write(content)).onResponseBody(SocketEffect.CloseSocket()).build(),
        )
        runCatching { fetcher.fetch(file(), target, null) { _, _ -> } }
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", validator(target).readText())
    }

    @Test
    fun truncatedBodyKeepsThePartAndTheNextAttemptResumes() = bounded {
        val target = File(dir, "out.gguf")
        // The server ends the body cleanly but short of the size the Hub announced.
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(content.copyOfRange(0, 100_000))).build())
        val error = runCatching { fetcher.fetch(file(sha(content), content.size.toLong()), target, null) { _, _ -> } }.exceptionOrNull()
        assertTrue(error is DownloadException)
        assertTrue((error as DownloadException).retryable)
        assertTrue(error.message!!, error.message!!.startsWith("Incomplete download"))
        assertEquals(100_000L, part(target).length())
        assertFalse(target.exists())
        server.takeRequest()

        server.enqueue(
            MockResponse.Builder().code(206).setHeader("Content-Range", "bytes 100000-${content.size - 1}/${content.size}")
                .body(Buffer().write(content.copyOfRange(100_000, content.size))).build(),
        )
        val result = fetcher.fetch(file(sha(content), content.size.toLong()), target, null) { _, _ -> }
        assertEquals("bytes=100000-", server.takeRequest().headers["Range"])
        assertEquals(sha(content), result.sha256)
    }

    @Test
    fun checksumMismatchAfterAResumeDeletesEverything() = bounded {
        val target = File(dir, "out.gguf")
        part(target).writeBytes(ByteArray(100_000) { 9 })
        validator(target).writeText("\"v\"")
        server.enqueue(
            MockResponse.Builder().code(206).setHeader("Content-Range", "bytes 100000-${content.size - 1}/${content.size}")
                .body(Buffer().write(content.copyOfRange(100_000, content.size))).build(),
        )
        val error = runCatching { fetcher.fetch(file(sha(content), content.size.toLong()), target, null) { _, _ -> } }.exceptionOrNull()
        assertTrue(error is DownloadException)
        assertTrue(error!!.message!!, error.message!!.contains("Checksum mismatch") && error.message!!.contains("deleted"))
        assertFalse(part(target).exists())
        assertFalse(target.exists())
    }

    @Test
    fun cancellingAStalledTransferReturnsAtOnce() = bounded {
        val target = File(dir, "out.gguf")
        // Headers and part of the body arrive, then the connection goes silent (the read timeout is minutes).
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(content)).onResponseBody(SocketEffect.Stall).build())
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            fetcher.fetch(file(), target, null) { written, _ -> if (written > 0) started.complete(Unit) }
        }
        started.await()
        val began = System.nanoTime()
        withTimeout(5.seconds) { job.cancelAndJoin() }
        assertTrue("cancel took ${(System.nanoTime() - began) / 1_000_000} ms", System.nanoTime() - began < TimeUnit.SECONDS.toNanos(5))
        // The bytes that arrived stay for a resume.
        assertTrue(part(target).length() > 0)
        assertFalse(target.exists())
    }

    @Test
    fun killSwitchBlocksBeforeAnyRequestAndIsAudited() = bounded {
        policy.setAllowLoopbackWhenOffline(false)
        val error = runCatching {
            fetcher.fetch(file(), File(dir, "x.gguf"), null) { _, _ -> }
        }.exceptionOrNull()
        assertTrue("$error", error is NetworkBlockedException)
        assertEquals(0, server.requestCount)
        val entry = audit.entries.value.single()
        assertTrue(entry.blocked)
        assertEquals("models:download", entry.tag)
        // A public host is refused the same way while the network is off: nothing leaves the phone.
        policy.setMode(NetworkMode.Offline)
        val hub = ModelFile(FileRole.MODEL, "m.gguf", "https://huggingface.co/a/b/resolve/main/m.gguf", auth = AuthHost.HUGGING_FACE)
        assertTrue(runCatching { fetcher.fetch(hub, File(dir, "y.gguf"), null) { _, _ -> } }.exceptionOrNull() is NetworkBlockedException)
        assertTrue(audit.entries.value.all { it.blocked })
    }

    @Test
    fun gatedAndPrivateReposSayWhatToDo() = bounded {
        server.enqueue(MockResponse.Builder().code(403).setHeader("X-Error-Code", "GatedRepo").body("gated").build())
        val gated = runCatching { fetcher.fetch(file(auth = AuthHost.HUGGING_FACE), File(dir, "g"), null) { _, _ -> } }.exceptionOrNull() as DownloadException
        assertFalse(gated.retryable)
        assertTrue(gated.message!!, gated.message!!.contains("accept its terms") && gated.message!!.contains("Remote → Accounts"))

        server.enqueue(MockResponse.Builder().code(401).body("unauthorized").build())
        val private = runCatching { fetcher.fetch(file(auth = AuthHost.HUGGING_FACE), File(dir, "p"), null) { _, _ -> } }.exceptionOrNull() as DownloadException
        assertFalse(private.retryable)
        assertTrue(private.message!!, private.message!!.contains("Remote → Accounts"))
        assertFalse(File(dir, "g.part").exists())
    }

    @Test
    fun redirectToTheCdnResumesOnTheCdnWithoutTheToken() = bounded {
        val target = File(dir, "out.gguf")
        part(target).writeBytes(content.copyOfRange(0, 50_000))
        server.enqueue(
            MockResponse.Builder().code(302).setHeader("Location", url("/cdn/blob?sig=1"))
                .setHeader("X-Linked-ETag", "\"${sha(content)}\"").setHeader("X-Linked-Size", content.size.toString()).build(),
        )
        server.enqueue(
            MockResponse.Builder().code(206).setHeader("Content-Range", "bytes 50000-${content.size - 1}/${content.size}")
                .body(Buffer().write(content.copyOfRange(50_000, content.size))).build(),
        )

        val result = fetcher.fetch(file(auth = AuthHost.HUGGING_FACE), target, dev.wckdboy.autobot.core.security.Secret("hf_x")) { _, _ -> }

        val hub = server.takeRequest()
        val cdn = server.takeRequest()
        assertEquals("bytes=50000-", hub.headers["Range"])
        assertEquals("/cdn/blob?sig=1", cdn.target)
        assertEquals("bytes=50000-", cdn.headers["Range"])
        assertNull(cdn.headers["Authorization"])
        assertEquals(sha(content), result.sha256)
        assertTrue(target.readBytes().contentEquals(content))
    }

    /** Wraps [next] so its first answer is cut off mid-body (the connection drops). */
    private class DropFirst(private val next: Dispatcher) : Dispatcher() {
        private var dropped = false

        override fun dispatch(request: RecordedRequest): MockResponse {
            val response = next.dispatch(request)
            if (dropped) return response
            dropped = true
            return response.newBuilder().onResponseBody(SocketEffect.CloseSocket()).build()
        }
    }
}

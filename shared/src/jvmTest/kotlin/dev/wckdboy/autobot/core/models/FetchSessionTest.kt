package dev.wckdboy.autobot.core.models

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The download state machine both apps drive (ports of the logic cases of Android's
 * `FileFetcherTest` and iOS's `FileFetcherTests`). [Host] plays the platform: it sends what the
 * session asks for to a scripted server and keeps the part file in memory.
 */
class FetchSessionTest {
    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private val sha = sha256(content)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private class Reply(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0))

    private class Host(private val replies: ArrayDeque<Reply>, var part: ByteArray? = null) {
        val sent = mutableListOf<FetchRequest>()
        var deletedPart = false

        /** Runs [session] like the platform fetchers do; returns the verified file. */
        fun run(session: FetchSession): ModelFile {
            var step = session.begin(part?.size?.toLong() ?: 0)
            while (true) {
                when (val s = step) {
                    FetchStep.Next -> {
                        val have = part?.size?.toLong() ?: 0
                        val request = session.request(have)
                        sent += request
                        val reply = replies.removeFirst()
                        step = when (val next = session.onResponse(reply.status, reply.headers, have, reply.body.size.toLong())) {
                            is FetchStep.Write -> {
                                part = if (next.append) (part ?: ByteArray(0)) + reply.body else reply.body
                                FetchStep.Verify
                            }
                            FetchStep.Reject -> throw session.failure(reply.status, reply.headers, reply.body.decodeToString())
                            else -> next
                        }
                    }
                    FetchStep.Restart -> {
                        part = null
                        deletedPart = true
                        step = FetchStep.Next
                    }
                    is FetchStep.Fail -> throw s.error
                    FetchStep.Verify -> {
                        session.checkComplete(part?.size?.toLong() ?: -1)
                        val bytes = part!!
                        return try {
                            session.verify(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())
                        } catch (e: DownloadException) {
                            part = null
                            deletedPart = true
                            throw e
                        }
                    }
                    else -> error("unexpected $s")
                }
            }
        }
    }

    private fun hub(sha256: String? = null, size: Long = 0) =
        ModelFile(FileRole.MODEL, "m.gguf", "https://huggingface.co/a/b/resolve/main/m.gguf", size, sha256, AuthHost.HUGGING_FACE)

    private inline fun <reified T : Throwable> failure(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun followsRedirectAdoptsHubHashAndVerifies() {
        val host = Host(
            ArrayDeque(
                listOf(
                    Reply(302, mapOf("location" to "https://cdn-lfs.huggingface.co/blob?sig=1", "x-linked-etag" to "\"$sha\"", "X-Linked-Size" to "${content.size}")),
                    Reply(200, body = content),
                ),
            ),
        )
        val session = FetchSession(hub(), "hf_secret")

        val result = host.run(session)

        assertEquals(sha, result.sha256)
        assertEquals(content.size.toLong(), result.sizeBytes)
        assertEquals(sha, session.expectedSha256)
        // The token goes to huggingface.co, never to the CDN the Hub redirects to.
        assertEquals("Bearer hf_secret", host.sent[0].headers["Authorization"])
        assertEquals("https://cdn-lfs.huggingface.co/blob?sig=1", host.sent[1].url)
        assertNull(host.sent[1].headers["Authorization"])
        assertNull(host.sent[0].headers["Range"])
    }

    @Test
    fun tokenNeverReachesOtherHostsEvenOnTheFirstRequest() {
        val local = ModelFile(FileRole.MODEL, "m.gguf", "http://127.0.0.1:8080/resolve/main/m.gguf", auth = AuthHost.HUGGING_FACE)
        assertNull(FetchSession(local, "hf_secret").request(0).headers["Authorization"])
        val civitai = ModelFile(FileRole.MODEL, "m.safetensors", "https://civitai.red/api/download/models/9", auth = AuthHost.CIVITAI)
        assertEquals("Bearer key", FetchSession(civitai, "key").request(0).headers["Authorization"])
        assertNull(FetchSession(civitai, "  ").request(0).headers["Authorization"])
        assertNull(FetchSession(civitai.copy(auth = null), "key").request(0).headers["Authorization"])
    }

    @Test
    fun relativeRedirectsResolveAgainstTheRequest() {
        val host = Host(ArrayDeque(listOf(Reply(302, mapOf("Location" to "/cdn/blob?sig=1")), Reply(200, body = content))))
        val file = ModelFile(FileRole.MODEL, "m.gguf", "http://127.0.0.1:8080/resolve/main/m.gguf")
        host.run(FetchSession(file, null))
        assertEquals("http://127.0.0.1:8080/cdn/blob?sig=1", host.sent[1].url)
        assertEquals("https://h.example/a/c", FetchRules.resolve("https://h.example/a/b?x=1", "c"))
        assertEquals("https://other.example/x", FetchRules.resolve("https://h.example/a", "//other.example/x"))
        assertNull(FetchRules.resolve("https://h.example/a", "ftp://h.example/x"))
    }

    @Test
    fun redirectWithoutLocationFails() {
        val host = Host(ArrayDeque(listOf(Reply(302))))
        val error = failure<DownloadException> { host.run(FetchSession(hub(), null)) }
        assertEquals("Redirect without location", error.message)
        assertTrue(error.retryable)
    }

    @Test
    fun tooManyRedirectsStop() {
        val loop = Reply(302, mapOf("Location" to "https://huggingface.co/a/b/resolve/main/m.gguf"))
        val host = Host(ArrayDeque(List(20) { loop }))
        val error = failure<DownloadException> { host.run(FetchSession(hub(), null)) }
        assertEquals("Too many redirects", error.message)
        assertEquals(FetchRules.MAX_HOPS + 1, host.sent.size)
    }

    @Test
    fun resumesWithRangeFromPartialFile() {
        val host = Host(
            ArrayDeque(listOf(Reply(206, mapOf("Content-Range" to "bytes 100000-${content.size - 1}/${content.size}"), content.copyOfRange(100_000, content.size)))),
            part = content.copyOfRange(0, 100_000),
        )
        val result = host.run(FetchSession(hub(sha256 = sha, size = content.size.toLong()), null))
        assertEquals("bytes=100000-", host.sent.single().headers["Range"])
        assertEquals(sha, result.sha256)
        assertTrue(host.part!!.contentEquals(content))
    }

    @Test
    fun serverIgnoringRangeStartsOver() {
        val host = Host(ArrayDeque(listOf(Reply(200, body = content))), part = ByteArray(10))
        val result = host.run(FetchSession(hub(), null))
        assertEquals(content.size.toLong(), result.sizeBytes)
        assertTrue(host.part!!.contentEquals(content))
    }

    @Test
    fun partialAnswerThatDoesNotContinueThePartStartsOver() {
        // The server answers the resume from the wrong offset: appending would splice two files.
        val session = FetchSession(hub(), null)
        assertEquals(FetchStep.Restart, session.onResponse(206, mapOf("content-range" to "bytes 0-299999/300000"), have = 100_000, contentLength = 300_000))
        val host = Host(
            ArrayDeque(
                listOf(
                    Reply(206, mapOf("Content-Range" to "bytes 50000-${content.size - 1}/${content.size}"), content.copyOfRange(50_000, content.size)),
                    Reply(200, body = content),
                ),
            ),
            part = content.copyOfRange(0, 100_000),
        )
        val result = host.run(FetchSession(hub(sha256 = sha, size = content.size.toLong()), null))
        assertTrue(host.deletedPart)
        assertEquals(listOf("bytes=100000-", null), host.sent.map { it.headers["Range"] })
        assertEquals(sha, result.sha256)
        assertTrue(host.part!!.contentEquals(content))
        // The matching offset appends as before.
        assertTrue((FetchSession(hub(), null).onResponse(206, mapOf("Content-Range" to "bytes 100000-299999/300000"), 100_000, 200_000) as FetchStep.Write).append)
    }

    @Test
    fun unknownSizeIsLearnedFromTheResponse() {
        val session = FetchSession(hub(), null)
        assertEquals(FetchStep.Next, session.begin(1000))
        val step = session.onResponse(206, emptyMap(), have = 1000, contentLength = 500)
        assertTrue(step is FetchStep.Write && step.append)
        assertEquals(1500L, session.expectedSize)
        // An unknown length on an append does not shrink the expectation below what is on disk.
        val unknown = FetchSession(hub(), null)
        unknown.onResponse(206, emptyMap(), have = 1000, contentLength = -1)
        assertEquals(1000L, unknown.expectedSize)
    }

    @Test
    fun rangeNotSatisfiable() {
        // The part file is already complete: verify it.
        val done = FetchSession(hub(size = 10), null)
        assertEquals(FetchStep.Verify, done.onResponse(416, emptyMap(), have = 10, contentLength = 0))
        // It does not match the expected size: start over.
        val wrong = FetchSession(hub(size = 10), null)
        assertEquals(FetchStep.Restart, wrong.onResponse(416, emptyMap(), have = 12, contentLength = 0))
        val host = Host(ArrayDeque(listOf(Reply(416), Reply(200, body = content))), part = ByteArray(400_000))
        host.run(FetchSession(hub(size = content.size.toLong()), null))
        assertTrue(host.deletedPart)
        assertEquals(listOf("bytes=400000-", null), host.sent.map { it.headers["Range"] })
    }

    @Test
    fun checksumMismatchDeletesTheDownload() {
        val host = Host(ArrayDeque(listOf(Reply(200, body = content))))
        val error = failure<DownloadException> { host.run(FetchSession(hub(sha256 = "0".repeat(64)), null)) }
        assertTrue(error.message!!.contains("Checksum mismatch"))
        assertTrue(error.retryable)
        assertTrue(host.deletedPart)
        assertNull(host.part)
    }

    @Test
    fun incompleteDownloadKeepsThePartFile() {
        val session = FetchSession(hub(size = 1_000_000), null)
        val error = failure<DownloadException> { session.checkComplete(300_000) }
        assertEquals("Incomplete download (300 KB of 1 MB); resume to continue", error.message)
        assertEquals("Download produced no data", failure<DownloadException> { session.checkComplete(-1) }.message)
        // Size unknown: anything that arrived is checked by its hash only.
        FetchSession(hub(), null).checkComplete(5)
    }

    @Test
    fun failuresAreTypedForTheUser() {
        val gated = failure<DownloadException> {
            Host(ArrayDeque(listOf(Reply(401, mapOf("X-Error-Code" to "GatedRepo"), "restricted".encodeToByteArray())))).run(FetchSession(hub(), null))
        }
        assertFalse(gated.retryable)
        assertTrue(gated.message!!.contains("Gated"))
        val civitai = ModelFile(FileRole.MODEL, "x", "https://civitai.com/api/download/models/1", auth = AuthHost.CIVITAI)
        assertTrue(FetchSession(civitai, null).failure(401, emptyMap(), "").message!!.startsWith("Civitai requires login"))
        assertEquals("Access denied (HTTP 403): early-access file", FetchSession(civitai, null).failure(403, emptyMap(), "Early Access only").message)
        // A Hub 401/403 without the gated marker (a private repo, a token without read access) says what to do.
        listOf(401, 403).forEach { status ->
            val denied = FetchSession(hub(), null).failure(status, emptyMap(), "")
            assertFalse(denied.retryable)
            assertTrue(denied.message!!, denied.message!!.contains("Remote → Accounts") && denied.message!!.contains("HTTP $status"))
        }
        assertFalse(FetchRules.failure(410, emptyMap(), "", null).retryable)
        assertTrue(FetchRules.failure(429, emptyMap(), "", null).retryable)
        assertEquals("HTTP 500", FetchRules.failure(500, emptyMap(), "", null).message)
    }

    @Test
    fun hubHostsAreExact() {
        assertTrue(FetchRules.isHubHost("huggingface.co", AuthHost.HUGGING_FACE))
        assertFalse(FetchRules.isHubHost("cdn-lfs.huggingface.co", AuthHost.HUGGING_FACE))
        assertFalse(FetchRules.isHubHost("evilhuggingface.co", AuthHost.HUGGING_FACE))
        assertTrue(FetchRules.isHubHost("civitai.red", AuthHost.CIVITAI))
        assertFalse(FetchRules.isHubHost("civitai-delivery-worker-prod.r2.cloudflarestorage.com", AuthHost.CIVITAI))
        assertFalse(FetchRules.isSha256("ABC"))
        assertTrue(FetchRules.isSha256(sha))
    }

    @Test
    fun handedOffTransferKeepsCredentialOnlyOnItsOwnHubHost() {
        val original = "https://huggingface.co/a/resolve/main/m.gguf"
        assertTrue(FetchRules.keepsCredential("https://huggingface.co/b/resolve/main/m.gguf", original, AuthHost.HUGGING_FACE))
        assertTrue(FetchRules.keepsCredential("https://HuggingFace.co:443/b", original, AuthHost.HUGGING_FACE))
        assertFalse(FetchRules.keepsCredential("https://cdn-lfs.huggingface.co/x", original, AuthHost.HUGGING_FACE))
        assertFalse(FetchRules.keepsCredential("https://civitai.com/x", "https://civitai.red/x", AuthHost.CIVITAI))
        assertFalse(FetchRules.keepsCredential("https://huggingface.co@evil.example/x", original, AuthHost.HUGGING_FACE))
        assertFalse(FetchRules.keepsCredential("https://huggingface.co/x", original, null))
    }

    @Test
    fun zipEntryIsFetchedByRangeAndCrcChecked() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("pkg/unet.bin"))
            z.write(content)
            z.closeEntry()
        }
        val zip = out.toByteArray()
        val cd = RemoteZip.locateDirectory(zip, 0) { from, to -> zip.copyOfRange(from.toInt(), to.toInt() + 1) }
        val entry = RemoteZip.parseCentralDirectory(zip.copyOfRange(cd.offset.toInt(), (cd.offset + cd.size).toInt()), cd.count).single()
        val start = entry.localHeaderOffset + RemoteZip.dataStart(zip.copyOfRange(0, 30))
        val compressed = zip.copyOfRange(start.toInt(), (start + entry.compressedSize).toInt())
        val ref = ZipEntryRef(entry.name, start, entry.compressedSize, entry.method, entry.crc32)
        val file = ModelFile(FileRole.UNET, "unet.bin", "https://huggingface.co/x/resolve/main/pkg.zip", entry.size, auth = AuthHost.HUGGING_FACE, zipEntry = ref, keepName = true)

        // The part file holds half; the next request resumes from there and ends at the entry's last byte.
        val half = compressed.size / 2
        val session = FetchSession(file, "hf_x")
        assertEquals(FetchStep.Next, session.begin(half.toLong()))
        val request = session.request(half.toLong())
        assertEquals("bytes=${start + half}-${start + entry.compressedSize - 1}", request.headers["Range"])
        assertEquals("Bearer hf_x", request.headers["Authorization"])
        val step = session.onResponse(206, emptyMap(), half.toLong(), (compressed.size - half).toLong())
        assertTrue(step is FetchStep.Write && step.append)
        assertEquals(entry.compressedSize, session.expectedSize)
        assertTrue(failure<DownloadException> { session.checkComplete(half.toLong()) }.message!!.startsWith("Incomplete download"))
        session.checkComplete(compressed.size.toLong())

        val inflated = InflaterInputStream(compressed.inputStream(), Inflater(true)).readBytes()
        val crc = CRC32().apply { update(inflated) }.value
        assertEquals(content.size.toLong(), session.verifyInflated(crc, inflated.size.toLong()).sizeBytes)
        assertTrue(failure<DownloadException> { session.verifyInflated(crc xor 1, inflated.size.toLong()) }.message!!.startsWith("Checksum mismatch"))

        // A complete part file needs no request; a longer one starts over; a server without ranges fails.
        assertEquals(FetchStep.Verify, FetchSession(file, null).begin(entry.compressedSize))
        assertEquals(FetchStep.Restart, FetchSession(file, null).begin(entry.compressedSize + 1))
        val noRanges = FetchSession(file, null).onResponse(200, emptyMap(), 0, zip.size.toLong())
        assertEquals("Server does not support partial downloads", (noRanges as FetchStep.Fail).error.message)
        assertEquals(FetchStep.Reject, FetchSession(file, null).onResponse(416, emptyMap(), 0, 0))
        val unresolved = FetchSession(file.copy(zipEntry = ZipEntryRef("pkg/unet.bin")), null).begin(0)
        assertTrue((unresolved as FetchStep.Fail).error.message!!.contains("was not resolved"))
        val deflate64 = FetchSession(file.copy(zipEntry = ref.copy(method = 9)), null).begin(0) as FetchStep.Fail
        assertFalse(deflate64.error.retryable)
    }
}

package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.security.Secret
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response

/**
 * Moves the bytes of one file for a shared [FetchSession], which decides everything else:
 * redirects hop by hop (each hop passes the kill switch and is audited; credentials are only sent
 * to their own hub), `Range` resume of `*.part`, SHA-256 verification (known up front, or the
 * Hub's `X-Linked-ETag`) and the atomic rename to the target. Zip entries are fetched by range
 * into `*.z.part`, then inflated and checked against the zip's CRC-32.
 *
 * A resumed part carries the validator (strong `ETag`, else `Last-Modified`) of the response
 * that started it in `*.part.validator`, sent back as `If-Range`: a file that changed on the
 * server comes back whole (200) and replaces the part instead of being spliced onto it.
 * Cancelling the caller cancels the HTTP call too, so a pause or a cancel never waits for a
 * stalled read to time out.
 */
class FileFetcher(private val clients: HttpClientFactory) {

    /** Bytes read back from disk to hash (a resumed part's prefix, or a fallback); tests watch it. */
    @Volatile
    internal var bytesHashedFromDisk = 0L
        private set

    /** SHA-256 of a `*.part` as it is written, so verification never re-reads a multi-GB file. */
    private class RunningHash {
        var digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        /** How many bytes of the part [digest] covers. */
        var bytes = 0L

        fun reset() {
            digest = MessageDigest.getInstance("SHA-256")
            bytes = 0L
        }
    }

    suspend fun fetch(file: ModelFile, target: File, credential: Secret?, onProgress: suspend (Long, Long) -> Unit): ModelFile {
        target.parentFile?.mkdirs()
        val session = FetchSession(file, credential?.takeUnless { it.isBlank }?.reveal())
        val part = File(target.path + if (file.zipEntry != null) ".z.part" else ".part")
        val validator = File(part.path + VALIDATOR)
        fun have() = if (part.isFile) part.length() else 0L
        val hash = RunningHash()
        var step = session.begin(have())
        while (true) {
            step = when (val s = step) {
                FetchStep.Next -> {
                    currentCoroutineContext().ensureActive()
                    val have = have()
                    val request = session.request(have)
                    val ifRange = if (have > 0 && file.zipEntry == null) validator.readValidator() else null
                    execute(request.url, if (ifRange != null) request.headers + ("If-Range" to ifRange) else request.headers) { r ->
                        val headers = r.headers.toMap()
                        when (val next = session.onResponse(r.code, headers, have, r.body.contentLength())) {
                            is FetchStep.Write -> {
                                if (!next.append) validator.writeValidator(r)
                                stream(r, part, next.append, session.expectedSize, hash, onProgress)
                                FetchStep.Verify
                            }
                            FetchStep.Reject -> throw session.failure(r.code, headers, runCatching { r.peekBody(2048).string() }.getOrDefault(""))
                            else -> next
                        }
                    }
                }
                FetchStep.Restart -> {
                    part.delete()
                    validator.delete()
                    FetchStep.Next
                }
                is FetchStep.Fail -> throw s.error
                FetchStep.Verify -> return try {
                    withContext(Dispatchers.IO) {
                        session.checkComplete(if (part.isFile) part.length() else -1)
                        if (file.zipEntry != null) inflateAndCommit(session, part, target) else verifyAndCommit(session, part, target, hash)
                    }
                } finally {
                    // Committed or deleted as corrupt: the validator goes with the part. Incomplete: both stay for a resume.
                    if (!part.isFile) validator.delete()
                }
                is FetchStep.Write, FetchStep.Reject -> error("$s is answered inside a response")
            }
        }
    }

    /**
     * Reads bytes [from]..[to] (inclusive) of a remote file, following redirects hop by hop.
     * Returns the bytes and the file's total size from `Content-Range`.
     */
    suspend fun readRange(url: String, from: Long, to: Long, credential: Secret?, auth: AuthHost?): Pair<ByteArray, Long> {
        var current = url
        repeat(FetchRules.MAX_HOPS) {
            val headers = FetchRules.headers(current, from, to, credential?.takeUnless { it.isBlank }?.reveal(), auth)
            execute(current, headers) { r ->
                if (FetchRules.isRedirect(r.code)) {
                    current = r.header("Location")?.let { FetchRules.resolve(current, it) } ?: throw DownloadException("Redirect without location")
                    return@execute null
                }
                if (r.code != 206) {
                    throw if (r.code == 200) {
                        DownloadException("Server does not support partial downloads")
                    } else {
                        FetchRules.failure(r.code, r.headers.toMap(), runCatching { r.peekBody(2048).string() }.getOrDefault(""), null)
                    }
                }
                val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                withContext(Dispatchers.IO) { r.body.bytes() } to total
            }?.let { return it }
        }
        throw DownloadException("Too many redirects")
    }

    /**
     * Sends one GET through the audited client and hands the response to [block], then closes it.
     * Cancelling the caller cancels the call: a read blocked on a stalled connection fails at once
     * instead of after the read timeout.
     */
    private suspend fun <T> execute(url: String, headers: Map<String, String>, block: suspend (Response) -> T): T {
        val request = try {
            Request.Builder().url(url).get().apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        } catch (e: IllegalArgumentException) {
            throw DownloadException("Invalid URL", retryable = false)
        }
        val call = clients.create(tag = "models:download").newCall(request)
        return coroutineScope {
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) { call.execute() }.use { block(it) }
            } finally {
                watcher.cancel()
            }
        }
    }

    /** The validator a resumed request sends as `If-Range`, or null when none was kept. */
    private fun File.readValidator(): String? = runCatching { if (isFile) readText().trim().takeIf { it.isNotEmpty() } else null }.getOrNull()

    /** Keeps the response's strong `ETag` (else `Last-Modified`) for resuming the part it starts; weak ETags can't be used with If-Range. */
    private suspend fun File.writeValidator(r: Response) = withContext(Dispatchers.IO) {
        val value = r.header("ETag")?.takeUnless { it.startsWith("W/") } ?: r.header("Last-Modified")
        runCatching { if (value.isNullOrBlank()) delete() else writeText(value) }
    }

    private suspend fun stream(response: Response, part: File, append: Boolean, expectedSize: Long, hash: RunningHash, onProgress: suspend (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            if (!append) part.delete()
            // Resuming: hash what is already on disk once (from an earlier process or attempt), then keep going.
            val existing = if (part.isFile) part.length() else 0L
            if (hash.bytes != existing) {
                hash.reset()
                if (existing > 0) hashPrefix(part, hash)
            }
            val source = response.body.source()
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(BUFFER)
                var written = if (append) part.length() else 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = try {
                        source.read(buffer)
                    } catch (e: IOException) {
                        // Cancelling closes the call, and a read blocked on the stalled socket then
                        // fails with "Socket closed": that is the cancellation, not a network error.
                        currentCoroutineContext().ensureActive()
                        throw e
                    }
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    hash.digest.update(buffer, 0, n)
                    hash.bytes += n
                    written += n
                    onProgress(written, expectedSize.coerceAtLeast(written))
                }
                out.fd.sync()
            }
        }
    }

    private fun hashPrefix(part: File, hash: RunningHash) {
        part.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                hash.digest.update(buffer, 0, n)
                hash.bytes += n
                bytesHashedFromDisk += n
            }
        }
    }

    private fun verifyAndCommit(session: FetchSession, part: File, target: File, hash: RunningHash): ModelFile {
        val length = part.length()
        // The running hash covers the part unless it was written outside this fetch (then hash it once now).
        if (hash.bytes != length) {
            hash.reset()
            hashPrefix(part, hash)
        }
        val sha = hash.digest.digest().joinToString("") { "%02x".format(it) }
        hash.reset()
        val verified = try {
            session.verify(sha, length)
        } catch (e: DownloadException) {
            part.delete()
            throw e
        }
        target.delete()
        if (!part.renameTo(target)) throw DownloadException("Could not store ${session.file.name}")
        return verified
    }

    private fun inflateAndCommit(session: FetchSession, part: File, target: File): ModelFile {
        val tmp = File(target.path + ".tmp")
        val crc = CRC32()
        var size = 0L
        part.inputStream().buffered(BUFFER).use { raw ->
            val input = if (session.file.zipEntry?.method == 8) InflaterInputStream(raw, Inflater(true), BUFFER) else raw
            FileOutputStream(tmp).use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    size += n
                }
                out.fd.sync()
            }
        }
        val verified = try {
            session.verifyInflated(crc.value, size)
        } catch (e: DownloadException) {
            tmp.delete()
            part.delete()
            throw e
        }
        target.delete()
        if (!tmp.renameTo(target)) throw DownloadException("Could not store ${session.file.name}")
        part.delete()
        return verified
    }

    companion object {
        private const val BUFFER = 256 * 1024

        /** Suffix of the file next to a part that holds its `If-Range` validator. */
        internal const val VALIDATOR = ".validator"

        fun isHubHost(host: String, auth: AuthHost): Boolean = FetchRules.isHubHost(host, auth)

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

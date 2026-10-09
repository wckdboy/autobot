package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.core.network.HttpClientFactory
import dev.wckdboy.autobot.core.security.Secret
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response

/** A failure the user can fix (or retry); the model is left PAUSED/FAILED with this message. */
class DownloadException(message: String, val retryable: Boolean = true) : IOException(message)

/**
 * Transfers one file: resolves redirects hop by hop (each hop passes the kill switch and is
 * audited; credentials are only sent to their own hub), appends to `*.part` with `Range`
 * resume, verifies SHA-256 (known up front, or the Hub's `X-Linked-ETag`) and atomically
 * renames to the target.
 */
class FileFetcher(private val clients: HttpClientFactory) {

    suspend fun fetch(file: ModelFile, target: File, credential: Secret?, onProgress: suspend (Long, Long) -> Unit): ModelFile {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        var expectedSha = file.sha256?.lowercase()
        var expectedSize = file.sizeBytes
        var url = file.url
        var hops = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (hops++ > MAX_HOPS) throw DownloadException("Too many redirects")
            val have = if (part.isFile) part.length() else 0L
            val response = execute(url, have, credential, file.auth)
            response.use { r ->
                if (r.isRedirect) {
                    // The Hub reports the LFS SHA-256 and size on the redirect.
                    r.header("X-Linked-ETag")?.trim('"', ' ')?.lowercase()?.takeIf { SHA256.matches(it) }?.let { if (expectedSha == null) expectedSha = it }
                    r.header("X-Linked-Size")?.toLongOrNull()?.let { expectedSize = it }
                    url = r.header("Location")?.let { r.request.url.resolve(it)?.toString() }
                        ?: throw DownloadException("Redirect without location")
                    return@use
                }
                when (r.code) {
                    200, 206 -> {
                        val append = r.code == 206 && have > 0
                        val length = r.body.contentLength()
                        val total = if (append) have + length else length
                        if (expectedSize <= 0 && total > 0) expectedSize = total
                        stream(r, part, append, expectedSize, onProgress)
                    }
                    416 -> if (expectedSize > 0 && have != expectedSize) {
                        part.delete()
                        return@use
                    }
                    else -> throw failure(r, file)
                }
                return verifyAndCommit(part, target, file, expectedSha, expectedSize)
            }
        }
    }

    private suspend fun execute(url: String, offset: Long, credential: Secret?, auth: AuthHost?): Response = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw DownloadException("Invalid URL", retryable = false)
        val builder = Request.Builder().url(httpUrl).get().header("Accept", "*/*")
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        // Credentials go only to their own hub, never to CDNs or third parties.
        if (credential != null && !credential.isBlank && auth != null && isHubHost(httpUrl.host, auth)) {
            builder.header("Authorization", "Bearer ${credential.reveal()}")
        }
        clients.create(tag = "models:download").newCall(builder.build()).execute()
    }

    private suspend fun stream(response: Response, part: File, append: Boolean, expectedSize: Long, onProgress: suspend (Long, Long) -> Unit) {
        withContext(Dispatchers.IO) {
            if (!append) part.delete()
            val source = response.body.source()
            FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(BUFFER)
                var written = if (append) part.length() else 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = source.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    onProgress(written, expectedSize.coerceAtLeast(written))
                }
                out.fd.sync()
            }
        }
    }

    private suspend fun verifyAndCommit(part: File, target: File, file: ModelFile, expectedSha: String?, expectedSize: Long): ModelFile =
        withContext(Dispatchers.IO) {
            if (!part.isFile) throw DownloadException("Download produced no data")
            if (expectedSize > 0 && part.length() != expectedSize) {
                throw DownloadException("Incomplete download (${formatBytes(part.length())} of ${formatBytes(expectedSize)}); resume to continue")
            }
            val actual = sha256(part)
            if (expectedSha != null && actual != expectedSha) {
                part.delete()
                throw DownloadException("Checksum mismatch for ${file.name}: the file was corrupted or altered. It was deleted; retry the download.")
            }
            target.delete()
            if (!part.renameTo(target)) throw DownloadException("Could not store ${file.name}")
            file.copy(sizeBytes = target.length(), sha256 = actual)
        }

    private fun failure(r: Response, file: ModelFile): DownloadException {
        val body = runCatching { r.peekBody(2048).string() }.getOrDefault("")
        return when {
            r.header("X-Error-Code") == "GatedRepo" -> DownloadException(
                "Gated model: accept its terms on huggingface.co with your account, then sign in under Remote → Accounts.",
                retryable = false,
            )
            r.code == 401 && file.auth == AuthHost.CIVITAI -> DownloadException(
                "Civitai requires login for this file: add an API key under Remote → Accounts.",
                retryable = false,
            )
            r.code == 401 || r.code == 403 -> DownloadException(
                "Access denied (HTTP ${r.code})${if ("Early Access" in body) ": early-access file" else ""}",
                retryable = false,
            )
            r.code == 404 || r.code == 410 -> DownloadException("File no longer available (HTTP ${r.code})", retryable = false)
            r.code == 429 -> DownloadException("Rate limited by the server — resume later")
            else -> DownloadException("HTTP ${r.code}")
        }
    }

    companion object {
        private const val MAX_HOPS = 6
        private const val BUFFER = 256 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun isHubHost(host: String, auth: AuthHost): Boolean = when (auth) {
            AuthHost.HUGGING_FACE -> host == "huggingface.co"
            AuthHost.CIVITAI -> host == "civitai.com" || host == "civitai.red"
        }

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

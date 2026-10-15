package dev.wckdboy.autobot.core.models

import okio.IOException

/** A failure the user can fix (or retry); the model is left PAUSED/FAILED with this message. */
class DownloadException(message: String, val retryable: Boolean = true) : IOException(message)

/** The credential and request rules every model transfer follows, on both platforms. */
object FetchRules {
    /** Requests per file, redirect hops included. */
    const val MAX_HOPS = 6

    private val SHA256 = Regex("[0-9a-f]{64}")

    fun isHubHost(host: String, auth: AuthHost): Boolean = when (auth) {
        AuthHost.HUGGING_FACE -> host == "huggingface.co"
        AuthHost.CIVITAI -> host == "civitai.com" || host == "civitai.red"
    }

    fun isSha256(s: String): Boolean = SHA256.matches(s)

    /**
     * Headers for a GET of [url]: `Range` from [offset] (to [end] inclusive when `>= 0`) and the
     * hub [credential] — only when [url] is on that hub's own host, never on a CDN or third party.
     */
    fun headers(url: String, offset: Long, end: Long, credential: String?, auth: AuthHost?): Map<String, String> = buildMap {
        put("Accept", "*/*")
        if (end >= 0) put("Range", "bytes=$offset-$end") else if (offset > 0) put("Range", "bytes=$offset-")
        val host = hostOf(url)
        if (!credential.isNullOrBlank() && auth != null && host != null && isHubHost(host, auth)) put("Authorization", "Bearer $credential")
    }

    /**
     * Whether a redirect met by a transfer running in another session (iOS background downloads)
     * keeps the hub credential: only to the very host it was issued for, and only a hub host.
     */
    fun keepsCredential(hopUrl: String, originalUrl: String, auth: AuthHost?): Boolean {
        val host = hostOf(hopUrl) ?: return false
        return auth != null && host == hostOf(originalUrl) && isHubHost(host, auth)
    }

    /** A redirect's `Location` resolved against the request URL; null when unusable. */
    fun resolve(url: String, location: String): String? = resolveLocation(url, location)

    /** Whether [status] is a redirect to follow hop by hop. */
    fun isRedirect(status: Int): Boolean = status in REDIRECTS

    private val REDIRECTS = setOf(300, 301, 302, 303, 307, 308)

    /** The user-facing error for a failed response ([body]: its first bytes, may be empty). */
    fun failure(status: Int, headers: Map<String, String>, body: String, auth: AuthHost?): DownloadException = when {
        headers.header("X-Error-Code") == "GatedRepo" -> DownloadException(
            "Gated model: accept its terms on huggingface.co with your account, then sign in under Remote → Accounts.",
            retryable = false,
        )
        status == 401 && auth == AuthHost.CIVITAI -> DownloadException(
            "Civitai requires login for this file: add an API key under Remote → Accounts.",
            retryable = false,
        )
        status == 401 || status == 403 -> DownloadException(
            "Access denied (HTTP $status)${if ("Early Access" in body) ": early-access file" else ""}",
            retryable = false,
        )
        status == 404 || status == 410 -> DownloadException("File no longer available (HTTP $status)", retryable = false)
        status == 429 -> DownloadException("Rate limited by the server — resume later")
        else -> DownloadException("HTTP $status")
    }

    internal fun Map<String, String>.header(name: String): String? = entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** A GET the host sends for a transfer, without following redirects. */
class FetchRequest(val url: String, val headers: Map<String, String>)

/** What the host does next in a [FetchSession]. */
sealed interface FetchStep {
    /** Send [FetchSession.request]. */
    data object Next : FetchStep

    /** Stream the body into the part file (appending to it, or starting it over), then [Verify]. */
    class Write(val append: Boolean) : FetchStep

    /** The part file cannot be resumed: delete it, then [Next]. */
    data object Restart : FetchStep

    /** Nothing (more) to transfer: check the part file. */
    data object Verify : FetchStep

    /** Stop with [error]. */
    class Fail(val error: DownloadException) : FetchStep

    /** An error response: read the start of its body and stop with [FetchSession.failure]. */
    data object Reject : FetchStep
}

/**
 * The decisions of one resumable, verified file transfer; the host moves the bytes (OkHttp on
 * Android, URLSession on iOS) into a part file and hashes it.
 *
 * Redirects are followed hop by hop (each hop passes the host's kill switch and audit log), the
 * credential goes only to its own hub, a part file resumes with `Range`, the size and SHA-256
 * come from the plan or from the Hub's `X-Linked-Size` / `X-Linked-ETag`, and a file that fails
 * verification is deleted. Entries of remote zips ([ModelFile.zipEntry]) are fetched by byte
 * range and checked against the zip's CRC-32 once inflated.
 */
class FetchSession(val file: ModelFile, credential: String?) {
    private val credential = credential?.takeIf { it.isNotBlank() }
    private val entry = file.zipEntry
    private var hops = 0

    /** The URL of the next request (changes with every redirect). */
    var url: String = file.url
        private set

    /** Lower-case hex SHA-256 the finished file must have, when known. */
    var expectedSha256: String? = file.sha256?.lowercase()
        private set

    /** Size of the finished file (the compressed entry for zips), 0 when unknown. */
    var expectedSize: Long = entry?.compressedSize ?: file.sizeBytes
        private set

    /** Where to start, given the part file holds [have] bytes. */
    fun begin(have: Long): FetchStep {
        if (entry == null) return FetchStep.Next
        if (entry.dataOffset < 0) return FetchStep.Fail(DownloadException("Zip entry ${entry.entry} was not resolved"))
        if (entry.method != 0 && entry.method != 8) return FetchStep.Fail(DownloadException("Unsupported zip compression (${entry.method})", retryable = false))
        val total = entry.compressedSize
        return when {
            total <= 0 || have == total -> FetchStep.Verify
            have > total -> FetchStep.Restart
            else -> FetchStep.Next
        }
    }

    /** The request for a part file holding [have] bytes. */
    @Throws(DownloadException::class)
    fun request(have: Long): FetchRequest {
        if (hops++ > FetchRules.MAX_HOPS) throw DownloadException("Too many redirects")
        val headers = if (entry != null) {
            FetchRules.headers(url, entry.dataOffset + have, entry.dataOffset + entry.compressedSize - 1, credential, file.auth)
        } else {
            FetchRules.headers(url, have, -1, credential, file.auth)
        }
        return FetchRequest(url, headers)
    }

    /**
     * Handles the response to [request] ([contentLength]: body length, -1 when unknown). Headers
     * may use any case.
     */
    fun onResponse(status: Int, headers: Map<String, String>, have: Long, contentLength: Long): FetchStep {
        with(FetchRules) {
            if (isRedirect(status)) {
                if (entry == null) {
                    // The Hub reports the LFS SHA-256 and size on the redirect.
                    headers.header("X-Linked-ETag")?.trim('"', ' ')?.lowercase()?.takeIf { isSha256(it) }?.let { if (expectedSha256 == null) expectedSha256 = it }
                    headers.header("X-Linked-Size")?.toLongOrNull()?.let { expectedSize = it }
                }
                url = headers.header("Location")?.let { resolve(url, it) }
                    ?: return FetchStep.Fail(DownloadException("Redirect without location"))
                return FetchStep.Next
            }
        }
        if (entry != null) {
            return when (status) {
                206 -> FetchStep.Write(append = have > 0)
                200 -> FetchStep.Fail(DownloadException("Server does not support partial downloads"))
                else -> FetchStep.Reject
            }
        }
        return when (status) {
            200, 206 -> {
                val append = status == 206 && have > 0
                val total = if (append) have + contentLength.coerceAtLeast(0) else contentLength
                if (expectedSize <= 0 && total > 0) expectedSize = total
                FetchStep.Write(append)
            }
            416 -> if (expectedSize > 0 && have != expectedSize) FetchStep.Restart else FetchStep.Verify
            else -> FetchStep.Reject
        }
    }

    /** The error for a [FetchStep.Reject]ed response ([body]: its first bytes). */
    fun failure(status: Int, headers: Map<String, String>, body: String): DownloadException = FetchRules.failure(status, headers, body, file.auth)

    /** Fails unless the part file ([partSize] bytes, -1 when missing) holds everything. */
    @Throws(DownloadException::class)
    fun checkComplete(partSize: Long) {
        if (entry == null && partSize < 0) throw DownloadException("Download produced no data")
        val have = partSize.coerceAtLeast(0)
        if ((entry != null || expectedSize > 0) && have != expectedSize) {
            throw DownloadException("Incomplete download (${formatBytes(have)} of ${formatBytes(expectedSize)}); resume to continue")
        }
    }

    /**
     * The finished file with its verified size and SHA-256. Throws when the bytes do not match:
     * the host deletes the part file.
     */
    @Throws(DownloadException::class)
    fun verify(sha256: String, size: Long): ModelFile {
        if (expectedSha256 != null && sha256 != expectedSha256) {
            throw DownloadException("Checksum mismatch for ${file.name}: the file was corrupted or altered. It was deleted; retry the download.")
        }
        return file.copy(sizeBytes = size, sha256 = sha256)
    }

    /**
     * The finished zip entry, inflated (method 8) or copied (method 0) to [size] bytes with [crc32]. Throws when it does not match
     * the directory: the host deletes the inflated and the part file.
     */
    @Throws(DownloadException::class)
    fun verifyInflated(crc32: Long, size: Long): ModelFile {
        val entry = entry ?: throw DownloadException("${file.name} is not a zip entry", retryable = false)
        if (crc32 != entry.crc32 || (file.sizeBytes > 0 && size != file.sizeBytes)) {
            throw DownloadException("Checksum mismatch for ${file.name}: the file was corrupted. It was deleted; retry the download.")
        }
        return file.copy(sizeBytes = size)
    }
}

package dev.wckdboy.autobot.core.models

import dev.wckdboy.autobot.core.security.Secret
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One file inside a remote zip, as listed by its central directory. */
data class RemoteZipEntry(
    val name: String,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
    val method: Int,
    val crc32: Long,
) {
    val baseName: String get() = name.substringAfterLast('/')
    val isDirectory: Boolean get() = name.endsWith("/")
}

/**
 * Reads the directory of a zip on a server with HTTP Range requests, so single entries of a
 * multi-GB package can be downloaded without fetching (or storing) the whole archive.
 */
class RemoteZip(private val fetcher: FileFetcher) {

    suspend fun list(url: String, credential: Secret?, auth: AuthHost?): List<RemoteZipEntry> {
        val (first, total) = fetcher.readRange(url, 0, 0, credential, auth)
        if (first.isEmpty() || total <= 0) throw DownloadException("Cannot read the archive size")
        val tailStart = (total - TAIL).coerceAtLeast(0)
        val (tail, _) = fetcher.readRange(url, tailStart, total - 1, credential, auth)
        val dir = locateDirectory(tail, tailStart) { from, to -> fetcher.readRange(url, from, to, credential, auth).first }
        val (cd, _) = fetcher.readRange(url, dir.offset, dir.offset + dir.size - 1, credential, auth)
        return parseCentralDirectory(cd, dir.count)
    }

    /** Fills in where [entry]'s data starts (the local header's extra field can differ from the directory's). */
    suspend fun resolve(url: String, entry: RemoteZipEntry, credential: Secret?, auth: AuthHost?): ZipEntryRef {
        val (header, _) = fetcher.readRange(url, entry.localHeaderOffset, entry.localHeaderOffset + 29, credential, auth)
        return ZipEntryRef(
            entry = entry.name,
            dataOffset = entry.localHeaderOffset + dataStart(header),
            compressedSize = entry.compressedSize,
            method = entry.method,
            crc32 = entry.crc32,
        )
    }

    internal data class Directory(val offset: Long, val size: Long, val count: Long)

    companion object {
        private const val TAIL = 64L * 1024

        private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        /** Offset of entry data relative to its local header (30 + name + extra). */
        fun dataStart(localHeader: ByteArray): Long {
            val b = le(localHeader)
            if (b.getInt(0) != 0x04034b50) throw DownloadException("Corrupt archive (bad local header)", retryable = false)
            return 30L + (b.getShort(26).toInt() and 0xffff) + (b.getShort(28).toInt() and 0xffff)
        }

        /** Finds the central directory from the archive tail (zip64 aware). */
        internal suspend fun locateDirectory(tail: ByteArray, tailStart: Long, read: suspend (Long, Long) -> ByteArray): Directory {
            val b = le(tail)
            val eocd = (tail.size - 22 downTo 0).firstOrNull { b.getInt(it) == 0x06054b50 }
                ?: throw DownloadException("Not a zip archive", retryable = false)
            var count = b.getShort(eocd + 10).toLong() and 0xffff
            var size = b.getInt(eocd + 12).toLong() and 0xffffffffL
            var offset = b.getInt(eocd + 16).toLong() and 0xffffffffL
            if (offset == 0xffffffffL || count == 0xffffL || size == 0xffffffffL) {
                val locator = eocd - 20
                if (locator < 0 || b.getInt(locator) != 0x07064b50) throw DownloadException("Corrupt zip64 archive", retryable = false)
                val at = b.getLong(locator + 8)
                val z = le(if (at >= tailStart && at + 56 <= tailStart + tail.size) tail.copyOfRange((at - tailStart).toInt(), (at - tailStart).toInt() + 56) else read(at, at + 55))
                count = z.getLong(32)
                size = z.getLong(40)
                offset = z.getLong(48)
            }
            return Directory(offset, size, count)
        }

        fun parseCentralDirectory(cd: ByteArray, count: Long): List<RemoteZipEntry> {
            val b = le(cd)
            val out = ArrayList<RemoteZipEntry>()
            var p = 0
            repeat(count.toInt()) {
                if (p + 46 > cd.size || b.getInt(p) != 0x02014b50) throw DownloadException("Corrupt central directory", retryable = false)
                val method = b.getShort(p + 10).toInt() and 0xffff
                val crc = b.getInt(p + 16).toLong() and 0xffffffffL
                var compressed = b.getInt(p + 20).toLong() and 0xffffffffL
                var size = b.getInt(p + 24).toLong() and 0xffffffffL
                val nameLen = b.getShort(p + 28).toInt() and 0xffff
                val extraLen = b.getShort(p + 30).toInt() and 0xffff
                val commentLen = b.getShort(p + 32).toInt() and 0xffff
                var offset = b.getInt(p + 42).toLong() and 0xffffffffL
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                var x = p + 46 + nameLen
                val end = x + extraLen
                while (x + 4 <= end) {
                    val id = b.getShort(x).toInt() and 0xffff
                    val len = b.getShort(x + 2).toInt() and 0xffff
                    if (id == 1) {
                        var q = x + 4
                        if (size == 0xffffffffL) { size = b.getLong(q); q += 8 }
                        if (compressed == 0xffffffffL) { compressed = b.getLong(q); q += 8 }
                        if (offset == 0xffffffffL) offset = b.getLong(q)
                    }
                    x += 4 + len
                }
                out += RemoteZipEntry(name, compressed, size, offset, method, crc)
                p += 46 + nameLen + extraLen + commentLen
            }
            return out
        }
    }
}

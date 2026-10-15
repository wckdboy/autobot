package dev.wckdboy.autobot.core.models

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
 * multi-GB package can be downloaded without fetching (or storing) the whole archive. [read]
 * returns bytes `from..to` (inclusive) of the archive and its total size.
 */
class RemoteZip(private val read: suspend (from: Long, to: Long) -> Pair<ByteArray, Long>) {

    suspend fun list(): List<RemoteZipEntry> {
        val (first, total) = read(0, 0)
        if (first.isEmpty() || total <= 0) throw DownloadException("Cannot read the archive size")
        val tailStart = (total - TAIL).coerceAtLeast(0)
        val (tail, _) = read(tailStart, total - 1)
        val dir = locateDirectory(tail, tailStart) { from, to -> read(from, to).first }
        val (cd, _) = read(dir.offset, dir.offset + dir.size - 1)
        return parseCentralDirectory(cd, dir.count)
    }

    /** Fills in where [entry]'s data starts (the local header's extra field can differ from the directory's). */
    suspend fun resolve(entry: RemoteZipEntry): ZipEntryRef {
        val (header, _) = read(entry.localHeaderOffset, entry.localHeaderOffset + 29)
        return ZipEntryRef(
            entry = entry.name,
            dataOffset = entry.localHeaderOffset + dataStart(header),
            compressedSize = entry.compressedSize,
            method = entry.method,
            crc32 = entry.crc32,
        )
    }

    data class Directory(val offset: Long, val size: Long, val count: Long)

    companion object {
        private const val TAIL = 64L * 1024

        private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)
        private fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        private fun ByteArray.i64(at: Int): Long = u32(at) or (u32(at + 4) shl 32)

        /** Offset of entry data relative to its local header (30 + name + extra). */
        fun dataStart(localHeader: ByteArray): Long {
            if (localHeader.size < 30 || localHeader.u32(0) != 0x04034b50L) throw DownloadException("Corrupt archive (bad local header)", retryable = false)
            return 30L + localHeader.u16(26) + localHeader.u16(28)
        }

        /** Finds the central directory from the archive tail (zip64 aware). */
        suspend fun locateDirectory(tail: ByteArray, tailStart: Long, read: suspend (Long, Long) -> ByteArray): Directory {
            val eocd = (tail.size - 22 downTo 0).firstOrNull { tail.u32(it) == 0x06054b50L }
                ?: throw DownloadException("Not a zip archive", retryable = false)
            var count = tail.u16(eocd + 10).toLong()
            var size = tail.u32(eocd + 12)
            var offset = tail.u32(eocd + 16)
            if (offset == 0xffffffffL || count == 0xffffL || size == 0xffffffffL) {
                val locator = eocd - 20
                if (locator < 0 || tail.u32(locator) != 0x07064b50L) throw DownloadException("Corrupt zip64 archive", retryable = false)
                val at = tail.i64(locator + 8)
                val z = if (at >= tailStart && at + 56 <= tailStart + tail.size) {
                    tail.copyOfRange((at - tailStart).toInt(), (at - tailStart).toInt() + 56)
                } else {
                    read(at, at + 55)
                }
                count = z.i64(32)
                size = z.i64(40)
                offset = z.i64(48)
            }
            return Directory(offset, size, count)
        }

        fun parseCentralDirectory(cd: ByteArray, count: Long): List<RemoteZipEntry> {
            val out = ArrayList<RemoteZipEntry>()
            var p = 0
            repeat(count.toInt()) {
                if (p + 46 > cd.size || cd.u32(p) != 0x02014b50L) throw DownloadException("Corrupt central directory", retryable = false)
                val method = cd.u16(p + 10)
                val crc = cd.u32(p + 16)
                var compressed = cd.u32(p + 20)
                var size = cd.u32(p + 24)
                val nameLen = cd.u16(p + 28)
                val extraLen = cd.u16(p + 30)
                val commentLen = cd.u16(p + 32)
                var offset = cd.u32(p + 42)
                val name = cd.decodeToString(p + 46, p + 46 + nameLen)
                var x = p + 46 + nameLen
                val end = x + extraLen
                while (x + 4 <= end) {
                    val id = cd.u16(x)
                    val len = cd.u16(x + 2)
                    if (id == 1) {
                        var q = x + 4
                        if (size == 0xffffffffL) { size = cd.i64(q); q += 8 }
                        if (compressed == 0xffffffffL) { compressed = cd.i64(q); q += 8 }
                        if (offset == 0xffffffffL) offset = cd.i64(q)
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

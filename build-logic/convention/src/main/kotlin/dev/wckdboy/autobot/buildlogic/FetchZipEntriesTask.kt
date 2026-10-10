package dev.wckdboy.autobot.buildlogic

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Extracts a few entries from a large remote zip using HTTP Range requests, so a multi-GB SDK
 * does not have to be downloaded to get its headers. Each entry is checked against the CRC-32
 * in the zip's central directory. Used for the Qualcomm QAIRT headers, which may not be
 * committed to the repository.
 */
@DisableCachingByDefault(because = "Downloads from the network; the output directory is the cache")
abstract class FetchZipEntriesTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    /** Entry name prefixes to extract, e.g. `qairt/2.47.0.260601/include/QNN/`. */
    @get:Input
    abstract val prefixes: ListProperty<String>

    /** Leading path removed from every extracted entry name. */
    @get:Input
    abstract val stripPrefix: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @Transient
    private var client: HttpClient? = null

    private class Entry(val name: String, val compressed: Long, val size: Long, val offset: Long, val method: Int, val crc: Long)

    @TaskAction
    fun fetch() {
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()
        val out = outputDir.get().asFile
        val total = probeSize()
        val tail = range(total - minOf(total, 1L shl 20), total - 1)
        val entries = centralDirectory(tail, total)
        val wanted = entries.filter { e -> !e.name.endsWith("/") && prefixes.get().any { e.name.startsWith(it) } }
        if (wanted.isEmpty()) throw IOException("No entries under ${prefixes.get()} in ${url.get()}")
        for (e in wanted) {
            val local = le(range(e.offset, e.offset + 29))
            val data = e.offset + 30 + (local.getShort(26).toInt() and 0xffff) + (local.getShort(28).toInt() and 0xffff)
            val raw = if (e.compressed == 0L) ByteArray(0) else range(data, data + e.compressed - 1)
            val bytes = when (e.method) {
                0 -> raw
                8 -> InflaterInputStream(ByteArrayInputStream(raw), Inflater(true)).readBytes()
                else -> throw IOException("Unsupported compression ${e.method} for ${e.name}")
            }
            val crc = CRC32().apply { update(bytes) }.value
            if (crc != e.crc || bytes.size.toLong() != e.size) throw IOException("Checksum mismatch for ${e.name}")
            val target = out.resolve(e.name.removePrefix(stripPrefix.get())).normalize()
            if (!target.toPath().startsWith(out.toPath())) throw IOException("Unsafe entry name ${e.name}")
            target.parentFile.mkdirs()
            target.writeBytes(bytes)
        }
        logger.lifecycle("Fetched ${wanted.size} files from ${url.get()} into $out")
    }

    private fun probeSize(): Long {
        val response = send(0, 0)
        val header = response.headers().firstValue("Content-Range").orElseThrow { IOException("Server does not support ranges") }
        return header.substringAfter('/').toLong()
    }

    private fun range(from: Long, to: Long): ByteArray = send(from, to).body()

    private fun send(from: Long, to: Long): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create(url.get())).header("Range", "bytes=$from-$to").build()
        val response = client!!.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 206) throw IOException("HTTP ${response.statusCode()} for ${url.get()}")
        return response
    }

    private fun centralDirectory(tail: ByteArray, total: Long): List<Entry> {
        val b = le(tail)
        val eocd = (tail.size - 22 downTo 0).firstOrNull { b.getInt(it) == 0x06054b50 } ?: throw IOException("Not a zip")
        var count = (b.getShort(eocd + 10).toLong() and 0xffff)
        var cdSize = b.getInt(eocd + 12).toLong() and 0xffffffffL
        var cdOffset = b.getInt(eocd + 16).toLong() and 0xffffffffL
        if (cdOffset == 0xffffffffL || count == 0xffffL) {
            val locator = eocd - 20
            val z = le(range(b.getLong(locator + 8), b.getLong(locator + 8) + 55))
            count = z.getLong(32)
            cdSize = z.getLong(40)
            cdOffset = z.getLong(48)
        }
        require(cdOffset + cdSize <= total) { "Corrupt central directory" }
        val cd = le(range(cdOffset, cdOffset + cdSize - 1))
        val list = ArrayList<Entry>()
        var p = 0
        repeat(count.toInt()) {
            val method = cd.getShort(p + 10).toInt() and 0xffff
            val crc = cd.getInt(p + 16).toLong() and 0xffffffffL
            var compressed = cd.getInt(p + 20).toLong() and 0xffffffffL
            var size = cd.getInt(p + 24).toLong() and 0xffffffffL
            val nameLen = cd.getShort(p + 28).toInt() and 0xffff
            val extraLen = cd.getShort(p + 30).toInt() and 0xffff
            val commentLen = cd.getShort(p + 32).toInt() and 0xffff
            var offset = cd.getInt(p + 42).toLong() and 0xffffffffL
            val name = String(cd.array(), p + 46, nameLen, Charsets.UTF_8)
            var x = p + 46 + nameLen
            val end = x + extraLen
            while (x + 4 <= end) {
                val id = cd.getShort(x).toInt() and 0xffff
                val len = cd.getShort(x + 2).toInt() and 0xffff
                if (id == 1) {
                    var q = x + 4
                    if (size == 0xffffffffL) { size = cd.getLong(q); q += 8 }
                    if (compressed == 0xffffffffL) { compressed = cd.getLong(q); q += 8 }
                    if (offset == 0xffffffffL) offset = cd.getLong(q)
                }
                x += 4 + len
            }
            list += Entry(name, compressed, size, offset, method, crc)
            p += 46 + nameLen + extraLen + commentLen
        }
        return list
    }

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}

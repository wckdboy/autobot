package dev.wckdboy.autobot.engine.llama

import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A look at a model file before llama.cpp loads it, so a file that cannot work fails with a
 * message that says what is wrong instead of llama.cpp's generic load failure (or a crash): gone
 * from disk, not a GGUF file, cut short, or not a chat model at all (a vision projector).
 *
 * Only what can be known without the tensor type table is checked: the file must reach the start
 * of its last tensor's data. That never rejects a good file.
 */
internal object GgufCheck {
    /** Architectures of GGUF files that are not language models (a multimodal projector). */
    private val NOT_A_CHAT_MODEL = setOf("clip")

    private const val MAGIC = 0x46554747 // "GGUF", little-endian
    private const val DEFAULT_ALIGNMENT = 32L
    private const val MAX_STRING = 1L shl 20
    private const val MAX_COUNT = 1L shl 24

    /** What the header says: the architecture, if any. */
    data class Header(val architecture: String?)

    /** A file llama.cpp should not even try: [message] is user-facing. */
    class Problem(val message: String)

    /** The header of the GGUF at [path], or the [Problem] that rules it out. */
    fun inspect(path: String): Pair<Header?, Problem?> {
        val file = File(path)
        if (!file.isFile) return null to Problem("the model file is missing (deleted outside the app?): download it again in the Models tab")
        return try {
            file.inputStream().buffered(64 * 1024).use { read(Reader(it, file.length()), file.length()) }
        } catch (e: Unreadable) {
            // A value type this check does not know (a newer GGUF): leave the verdict to llama.cpp.
            null to null
        } catch (e: EOFException) {
            null to truncated()
        } catch (e: IOException) {
            null to Problem("the model file cannot be read (${e.message})")
        }
    }

    /**
     * The chat template in the header of the GGUF at [path] (`tokenizer.chat_template`), or null
     * when there is none or the file cannot be read. Reads only the key/value header.
     */
    fun chatTemplate(path: String): String? {
        val file = File(path)
        if (!file.isFile) return null
        return try {
            file.inputStream().buffered(64 * 1024).use { input ->
                val r = Reader(input, file.length())
                if (file.length() < 24 || r.u32() != MAGIC) return null
                if (r.u32() < 2) return null
                r.u64()
                val kvs = r.u64()
                if (kvs !in 0..MAX_COUNT) return null
                repeat(kvs.toInt()) {
                    val key = r.string()
                    val type = r.u32()
                    if (key == CHAT_TEMPLATE && type == TYPE_STRING) return r.string()
                    r.skip(type)
                }
                null
            }
        } catch (e: IOException) {
            null
        }
    }

    private const val CHAT_TEMPLATE = "tokenizer.chat_template"

    private fun truncated() = Problem("the model file is incomplete or corrupt: download it again in the Models tab")

    private fun read(r: Reader, size: Long): Pair<Header?, Problem?> {
        if (size < 24 || r.u32() != MAGIC) return null to Problem("not a GGUF model file (corrupt or the wrong file): download it again in the Models tab")
        val version = r.u32()
        if (version < 2) return null to Problem("GGUF version $version is too old for this app: download a current version of the model")
        val tensors = r.u64()
        val kvs = r.u64()
        if (tensors !in 0..MAX_COUNT || kvs !in 0..MAX_COUNT) return null to truncated()
        var architecture: String? = null
        var alignment = DEFAULT_ALIGNMENT
        repeat(kvs.toInt()) {
            val key = r.string()
            val type = r.u32()
            when {
                key == "general.architecture" && type == TYPE_STRING -> architecture = r.string()
                key == "general.alignment" && type == TYPE_U32 -> alignment = r.u32().toLong().takeIf { it > 0 } ?: DEFAULT_ALIGNMENT
                else -> r.skip(type)
            }
        }
        val header = Header(architecture)
        if (architecture in NOT_A_CHAT_MODEL) {
            return header to Problem("this file is not a chat model (architecture '$architecture': a vision projector, which goes with a model): pick the model file itself")
        }
        var lastOffset = -1L
        repeat(tensors.toInt()) {
            r.string()
            val dims = r.u32()
            if (dims !in 0..8) return header to truncated()
            repeat(dims) { r.u64() }
            r.u32()
            lastOffset = maxOf(lastOffset, r.u64())
        }
        val dataStart = (r.position + alignment - 1) / alignment * alignment
        if (tensors > 0 && dataStart + lastOffset >= size) return header to truncated()
        return header to null
    }

    private class Unreadable : IOException("unknown GGUF value type")

    private const val TYPE_U32 = 4
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9

    /**
     * Reads the header through a buffer, counting its position: a tokenizer holds hundreds of
     * thousands of strings, one small read (and seek) each would make this check slow.
     */
    private class Reader(private val input: InputStream, private val size: Long) {
        private val buffer = ByteArray(8)
        var position = 0L
            private set

        private fun readFully(into: ByteArray, length: Int) {
            var done = 0
            while (done < length) {
                val n = input.read(into, done, length - done)
                if (n < 0) throw EOFException()
                done += n
            }
            position += length
        }

        fun u32(): Int {
            readFully(buffer, 4)
            val b = buffer
            return (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
        }

        fun u64(): Long {
            readFully(buffer, 8)
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (buffer[i].toLong() and 0xff)
            return v
        }

        fun string(): String {
            val n = u64()
            if (n !in 0..MAX_STRING) throw EOFException("string of $n bytes")
            val out = ByteArray(n.toInt())
            readFully(out, out.size)
            return String(out, Charsets.UTF_8)
        }

        fun skipBytes(n: Long) {
            if (n < 0 || position + n > size) throw EOFException("skip $n")
            var left = n
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) throw EOFException()
                    left--
                } else {
                    left -= skipped
                }
            }
            position += n
        }

        /** Skips one value of GGUF [type]. */
        fun skip(type: Int) {
            when (type) {
                0, 1, 7 -> skipBytes(1) // u8, i8, bool
                2, 3 -> skipBytes(2) // u16, i16
                4, 5, 6 -> skipBytes(4) // u32, i32, f32
                10, 11, 12 -> skipBytes(8) // u64, i64, f64
                TYPE_STRING -> skipBytes(u64())
                TYPE_ARRAY -> {
                    val itemType = u32()
                    val count = u64()
                    if (count !in 0..MAX_COUNT * 64) throw EOFException("array of $count")
                    val fixed = when (itemType) {
                        0, 1, 7 -> 1L
                        2, 3 -> 2L
                        4, 5, 6 -> 4L
                        10, 11, 12 -> 8L
                        else -> -1L
                    }
                    if (fixed > 0) skipBytes(fixed * count) else repeat(count.toInt()) { skip(itemType) }
                }
                else -> throw Unreadable()
            }
        }
    }
}

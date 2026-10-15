package dev.wckdboy.autobot.shared.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException
import okio.Source
import okio.buffer

/*
 * The `.autobot` export bundle: everything a user owns (sessions, gallery, providers without
 * keys, settings), sealed with a passphrase, readable by the other platform's app.
 *
 *   file    = MAGIC | u32 headerLength | header (UTF-8 JSON, [BackupHeader]) | chunk* (last one final)
 *   chunk   = u32 length | AES-256-GCM(key, nonce, aad, plaintext) (ciphertext ‖ 16-byte tag)
 *   key     = PBKDF2-HMAC-SHA256(passphrase UTF-8, header.salt, header.iterations, 32 bytes)
 *   nonce   = header.noncePrefix (8 bytes) ‖ u32 chunkIndex
 *   aad     = header bytes ‖ u32 chunkIndex ‖ u8 final
 *   stream  = entry* | u16 0                       (the concatenated chunk plaintexts)
 *   entry   = u16 nameLength | name (UTF-8) | u64 size | bytes
 *
 * All integers are big-endian. Binding the header, the index and the final flag into every
 * chunk's AAD makes tampering with the header, reordering, dropping or truncating chunks fail
 * authentication. The first entry is always [MANIFEST] ([BackupManifest] JSON).
 */

/** Crypto primitives, provided by each platform (javax.crypto on the JVM, CryptoKit/CommonCrypto on iOS). */
interface BackupCrypto {
    fun randomBytes(count: Int): ByteArray

    /** PBKDF2-HMAC-SHA256 → 32-byte key. */
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray

    /** AES-256-GCM; returns ciphertext ‖ 16-byte tag. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** Inverse of [seal]; throws when authentication fails. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray
}

@Serializable
class BackupHeader(
    val format: Int = FORMAT,
    val kdf: String = "pbkdf2-hmac-sha256",
    val iterations: Int,
    /** Base64. */
    val salt: String,
    /** Base64, 8 bytes. */
    val noncePrefix: String,
    val chunkSize: Int = CHUNK_SIZE,
    /** e.g. `Autobot 1.3.0 (7)`; informational. */
    val app: String,
    /** `android` or `ios`; informational. */
    val platform: String,
    val createdAt: Long,
) {
    companion object {
        const val FORMAT = 1
        const val CHUNK_SIZE = 1 shl 20
    }
}

/** Raised for a wrong passphrase, a damaged or truncated file, or an unknown format. */
class BackupException(message: String) : IOException(message)

internal val BackupJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private val MAGIC = byteArrayOf('A'.code.toByte(), 'U'.code.toByte(), 'T'.code.toByte(), 'O'.code.toByte(), 'B'.code.toByte(), 'O'.code.toByte(), 'T'.code.toByte(), 1)
private const val MAX_HEADER = 64 * 1024
private const val MAX_NAME = 1024
private const val TAG = 16

/** OWASP 2023 guidance for PBKDF2-HMAC-SHA256. */
const val DEFAULT_ITERATIONS = 600_000

/** Entry name of the manifest, always the first entry. */
const val MANIFEST = "manifest.json"

private fun aad(header: ByteArray, index: Int, final: Boolean): ByteArray =
    Buffer().write(header).writeInt(index).writeByte(if (final) 1 else 0).readByteArray()

private fun nonce(prefix: ByteArray, index: Int): ByteArray = Buffer().write(prefix).writeInt(index).readByteArray()

/** Entry names are relative paths without `..`, absolute parts or backslashes. */
fun isSafeEntryName(name: String): Boolean =
    name.isNotEmpty() && name.length <= MAX_NAME && !name.startsWith("/") && '\\' !in name && '\u0000' !in name &&
        name.split('/').none { it.isEmpty() || it == "." || it == ".." }

/**
 * Writes a bundle to [sink]. Call [entry] for each file (the [MANIFEST] first), then [finish].
 * Key derivation runs in the constructor, so build it off the main thread.
 */
class BackupWriter(
    private val sink: BufferedSink,
    private val crypto: BackupCrypto,
    passphrase: String,
    app: String,
    platform: String,
    createdAt: Long,
    iterations: Int = DEFAULT_ITERATIONS,
    private val chunkSize: Int = BackupHeader.CHUNK_SIZE,
) {
    private val headerBytes: ByteArray
    private val key: ByteArray
    private val noncePrefix = crypto.randomBytes(8)
    private val plain = Buffer()
    private var index = 0
    private var finished = false

    init {
        require(passphrase.isNotEmpty()) { "empty passphrase" }
        val salt = crypto.randomBytes(16)
        val header = BackupHeader(
            iterations = iterations,
            salt = salt.toByteString().base64(),
            noncePrefix = noncePrefix.toByteString().base64(),
            chunkSize = chunkSize,
            app = app,
            platform = platform,
            createdAt = createdAt,
        )
        headerBytes = BackupJson.encodeToString(BackupHeader.serializer(), header).encodeToByteArray()
        key = crypto.deriveKey(passphrase, salt, iterations)
        sink.write(MAGIC).writeInt(headerBytes.size).write(headerBytes)
    }

    fun entry(name: String, bytes: ByteArray) = entry(name, bytes.size.toLong(), Buffer().write(bytes))

    /** Streams [size] bytes from [data] into an entry named [name]. */
    fun entry(name: String, size: Long, data: Source) {
        check(!finished) { "finished" }
        require(isSafeEntryName(name)) { "unsafe entry name: $name" }
        val encoded = name.encodeToByteArray()
        plain.writeShort(encoded.size).write(encoded).writeLong(size)
        val source = data.buffer()
        var left = size
        while (left > 0) {
            val read = source.read(plain, minOf(left, chunkSize.toLong()))
            if (read == -1L) throw BackupException("entry $name ended after ${size - left} of $size bytes")
            left -= read
            flushFull()
        }
        flushFull()
    }

    /** Writes the end marker and the final chunk. The sink is flushed, not closed. */
    fun finish() {
        check(!finished) { "finished" }
        plain.writeShort(0)
        flushFull()
        seal(plain.readByteArray(), final = true)
        finished = true
        sink.flush()
    }

    private fun flushFull() {
        while (plain.size >= chunkSize) seal(plain.readByteArray(chunkSize.toLong()), final = false)
    }

    private fun seal(chunk: ByteArray, final: Boolean) {
        val sealed = crypto.seal(key, nonce(noncePrefix, index), aad(headerBytes, index, final), chunk)
        sink.writeInt(sealed.size).write(sealed)
        index++
    }
}

/**
 * Reads a bundle from [source]. [header] is readable before the passphrase is checked; the key is
 * derived lazily by [entries]. A wrong passphrase fails on the first chunk.
 */
class BackupReader(private val source: BufferedSource, private val crypto: BackupCrypto) {
    private val headerBytes: ByteArray
    val header: BackupHeader

    init {
        val magic = runCatching { source.readByteArray(MAGIC.size.toLong()) }.getOrNull()
        if (magic == null || !magic.contentEquals(MAGIC)) throw BackupException("Not an Autobot export")
        val length = source.readInt()
        if (length <= 0 || length > MAX_HEADER) throw BackupException("Damaged export header")
        headerBytes = source.readByteArray(length.toLong())
        header = runCatching { BackupJson.decodeFromString(BackupHeader.serializer(), headerBytes.decodeToString()) }
            .getOrElse { throw BackupException("Damaged export header") }
        if (header.format != BackupHeader.FORMAT) throw BackupException("Export format ${header.format} is not supported by this version")
        if (header.kdf != "pbkdf2-hmac-sha256" || header.iterations < 100_000 || header.chunkSize !in 1024..(16 shl 20)) {
            throw BackupException("Unsupported export parameters")
        }
    }

    /**
     * Decrypts and calls [onEntry] for each entry in order; [onEntry] must consume exactly `size`
     * bytes of the source it gets (or return early, in which case the rest is skipped).
     */
    fun entries(passphrase: String, onEntry: (name: String, size: Long, data: BufferedSource) -> Unit) {
        val salt = header.salt.decodeBase64()?.toByteArray() ?: throw BackupException("Damaged export header")
        val prefix = header.noncePrefix.decodeBase64()?.toByteArray()?.takeIf { it.size == 8 }
            ?: throw BackupException("Damaged export header")
        val key = crypto.deriveKey(passphrase, salt, header.iterations)
        val plain = ChunkSource(key, prefix).buffer()
        while (true) {
            val nameLength = plain.readShort().toInt() and 0xffff
            if (nameLength == 0) break
            if (nameLength > MAX_NAME) throw BackupException("Damaged export")
            val name = plain.readUtf8(nameLength.toLong())
            if (!isSafeEntryName(name)) throw BackupException("Unsafe entry name in export")
            val size = plain.readLong()
            if (size < 0) throw BackupException("Damaged export")
            val view = BoundedSource(plain, size).buffer()
            onEntry(name, size, view)
            // Drain whatever the consumer left, so the next entry starts at the right byte.
            val discard = Buffer()
            while (view.read(discard, 8192) != -1L) discard.clear()
        }
        if (!plain.exhausted()) throw BackupException("Damaged export: data after the end marker")
    }

    /** Decrypted stream of chunk plaintexts; ends after the final chunk. */
    private inner class ChunkSource(private val key: ByteArray, private val prefix: ByteArray) : Source {
        private var index = 0
        private var done = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (done) return -1
            val length = runCatching { source.readInt() }.getOrElse { throw BackupException("Export is truncated") }
            if (length < TAG || length > header.chunkSize + TAG) throw BackupException("Damaged export")
            val sealed = runCatching { source.readByteArray(length.toLong()) }.getOrElse { throw BackupException("Export is truncated") }
            // The final flag is not stored: a chunk is final iff it opens with final = 1.
            val opened = runCatching { crypto.open(key, nonce(prefix, index), aad(headerBytes, index, false), sealed) }.getOrNull()
                ?: runCatching { crypto.open(key, nonce(prefix, index), aad(headerBytes, index, true), sealed) }.getOrNull()
                    ?.also { done = true }
                ?: throw BackupException(if (index == 0) "Wrong passphrase or damaged export" else "Damaged export")
            if (done && !source.exhausted()) throw BackupException("Damaged export: data after the final chunk")
            index++
            sink.write(opened)
            return opened.size.toLong()
        }

        override fun timeout() = source.timeout()

        override fun close() = Unit
    }

    /** At most [remaining] bytes of [upstream]. */
    private class BoundedSource(private val upstream: BufferedSource, var remaining: Long) : Source {
        override fun read(sink: Buffer, byteCount: Long): Long {
            if (remaining == 0L) return -1
            val read = upstream.read(sink, minOf(byteCount, remaining))
            if (read == -1L) throw BackupException("Export is truncated")
            remaining -= read
            return read
        }

        override fun timeout() = upstream.timeout()

        override fun close() = Unit
    }
}

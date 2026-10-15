package dev.wckdboy.autobot.shared.backup

import dev.wckdboy.autobot.shared.SystemFileSystem
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/*
 * File-based wrappers around [BackupWriter] / [BackupReader] for hosts that do not speak okio
 * (iOS). Paths are plain strings and every entry is a file, so large images never cross the
 * Kotlin/Swift boundary as byte arrays. The format code itself is untouched.
 */

/**
 * [BackupCrypto] for hosts that cannot throw into Kotlin (Swift): [open] returns null when
 * authentication fails instead of throwing.
 */
interface HostBackupCrypto {
    fun randomBytes(count: Int): ByteArray
    fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int): ByteArray
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?
}

internal class HostCryptoAdapter(private val host: HostBackupCrypto) : BackupCrypto {
    override fun randomBytes(count: Int) = host.randomBytes(count)
    override fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int) = host.deriveKey(passphrase, salt, iterations)
    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray) = host.seal(key, nonce, aad, plaintext)
    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray =
        host.open(key, nonce, aad, sealed) ?: throw BackupException("authentication failed")
}

/** Writes a bundle to [path]. Add the [MANIFEST] first, then [finish] (or [abort] on failure). */
class BackupFileWriter @Throws(Throwable::class) constructor(
    path: String,
    crypto: HostBackupCrypto,
    passphrase: String,
    app: String,
    platform: String,
    createdAt: Long,
    iterations: Int = DEFAULT_ITERATIONS,
) {
    private val sink = SystemFileSystem.sink(path.toPath()).buffer()
    private val writer = try {
        BackupWriter(sink, HostCryptoAdapter(crypto), passphrase, app, platform, createdAt, iterations)
    } catch (t: Throwable) {
        sink.close()
        throw t
    }

    @Throws(Throwable::class)
    fun entry(name: String, bytes: ByteArray) = writer.entry(name, bytes)

    /** Streams the file at [path] into an entry named [name]. */
    @Throws(Throwable::class)
    fun entryFromFile(name: String, path: String) {
        val file = path.toPath()
        val size = SystemFileSystem.metadata(file).size ?: throw BackupException("cannot read $path")
        SystemFileSystem.source(file).use { writer.entry(name, size, it) }
    }

    @Throws(Throwable::class)
    fun finish() {
        writer.finish()
        sink.close()
    }

    fun abort() {
        runCatching { sink.close() }
    }
}

/** Receives each entry of a bundle as a temporary file the host may move or must delete. */
fun interface BackupEntryHandler {
    fun onEntry(name: String, size: Long, path: String)
}

/** Reads the bundle at [path]; entries are written one by one into [scratchDir] and handed over. */
class BackupFileReader @Throws(Throwable::class) constructor(path: String, crypto: HostBackupCrypto) {
    private val source = SystemFileSystem.source(path.toPath()).buffer()
    private val reader = try {
        BackupReader(source, HostCryptoAdapter(crypto))
    } catch (t: Throwable) {
        source.close()
        throw t
    }

    val header: BackupHeader get() = reader.header

    /** Decrypts every entry in order. Each temp file is deleted after [handler] returns unless it was moved. */
    @Throws(Throwable::class)
    fun entries(passphrase: String, scratchDir: String, handler: BackupEntryHandler) {
        val dir = scratchDir.toPath()
        SystemFileSystem.createDirectories(dir)
        try {
            var n = 0
            reader.entries(passphrase) { name, size, data ->
                val file = dir / "entry-${n++}"
                SystemFileSystem.sink(file).buffer().use { it.writeAll(data) }
                try {
                    handler.onEntry(name, size, file.toString())
                } finally {
                    SystemFileSystem.delete(file, mustExist = false)
                }
            }
        } finally {
            source.close()
        }
    }
}

/** [BackupManifest.decode] for Swift: a damaged manifest becomes a Swift error instead of a crash. */
object BackupManifests {
    @Throws(Throwable::class)
    fun decode(bytes: ByteArray): BackupManifest = BackupManifest.decode(bytes)
}

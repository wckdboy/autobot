package dev.wckdboy.autobot.shared.backup

import java.io.File
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val iterations = 100_000

    private fun write(passphrase: String = "correct horse", chunk: Int = 1024, crypto: BackupCrypto = JvmBackupCrypto, block: BackupWriter.() -> Unit): ByteArray {
        val out = Buffer()
        BackupWriter(out, crypto, passphrase, "Autobot test", "jvm", 1_700_000_000_000, iterations, chunk).apply {
            block()
            finish()
        }
        return out.readByteArray()
    }

    private fun read(bytes: ByteArray, passphrase: String = "correct horse"): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        BackupReader(Buffer().write(bytes), JvmBackupCrypto).entries(passphrase) { name, size, data -> out[name] = data.readByteArray(size) }
        return out
    }

    private fun failure(block: () -> Unit): String = runCatching(block).exceptionOrNull()?.message ?: "no error"

    @Test
    fun roundTripsManyChunksAndEntries() {
        val big = ByteArray(10_000) { (it * 31).toByte() }
        val bytes = write {
            entry(MANIFEST, BackupManifest.encode(BackupManifest(settings = mapOf("amoled" to "true"))))
            entry("sessions/a.jsonl", "{\"seq\":1}\n".encodeToByteArray())
            entry("gallery/x.png", big)
            entry("empty", ByteArray(0))
        }
        val entries = read(bytes)
        assertEquals(listOf(MANIFEST, "sessions/a.jsonl", "gallery/x.png", "empty"), entries.keys.toList())
        assertArrayEquals(big, entries.getValue("gallery/x.png"))
        assertEquals("true", BackupManifest.decode(entries.getValue(MANIFEST)).settings["amoled"])
        assertEquals(0, entries.getValue("empty").size)
    }

    @Test
    fun consumersMayReadPartially() {
        val bytes = write {
            entry("a", ByteArray(5000) { 1 })
            entry("b", byteArrayOf(9, 9))
        }
        val seen = mutableListOf<String>()
        BackupReader(Buffer().write(bytes), JvmBackupCrypto).entries("correct horse") { name, _, data ->
            seen += name
            if (name == "a") data.readByteArray(10) else assertArrayEquals(byteArrayOf(9, 9), data.readByteArray())
        }
        assertEquals(listOf("a", "b"), seen)
    }

    @Test
    fun wrongPassphraseTamperingAndTruncationAreRejected() {
        val bytes = write { entry("a", ByteArray(4000) { 7 }) }
        assertEquals("Wrong passphrase or damaged export", failure { read(bytes, "nope") })

        val flipped = bytes.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
        assertEquals("Damaged export", failure { read(flipped) })

        // Header tampering (createdAt digit) breaks every chunk's AAD.
        val header = bytes.copyOf().also { b -> val i = String(b, Charsets.ISO_8859_1).indexOf("1700000000000"); b[i] = '2'.code.toByte() }
        assertEquals("Wrong passphrase or damaged export", failure { read(header) })

        // Dropping the final chunk.
        val chunks = Buffer().write(bytes)
        chunks.skip(8)
        val headerLength = chunks.readInt()
        chunks.skip(headerLength.toLong())
        val offsets = mutableListOf<Int>()
        var offset = 12 + headerLength
        while (!chunks.exhausted()) {
            offsets += offset
            val n = chunks.readInt()
            chunks.skip(n.toLong())
            offset += 4 + n
        }
        assertTrue(offsets.size > 2)
        assertEquals("Export is truncated", failure { read(bytes.copyOf(offsets.last())) })
        assertEquals("Not an Autobot export", failure { read("hello".encodeToByteArray()) })
    }

    @Test
    fun unsafeNamesAreRefused() {
        listOf("../x", "/etc/passwd", "a//b", "a/./b", "a\\b", "").forEach { assertFalse(it, isSafeEntryName(it)) }
        assertTrue(isSafeEntryName("sessions/abc.jsonl"))
        assertTrue(failure { write { entry("../evil", ByteArray(1)) } }.contains("unsafe"))
    }

    /** Fixed randomness, so the bundle bytes are reproducible. */
    private object FixedCrypto : BackupCrypto by JvmBackupCrypto {
        private var next = 0

        override fun randomBytes(count: Int): ByteArray = ByteArray(count) { (next++ * 7 + 3).toByte() }
    }

    /**
     * `shared/backup/testvector.autobot` (passphrase `autobot-test-vector`) is decrypted by the iOS
     * tests with CryptoKit/CommonCrypto, proving both platforms read the same format. Regenerate with
     * `UPDATE_BACKUP_VECTOR=1`.
     */
    @Test
    fun testVectorStaysReadable() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile && File(it, "shared").isDirectory }
        val file = File(root, "shared/backup/testvector.autobot")
        if (System.getenv("UPDATE_BACKUP_VECTOR") == "1") {
            val vector = write(passphrase = "autobot-test-vector", chunk = 1024, crypto = FixedCrypto) {
                entry(MANIFEST, BackupManifest.encode(BackupManifest(settings = mapOf("theme_mode" to "LIGHT"), attachments = listOf("img1"))))
                entry("attachments/img1", ByteArray(3000) { (it % 251).toByte() })
            }
            file.parentFile.mkdirs()
            file.writeBytes(vector)
        }
        val entries = read(file.readBytes(), "autobot-test-vector")
        assertEquals("LIGHT", BackupManifest.decode(entries.getValue(MANIFEST)).settings["theme_mode"])
        assertArrayEquals(ByteArray(3000) { (it % 251).toByte() }, entries.getValue("attachments/img1"))
    }
}

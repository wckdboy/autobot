package dev.wckdboy.autobot.shared.backup

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A damaged or crafted export must fail as a [BackupException], before anything big is allocated. */
class BackupGuardsTest {

    /** No encryption, so a test can edit the plaintext; the tag byte carries the final flag as AES-GCM's AAD would. */
    private object PlainCrypto : BackupCrypto {
        override fun randomBytes(count: Int) = ByteArray(count) { 7 }
        override fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int) = ByteArray(32)
        override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray) = plaintext + ByteArray(16) { aad.last() }
        override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
            require(sealed.size >= 16 && sealed.last() == aad.last()) { "bad tag" }
            return sealed.copyOf(sealed.size - 16)
        }
    }

    private fun bundle(crypto: BackupCrypto, vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = Buffer()
        BackupWriter(out, crypto, "pw", "Autobot test", "jvm", 1_700_000_000_000, 100_000, 1024).apply {
            entries.forEach { (name, bytes) -> entry(name, bytes) }
            finish()
        }
        return out.readByteArray()
    }

    private fun readAll(bytes: ByteArray, crypto: BackupCrypto): Map<String, Int> {
        val out = linkedMapOf<String, Int>()
        BackupReader(Buffer().write(bytes), crypto).entries("pw") { name, size, data -> out[name] = data.readByteArray(size).size }
        return out
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int =
        (0..haystack.size - needle.size).first { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    @Test
    fun bogusEntryLengthsAreRejectedBeforeReading() {
        val bytes = bundle(PlainCrypto, "sessions/a.jsonl" to ByteArray(8) { 1 })
        assertEquals(mapOf("sessions/a.jsonl" to 8), readAll(bytes, PlainCrypto))

        // The 8-byte size follows the name: claim an entry far larger than any export.
        val at = indexOf(bytes, "sessions/a.jsonl".encodeToByteArray()) + "sessions/a.jsonl".length
        for (size in listOf(Long.MAX_VALUE, MAX_BACKUP_ENTRY + 1, -1L)) {
            val forged = bytes.copyOf()
            Buffer().writeLong(size).readByteArray().copyInto(forged, at)
            val error = runCatching { readAll(forged, PlainCrypto) }.exceptionOrNull()
            assertTrue("size $size: $error", error is BackupException)
        }
    }

    @Test
    fun everyTruncationIsATypedError() {
        val bytes = bundle(JvmBackupCrypto, "a" to ByteArray(3000) { it.toByte() }, "b" to ByteArray(10))
        // Each cut that reaches the chunks derives the key (PBKDF2): sample the middle, cover both ends.
        val cuts = (0 until 200) + (200 until bytes.size step 37) + (bytes.size - 40 until bytes.size)
        for (cut in cuts) {
            val error = runCatching { readAll(bytes.copyOf(cut), JvmBackupCrypto) }.exceptionOrNull()
            assertTrue("cut at $cut: $error", error is BackupException)
        }
    }
}

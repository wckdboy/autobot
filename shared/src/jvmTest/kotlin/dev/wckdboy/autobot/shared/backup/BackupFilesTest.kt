package dev.wckdboy.autobot.shared.backup

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file-based host wrappers write and read the same bundles as the okio API. */
class BackupFilesTest {
    /** A host crypto that reports failure with null, as a Swift implementation does. */
    private object Host : HostBackupCrypto {
        override fun randomBytes(count: Int) = JvmBackupCrypto.randomBytes(count)
        override fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int) = JvmBackupCrypto.deriveKey(passphrase, salt, iterations)
        override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray) = JvmBackupCrypto.seal(key, nonce, aad, plaintext)
        override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray) =
            runCatching { JvmBackupCrypto.open(key, nonce, aad, sealed) }.getOrNull()
    }

    @Test
    fun filesRoundTripAndEntriesArriveAsTempFiles() {
        val dir = Files.createTempDirectory("backupfiles").toFile()
        val image = File(dir, "img.bin").apply { writeBytes(ByteArray(3_000_000) { (it % 251).toByte() }) }
        val bundle = File(dir, "out.autobot")
        BackupFileWriter(bundle.path, Host, "correct horse battery", "Autobot test", "jvm", 1L, 100_000).apply {
            entry(MANIFEST, BackupManifest.encode(BackupManifest(settings = mapOf("theme_mode" to "LIGHT"))))
            entryFromFile("gallery/a.png", image.path)
            finish()
        }

        val reader = BackupFileReader(bundle.path, Host)
        assertEquals("jvm", reader.header.platform)
        val seen = linkedMapOf<String, ByteArray>()
        val scratch = File(dir, "scratch")
        reader.entries("correct horse battery", scratch.path) { name, size, path ->
            assertEquals(size, File(path).length())
            seen[name] = File(path).readBytes()
        }
        assertEquals(listOf(MANIFEST, "gallery/a.png"), seen.keys.toList())
        assertArrayEquals(image.readBytes(), seen.getValue("gallery/a.png"))
        assertEquals("LIGHT", BackupManifest.decode(seen.getValue(MANIFEST)).settings["theme_mode"])
        assertTrue("temp files are removed", scratch.listFiles().orEmpty().isEmpty())

        // A null from the host's open() surfaces as the usual wrong-passphrase error.
        val error = runCatching { BackupFileReader(bundle.path, Host).entries("wrong passphrase!", scratch.path) { _, _, _ -> } }.exceptionOrNull()
        assertEquals("Wrong passphrase or damaged export", error?.message)
        assertEquals("Damaged export manifest", runCatching { BackupManifests.decode("nope".encodeToByteArray()) }.exceptionOrNull()?.message)
    }
}

package dev.wckdboy.autobot.core.security.panic

import dev.wckdboy.autobot.shared.panic.LockRecord
import dev.wckdboy.autobot.shared.panic.WipeJournal
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The lock record and the wipe journal: two small files in `<dataDir>/panic/`, outside every
 * folder a wipe deletes, readable before the app's data opens. Writes replace the file
 * atomically and are synced to disk before they return, so a counted PIN attempt or a committed
 * wipe survives a kill or a power cut right after.
 */
class PinLockStore(val directory: File) {
    private val lockFile get() = File(directory, LOCK_FILE)
    private val journalFile get() = File(directory, JOURNAL_FILE)

    @Synchronized
    fun read(): LockRecord = LockRecord.decode(readText(lockFile))

    @Synchronized
    fun write(record: LockRecord) {
        if (record == LockRecord()) delete(lockFile) else writeDurably(lockFile, LockRecord.encode(record))
    }

    @Synchronized
    fun clear() = delete(lockFile)

    @Synchronized
    fun journal(): WipeJournal? = WipeJournal.decode(readText(journalFile))

    @Synchronized
    fun writeJournal(journal: WipeJournal) = writeDurably(journalFile, WipeJournal.encode(journal))

    @Synchronized
    fun clearJournal() = delete(journalFile)

    private fun readText(file: File): String? =
        // A half-written temp file is ignored: the rename is the commit.
        runCatching { if (file.isFile) file.readText() else null }.getOrNull()

    private fun delete(file: File) {
        file.delete()
        syncDirectory()
    }

    /** Writes a temp file, syncs it, renames it over [file] and syncs the folder. */
    private fun writeDurably(file: File, text: String) {
        directory.mkdirs()
        val tmp = File(directory, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.encodeToByteArray())
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not replace ${file.name}")
        }
        syncDirectory()
    }

    /** Best effort: makes the rename itself durable where the platform allows opening a folder. */
    private fun syncDirectory() {
        runCatching { java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
    }

    override fun toString(): String = "PinLockStore(<redacted>)"

    companion object {
        const val DIR = "panic"
        private const val LOCK_FILE = "lock.json"
        private const val JOURNAL_FILE = "wipe.json"
    }
}

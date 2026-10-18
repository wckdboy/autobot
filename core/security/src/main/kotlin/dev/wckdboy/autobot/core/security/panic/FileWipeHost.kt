package dev.wckdboy.autobot.core.security.panic

import dev.wckdboy.autobot.shared.panic.LockRecord
import dev.wckdboy.autobot.shared.panic.WipeJournal
import dev.wckdboy.autobot.shared.panic.WipeRunner

/**
 * The Android side of each [WipeRunner] step: the journal and lock record in [store], the files
 * in [files], the keys through [destroyKeys] and [pinKey]. Plain callbacks, so tests run a whole
 * wipe on a temporary folder with stand-in keys.
 */
class FileWipeHost(
    private val files: WipeFiles,
    private val store: PinLockStore,
    private val pinKey: PinMacKey,
    private val destroyKeys: () -> Unit,
    private val stopWork: suspend () -> Unit = {},
    private val deleteDatabases: () -> Unit = {},
    private val finished: () -> Unit = {},
) : WipeRunner.Host {
    override fun saveJournal(journal: WipeJournal) = store.writeJournal(journal)

    override fun destroyKeys() = destroyKeys.invoke()

    override suspend fun stopWork() = stopWork.invoke()

    override fun parkModels() = files.parkModels()

    override fun deleteData(keepModels: Boolean) {
        runCatching { deleteDatabases() }
        files.deleteData(keepModels)
    }

    override fun restore(journal: WipeJournal, landing: LockRecord?) {
        files.restoreModels(journal.keptModels)
        if (landing == null) {
            // First run: no PIN, so the PIN key goes too.
            store.clear()
            runCatching { pinKey.destroy() }
        } else {
            store.write(landing)
        }
    }

    override fun clearJournal() {
        store.clearJournal()
        finished()
    }
}

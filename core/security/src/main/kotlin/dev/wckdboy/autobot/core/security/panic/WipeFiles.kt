package dev.wckdboy.autobot.core.security.panic

import java.io.File

/**
 * The file half of a wipe, on plain folders so tests run it on a temporary tree.
 *
 * Everything under [dataDir] goes except the `panic/` folder (lock record, journal, parked models)
 * and the platform's `lib` link: databases, `files/` (encrypted stores, wrapped keys, secrets,
 * vault, gallery, Studio, workspace, attachments, DataStore), `shared_prefs/`, `no_backup/`,
 * caches and `app_*` folders. [extraDirs] (external cache and files folders) go too. Each item
 * is first renamed into `panic/tomb/` (instant, so nothing can open it any more even if the
 * delete is cut off) and the tomb is then deleted; a later start deletes whatever a kill left.
 *
 * Kept models are moved to `panic/models-kept/` before the delete and back after, with the
 * registry the new install adopts ([KEPT_REGISTRY]).
 */
class WipeFiles(
    private val dataDir: File,
    private val filesDir: File = File(dataDir, "files"),
    private val extraDirs: List<File> = emptyList(),
) {
    val panicDir: File get() = File(dataDir, PinLockStore.DIR)
    val modelsDir: File get() = File(filesDir, MODELS_DIR)
    private val parked: File get() = File(panicDir, PARKED_DIR)
    private val tomb: File get() = File(panicDir, TOMB_DIR)

    /** Items that could not be deleted in the last [deleteData] (0 when everything went). */
    var failed: Int = 0
        private set

    /** Items deleted in the last [deleteData]. */
    var deleted: Int = 0
        private set

    /** Moves the models folder out of the way of [deleteData]. Idempotent. */
    fun parkModels() {
        val models = modelsDir
        if (!models.exists()) return
        if (parked.exists()) {
            // Parked by an earlier, interrupted run: what is in the models folder now is not the user's.
            models.deleteRecursively()
            return
        }
        panicDir.mkdirs()
        // One file system, so a rename; if it ever failed, the models would go with the rest.
        models.renameTo(parked)
    }

    /** Deletes every target; models only when not [keepModels] (they are parked already otherwise). */
    fun deleteData(keepModels: Boolean) {
        deleted = 0
        failed = 0
        if (!keepModels) parked.deleteRecursively()
        tomb.mkdirs()
        val targets = buildList {
            dataDir.listFiles().orEmpty().filterTo(this) { it.name != PinLockStore.DIR && it.name != "lib" }
            if (!filesDir.absolutePath.startsWith(dataDir.absolutePath)) add(filesDir)
            extraDirs.filterTo(this) { it.exists() }
        }
        var n = tomb.listFiles().orEmpty().size
        for (target in targets) {
            val dest = File(tomb, "${n++}-${target.name}")
            if (!target.renameTo(dest)) {
                if (target.deleteRecursively()) deleted++ else failed++
                continue
            }
            deleted++
        }
        if (!tomb.deleteRecursively()) failed++
    }

    /** Puts parked models back and leaves [registry] (the kept models' rows) for the new install. */
    fun restoreModels(registry: String?) {
        if (parked.exists()) {
            filesDir.mkdirs()
            modelsDir.deleteRecursively()
            parked.renameTo(modelsDir)
        }
        if (registry != null && modelsDir.isDirectory) File(modelsDir, KEPT_REGISTRY).writeText(registry)
    }

    /** Whether anything a wipe would delete is left (tests and the resume check). */
    fun leftovers(keepModels: Boolean): List<File> = buildList {
        dataDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val inPanic = file.startsWith(panicDir) && !file.startsWith(tomb) && !file.startsWith(parked)
            val keptModel = keepModels && file.startsWith(modelsDir)
            if (!inPanic && !keptModel) add(file)
        }
        extraDirs.forEach { dir -> dir.walkTopDown().filterTo(this) { it.isFile } }
    }

    companion object {
        const val MODELS_DIR = "models"
        private const val PARKED_DIR = "models-kept"
        private const val TOMB_DIR = "tomb"

        /** Left in the models folder after a wipe that kept models; `ModelLibrary` adopts and deletes it. */
        const val KEPT_REGISTRY = ".kept-registry.json"
    }
}

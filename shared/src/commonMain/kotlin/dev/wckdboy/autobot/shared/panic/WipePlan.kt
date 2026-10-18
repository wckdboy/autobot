package dev.wckdboy.autobot.shared.panic

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the app lands after a wipe. */
@Serializable
enum class WipeLanding {
    /** The welcome screen of a new install (failed unlocks, the manual panic wipe). */
    FIRST_RUN,

    /**
     * An empty app that looks used: unlocked once, past the welcome, the duress PIN as its PIN.
     * What the duress PIN opens.
     */
    QUIET,
}

/**
 * The steps of a wipe, in the order they run. Every step after [COMMIT] is idempotent, so a wipe
 * interrupted by a kill is finished by running the remaining steps again on the next start,
 * before anything opens the app's data.
 */
@Serializable
enum class WipeStep {
    /** The journal is written and synced: from here on the wipe completes, whatever happens. */
    COMMIT,

    /**
     * Keystore / Keychain keys and wrapped key files are deleted (and no new key may be created
     * until the wipe finishes): every byte of ciphertext left behind is now noise.
     */
    DESTROY_KEYS,

    /** Agents, downloads, image jobs and engines stop; databases close. Only in the process that started the wipe. */
    STOP_WORK,

    /** Downloaded models are moved aside so the delete leaves them (when they are kept). */
    PARK_MODELS,

    /** Database, encrypted stores, secrets, vault, memory, gallery, Studio, workspace, attachments, settings, caches and temp files are deleted. */
    DELETE_DATA,

    /** Kept models move back and are listed for the new install to adopt; the lock record is reset for the landing. */
    RESTORE,

    /** The journal is deleted: the wipe is over. */
    FINISH,
}

/** What a wipe destroys, by kind. Platforms map each to their paths. */
@Serializable
enum class WipeTarget {
    KEYS, SECRETS, DATABASE, ENCRYPTED_STORES, VAULT, MEMORY, GALLERY, STUDIO, WORKSPACE, ATTACHMENTS, SETTINGS, CACHES, TEMP, MODELS,
}

/**
 * The crash-safety journal of a wipe, persisted next to the lock record. [done] is the last step
 * that completed. [keptModels] is the platform's registry of downloaded models (catalog metadata
 * only), carried across the wipe so the new install can use them; [carriedPin] becomes the PIN of
 * a [WipeLanding.QUIET] landing. Nothing here names a reason for the wipe.
 */
@Serializable
data class WipeJournal(
    val landing: WipeLanding,
    val keepModels: Boolean,
    val done: WipeStep? = null,
    val keptModels: String? = null,
    val carriedPin: PinVerifier? = null,
    val biometrics: Boolean = true,
) {
    /** The journal after [step] completed. */
    fun after(step: WipeStep): WipeJournal = copy(done = step)

    override fun toString(): String = "WipeJournal(landing=$landing, keepModels=$keepModels, done=$done)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(journal: WipeJournal): String = json.encodeToString(serializer(), journal)

        /** Null for no journal. An unreadable journal still means a wipe was committed: it is finished keeping nothing. */
        fun decode(text: String?): WipeJournal? {
            if (text == null) return null
            return runCatching { json.decodeFromString(serializer(), text) }.getOrNull() ?: WipeJournal(WipeLanding.FIRST_RUN, keepModels = false)
        }
    }
}

/** The order of a wipe and how an interrupted one resumes. */
object WipePlan {

    /** Every step of a wipe started in this process. */
    val Steps: List<WipeStep> = WipeStep.entries

    /** What is destroyed. Models only when [keepModels] is false. */
    fun targets(keepModels: Boolean): List<WipeTarget> =
        WipeTarget.entries.filter { it != WipeTarget.MODELS || !keepModels }

    /** A new journal for a wipe that is about to start. */
    fun journal(landing: WipeLanding, keepModels: Boolean, keptModels: String? = null, carriedPin: PinVerifier? = null, biometrics: Boolean = true): WipeJournal {
        require(landing == WipeLanding.QUIET || carriedPin == null) { "Only a quiet landing carries a PIN" }
        return WipeJournal(landing, keepModels, null, keptModels.takeIf { keepModels }, carriedPin, biometrics)
    }

    /**
     * The steps still to run. A wipe started in this process ([resumed] false) runs all of them; one
     * found at start-up ([resumed] true) repeats every idempotent step from [WipeStep.DESTROY_KEYS]
     * on, whatever [WipeJournal.done] says (a step may have been cut off half way), except
     * [WipeStep.STOP_WORK]: nothing runs yet.
     */
    fun remaining(journal: WipeJournal, resumed: Boolean): List<WipeStep> {
        if (!resumed) {
            val from = journal.done?.let { Steps.indexOf(it) + 1 } ?: 0
            return Steps.drop(from)
        }
        return Steps.filter { it != WipeStep.COMMIT && it != WipeStep.STOP_WORK }
            .filter { it != WipeStep.PARK_MODELS || journal.keepModels }
    }

    /** The lock record a finished wipe leaves for its landing. */
    fun landingRecord(journal: WipeJournal): LockRecord? = when (journal.landing) {
        WipeLanding.FIRST_RUN -> null
        WipeLanding.QUIET -> journal.carriedPin?.let { LockRecord(pin = it, biometrics = journal.biometrics, quietStart = true) }
    }
}

/**
 * Runs a wipe's steps in [WipePlan] order, saving the journal after each. Android uses it as is;
 * iOS walks [WipePlan.remaining] itself (its participants are async Swift).
 */
class WipeRunner(private val host: Host) {

    /** The platform side of each step. */
    interface Host {
        /** Writes [journal] durably (atomic replace + fsync). */
        fun saveJournal(journal: WipeJournal)
        fun destroyKeys()
        suspend fun stopWork()
        fun parkModels()
        fun deleteData(keepModels: Boolean)
        fun restore(journal: WipeJournal, landing: LockRecord?)
        fun clearJournal()
    }

    /**
     * Runs [journal]'s remaining steps up to and including [through]; [resumed] for a wipe found at
     * start-up. Stopping after [WipeStep.DESTROY_KEYS] and restarting the process is how a wipe
     * returns to the screen at once: the keys are gone, the next start deletes the rest before
     * anything opens. Returns the steps run.
     */
    suspend fun run(journal: WipeJournal, resumed: Boolean, through: WipeStep = WipeStep.FINISH): List<WipeStep> {
        var current = journal
        val ran = mutableListOf<WipeStep>()
        val steps = WipePlan.remaining(journal, resumed)
        for (step in steps.take(steps.indexOf(through).let { if (it < 0) steps.size else it + 1 })) {
            // One failing step must not leave the rest of the data behind; a later start retries.
            runCatching {
                when (step) {
                    WipeStep.COMMIT -> Unit
                    WipeStep.DESTROY_KEYS -> host.destroyKeys()
                    WipeStep.STOP_WORK -> host.stopWork()
                    WipeStep.PARK_MODELS -> if (current.keepModels) host.parkModels()
                    WipeStep.DELETE_DATA -> host.deleteData(current.keepModels)
                    WipeStep.RESTORE -> host.restore(current, WipePlan.landingRecord(current))
                    WipeStep.FINISH -> host.clearJournal()
                }
            }.onFailure { if (step == WipeStep.COMMIT) throw it }
            ran += step
            current = current.after(step)
            // COMMIT is the first durable write; FINISH removed the journal.
            if (step != WipeStep.FINISH) runCatching { host.saveJournal(current) }.onFailure { if (step == WipeStep.COMMIT) throw it }
        }
        return ran
    }
}

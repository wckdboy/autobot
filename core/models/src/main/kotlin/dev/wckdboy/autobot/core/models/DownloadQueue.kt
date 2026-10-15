package dev.wckdboy.autobot.core.models

/** Live progress of the running download. */
data class DownloadProgress(val id: String, val title: String, val downloaded: Long, val total: Long, val bytesPerSecond: Long) {
    val fraction: Float get() = if (total <= 0) 0f else (downloaded.toFloat() / total).coerceIn(0f, 1f)

    /** Seconds left at the current speed; null while the speed is unknown. */
    val etaSeconds: Long? get() = DownloadQueue.eta(total - downloaded, bytesPerSecond)
}

/** Why the queue is not moving, in the order the panel reports them. */
enum class DownloadBlock(val label: String) {
    /** The kill switch (Offline mode in Privacy Center). Downloads go through it like every request. */
    KILL_SWITCH("network is off · kill switch in privacy center"),
    NO_NETWORK("no network · continues when you're back online"),
    WAITING_FOR_WIFI("waiting for wi-fi · wi-fi only is on"),
    LOW_STORAGE("not enough storage · free some space, then resume"),
}

/** What the queue depends on outside itself. */
data class DownloadEnvironment(
    val networkOff: Boolean = false,
    val connected: Boolean = true,
    val metered: Boolean = false,
    val wifiOnly: Boolean = false,
)

/** The downloads panel: what runs, what waits and why. */
data class DownloadsState(
    val active: DownloadProgress? = null,
    /** Waiting their turn, oldest first (the order they run in). */
    val queued: List<InstalledModel> = emptyList(),
    val paused: List<InstalledModel> = emptyList(),
    val block: DownloadBlock? = null,
    val wifiOnly: Boolean = false,
    val metered: Boolean = false,
) {
    val isEmpty: Boolean get() = active == null && queued.isEmpty() && paused.isEmpty()
}

/**
 * The rules of the download queue, kept apart from Android so they can be tested: the queue is
 * the model registry itself (QUEUED rows, oldest first), so it survives process death; after a
 * restart an interrupted download simply queues again and continues from its `*.part` file.
 */
object DownloadQueue {
    /** Bigger than this on a metered network gets a warning before it starts. */
    const val METERED_WARNING_BYTES = 1L shl 30

    /** Error kept on a model the kill switch stopped; it resumes when the network comes back on. */
    const val KILL_SWITCH_ERROR = "Network is off (kill switch). Downloads continue when you allow a network mode in Privacy Center."
    const val LOW_STORAGE_PREFIX = "Not enough storage"

    /** The next model to download. */
    fun next(models: List<InstalledModel>): InstalledModel? = queued(models).firstOrNull()

    fun queued(models: List<InstalledModel>): List<InstalledModel> =
        models.filter { it.status == ModelStatus.QUEUED }.sortedWith(compareBy({ it.addedAt }, { it.id }))

    /**
     * After the process died nothing is running: a download that was in flight queues again (it
     * resumes from its part file). Returns the models to change.
     */
    fun recover(models: List<InstalledModel>): List<InstalledModel> = models.filter { it.status == ModelStatus.DOWNLOADING }

    /** Models paused by the kill switch, to queue again when a network mode is allowed. */
    fun blockedByKillSwitch(models: List<InstalledModel>): List<InstalledModel> =
        models.filter { it.status == ModelStatus.PAUSED && it.error == KILL_SWITCH_ERROR }

    /** Why nothing can download right now, or null. */
    fun block(env: DownloadEnvironment): DownloadBlock? = when {
        env.networkOff -> DownloadBlock.KILL_SWITCH
        !env.connected -> DownloadBlock.NO_NETWORK
        env.wifiOnly && env.metered -> DownloadBlock.WAITING_FOR_WIFI
        else -> null
    }

    /** Why [model] is paused, for the panel. */
    fun pausedReason(model: InstalledModel): DownloadBlock? = when {
        model.status != ModelStatus.PAUSED -> null
        model.error == KILL_SWITCH_ERROR -> DownloadBlock.KILL_SWITCH
        model.error?.startsWith(LOW_STORAGE_PREFIX) == true -> DownloadBlock.LOW_STORAGE
        else -> null
    }

    /** Whether to warn before [bytes] go over a metered network. */
    fun warnMetered(bytes: Long, env: DownloadEnvironment): Boolean =
        bytes > METERED_WARNING_BYTES && env.connected && env.metered && !env.wifiOnly

    /** Seconds to move [remaining] bytes at [bytesPerSecond]; null when unknown. */
    fun eta(remaining: Long, bytesPerSecond: Long): Long? =
        if (bytesPerSecond <= 0 || remaining < 0) null else (remaining + bytesPerSecond - 1) / bytesPerSecond

    /** "45 s", "12 min", "1 h 05 min". */
    fun formatEta(seconds: Long): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${(seconds + 59) / 60} min"
        else -> "${seconds / 3600} h %02d min".format((seconds % 3600) / 60)
    }

    /** Exponentially smoothed speed, so the ETA does not jump with every packet. */
    fun smooth(previous: Long, sample: Long): Long = if (previous <= 0) sample else (previous * 0.7 + sample * 0.3).toLong()

    fun state(models: List<InstalledModel>, progress: DownloadProgress?, env: DownloadEnvironment): DownloadsState {
        val active = progress?.takeIf { p -> models.any { it.id == p.id && it.status == ModelStatus.DOWNLOADING } }
        return DownloadsState(
            active = active,
            queued = queued(models).filter { it.id != active?.id },
            paused = models.filter { it.status == ModelStatus.PAUSED || it.status == ModelStatus.FAILED }.sortedBy { it.addedAt },
            block = block(env).takeIf { active != null || queued(models).isNotEmpty() },
            wifiOnly = env.wifiOnly,
            metered = env.metered,
        )
    }
}

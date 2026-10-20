package dev.wckdboy.autobot.core.models

/** Where the first-run model card is: what it shows and its one action. */
enum class FirstModelStep {
    /** A chat model already runs here and there are chats: no card. */
    HIDDEN,

    /** Nothing fits this phone (or nothing is free to download): the card says so, no action. */
    NONE_FITS,

    /** The pick, its size and one button: Download. */
    OFFER,

    /** Waiting for its turn or for wi-fi. */
    QUEUED,
    DOWNLOADING,
    PAUSED,

    /** The download failed: why, and Retry. */
    FAILED,

    /** Downloaded: Start a chat. */
    READY,
}

/**
 * The first-run model: one chat model that fits this phone, offered at the top of Models (where
 * "Set up this phone" lands) so a first chat is three taps away: Set up, Download, Start a chat.
 * The same rules and words on Android and iOS.
 */
object FirstModel {
    /**
     * Best first: strong tool calling (agent sessions), then smaller all-rounders down to the one
     * that runs on almost anything.
     */
    val LADDER: List<String> = listOf(
        "catalog:qwen3-4b-2507-q4_0",
        "catalog:qwen3.5-2b-q4_0",
        "catalog:qwen3.5-0.8b-q4_0",
    )

    const val TITLE = "Start here"
    const val OFFLINE = "It runs offline: what you type stays on this phone."

    /** Offline mode (the default) is on: the one sentence that explains it, and the one tap past it. */
    const val OFFLINE_FIRST = "Autobot starts offline. The download needs the network once; the model then runs offline."
    const val ALLOW_AND_DOWNLOAD = "Allow network and download"

    /** Ready, with the network allowed for the download: the way back. */
    const val BACK_OFFLINE = "Turn offline mode back on"
    const val NONE_FITS = "No chat model fits this phone's memory. Pair a PC or add an API key in Remote."
    const val DOWNLOAD = "Download"
    const val PAUSE = "Pause"
    const val RESUME = "Resume"
    const val RETRY = "Retry"
    const val START_CHAT = "Start a chat"
    const val OTHER_MODELS = "Other models"

    /**
     * The ladder's first model that fits well (RAM, and twice its size free on disk), else the
     * first that fits at all; null when none does.
     */
    fun pickId(device: DeviceCapabilities, plans: List<ModelPlan> = Catalog.chatModels): String? {
        val ladder = LADDER.mapNotNull { id -> plans.firstOrNull { it.id == id } }
        fun fits(plan: ModelPlan, good: Boolean): Boolean {
            val fit = device.fit(plan.kind, plan.totalBytes, plan.engine)
            val room = device.freeStorageBytes <= 0 || device.freeStorageBytes >= plan.totalBytes * 2
            return room && if (good) fit == Fit.GOOD else fit == Fit.GOOD || fit == Fit.TIGHT
        }
        return (ladder.firstOrNull { fits(it, good = true) } ?: ladder.firstOrNull { fits(it, good = false) })?.id
    }

    /**
     * The card's step for the picked model ([status] its `ModelStatus` name, null when not
     * added), given whether some local chat model is ready ([localChat]) and any chat exists
     * ([hasChats]). A phone that already chats on its own model needs no card.
     */
    fun step(picked: Boolean, status: String?, localChat: Boolean, hasChats: Boolean): FirstModelStep {
        if (localChat && hasChats) return FirstModelStep.HIDDEN
        if (!picked) return if (localChat) FirstModelStep.HIDDEN else FirstModelStep.NONE_FITS
        return when (status) {
            null -> if (localChat) FirstModelStep.HIDDEN else FirstModelStep.OFFER
            ModelStatus.QUEUED.name -> FirstModelStep.QUEUED
            ModelStatus.DOWNLOADING.name -> FirstModelStep.DOWNLOADING
            ModelStatus.PAUSED.name -> FirstModelStep.PAUSED
            ModelStatus.FAILED.name -> FirstModelStep.FAILED
            ModelStatus.READY.name -> FirstModelStep.READY
            else -> FirstModelStep.OFFER
        }
    }

    /** "2.3 GB · fits in 8.0 GB of RAM". */
    fun sizeLine(sizeBytes: Long, ramBytes: Long): String =
        if (ramBytes > 0) "${formatBytes(sizeBytes)} · fits in ${formatBytes(ramBytes)} of RAM" else formatBytes(sizeBytes)

    /** "1.1 GB of 2.3 GB · 47%", with what it waits for when not moving. */

    fun progressLine(step: FirstModelStep, downloaded: Long, total: Long): String {
        val percent = if (total > 0) (downloaded.coerceIn(0, total) * 100 / total).toInt() else 0
        val amount = "${formatBytes(downloaded.coerceAtLeast(0))} of ${formatBytes(total)} · $percent%"
        return when (step) {
            FirstModelStep.QUEUED -> if (downloaded > 0) "$amount · waiting" else "Waiting to start"
            FirstModelStep.PAUSED -> "$amount · paused, resumes where it stopped"
            else -> amount
        }
    }

    /** The ready line: what the model is and that it is local. */
    fun readyLine(title: String): String = "Downloaded. Chats with $title stay on this phone."
}

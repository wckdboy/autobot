package dev.wckdboy.autobot.shared.panic

/**
 * The words of panic protection, shared so both apps say exactly the same thing: the Settings
 * rows and their confirmations, the lock screen, and the incognito image marks. Plain text, no
 * formatting; the apps print it with their own components.
 */
object PanicCopy {
    // Settings › Privacy & security

    const val PIN_TITLE = "App PIN"
    const val PIN_INFO = "A PIN of 4 to 12 digits opens Autobot instead of the device credential. The wipe and the duress PIN need it."
    const val BIOMETRICS_TITLE = "Unlock with biometrics"
    const val BIOMETRICS_INFO =
        "Your fingerprint or face may open Autobot until a wrong PIN is entered. Biometrics never count as an attempt and never trigger the duress PIN."
    const val WIPE_TITLE = "Wipe after failed unlocks"
    const val WIPE_INFO = "After that many wrong PINs in a row, Autobot erases everything and starts over as a new install."
    const val DURESS_TITLE = "Duress PIN"
    const val DURESS_INFO = "A second PIN that looks like a normal unlock but silently erases everything and opens an empty Autobot."
    const val MODELS_TITLE = "Erase models too"
    const val MODELS_INFO = "Downloaded models hold nothing of yours and are kept by default. Turn on to delete them with the rest."

    /** What a wipe destroys, said the same way everywhere. */
    const val DESTROYED =
        "The encryption keys are destroyed first, then every chat, memory note, vault file, gallery image, Studio project and recipe, " +
            "the workspace, providers and API keys, settings and caches."

    const val CANNOT_UNDO = "This cannot be undone."

    fun modelsLine(eraseModels: Boolean): String =
        if (eraseModels) "Downloaded models are deleted too." else "Downloaded models are kept."

    /** `Off`, `After 10`. */
    fun wipeValue(attempts: Int): String = if (attempts <= 0) "Off" else "After $attempts"

    fun onOff(on: Boolean): String = if (on) "On" else "Off"

    fun wipeConfirmTitle(attempts: Int): String = "Wipe after $attempts failed unlocks?"

    fun wipeConfirmBody(attempts: Int, eraseModels: Boolean): String =
        "After $attempts wrong PINs in a row Autobot erases itself and returns to its welcome screen. $DESTROYED " +
            "${modelsLine(eraseModels)} $CANNOT_UNDO You are warned when ${UnlockPolicy.WARN_REMAINING} attempts are left."

    const val DURESS_CONFIRM_TITLE = "Set a duress PIN?"

    fun duressConfirmBody(eraseModels: Boolean): String =
        "Entering the duress PIN on the lock screen looks exactly like unlocking, but Autobot first erases everything and then opens empty, " +
            "with the duress PIN as its only PIN. $DESTROYED ${modelsLine(eraseModels)} $CANNOT_UNDO It works on the PIN pad only, never with biometrics."

    const val CURRENT_PIN = "Current PIN"
    const val NEW_PIN = "New PIN"
    const val CONFIRM_PIN = "Repeat PIN"
    const val DURESS_PIN = "Duress PIN"
    const val CONFIRM_DURESS_PIN = "Repeat duress PIN"
    const val TURN_ON = "Turn on"
    const val TURN_OFF = "Turn off"
    const val SET_PIN = "Set PIN"
    const val CHANGE_PIN = "Change PIN"
    const val REMOVE_PIN = "Remove PIN"
    const val REMOVE_PIN_BODY = "The duress PIN and the wipe after failed unlocks are turned off with it. The device credential opens Autobot again."
    const val CANCEL = "Cancel"

    // Lock screen

    const val LOCKED = "Locked"
    const val ENTER_PIN = "Enter your PIN"
    const val UNLOCK = "Unlock"
    const val WRONG_PIN = "Wrong PIN"

    /** `2 attempts left before Autobot erases everything`. */
    fun attemptsLeft(remaining: Int): String =
        if (remaining == 1) "1 attempt left before Autobot erases everything" else "$remaining attempts left before Autobot erases everything"

    /** `Try again in 0:30`. */
    fun tryAgainIn(millis: Long): String {
        val seconds = (millis + 999) / 1000
        val rest = seconds % 60
        return "Try again in ${seconds / 60}:${if (rest < 10) "0" else ""}$rest"
    }

    // Incognito images

    const val INCOGNITO_IMAGES_TITLE = "Incognito"
    const val INCOGNITO_IMAGES_INFO = "Results stay in memory only and never reach the gallery or the Studio. Save the ones you want to keep."
    const val NOT_SAVED = "Not saved"
    const val SAVE = "Save"
    const val SAVED = "Saved"
}

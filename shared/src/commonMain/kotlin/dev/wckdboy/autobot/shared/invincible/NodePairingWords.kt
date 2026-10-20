package dev.wckdboy.autobot.shared.invincible

/** The words of pairing a node, the same on Android and iOS (one name for it: "Pair a node"). */
object NodePairingWords {
    const val TITLE = "Pair a node"
    const val HOW = "On the computer running Invincible, run `invincible pair`, then scan its QR code with the Camera app or paste the code here."
    const val CODE = "Pairing code"
    const val KEY = "Node key"
    const val COMPARE = "match it with `invincible pair`"
    const val NAME = "This phone's name on the node"
    const val NOTE = "end-to-end encrypted · no account"
    const val NOT_A_CODE = "Not an Invincible pairing code (invincible://pair?…)"
    const val NAME_MISSING = "Name this phone"
    const val PAIR = "Pair"

    /** Remote, with no node yet. */
    const val REMOTE_ROW = "Pair a node"
    const val REMOTE_ROW_DETAIL = "Invincible on your computer"

    /** A node's row in Providers: where it is, and that it is paired (it has no API key). */
    fun row(addresses: List<String>): String = (addresses.take(2) + "paired").joinToString(" · ")
}

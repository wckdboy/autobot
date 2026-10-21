package dev.wckdboy.autobot.shared.memory

/** The Memory screen's words, the same on Android and iOS. */
object MemoryWords {
    const val USE_IN_CHATS = "Use in chats"
    const val USE_INFO = "The agent recalls relevant notes and can save new ones. Notes stay encrypted on this phone."
    const val SEARCH = "Search notes or #tag"
    const val ADD = "Add note"
    const val DELETE_ALL = "Delete all notes"
    const val NO_NOTES = "No notes yet."
    const val NO_MATCH = "No notes match."

    /** `Notes · 3 / 500`. */
    fun count(total: Int): String = "Notes · $total / ${MemoryRules.MAX_NOTES}"

    /** The empty list's one line. */
    fun empty(total: Int): String = if (total == 0) NO_NOTES else NO_MATCH
}

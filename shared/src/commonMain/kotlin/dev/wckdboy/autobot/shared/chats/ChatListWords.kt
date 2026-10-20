package dev.wckdboy.autobot.shared.chats

/** The chat list's words, the same on Android and iOS. A chat is a "chat" everywhere. */
object ChatListWords {
    const val TITLE = "Chats"
    const val SEARCH = "Search chats"
    const val NEW = "New"
    const val NEW_INCOGNITO = "New incognito chat"
    const val NO_MODEL = "No model yet."
    const val ADD_PROVIDER = "Add provider"

    /** The line under the title: only when something runs. */
    fun live(live: Int): String? = if (live > 0) "$live live" else null

    fun selected(count: Int): String = "$count selected"

    /** One line for an empty list: what is empty, no instructions. */
    fun empty(searching: Boolean, filter: ChatFilter): String = when {
        searching -> "No chats match that search."
        filter == ChatFilter.Archived -> "No archived chats."
        filter == ChatFilter.Pinned -> "Nothing pinned."
        filter is ChatFilter.Folder -> "This folder is empty."
        filter == ChatFilter.Incognito -> "No incognito chats."
        else -> "No chats yet."
    }
}

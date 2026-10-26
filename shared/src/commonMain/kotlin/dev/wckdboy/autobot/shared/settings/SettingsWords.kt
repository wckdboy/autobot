package dev.wckdboy.autobot.shared.settings

import dev.wckdboy.autobot.shared.cascade.CascadePolicy

/**
 * The Settings tab's explanations, behind each row's ⓘ, the same on Android and iOS. Rows carry
 * a one- or two-word title; the why is one tap away, never a paragraph under the row.
 */
object SettingsWords {
    const val INCOGNITO = "New chats are kept in memory only, never written to disk."
    const val REDACT = "Names, emails, numbers, addresses and memory facts become placeholders before a cloud model sees them. Answers show the real values."
    const val PANIC_WIPE = "Erases every key and all data on this phone, then closes the app."
    const val MEMORY = "The agent recalls and saves notes, kept encrypted on this phone."
    const val WEB_SEARCH = "Used by chats with agent tools on. Only the query is sent, through your network route."
    const val OFFLINE_BLOCKS_SEARCH = "Offline mode blocks searches"
    const val QUOTA_FALLBACK = "When a provider's usage limit is used up, answer once with its helper model, else the on-device model, and say so."
    const val GENERATIVE_UI = "Lets the on-device agent show forms and choices in phone chats."
    const val DELETE_ALL_TITLE = "Delete all chats?"
    const val DELETE_ALL_TEXT = "Every chat is removed from this phone. Folders, memory and files stay."
    const val DELETE_ALL_CONFIRM = "Delete all"

    /** What "Phone first" covers. */
    const val CASCADE_SCOPE = "For plain chats on a cloud model or your node; chats can choose their own."

    /** Heads the options as they work on a cloud model's chats, then on the user's own node's. */
    const val CASCADE_CLOUD = "With a cloud model:"
    const val CASCADE_NODE = "With your node:"

    /** "Phone first"'s ⓘ: each option on its line, with a cloud model and then with a node, then what it covers. */
    val cascade: String =
        CASCADE_CLOUD + "\n" + CascadePolicy.entries.joinToString("\n") { "${it.title}: ${it.detail}" } + "\n\n" +
            CASCADE_NODE + "\n" + CascadePolicy.entries.joinToString("\n") { "${it.title}: ${it.nodeDetail}" } + "\n\n" +
            CASCADE_SCOPE
}

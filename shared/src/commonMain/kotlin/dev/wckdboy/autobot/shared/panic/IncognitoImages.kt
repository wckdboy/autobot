package dev.wckdboy.autobot.shared.panic

/**
 * Incognito image runs: images from an incognito chat (`generate_image`) or from the Image screen
 * with incognito on stay in process memory, under ids with [ID_PREFIX], and never reach the
 * gallery or the Studio library unless the user taps Save. Both apps use these rules.
 */
object IncognitoImages {
    /** Ids of unsaved, in-memory images (in place of a gallery id in tool results and the UI). */
    const val ID_PREFIX = "incognito-img-"

    /** Incognito chats (and their subagents, whose ids start with the parent's). */
    private const val SESSION_PREFIX = "incognito-"

    fun isUnsaved(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** Whether a tool call in [sessionId] runs in an incognito chat. */
    fun isIncognitoSession(sessionId: String): Boolean = sessionId.startsWith(SESSION_PREFIX)

    /** What `generate_image` tells the model about where incognito results went. */
    fun toolNote(count: Int): String =
        "They are shown to the user in this incognito chat only and are not saved to the gallery; the user can save ${if (count == 1) "it" else "them"}."
}

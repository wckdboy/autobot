package dev.wckdboy.autobot.shared.vault

/** The Vault screen's words, the same on Android and iOS. */
object VaultWords {
    const val AGENT_SEARCH = "Agent search"
    const val AGENT_SEARCH_INFO = "The agent can look up passages in your files (vault_search). Files are indexed on this phone, encrypted, never uploaded."
    const val EMBEDDING_MODEL = "Embedding model"
    const val NO_MODEL = "None"
    const val SEARCH = "Search your files"
    const val ADD = "Add files"
    const val DELETE_ALL = "Delete all documents"
    const val NO_MATCH = "No passages match."
    const val REINDEX = "Re-index"

    /** `Documents · 2 · 40 / 50000 passages`. */
    fun count(documents: Int, passages: Int): String = "Documents · $documents · $passages / ${VaultRules.MAX_CHUNKS} passages"

    /** Files indexed with another model, and the one tap that fixes it ([REINDEX]). */
    fun stale(count: Int): String = if (count == 1) "1 document uses another embedding model." else "$count documents use another embedding model."

    /**
     * The empty list's one line. Files need an embedding model first: without one there is no
     * "+" to tap, so the line says what is missing instead.
     */
    fun empty(hasModel: Boolean): String = if (hasModel) "No files yet." else "No files yet. Files need an embedding model."
}

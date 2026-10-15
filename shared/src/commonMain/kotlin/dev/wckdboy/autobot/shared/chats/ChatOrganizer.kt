package dev.wckdboy.autobot.shared.chats

/** How the chat list is ordered. */
enum class ChatSort(val label: String) {
    /** Most recent activity first (the default). */
    RECENT("Recent"),

    /** Newest chat first. */
    CREATED("Created"),

    /** By title, A to Z. */
    NAME("Name"),
    ;

    companion object {
        fun parse(name: String?): ChatSort = entries.firstOrNull { it.name == name } ?: RECENT
    }
}

/**
 * Which chats the list shows. Archived chats only appear under [Archived]; [key] is a stable string
 * form (for saved state and Swift).
 */
sealed interface ChatFilter {
    val key: String

    data object All : ChatFilter {
        override val key = "all"
    }

    data object Pinned : ChatFilter {
        override val key = "pinned"
    }

    data object Incognito : ChatFilter {
        override val key = "incognito"
    }

    data object Archived : ChatFilter {
        override val key = "archived"
    }

    data class Folder(val id: String) : ChatFilter {
        override val key = "folder:$id"
    }

    companion object {
        fun parse(key: String?): ChatFilter = when {
            key == null -> All
            key == Pinned.key -> Pinned
            key == Incognito.key -> Incognito
            key == Archived.key -> Archived
            key.startsWith("folder:") && key.length > 7 -> Folder(key.substring(7))
            else -> All
        }

        fun folder(id: String): ChatFilter = Folder(id)
    }
}

/** What the organizer needs to know about a chat. */
data class ChatEntry(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val folderId: String? = null,
    val incognito: Boolean = false,
)

/** A heading in the list. [NONE] is the single unlabelled section of a by-name list. */
enum class ChatGroup(val label: String) {
    PINNED("Pinned"),
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    OLDER("Older"),
    NONE(""),
}

data class ChatSection(val group: ChatGroup, val entries: List<ChatEntry>)

/**
 * The chat list's rules, shared by both apps: what a filter shows, the sort orders, pinned chats
 * floating to the top, and grouping by day (Today / Yesterday / This week / Older).
 */
object ChatOrganizer {
    const val DAY_MS = 86_400_000L

    fun matches(entry: ChatEntry, filter: ChatFilter): Boolean = when (filter) {
        ChatFilter.All -> !entry.archived
        ChatFilter.Pinned -> entry.pinned && !entry.archived
        ChatFilter.Incognito -> entry.incognito && !entry.archived
        ChatFilter.Archived -> entry.archived
        is ChatFilter.Folder -> entry.folderId == filter.id && !entry.archived
    }

    fun sort(entries: List<ChatEntry>, sort: ChatSort): List<ChatEntry> = when (sort) {
        ChatSort.RECENT -> entries.sortedWith(compareByDescending<ChatEntry> { it.updatedAt }.thenBy { it.id })
        ChatSort.CREATED -> entries.sortedWith(compareByDescending<ChatEntry> { it.createdAt }.thenBy { it.id })
        ChatSort.NAME -> entries.sortedWith(compareBy<ChatEntry> { it.title.trim().lowercase() }.thenByDescending { it.updatedAt }.thenBy { it.id })
    }

    /**
     * The visible chats in sections. Pinned chats come first (except in the Pinned and Archived
     * views, which are not split); the rest are grouped by the sort's date ([ChatSort.NAME]: one
     * unlabelled section). [startOfToday] is local midnight in epoch milliseconds. Empty sections
     * are left out.
     */
    fun sections(entries: List<ChatEntry>, filter: ChatFilter, sort: ChatSort, startOfToday: Long): List<ChatSection> {
        val visible = sort(entries.filter { matches(it, filter) }, sort)
        val splitPinned = filter != ChatFilter.Pinned && filter != ChatFilter.Archived
        val pinned = if (splitPinned) visible.filter { it.pinned } else emptyList()
        val rest = if (splitPinned) visible.filterNot { it.pinned } else visible
        val sections = ArrayList<ChatSection>()
        if (pinned.isNotEmpty()) sections += ChatSection(ChatGroup.PINNED, pinned)
        if (sort == ChatSort.NAME) {
            if (rest.isNotEmpty()) sections += ChatSection(ChatGroup.NONE, rest)
        } else {
            val byGroup = rest.groupBy { group(if (sort == ChatSort.CREATED) it.createdAt else it.updatedAt, startOfToday) }
            ChatGroup.entries.forEach { g -> byGroup[g]?.let { sections += ChatSection(g, it) } }
        }
        return sections
    }

    /** The day group of [time]. Times in the future (clock changes) count as today. */
    fun group(time: Long, startOfToday: Long): ChatGroup = when {
        time >= startOfToday -> ChatGroup.TODAY
        time >= startOfToday - DAY_MS -> ChatGroup.YESTERDAY
        time >= startOfToday - 6 * DAY_MS -> ChatGroup.THIS_WEEK
        else -> ChatGroup.OLDER
    }

    /** Folder chips worth showing: [ChatFilter.Incognito] only while an incognito chat exists. */
    fun showsIncognitoChip(entries: List<ChatEntry>): Boolean = entries.any { it.incognito && !it.archived }

    /** Chats per folder id (not archived), for chip counts. */
    fun folderCounts(entries: List<ChatEntry>): Map<String, Int> =
        entries.filter { !it.archived && it.folderId != null }.groupingBy { it.folderId!! }.eachCount()
}

/** Folder and title naming rules. */
object ChatNames {
    const val MAX_FOLDER_NAME = 40
    const val MAX_TITLE = 80
    const val PROMPT_TITLE = 48
    const val DEFAULT_TITLE = "New chat"

    private val whitespace = Regex("\\s+")

    /** Trimmed, whitespace collapsed (no line breaks) and cut to [max] characters. */
    fun clean(name: String, max: Int): String = name.trim().replace(whitespace, " ").take(max).trim()

    fun folderName(name: String): String = clean(name, MAX_FOLDER_NAME)

    /** A renamed chat's title, or null when [title] is blank. */
    fun title(title: String): String? = clean(title, MAX_TITLE).ifEmpty { null }

    /** The automatic title: the prompt's first line ("Image" for an image-only prompt). */
    fun fromPrompt(prompt: String): String =
        prompt.trim().lineSequence().first().trim().ifEmpty { "Image" }.take(PROMPT_TITLE)

    /**
     * Why [name] cannot be used for a folder, or null when it can. [existing] are the other
     * folders' names (the folder being renamed left out); names are unique ignoring case.
     */
    fun folderProblem(name: String, existing: List<String>): String? {
        val cleaned = folderName(name)
        return when {
            cleaned.isEmpty() -> "Enter a name"
            existing.any { it.equals(cleaned, ignoreCase = true) } -> "A folder with this name exists"
            else -> null
        }
    }
}

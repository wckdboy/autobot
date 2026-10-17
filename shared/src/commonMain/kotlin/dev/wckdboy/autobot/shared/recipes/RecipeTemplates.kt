package dev.wckdboy.autobot.shared.recipes

/**
 * The example recipes both apps add once (prefilled, disabled): the user edits the feeds or the
 * folder, picks a model and switches them on.
 */
object RecipeTemplates {
    const val MORNING_BRIEF = "template-morning-brief"
    const val NOTES_TIDY = "template-notes-tidy"
    const val CHAT_DIGEST = "template-chat-digest"

    fun all(nowMillis: Long): List<Recipe> = listOf(
        Recipe(
            id = MORNING_BRIEF,
            name = "Morning brief from my feeds",
            prompt = """
                Fetch each feed below with web_fetch and write a short morning brief: the five most
                important new items across all of them, one line each, with the source in brackets.
                Skip anything older than a day.

                Feeds:
                - https://hnrss.org/frontpage
                - https://feeds.arstechnica.com/arstechnica/index
                - https://www.theguardian.com/world/rss
            """.trimIndent(),
            schedule = RecipeSchedule.Daily(7 * 60),
            tools = RecipeTools.WEB.tools,
            template = MORNING_BRIEF,
            createdAt = nowMillis,
        ),
        Recipe(
            id = NOTES_TIDY,
            name = "Weekly notes tidy",
            prompt = """
                Tidy the notes in the workspace folder notes/: fix typos and formatting, merge notes
                that cover the same thing, and write notes/INDEX.md listing every note with a
                one-line summary. Never delete a file: when you merge two notes, leave a line in the
                old one saying where its text went.
            """.trimIndent(),
            schedule = RecipeSchedule.Weekly(dayOfWeek = 5, minuteOfDay = 18 * 60),
            tools = RecipeTools.READ_FILES.tools + RecipeTools.WRITE_FILES.tools,
            requiresCharging = true,
            template = NOTES_TIDY,
            createdAt = nowMillis,
        ),
        Recipe(
            id = CHAT_DIGEST,
            name = "Daily digest of my chats",
            prompt = """
                Write a short digest of my chats from today: the topics, any decisions, and open
                questions I should come back to. Bullet points, at most ten.
            """.trimIndent(),
            schedule = RecipeSchedule.Daily(21 * 60),
            includeRecentChats = true,
            template = CHAT_DIGEST,
            createdAt = nowMillis,
        ),
    )

    /** List order: by creation, the templates in their own order. */
    val listOrder: Comparator<Recipe> = compareBy<Recipe> { it.createdAt }.thenBy { listOf(MORNING_BRIEF, NOTES_TIDY, CHAT_DIGEST).indexOf(it.id).let { i -> if (i < 0) 99 else i } }.thenBy { it.id }

    /** A new, empty recipe for the editor. */
    fun blank(id: String, nowMillis: Long): Recipe = Recipe(
        id = id,
        name = "",
        prompt = "",
        schedule = RecipeSchedule.Daily(RecipeSchedule.DEFAULT_MINUTE),
        createdAt = nowMillis,
    )
}

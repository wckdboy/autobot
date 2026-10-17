package dev.wckdboy.autobot.shared.recipes

import kotlinx.datetime.TimeZone

/**
 * The recipe rules for Swift, as JSON in and out ([RecipeCodec]): the iOS app keeps a Codable
 * mirror of [Recipe] and asks this object for every decision, so both apps schedule, word and
 * fence runs the same way. Times are epoch milliseconds in the phone's current time zone.
 */
object RecipeBridge {
    private val zone: TimeZone get() = TimeZone.currentSystemDefault()

    private fun map(json: String, block: (Recipe) -> Recipe): String = RecipeCodec.decode(json)?.let { RecipeCodec.encode(block(it)) } ?: json

    /** The example recipes ([RecipeTemplates]), prefilled and disabled. */
    fun templates(nowMillis: Long): List<String> = RecipeTemplates.all(nowMillis).map(RecipeCodec::encode)

    fun blank(id: String, nowMillis: Long): String = RecipeCodec.encode(RecipeTemplates.blank(id, nowMillis))

    /** [json] as saved from the editor ([RecipePlanner.saved]). */
    fun saved(json: String, previousJson: String?, nowMillis: Long): String =
        map(json) { RecipePlanner.saved(it, previousJson?.let(RecipeCodec::decode), nowMillis, zone) }

    fun reconcile(json: String, nowMillis: Long): String = map(json) { RecipePlanner.reconcile(it, nowMillis, zone) }

    fun finished(json: String, recordJson: String): String {
        val record = RecipeCodec.decodeRecord(recordJson) ?: return json
        return map(json) { RecipePlanner.finished(it, record, zone) }
    }

    fun waitForUnlock(json: String): String = map(json, RecipePlanner::waitForUnlock)

    fun isDue(json: String, nowMillis: Long): Boolean = RecipeCodec.decode(json)?.let { RecipePlanner.isDue(it, nowMillis) } ?: false

    /** Epoch ms of the next wake for [jsons], or -1 when nothing is scheduled. */
    fun nextWake(jsons: List<String>): Long = RecipePlanner.nextWake(jsons.mapNotNull(RecipeCodec::decode)) ?: -1L

    fun problems(json: String): List<String> = RecipeCodec.decode(json)?.problems() ?: listOf("This recipe cannot be read")

    fun status(json: String, nowMillis: Long, approximate: Boolean): String =
        RecipeCodec.decode(json)?.let { RecipeCopy.status(it, nowMillis, zone, approximate) }.orEmpty()

    fun scheduleLabel(json: String): String = RecipeCodec.decode(json)?.schedule?.label().orEmpty()

    fun chatTitle(name: String, atMillis: Long): String = RecipeCopy.chatTitle(name, atMillis, zone)

    fun usesNetwork(json: String): Boolean = RecipeCodec.decode(json)?.usesNetwork ?: false

    /** Where a recent-chats digest for [json] starts. */
    fun digestSince(json: String, nowMillis: Long): Long =
        RecipeCodec.decode(json)?.let { RecipeContext.since(it, nowMillis) } ?: (nowMillis - RecipeSchedule.DAY_MS)

    fun recentChats(chats: List<RecipeContext.ChatExcerpt>, sinceMillis: Long): String = RecipeContext.recentChats(chats, sinceMillis)

    /** A run that never started a turn (no model): its record JSON. */
    fun failedRecord(reason: String, startedAt: Long, finishedAt: Long, manual: Boolean): String =
        RecipeCodec.encodeRecord(
            RecipeTurnResult.failed(reason).record(startedAt, finishedAt, if (manual) RecipeTrigger.MANUAL else RecipeTrigger.SCHEDULE, null),
        )

    fun notificationTitle(name: String, recordJson: String): String =
        RecipeCodec.decodeRecord(recordJson)?.let { RecipeCopy.title(name, it) } ?: RecipeCopy.failedTitle(name)

    fun notificationBody(recordJson: String, showSummary: Boolean): String =
        RecipeCodec.decodeRecord(recordJson)?.let { RecipeCopy.body(it, showSummary) } ?: RecipeCopy.OPEN_TO_READ

    /** Tool groups the editor offers. */
    val toolGroups: List<RecipeTools.Group> get() = RecipeTools.groups

    fun toggleTools(tools: List<String>, groupId: String, on: Boolean): List<String> {
        val group = RecipeTools.groups.firstOrNull { it.id == groupId } ?: return RecipeTools.sanitize(tools)
        return RecipeTools.toggle(tools, group, on)
    }
}

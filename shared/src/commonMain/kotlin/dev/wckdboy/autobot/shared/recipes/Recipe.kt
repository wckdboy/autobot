package dev.wckdboy.autobot.shared.recipes

import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A scheduled on-device agent run (ROADMAP Phase 2.5 item 7): "every morning at 7, summarise these
 * feeds". Both apps store recipes in their encrypted store as [RecipeCodec] JSON, run them headless
 * on an on-device model ([RecipeTurns]) and deliver the result as a notification plus a chat.
 *
 * @property model the on-device model to run on ("" = This phone's default model). Cloud models
 *   are not allowed in recipes.
 * @property tools the tool allowlist: these tools are offered to the model and run without asking
 *   (the user approved them for this recipe); anything else that would need approval is denied.
 * @property includeRecentChats the run gets a digest of chats changed since the last run as context.
 * @property requiresCharging run only while charging (Android constraint, iOS external power).
 * @property requiresBatteryNotLow run only when the battery is not low (Android).
 * @property runsOn the paired Invincible node (its provider id) that runs this recipe instead of
 *   the phone: always on, no time limit; the phone collects the results ([NodeRecipes]). Null:
 *   this phone.
 */
@OptIn(ExperimentalObjCName::class)
@ObjCName("AgentRecipe") // The Studio's `Recipe` keeps its Swift name.
@Serializable
data class Recipe(
    val id: String,
    val name: String,
    val prompt: String,
    val schedule: RecipeSchedule,
    val model: String = "",
    val tools: List<String> = emptyList(),
    val includeRecentChats: Boolean = false,
    val requiresCharging: Boolean = false,
    val requiresBatteryNotLow: Boolean = true,
    val enabled: Boolean = false,
    /** The template this recipe started from, if any (see [RecipeTemplates]). */
    val template: String? = null,
    val runsOn: String? = null,
    val createdAt: Long = 0,
    val state: RecipeState = RecipeState(),
) {
    /** Whether a run may use the network (a network tool is allowed): Android then waits for an unmetered network. */
    val usesNetwork: Boolean get() = tools.any { it in RecipeTools.NETWORK }

    /** Problems that keep this recipe from being saved, in display order (empty: fine). */
    fun problems(): List<String> = buildList {
        if (name.isBlank()) add("Give the recipe a name")
        if (prompt.isBlank()) add("Write what the recipe should do")
        schedule.problem()?.let(::add)
    }
}

/** What the scheduler knows about a recipe between runs. */
@Serializable
data class RecipeState(
    /** When the next run is due (epoch ms); null while the recipe is disabled. */
    val nextRunAt: Long? = null,
    /** A due run could not read the encrypted data (the device was locked): it runs at the next unlock. */
    val waitingForUnlock: Boolean = false,
    val lastRun: RecipeRunRecord? = null,
    /** For a recipe on a node: the node and the UTC schedule last sent there ([NodeRecipes.pushed]). */
    val pushed: String? = null,
)

/** How one run ended. */
@Serializable
enum class RecipeRunStatus {
    /** The model answered. */
    DONE,

    /** The run ended with an error (no model, engine failure, the turn failed). */
    FAILED,

    /** The run took longer than [RecipeTurns.DEFAULT_TIMEOUT_MS] and was stopped. */
    TIMED_OUT,
}

/** What started a run. */
@Serializable
enum class RecipeTrigger {
    /** Its scheduled time came (or the app opened after it was missed). */
    SCHEDULE,

    /** "Run now". */
    MANUAL,
}

/**
 * One run's outcome, kept with the recipe (the last one) for the Recipes screen.
 *
 * @property sessionId the chat the result was written to (null when the run never started a turn).
 * @property summary a short plain-text summary of the answer (or of the failure).
 * @property notes what the run could not do ("Needed approval for web_fetch").
 * @property hosts how many hosts the run contacted (from its privacy receipt).
 */
@Serializable
data class RecipeRunRecord(
    val startedAt: Long,
    val finishedAt: Long,
    val status: RecipeRunStatus,
    val trigger: RecipeTrigger = RecipeTrigger.SCHEDULE,
    val sessionId: String? = null,
    val summary: String = "",
    val notes: List<String> = emptyList(),
    val hosts: Int = 0,
    val bytesSent: Long = 0,
)

/** JSON as both apps store it (one document per recipe) and hand it across the Swift bridge. */
object RecipeCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(recipe: Recipe): String = json.encodeToString(Recipe.serializer(), recipe)

    /** The recipe in [text], or null when it is not a readable recipe. */
    fun decode(text: String): Recipe? = runCatching { json.decodeFromString(Recipe.serializer(), text) }.getOrNull()

    fun encodeRecord(record: RecipeRunRecord): String = json.encodeToString(RecipeRunRecord.serializer(), record)

    fun decodeRecord(text: String): RecipeRunRecord? = runCatching { json.decodeFromString(RecipeRunRecord.serializer(), text) }.getOrNull()
}

/**
 * The tools a recipe can allow, as the editor offers them (groups of tool names). Tools that need
 * a person (questions), delete files, run other agents or reach configured servers are never
 * offered: a headless run cannot ask, and those are the risky ones.
 */
object RecipeTools {
    @Serializable
    data class Group(
        val id: String,
        val label: String,
        val detail: String,
        val tools: List<String>,
        @SerialName("network") val usesNetwork: Boolean = false,
    )

    val READ_FILES = Group("files-read", "Read files", "read, glob, grep in the agent workspace", listOf("read", "glob", "grep"))
    val WRITE_FILES = Group("files-write", "Write files", "write and edit in the agent workspace (never delete)", listOf("write", "edit"))
    val WEB = Group(
        "web", "Web", "web_fetch and web_search, through the kill switch and the audit log",
        listOf("web_fetch", "web_search"), usesNetwork = true,
    )
    val MEMORY = Group("memory", "Memory", "memory_search and memory_save (when memory is on)", listOf("memory_search", "memory_save"))
    val VAULT = Group("vault", "Vault", "vault_search (when the vault is on)", listOf("vault_search"))

    val groups: List<Group> = listOf(READ_FILES, WRITE_FILES, WEB, MEMORY, VAULT)

    /** Tools that reach the network. */
    val NETWORK: Set<String> = groups.filter { it.usesNetwork }.flatMap { it.tools }.toSet()

    /** Every tool a recipe may allow. */
    val ALLOWED: Set<String> = groups.flatMap { it.tools }.toSet()

    /** [tools] with only the ones a recipe may allow, in catalog order. */
    fun sanitize(tools: Collection<String>): List<String> = groups.flatMap { it.tools }.filter { it in tools }

    /** [tools] with [group] switched on or off. */
    fun toggle(tools: List<String>, group: Group, on: Boolean): List<String> =
        sanitize(if (on) tools + group.tools else tools - group.tools.toSet())

    fun isOn(tools: List<String>, group: Group): Boolean = group.tools.all { it in tools }
}

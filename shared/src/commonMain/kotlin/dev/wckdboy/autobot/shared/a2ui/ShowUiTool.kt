package dev.wckdboy.autobot.shared.a2ui

import dev.wckdboy.autobot.agent.core.Lock
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.prompt.PromptSection
import dev.wckdboy.autobot.agent.core.prompt.SectionOrder
import dev.wckdboy.autobot.agent.core.prompt.SystemPromptService
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRegistry
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import dev.wckdboy.autobot.agent.core.llm.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `show_ui`: the agent shows an interactive UI (A2UI v1.0, basic catalog) in the chat. The
 * argument is the A2UI messages: a JSON array of message objects (what the schema declares), or,
 * for older callers, one string holding a JSON array or JSON lines.
 *
 * What the model sends first goes through [A2uiIntake], which repairs what has one obvious
 * reading (unclosed brackets, a missing envelope, `version` or `catalogId`, a Button label as its
 * child, inlined children, a ChoicePicker string value) and lists each repair. The result is
 * validated strictly against the catalog and the chat's earlier surfaces ([A2uiValidator]):
 * invalid output goes back to the model as [A2uiFeedback] lines to fix (at most
 * [MAX_INVALID_PER_TURN] times a turn), valid output is stored in the result's meta
 * ([A2uiChat.META]) and drawn under the call. Either way the model reads the repairs, so it
 * learns to send them right. A user action comes back as the next user turn
 * ([A2uiChat.actionMessage]).
 *
 * Interact kind: allowed under every permission preset (it only draws; images load later, on
 * the user's tap, through the app's network policy). Subagents cannot use it.
 */
class ShowUiTool(private val catalogs: List<A2uiCatalog> = listOf(A2uiCatalog.basic)) : ToolDefinition {
    override val name = A2uiChat.TOOL
    override val kind = ToolKind.INTERACT
    override val description =
        "Show the user an interactive UI in the chat: a form, choices, a card or a list, built from A2UI v1.0 " +
            "messages. Use it when the user should pick, fill in or review something; otherwise answer in text. " +
            "If the UI is invalid you get the errors back: fix them and call again."
    /**
     * What the executor checks: `messages` is required and may be an array, an object or a
     * string ([A2uiIntake] reads each). The model is shown [schema] instead, which declares the
     * array form only.
     */
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "messages",
                    buildJsonObject {
                        put("description", "A2UI v1.0 messages: a JSON array of message objects, or a string holding one")
                        put("anyOf", buildJsonArray { add(messagesArray); add(buildJsonObject { put("type", "string") }) })
                    },
                )
            },
        )
        put("required", buildJsonArray { add(JsonPrimitive("messages")) })
        put("additionalProperties", false)
    }

    /** The declared schema: `messages` is a JSON array of message objects (not a string). */
    override fun schema(): ToolSchema = ToolSchema(
        name,
        description,
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("messages", JsonObject(messagesArray + ("description" to JsonPrimitive(MESSAGES_DESCRIPTION)))) })
            put("required", buildJsonArray { add(JsonPrimitive("messages")) })
            put("additionalProperties", false)
        },
    )

    private val lock = Lock()

    /** Invalid calls of the running turn, per session: session id → (turn, count). */
    private val invalid = HashMap<String, Pair<Int, Int>>()

    override fun describeCall(args: JsonObject): String {
        val raw = args["messages"]
        val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: raw?.toString().orEmpty()
        val ids = SURFACE_ID.findAll(text).map { it.groupValues[1] }.distinct().take(3).toList()
        return if (ids.isEmpty()) "" else "surface " + ids.joinToString(", ")
    }

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
        val agent = run.agent
        if (agent != null && isSubagent(agent)) return ToolOutput.error("show_ui is only available in the main chat", "NOT_AVAILABLE")
        val turn = agent?.log?.latest<SessionEvent.TurnStart>()?.turn ?: 0
        val tries = lock.withLock { invalid[run.sessionId]?.takeIf { it.first == turn }?.second ?: 0 }
        if (tries >= MAX_INVALID_PER_TURN) {
            return ToolOutput.error("show_ui failed $tries times this turn; answer in plain text instead", "A2UI_GIVE_UP")
        }
        val prior = agent?.let { A2uiChat.streams(it.log.entries.value) }.orEmpty()
        val out = check(args["messages"] ?: JsonNull, prior)
        if (out.isError) lock.withLock { invalid[run.sessionId] = turn to tries + 1 }
        return out
    }

    /** The tool's answer for [messages] after the chat's earlier streams [prior] (no turn bookkeeping). */
    fun check(messages: JsonElement, prior: List<String> = emptyList()): ToolOutput {
        val intake = A2uiIntake.accept(messages, catalogs, A2uiChat.limits, priorComponents(prior))
        val repaired = if (intake.repairs.isEmpty()) "" else "\nRepaired for you (do it this way next time):\n" + intake.repairs.joinToString("\n") { "- $it" }
        val text = intake.text
            ?: return ToolOutput("Error: the UI was not shown. Fix this and call show_ui again:\n${intake.problem}$repaired", isError = true, code = "A2UI_INVALID")
        val report = A2uiValidator(catalogs, A2uiChat.limits).validate(text, prior)
        if (!report.isValid || report.messageCount == 0) {
            val why = if (report.isValid) "no A2UI messages found" else A2uiFeedback.lines(report, intake.messages).joinToString("\n")
            return ToolOutput("Error: the UI was not shown. Fix these and call show_ui again:\n$why$repaired", isError = true, code = "A2UI_INVALID")
        }
        val shown = report.surfaceIds.joinToString(", ") { "'$it'" }
        return ToolOutput(
            "Shown to the user (surface $shown). Their input comes back as their next message; don't repeat the UI in text.$repaired",
            meta = A2uiChat.meta(text),
        )
    }

    /** The component ids of each surface [prior] left alive. */
    private fun priorComponents(prior: List<String>): Map<String, Set<String>> {
        if (prior.isEmpty()) return emptyMap()
        val host = A2uiHost(catalogs, A2uiChat.limits)
        prior.forEach { host.receive(it) }
        return host.surfaceIds.associateWith { id -> host.surface(id)?.components?.keys?.toSet().orEmpty() }
    }

    companion object {
        /** Invalid `show_ui` calls a turn may make before the tool asks for plain text. */
        const val MAX_INVALID_PER_TURN = 3

        private const val MESSAGES_DESCRIPTION =
            "A2UI v1.0 messages as a JSON array (not a string), usually one createSurface message"

        private val messagesArray: JsonObject = buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "object") })
        }

        private val SURFACE_ID = Regex("\"surfaceId\"\\s*:\\s*\"([^\"]{1,64})\"")

        internal fun isSubagent(agent: Agent): Boolean = "subagent:" in agent.ctx.name
    }
}

/**
 * The prompt that goes with `show_ui`: a compact list of the basic catalog and one example, kept
 * small for on-device models (the catalog's own instructions run to several thousand characters).
 */
object A2uiPrompt {
    val text: String = """
        |Generative UI: call show_ui when a small UI helps more than text (a form to fill, options to pick, a list to choose from, a summary card). For plain questions or facts, answer in text. Keep it short: one surface, a few components.
        |show_ui's "messages" is a JSON array (not a string) holding one message: {"version":"v1.0","createSurface":{"surfaceId":"<a new id>","catalogId":"${A2uiCatalog.BASIC_CATALOG_ID}","components":[...],"dataModel":{...}}}. Close every { and [ you open.
        |"components" is a flat list of {"id","component",...props}; one has id "root". Containers name their children by id (strings), never by inline objects:
        |Column/Row{children:["a","b"]} · Card{child:"a"} · Tabs{tabs:[{title,child:"a"}]} · Modal{trigger:"a",content:"b"} · List{children:{"componentId":"row","path":"/items"}} repeats "row" for each item of the array /items in dataModel; inside it, paths without a leading "/" read the item: {"@path":"name"}.
        |Text{text} · Image{url} · Icon{name} · Divider · Button{child:"<id of a Text>", variant:primary|default|borderless, action}: a Button's label is its own Text component.
        |Inputs: "value" is {"@path":"/x"} and dataModel holds its start value. TextField{label, variant:shortText|longText|number|obscured} ("") · CheckBox{label} (false) · ChoicePicker{label, options:[{label,value}], variant:mutuallyExclusive|multipleSelection, displayStyle:checkbox|chips} ([] — always a list, even for one choice) · Slider{label, min, max} (a number) · DateTimeInput{label, enableDate:true, enableTime:true} ("").
        |Validation on an input: "checks":[{"condition":{"@call":"required","args":{"value":{"@path":"/x"}}},"message":"..."}]; the only check functions are required{value}, email{value}, length{value,min,max}, regex{value,pattern}, numeric{value,min,max}.
        |A Button's "action":{"event":{"name":"submit","context":{"x":{"@path":"/x"}}}} comes back to you as the user's next message, in <a2ui_action>: put every input's path in the context.
        |Example (its shape only; write your own ids and text): [{"version":"v1.0","createSurface":{"surfaceId":"tea","catalogId":"${A2uiCatalog.BASIC_CATALOG_ID}","components":[{"id":"root","component":"Column","children":["who","kind","send"]},{"id":"who","component":"TextField","label":"Name","value":{"@path":"/who"},"checks":[{"condition":{"@call":"required","args":{"value":{"@path":"/who"}}},"message":"Enter a name."}]},{"id":"kind","component":"ChoicePicker","label":"Tea","options":[{"label":"Green","value":"green"},{"label":"Black","value":"black"}],"value":{"@path":"/kind"},"displayStyle":"chips"},{"id":"send","component":"Button","variant":"primary","child":"send_t","action":{"event":{"name":"order_tea","context":{"who":{"@path":"/who"},"kind":{"@path":"/kind"}}}}},{"id":"send_t","component":"Text","text":"Order"}],"dataModel":{"who":"","kind":[]}}}]
    """.trimMargin()

    /** The example of [text] alone, for tests. */
    internal val example: String get() = text.substringAfter("Example (").substringAfter("): ").trim()
}

/**
 * `show_ui` and its prompt, for chats that draw generative UI (Android's phone chats today).
 * The prompt section only renders when the agent sends tools and may call `show_ui`.
 */
fun a2uiPlugin(catalogs: List<A2uiCatalog> = listOf(A2uiCatalog.basic)): Plugin =
    plugin("a2ui", SystemPromptService.Key, ToolRegistry.Key) { ctx ->
        val prompts = ctx.require(SystemPromptService.Key)
        val tools = ctx.require(ToolRegistry.Key)
        ctx.effect {
            prompts.section(
                PromptSection("tools:${A2uiChat.TOOL}", SectionOrder.TOOL_GUIDANCE) { agent ->
                    val config = agent.config.value
                    if (config.toolsEnabled && config.restriction.permits(A2uiChat.TOOL) && !ShowUiTool.isSubagent(agent)) A2uiPrompt.text else null
                },
            )
        }
        ctx.effect { tools.register(ShowUiTool(catalogs)) }
    }

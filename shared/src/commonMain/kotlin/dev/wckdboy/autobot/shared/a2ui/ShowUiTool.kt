package dev.wckdboy.autobot.shared.a2ui

import dev.wckdboy.autobot.agent.core.Lock
import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.approval.ApprovalPolicy
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
import dev.wckdboy.autobot.agent.core.tools.TaskTool
import dev.wckdboy.autobot.agent.core.tools.schema
import dev.wckdboy.autobot.agent.core.tools.string
import kotlinx.serialization.json.JsonObject

/**
 * `show_ui`: the agent shows an interactive UI (A2UI v1.0, basic catalog) in the chat. The
 * argument is the A2UI messages as one string (a JSON array or JSON lines). They are validated
 * against the catalog and the chat's earlier surfaces ([A2uiValidator]): invalid output goes back
 * to the model as [A2uiReport.forModel] lines to fix (at most [MAX_INVALID_PER_TURN] times a
 * turn), valid output is stored in the result's meta ([A2uiChat.META]) and drawn under the
 * call. A user action comes back as the next user turn ([A2uiChat.actionMessage]).
 *
 * Interact kind: allowed under every permission preset (it only draws; images load later, on
 * the user's tap, through the app's network policy). Only an interactive chat is offered it
 * ([offeredTo]): not a subagent (it reports to another agent, nobody sees its UI) and not an
 * unattended run such as a recipe (approval policy [ApprovalPolicy.NEVER]: nobody is there to
 * fill anything in). A call that arrives anyway is refused.
 */
class ShowUiTool(private val catalogs: List<A2uiCatalog> = listOf(A2uiCatalog.basic)) : ToolDefinition {
    override val name = A2uiChat.TOOL
    override val kind = ToolKind.INTERACT
    override val description =
        "Show the user an interactive UI in the chat: a form, choices, a card or a list, built from A2UI v1.0 " +
            "messages. Use it when the user should pick, fill in or review something; otherwise answer in text. " +
            "If the UI is invalid you get the errors back: fix them and call again."
    override val parameters = schema {
        string("messages", "A2UI v1.0 messages as a JSON array, starting with createSurface", required = true)
    }

    private val lock = Lock()

    /** Invalid calls of the running turn, per session: session id → (turn, count). */
    private val invalid = HashMap<String, Pair<Int, Int>>()

    override fun describeCall(args: JsonObject): String {
        val ids = SURFACE_ID.findAll(args.string("messages").orEmpty()).map { it.groupValues[1] }.distinct().take(3).toList()
        return if (ids.isEmpty()) "" else "surface " + ids.joinToString(", ")
    }

    override fun offeredTo(agent: Agent): Boolean = isInteractive(agent)

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
        val agent = run.agent
        if (agent != null && isSubagent(agent)) return ToolOutput.error("show_ui is only available in the main chat", "NOT_AVAILABLE")
        if (agent != null && !isInteractive(agent)) return ToolOutput.error("show_ui needs someone in the chat; this run is unattended, answer in text", "NOT_AVAILABLE")
        val text = args.string("messages").orEmpty()
        val turn = agent?.log?.latest<SessionEvent.TurnStart>()?.turn ?: 0
        val tries = lock.withLock { invalid[run.sessionId]?.takeIf { it.first == turn }?.second ?: 0 }
        if (tries >= MAX_INVALID_PER_TURN) {
            return ToolOutput.error("show_ui failed $tries times this turn; answer in plain text instead", "A2UI_GIVE_UP")
        }
        val prior = agent?.let { A2uiChat.streams(it.log.entries.value) }.orEmpty()
        val report = A2uiValidator(catalogs, A2uiChat.limits).validate(text, prior)
        if (!report.isValid || report.messageCount == 0) {
            lock.withLock { invalid[run.sessionId] = turn to tries + 1 }
            val why = if (report.isValid) "no A2UI messages found" else report.forModel()
            return ToolOutput("Error: the UI was not shown. Fix these and call show_ui again:\n$why", isError = true, code = "A2UI_INVALID")
        }
        val shown = report.surfaceIds.joinToString(", ") { "'$it'" }
        return ToolOutput(
            "Shown to the user (surface $shown). Their input comes back as their next message; don't repeat the UI in text.",
            meta = A2uiChat.meta(text),
        )
    }

    companion object {
        /** Invalid `show_ui` calls a turn may make before the tool asks for plain text. */
        const val MAX_INVALID_PER_TURN = 3

        private val SURFACE_ID = Regex("\"surfaceId\"\\s*:\\s*\"([^\"]{1,64})\"")

        internal fun isSubagent(agent: Agent): Boolean = "subagent:" in agent.ctx.name || TaskTool.isSubagent(agent.id)

        /** A chat a person is in: the main agent of a chat, not a subagent or an unattended (recipe) run. */
        fun isInteractive(agent: Agent): Boolean = !isSubagent(agent) && agent.approvalPolicy.value != ApprovalPolicy.NEVER
    }
}

/**
 * The prompt that goes with `show_ui`: a compact list of the basic catalog and one example, kept
 * small for on-device models (the catalog's own instructions run to several thousand characters).
 */
object A2uiPrompt {
    val text: String = """
        |Generative UI: call show_ui when a small UI helps more than text (a form to fill, options to pick, a summary card). Keep it short: one surface, a few components.
        |Each message is {"version":"v1.0", <one of createSurface | updateComponents | updateDataModel | deleteSurface>}. createSurface needs a new surfaceId, "catalogId":"${A2uiCatalog.BASIC_CATALOG_ID}", "components" and an optional "dataModel".
        |A component is {"id","component",...props}; exactly one has id "root"; children are referenced by id, never inlined. Components:
        |Text{text (markdown), variant:body|caption} · Image{url, variant} · Icon{name e.g. mail, check, star} · Divider · Row/Column{children:[ids], justify, align} · List{children, direction} · Card{child} · Tabs{tabs:[{title,child}]} · Modal{trigger,content} · Button{child, variant:primary|default|borderless, action} · TextField{label, value, variant:shortText|longText|number|obscured} · CheckBox{label, value} · ChoicePicker{label, options:[{label,value}], value, variant:mutuallyExclusive|multipleSelection, displayStyle:checkbox|chips} · Slider{label, value, min, max} · DateTimeInput{label, value, enableDate, enableTime}.
        |Inputs bind with {"@path":"/form/name"}. Validation: "checks":[{"condition":{"@call":"required","args":{"value":{"@path":"/form/name"}}},"message":"Name is required."}]. A Button's "action":{"event":{"name":"submit","context":{"name":{"@path":"/form/name"}}}} comes back to you as the user's next message, in <a2ui_action>.
        |Example: [{"version":"v1.0","createSurface":{"surfaceId":"rsvp","catalogId":"${A2uiCatalog.BASIC_CATALOG_ID}","components":[{"id":"root","component":"Column","children":["q","name","go"]},{"id":"q","component":"Text","text":"**Coming on Friday?**"},{"id":"name","component":"TextField","label":"Your name","value":{"@path":"/name"}},{"id":"go","component":"Button","variant":"primary","child":"go_t","action":{"event":{"name":"rsvp","context":{"name":{"@path":"/name"}}}}},{"id":"go_t","component":"Text","text":"I'm in"}],"dataModel":{"name":""}}}]
    """.trimMargin()

    /** The example of [text] alone, for tests. */
    internal val example: String get() = text.substringAfter("Example: ").trim()
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
                    if (config.toolsEnabled && config.restriction.permits(A2uiChat.TOOL) && ShowUiTool.isInteractive(agent)) A2uiPrompt.text else null
                },
            )
        }
        ctx.effect { tools.register(ShowUiTool(catalogs)) }
    }

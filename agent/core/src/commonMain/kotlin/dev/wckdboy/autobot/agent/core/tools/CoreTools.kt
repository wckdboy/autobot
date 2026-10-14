package dev.wckdboy.autobot.agent.core.tools

import dev.wckdboy.autobot.agent.core.Plugin
import dev.wckdboy.autobot.agent.core.plugin
import dev.wckdboy.autobot.agent.core.session.SessionEvent
import dev.wckdboy.autobot.agent.core.session.TodoItem
import dev.wckdboy.autobot.agent.core.session.TodoStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** DSH `todo_write`: replaces the whole list; logged as `todo/write` for the UI. */
object TodoWriteTool : ToolDefinition {
    override val name = "todo_write"
    override val kind = ToolKind.OTHER
    override val description =
        "Create or update your task list for multi-step work. Send the complete list every time (it replaces the " +
            "previous one). Keep exactly one item in_progress while working, and mark items completed as soon as " +
            "they are done. Skip this for trivial single-step requests."
    override val parameters = schema {
        objectArray("todos", "The full task list", required = true) {
            string("content", "Imperative description of the task", required = true)
            string("status", "Task state", required = true, enum = listOf("pending", "in_progress", "completed"))
        }
    }

    override fun isConcurrencySafe(args: JsonObject) = false

    override fun describeCall(args: JsonObject): String = "${(args["todos"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0} items"

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
        val todos = (args["todos"] as kotlinx.serialization.json.JsonArray).map { item ->
            val obj = item as JsonObject
            val status = when ((obj["status"] as JsonPrimitive).content) {
                "in_progress" -> TodoStatus.IN_PROGRESS
                "completed" -> TodoStatus.COMPLETED
                else -> TodoStatus.PENDING
            }
            TodoItem(obj.string("content").orEmpty(), status)
        }
        run.log(SessionEvent.TodoWrite(todos))
        val done = todos.count { it.status == TodoStatus.COMPLETED }
        return ToolOutput("Todo list updated ($done/${todos.size} completed).")
    }
}

/** DSH `ask_user_question`, simplified to one question with optional choices. */
object AskUserTool : ToolDefinition {
    override val name = "ask_user_question"
    override val kind = ToolKind.INTERACT
    override val description =
        "Ask the user a clarifying question when a decision is genuinely theirs and you cannot infer it. Offer 2–4 " +
            "concrete options when possible; the user can also type a free answer."
    override val parameters = schema {
        string("question", "The question, ending with a question mark", required = true)
        stringArray("options", "Suggested answers (2–4)", maxItems = 4)
        boolean("multi_select", "Allow several options")
    }
    override val timeoutMs: Long = 30 * 60_000L

    override fun describeCall(args: JsonObject): String = args.string("question").orEmpty()

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput {
        val answer = run.askUser(
            UserQuestion(args.string("question").orEmpty(), args.strings("options"), args.bool("multi_select") ?: false),
        ) ?: return ToolOutput.error("the user did not answer", "NO_ANSWER")
        return ToolOutput("User answered: ${answer.joinToString("; ")}")
    }
}

/** Registers [tools] for the lifetime of the plugin. */
fun toolsPlugin(name: String, vararg tools: ToolDefinition): Plugin = plugin(name, ToolRegistry.Key) { ctx ->
    val registry = ctx.require(ToolRegistry.Key)
    tools.forEach { tool -> ctx.effect { registry.register(tool) } }
}

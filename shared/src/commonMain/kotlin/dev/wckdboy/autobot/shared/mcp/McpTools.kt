package dev.wckdboy.autobot.shared.mcp

import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolKind
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.ToolRunContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One remote tool, exposed to the model as `mcp__<server>__<tool>` with the server's own input
 * schema. Kind [ToolKind.OTHER]; the MCP gate ([McpHub.plugin]) asks for approval on every call
 * unless the server is trusted, and the approval card names the server and its host.
 */
class McpToolDefinition internal constructor(
    val serverId: String,
    val serverName: String,
    val host: String,
    val remoteName: String,
    override val name: String,
    override val description: String,
    override val parameters: JsonObject,
    private val client: McpClient,
) : ToolDefinition {
    override val kind = ToolKind.OTHER
    override val timeoutMs: Long = TIMEOUT_MS

    override fun describeCall(args: JsonObject): String =
        listOf(host, super.describeCall(args)).filter { it.isNotEmpty() }.joinToString(" · ")

    override suspend fun execute(args: JsonObject, run: ToolRunContext): ToolOutput = try {
        McpResults.toOutput(client.callTool(remoteName, args), serverName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: McpException) {
        McpResults.failure(e, serverName)
    }

    companion object {
        const val PREFIX = "mcp__"
        const val TIMEOUT_MS = 120_000L
    }
}

/** MCP results and failures → [ToolOutput]. */
object McpResults {
    const val MAX_CHARS = 100_000

    fun toOutput(result: McpCallResult, serverName: String): ToolOutput {
        val parts = result.content.map(::render)
        var text = parts.joinToString("\n\n").ifBlank { result.structured?.toString().orEmpty() }.ifBlank { "(no content)" }
        if (text.length > MAX_CHARS) text = text.take(MAX_CHARS) + "\n… [truncated]"
        val media = result.content.count { it is McpContent.Image || it is McpContent.Audio }
        val meta = buildJsonObject {
            put("mcpServer", serverName)
            if (media > 0) put("mcpMedia", media)
        }
        return ToolOutput(text, isError = result.isError, code = if (result.isError) "MCP_TOOL_ERROR" else null, meta = meta)
    }

    fun failure(e: McpException, serverName: String): ToolOutput {
        val code = when (e.kind) {
            McpErrorKind.BLOCKED -> "BLOCKED"
            McpErrorKind.NETWORK -> "TRANSPORT"
            McpErrorKind.AUTH -> "MCP_AUTH"
            McpErrorKind.RPC -> "MCP_ERROR"
            else -> "MCP_FAILED"
        }
        val what = if (e.kind == McpErrorKind.BLOCKED) "blocked by the network policy (${e.message})" else e.message.orEmpty()
        return ToolOutput.error("MCP server $serverName: $what", code)
    }

    private fun render(content: McpContent): String = when (content) {
        is McpContent.Text -> content.text
        is McpContent.Image -> "[image ${content.mimeType}, ${kb(content.bytes)} — not shown]"
        is McpContent.Audio -> "[audio ${content.mimeType}, ${kb(content.bytes)} — not played]"
        is McpContent.ResourceLink -> "[resource ${content.name ?: content.uri}: ${content.uri}]"
        is McpContent.Resource -> when {
            content.text != null -> "Resource ${content.uri}:\n${content.text}"
            else -> "[resource ${content.uri} (${content.mimeType ?: "binary"}, ${kb(content.blobBytes ?: 0)}) — not shown]"
        }
        is McpContent.Unsupported -> "[unsupported content type ${content.type}]"
    }

    private fun kb(bytes: Int) = if (bytes < 1024) "$bytes B" else "${bytes / 1024} KB"
}

/** Naming and schema rules for exposing remote tools to chat models. */
object McpNames {
    /** OpenAI-style function names: `[A-Za-z0-9_-]{1,64}`. */
    const val MAX_NAME = 64
    private const val MAX_SERVER = 24
    private val INVALID = Regex("[^A-Za-z0-9_-]+")
    private val UNDERSCORES = Regex("_{2,}")

    /** A server's name as a tool-name segment (`My Notes!` → `my_notes`). */
    fun serverSlug(name: String, fallback: String): String =
        UNDERSCORES.replace(INVALID.replace(name.trim().lowercase(), "_"), "_").trim('_', '-').take(MAX_SERVER).trim('_', '-')
            .ifEmpty { INVALID.replace(fallback.lowercase(), "").take(8).ifEmpty { "server" } }

    /** `mcp__<slug>__<tool>`, sanitized and kept within [MAX_NAME] (a hash keeps long names unique). */
    fun toolName(slug: String, tool: String): String {
        val clean = INVALID.replace(tool, "_").ifEmpty { "tool" }
        val full = "${McpToolDefinition.PREFIX}${slug}__$clean"
        if (full.length <= MAX_NAME && clean == tool) return full
        val hash = (tool.hashCode().toUInt() % 0xFFFFFFu).toString(16).padStart(6, '0')
        val head = "${McpToolDefinition.PREFIX}${slug}__"
        return head + clean.take((MAX_NAME - head.length - 7).coerceAtLeast(1)) + "_" + hash
    }

    /** Chat APIs want an object schema with `properties`; anything else is wrapped to that. */
    fun schema(input: JsonObject): JsonObject {
        val type = (input["type"] as? JsonPrimitive)?.content
        if (type != null && type != "object") return JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap())))
        val withType = if (type == null) input + ("type" to JsonPrimitive("object")) else input
        return JsonObject(if (withType["properties"] is JsonObject) withType else withType + ("properties" to JsonObject(emptyMap())))
    }

    fun description(server: String, tool: McpTool): String {
        val text = listOfNotNull(tool.title?.takeIf { it != tool.name }, tool.description.takeIf { it.isNotBlank() }).joinToString(" — ")
        return "[MCP server $server] " + text.ifEmpty { tool.name }.take(MAX_DESCRIPTION)
    }

    private const val MAX_DESCRIPTION = 1_024
}

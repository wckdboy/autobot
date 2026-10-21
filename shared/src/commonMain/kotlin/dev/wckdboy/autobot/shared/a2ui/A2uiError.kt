package dev.wckdboy.autobot.shared.a2ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The A2UI v1.0 error codes this core reports. */
enum class A2uiErrorCode {
    /** The message or a component does not match the schema or the catalog. */
    VALIDATION_FAILED,

    /** The surface state does not hold together: unknown surface, duplicate surface, missing child, cycle. */
    INTEGRITY_ERROR,

    /** A function that may not run here: agent-only in the UI, or an unsupported RPC. */
    INVALID_FUNCTION_CALL,

    /** A component is placed under a parent its catalog entry does not allow. */
    UNALLOWED_PARENT,

    /** A container holds a child type its catalog entry does not allow. */
    UNALLOWED_CHILD,

    /** An expression failed to evaluate: bad formatString, unknown function, `@index` outside a list. */
    EXPRESSION_ERROR,
}

/**
 * One problem with what the agent sent, in the A2UI standard error form: a [code], the
 * [surfaceId], a JSON Pointer [path] into the offending message payload (for example
 * `/components/0/text`) and a one-sentence [message]. [messageIndex] is the position of the
 * message in the stream it came from (0-based), when known.
 */
data class A2uiError(
    val code: A2uiErrorCode,
    val message: String,
    val path: String = "",
    val surfaceId: String? = null,
    val messageIndex: Int? = null,
    val functionCallId: String? = null,
) {
    /** The code as the wire string, e.g. `VALIDATION_FAILED`. */
    val codeName: String get() = code.name

    /** One line for an LLM to correct its output, e.g. `VALIDATION_FAILED [message 1] /components/0/text: expected string`. */
    fun forModel(): String = buildString {
        append(code.name)
        if (messageIndex != null) append(" [message ").append(messageIndex).append(']')
        if (surfaceId != null) append(" surface '").append(surfaceId).append('\'')
        append(' ').append(path.ifEmpty { "/" }).append(": ").append(message)
    }

    /**
     * The renderer-to-agent message reporting this error (`renderer_to_agent.json`): a
     * `rendererFunctionResponse` error for a rejected `callRendererFunction`, otherwise an
     * `error` message. Null when there is no surface or call to attach it to.
     */
    fun toAgentMessageJson(): String? {
        val payload: JsonObject = when {
            functionCallId != null && code == A2uiErrorCode.INVALID_FUNCTION_CALL -> buildJsonObject {
                put("version", "v1.0")
                put("rendererFunctionResponse", buildJsonObject {
                    put("functionCallId", functionCallId)
                    put("error", buildJsonObject {
                        put("code", code.name)
                        put("message", message)
                    })
                })
            }
            surfaceId == null -> return null
            else -> buildJsonObject {
                put("version", "v1.0")
                put("error", buildJsonObject {
                    put("code", code.name)
                    put("surfaceId", surfaceId)
                    if (code == A2uiErrorCode.VALIDATION_FAILED || code == A2uiErrorCode.UNALLOWED_PARENT || code == A2uiErrorCode.UNALLOWED_CHILD) {
                        put("path", JsonPrimitive(path))
                    }
                    put("message", message)
                })
            }
        }
        return payload.toJsonText()
    }
}

/**
 * Bounds on what an agent can make the renderer hold or compute. Hostile or broken output hits
 * one of these and is rejected with an error instead of exhausting memory or the stack.
 */
data class A2uiLimits(
    /** Characters in one text handed to [A2uiHost.receive] or [A2uiValidator.validate]. */
    val maxStreamChars: Int = 2_000_000,
    /** Characters in one message. */
    val maxMessageChars: Int = 512_000,
    /** Messages in one stream. */
    val maxMessages: Int = 500,
    /** Nesting depth of any JSON value in a message. */
    val maxJsonDepth: Int = 48,
    /** Items in any one JSON array. */
    val maxArrayLength: Int = 5_000,
    /** Keys in any one JSON object. */
    val maxObjectKeys: Int = 1_000,
    /** Characters in any one JSON string. */
    val maxStringLength: Int = 100_000,
    /** Components one surface may hold. */
    val maxComponentsPerSurface: Int = 1_000,
    /** Surfaces alive at once. */
    val maxSurfaces: Int = 32,
    /** Characters of one surface's data model (as JSON). */
    val maxDataModelChars: Int = 1_000_000,
    /** Nodes in one rendered tree (templates multiply components). */
    val maxRenderNodes: Int = 5_000,
    /** Depth of the rendered component tree. */
    val maxRenderDepth: Int = 64,
    /** Depth of nested function calls and dynamic values. */
    val maxCallDepth: Int = 32,
    /** Characters of a formatString template. */
    val maxTemplateChars: Int = 10_000,
    /** Characters of a `regex` pattern. */
    val maxRegexPatternChars: Int = 256,
    /** Characters a `regex` check reads. */
    val maxRegexInputChars: Int = 10_000,
) {
    companion object {
        /** The defaults (Swift has no default arguments). */
        val standard: A2uiLimits = A2uiLimits()
    }
}

package dev.wckdboy.autobot.agent.core

import dev.wckdboy.autobot.agent.core.llm.LlmCall
import dev.wckdboy.autobot.agent.core.llm.LlmCallConfig
import dev.wckdboy.autobot.agent.core.llm.LlmFailure
import dev.wckdboy.autobot.agent.core.llm.StreamChunk
import dev.wckdboy.autobot.agent.core.loop.Agent
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.tools.ToolDefinition
import dev.wckdboy.autobot.agent.core.tools.ToolOutput
import dev.wckdboy.autobot.agent.core.tools.UserQuestion
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** Decision of `agent/pre-step`. */
sealed interface PreStepDecision {
    data object Enter : PreStepDecision
    data class Reject(val reason: String) : PreStepDecision
}

data class PreStepInput(val agent: Agent, val turn: Int, val step: Int)

/** Decision of `agent/request-error`. */
sealed interface RequestErrorDecision {
    data object Fail : RequestErrorDecision
    data class Retry(val delayMs: Long) : RequestErrorDecision
}

data class RequestErrorInput(val agent: Agent, val failure: LlmFailure, val attempt: Int)

/** Decision of `agent/turn-stopping`: stop, or continue with an injected message. */
sealed interface TurnStoppingDecision {
    data object Stop : TurnStoppingDecision
    data class Continue(val message: String) : TurnStoppingDecision
}

data class TurnStoppingInput(val agent: Agent, val turn: Int, val steps: Int)

/** A dispatched tool call, as seen by the tool hooks. */
data class ToolCallInput(val agent: Agent, val callId: String, val tool: ToolDefinition, val args: JsonObject)

data class ToolPostInput(val call: ToolCallInput, val output: ToolOutput)

/** DSH `PreToolDecision`. Argument rewriting is deliberately impossible. */
sealed interface PreToolDecision {
    data object Allow : PreToolDecision
    data class Deny(val reason: String) : PreToolDecision
    data class Ask(val reason: String? = null) : PreToolDecision
}

data class ApprovalRequest(
    val agent: Agent,
    val callId: String,
    val toolName: String,
    val summary: String,
    val reason: String?,
)

data class QuestionRequest(val agent: Agent, val callId: String, val question: UserQuestion)

/** Inputs to `system-prompt/assemble`: ordered sections; listeners may add, drop or rewrite. */
data class PromptAssembly(val agent: Agent, val sections: List<Pair<Int, String>>)

/** Every extension point of the runtime. Names follow DSH. */
object Hooks {
    val PreStep = Hook<PreStepInput, PreStepDecision>("agent/pre-step")
    val Request = Hook<LlmCallConfig, LlmCallConfig>("agent/request")
    val RequestError = Hook<RequestErrorInput, RequestErrorDecision>("agent/request-error")
    val TurnStopping = Hook<TurnStoppingInput, TurnStoppingDecision>("agent/turn-stopping")
    val LlmStream = Hook<LlmCall, Flow<StreamChunk>>("llm/stream")
    val SystemPromptAssemble = Hook<PromptAssembly, PromptAssembly>("system-prompt/assemble")

    val ToolPreExecute = Hook<ToolCallInput, PreToolDecision>("tools/pre-execute")
    val ToolExecute = Hook<ToolCallInput, ToolOutput>("tools/execute")
    val ToolPostExecute = Hook<ToolPostInput, ToolOutput>("tools/post-execute")

    /** Answerers (UI, policy bots). Terminal answer is [ApprovalOutcome.UNAVAILABLE] → fail closed. */
    val ApprovalRequest = Hook<ApprovalRequest, ApprovalOutcome>("approval/request")
    val UserQuestion = Hook<QuestionRequest, List<String>?>("user-questions/request")

    val AgentCreated = Event<Agent>("agent/created")
    val AgentDisposed = Event<Agent>("agent/disposed")
}

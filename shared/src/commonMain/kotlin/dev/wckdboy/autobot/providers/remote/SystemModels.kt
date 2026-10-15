package dev.wckdboy.autobot.providers.remote

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * The operating system's own on-device language model: Apple Intelligence (Foundation Models)
 * on iOS 26+, Gemini Nano (AICore) on Android builds that include it. It is offered as a model
 * of the "This phone" provider and is the fallback when no downloaded model can run.
 */
object SystemModels {
    const val PREFIX = "system:"
    const val APPLE = "system:apple"
    const val GEMINI_NANO = "system:gemini-nano"

    /** Both system models have about 4k tokens of context (prompt + answer). */
    const val CONTEXT_TOKENS = 4096
    const val MAX_OUTPUT_TOKENS = 1024

    fun isSystem(model: String): Boolean = model.startsWith(PREFIX)

    fun title(id: String): String = when (id) {
        APPLE -> "Apple Intelligence (on-device)"
        GEMINI_NANO -> "Gemini Nano (on-device)"
        else -> id.removePrefix(PREFIX)
    }

    /** The transcript notice for an answer the system model gave in place of [fallback]'s model. */
    fun fallbackNotice(id: String, fallback: String): String = "${title(id)} · system model · fallback — $fallback"
}

/** What the platform says about its system model. */
enum class SystemAvailability {
    AVAILABLE,

    /** Supported, but the OS still has to download it (Android AICore). */
    DOWNLOADABLE,
    DOWNLOADING,
    UNAVAILABLE,
}

/**
 * @param detail why it cannot run, in words for the user ("Apple Intelligence is turned off in
 *   Settings"); null when available.
 */
@Serializable
data class SystemModelStatus(
    val id: String,
    val title: String,
    val availability: SystemAvailability,
    val detail: String? = null,
) {
    val usable: Boolean get() = availability == SystemAvailability.AVAILABLE

    /** One line for the Compute screen and the diagnostics report. */
    val summary: String
        get() = when (availability) {
            SystemAvailability.AVAILABLE -> "available"
            SystemAvailability.DOWNLOADABLE -> "not downloaded yet"
            SystemAvailability.DOWNLOADING -> "downloading"
            SystemAvailability.UNAVAILABLE -> "unavailable"
        } + detail?.let { " · $it" }.orEmpty()
}

/** Whether a requested downloaded (GGUF) chat model can run. */
enum class LocalModelState { READY, NOT_INSTALLED, TOO_BIG }

/** Which on-device model a chat runs on. */
sealed interface LocalPlan {
    data class Gguf(val model: String) : LocalPlan

    /** [fallback] is the reason the requested model was replaced, null when it was chosen. */
    data class System(val model: String, val fallback: String?) : LocalPlan
    data class Fail(val message: String) : LocalPlan
}

/**
 * Picks the on-device model for a chat. Pure: the platforms only report the GGUF model's state,
 * the system model's status and whether loading failed.
 *
 * requested system model → it, if available · requested GGUF ready → it · not installed / failed
 * to load → the system model if available · too big → the system model if available, otherwise
 * the GGUF anyway (it may still fit) · otherwise an error that says what to do.
 */
object LocalModelResolver {
    /** Engine error codes that mean the model never started answering. */
    val LOAD_FAILURES = setOf("LOAD_FAILED", "NO_MEMORY", "ENGINE_DIED")

    fun resolve(requested: String, state: LocalModelState, system: SystemModelStatus?, loadError: String? = null): LocalPlan {
        if (SystemModels.isSystem(requested)) {
            return when {
                system == null || system.id != requested -> LocalPlan.Fail("${SystemModels.title(requested)} is not available on this device")
                system.usable -> LocalPlan.System(requested, null)
                else -> LocalPlan.Fail("${system.title} is not available: ${system.summary}. Download a model in the Models tab instead.")
            }
        }
        val name = requested.ifBlank { "No model" }.let { if (requested.isBlank()) it else "'$it'" }
        val reason = when {
            loadError != null -> "$name failed to load ($loadError)"
            state == LocalModelState.READY -> return LocalPlan.Gguf(requested)
            state == LocalModelState.NOT_INSTALLED -> if (requested.isBlank()) "no model is installed" else "$name is not installed"
            else -> "$name is too big for this phone's memory"
        }
        if (system != null && system.usable) return LocalPlan.System(system.id, reason)
        if (state == LocalModelState.TOO_BIG && loadError == null) return LocalPlan.Gguf(requested)
        val guidance = when {
            loadError != null -> "Try a smaller model or a shorter context."
            else -> "Download a chat model in the Models tab."
        }
        val systemNote = system?.let { " ${it.title} is not available either: ${it.summary}." }.orEmpty()
        return LocalPlan.Fail("${reason.replaceFirstChar { it.uppercase() }}. $guidance$systemNote")
    }

    fun isLoadFailure(error: ChatEvent.Error): Boolean = error.code in LOAD_FAILURES
}

/** The platform's system model engine (Android; iOS goes through `LocalEngine`). */
interface SystemLlm {
    val id: String

    suspend fun status(): SystemModelStatus

    /** One answer to [prompt]. Failures are emitted as [ChatEvent.Error]; ends with [ChatEvent.Done]. */
    fun chat(prompt: SystemPrompt): Flow<ChatEvent>
}

/**
 * Routes one on-device chat with [LocalModelResolver]: the downloaded model through [gguf], or the
 * system model, announced with [ChatEvent.ServedBy] when it stands in for another model. A GGUF
 * model that fails to load before producing output is retried on the system model.
 */
class LocalChatRouter(
    private val modelState: suspend (String) -> LocalModelState,
    private val system: SystemLlm?,
    private val gguf: (ChatRequest) -> Flow<ChatEvent>,
) {
    fun stream(request: ChatRequest): Flow<ChatEvent> = flow {
        val requested = request.model
        val state = if (SystemModels.isSystem(requested)) LocalModelState.NOT_INSTALLED else modelState(requested)
        val status = if (state == LocalModelState.READY) null else systemStatus()
        when (val plan = LocalModelResolver.resolve(requested, state, status)) {
            is LocalPlan.Fail -> emit(ChatEvent.Error(ErrorKind.BAD_REQUEST, plan.message))
            is LocalPlan.System -> emitAll(runSystem(request, plan))
            is LocalPlan.Gguf -> {
                var produced = false
                var loadFailure: ChatEvent.Error? = null
                gguf(request).collect { event ->
                    if (loadFailure != null) return@collect
                    if (!produced && event is ChatEvent.Error && LocalModelResolver.isLoadFailure(event)) {
                        loadFailure = event
                        return@collect
                    }
                    if (event is ChatEvent.ContentDelta || event is ChatEvent.ReasoningDelta || event is ChatEvent.ToolCallDelta) produced = true
                    emit(event)
                }
                val failure = loadFailure ?: return@flow
                when (val retry = LocalModelResolver.resolve(requested, state, systemStatus(), failure.message)) {
                    is LocalPlan.System -> emitAll(runSystem(request, retry))
                    is LocalPlan.Fail -> emit(failure.copy(message = retry.message))
                    is LocalPlan.Gguf -> emit(failure)
                }
            }
        }
    }

    private suspend fun systemStatus(): SystemModelStatus? = system?.let { runCatching { it.status() }.getOrNull() }

    private fun runSystem(request: ChatRequest, plan: LocalPlan.System): Flow<ChatEvent> = flow {
        val llm = system
        if (llm == null) {
            emit(ChatEvent.Error(ErrorKind.BAD_REQUEST, "No system model on this device"))
            return@flow
        }
        if (plan.fallback != null) emit(ChatEvent.ServedBy(plan.model, plan.fallback))
        emitAll(llm.chat(SystemPrompt.from(request)))
    }
}

/**
 * A chat request reshaped for a system model: one instruction text, alternating user/assistant
 * turns and the prompt to answer, trimmed to the model's small context. Tools are not offered
 * (earlier tool calls and results stay readable as text).
 */
@Serializable
data class SystemPrompt(
    val instructions: String?,
    val history: List<Turn>,
    val prompt: String,
    val temperature: Double? = null,
    val maxTokens: Int = SystemModels.MAX_OUTPUT_TOKENS,
) {
    @Serializable
    data class Turn(val role: String, val text: String)

    /** Everything as one text, for APIs without a transcript (Gemini Nano's Prompt API). */
    fun flatten(): String = buildString {
        instructions?.let { append(it).append("\n\n") }
        history.forEach { t -> append(if (t.role == USER) "User: " else "Assistant: ").append(t.text).append("\n\n") }
        append("User: ").append(prompt).append("\n\nAssistant:")
    }

    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"

        /** Conservative: about 3 characters per token, so non-English text fits too. */
        private const val CHARS_PER_TOKEN = 3

        fun from(request: ChatRequest, contextTokens: Int = SystemModels.CONTEXT_TOKENS): SystemPrompt {
            val maxTokens = (request.maxTokens ?: SystemModels.MAX_OUTPUT_TOKENS).coerceIn(1, SystemModels.MAX_OUTPUT_TOKENS)
            val instructions = request.messages.filter { it.role == ChatRole.SYSTEM }.joinToString("\n\n") { it.content }.trim()
            val turns = mutableListOf<Turn>()
            fun add(role: String, text: String) {
                if (text.isBlank()) return
                val last = turns.lastOrNull()
                if (last != null && last.role == role) turns[turns.size - 1] = last.copy(text = last.text + "\n\n" + text) else turns += Turn(role, text)
            }
            request.messages.forEach { m ->
                when (m.role) {
                    ChatRole.SYSTEM -> Unit
                    ChatRole.USER -> add(USER, m.content + if (m.images.isNotEmpty()) "\n[${m.images.size} image(s) attached, not visible to this model]" else "")
                    ChatRole.ASSISTANT -> add(
                        ASSISTANT,
                        (listOf(m.content) + m.toolCalls.map { "[called tool ${it.name} with ${it.arguments}]" }).filter { it.isNotBlank() }.joinToString("\n"),
                    )
                    ChatRole.TOOL -> add(USER, "[tool result]\n${m.content}")
                }
            }
            val prompt = if (turns.lastOrNull()?.role == USER) turns.removeAt(turns.size - 1).text else "Continue."

            // Budget in characters: instructions get at most a third, the prompt what it needs (up
            // to the rest), history fills what is left, newest first.
            var budget = (contextTokens - maxTokens).coerceAtLeast(256) * CHARS_PER_TOKEN
            val keptInstructions = instructions.takeIf { it.isNotEmpty() }?.let { clip(it, budget / 3) }
            budget -= keptInstructions?.length ?: 0
            val keptPrompt = clip(prompt, budget)
            budget -= keptPrompt.length
            val history = ArrayDeque<Turn>()
            for (turn in turns.asReversed()) {
                if (turn.text.length > budget) break
                history.addFirst(turn)
                budget -= turn.text.length
            }
            // A transcript starts with the user.
            while (history.firstOrNull()?.role == ASSISTANT) history.removeFirst()
            return SystemPrompt(keptInstructions, history.toList(), keptPrompt, request.temperature, maxTokens)
        }

        private fun clip(text: String, max: Int): String =
            if (text.length <= max) text else text.take((max - CLIPPED.length).coerceAtLeast(0)) + CLIPPED

        private const val CLIPPED = "\n[…shortened]"
    }
}

package dev.wckdboy.autobot.agent.runtime

import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The approvals and questions agents are waiting on. Answers are matched by session *and* call:
 * call ids are only unique within a session (models and synthesized ids repeat across chats), so
 * an answer given in one chat must never release a call waiting in another.
 */
class InteractionBoard {
    private val _items = MutableStateFlow<List<Interaction>>(emptyList())
    val items: StateFlow<List<Interaction>> = _items.asStateFlow()

    /** Shows [interaction] until it is answered (or the waiting turn is cancelled). */
    suspend fun <T> await(interaction: Interaction): T {
        _items.update { it + interaction }
        try {
            @Suppress("UNCHECKED_CAST")
            return when (interaction) {
                is Interaction.Approval -> interaction.answer.await() as T
                is Interaction.Question -> interaction.answer.await() as T
            }
        } finally {
            _items.update { list -> list.filterNot { it === interaction } }
        }
    }

    /** Answers [sessionId]'s approval for [callId]; false when nothing there waits on it. */
    fun answerApproval(sessionId: String, callId: String, outcome: ApprovalOutcome): Boolean =
        find<Interaction.Approval>(sessionId, callId)?.answer?.complete(outcome) == true

    /** Answers [sessionId]'s question [callId] (null answers: skipped); false when nothing waits on it. */
    fun answerQuestion(sessionId: String, callId: String, answers: List<String>?): Boolean =
        find<Interaction.Question>(sessionId, callId)?.answer?.complete(answers) == true

    private inline fun <reified T : Interaction> find(sessionId: String, callId: String): T? =
        _items.value.firstOrNull { it is T && it.sessionId == sessionId && it.callId == callId } as T?
}

package dev.wckdboy.autobot.feature.chat.conversation

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a system entry point hands to a chat it just started: a composer [draft] (never sent by
 * itself), [images] to attach (content URIs, re-encoded without metadata on attach) and whether to
 * open in [voice] mode.
 */
data class ChatStart(val draft: String? = null, val images: List<String> = emptyList(), val voice: Boolean = false)

/**
 * One-shot hand-off from the entry points to [ChatViewModel], kept in memory only: a hand-off lost
 * to process death is dropped rather than replayed.
 */
@Singleton
class ChatHandoff @Inject constructor() {
    private val pending = ConcurrentHashMap<String, ChatStart>()

    fun put(conversationId: String, start: ChatStart) {
        pending[conversationId] = start
    }

    /** The hand-off for [conversationId], once. */
    fun take(conversationId: String): ChatStart? = pending.remove(conversationId)
}

package dev.wckdboy.autobot.feature.chat.conversation

import android.content.Context
import android.content.Intent
import dev.wckdboy.autobot.shared.session.NoticeKind
import dev.wckdboy.autobot.shared.session.SessionItem

/**
 * "Retry": ask the last prompt again, as a new turn. Offered on the transcript's last item when it
 * is an answer or a failure ([key]), and only while nothing runs. Same rule as iOS.
 */
data class ChatRetry(val key: String, val prompt: String) {
    companion object {
        fun of(items: List<SessionItem>, running: Boolean): ChatRetry? {
            if (running) return null
            val last = items.lastOrNull() ?: return null
            val retryable = last is SessionItem.Assistant || (last is SessionItem.Notice && last.kind == NoticeKind.ERROR)
            if (!retryable) return null
            val prompt = items.lastOrNull { it is SessionItem.User } as? SessionItem.User ?: return null
            return prompt.text.takeIf { it.isNotBlank() }?.let { ChatRetry(last.key, it) }
        }
    }
}

/** Hands [text] (a message as written, markdown) to the system share sheet. */
internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

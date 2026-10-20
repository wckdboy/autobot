package dev.wckdboy.autobot.feature.chat.conversation

import android.content.ClipData
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.ActionText
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import kotlinx.coroutines.launch

/** The message menu's entries, in the order both apps show them. */
enum class MessageAction(val label: String) { COPY("Copy"), SHARE("Share"), RETRY("Retry"), READ_ALOUD("Read aloud"), SELECT("Select text") }

/** What a message's menu offers: Retry only on the last answer, Read aloud only on answers. */
fun messageActions(answer: Boolean, retry: Boolean): List<MessageAction> = buildList {
    add(MessageAction.COPY)
    add(MessageAction.SHARE)
    if (retry) add(MessageAction.RETRY)
    if (answer) add(MessageAction.READ_ALOUD)
    add(MessageAction.SELECT)
}

/** Share and Retry beside the answer's Copy and Read aloud. */
@Composable
internal fun ShareButton(text: String) {
    val context = LocalContext.current
    IconButton(onClick = { shareText(context, text) }, modifier = Modifier.size(32.dp)) {
        Icon(AutobotIcons.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun RetryButton(onRetry: () -> Unit) {
    IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
        Icon(AutobotIcons.Retry, contentDescription = "Retry", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/**
 * The long-press menu of a message: Copy, Share, Retry, Read aloud, Select text (the long press
 * replaces in-place selection, so the text opens selectable on its own).
 */
@Composable
internal fun MessageMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    text: String,
    onRetry: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selecting by remember { mutableStateOf(false) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        messageActions(answer = onSpeak != null, retry = onRetry != null).forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                onClick = {
                    onDismiss()
                    when (action) {
                        MessageAction.COPY -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text))) }
                        MessageAction.SHARE -> shareText(context, text)
                        MessageAction.RETRY -> onRetry?.invoke()
                        MessageAction.READ_ALOUD -> onSpeak?.invoke()
                        MessageAction.SELECT -> selecting = true
                    }
                },
            )
        }
    }
    if (selecting) {
        AlertDialog(
            onDismissRequest = { selecting = false },
            text = { SelectionContainer(Modifier.verticalScroll(rememberScrollState())) { Text(text, style = MaterialTheme.typography.bodyLarge) } },
            confirmButton = { TextButton(onClick = { selecting = false }) { ActionText("Done") } },
        )
    }
}

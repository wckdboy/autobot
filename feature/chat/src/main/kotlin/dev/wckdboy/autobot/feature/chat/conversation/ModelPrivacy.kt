package dev.wckdboy.autobot.feature.chat.conversation

import dev.wckdboy.autobot.core.designsystem.component.ActionText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.wckdboy.autobot.providers.remote.ModelDataPolicies

/** Asks before a chat switches to [model], whose provider trains on prompts ([ModelDataPolicies]). */
@Composable
internal fun TrainsConfirmDialog(model: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch to $model?") },
        text = { Text(ModelDataPolicies.TRAINS_WARNING + " Anything you send in this chat from now on may end up in a future model.") },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { ActionText("Switch anyway") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { ActionText("Cancel") } },
    )
}

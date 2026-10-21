package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * The composer of every chat (the phone's chat and a node's agent), the kit's piece for typing a
 * message (see Standard.kt). The caller places it under the transcript and pads the pair for the
 * keyboard once (`imePadding()`, see Keyboard.kt).
 */

/**
 * [tools] (attach, dictate: icon buttons), a filled, rounded [TextField] that grows to [maxLines]
 * lines (the shared `Composer.MAX_LINES`) and then scrolls, and the [action] (a [SendIconButton]
 * or a [StopIconButton]). [above] sits over the row (attachments, slash commands, words heard).
 * A large font leaves the field too narrow beside the tools: they take a row above it.
 */
@Composable
fun MessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 5,
    above: @Composable ColumnScope.() -> Unit = {},
    tools: (@Composable RowScope.() -> Unit)? = null,
    action: @Composable () -> Unit,
) {
    val large = isLargeText()
    Column(modifier.fillMaxWidth()) {
        above()
        if (large && tools != null) Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp), content = tools)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (!large && tools != null) Row(Modifier.padding(bottom = 4.dp), content = tools)
            // The keyboard is asked not to learn from what is typed.
            NoPersonalizedLearning {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).then(fieldModifier),
                    enabled = enabled,
                    placeholder = { Text(placeholder) },
                    maxLines = maxLines,
                    keyboardOptions = PrivateKeyboardOptions,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                )
            }
            Box(Modifier.padding(bottom = 4.dp)) { action() }
        }
    }
}

/** The composer's button side: Material's 48 dp touch target. */
val ComposerActionSize = 48.dp

/** Send (Queue while a turn runs, as [label]): a filled icon button in the primary colour. */
@Composable
fun SendIconButton(onClick: () -> Unit, enabled: Boolean, label: String = "Send") {
    FilledIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(ComposerActionSize)) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = label)
    }
}

/** Stops the running turn: a filled tonal icon button with the stop square. */
@Composable
fun StopIconButton(onClick: () -> Unit, label: String = "Stop") {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(ComposerActionSize).semantics { contentDescription = label }) {
        Box(Modifier.size(14.dp).background(LocalContentColor.current, MaterialTheme.shapes.extraSmall))
    }
}

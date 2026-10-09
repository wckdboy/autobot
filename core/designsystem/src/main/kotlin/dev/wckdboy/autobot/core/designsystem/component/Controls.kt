package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme

/**
 * Labelled dropdown field: micro-label above a bordered mono value with a caret.
 * [options] may be empty, in which case the field is read-only.
 */
@Composable
fun <T> SelectField(
    label: String,
    value: String,
    options: List<T>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (T) -> String = { it.toString() },
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    val interactive = enabled && options.isNotEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MicroLabel(label)
        Box {
            Row(
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                    .clickable(enabled = interactive, role = Role.DropdownList, onClickLabel = "Choose $label") { open = true }
                    .heightIn(min = 36.dp)
                    .padding(start = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value.ifBlank { "—" },
                    modifier = Modifier.weight(1f, fill = false).widthIn(max = 280.dp),
                    style = AutobotTheme.styles.code,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (interactive) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Box(Modifier.size(8.dp))
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option), style = AutobotTheme.styles.code, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/** `[-] 4 [+]` integer stepper with a micro-label. */
@Composable
fun Stepper(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MicroLabel(label)
        Row(
            Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepperButton("−", enabled = value > range.first) { onValueChange(value - 1) }
            Text(
                value.toString(),
                modifier = Modifier.widthIn(min = 32.dp).padding(horizontal = 4.dp),
                style = AutobotTheme.styles.readout,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            StepperButton("+", enabled = value < range.last) { onValueChange(value + 1) }
        }
    }
}

@Composable
private fun StepperButton(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = AutobotTheme.styles.readout,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Hairline-bordered text input for prompts and fields. The keyboard is asked not to learn from
 * what is typed. [mono] switches to the code face (prompts, URLs, ids) and, unless
 * [keyboardOptions] says otherwise, turns off auto-capitalization and autocorrect.
 */
@Composable
fun ConsoleTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    singleLine: Boolean = false,
    mono: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    NoPersonalizedLearning {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            textStyle = if (mono) AutobotTheme.styles.code else MaterialTheme.typography.bodyMedium,
            placeholder = placeholder?.let { { Text(it, style = if (mono) AutobotTheme.styles.code else MaterialTheme.typography.bodyMedium) } },
            label = label?.let { { Text(it.uppercase(java.util.Locale.ROOT), style = AutobotTheme.styles.micro) } },
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxLines,
            singleLine = singleLine,
            keyboardOptions = keyboardOptions ?: if (mono) PromptKeyboardOptions else PrivateKeyboardOptions,
            keyboardActions = keyboardActions,
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
            shape = MaterialTheme.shapes.small,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

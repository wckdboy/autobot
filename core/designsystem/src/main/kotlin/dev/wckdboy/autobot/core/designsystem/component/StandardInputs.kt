package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme

/*
 * Fields and single choices, the kit's input pieces (see Standard.kt). The keyboard learns
 * nothing from any of them ([NoPersonalizedLearning]).
 */

/**
 * Material's outlined text field that keeps the keyboard from learning what is typed. [mono] uses
 * the code face (URLs, ids, keys) and turns off capitalization and autocorrect. [value] may arrive
 * late from a view model: edits show at once and stale echoes are ignored.
 */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    mono: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val (shown, change) = rememberBufferedText(value, onValueChange)
    NoPersonalizedLearning {
        OutlinedTextField(
            value = shown,
            onValueChange = change,
            modifier = modifier,
            enabled = enabled,
            textStyle = if (mono) AutobotTheme.styles.code else MaterialTheme.typography.bodyLarge,
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            supportingText = supporting?.let { { Text(it) } },
            isError = isError,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            maxLines = if (singleLine) 1 else maxLines,
            keyboardOptions = fieldKeyboardOptions(keyboardOptions, mono, visualTransformation),
            keyboardActions = keyboardActions,
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
        )
    }
}

/**
 * A [Field]'s keyboard: [given], else the code or the prose keyboard. A masked field (a key, a
 * token, a pairing code) always gets a password keyboard: a text or URL keyboard still shows what
 * was typed in its suggestion strip, the secret the mask hides.
 */
internal fun fieldKeyboardOptions(given: KeyboardOptions?, mono: Boolean, visual: VisualTransformation): KeyboardOptions {
    val options = given ?: if (mono) PromptKeyboardOptions else PrivateKeyboardOptions
    if (visual !is PasswordVisualTransformation) return options
    if (options.keyboardType == KeyboardType.Password || options.keyboardType == KeyboardType.NumberPassword) return options.copy(autoCorrectEnabled = false)
    return options.copy(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None)
}

/** A search field: Material's rounded outlined field with the search icon. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null) {
    NoPersonalizedLearning {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            placeholder = { Text(placeholder) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            keyboardOptions = if (onSearch == null) PromptKeyboardOptions else PromptKeyboardOptions.copy(imeAction = ImeAction.Search),
            keyboardActions = if (onSearch == null) KeyboardActions.Default else KeyboardActions(onSearch = { onSearch() }),
        )
    }
}

/**
 * A single choice among a few short options: Material's segmented buttons, without the check
 * mark (the fill says which). A label stays on one line and steps its size down before it would
 * break a word ("Workspace-writ / e" on a phone); at a large font, where the segments would
 * break their words anyway, the options wrap as [ChoiceChips] instead.
 */
@Composable
fun <T> Choices(options: List<T>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, label: (T) -> String) {
    if (isLargeText()) {
        ChoiceChips(options, selected, onSelect, label, modifier)
        return
    }
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                icon = {},
                label = { SegmentLabel(label(option)) },
            )
        }
    }
}

/** One segment's label: one line, shrinking (down to [SEGMENT_MIN]) rather than wrapping. */
@Composable
internal fun SegmentLabel(text: String) {
    val style = LocalTextStyle.current
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        autoSize = if (style.fontSize.isSp) TextAutoSize.StepBased(minFontSize = SEGMENT_MIN, maxFontSize = style.fontSize, stepSize = 0.5.sp) else null,
    )
}

private val SEGMENT_MIN = 10.sp

/**
 * A single choice among options too many or too long for segments: Material filter chips that
 * wrap to another line on a narrow window or at a large font. Read as radio buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    FlowRow(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
                modifier = Modifier.semantics { role = Role.RadioButton },
            )
        }
    }
}

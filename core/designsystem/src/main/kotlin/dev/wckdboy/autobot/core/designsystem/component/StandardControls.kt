package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.icon.MaterialSymbols
import java.util.Locale

/*
 * Value controls, the kit's pieces for numbers and picks (see Standard.kt): Material's Slider,
 * outlined icon buttons around a number, and the exposed dropdown menu.
 */

/**
 * A labelled [Slider] with its value at the end of the label row (`CFG ········ 7.0`).
 *
 * @param steps discrete steps between the ends (as in [Slider]); 0 for continuous.
 */
@Composable
fun SliderField(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    format: (Float) -> String = { String.format(Locale.ROOT, "%.2f", it) },
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(labelCase(label), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(format(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = labelCase(label) },
        )
    }
}

/** A whole number in [range] with − and + outlined icon buttons, under its [label]. */
@Composable
fun StepperField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(labelCase(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedIconButton(onClick = { onValueChange(value - 1) }, enabled = value > range.first) {
                Icon(MaterialSymbols.Remove, contentDescription = "Fewer ${labelCase(label).lowercase(Locale.ROOT)}")
            }
            Text(
                value.toString(),
                Modifier.widthIn(min = 32.dp).semantics { stateDescription = value.toString() },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            OutlinedIconButton(onClick = { onValueChange(value + 1) }, enabled = value < range.last) {
                Icon(Icons.Filled.Add, contentDescription = "More ${labelCase(label).lowercase(Locale.ROOT)}")
            }
        }
    }
}

/**
 * A pick among [options] as Material's exposed dropdown menu: an outlined, read-only field
 * showing [value] under its [label]. With no [options] (or not [enabled]) it only shows the value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(
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
    ExposedDropdownMenuBox(expanded = open && interactive, onExpandedChange = { if (interactive) open = it }, modifier = modifier) {
        OutlinedTextField(
            value = value.ifBlank { "—" },
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(labelCase(label)) },
            trailingIcon = if (interactive) {
                { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, interactive),
        )
        ExposedDropdownMenu(expanded = open && interactive, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

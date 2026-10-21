package dev.wckdboy.autobot.feature.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import dev.wckdboy.autobot.core.designsystem.component.Caption
import dev.wckdboy.autobot.core.designsystem.component.Choices
import dev.wckdboy.autobot.core.designsystem.component.DropdownField
import dev.wckdboy.autobot.core.designsystem.component.Field
import dev.wckdboy.autobot.core.designsystem.component.SheetLabel
import dev.wckdboy.autobot.core.designsystem.component.StepperField
import dev.wckdboy.autobot.core.designsystem.component.clearListItem
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.PromptKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.motion.inkStagger
import dev.wckdboy.autobot.shared.studio.GridAxis
import dev.wckdboy.autobot.shared.studio.GridPlanner
import dev.wckdboy.autobot.shared.studio.PromptLibrary
import dev.wckdboy.autobot.shared.studio.PromptSnippet
import dev.wckdboy.autobot.shared.studio.SeedMode
import dev.wckdboy.autobot.shared.studio.Seeds
import dev.wckdboy.autobot.shared.studio.StudioAsset
import dev.wckdboy.autobot.shared.studio.Wildcards

/**
 * The prompt in Material's outlined field, with the weighted-token helper: select words (or put
 * the cursor in one) and press − / + to change its emphasis, `(word:1.1)`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptEditor(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    onOpenLibrary: (() -> Unit)? = null,
    cursor: (Int) -> Unit = {},
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    Column(modifier) {
        NoPersonalizedLearning {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    field = it
                    cursor(it.selection.end)
                    if (it.text != value) onChange(it.text)
                },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                textStyle = style,
                placeholder = { Text(placeholder, style = style) },
                minLines = 3,
                keyboardOptions = PromptKeyboardOptions,
            )
        }
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(onClick = { weight(field, -0.1) { field = it; onChange(it.text) } }, label = { Text("− Weight") })
            AssistChip(onClick = { weight(field, 0.1) { field = it; onChange(it.text) } }, label = { Text("+ Weight") })
            onOpenLibrary?.let { AssistChip(onClick = it, label = { Text("Library") }) }
        }
    }
}

private fun weight(field: TextFieldValue, delta: Double, apply: (TextFieldValue) -> Unit) {
    if (field.text.isBlank()) return
    val (text, start, end) = PromptLibrary.adjustWeight(field.text, field.selection.min, field.selection.max, delta)
    apply(TextFieldValue(text, TextRange(start, end)))
}

/** `{red|blue}` → how many prompts the wildcards make, with the first few. */
@Composable
fun WildcardPreview(prompt: String, batch: Int, modifier: Modifier = Modifier) {
    if (!Wildcards.hasWildcards(prompt)) return
    val expansion = remember(prompt) { Wildcards.expand(prompt) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val jobs = (expansion.prompts.size * batch).coerceAtMost(Wildcards.DEFAULT_CAP)
        Caption(
            "wildcards · ${expansion.total} prompt${if (expansion.total == 1L) "" else "s"} × $batch = $jobs job${if (jobs == 1) "" else "s"}" +
                if (expansion.total > expansion.prompts.size) " (first ${expansion.prompts.size})" else "",
            color = MaterialTheme.colorScheme.primary,
        )
        expansion.prompts.take(3).forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** Seed: random each time, locked (compare prompts / params), or walking from a base (+1 per image). */
@Composable
fun SeedControl(seed: Long?, mode: SeedMode, onSeed: (Long?) -> Unit, onMode: (SeedMode) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Choices(SeedMode.entries, mode, onMode, Modifier.fillMaxWidth()) { labelCase(it.name.lowercase()) }
        if (mode != SeedMode.RANDOM) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    NumberField(if (mode == SeedMode.WALK) "Base seed" else "Seed", seed?.toString().orEmpty(), integer = true) { onSeed(it.toLongOrNull()?.coerceIn(0, Seeds.MAX)) }
                }
                IconButton(onClick = { onSeed(Seeds.random()) }) { Icon(AutobotIcons.Dice, contentDescription = "New random seed") }
            }
        }
    }
}

/** Prompt library: built-in and saved styles, saved snippets; save the current prompt as either. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PromptLibrarySheet(
    saved: List<PromptSnippet>,
    prompt: String,
    onInsert: (PromptSnippet) -> Unit,
    onApplyStyle: (PromptSnippet) -> Unit,
    onSave: (name: String, kind: String) -> Unit,
    onDelete: (PromptSnippet) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SheetLabel("prompt library", Modifier.inkStagger(0))
            Caption("styles wrap the prompt · snippets go in at the cursor")
            FlowRow(Modifier.inkStagger(1), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (PromptLibrary.builtInStyles + saved.filter { it.kind == PromptSnippet.STYLE }).forEach { s ->
                    SuggestionChip(onClick = { onApplyStyle(s); onDismiss() }, label = { Text(s.name) })
                }
            }
            val snippets = saved.filter { it.kind == PromptSnippet.SNIPPET }
            SheetLabel("snippets · ${snippets.size}")
            if (snippets.isEmpty()) Caption("none yet — save part of a prompt below")
            snippets.forEach { s ->
                ListItem(
                    headlineContent = { Text(s.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { TextButton(onClick = { onDelete(s) }) { Text("Delete") } },
                    colors = clearListItem(),
                    modifier = Modifier.clickable { onInsert(s); onDismiss() },
                )
            }
            if (prompt.isNotBlank()) {
                Field(name, { name = it }, Modifier.fillMaxWidth(), label = "Save the current prompt as", singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSave(name, PromptSnippet.SNIPPET); name = "" }, Modifier.weight(1f), enabled = name.isNotBlank()) { Text("Snippet") }
                    OutlinedButton(onClick = { onSave(name, PromptSnippet.STYLE); name = "" }, Modifier.weight(1f), enabled = name.isNotBlank()) { Text("Style") }
                }
                Caption("a style keeps the prompt as its template: {prompt} marks where the next prompt goes")
            }
        }
    }
}

/** Negative-prompt presets as chips; each joins the negative without duplicating terms. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NegativePresets(negative: String, onNegative: (String) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        Text("Presets", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PromptLibrary.negativePresets.forEach { p ->
            SuggestionChip(onClick = { onNegative(PromptLibrary.joinNegative(negative, p.text)) }, label = { Text("+ ${labelCase(p.name.lowercase())}") })
        }
    }
}

/** One grid parameter the image room offers. */
data class GridParam(val name: String, val label: String, val integer: Boolean, val hint: String)

/**
 * The X/Y grid builder: one or two parameters over typed value lists (`10, 20, 30`, `5-9`,
 * `1-2 (+0.25)`, `euler, dpmpp_2m`); shows the cell count before queueing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GridBuilderSheet(params: List<GridParam>, onQueue: (GridAxis, GridAxis?) -> Unit, onDismiss: () -> Unit) {
    var x by rememberSaveable { mutableStateOf(params.first().name) }
    var xValues by rememberSaveable { mutableStateOf("") }
    var useY by rememberSaveable { mutableStateOf(false) }
    var y by rememberSaveable { mutableStateOf(params.getOrNull(1)?.name ?: params.first().name) }
    var yValues by rememberSaveable { mutableStateOf("") }
    val px = params.first { it.name == x }
    val py = params.first { it.name == y }
    val xs = remember(xValues, x) { GridPlanner.parseValues(xValues, px.integer) }
    val ys = remember(yValues, y) { GridPlanner.parseValues(yValues, py.integer) }
    val cells = xs.size * if (useY) ys.size else 1
    val valid = xs.isNotEmpty() && (!useY || (ys.isNotEmpty() && x != y))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetLabel("x / y grid", Modifier.inkStagger(0))
            DropdownField("X axis", px.label, params, { x = it.name }, Modifier.fillMaxWidth(), optionLabel = { it.label })
            Field(xValues, { xValues = it }, Modifier.fillMaxWidth(), label = "X values", placeholder = px.hint, singleLine = true, mono = true)
            Row(
                Modifier.fillMaxWidth().toggleable(useY, role = Role.Switch, onValueChange = { useY = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Second axis", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = useY, onCheckedChange = null)
            }
            if (useY) {
                DropdownField("Y axis", py.label, params, { y = it.name }, Modifier.fillMaxWidth(), optionLabel = { it.label })
                Field(yValues, { yValues = it }, Modifier.fillMaxWidth(), label = "Y values", placeholder = py.hint, singleLine = true, mono = true)
                if (x == y) Caption("x and y must differ", color = MaterialTheme.colorScheme.error)
            }
            Caption(
                if (cells > GridPlanner.DEFAULT_CAP) "$cells cells · only the first ${GridPlanner.DEFAULT_CAP} are queued" else "${counted(cells, "cell")} · one job each",
                color = if (cells > GridPlanner.DEFAULT_CAP) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                onQueue(GridAxis(x, xs, px.label), if (useY) GridAxis(y, ys, py.label) else null)
                onDismiss()
            }, Modifier.fillMaxWidth(), enabled = valid) { Text("Queue grid") }
        }
    }
}

/** A grid's results with its axis labels; empty cells develop while their job runs. */
@Composable
fun GridResults(
    columns: List<String>,
    rows: List<String>,
    xLabel: String,
    yLabel: String?,
    cell: (row: Int, column: Int) -> StudioAsset?,
    onOpen: (StudioAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowLabels = rows.ifEmpty { listOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Caption(listOfNotNull("x · $xLabel", yLabel?.let { "y · $it" }).joinToString("   "))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (rows.isNotEmpty()) Box(Modifier.width(44.dp))
            columns.forEach { c -> Text(c, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        rowLabels.forEachIndexed { r, label ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (rows.isNotEmpty()) Text(label, Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                columns.indices.forEach { c ->
                    val asset = cell(r, c)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .semantics { contentDescription = "$xLabel ${columns[c]}" + (yLabel?.let { ", $it $label" } ?: "") },
                    ) {
                        if (asset != null) AssetThumb(asset, Modifier.fillMaxSize().clickable { onOpen(asset) })
                    }
                }
            }
        }
    }
}

/** Batch size for queued work (one job per image so each is its own plate). */
@Composable
fun BatchStepper(value: Int, onValue: (Int) -> Unit, max: Int = 16, modifier: Modifier = Modifier) {
    StepperField("Batch", value, onValue, 1..max, modifier)
}

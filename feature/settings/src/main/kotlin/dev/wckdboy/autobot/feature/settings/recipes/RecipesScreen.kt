package dev.wckdboy.autobot.feature.settings.recipes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.agent.runtime.recipes.RecipeModelChoice
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.SwitchRow
import dev.wckdboy.autobot.core.designsystem.component.rememberBufferedText
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.shared.recipes.Recipe
import dev.wckdboy.autobot.shared.recipes.RecipeCopy
import dev.wckdboy.autobot.shared.recipes.RecipeRunStatus
import dev.wckdboy.autobot.shared.recipes.RecipeSchedule
import dev.wckdboy.autobot.shared.recipes.RecipeTools

@Composable
fun RecipesRoute(onBack: () -> Unit, onOpenChat: (String) -> Unit, viewModel: RecipesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Results arrive as notifications: ask once, when the user first switches a recipe on or runs one.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun askForNotifications() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    RecipesScreen(
        state = state,
        onBack = onBack,
        onAdd = viewModel::startAdd,
        onEdit = viewModel::startEdit,
        onEnabled = { id, on ->
            if (on) askForNotifications()
            viewModel.setEnabled(id, on)
        },
        onRunNow = {
            askForNotifications()
            viewModel.runNow(it)
        },
        onOpenChat = onOpenChat,
        onSummary = viewModel::setSummaryInNotifications,
        onDraftChange = viewModel::updateDraft,
        onSave = viewModel::save,
        onDelete = viewModel::delete,
        onDismissEditor = viewModel::dismissEditor,
    )
}

@Composable
fun RecipesScreen(
    state: RecipesUiState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onRunNow: (String) -> Unit,
    onOpenChat: (String) -> Unit,
    onSummary: (Boolean) -> Unit,
    onDraftChange: ((Recipe) -> Recipe) -> Unit,
    onSave: () -> Unit,
    onDelete: (String) -> Unit,
    onDismissEditor: () -> Unit,
) {
    Scaffold(
        topBar = {
            AutobotTopBar(
                title = RecipeCopy.SCREEN_TITLE,
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = RecipeCopy.NEW_RECIPE) }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (state.loaded && state.models.isEmpty()) {
                item { Note(RecipeCopy.NO_MODEL, MaterialTheme.colorScheme.error) }
            }
            if (state.loaded && state.recipes.isEmpty()) item { Note(RecipeCopy.EMPTY) }
            items(state.recipes, key = { it.id }, contentType = { "recipe" }) { row ->
                RecipeListRow(row, onEdit, onEnabled, onRunNow, onOpenChat)
                HorizontalDivider()
            }
            item {
                SectionLabel("Notifications", Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
                SwitchRow(RecipeCopy.SUMMARY_SWITCH, state.summaryInNotifications, onSummary, info = RecipeCopy.SUMMARY_DETAIL)
                Note(RecipeCopy.ANDROID_NOTE)
            }
        }
    }
    state.editor?.let { draft ->
        RecipeEditorSheet(
            draft = draft,
            isNew = state.editorIsNew,
            problems = state.problems,
            models = state.models,
            onDraftChange = onDraftChange,
            onSave = onSave,
            onDelete = { onDelete(draft.id) },
            onDismiss = onDismissEditor,
        )
    }
}

@Composable
private fun RecipeListRow(
    row: RecipeRowUi,
    onEdit: (String) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onRunNow: (String) -> Unit,
    onOpenChat: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onEdit(row.id) }
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.schedule, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = row.enabled, onCheckedChange = { onEnabled(row.id, it) })
        }
        Text(
            row.status,
            style = MaterialTheme.typography.bodySmall,
            color = if (row.lastStatus == RecipeRunStatus.DONE || row.lastStatus == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
        row.lastSummary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        row.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = AutobotColors.Amber) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onRunNow(row.id) }, enabled = !row.running) { Text(if (row.running) "Running…" else RecipeCopy.RUN_NOW) }
            row.lastChat?.let { chat -> TextButton(onClick = { onOpenChat(chat) }) { Text(RecipeCopy.OPEN_RESULT) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RecipeEditorSheet(
    draft: Recipe,
    isNew: Boolean,
    problems: List<String>,
    models: List<RecipeModelChoice>,
    onDraftChange: ((Recipe) -> Recipe) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pickingTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        NoPersonalizedLearning {
            Column(
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (isNew) RecipeCopy.NEW_RECIPE else "Edit recipe", style = MaterialTheme.typography.titleLarge)
                val (name, onName) = rememberBufferedText(draft.name) { v -> onDraftChange { it.copy(name = v) } }
                val (prompt, onPrompt) = rememberBufferedText(draft.prompt) { v -> onDraftChange { it.copy(prompt = v) } }
                OutlinedTextField(name, onName, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    prompt,
                    onPrompt,
                    label = { Text("What it should do") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    minLines = 4,
                )

                SectionLabel("Schedule")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecipeSchedule.Kind.entries.forEach { kind ->
                        FilterChip(
                            selected = draft.schedule.kind == kind,
                            onClick = { onDraftChange { it.copy(schedule = RecipeSchedule.of(kind, it.schedule)) } },
                            label = { Text(kind.label) },
                        )
                    }
                }
                val schedule = draft.schedule
                if (schedule is RecipeSchedule.Weekly) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..7).forEach { day ->
                            FilterChip(
                                selected = schedule.dayOfWeek == day,
                                onClick = { onDraftChange { it.copy(schedule = schedule.copy(dayOfWeek = day)) } },
                                label = { Text(RecipeSchedule.dayName(day).take(3)) },
                            )
                        }
                    }
                }
                if (schedule is RecipeSchedule.EveryHours) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3, 4, 6, 8, 12, 24).forEach { h ->
                            FilterChip(selected = schedule.hours == h, onClick = { onDraftChange { it.copy(schedule = RecipeSchedule.EveryHours(h)) } }, label = { Text("${h}h") })
                        }
                    }
                }
                schedule.minute?.let { minute ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(schedule.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { pickingTime = true }) { Text(RecipeSchedule.clock(minute)) }
                    }
                } ?: Text(schedule.label(), style = MaterialTheme.typography.bodyLarge)

                SectionLabel(RecipeCopy.MODEL_HEADER)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = draft.model.isBlank(), onClick = { onDraftChange { it.copy(model = "") } }, label = { Text(RecipeCopy.MODEL_DEFAULT) })
                    models.forEach { m ->
                        FilterChip(selected = draft.model == m.id, onClick = { onDraftChange { it.copy(model = m.id) } }, label = { Text(m.title) })
                    }
                }
                if (models.isEmpty()) Text(RecipeCopy.NO_MODEL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

                SectionLabel(RecipeCopy.TOOLS_HEADER)
                Text(RecipeCopy.TOOLS_DETAIL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    RecipeTools.groups.forEach { group ->
                        SwitchRow(
                            group.label,
                            RecipeTools.isOn(draft.tools, group),
                            { on -> onDraftChange { it.copy(tools = RecipeTools.toggle(it.tools, group, on)) } },
                            info = group.detail,
                        )
                    }
                    SwitchRow(RecipeCopy.RECENT_CHATS, draft.includeRecentChats, { on -> onDraftChange { it.copy(includeRecentChats = on) } }, info = RecipeCopy.RECENT_CHATS_DETAIL)
                    SwitchRow(RecipeCopy.CHARGING, draft.requiresCharging, { on -> onDraftChange { it.copy(requiresCharging = on) } })
                    SwitchRow(RecipeCopy.BATTERY, draft.requiresBatteryNotLow, { on -> onDraftChange { it.copy(requiresBatteryNotLow = on) } })
                    SwitchRow("Enabled", draft.enabled, { on -> onDraftChange { it.copy(enabled = on) } })
                }
                if (draft.usesNetwork) Text(RecipeCopy.NETWORK_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text(RecipeCopy.DELETE, color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = onSave, enabled = problems.isEmpty()) { Text("Save") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    if (pickingTime) {
        val minute = draft.schedule.minute ?: RecipeSchedule.DEFAULT_MINUTE
        val picker = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton(onClick = {
                    val m = picker.hour * 60 + picker.minute
                    onDraftChange { r ->
                        r.copy(
                            schedule = when (val s = r.schedule) {
                                is RecipeSchedule.Daily -> s.copy(minuteOfDay = m)
                                is RecipeSchedule.Weekdays -> s.copy(minuteOfDay = m)
                                is RecipeSchedule.Weekly -> s.copy(minuteOfDay = m)
                                is RecipeSchedule.EveryHours -> s
                            },
                        )
                    }
                    pickingTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel") } },
            text = { TimePicker(picker) },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${draft.name.ifBlank { "this recipe" }}?") },
            text = { Text("Its schedule stops. Chats it already wrote stay in the Recipes folder.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = color)

@Preview(showBackground = true, backgroundColor = 0xFF0B0C0F)
@Composable
private fun RecipesScreenPreview() {
    AutobotTheme {
        RecipesScreen(
            state = RecipesUiState(
                loaded = true,
                models = listOf(RecipeModelChoice("qwen", "Qwen3 4B")),
                recipes = listOf(
                    RecipeRowUi("1", "Morning brief from my feeds", "Every day at 07:00", "Next: tomorrow 07:00 · Done today 07:03", true, false, RecipeRunStatus.DONE, "Five headlines from three feeds.", emptyList(), "c1"),
                    RecipeRowUi("2", "Weekly notes tidy", "Fridays at 18:00", "Off", false, false, null, null, emptyList(), null),
                ),
            ),
            onBack = {}, onAdd = {}, onEdit = {}, onEnabled = { _, _ -> }, onRunNow = {}, onOpenChat = {}, onSummary = {},
            onDraftChange = {}, onSave = {}, onDelete = {}, onDismissEditor = {},
        )
    }
}

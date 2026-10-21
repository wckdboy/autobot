package dev.wckdboy.autobot.feature.home.node

import dev.wckdboy.autobot.core.designsystem.component.NodeMark
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.feature.home.remoteui.Field
import dev.wckdboy.autobot.feature.home.remoteui.Loading
import dev.wckdboy.autobot.feature.home.remoteui.NoticeCard
import dev.wckdboy.autobot.feature.home.remoteui.Tone
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Switch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.shared.invincible.AgentSchedule
import java.text.DateFormat
import java.util.Date

/** Tasks the agent runs on a schedule on the machine: list, remove, add. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeSchedulesSheet(
    state: NodeAgentUiState,
    onDismiss: () -> Unit,
    onAdd: (schedule: String, task: String, telegram: Boolean, onAdded: () -> Unit) -> Unit,
    onRemove: (String) -> Unit,
) {
    var schedule by rememberSaveable { mutableStateOf("") }
    var task by rememberSaveable { mutableStateOf("") }
    var telegram by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NodeMark()
                Text(labelCase("scheduled on ${state.node}"), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "Runs on the machine with nobody to ask: only allowlisted commands and writes in its working directory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.scheduleProblem?.let { NoticeCard(it, tone = Tone.WARNING) }
            when (val list = state.schedules) {
                null -> Loading()
                else -> {
                    if (list.isEmpty()) Text("Nothing scheduled yet.", style = MaterialTheme.typography.bodyMedium)
                    list.forEach { ScheduleRow(it, onRemove) }
                }
            }
            SheetLabel("Add a schedule")
            Field(schedule, { schedule = it }, Modifier.fillMaxWidth(), placeholder = "0 8 * * 1-5 · @daily · every 30m", singleLine = true, mono = true)
            Field(task, { task = it }, Modifier.fillMaxWidth(), placeholder = "What to do, e.g. check disk space and warn me", minLines = 2, maxLines = 5)
            ListItem(
                headlineContent = { Text("Send answers to Telegram") },
                trailingContent = { Switch(checked = telegram, onCheckedChange = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.toggleable(value = telegram, role = Role.Switch) { telegram = it },
            )
            Button(
                onClick = {
                    onAdd(schedule, task, telegram) {
                        schedule = ""
                        task = ""
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                enabled = schedule.isNotBlank() && task.isNotBlank(),
            ) { Text("Schedule") }
        }
    }
}

@Composable
private fun ScheduleRow(item: AgentSchedule, onRemove: (String) -> Unit) {
    val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(item.task, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(item.schedule, item.next?.let { "next ${format.format(Date(it * 1000))}" }, "→ Telegram".takeIf { item.telegram }).joinToString(" · "),
                style = AutobotTheme.styles.code,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            item.lastAt?.let { lastAt ->
                Text(
                    "${if (item.lastOk == true) "✓" else "✗"} ${format.format(Date(lastAt * 1000))} · ${item.lastResult.orEmpty().lineSequence().firstOrNull().orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    color = if (item.lastOk == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
        }
        IconButton(onClick = { onRemove(item.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove ${item.task}") }
    }
}

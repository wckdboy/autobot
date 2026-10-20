package dev.wckdboy.autobot.feature.home.node

import dev.wckdboy.autobot.core.designsystem.component.NodeMark
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.feature.home.remoteui.Field
import dev.wckdboy.autobot.feature.home.remoteui.Loading
import dev.wckdboy.autobot.feature.home.remoteui.NoticeCard
import dev.wckdboy.autobot.feature.home.remoteui.Tone
import androidx.compose.material3.Button
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import dev.wckdboy.autobot.shared.invincible.AgentFact
import dev.wckdboy.autobot.shared.invincible.NodeActivity
import dev.wckdboy.autobot.shared.invincible.NodeActivityWords
import java.text.DateFormat
import java.util.Date

private val TABS = listOf("Requests", "Actions", "Board", "Memory")

/** The Board tab's index, to open the sheet on it. */
internal const val BOARD_TAB = 2

/** What the node answered, what the agent did, and what it remembers: from the node's own logs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeActivitySheet(
    state: NodeAgentUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onForget: (Long) -> Unit,
    onAnswer: (id: String, text: String, onDone: () -> Unit) -> Unit = { _, _, _ -> },
    initialTab: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NodeMark()
                Text(labelCase("activity on ${state.node}"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            }
            SecondaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                TABS.forEachIndexed { i, name ->
                    val waiting = state.activity?.waiting?.size ?: 0
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(if (name == "Board" && waiting > 0) "Board · $waiting" else name) })
                }
            }
            state.activityProblem?.let { NoticeCard(it, tone = Tone.WARNING) }
            val activity = state.activity
            when {
                activity == null && state.activityProblem == null -> Loading()
                activity == null -> Unit
                tab == 0 -> Requests(activity)
                tab == 1 -> Actions(activity)
                tab == 2 -> Board(activity, onAnswer)
                else -> Memory(activity, onForget)
            }
            Text("", Modifier.padding(bottom = 8.dp))
        }
    }
}

private val format: DateFormat get() = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

@Composable
private fun Requests(activity: NodeActivity) {
    Text(NodeActivityWords.summary(activity), style = MaterialTheme.typography.bodyMedium)
    if (activity.requests.isEmpty()) Text("The node has not answered anything yet.", style = MaterialTheme.typography.bodySmall)
    activity.requests.forEach { r ->
        Column {
            Text("${NodeActivityWords.who(r)} · ${NodeActivityWords.where(r)}", style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    format.format(Date(r.at * 1000)),
                    r.receipt.let { if (it.verified) "signed ✓" else "not verified" },
                    NodeActivityWords.left(r),
                ).joinToString(" · "),
                style = AutobotTheme.styles.code,
                color = if (r.receipt.egress.isEmpty() && r.receipt.verified) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Actions(activity: NodeActivity) {
    activity.agentProblem?.let {
        NoticeCard(it, tone = Tone.WARNING)
        return
    }
    if (activity.actions.isEmpty()) Text("The agent has not asked to do anything yet.", style = MaterialTheme.typography.bodySmall)
    activity.actions.forEach { a ->
        Column {
            Text(a.action, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 3)
            Text(
                "${format.format(Date(a.at * 1000))} · ${NodeActivityWords.decided(a)} · ${a.session}" + (a.bot?.let { " · bot $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (a.ran) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun Board(activity: NodeActivity, onAnswer: (String, String, () -> Unit) -> Unit) {
    activity.agentProblem?.let {
        NoticeCard(it, tone = Tone.WARNING)
        return
    }
    if (activity.board.isEmpty()) {
        Text("Nothing on the board. When the agent plans work for its team (board_plan), its tasks show here.", style = MaterialTheme.typography.bodySmall)
    }
    var plan: String? = null
    activity.board.forEach { task ->
        if (task.plan != plan) {
            plan = task.plan
            SheetLabel("plan ${task.plan}")
        }
        Column(Modifier.fillMaxWidth()) {
            Text(NodeActivityWords.task(task), style = MaterialTheme.typography.bodyMedium)
            task.result?.takeIf { task.status == "done" || task.status == "failed" }?.let {
                Text(it.lineSequence().firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 3, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (task.waiting) {
                var answer by rememberSaveable(task.id) { mutableStateOf("") }
                Text("${task.agent} asks: ${task.question.orEmpty()}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Field(answer, { answer = it }, Modifier.fillMaxWidth(), placeholder = "Your answer", minLines = 1, maxLines = 4)
                Button(onClick = { onAnswer(task.id, answer) { answer = "" } }, Modifier.fillMaxWidth(), enabled = answer.isNotBlank()) { Text("Answer") }
            }
        }
    }
}

@Composable
private fun Memory(activity: NodeActivity, onForget: (Long) -> Unit) {
    activity.agentProblem?.let {
        NoticeCard(it, tone = Tone.WARNING)
        return
    }
    SheetLabel("remembered")
    if (activity.facts.isEmpty()) Text("Nothing yet. The agent keeps short facts when they will help later.", style = MaterialTheme.typography.bodySmall)
    activity.facts.forEach { fact -> FactRow(fact, onForget) }
    SheetLabel("skills")
    if (activity.skills.isEmpty()) {
        Text("None yet: put Markdown how-to notes in ~/.autobot/skills on the machine.", style = MaterialTheme.typography.bodySmall)
    }
    activity.skills.forEach { skill ->
        Column {
            Text(skill.name, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
            Text(skill.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FactRow(fact: AgentFact, onForget: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(fact.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = { onForget(fact.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Forget: ${fact.text}") }
    }
}

/** A group's label inside the sheet (a plan, remembered, skills): labelLarge in the primary colour. */
@Composable
internal fun SheetLabel(text: String) {
    Text(labelCase(text), Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

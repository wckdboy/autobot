package dev.wckdboy.autobot.feature.home.node

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.BubbleRole
import dev.wckdboy.autobot.core.designsystem.component.ChatBubble
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.Notice
import dev.wckdboy.autobot.core.designsystem.component.NoticeTone
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.shared.invincible.AgentAction
import dev.wckdboy.autobot.shared.invincible.ApprovalAnswer

/** The Autobot Agent on a paired node: tasks run on that machine; questions come here. */
@Composable
fun NodeAgentRoute(providerId: String, onBack: () -> Unit) {
    val viewModel = hiltViewModel<NodeAgentViewModel, NodeAgentViewModel.Factory>(key = providerId) { it.create(providerId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSchedules by rememberSaveable { mutableStateOf(false) }
    var showActivity by rememberSaveable { mutableStateOf(false) }
    NodeAgentScreen(
        state,
        onBack,
        viewModel::send,
        viewModel::answer,
        viewModel::newConversation,
        viewModel::connect,
        onOpenSchedules = {
            showSchedules = true
            viewModel.loadSchedules()
        },
        onOpenActivity = {
            showActivity = true
            viewModel.loadActivity()
        },
    )
    if (showActivity) {
        NodeActivitySheet(state, onDismiss = { showActivity = false }, onRefresh = viewModel::loadActivity, onForget = viewModel::forget, onAnswer = viewModel::answerBoard)
    }
    if (showSchedules) {
        NodeSchedulesSheet(state, onDismiss = { showSchedules = false }, onAdd = viewModel::addSchedule, onRemove = viewModel::removeSchedule)
    }
}

@Composable
fun NodeAgentScreen(
    state: NodeAgentUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onAnswer: (String, ApprovalAnswer) -> Unit,
    onNewConversation: () -> Unit,
    onRetry: () -> Unit,
    onOpenSchedules: () -> Unit = {},
    onOpenActivity: () -> Unit = {},
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(state.rows.size) { if (state.rows.isNotEmpty()) list.animateScrollToItem(state.rows.size - 1) }
    Scaffold(
        topBar = {
            AutobotTopBar(
                title = "Agent · ${state.node}",
                showMark = false,
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = onOpenActivity) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Activity: what the node and the agent did")
                    }
                    IconButton(onClick = onOpenSchedules, enabled = state.info != null) {
                        Icon(Icons.Filled.DateRange, contentDescription = "Scheduled tasks")
                    }
                    IconButton(onClick = onNewConversation, enabled = !state.running && state.info != null) {
                        Icon(Icons.Filled.Refresh, contentDescription = "New conversation")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "head") {
                    when {
                        state.problem != null -> Notice(state.problem, tone = NoticeTone.WARNING, action = "Retry", onAction = onRetry)
                        state.info == null -> InkLoader()
                        else -> SectionLabel(
                            listOfNotNull(
                                "runs on ${state.node}",
                                "approvals: ${state.info.approvals}",
                                "local models only".takeIf { state.info.private },
                                // The end of the path is the part that tells folders apart.
                                state.info.workdir.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/", prefix = "…/"),
                            ).joinToString(" · "),
                        )
                    }
                }
                items(state.rows, key = { it.key }) { row -> AgentRowView(row, onAnswer) }
                if (state.running) item(key = "busy") { InkLoader() }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ConsoleTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = "A task for your machine",
                    maxLines = 5,
                    enabled = state.info != null,
                )
                IconButton(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank() && !state.running && state.info != null,
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}

@Composable
private fun AgentRowView(row: AgentRow, onAnswer: (String, ApprovalAnswer) -> Unit) {
    when (row) {
        is AgentRow.User -> ChatBubble(BubbleRole.USER, row.text)
        is AgentRow.Reply -> ChatBubble(
            BubbleRole.ASSISTANT,
            row.text,
            footer = row.route?.let { route -> { Text(route, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        )
        is AgentRow.Note -> Text(
            (if (row.tone == AgentRow.Tone.TOOL) "› " else "✗ ") + row.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (row.tone == AgentRow.Tone.TOOL) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        is AgentRow.Question -> QuestionCard(row, onAnswer)
    }
}

@Composable
private fun QuestionCard(row: AgentRow.Question, onAnswer: (String, ApprovalAnswer) -> Unit) {
    val (title, detail) = when (val a = row.action) {
        is AgentAction.Command -> "Run a command?" to a.command
        is AgentAction.Write -> (if (a.inside) "Write a file?" else "Write outside the working directory?") to "${a.path} · ${a.bytes} bytes"
        is AgentAction.Read -> "Read outside the working directory?" to a.path
        is AgentAction.Cloud -> "Use a cloud model for this?" to a.reason
        is AgentAction.Fetch -> "Fetch a web page?" to a.url
        is AgentAction.Mcp -> "Use ${a.tool}?" to a.arguments
        is AgentAction.Other -> "Approve?" to row.text
    }.let { (title, detail) -> (row.agent?.let { "$it: $title" } ?: title) to detail }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small).padding(12.dp).semantics { contentDescription = "Approval: $title $detail" }) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 8.dp))
        when (row.answer) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Allow", { onAnswer(row.id, ApprovalAnswer.ONCE) })
                GhostButton("Always", { onAnswer(row.id, ApprovalAnswer.ALWAYS) })
                GhostButton("Deny", { onAnswer(row.id, ApprovalAnswer.DENY) })
            }
            ApprovalAnswer.ONCE -> Text("Allowed", style = MaterialTheme.typography.labelMedium)
            ApprovalAnswer.ALWAYS -> Text("Allowed for this conversation", style = MaterialTheme.typography.labelMedium)
            ApprovalAnswer.DENY -> Text("Denied", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

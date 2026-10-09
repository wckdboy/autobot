package dev.wckdboy.autobot.feature.chat.list

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Panel
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatus
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatusPill
import dev.wckdboy.autobot.core.designsystem.component.StatusDot
import dev.wckdboy.autobot.core.designsystem.component.hairlineEdge
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme

@Composable
fun ChatListRoute(
    onOpenChat: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is ChatListEvent.OpenChat -> onOpenChat(event.conversationId)
            }
        }
    }
    ChatListScreen(
        state = state,
        onOpenChat = onOpenChat,
        onNewChat = viewModel::newChat,
        onIncognitoChange = viewModel::setIncognito,
        onDelete = viewModel::delete,
        onOpenProviders = onOpenProviders,
        onOpenPrivacyCenter = onOpenPrivacyCenter,
    )
}

@Composable
fun ChatListScreen(
    state: ChatListUiState,
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
    onIncognitoChange: (Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onOpenProviders: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
) {
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.statusBarsPadding().hairlineEdge()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("AUTOBOT", style = MaterialTheme.typography.titleLarge)
                            MicroLabel("${state.conversations.size} sessions · ${state.conversations.count { it.running }} live")
                        }
                        PrivacyStatusPill(state.privacyStatus, onClick = onOpenPrivacyCenter)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (state.incognito) AutobotColors.Uv else MaterialTheme.colorScheme.outline)
                        Column(Modifier.weight(1f)) {
                            MicroLabel("incognito", color = if (state.incognito) AutobotColors.Uv else MaterialTheme.colorScheme.onSurfaceVariant)
                            MicroLabel("memory only · never written to disk", color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(checked = state.incognito, onCheckedChange = onIncognitoChange)
                        Button(
                            onClick = onNewChat,
                            shape = MaterialTheme.shapes.small,
                            colors = if (state.incognito) ButtonDefaults.buttonColors(containerColor = AutobotColors.Uv) else ButtonDefaults.buttonColors(),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) {
                            Icon(AutobotIcons.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("NEW")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.loading && !state.hasProviders) {
                Panel(Modifier.fillMaxWidth().padding(12.dp), title = "No model provider", accent = AutobotColors.Amber) {
                    Text(
                        "Add DeepSeek, OpenRouter, a local Ollama server or any OpenAI-compatible endpoint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenProviders) { Text("ADD PROVIDER") }
                }
            }
            if (!state.loading && state.conversations.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(AutobotIcons.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(28.dp))
                        MicroLabel("no sessions")
                        MicroLabel("everything stays encrypted on this device", color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.conversations, key = { it.id }, contentType = { "conversation" }) { item ->
                        ConversationRow(item, onClick = { onOpenChat(item.id) }, onDelete = { pendingDelete = item.id })
                        Hairline(Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }

    pendingDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete session?") },
            text = { Text("Its encrypted history, tool calls and approvals are removed. Workspace files stay.") },
            confirmButton = {
                TextButton(onClick = { onDelete(id); pendingDelete = null }) { Text("DELETE", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun ConversationRow(item: ConversationItem, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(8.dp), contentAlignment = Alignment.Center) {
            when {
                item.running -> StatusDot(AutobotColors.Acid, live = true)
                item.incognito -> StatusDot(AutobotColors.Uv)
                else -> Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.outline))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(item.subtitle, DateUtils.getRelativeTimeSpanString(item.updatedAt).toString()).filter { it.isNotBlank() }.joinToString(" · "),
                style = AutobotTheme.styles.codeBlock,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete session", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF09090B)
@Composable
private fun ChatListScreenPreview() {
    AutobotTheme {
        ChatListScreen(
            state = ChatListUiState(
                conversations = listOf(
                    ConversationItem("1", "Refactor notes into a plan", "DeepSeek · deepseek-chat", 0L, false, running = true),
                    ConversationItem("2", "Private question", "Ollama · qwen3", 0L, true),
                ),
                privacyStatus = PrivacyStatus.OFFLINE,
                loading = false,
            ),
            onOpenChat = {}, onNewChat = {}, onIncognitoChange = {}, onDelete = {},
            onOpenProviders = {}, onOpenPrivacyCenter = {},
        )
    }
}

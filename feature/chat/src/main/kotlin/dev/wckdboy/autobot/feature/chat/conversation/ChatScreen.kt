package dev.wckdboy.autobot.feature.chat.conversation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.TodoStatus
import dev.wckdboy.autobot.core.designsystem.component.BlockStatus
import dev.wckdboy.autobot.core.designsystem.component.BubbleRole
import dev.wckdboy.autobot.core.designsystem.component.ChatBubble
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Panel
import dev.wckdboy.autobot.core.designsystem.component.PrivacyStatusPill
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.StatusDot
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.TerminalBlock
import dev.wckdboy.autobot.core.designsystem.component.ThinkingDisclosure
import dev.wckdboy.autobot.core.designsystem.component.TokenRateLabel
import dev.wckdboy.autobot.core.designsystem.component.hairlineEdge
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.util.Locale

@Composable
fun ChatRoute(
    conversationId: String,
    onBack: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenGallery: () -> Unit = {},
) {
    val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = conversationId) { factory ->
        factory.create(conversationId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ChatScreen(state, viewModel, onBack, onOpenPrivacyCenter, onOpenProviders, onOpenGallery)
}

private val COMMANDS = listOf(
    "/compact" to "summarize older history to free context",
    "/tools on|off" to "toggle tool use for this session",
    "/permission read-only|workspace-write|full-access" to "set the permission preset",
    "/model <id>" to "switch model",
    "/stop" to "cancel the running turn",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatViewModel,
    onBack: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showSession by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val newest = state.items.lastOrNull()?.key to (state.live != null)
    LaunchedEffect(newest) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }

    Scaffold(
        topBar = {
            SessionTopBar(
                state = state,
                onBack = onBack,
                onOpenModels = { showModels = true },
                onOpenSession = { showSession = true },
                onOpenPrivacyCenter = onOpenPrivacyCenter,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            if (state.showOfflineBanner) {
                Banner("Offline mode: this provider is remote, so the kill switch blocks it.", "PRIVACY", onOpenPrivacyCenter, AutobotColors.Uv)
            }
            if (state.loaded && state.providers.isEmpty()) Banner("No model provider configured.", "ADD", onOpenProviders, AutobotColors.Amber)
            state.notice?.let { Banner(it, "OK", actions::dismissNotice, AutobotColors.Cyan) }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.live?.let { live ->
                    item(key = "live", contentType = "live") { LiveMessage(live) }
                }
                items(state.items.asReversed(), key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is SessionItem.User -> ChatBubble(BubbleRole.USER, item.text)
                        is SessionItem.Assistant -> AssistantMessage(item)
                        is SessionItem.Tool -> ToolCard(item, state.approvals[item.callId], actions, onOpenGallery)
                        is SessionItem.Notice -> NoticeRow(item)
                    }
                }
                if (state.loaded && state.items.isEmpty() && state.live == null) {
                    item(key = "empty") { EmptySession(state) }
                }
            }

            if (state.todos.isNotEmpty() && state.todos.any { it.status != TodoStatus.COMPLETED }) TodoPanel(state)
            state.question?.let { QuestionCard(it, actions::answer) }
            InputBar(state, actions::send, actions::stop)
        }
    }

    if (showModels) {
        ModelSheet(
            state = state,
            onDismiss = { showModels = false },
            onSelectProvider = actions::selectProvider,
            onSelectModel = {
                actions.selectModel(it)
                showModels = false
            },
            onOpenProviders = {
                showModels = false
                onOpenProviders()
            },
        )
    }
    if (showSession) {
        SessionSheet(
            state = state,
            onDismiss = { showSession = false },
            onTools = actions::setToolsEnabled,
            onPermission = actions::setPermission,
            onCompact = {
                actions.compact()
                showSession = false
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Chrome

@Composable
private fun SessionTopBar(
    state: ChatUiState,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSession: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().hairlineEdge()) {
            Row(Modifier.fillMaxWidth().padding(end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = "Switch model", onClick = onOpenModels)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (state.isIncognito) {
                            Icon(Icons.Filled.Lock, contentDescription = "Incognito", modifier = Modifier.size(14.dp), tint = AutobotColors.Uv)
                            Spacer(Modifier.width(4.dp))
                        }
                        if (state.running) {
                            StatusDot(AutobotColors.Acid, live = true)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            state.title.ifBlank { "Session" },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MicroLabel(
                            listOfNotNull(state.selectedProviderName, state.selectedModel).joinToString(" · ").ifBlank { "no provider" },
                            Modifier.weight(1f, fill = false),
                        )
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                PrivacyStatusPill(state.privacyStatus, onClick = onOpenPrivacyCenter)
                IconButton(onClick = onOpenSession) { Icon(Icons.Filled.MoreVert, contentDescription = "Session settings") }
            }
            ContextGauge(state)
        }
    }
}

/** One-line telemetry strip: tools, permission preset and context fill. */
@Composable
private fun ContextGauge(state: ChatUiState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Tag(if (state.toolsEnabled) "tools" else "chat", accent = if (state.toolsEnabled) AutobotColors.Acid else MaterialTheme.colorScheme.onSurfaceVariant)
        Tag(
            state.permission.label,
            accent = when (state.permission) {
                PermissionPreset.READ_ONLY -> AutobotColors.Cyan
                PermissionPreset.WORKSPACE -> AutobotColors.Uv
                PermissionPreset.FULL_ACCESS -> AutobotColors.Signal
            },
        )
        val used = state.contextUsed
        val window = state.contextWindow
        if (used != null && window != null && window > 0) {
            val fraction = (used.toFloat() / window).coerceIn(0f, 1f)
            Box(Modifier.weight(1f).height(3.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(
                    Modifier.fillMaxWidth(fraction).height(3.dp)
                        .background(if (fraction > 0.75f) AutobotColors.Amber else MaterialTheme.colorScheme.primary),
                )
            }
            MicroLabel("${compact(used)}/${compact(window)}")
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1e6)
    n >= 1_000 -> "${n / 1000}k"
    else -> n.toString()
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit, accent: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.10f))
            .hairlineEdge()
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(accent)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action, color = accent) }
    }
}

// ---------------------------------------------------------------------------------------------
// Transcript rows

@Composable
private fun AssistantMessage(item: SessionItem.Assistant) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!item.reasoning.isNullOrEmpty()) ThinkingDisclosure(reasoning = item.reasoning)
        if (item.text.isNotEmpty()) {
            ChatBubble(
                role = BubbleRole.ASSISTANT,
                text = item.text,
                footer = if (item.interrupted || item.outputTokens != null) {
                    {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (item.interrupted) Tag("interrupted", accent = AutobotColors.Amber)
                            TokenRateLabel(tokensPerSecond = null, totalTokens = item.outputTokens)
                        }
                    }
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun LiveMessage(live: LiveUi) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live.reasoning.isNotEmpty()) ThinkingDisclosure(reasoning = live.reasoning, isThinking = live.text.isEmpty())
        if (live.text.isNotEmpty() || live.reasoning.isEmpty()) {
            ChatBubble(
                role = BubbleRole.ASSISTANT,
                text = live.text,
                isStreaming = true,
                footer = live.tokensPerSecond?.let { { TokenRateLabel(tokensPerSecond = it, totalTokens = null) } },
            )
        }
        live.toolNames.forEach { name -> TerminalBlock(name, BlockStatus.PENDING, subtitle = "composing call…") }
    }
}

@Composable
private fun ToolCard(item: SessionItem.Tool, approval: ApprovalUi?, actions: ChatViewModel, onOpenGallery: () -> Unit) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val status = when (item.state) {
        ToolState.STREAMING -> BlockStatus.PENDING
        ToolState.WAITING_APPROVAL -> BlockStatus.PENDING
        ToolState.RUNNING -> BlockStatus.RUNNING
        ToolState.OK -> BlockStatus.OK
        ToolState.ERROR -> BlockStatus.ERROR
        ToolState.DENIED -> BlockStatus.DENIED
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TerminalBlock(
            title = item.name,
            status = status,
            subtitle = item.summary.ifBlank { null },
            body = item.output,
            expanded = expanded,
            onToggle = if (item.output != null) ({ expanded = !expanded }) else null,
            actions = if (approval != null) {
                {
                    MicroLabel(approval.reason ?: "approval needed", Modifier.weight(1f).padding(start = 6.dp), color = AutobotColors.Amber)
                    TextButton(onClick = { actions.approve(item.callId, ApprovalOutcome.REJECTED) }) { Text("DENY", color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { actions.approve(item.callId, ApprovalOutcome.ALLOWED_FOR_SESSION) }) { Text("ALWAYS") }
                    TextButton(onClick = { actions.approve(item.callId, ApprovalOutcome.ALLOWED_ONCE) }) { Text("ALLOW", color = MaterialTheme.colorScheme.primary) }
                }
            } else {
                null
            },
        )
        if (item.galleryIds.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.galleryIds.forEach { id ->
                    val bitmap by produceState<ImageBitmap?>(null, id) { value = actions.thumbnail(id) }
                    Box(
                        Modifier
                            .size(120.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.small)
                            .clickable(onClick = onOpenGallery),
                    ) {
                        bitmap?.let { Image(it, contentDescription = "Generated image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeRow(item: SessionItem.Notice) {
    val color = when (item.kind) {
        NoticeKind.INFO -> MaterialTheme.colorScheme.outline
        NoticeKind.WARN -> AutobotColors.Amber
        NoticeKind.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Hairline(Modifier.weight(1f), color = color.copy(alpha = 0.35f))
        Text(
            item.text,
            style = AutobotTheme.styles.micro,
            color = color,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(3f, fill = false),
        )
        Hairline(Modifier.weight(1f), color = color.copy(alpha = 0.35f))
    }
}

@Composable
private fun EmptySession(state: ChatUiState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(AutobotIcons.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(28.dp))
        MicroLabel(if (state.toolsEnabled) "agent ready · workspace /workspace" else "plain chat")
        MicroLabel("type / for commands", color = MaterialTheme.colorScheme.outline)
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom stack

@Composable
private fun TodoPanel(state: ChatUiState) {
    var open by rememberSaveable { mutableStateOf(true) }
    val done = state.todos.count { it.status == TodoStatus.COMPLETED }
    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        title = "Plan · $done/${state.todos.size}",
        accent = AutobotColors.Acid,
        trailing = { Tag(if (open) "hide" else "show", onClick = { open = !open }) },
        contentPadding = 10.dp,
    ) {
        if (open) {
            state.todos.forEach { todo ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (glyph, color) = when (todo.status) {
                        TodoStatus.COMPLETED -> "■" to MaterialTheme.colorScheme.outline
                        TodoStatus.IN_PROGRESS -> "▶" to AutobotColors.Acid
                        TodoStatus.PENDING -> "□" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(glyph, style = AutobotTheme.styles.code, color = color)
                    Text(
                        todo.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (todo.status == TodoStatus.COMPLETED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(question: QuestionUi, onAnswer: (String, List<String>?) -> Unit) {
    var free by rememberSaveable(question.callId) { mutableStateOf("") }
    var picked by remember(question.callId) { mutableStateOf(setOf<String>()) }
    Panel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        title = "Agent asks",
        accent = AutobotColors.Uv,
        trailing = { Tag("skip", onClick = { onAnswer(question.callId, null) }) },
    ) {
        Text(question.question.question, style = MaterialTheme.typography.bodyMedium)
        if (question.question.options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                question.question.options.forEach { option ->
                    val active = option in picked
                    Tag(
                        option,
                        accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        filled = active,
                        onClick = {
                            if (question.question.multiSelect) {
                                picked = if (active) picked - option else picked + option
                            } else {
                                onAnswer(question.callId, listOf(option))
                            }
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConsoleTextField(free, { free = it }, Modifier.weight(1f), placeholder = "or type an answer", singleLine = true)
            Button(
                onClick = { onAnswer(question.callId, (picked + listOfNotNull(free.trim().ifBlank { null })).toList()) },
                enabled = free.isNotBlank() || picked.isNotEmpty(),
                shape = MaterialTheme.shapes.small,
            ) { Text("SEND") }
        }
    }
}

@Composable
private fun InputBar(state: ChatUiState, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.hairlineEdge(top = true, bottom = false)) {
            if (text.startsWith("/")) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    COMMANDS.filter { it.first.startsWith(text.substringBefore(' ')) }.forEach { (cmd, help) ->
                        Row(Modifier.fillMaxWidth().clickable { text = cmd.substringBefore(' ') + " " }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(cmd, style = AutobotTheme.styles.codeBlock, color = MaterialTheme.colorScheme.primary)
                            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Hairline()
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConsoleTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = if (state.running) "queue a follow-up…" else "message · / for commands",
                    maxLines = 8,
                )
                if (state.running && text.isBlank()) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onErrorContainer, RoundedCornerShape(2.dp)))
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            onSend(text)
                            text = ""
                        },
                        enabled = text.isNotBlank(),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (state.running) "Queue" else "Send")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModelSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onOpenProviders: () -> Unit,
) {
    var model by remember(state.selectedProviderId, state.selectedModel) { mutableStateOf(state.selectedModel.orEmpty()) }
    val selected = state.providers.firstOrNull { it.id == state.selectedProviderId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MicroLabel("// provider", color = MaterialTheme.colorScheme.primary)
            if (state.providers.isEmpty()) TextButton(onClick = onOpenProviders) { Text("ADD A PROVIDER") }
            state.providers.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onSelectProvider(option.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option.id == state.selectedProviderId, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(option.name, style = MaterialTheme.typography.bodyLarge)
                        Text(option.defaultModel, style = AutobotTheme.styles.codeBlock, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Hairline(Modifier.padding(vertical = 4.dp))
            MicroLabel("// model", color = MaterialTheme.colorScheme.primary)
            selected?.suggestedModels?.takeIf { it.isNotEmpty() }?.let { suggestions ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s ->
                        Tag(s, accent = if (s == model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, filled = s == model, onClick = { model = s })
                    }
                }
            }
            ConsoleTextField(model, { model = it }, Modifier.fillMaxWidth(), label = "Model id", singleLine = true, mono = true)
            Button(
                onClick = { onSelectModel(model) },
                enabled = model.isNotBlank() && selected != null,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.align(Alignment.End),
            ) { Text("USE MODEL") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSheet(
    state: ChatUiState,
    onDismiss: () -> Unit,
    onTools: (Boolean) -> Unit,
    onPermission: (PermissionPreset) -> Unit,
    onCompact: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MicroLabel("// session", color = MaterialTheme.colorScheme.primary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Agent tools", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Files in /workspace, web fetch, image generation, skills, todos. Off = plain chat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = state.toolsEnabled, onCheckedChange = onTools)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MicroLabel("Permission preset")
                Segmented(PermissionPreset.entries, state.permission, onPermission, { it.label }, Modifier.fillMaxWidth())
                Text(
                    when (state.permission) {
                        PermissionPreset.READ_ONLY -> "Reads only. Network fetches ask; writes are denied."
                        PermissionPreset.WORKSPACE -> "Edits the workspace freely. Deletes and network fetches ask."
                        PermissionPreset.FULL_ACCESS -> "Everything runs without asking. The kill switch still applies."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compact context", style = MaterialTheme.typography.titleSmall)
                    Text("Summarize older turns now (also automatic near the limit).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onCompact, enabled = !state.running) { Text("COMPACT") }
            }
        }
    }
}

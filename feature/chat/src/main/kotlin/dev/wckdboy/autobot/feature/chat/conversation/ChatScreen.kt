package dev.wckdboy.autobot.feature.chat.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.TodoStatus
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.BubbleRole
import dev.wckdboy.autobot.core.designsystem.component.ChatBubble
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.PaperButton
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.RouteTag
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.core.designsystem.component.StatusDot
import dev.wckdboy.autobot.core.designsystem.component.Tag
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

private const val MAX_ATTACHMENTS = 4

private val COMMANDS = listOf(
    "/compact" to "summarize older history to free context",
    "/tools on|off" to "toggle tool use for this session",
    "/permission read-only|workspace-write|full-access" to "set the permission preset",
    "/model <id>" to "switch model",
    "/stop" to "cancel the running turn",
)

/** Human names for tool steps (mockup: "Read calendar / calendar.list"). */
private fun stepTitle(name: String): String = when (name) {
    "read" -> "Read file"
    "write" -> "Write file"
    "edit" -> "Edit file"
    "delete" -> "Delete file"
    "glob" -> "Find files"
    "grep" -> "Search files"
    "web_fetch" -> "Fetch page"
    "generate_image" -> "Generate image"
    "todo_write" -> "Update plan"
    "ask_user_question" -> "Ask you"
    "skill" -> "Load skill"
    else -> name.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/**
 * An agent run (mockup 03): red run line, the task as a display title, numbered tool steps with
 * timings, approvals as a full red block, and the active policy in the footer.
 */
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
    // Step numbers in transcript order.
    val stepNumbers = remember(state.items) {
        var n = 0
        state.items.filterIsInstance<SessionItem.Tool>().associate { it.key to ++n }
    }
    val firstPromptKey = state.items.firstOrNull { it is SessionItem.User }?.key

    Scaffold(
        topBar = {
            AutobotTopBar(
                title = if (state.toolsEnabled) "Agent run" else "Chat",
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    RouteTag(state.route, Modifier.clickable { showModels = true })
                    IconButton(onClick = { showSession = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Session settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            if (state.showOfflineBanner) Banner("Offline mode: this provider is remote, so the kill switch blocks it.", "PRIVACY", onOpenPrivacyCenter)
            if (state.loaded && state.providers.isEmpty()) Banner("No model yet — download one or add a provider.", "ADD", onOpenProviders)
            state.notice?.let { Banner(it, "OK", actions::dismissNotice) }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.live?.let { live -> item(key = "live", contentType = "live") { LiveMessage(live) } }
                items(state.items.asReversed(), key = { it.key }, contentType = { it::class }) { item ->
                    when (item) {
                        is SessionItem.User -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.key == firstPromptKey) RunHeader(state, item.text) else FollowUp(item.text)
                            if (item.images.isNotEmpty()) AttachmentStrip(item.images, actions::attachmentThumbnail)
                        }
                        is SessionItem.Assistant -> AssistantMessage(item, speaking = state.speakingKey == item.key) { actions.speak(item.key, item.text) }
                        is SessionItem.Tool -> {
                            val approval = state.approvals[item.callId]
                            if (approval != null) {
                                ApprovalCard(stepNumbers[item.key] ?: 0, item, approval, actions)
                            } else {
                                StepRow(stepNumbers[item.key] ?: 0, item, actions, onOpenGallery)
                            }
                        }
                        is SessionItem.Notice -> NoticeRow(item)
                    }
                }
                if (state.loaded && state.items.isEmpty() && state.live == null) {
                    item(key = "empty") { EmptySession(state) }
                }
            }

            if (state.todos.isNotEmpty() && state.todos.any { it.status != TodoStatus.COMPLETED }) TodoPanel(state)
            state.question?.let { QuestionCard(it, actions::answer) }
            PolicyLine(state)
            InputBar(state, actions::send, actions::stop, actions::attach, actions::removeAttachment, actions::attachmentThumbnail, actions::toggleDictation, actions::consumeDictation)
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
// Transcript

@Composable
private fun RunHeader(state: ChatUiState, prompt: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        SectionLabel(
            listOfNotNull(
                "run",
                state.modelLabel ?: state.selectedModel,
                state.contextUsed?.let { "${compact(it)} tok" },
            ).joinToString(" · "),
        )
        Text(prompt, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun FollowUp(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("›", style = AutobotTheme.styles.code, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun AssistantMessage(item: SessionItem.Assistant, speaking: Boolean, onSpeak: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!item.reasoning.isNullOrEmpty()) ThinkingDisclosure(reasoning = item.reasoning)
        if (item.text.isNotEmpty()) {
            ChatBubble(
                role = BubbleRole.ASSISTANT,
                text = item.text,
                footer = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.interrupted) Tag("interrupted", accent = AutobotColors.Amber)
                        if (item.outputTokens != null) TokenRateLabel(tokensPerSecond = null, totalTokens = item.outputTokens)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onSpeak, modifier = Modifier.size(32.dp)) {
                            Icon(
                                AutobotIcons.Speaker,
                                contentDescription = if (speaking) "Stop reading" else "Read aloud",
                                tint = if (speaking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
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
        live.toolNames.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(MaterialTheme.colorScheme.primary, live = true)
                Text(stepTitle(name), style = MaterialTheme.typography.titleMedium)
                MicroLabel("composing…")
            }
        }
    }
}

/** `01  Read file / read · notes.md   ✓ 0.4 s` */
@Composable
private fun StepRow(number: Int, item: SessionItem.Tool, actions: ChatViewModel, onOpenGallery: () -> Unit) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val (mark, markColor) = when (item.state) {
        ToolState.OK -> "✓" to MaterialTheme.colorScheme.onSurfaceVariant
        ToolState.ERROR -> "×" to MaterialTheme.colorScheme.error
        ToolState.DENIED -> "denied" to MaterialTheme.colorScheme.onSurfaceVariant
        ToolState.RUNNING, ToolState.STREAMING, ToolState.WAITING_APPROVAL -> "…" to MaterialTheme.colorScheme.primary
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = item.output != null, role = Role.Button) { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(String.format(Locale.ROOT, "%02d", number), style = AutobotTheme.styles.readout, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stepTitle(item.name), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(item.name, item.summary).filter { it.isNotBlank() }.joinToString(" · "),
                    style = AutobotTheme.styles.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.state == ToolState.RUNNING) StatusDot(MaterialTheme.colorScheme.primary, live = true)
            Text(
                mark + (item.durationMs?.let { " ${String.format(Locale.ROOT, "%.1f", it / 1000f)} s" } ?: ""),
                style = AutobotTheme.styles.readout,
                color = markColor,
            )
        }
        if (expanded && item.output != null) {
            Text(
                item.output.take(4000),
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp),
                style = AutobotTheme.styles.codeBlock,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.galleryIds.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                item.galleryIds.forEach { id ->
                    val bitmap by produceState<ImageBitmap?>(null, id) { value = actions.thumbnail(id) }
                    Box(Modifier.size(120.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onOpenGallery)) {
                        bitmap?.let { Image(it, contentDescription = "Generated image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        Hairline()
    }
}

/** The red approval block: `04 · APPROVAL NEEDED · NETWORK ACCESS`, title, args, DENY / APPROVE. */
@Composable
private fun ApprovalCard(number: Int, item: SessionItem.Tool, approval: ApprovalUi, actions: ChatViewModel) {
    Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary, shape = MaterialTheme.shapes.extraSmall) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Text(String.format(Locale.ROOT, "%02d · APPROVAL NEEDED", number), style = AutobotTheme.styles.micro, modifier = Modifier.weight(1f))
                Text((approval.reason ?: "").uppercase(Locale.ROOT), style = AutobotTheme.styles.micro)
            }
            Text(stepTitle(item.name), style = MaterialTheme.typography.headlineMedium)
            Text("${item.name}(${approval.summary})", style = AutobotTheme.styles.codeBlock)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Deny", { actions.approve(item.callId, ApprovalOutcome.REJECTED) }, Modifier.weight(1f), accent = MaterialTheme.colorScheme.onPrimary)
                PaperButton("Approve", { actions.approve(item.callId, ApprovalOutcome.ALLOWED_ONCE) }, Modifier.weight(1f))
            }
            Text(
                "ALWAYS ALLOW ${item.name.uppercase(Locale.ROOT)} IN THIS RUN",
                style = AutobotTheme.styles.micro,
                modifier = Modifier.clickable { actions.approve(item.callId, ApprovalOutcome.ALLOWED_FOR_SESSION) }.padding(vertical = 4.dp),
            )
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
    Text(
        "// ${item.text.uppercase(Locale.ROOT)}",
        style = AutobotTheme.styles.micro,
        color = color,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun EmptySession(state: ChatUiState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(if (state.toolsEnabled) "agent ready · workspace /workspace" else "chat")
        Text(if (state.toolsEnabled) "What should\nit do?" else "Ask\nanything.", style = MaterialTheme.typography.displaySmall)
        MicroLabel("type / for commands", color = MaterialTheme.colorScheme.outline)
    }
}

/** `POLICY · READ: AUTO · WRITE: AUTO · DELETE: ASK · NETWORK: ASK` */
@Composable
private fun PolicyLine(state: ChatUiState) {
    if (!state.toolsEnabled) return
    val text = when (state.permission) {
        PermissionPreset.READ_ONLY -> "policy · read: auto · write: deny · network: ask"
        PermissionPreset.WORKSPACE -> "policy · read: auto · write: auto · delete: ask · network: ask"
        PermissionPreset.FULL_ACCESS -> "policy · everything: auto · kill switch still applies"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MicroLabel(text, Modifier.weight(1f))
        val used = state.contextUsed
        val window = state.contextWindow
        if (used != null && window != null && window > 0) MicroLabel("${compact(used)}/${compact(window)}")
    }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1e6)
    n >= 1_000 -> "${n / 1000}k"
    else -> n.toString()
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).hairlineEdge().padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action, color = MaterialTheme.colorScheme.primary) }
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom stack

@Composable
private fun TodoPanel(state: ChatUiState) {
    var open by rememberSaveable { mutableStateOf(true) }
    val done = state.todos.count { it.status == TodoStatus.COMPLETED }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("plan · $done/${state.todos.size}", Modifier.weight(1f))
            Tag(if (open) "hide" else "show", onClick = { open = !open })
        }
        if (open) {
            state.todos.forEach { todo ->
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (glyph, color) = when (todo.status) {
                        TodoStatus.COMPLETED -> "■" to MaterialTheme.colorScheme.outline
                        TodoStatus.IN_PROGRESS -> "▶" to MaterialTheme.colorScheme.primary
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
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("agent asks", Modifier.weight(1f))
            Tag("skip", onClick = { onAnswer(question.callId, null) })
        }
        Text(question.question.question, style = MaterialTheme.typography.titleMedium)
        if (question.question.options.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                question.question.options.forEach { option ->
                    val active = option in picked
                    Tag(
                        option,
                        accent = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        filled = active,
                        onClick = {
                            if (question.question.multiSelect) picked = if (active) picked - option else picked + option else onAnswer(question.callId, listOf(option))
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConsoleTextField(free, { free = it }, Modifier.weight(1f), placeholder = "or type an answer", singleLine = true)
            PrimaryButton("Send", { onAnswer(question.callId, (picked + listOfNotNull(free.trim().ifBlank { null })).toList()) }, enabled = free.isNotBlank() || picked.isNotEmpty())
        }
    }
}

@Composable
private fun InputBar(
    state: ChatUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttach: (Uri) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    thumbnail: suspend (String) -> ImageBitmap?,
    onDictate: () -> Unit,
    onDictationConsumed: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(state.dictated) {
        state.dictated?.let { spoken ->
            text = if (text.isBlank()) spoken else text.trimEnd() + " " + spoken
            onDictationConsumed()
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) onDictate() }
    val context = LocalContext.current
    // System photo picker: no storage permission, the app only sees what the user picks.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { uris ->
        uris.take(MAX_ATTACHMENTS - state.pendingImages.size).forEach(onAttach)
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.hairlineEdge(top = true, bottom = false)) {
            if (state.pendingImages.isNotEmpty()) {
                AttachmentStrip(state.pendingImages, thumbnail, Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), onRemove = onRemoveAttachment)
            }
            if (text.startsWith("/")) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = state.pendingImages.size < MAX_ATTACHMENTS,
                    modifier = Modifier.size(52.dp),
                ) { Icon(AutobotIcons.Image, contentDescription = "Attach image") }
                IconButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            onDictate()
                        } else {
                            microphone.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = state.dictation != Dictation.TRANSCRIBING,
                    modifier = Modifier.size(52.dp),
                ) {
                    when (state.dictation) {
                        Dictation.IDLE -> Icon(AutobotIcons.Mic, contentDescription = "Dictate")
                        Dictation.RECORDING -> Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary))
                        Dictation.TRANSCRIBING -> Text("…", style = AutobotTheme.styles.code, color = MaterialTheme.colorScheme.primary)
                    }
                }
                ConsoleTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = if (state.running) "queue a follow-up…" else "message · / for commands",
                    maxLines = 8,
                )
                if (state.running && text.isBlank() && state.pendingImages.isEmpty()) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.primary),
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.size(52.dp),
                    ) { Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary)) }
                } else {
                    FilledIconButton(
                        onClick = {
                            onSend(text)
                            text = ""
                        },
                        enabled = text.isNotBlank() || state.pendingImages.isNotEmpty(),
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.size(52.dp),
                    ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (state.running) "Queue" else "Send") }
                }
            }
        }
    }
}

/** Square thumbnails of attached images; [onRemove] adds a remove button (composer only). */
@Composable
private fun AttachmentStrip(
    ids: List<String>,
    thumbnail: suspend (String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    onRemove: ((String) -> Unit)? = null,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.forEach { id ->
            val image by produceState<ImageBitmap?>(null, id) { value = thumbnail(id) }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                image?.let { Image(it, contentDescription = "Attached image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (onRemove != null) {
                    Text(
                        "×",
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clickable { onRemove(id) }
                            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.8f))
                            .padding(horizontal = 6.dp),
                        style = AutobotTheme.styles.code,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
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
            SectionLabel("route")
            if (state.providers.isEmpty()) TextButton(onClick = onOpenProviders) { Text("ADD A PROVIDER") }
            state.providers.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { onSelectProvider(option.id) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = option.id == state.selectedProviderId, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(option.name, style = MaterialTheme.typography.titleMedium)
                        Text(option.defaultModel, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Hairline(Modifier.padding(vertical = 4.dp))
            SectionLabel("model")
            selected?.suggestedModels?.takeIf { it.isNotEmpty() }?.let { suggestions ->
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s ->
                        Tag(s, accent = if (s == model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, filled = s == model, onClick = { model = s })
                    }
                }
            }
            ConsoleTextField(model, { model = it }, Modifier.fillMaxWidth(), label = "Model id", singleLine = true, mono = true)
            PrimaryButton("Use model", { onSelectModel(model) }, Modifier.fillMaxWidth(), enabled = model.isNotBlank() && selected != null)
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
            SectionLabel("session")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Agent tools", style = MaterialTheme.typography.titleMedium)
                    Text("Files in /workspace, web fetch, image generation, skills, plans. Off = plain chat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.toolsEnabled, onCheckedChange = onTools)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MicroLabel("permission preset")
                Segmented(PermissionPreset.entries, state.permission, onPermission, { it.label }, Modifier.fillMaxWidth())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compact context", style = MaterialTheme.typography.titleMedium)
                    Text("Summarize older turns now (also automatic near the limit).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onCompact, enabled = !state.running) { Text("COMPACT") }
            }
        }
    }
}

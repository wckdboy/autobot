package dev.wckdboy.autobot.feature.chat.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
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
import dev.wckdboy.autobot.core.designsystem.component.CopyMessageButton
import dev.wckdboy.autobot.core.designsystem.component.DevelopingHalftone
import dev.wckdboy.autobot.core.designsystem.component.GhostButton
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.HalftoneField
import dev.wckdboy.autobot.core.designsystem.component.HalftoneStrip
import dev.wckdboy.autobot.core.designsystem.component.InkLoader
import dev.wckdboy.autobot.core.designsystem.component.InkLoaderSize
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.PaperButton
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.PrintReveal
import dev.wckdboy.autobot.core.designsystem.component.RouteTag
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Segmented
import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.providers.remote.ThinkingLabels
import dev.wckdboy.autobot.core.designsystem.component.StatusDot
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.ThinkingDisclosure
import dev.wckdboy.autobot.core.designsystem.component.TokenRateLabel
import dev.wckdboy.autobot.core.designsystem.component.hairlineEdge
import dev.wckdboy.autobot.core.designsystem.component.inkGround
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.motion.InkTweenRow
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.inkEnter
import dev.wckdboy.autobot.core.designsystem.motion.inkStagger
import dev.wckdboy.autobot.core.designsystem.motion.registerGhost
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkEnterTracker
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.feature.chat.NameDialog
import dev.wckdboy.autobot.providers.remote.ModelLists
import dev.wckdboy.autobot.shared.panic.IncognitoImages
import dev.wckdboy.autobot.shared.panic.PanicCopy
import dev.wckdboy.autobot.shared.session.NoticeKind
import dev.wckdboy.autobot.shared.session.Receipt
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.voice.DictationText
import dev.wckdboy.autobot.shared.session.ToolState
import java.util.Locale
import kotlin.math.sqrt

@Composable
fun ChatRoute(
    conversationId: String,
    onBack: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenGallery: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
) {
    val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = conversationId) { factory ->
        factory.create(conversationId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Streamed frames and microphone levels are read only where they show (see ChatScreen).
    val live = viewModel.live.collectAsStateWithLifecycle()
    val levels = viewModel.micLevels.collectAsStateWithLifecycle()
    val words = viewModel.liveDictation.collectAsStateWithLifecycle()
    ChatScreen(
        state, viewModel, onBack, onOpenPrivacyCenter, onOpenProviders, onOpenGallery,
        live = { live.value }, micLevels = { levels.value }, liveDictation = { words.value }, onOpenUsage = onOpenUsage,
    )
}

private const val MAX_ATTACHMENTS = 4

/** Test hook: told about each composition of the screen ("screen"), a transcript row ("row"), the live reply ("live") and the level meter ("meter"). Null in the app. */
internal var chatCompositionProbe: ((String) -> Unit)? = null

/** Saved instance state keeps at most this much typed text (it all travels in one binder transaction). */
private const val MAX_SAVED_TEXT = 4_000

private val BoundedText = Saver<MutableState<String>, String>(save = { it.value.take(MAX_SAVED_TEXT) }, restore = { mutableStateOf(it) })

private val COMMANDS = listOf(
    "/compact" to "summarize older history to free context",
    "/tools on|off" to "toggle tool use for this session",
    "/think off|auto|on [budget]" to "reasoning for this chat",
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
    "task" -> "Delegate"
    // mcp__<server>__<tool> → "Tool · server"
    else -> if (name.startsWith("mcp__") && name.indexOf("__", 5) > 0) {
        val server = name.substring(5, name.indexOf("__", 5))
        stepTitle(name.substring(name.indexOf("__", 5) + 2)) + " · " + server
    } else name.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/**
 * An agent run (mockup 03): red run line, the task as a display title, numbered tool steps with
 * timings, approvals as a full red block, and the active policy in the footer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    actions: ChatActions,
    onBack: () -> Unit,
    onOpenPrivacyCenter: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenGallery: () -> Unit,
    /** The streaming reply: read by the live message only, so its frames do not recompose the screen. */
    live: () -> LiveUi? = { null },
    /** Voice mode's microphone levels: read by the level meter only. */
    micLevels: () -> List<Float> = { emptyList() },
    /** The spend and quota board (from a receipt's token line). */
    onOpenUsage: () -> Unit = {},
    /** Words heard so far while dictating or listening: read by the live words line only. */
    liveDictation: () -> DictationText? = { null },
) {
    chatCompositionProbe?.invoke("screen")
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showSession by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var openReceipt by remember { mutableStateOf<Receipt?>(null) }
    var showChanges by rememberSaveable { mutableStateOf(false) }
    var showThink by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val currentLive by rememberUpdatedState(live)
    // Read here (the screen recomposes when a reply starts or ends), never inside the list's
    // content block: a derived state read there re-runs it, and every row, on each frame.
    val hasLive = remember { derivedStateOf { currentLive() != null } }.value
    val newest = state.items.lastOrNull()?.key to hasLive
    LaunchedEffect(newest) {
        if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
    }
    // Step numbers in transcript order (computed with the projection, off the main thread).
    val stepNumbers = state.stepNumbers
    val firstPromptKey = state.items.firstOrNull { it is SessionItem.User }?.key
    val runLabel = listOfNotNull("run", state.modelLabel ?: state.selectedModel, state.contextUsed?.let { "${compact(it)} tok" }).joinToString(" · ")
    val onReceipt = remember { { receipt: Receipt -> openReceipt = receipt } }
    // Items added after the transcript loaded ink in; history just appears.
    val enter = rememberInkEnterTracker(state.loaded) { state.items.map { it.key } }

    Scaffold(
        topBar = {
            AutobotTopBar(
                title = if (state.toolsEnabled) "Agent run" else "Chat",
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    val voiceOn = state.voice != null
                    Tag(
                        "voice",
                        accent = if (voiceOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        filled = voiceOn,
                        onClick = { actions.setVoiceMode(!voiceOn) },
                    )
                    // No extra spacer: the bar already spaces its actions, and the title needs the room.
                    RouteTag(state.route, Modifier.clickable { showModels = true })
                    IconButton(onClick = { showSession = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Session settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
            if (state.showOfflineBanner) Banner("Offline mode: this provider is remote, so the kill switch blocks it.", "PRIVACY", onOpenPrivacyCenter)
            if (state.loaded && state.providers.isEmpty()) Banner("No model yet.", "ADD", onOpenProviders)
            state.notice?.let { Banner(it, "OK", actions::dismissNotice) }

            val empty = state.loaded && state.items.isEmpty() && !hasLive
            LazyColumn(
                state = listState,
                // One ground per screen: with the voice panel up, its own paper is the ground (two fields
                // with their own grids would meet at the panel's edge).
                modifier = Modifier.weight(1f).fillMaxWidth().then(if (empty && state.voice == null) Modifier.inkGround(heavyWork = state.running) else Modifier),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (hasLive) {
                    item(key = "live", contentType = "live") {
                        val frame = currentLive() ?: return@item
                        chatCompositionProbe?.invoke("live")
                        LiveMessage(frame, Modifier.inkEnter(enter.armed))
                    }
                }
                items(state.items.asReversed(), key = { it.key }, contentType = { it::class }) { item ->
                    chatCompositionProbe?.invoke("row")
                    // An assistant reply replaces the live message already on screen: no entrance.
                    val fresh = remember(item.key) { enter.claim(item.key) } && item !is SessionItem.Assistant
                    val approval = (item as? SessionItem.Tool)?.let { state.approvals[it.callId] }
                    val ghost = if (approval != null) remember(approval.callId) { enter.claim("approval:${approval.callId}") } else false
                    // Plain values only: a row skips unless its own item, step or flags changed.
                    TranscriptRow(
                        item = item,
                        fresh = fresh,
                        runLabel = if (item.key == firstPromptKey) runLabel else null,
                        stepNumber = stepNumbers[item.key] ?: 0,
                        approval = approval,
                        ghost = ghost,
                        speaking = state.speakingKey == item.key,
                        running = state.running,
                        actions = actions,
                        onReceipt = onReceipt,
                        onOpenGallery = onOpenGallery,
                    )
                }
                if (empty) {
                    item(key = "empty") { EmptySession(state) }
                }
            }

            if (state.todos.isNotEmpty() && state.todos.any { it.status != TodoStatus.COMPLETED }) TodoPanel(state)
            state.question?.let { QuestionCard(it, actions::answer) }
            PolicyLine(
                state,
                onThink = { actions.setThinking(ThinkingLabels.next(state.thinking)) },
                onThinkMore = { showThink = true },
                onRedact = { actions.setRedaction(state.redaction != RedactionUi.ON) },
            )
            val voice = state.voice
            if (voice != null) {
                VoicePanel(voice, micLevels, liveDictation, actions::voiceTap, actions::voiceDenied, actions::setHandsFree) { actions.setVoiceMode(false) }
            } else {
                InputBar(
                    state, actions::send, actions::stop, actions::attach, actions::removeAttachment, actions::attachmentThumbnail, actions::toggleDictation,
                    actions::consumeDictation, actions.draft, actions::setDraft, liveDictation,
                )
            }
        }
    }

    if (showModels) {
        ModelSheet(
            state = state,
            onDismiss = { showModels = false },
            onSelectProvider = actions::selectProvider,
            onSelectModel = {
                actions.selectModel(it, confirmed = true)
                showModels = false
            },
            onOpenProviders = {
                showModels = false
                onOpenProviders()
            },
            onRefreshModels = actions::refreshModels,
        )
    }
    openReceipt?.let {
        ReceiptSheet(it, onDismiss = { openReceipt = null }, onOpenUsage = {
            openReceipt = null
            onOpenUsage()
        })
    }
    if (showThink) ThinkSheet(state, actions::setThinking, actions::setThinkingDefault) { showThink = false }
    if (renaming) {
        NameDialog(
            title = "Rename chat",
            initial = state.title,
            confirm = "RENAME",
            onDismiss = { renaming = false },
            onConfirm = {
                actions.rename(it)
                renaming = false
            },
        )
    }
    if (showChanges) ChangesSheet(state.changes, state.running, actions::undoStep, actions::undoTurn) { showChanges = false }
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
            onRename = {
                showSession = false
                renaming = true
            },
            onPin = actions::setPinned,
            onArchive = {
                actions.setArchived(it)
                showSession = false
            },
            onDelete = {
                showSession = false
                actions.delete()
                onBack()
            },
            onChanges = {
                showSession = false
                showChanges = true
            },
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Transcript

/** One transcript item; [runLabel] is set on the run's first prompt (it shows as the run header). */
@Composable
private fun TranscriptRow(
    item: SessionItem,
    fresh: Boolean,
    runLabel: String?,
    stepNumber: Int,
    approval: ApprovalUi?,
    ghost: Boolean,
    speaking: Boolean,
    running: Boolean,
    actions: ChatActions,
    onReceipt: (Receipt) -> Unit,
    onOpenGallery: () -> Unit,
) {
    chatCompositionProbe?.invoke("rowBody")
    Box(Modifier.inkEnter(fresh)) {
        when (item) {
            is SessionItem.User -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (runLabel != null) RunHeader(runLabel, item.text) else FollowUp(item.text)
                if (item.images.isNotEmpty()) AttachmentStrip(item.images, actions::attachmentThumbnail)
            }
            is SessionItem.Assistant -> AssistantMessage(
                item,
                speaking = speaking,
                onReceipt = onReceipt,
                undo = { UndoTurnLine(item.turnChanges, running, actions::undoTurn, actions::redo) },
            ) {
                actions.speak(item.key, item.text)
            }
            is SessionItem.Tool -> if (approval != null) {
                ApprovalCard(stepNumber, item, approval, actions, ghost)
            } else {
                StepRow(stepNumber, item, actions, onOpenGallery, running)
            }
            is SessionItem.Notice -> NoticeRow(item)
        }
    }
}

@Composable
private fun RunHeader(label: String, prompt: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        SectionLabel(label)
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
private fun AssistantMessage(
    item: SessionItem.Assistant,
    speaking: Boolean,
    onReceipt: (Receipt) -> Unit,
    undo: @Composable () -> Unit = {},
    onSpeak: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item.reasoning?.takeIf { it.isNotEmpty() }?.let { ThinkingDisclosure(reasoning = it, tokens = item.reasoningTokens, stop = item.reasoningStop) }
        if (item.text.isNotEmpty()) {
            ChatBubble(
                role = BubbleRole.ASSISTANT,
                text = item.text,
                footer = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.interrupted) Tag("interrupted", accent = AutobotColors.Amber)
                        if (item.outputTokens != null) TokenRateLabel(tokensPerSecond = null, totalTokens = item.outputTokens)
                        Spacer(Modifier.weight(1f))
                        // Copies the message as written (markdown), not the rendered text.
                        CopyMessageButton(item.text)
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
        ReceiptLine(item.receipt, onReceipt)
        undo()
    }
}

@Composable
private fun LiveMessage(live: LiveUi, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live.reasoning.isNotEmpty()) {
            ThinkingDisclosure(
                reasoning = live.reasoning,
                isThinking = !live.reasoningDone && live.text.isEmpty(),
                tokens = live.reasoningTokens,
                stop = live.reasoningStop,
            )
        }
        if (live.text.isEmpty() && live.reasoning.isEmpty() && live.toolNames.isEmpty()) {
            // Nothing to read yet: a tiny halftone strip instead of an empty bubble.
            HalftoneStrip(Modifier.padding(vertical = 6.dp))
        } else if (live.text.isNotEmpty()) {
            ChatBubble(
                role = BubbleRole.ASSISTANT,
                text = live.text,
                isStreaming = true,
                footer = live.tokensPerSecond?.let { { TokenRateLabel(tokensPerSecond = it, totalTokens = null) } },
            )
        }
        live.toolNames.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HalftoneStrip(cell = 3.dp)
                Text(stepTitle(name), style = MaterialTheme.typography.titleMedium)
                MicroLabel("composing")
            }
        }
    }
}

/** `01  Read file / read · notes.md   ✓ 0.4 s` */
@Composable
private fun StepRow(number: Int, item: SessionItem.Tool, actions: ChatActions, onOpenGallery: () -> Unit, running: Boolean = false) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    // Images this row saw being made get printed in; older ones just show.
    val watched = remember(item.key) { booleanArrayOf(false) }
    val generating = item.name == "generate_image" && item.state == ToolState.RUNNING
    if (generating) watched[0] = true
    val change = item.change
    val (mark, markColor) = when (item.state) {
        ToolState.OK -> (if (item.undone) "undone" else "✓") to MaterialTheme.colorScheme.onSurfaceVariant
        ToolState.ERROR -> "×" to MaterialTheme.colorScheme.error
        ToolState.DENIED -> "denied" to MaterialTheme.colorScheme.onSurfaceVariant
        ToolState.RUNNING, ToolState.STREAMING, ToolState.WAITING_APPROVAL -> "" to MaterialTheme.colorScheme.primary
    }
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = item.output != null, role = Role.Button) { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(String.format(Locale.ROOT, "%02d", number), style = AutobotTheme.styles.readout, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stepTitle(item.name),
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (item.undone) TextDecoration.LineThrough else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOf(item.name, item.summary).filter { it.isNotBlank() }.joinToString(" · "),
                        modifier = Modifier.weight(1f, fill = false),
                        style = AutobotTheme.styles.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (change != null) ChangeCounts(change)
                }
                // Why a step failed shouldn't take a tap to find out.
                // (a bare "denied" only repeats the mark)
                val reason = item.output?.lineSequence()?.firstOrNull { it.isNotBlank() }?.removePrefix("Error: ")?.takeIf { !it.equals("denied", ignoreCase = true) }
                if (!expanded && reason != null && (item.state == ToolState.ERROR || item.state == ToolState.DENIED)) {
                    Text(
                        reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.state == ToolState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (mark.isEmpty()) {
                // Still working: the ink loader stands where the mark will be stamped.
                InkLoader(Modifier.padding(top = 3.dp))
            } else {
                Text(
                    mark + (item.durationMs?.let { " ${String.format(Locale.ROOT, "%.1f", it / 1000f)} s" } ?: ""),
                    modifier = Modifier.stamp(item.state to item.undone, enabled = item.state == ToolState.OK || item.state == ToolState.ERROR || item.state == ToolState.DENIED),
                    style = AutobotTheme.styles.readout,
                    color = markColor,
                )
            }
        }
        val output = item.output
        if (expanded && item.steps.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(start = 34.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item.steps.forEach { step ->
                    Text(step, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (expanded && change != null) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DiffBlock(change)
                val redoId = item.revertId
                when {
                    item.undone && redoId != null -> Tag("↷ redo", accent = MaterialTheme.colorScheme.primary, onClick = { if (!running) actions.redo(redoId) })
                    !item.undone && change.undoable -> Tag("↶ undo step", accent = MaterialTheme.colorScheme.primary, onClick = { if (!running) actions.undoStep(item.callId) })
                }
            }
        } else if (expanded && output != null) {
            Text(
                output.take(4000),
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp),
                style = AutobotTheme.styles.codeBlock,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (generating && item.galleryIds.isEmpty()) {
            // The same plate as the image studio, flowing while the agent's job runs.
            Box(Modifier.padding(bottom = 6.dp).size(120.dp).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                DevelopingHalftone(preview = null, modifier = Modifier.fillMaxSize())
            }
        }
        if (item.galleryIds.isNotEmpty()) {
            val saved by actions.savedImages.collectAsStateWithLifecycle()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                item.galleryIds.forEach { id ->
                    val bitmap by produceState<ImageBitmap?>(null, id) { value = actions.thumbnail(id) }
                    // An incognito image is in memory only until saved; only a saved one is in the gallery.
                    val unsaved = IncognitoImages.isUnsaved(id)
                    val inGallery = !unsaved || saved.containsKey(id)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(120.dp).background(MaterialTheme.colorScheme.surfaceContainerLow).clickable(enabled = inGallery, onClick = onOpenGallery)) {
                            bitmap?.let { PrintReveal(it, Modifier.fillMaxSize(), animate = watched[0], contentDescription = "Generated image") }
                        }
                        if (unsaved && bitmap != null) {
                            if (saved.containsKey(id)) {
                                MicroLabel(PanicCopy.SAVED)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MicroLabel(PanicCopy.NOT_SAVED)
                                    Tag(PanicCopy.SAVE, accent = MaterialTheme.colorScheme.primary, onClick = { actions.saveImage(id) })
                                }
                            }
                        }
                    }
                }
            }
        }
        Hairline()
    }
}

/**
 * The red approval block: `04 · APPROVAL NEEDED · NETWORK ACCESS`, title, args, DENY / APPROVE.
 * When the call would send private data (taint tracking), the header says so and every private
 * fragment is printed in reverse, in the arguments and in the reason.
 */
@Composable
private fun ApprovalCard(number: Int, item: SessionItem.Tool, approval: ApprovalUi, actions: ChatActions, ghost: Boolean) {
    val privateData = approval.privateData
    val mark = SpanStyle(background = MaterialTheme.colorScheme.onPrimary, color = MaterialTheme.colorScheme.primary)
    Surface(
        modifier = Modifier.registerGhost(ghost, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), MaterialTheme.shapes.extraSmall),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Text(String.format(Locale.ROOT, "%02d · APPROVAL NEEDED", number), style = AutobotTheme.styles.micro, modifier = Modifier.weight(1f))
                Text((if (privateData.isEmpty()) approval.reason.orEmpty() else "private data").uppercase(Locale.ROOT), style = AutobotTheme.styles.micro)
            }
            Text(stepTitle(item.name), style = MaterialTheme.typography.headlineMedium)
            Text(highlight("${item.name}(${approval.summary})", privateData, mark), style = AutobotTheme.styles.codeBlock)
            if (privateData.isNotEmpty()) Text(highlight(approval.reason.orEmpty(), privateData, mark), style = MaterialTheme.typography.bodyMedium)
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

/** [text] with every case-insensitive occurrence of [fragments] in [style]. */
private fun highlight(text: String, fragments: List<String>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    for (fragment in fragments.filter { it.isNotBlank() }) {
        var at = text.indexOf(fragment, ignoreCase = true)
        while (at >= 0) {
            addStyle(style, at, at + fragment.length)
            at = text.indexOf(fragment, at + fragment.length, ignoreCase = true)
        }
    }
}

/** `// STOPPED`; errors keep their case (ids, hosts and keys must stay readable) and expand on tap. */
@Composable
private fun NoticeRow(item: SessionItem.Notice) {
    val color = when (item.kind) {
        NoticeKind.INFO -> MaterialTheme.colorScheme.outline
        NoticeKind.WARN -> AutobotColors.Amber
        NoticeKind.ERROR -> MaterialTheme.colorScheme.error
    }
    if (item.kind == NoticeKind.ERROR) {
        var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
        Text(
            "// ${item.text}",
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded },
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Text(
            "// ${item.text.uppercase(Locale.ROOT)}",
            style = AutobotTheme.styles.micro,
            color = color,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptySession(state: ChatUiState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(if (state.toolsEnabled) "agent ready · workspace /workspace" else "chat")
        Text(if (state.toolsEnabled) "What should\nit do?" else "Ask\nanything.", style = MaterialTheme.typography.displaySmall)
    }
}

/** `POLICY · WORKSPACE · ASKS: DELETE, NETWORK`: what runs without asking, in a few words. */
@Composable
private fun PolicyLine(state: ChatUiState, onThink: () -> Unit = {}, onThinkMore: () -> Unit = {}, onRedact: () -> Unit = {}) {
    val think = state.thinkingSupport.shown
    val redact = state.redaction != RedactionUi.NONE
    if (!state.toolsEnabled && !think && !redact) return
    val text = when {
        !state.toolsEnabled -> ""
        state.permission == PermissionPreset.READ_ONLY -> "policy · read-only · asks: network"
        state.permission == PermissionPreset.WORKSPACE -> "policy · workspace · asks: delete, network"
        else -> "policy · full access"
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (think) ThinkChip(state.thinking, onThink, onThinkMore)
        if (redact) RedactChip(state.redaction == RedactionUi.ON, onRedact)
        // Two lines: with the Think and redact chips, a narrow phone cuts the policy to "POLICY · WORKS…".
        MicroLabel(text, Modifier.weight(1f), maxLines = 2)
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
    var free by rememberSaveable(question.callId, saver = BoundedText) { mutableStateOf("") }
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
    draft: String,
    onDraftChange: (String) -> Unit,
    liveDictation: () -> DictationText? = { null },
) {
    // Held by the ViewModel, not saved instance state: a huge paste must not overflow the saved Bundle.
    var text by remember { mutableStateOf(draft) }
    LaunchedEffect(Unit) { snapshotFlow { text }.collect(onDraftChange) }
    LaunchedEffect(state.dictated) {
        state.dictated?.let { spoken ->
            text = if (text.isBlank()) spoken else text.trimEnd() + " " + spoken
            onDictationConsumed()
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) onDictate() }
    val context = LocalContext.current
    // A new, empty chat with a model ready opens with the keyboard up: one tap fewer to the first message.
    val focus = remember { FocusRequester() }
    val fresh = state.loaded && state.items.all { it is SessionItem.Notice } && state.providers.isNotEmpty() && !state.running
    LaunchedEffect(fresh) { if (fresh && text.isEmpty()) runCatching { focus.requestFocus() } }
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
            if (state.dictation != Dictation.IDLE) {
                LiveWords(liveDictation, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp))
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
                        Dictation.RECORDING -> Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary).semantics { contentDescription = "Stop dictating" })
                        Dictation.TRANSCRIBING -> InkLoader(size = InkLoaderSize.Button, contentDescription = "Transcribing")
                    }
                }
                ConsoleTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = if (state.running) "queue a follow-up…" else "message · / for commands",
                    maxLines = 8,
                )
                if (state.running && text.isBlank() && state.pendingImages.isEmpty()) {
                    FilledIconButton(
                        onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = MaterialTheme.colorScheme.primary),
                        shape = MaterialTheme.shapes.extraSmall,
                        modifier = Modifier.size(52.dp),
                    ) { Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary).semantics { contentDescription = "Stop the reply" }) }
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

/**
 * Voice mode's composer: what the loop is doing (`// VOICE · LISTENING`), a level meter while
 * listening, and one button that talks, sends, stops the agent or interrupts the answer.
 */
@Composable
private fun VoicePanel(
    voice: VoiceUi,
    levels: () -> List<Float>,
    words: () -> DictationText?,
    onTap: () -> Unit,
    onDenied: () -> Unit,
    onHandsFree: (Boolean) -> Unit,
    onType: () -> Unit,
) {
    val context = LocalContext.current
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) onTap() else onDenied() }
    val primary = MaterialTheme.colorScheme.primary
    val (label, hint) = when (voice.phase) {
        VoicePhase.READY -> "ready" to "tap to talk"
        VoicePhase.LISTENING -> "listening" to if (voice.handsFree) "pause to send · tap to send now" else "tap to send"
        VoicePhase.TRANSCRIBING -> "transcribing" to "on this phone"
        VoicePhase.THINKING -> "thinking" to "tap to stop"
        VoicePhase.SPEAKING -> "speaking" to "tap to interrupt and talk"
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .hairlineEdge(top = true, bottom = false)
                // Living paper while the loop waits on the user; still while the phone works.
                .inkGround(heavyWork = voice.phase == VoicePhase.TRANSCRIBING || voice.phase == VoicePhase.THINKING)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(if (voice.phase == VoicePhase.READY) MaterialTheme.colorScheme.outline else primary, live = voice.phase != VoicePhase.READY)
                SectionLabel("voice · $label", Modifier.weight(1f))
                Tag(
                    "hands-free",
                    accent = if (voice.handsFree) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    filled = voice.handsFree,
                    onClick = { onHandsFree(!voice.handsFree) },
                )
                Tag("type", onClick = onType)
            }
            if (voice.phase == VoicePhase.LISTENING || voice.phase == VoicePhase.TRANSCRIBING) LiveWords(words, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (voice.phase == VoicePhase.SPEAKING) {
                        // A slow flow strip while the answer is read aloud, on plain paper: its 6dp
                        // screen over the ground's 14dp one read as two misaligned dot grids.
                        HalftoneField(
                            Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.background),
                            cell = 6.dp,
                            flowing = true,
                            fill = 0.8f,
                            speed = 1.5f,
                        )
                    } else {
                        LevelMeter(levels, Modifier.fillMaxWidth().height(36.dp), live = voice.phase == VoicePhase.LISTENING)
                    }
                    MicroLabel(hint)
                }
                val talks = voice.phase == VoicePhase.READY || voice.phase == VoicePhase.SPEAKING
                FilledIconButton(
                    onClick = {
                        if (talks && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            microphone.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            onTap()
                        }
                    },
                    enabled = voice.phase != VoicePhase.TRANSCRIBING,
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = if (voice.phase == VoicePhase.THINKING || voice.phase == VoicePhase.SPEAKING) {
                        IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, contentColor = primary)
                    } else {
                        IconButtonDefaults.filledIconButtonColors()
                    },
                    modifier = Modifier.size(64.dp),
                ) {
                    when (voice.phase) {
                        VoicePhase.READY -> Icon(AutobotIcons.Mic, contentDescription = "Talk")
                        VoicePhase.LISTENING -> Box(Modifier.size(16.dp).background(MaterialTheme.colorScheme.onPrimary).semantics { contentDescription = "Send now" })
                        VoicePhase.TRANSCRIBING -> InkLoader(size = InkLoaderSize.Button, color = LocalContentColor.current, contentDescription = "Transcribing")
                        VoicePhase.THINKING -> Box(Modifier.size(14.dp).background(primary).semantics { contentDescription = "Stop the agent" })
                        VoicePhase.SPEAKING -> Icon(AutobotIcons.Mic, contentDescription = "Interrupt")
                    }
                }
            }
        }
    }
}

/**
 * The words heard so far, live: settled words in ink, the still-changing tail faded. Reads
 * [words] itself, so each new transcription recomposes this line only. Shows the last
 * [LIVE_WORDS_CHARS] characters (the newest words stay in view); nothing until a word is heard.
 */
@Composable
private fun LiveWords(words: () -> DictationText?, modifier: Modifier = Modifier) {
    val text = words()
    chatCompositionProbe?.invoke("words")
    if (text == null || text.isEmpty) return
    val ink = MaterialTheme.colorScheme.onSurface
    val faded = MaterialTheme.colorScheme.onSurfaceVariant
    val shown = remember(text) {
        val committed = text.committed
        val tentative = text.tentative
        val full = if (committed.isEmpty() || tentative.isEmpty()) committed + tentative else "$committed $tentative"
        val cut = (full.length - LIVE_WORDS_CHARS).coerceAtLeast(0)
        buildAnnotatedString {
            if (cut > 0) append("…")
            val solid = (committed.length - cut).coerceAtLeast(0)
            val visible = full.substring(cut)
            pushStyle(SpanStyle(color = ink))
            append(visible.take(solid))
            pop()
            pushStyle(SpanStyle(color = faded))
            append(visible.drop(solid))
            pop()
        }
    }
    Text(shown, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Clip, modifier = modifier.semantics { contentDescription = "Heard: ${text.full}" })
}

private const val LIVE_WORDS_CHARS = 160

/** Microphone peaks as riso bars, newest on the right, easing to each new reading; a flat rule when not listening. */
@Composable
private fun LevelMeter(readings: () -> List<Float>, modifier: Modifier = Modifier, live: Boolean = true) {
    // Read here, not by the panel: each reading recomposes the meter only.
    val levels = readings()
    chatCompositionProbe?.invoke("meter")
    val ink = MaterialTheme.colorScheme.primary
    val rule = MaterialTheme.colorScheme.outlineVariant
    val animated = live && !LocalInkMotion.current.reduced
    val clock = rememberInkClock(animated)
    val bars = remember { InkTweenRow(METER_SLOTS, METER_EASE_SECONDS) }
    SideEffect {
        val targets = FloatArray(METER_SLOTS) { i -> levels.getOrNull(levels.size - METER_SLOTS + i)?.let { sqrt(it.coerceIn(0f, 1f)) } ?: 0f }
        if (animated) bars.retarget(targets, Snapshot.withoutReadObservation { clock.time }) else bars.snap(targets)
    }
    val count = levels.size
    Canvas(modifier) {
        val now = clock.time
        val slot = size.width / METER_SLOTS
        val bar = slot * 0.6f
        val floor = 2.dp.toPx()
        for (i in 0 until METER_SLOTS) {
            val has = count - METER_SLOTS + i >= 0
            val h = if (has) (bars.value(i, now) * size.height).coerceAtLeast(floor) else floor
            drawRect(if (has) ink else rule, topLeft = Offset(i * slot, (size.height - h) / 2), size = Size(bar, h))
        }
    }
}

private const val METER_SLOTS = 32

/** Readings arrive 10 a second; each bar eases to its new height in that time. */
private const val METER_EASE_SECONDS = 0.1f

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
    onRefreshModels: () -> Unit,
) {
    var model by remember(state.selectedProviderId, state.selectedModel) { mutableStateOf(state.selectedModel.orEmpty()) }
    var confirmTrains by remember { mutableStateOf<String?>(null) }
    val selected = state.providers.firstOrNull { it.id == state.selectedProviderId }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("route", Modifier.inkStagger(0))
            if (state.providers.isEmpty()) TextButton(onClick = onOpenProviders) { Text("ADD A PROVIDER") }
            state.providers.forEach { option ->
                Row(Modifier.fillMaxWidth().inkStagger(1).clickable(role = Role.RadioButton) { onSelectProvider(option.id) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = option.id == state.selectedProviderId, onClick = null)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(option.name, style = MaterialTheme.typography.titleMedium)
                        Text(option.defaultModel, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Hairline(Modifier.padding(vertical = 4.dp))
            Row(Modifier.inkStagger(2), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("model", Modifier.weight(1f))
                selected?.let { MicroLabel(it.modelsLabel) }
                if (selected?.canRefresh == true) TextButton(onClick = onRefreshModels) { Text("REFRESH") }
            }
            ConsoleTextField(model, { model = it }, Modifier.fillMaxWidth().inkStagger(3), label = "Model id · type to search", singleLine = true, mono = true)
            selected?.let { option ->
                // The field searches the list unless it already names one of its models.
                val query = if (option.models.any { it.id == model }) "" else model
                val shown = ModelLists.filter(option.models, query, limit = 30)
                shown.forEach { m ->
                    val active = m.id == model
                    Column(Modifier.fillMaxWidth().clickable { model = m.id }.padding(vertical = 4.dp)) {
                        Text(
                            (if (active) "● " else "") + m.id,
                            style = AutobotTheme.styles.code,
                            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        option.caption(m).takeIf { it.isNotEmpty() }?.let { MicroLabel(it) }
                    }
                }
            }
            PrimaryButton("Use model", { if (selected?.trains(model) == true) confirmTrains = model else onSelectModel(model) }, Modifier.fillMaxWidth(), enabled = model.isNotBlank() && selected != null)
            confirmTrains?.let { TrainsConfirmDialog(it, onConfirm = { onSelectModel(it) }, onDismiss = { confirmTrains = null }) }
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
    onRename: () -> Unit,
    onPin: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onChanges: () -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("chat", Modifier.inkStagger(0))
            Row(Modifier.inkStagger(1), verticalAlignment = Alignment.CenterVertically) {
                Text(state.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = onRename) { Text("RENAME") }
            }
            Row(Modifier.inkStagger(2), verticalAlignment = Alignment.CenterVertically) {
                Text("Pin to top", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = state.pinned, onCheckedChange = onPin)
            }
            Row(Modifier.inkStagger(3), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onArchive(!state.archived) }) { Text(if (state.archived) "UNARCHIVE" else "ARCHIVE") }
                TextButton(onClick = onDelete) { Text("DELETE CHAT", color = MaterialTheme.colorScheme.error) }
            }
            SectionLabel("session", Modifier.inkStagger(4))
            Row(Modifier.inkStagger(5), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Agent tools", style = MaterialTheme.typography.titleMedium)
                    Text("Files in /workspace, web fetch, image generation, skills, plans. Off = plain chat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.toolsEnabled, onCheckedChange = onTools)
            }
            Column(Modifier.inkStagger(6), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MicroLabel("permission preset")
                Segmented(PermissionPreset.entries, state.permission, onPermission, { it.label }, Modifier.fillMaxWidth())
            }
            Row(Modifier.inkStagger(7), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compact context", style = MaterialTheme.typography.titleMedium)
                    Text("Summarize older turns now (also automatic near the limit).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onCompact, enabled = !state.running) { Text("COMPACT") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val files = state.changes.sumOf { it.files.size }
                Column(Modifier.weight(1f)) {
                    Text("Files changed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (files == 0) "Nothing yet. Every file the agent changes can be undone." else "$files file${if (files == 1) "" else "s"} across ${state.changes.size} turn${if (state.changes.size == 1) "" else "s"}; undo a step or a turn.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onChanges) { Text("VIEW") }
            }
        }
    }
}

/**
 * The Think switch of the chat: `think · auto`. A tap cycles auto → on → off; a long press opens
 * the budget and the model's default ([ThinkSheet]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThinkChip(setting: ThinkingSetting, onTap: () -> Unit, onMore: () -> Unit) {
    val active = setting.mode != ThinkingMode.OFF
    val color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = 0.6f))
            .combinedClickable(onClickLabel = "Change thinking", onLongClickLabel = "Thinking budget", onLongClick = onMore, onClick = onTap)
            .semantics { contentDescription = "Thinking ${setting.mode.name.lowercase()}" + (setting.budget?.let { ", budget $it tokens" } ?: "") }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) { MicroLabel("\u2234 " + ThinkingLabels.chip(setting), color = color) }
}

/**
 * The redaction switch of a cloud chat: `▮ redact` while personal data becomes placeholders
 * before the provider sees it, `redact off` dimmed (like an off Think chip) otherwise. A tap switches it for this chat.
 */
@Composable
private fun RedactChip(on: Boolean, onTap: () -> Unit) {
    val color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .border(1.dp, color.copy(alpha = 0.6f))
            .clickable(onClickLabel = if (on) "Stop redacting" else "Redact personal data", onClick = onTap)
            .semantics { contentDescription = if (on) "Redaction on: names, emails and numbers are hidden from the cloud" else "Redaction off" }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        MicroLabel(
            if (on) "\u25AE redact" else "redact off",
            color = color,
        )
    }
}

/** Long press on the Think chip: mode, budget for On, and the default for this model. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkSheet(
    state: ChatUiState,
    onThinking: (ThinkingSetting) -> Unit,
    onDefault: (ThinkingSetting?) -> Unit,
    onDismiss: () -> Unit,
) {
    val setting = state.thinking
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("think", Modifier.inkStagger(0))
            Segmented(
                ThinkingMode.entries,
                setting.mode,
                { mode -> onThinking(ThinkingSetting(mode, if (mode == ThinkingMode.ON) setting.budget else null)) },
                { it.name.lowercase() },
                Modifier.fillMaxWidth().inkStagger(1),
            )
            if (setting.mode == ThinkingMode.ON) {
                Column(Modifier.inkStagger(2), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MicroLabel("budget · tokens")
                    Segmented(
                        ThinkingSetting.BUDGETS,
                        setting.budget,
                        { budget -> onThinking(ThinkingSetting(ThinkingMode.ON, budget)) },
                        { it?.let(ThinkingLabels::tokens) ?: "model" },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            Row(Modifier.inkStagger(3), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Default for this model", style = MaterialTheme.typography.titleMedium)
                    MicroLabel(state.thinkingDefault?.let { ThinkingLabels.chip(it) } ?: "auto")
                }
                if (state.thinkingDefault == setting || (state.thinkingDefault == null && setting == ThinkingSetting.AUTO)) {
                    TextButton(onClick = { onDefault(null) }, enabled = state.thinkingDefault != null) { Text("CLEAR") }
                } else {
                    TextButton(onClick = { onDefault(setting) }) { Text("SET") }
                }
            }
        }
    }
}

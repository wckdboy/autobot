package dev.wckdboy.autobot.feature.chat.conversation

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.agent.core.approval.PermissionPreset
import dev.wckdboy.autobot.agent.core.llm.ThinkingMode
import dev.wckdboy.autobot.agent.core.llm.ThinkingSetting
import dev.wckdboy.autobot.agent.core.session.ApprovalOutcome
import dev.wckdboy.autobot.agent.core.session.TodoStatus
import dev.wckdboy.autobot.core.designsystem.component.NoticeCard
import dev.wckdboy.autobot.core.designsystem.component.MessageComposer
import dev.wckdboy.autobot.core.designsystem.component.SendIconButton
import dev.wckdboy.autobot.core.designsystem.component.StopIconButton
import dev.wckdboy.autobot.core.designsystem.component.AutobotMark
import dev.wckdboy.autobot.core.designsystem.component.BubbleRole
import dev.wckdboy.autobot.core.designsystem.component.ChatBubble
import dev.wckdboy.autobot.core.designsystem.component.CopyMessageButton
import dev.wckdboy.autobot.core.designsystem.component.NoPersonalizedLearning
import dev.wckdboy.autobot.core.designsystem.component.PrintReveal
import dev.wckdboy.autobot.core.designsystem.component.PrivateKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.PromptKeyboardOptions
import dev.wckdboy.autobot.core.designsystem.component.RouteChip
import dev.wckdboy.autobot.core.designsystem.component.ThinkingDisclosure
import dev.wckdboy.autobot.core.designsystem.component.TitleWithDot
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.component.counted
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.core.designsystem.component.keyboardUp
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.core.designsystem.component.longPressAction
import dev.wckdboy.autobot.core.designsystem.component.readingWidth
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.motion.InkTweenRow
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.inkEnter
import dev.wckdboy.autobot.core.designsystem.motion.registerGhost
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkEnterTracker
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.feature.chat.NameDialog
import dev.wckdboy.autobot.providers.remote.ModelLists
import dev.wckdboy.autobot.providers.remote.ThinkingLabels
import dev.wckdboy.autobot.shared.ChatBannerWords
import dev.wckdboy.autobot.shared.cascade.CascadeLabels
import dev.wckdboy.autobot.shared.cascade.CascadePolicy
import dev.wckdboy.autobot.shared.composer.Composer
import dev.wckdboy.autobot.shared.composer.Composer.Part
import dev.wckdboy.autobot.shared.panic.IncognitoImages
import dev.wckdboy.autobot.shared.panic.PanicCopy
import dev.wckdboy.autobot.shared.session.CascadeOffer
import dev.wckdboy.autobot.shared.session.NoticeKind
import dev.wckdboy.autobot.shared.session.Receipt
import dev.wckdboy.autobot.shared.session.SessionItem
import dev.wckdboy.autobot.shared.session.ToolState
import dev.wckdboy.autobot.shared.voice.DictationText
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
    /** A generated image full size ([id], swiping through its [batch]). */
    onOpenImage: (id: String, batch: List<String>) -> Unit = { _, _ -> onOpenGallery() },
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
        onOpenImage = onOpenImage,
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
 * A chat or an agent run, in Material 3: a top app bar (back, voice, the route chip, session
 * settings), the transcript (the run's prompt as its title, numbered tool steps, approvals on an
 * outlined card), the chat's switches as filter chips, and a filled composer. On a wide window
 * the column keeps a reading width.
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
    /** A generated image full size ([id], swiping through its [batch]). */
    onOpenImage: (id: String, batch: List<String>) -> Unit = { _, _ -> onOpenGallery() },
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
    var showCascade by rememberSaveable { mutableStateOf(false) }
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
    // "Retry" sits on the last answer (or failure) while nothing runs.
    val retry = remember(state.items, state.running) { ChatRetry.of(state.items, state.running) }
    val firstPromptKey = state.items.firstOrNull { it is SessionItem.User }?.key
    val runLabel = listOfNotNull("run", state.modelLabel ?: state.selectedModel, state.contextUsed?.let { "${compact(it)} tok" }).joinToString(" · ")
    val onReceipt = remember { { receipt: Receipt -> openReceipt = receipt } }
    // Items added after the transcript loaded ink in; history just appears.
    val enter = rememberInkEnterTracker(state.loaded) { state.items.map { it.key } }

    Scaffold(
        topBar = {
            val large = isLargeText()
            Column {
                TopAppBar(
                    title = { TitleWithDot(if (state.toolsEnabled) "Agent run" else "Chat", mark = false) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    actions = {
                        val voiceOn = state.voice != null
                        IconToggleButton(checked = voiceOn, onCheckedChange = { actions.setVoiceMode(it) }) {
                            Icon(AutobotIcons.Mic, contentDescription = "Voice mode")
                        }
                        // Where it runs; a tap picks the provider and model.
                        if (!large) RouteChip(state.route, Modifier.padding(horizontal = 4.dp)) { showModels = true }
                        IconButton(onClick = { showSession = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Session settings") }
                    },
                )
                // A large font leaves no room beside the title: the route takes a line under the bar.
                if (large) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                        RouteChip(state.route) { showModels = true }
                    }
                }
            }
        },
    ) { padding ->
        // While typing only the field, the transcript and what is being answered stay (Keyboard.kt).
        val keyboard = keyboardUp()
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // On a wide window the transcript and its composer keep a reading width, centred.
            Column(Modifier.readingWidth().fillMaxSize()) {
                // One banner at a time: a notice answers what was just done, else the most pressing one.
                val notice = state.notice
                val bannerModifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                when {
                    notice != null -> NoticeCard(notice, bannerModifier, action = "OK", onAction = actions::dismissNotice)
                    !Composer.shows(Part.BANNER, keyboard) -> Unit
                    state.loaded && state.providers.isEmpty() -> NoticeCard(ChatBannerWords.NO_MODEL, bannerModifier, action = "Add", onAction = onOpenProviders)
                    state.showOfflineBanner -> NoticeCard(ChatBannerWords.OFFLINE, bannerModifier, action = "Privacy", onAction = onOpenPrivacyCenter)
                    state.missingKeyProvider != null -> NoticeCard(ChatBannerWords.missingKey(state.missingKeyProvider), bannerModifier, action = "Add key", onAction = onOpenProviders)
                }

                val empty = state.loaded && state.items.isEmpty() && !hasLive
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    // Bottom: a short transcript sits on the composer, not under the top bar.
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                ) {
                    // The cascade's offer sits under the phone's answer (the list is reversed: first is lowest).
                    val offer = state.cascadeOffer
                    if (offer != null && !hasLive) {
                        item(key = "cascade-offer:${offer.turn}", contentType = "cascade-offer") {
                            CascadeOfferRow(offer, state.modelLabel ?: state.selectedModel.orEmpty(), actions::escalate)
                        }
                    }
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
                            retry = retry?.takeIf { it.key == item.key }?.prompt,
                            approval = approval,
                            ghost = ghost,
                            speaking = state.speakingKey == item.key,
                            running = state.running,
                            actions = actions,
                            onReceipt = onReceipt,
                            onOpenImage = onOpenImage,
                        )
                    }
                    if (empty && Composer.shows(Part.DECOR, keyboard)) {
                        item(key = "empty") { EmptySession(state) }
                    }
                }

                if (Composer.shows(Part.PLAN, keyboard) && state.todos.any { it.status != TodoStatus.COMPLETED }) TodoPanel(state)
                state.question?.let { QuestionCard(it, actions::answer) }
                if (Composer.shows(Part.CHIPS, keyboard)) {
                    PolicyLine(
                        state,
                        onThink = { actions.setThinking(ThinkingLabels.next(state.thinking)) },
                        onThinkMore = { showThink = true },
                        onRedact = { actions.setRedaction(state.redaction != RedactionUi.ON) },
                        onCascade = { showCascade = true },
                    )
                }
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
    val cascade = state.cascade
    if (showCascade && cascade != null) {
        CascadeSheet(cascade, state.modelLabel ?: state.selectedModel.orEmpty(), state.onNode, actions::setCascade) { showCascade = false }
    }
    if (renaming) {
        NameDialog(
            title = "Rename chat",
            initial = state.title,
            confirm = "Rename",
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
    retry: String?,
    approval: ApprovalUi?,
    ghost: Boolean,
    speaking: Boolean,
    running: Boolean,
    actions: ChatActions,
    onReceipt: (Receipt) -> Unit,
    onOpenImage: (String, List<String>) -> Unit,
) {
    chatCompositionProbe?.invoke("rowBody")
    Box(Modifier.inkEnter(fresh)) {
        when (item) {
            is SessionItem.User -> {
                var menu by remember { mutableStateOf(false) }
                Column(
                    Modifier.combinedClickable(onLongClickLabel = "Message actions", onLongClick = { menu = true }, onClick = {}),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (runLabel != null) RunHeader(runLabel, item.text) else FollowUp(item.text)
                    if (item.images.isNotEmpty()) AttachmentStrip(item.images, actions::attachmentThumbnail)
                    MessageMenu(menu, { menu = false }, item.text)
                }
            }
            is SessionItem.Assistant -> AssistantMessage(
                item,
                speaking = speaking,
                onReceipt = onReceipt,
                onRetry = retry?.let { prompt -> { actions.send(prompt) } },
                undo = { UndoTurnLine(item.turnChanges, running, actions::undoTurn, actions::redo) },
            ) {
                actions.speak(item.key, item.text)
            }
            is SessionItem.Tool -> if (approval != null) {
                ApprovalCard(stepNumber, item, approval, actions, ghost)
            } else {
                StepRow(stepNumber, item, actions, onOpenImage, running)
            }
            is SessionItem.Notice -> NoticeRow(item, onRetry = retry?.let { prompt -> { actions.send(prompt) } })
        }
    }
}

/** The run's first prompt: what ran (`Run · model · tokens`) over the prompt as the run's title. */
@Composable
private fun RunHeader(label: String, prompt: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Text(labelCase(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(prompt, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A later prompt in the run: a tonal bubble on the trailing side. */
@Composable
private fun FollowUp(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AssistantMessage(
    item: SessionItem.Assistant,
    speaking: Boolean,
    onReceipt: (Receipt) -> Unit,
    onRetry: (() -> Unit)? = null,
    undo: @Composable () -> Unit = {},
    onSpeak: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item.reasoning?.takeIf { it.isNotEmpty() }?.let { ThinkingDisclosure(reasoning = it, tokens = item.reasoningTokens, stop = item.reasoningStop) }
        if (item.text.isNotEmpty()) {
            MessageMenu(menu, { menu = false }, item.text, onRetry = onRetry, onSpeak = onSpeak)
            ChatBubble(
                role = BubbleRole.ASSISTANT,
                text = item.text,
                onLongPress = { menu = true },
                footer = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.interrupted) {
                            TonalLabel("Interrupted", containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        if (item.outputTokens != null) MetaText(rateLine(null, item.outputTokens))
                        Spacer(Modifier.weight(1f))
                        // Copies the message as written (markdown), not the rendered text.
                        CopyMessageButton(item.text)
                        ShareButton(item.text)
                        if (onRetry != null) RetryButton(onRetry)
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

/** `12.4 tok/s · 256 tokens`: either part may be missing. */
private fun rateLine(tokensPerSecond: Float?, totalTokens: Int?): String = listOfNotNull(
    tokensPerSecond?.let { String.format(Locale.ROOT, "%.1f tok/s", it) },
    totalTokens?.let { counted(it, "token") },
).joinToString(" · ")

/** A quiet line of detail (token counts, timings) in the label type. */
@Composable
private fun MetaText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines: Int = Int.MAX_VALUE) {
    if (text.isEmpty()) return
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Material's expressive loading indicator, [size] across, read out as [description] (or nothing). */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Working(modifier: Modifier = Modifier, size: Dp = 32.dp, description: String? = null, color: Color = MaterialTheme.colorScheme.primary) {
    LoadingIndicator(
        modifier.size(size).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        color = color,
    )
}

@Composable
private fun LiveMessage(live: LiveUi, modifier: Modifier = Modifier) {
    SideEffect { ChatTiming.shown(live.text) }
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
            // Nothing to read yet: the loading indicator instead of an empty bubble.
            Working(Modifier.padding(vertical = 6.dp), size = 40.dp, description = "Working")
        } else if (live.text.isNotEmpty()) {
            ChatBubble(
                role = BubbleRole.ASSISTANT,
                text = live.text,
                isStreaming = true,
                footer = live.tokensPerSecond?.let { { MetaText(rateLine(it, null)) } },
            )
        }
        live.toolNames.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Working(size = 24.dp)
                Text(stepTitle(name), style = MaterialTheme.typography.titleMedium)
                MetaText("Composing")
            }
        }
    }
}

/** `▪ Read file / └ read · notes.md   ✓ 0.4 s`: a tool step, its output or diff a tap away. */
@Composable
private fun StepRow(number: Int, item: SessionItem.Tool, actions: ChatActions, onOpenImage: (String, List<String>) -> Unit, running: Boolean = false) {
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
            // The step square: primary while it works or waits for you, error when it failed.
            Text(
                STEP_MARK,
                modifier = Modifier.semantics { contentDescription = "Step $number" },
                // On the title's line and at its size.
                style = MaterialTheme.typography.titleMedium,
                color = when (item.state) {
                    ToolState.RUNNING, ToolState.STREAMING, ToolState.WAITING_APPROVAL -> MaterialTheme.colorScheme.primary
                    ToolState.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stepTitle(item.name),
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (item.undone) TextDecoration.LineThrough else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    MetaText(
                        "└ " + listOf(item.name, item.summary).filter { it.isNotBlank() }.joinToString(" · "),
                        Modifier.weight(1f, fill = false),
                        maxLines = 1,
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
                // Still working: the loading indicator stands where the mark will be stamped.
                Working(size = 24.dp)
            } else {
                Text(
                    mark + (item.durationMs?.let { " ${String.format(Locale.ROOT, "%.1f", it / 1000f)} s" } ?: ""),
                    modifier = Modifier.stamp(item.state to item.undone, enabled = item.state == ToolState.OK || item.state == ToolState.ERROR || item.state == ToolState.DENIED),
                    style = MaterialTheme.typography.labelLarge,
                    color = markColor,
                )
            }
        }
        val output = item.output
        if (expanded && item.steps.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item.steps.forEach { step -> MetaText(step, maxLines = 1) }
            }
        }
        if (expanded && change != null) {
            Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DiffBlock(change)
                val redoId = item.revertId
                when {
                    item.undone && redoId != null -> TextButton(onClick = { if (!running) actions.redo(redoId) }) { Text("Redo step") }
                    !item.undone && change.undoable -> TextButton(onClick = { if (!running) actions.undoStep(item.callId) }) {
                        Icon(AutobotIcons.Undo, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Undo step")
                    }
                }
            }
        } else if (expanded && output != null) {
            // Tool output is code: monospace on a tonal plate.
            Text(
                output.take(4000),
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                style = AutobotTheme.styles.codeBlock,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (generating && item.galleryIds.isEmpty()) {
            // A plate while the agent's job runs; the indicator says it is working.
            Box(Modifier.padding(bottom = 6.dp).size(120.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Working(description = "Generating")
            }
        }
        if (item.galleryIds.isNotEmpty()) {
            val saved by actions.savedImages.collectAsStateWithLifecycle()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                item.galleryIds.forEach { id ->
                    val bitmap by produceState<ImageBitmap?>(null, id) { value = actions.thumbnail(id) }
                    // An incognito image is in memory only until saved: it opens full size from memory
                    // (the viewer writes it nowhere but through Save); a saved one opens as its gallery copy.
                    val unsaved = IncognitoImages.isUnsaved(id)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier
                                .size(120.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .clickable(onClickLabel = "View full size") { onOpenImage(saved[id] ?: id, item.galleryIds.map { saved[it] ?: it }) },
                        ) {
                            bitmap?.let { PrintReveal(it, Modifier.fillMaxSize(), animate = watched[0], contentDescription = "Generated image") }
                        }
                        if (unsaved && bitmap != null) {
                            if (saved.containsKey(id)) {
                                MetaText(PanicCopy.SAVED)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MetaText(PanicCopy.NOT_SAVED)
                                    TextButton(onClick = { actions.saveImage(id) }) { Text(PanicCopy.SAVE) }
                                }
                            }
                        }
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * The approval block: `▪ Approval needed · network access`, title, args, Deny / Approve, on an
 * outlined card ringed in the primary colour (it is what needs you now). When the call would send
 * private data (taint tracking), the header says so and every private fragment is marked, in the
 * arguments and in the reason.
 */
@Composable
private fun ApprovalCard(number: Int, item: SessionItem.Tool, approval: ApprovalUi, actions: ChatActions, ghost: Boolean) {
    val privateData = approval.privateData
    val mark = SpanStyle(background = MaterialTheme.colorScheme.primaryContainer, color = MaterialTheme.colorScheme.onPrimaryContainer)
    val ring = MaterialTheme.colorScheme.primary
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().registerGhost(ghost, ring.copy(alpha = 0.5f), MaterialTheme.shapes.medium),
        border = BorderStroke(2.dp, ring),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$STEP_MARK Approval needed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).semantics { contentDescription = "Step $number: approval needed" })
                Text(
                    if (privateData.isEmpty()) approval.reason.orEmpty() else "private data",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (privateData.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            Text(stepTitle(item.name), style = MaterialTheme.typography.headlineSmall)
            Text(highlight("${item.name}(${approval.summary})", privateData, mark), style = AutobotTheme.styles.codeBlock)
            if (privateData.isNotEmpty()) Text(highlight(approval.reason.orEmpty(), privateData, mark), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.approve(item.callId, ApprovalOutcome.REJECTED) }, modifier = Modifier.weight(1f)) { Text("Deny") }
                Button(onClick = { actions.approve(item.callId, ApprovalOutcome.ALLOWED_ONCE) }, modifier = Modifier.weight(1f)) { Text("Approve") }
            }
            TextButton(onClick = { actions.approve(item.callId, ApprovalOutcome.ALLOWED_FOR_SESSION) }) {
                Text("Always allow ${item.name} in this run")
            }
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

/** `Stopped`, as written; errors keep their case (ids, hosts and keys must stay readable) and expand on tap. */
@Composable
private fun NoticeRow(item: SessionItem.Notice, onRetry: (() -> Unit)? = null) {
    val color = when (item.kind) {
        NoticeKind.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeKind.WARN -> MaterialTheme.colorScheme.tertiary
        NoticeKind.ERROR -> MaterialTheme.colorScheme.error
    }
    if (item.kind == NoticeKind.ERROR) {
        var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
        Column {
            Text(
                "✗ ${item.text}",
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded },
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
        }
    } else {
        Text(labelCase(item.text), style = MaterialTheme.typography.labelLarge, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptySession(state: ChatUiState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AutobotMark(size = 32.dp)
        Text(
            if (state.toolsEnabled) "Agent ready · workspace /workspace" else "Chat",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(if (state.toolsEnabled) "What should it do?" else "Ask anything.", style = MaterialTheme.typography.headlineMedium)
    }
}

/** The chat's switches (Think, Redact, Phone first) as filter chips, the context meter, and what runs without asking. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PolicyLine(
    state: ChatUiState,
    onThink: () -> Unit = {},
    onThinkMore: () -> Unit = {},
    onRedact: () -> Unit = {},
    onCascade: () -> Unit = {},
) {
    val think = state.thinkingSupport.shown
    val redact = state.redaction != RedactionUi.NONE
    val cascade = state.cascade
    if (!state.toolsEnabled && !think && !redact && cascade == null) return
    val text = when {
        !state.toolsEnabled -> ""
        state.permission == PermissionPreset.READ_ONLY -> "Policy · read-only · asks: network"
        state.permission == PermissionPreset.WORKSPACE -> "Policy · workspace · asks: delete, network"
        else -> "Policy · full access"
    }
    // The chips (and the context meter) wrap on a narrow window; the policy has its own line under them.
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            if (think) ThinkChip(state.thinking, onThink, onThinkMore)
            if (redact) RedactChip(state.redaction == RedactionUi.ON, onRedact)
            if (cascade != null) CascadeChip(cascade, onCascade)
            val used = state.contextUsed
            val window = state.contextWindow
            if (used != null && window != null && window > 0) MetaText("${compact(used)}/${compact(window)}", Modifier.padding(horizontal = 4.dp))
        }
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A step's mark, as the terminal prints it (`▪ Run cargo test`). */
private const val STEP_MARK = "▪"


private fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", n / 1e6)
    n >= 1_000 -> "${n / 1000}k"
    else -> n.toString()
}

// ---------------------------------------------------------------------------------------------
// Bottom stack

/** The agent's plan, above the composer: done, doing and to do, foldable. */
@Composable
private fun TodoPanel(state: ChatUiState) {
    var open by rememberSaveable { mutableStateOf(true) }
    val done = state.todos.count { it.status == TodoStatus.COMPLETED }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan · $done/${state.todos.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            }
            if (open) {
                state.todos.forEach { todo ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        when (todo.status) {
                            TodoStatus.COMPLETED -> Icon(Icons.Filled.CheckCircle, contentDescription = "Done", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                            TodoStatus.IN_PROGRESS -> Icon(Icons.Filled.PlayArrow, contentDescription = "In progress", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            TodoStatus.PENDING -> Box(
                                Modifier.size(18.dp).padding(1.dp).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                                    .semantics { contentDescription = "To do" },
                            )
                        }
                        Text(
                            todo.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (todo.status == TodoStatus.COMPLETED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (todo.status == TodoStatus.COMPLETED) TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** The agent's question: its options as chips (several when it allows), or a typed answer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(question: QuestionUi, onAnswer: (String, List<String>?) -> Unit) {
    var free by rememberSaveable(question.callId, saver = BoundedText) { mutableStateOf("") }
    var picked by remember(question.callId) { mutableStateOf(setOf<String>()) }
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Agent asks", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { onAnswer(question.callId, null) }) { Text("Skip") }
            }
            Text(question.question.question, style = MaterialTheme.typography.titleMedium)
            if (question.question.options.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    question.question.options.forEach { option ->
                        val active = option in picked
                        FilterChip(
                            selected = active,
                            onClick = {
                                if (question.question.multiSelect) picked = if (active) picked - option else picked + option else onAnswer(question.callId, listOf(option))
                            },
                            label = { Text(option) },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NoPersonalizedLearning {
                    OutlinedTextField(
                        free,
                        { free = it },
                        Modifier.weight(1f),
                        placeholder = { Text("Or type an answer") },
                        singleLine = true,
                        keyboardOptions = PrivateKeyboardOptions,
                    )
                }
                Button(
                    onClick = { onAnswer(question.callId, (picked + listOfNotNull(free.trim().ifBlank { null })).toList()) },
                    enabled = free.isNotBlank() || picked.isNotEmpty(),
                ) { Text("Send") }
            }
        }
    }
}

/**
 * The composer: attach and dictate, the field (a filled, rounded text field that grows to
 * `Composer.MAX_LINES` lines), and Send (Queue while a turn runs) or Stop.
 */
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
    val action = Composer.action(text, state.pendingImages.size, state.running)
    val label = Composer.label(action)
    MessageComposer(
        value = text,
        onValueChange = { text = it },
        placeholder = Composer.placeholder(state.running),
        fieldModifier = Modifier.focusRequester(focus).testTag("composer"),
        maxLines = Composer.MAX_LINES,
        above = {
            if (state.pendingImages.isNotEmpty()) {
                AttachmentStrip(state.pendingImages, thumbnail, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), onRemove = onRemoveAttachment)
            }
            if (text.startsWith("/")) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    COMMANDS.filter { it.first.startsWith(text.substringBefore(' ')) }.forEach { (cmd, help) ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { text = cmd.substringBefore(' ') + " " }.padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // A command is code: monospace.
                            Text(cmd, style = AutobotTheme.styles.codeBlock, color = MaterialTheme.colorScheme.primary)
                            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (state.dictation != Dictation.IDLE) {
                LiveWords(liveDictation, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp))
            }
        },
        tools = {
            IconButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = state.pendingImages.size < MAX_ATTACHMENTS,
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
            ) {
                when (state.dictation) {
                    Dictation.IDLE -> Icon(AutobotIcons.Mic, contentDescription = "Dictate")
                    Dictation.RECORDING -> Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall).semantics { contentDescription = "Stop dictating" })
                    Dictation.TRANSCRIBING -> Working(size = 24.dp, description = "Transcribing")
                }
            }
        },
    ) {
        if (action == Composer.Action.STOP) {
            StopIconButton(onClick = onStop, label = label)
        } else {
            SendIconButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = Composer.enabled(action, text, state.pendingImages.size),
                label = label,
            )
        }
    }
}

/**
 * Voice mode's composer: what the loop is doing (`Voice · listening`), a level meter while
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
    val (label, hint) = when (voice.phase) {
        VoicePhase.READY -> "ready" to "Tap to talk"
        VoicePhase.LISTENING -> "listening" to if (voice.handsFree) "Pause to send · tap to send now" else "Tap to send"
        VoicePhase.TRANSCRIBING -> "transcribing" to "On this phone"
        VoicePhase.THINKING -> "thinking" to "Tap to stop"
        VoicePhase.SPEAKING -> "speaking" to "Tap to interrupt and talk"
    }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice · $label", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                FilterChip(selected = voice.handsFree, onClick = { onHandsFree(!voice.handsFree) }, label = { Text("Hands-free") })
                AssistChip(onClick = onType, label = { Text("Type") })
            }
            if (voice.phase == VoicePhase.LISTENING || voice.phase == VoicePhase.TRANSCRIBING) LiveWords(words, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (voice.phase == VoicePhase.SPEAKING) {
                        // Read aloud: the loading indicator, as tall as the meter.
                        Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.CenterStart) { Working(size = 36.dp, description = "Speaking") }
                    } else {
                        LevelMeter(levels, Modifier.fillMaxWidth().height(36.dp), live = voice.phase == VoicePhase.LISTENING)
                    }
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val talks = voice.phase == VoicePhase.READY || voice.phase == VoicePhase.SPEAKING
                val tonal = voice.phase == VoicePhase.THINKING || voice.phase == VoicePhase.SPEAKING
                FilledIconButton(
                    onClick = {
                        if (talks && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            microphone.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            onTap()
                        }
                    },
                    enabled = voice.phase != VoicePhase.TRANSCRIBING,
                    colors = if (tonal) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.filledIconButtonColors(),
                    modifier = Modifier.size(64.dp),
                ) {
                    when (voice.phase) {
                        VoicePhase.READY -> Icon(AutobotIcons.Mic, contentDescription = "Talk")
                        VoicePhase.LISTENING -> Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send now")
                        VoicePhase.TRANSCRIBING -> Working(size = 32.dp, description = "Transcribing", color = LocalContentColor.current)
                        VoicePhase.THINKING -> Box(Modifier.size(16.dp).background(LocalContentColor.current, MaterialTheme.shapes.extraSmall).semantics { contentDescription = "Stop the agent" })
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
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                image?.let { Image(it, contentDescription = "Attached image", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (onRemove != null) {
                    FilledTonalIconButton(onClick = { onRemove(id) }, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove image", Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets

/** Where the chat runs: the provider (a radio list) and the model (search field and list). */
@OptIn(ExperimentalMaterial3Api::class)
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
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
            SheetHeader("Route")
            if (state.providers.isEmpty()) TextButton(onClick = onOpenProviders, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Add a provider") }
            Column(Modifier.selectableGroup()) {
                state.providers.forEach { option ->
                    val chosen = option.id == state.selectedProviderId
                    ListItem(
                        headlineContent = { Text(option.name) },
                        supportingContent = { Text(option.defaultModel) },
                        leadingContent = { RadioButton(selected = chosen, onClick = null) },
                        modifier = Modifier.selectable(selected = chosen, role = Role.RadioButton) { onSelectProvider(option.id) },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Model", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                selected?.let { MetaText(it.modelsLabel) }
                if (selected?.canRefresh == true) TextButton(onClick = onRefreshModels) { Text("Refresh") }
            }
            NoPersonalizedLearning {
                OutlinedTextField(
                    model,
                    { model = it },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    label = { Text("Model id · type to search") },
                    singleLine = true,
                    keyboardOptions = PromptKeyboardOptions,
                )
            }
            selected?.let { option ->
                // The field searches the list unless it already names one of its models.
                val query = if (option.models.any { it.id == model }) "" else model
                ModelLists.filter(option.models, query, limit = 30).forEach { m ->
                    val active = m.id == model
                    ListItem(
                        headlineContent = { Text(m.id, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = option.caption(m).takeIf { it.isNotEmpty() }?.let { { Text(it) } },
                        trailingContent = if (active) ({ Icon(Icons.Filled.Check, contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary) }) else null,
                        modifier = Modifier.selectable(selected = active, role = Role.RadioButton) { model = m.id },
                    )
                }
            }
            Button(
                onClick = { if (selected?.trains(model) == true) confirmTrains = model else onSelectModel(model) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                enabled = model.isNotBlank() && selected != null,
            ) { Text("Use model") }
            confirmTrains?.let { TrainsConfirmDialog(it, onConfirm = { onSelectModel(it) }, onDismiss = { confirmTrains = null }) }
        }
    }
}

/** A sheet's section title: titleSmall in the primary colour, read as a heading. */
@Composable
private fun SheetHeader(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A row with a switch: the whole row toggles it. */
@Composable
private fun SwitchItem(title: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

/** One choice of [options] as Material's segmented buttons. */
@Composable
private fun <T> Choice(options: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                label = { Text(labelCase(label(option)), textAlign = TextAlign.Center) },
            )
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
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetHeader("Chat")
            ListItem(
                headlineContent = { Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { TextButton(onClick = onRename) { Text("Rename") } },
            )
            SwitchItem("Pin to top", state.pinned, onPin)
            ListItem(
                headlineContent = { Text(if (state.archived) "Unarchive" else "Archive") },
                modifier = Modifier.clickable(role = Role.Button) { onArchive(!state.archived) },
            )
            ListItem(
                headlineContent = { Text("Delete chat", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onDelete),
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SheetHeader("Session")
            SwitchItem("Agent tools", state.toolsEnabled, onTools, "Files in /workspace, web fetch, image generation, skills, plans. Off = plain chat.")
            Text("Permission preset", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge)
            Choice(PermissionPreset.entries, state.permission, onPermission, { it.label }, Modifier.padding(horizontal = 16.dp))
            ListItem(
                headlineContent = { Text("Compact context") },
                supportingContent = { Text("Summarize older turns now (also automatic near the limit).") },
                trailingContent = { TextButton(onClick = onCompact, enabled = !state.running) { Text("Compact") } },
                modifier = Modifier.padding(top = 8.dp),
            )
            val files = state.changes.sumOf { it.files.size }
            ListItem(
                headlineContent = { Text("Files changed") },
                supportingContent = {
                    Text(if (files == 0) "Nothing yet. Every file the agent changes can be undone." else "$files file${if (files == 1) "" else "s"} across ${state.changes.size} turn${if (state.changes.size == 1) "" else "s"}; undo a step or a turn.")
                },
                trailingContent = { TextButton(onClick = onChanges) { Text("View") } },
            )
        }
    }
}

/**
 * The Think switch of the chat: `Think · auto`. A tap cycles auto → on → off; a long press opens
 * the budget and the model's default ([ThinkSheet]).
 */
@Composable
private fun ThinkChip(setting: ThinkingSetting, onTap: () -> Unit, onMore: () -> Unit) {
    FilterChip(
        selected = setting.mode != ThinkingMode.OFF,
        onClick = onTap,
        label = { Text(labelCase(ThinkingLabels.chip(setting))) },
        modifier = Modifier
            .longPressAction("Thinking budget", onMore)
            .semantics { contentDescription = "Thinking ${setting.mode.name.lowercase()}" + (setting.budget?.let { ", budget $it tokens" } ?: "") },
    )
}

/**
 * The redaction switch of a cloud chat: selected while personal data becomes placeholders before
 * the provider sees it. A tap switches it for this chat.
 */
@Composable
private fun RedactChip(on: Boolean, onTap: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onTap,
        label = { Text("Redact") },
        modifier = Modifier.semantics { contentDescription = if (on) "Redaction on: names, emails and numbers are hidden from the cloud" else "Redaction off" },
    )
}

/**
 * The local-first cascade of a plain cloud chat: `Phone first · ask`, selected while this phone
 * answers first. A tap opens the choice ([CascadeSheet]).
 */
@Composable
private fun CascadeChip(policy: CascadePolicy, onTap: () -> Unit) {
    FilterChip(
        selected = policy.localFirst,
        onClick = onTap,
        label = { Text(labelCase(policy.chip)) },
        modifier = Modifier.semantics { contentDescription = "Phone first: ${policy.title}. ${policy.detail}" },
    )
}

/** Under the phone's answer when the judge was not convinced: why, and "Ask <cloud model>". */
@Composable
private fun CascadeOfferRow(offer: CascadeOffer, model: String, onAsk: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Phone first", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(offer.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onAsk) { Text(CascadeLabels.ask(model)) }
        }
    }
}

/** The chat's cascade policy: off, ask, auto or never, each with its one line, as a radio list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CascadeSheet(policy: CascadePolicy, model: String, onNode: Boolean, onPick: (CascadePolicy) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SheetHeader("Phone first")
            Column(Modifier.selectableGroup()) {
                CascadePolicy.entries.forEach { option ->
                    ListItem(
                        headlineContent = { Text(option.title) },
                        supportingContent = { Text(option.detail) },
                        leadingContent = { RadioButton(selected = option == policy, onClick = null) },
                        modifier = Modifier.selectable(selected = option == policy, role = Role.RadioButton) { onPick(option) },
                    )
                }
            }
            Text(
                CascadeLabels.sheetNote(model, onNode),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetHeader("Think")
            Choice(
                ThinkingMode.entries,
                setting.mode,
                { mode -> onThinking(ThinkingSetting(mode, if (mode == ThinkingMode.ON) setting.budget else null)) },
                { it.name.lowercase() },
                Modifier.padding(horizontal = 16.dp),
            )
            if (setting.mode == ThinkingMode.ON) {
                Text("Budget · tokens", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
                Choice(
                    ThinkingSetting.BUDGETS,
                    setting.budget,
                    { budget -> onThinking(ThinkingSetting(ThinkingMode.ON, budget)) },
                    { it?.let(ThinkingLabels::tokens) ?: "model" },
                    Modifier.padding(horizontal = 16.dp),
                )
            }
            ListItem(
                headlineContent = { Text("Default for this model") },
                supportingContent = { Text(labelCase(state.thinkingDefault?.let { ThinkingLabels.chip(it) } ?: "auto")) },
                trailingContent = {
                    if (state.thinkingDefault == setting || (state.thinkingDefault == null && setting == ThinkingSetting.AUTO)) {
                        TextButton(onClick = { onDefault(null) }, enabled = state.thinkingDefault != null) { Text("Clear") }
                    } else {
                        TextButton(onClick = { onDefault(setting) }) { Text("Set") }
                    }
                },
            )
        }
    }
}


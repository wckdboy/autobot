package dev.wckdboy.autobot.feature.home.node

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import dev.wckdboy.autobot.core.designsystem.component.NodeMark
import dev.wckdboy.autobot.core.designsystem.component.MessageComposer
import dev.wckdboy.autobot.core.designsystem.component.SendIconButton
import dev.wckdboy.autobot.core.designsystem.component.TitleWithDot
import dev.wckdboy.autobot.core.designsystem.component.readingWidth
import androidx.compose.material.icons.outlined.Warning
import dev.wckdboy.autobot.core.designsystem.component.BackIcon
import dev.wckdboy.autobot.core.designsystem.component.Field
import dev.wckdboy.autobot.core.designsystem.component.Loading
import dev.wckdboy.autobot.core.designsystem.component.NoticeCard
import dev.wckdboy.autobot.core.designsystem.component.NoticeTone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.graphics.Color
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.TextButton
import dev.wckdboy.autobot.core.designsystem.component.BubbleRole
import dev.wckdboy.autobot.core.designsystem.component.ChatBubble
import dev.wckdboy.autobot.core.designsystem.component.keyboardUp
import dev.wckdboy.autobot.shared.composer.Composer
import dev.wckdboy.autobot.shared.composer.Composer.Part
import androidx.compose.foundation.layout.consumeWindowInsets
import dev.wckdboy.autobot.shared.invincible.AgentAction
import dev.wckdboy.autobot.shared.invincible.ApprovalAnswer
import dev.wckdboy.autobot.shared.invincible.NodeBots
import dev.wckdboy.autobot.shared.invincible.NodeImages
import dev.wckdboy.autobot.core.designsystem.component.labelCase
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag

/** The Autobot Agent on a paired node: tasks run on that machine; questions come here. */
@Composable
fun NodeAgentRoute(providerId: String, onBack: () -> Unit, open: String? = null, session: String? = null, bot: String? = null) {
    val viewModel = hiltViewModel<NodeAgentViewModel, NodeAgentViewModel.Factory>(key = "$providerId/${session.orEmpty()}/${bot.orEmpty()}") { it.create(providerId, session, bot) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSchedules by rememberSaveable { mutableStateOf(false) }
    // Opened from the Run screen's "While you were away": straight to the activity (or its board).
    var showActivity by rememberSaveable { mutableStateOf(open != null) }
    var arrived by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (open != null && !arrived) viewModel.loadActivity()
        arrived = true
    }
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
        onInstead = viewModel::answerInstead,
        onRetryTurn = viewModel::retry,
        onAttach = viewModel::attach,
        onRemovePhoto = viewModel::removePhoto,
        onTookReturned = viewModel::tookReturned,
        onPhotoProblem = viewModel::photoProblem,
    )
    if (showActivity) {
        NodeActivitySheet(state, initialTab = if (open == "board") BOARD_TAB else 0, onDismiss = { showActivity = false }, onRefresh = viewModel::loadActivity, onForget = viewModel::forget, onAnswer = viewModel::answerBoard)
    }
    if (showSchedules) {
        NodeSchedulesSheet(state, onDismiss = { showSchedules = false }, onAdd = viewModel::addSchedule, onRemove = viewModel::removeSchedule)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onInstead: (id: String, text: String) -> Unit = { _, _ -> },
    onRetryTurn: () -> Unit = {},
    onAttach: (uri: Uri, fromCamera: Boolean) -> Unit = { _, _ -> },
    onRemovePhoto: (Int) -> Unit = {},
    onTookReturned: () -> Unit = {},
    onPhotoProblem: (String) -> Unit = {},
) {
    var draft by rememberSaveable { mutableStateOf("") }
    // The model could not see the photo: the text comes back to the box.
    LaunchedEffect(state.returned) {
        state.returned?.let {
            draft = it
            onTookReturned()
        }
    }
    val retryKey = AgentTranscript.retryTarget(state.rows, state.running)
    val list = rememberLazyListState()
    // The list is reversed (first item lowest): the newest row sits on the composer, also as the keyboard opens.
    LaunchedEffect(state.rows.size) { if (state.rows.isNotEmpty()) list.animateScrollToItem(0) }
    val keyboard = keyboardUp()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // A screen about the node: Invincible's pixel i after the title, where the dot sits
                    // on the app's own screens, says which product is speaking.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TitleWithDot("Agent · ${state.node}", Modifier.weight(1f, fill = false), mark = false)
                        NodeMark(size = 14.dp)
                    }
                },
                navigationIcon = { BackIcon(onBack) },
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
        // The scaffold already pads for the navigation bar: consumed, so the keyboard's padding does not add it twice.
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            LazyColumn(
                state = list,
                // A reading width on a wide window.
                modifier = Modifier.weight(1f).readingWidth().fillMaxWidth().testTag("node-agent"),
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                if (state.running) item(key = "busy") { Loading() }
                items(state.rows.asReversed(), key = { it.key }) { row -> AgentRowView(row, onAnswer, onInstead, retry = if (row.key == retryKey) onRetryTurn else null) }
                item(key = "head") {
                    when {
                        state.problem != null -> NoticeCard(state.problem, tone = NoticeTone.WARNING, action = "Retry", onAction = onRetry)
                        state.info == null -> Loading()
                        // What the conversation runs with: it steps aside while typing.
                        !Composer.shows(Part.DECOR, keyboard) -> Unit
                        else -> Text(style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, text = labelCase(
                            listOfNotNull(
                                // Several bots: which one this conversation is with.
                                "bot ${NodeBots.of(state.bot)}".takeIf { state.severalBots },
                                "runs on ${state.node}",
                                "approvals: ${state.info.approvals}",
                                "local models only".takeIf { state.info.private },
                                // The end of the path is the part that tells folders apart.
                                state.info.workdir.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/", prefix = "…/"),
                            ).joinToString(" · "),
                        ))
                    }
                }
            }
            MessageComposer(
                value = draft,
                onValueChange = { draft = it },
                placeholder = Composer.placeholder(state.running, node = true),
                modifier = Modifier.readingWidth(),
                enabled = state.info != null,
                maxLines = Composer.MAX_LINES,
                above = {
                    if (state.photos.isNotEmpty() || state.photoProblem != null || state.encoding > 0) {
                        PendingPhotos(state.photos, state.photoProblem, state.encoding, onRemovePhoto)
                    }
                },
                tools = {
                    PhotoButton(enabled = state.info != null && !state.running && state.photos.size + state.encoding < NodeImages.MAX_PHOTOS, onAttach = onAttach, onProblem = onPhotoProblem)
                },
            ) {
                // The node's agent neither queues nor stops a turn: Send waits for it, and for a photo still being downscaled.
                SendIconButton(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = Composer.enabled(Composer.Action.SEND, draft, 0, ready = !state.running && state.info != null && state.encoding == 0),
                )
            }
        }
    }
}

@Composable
private fun AgentRowView(row: AgentRow, onAnswer: (String, ApprovalAnswer) -> Unit, onInstead: (String, String) -> Unit, retry: (() -> Unit)? = null) {
    when (row) {
        is AgentRow.User -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (row.photos.isNotEmpty()) SentPhotos(row.photos)
            ChatBubble(BubbleRole.USER, row.text)
            // From the node's history: the photos stay there; how many.
            if (row.photos.isEmpty() && row.photoCount > 0) Caption(NodeImages.label(row.photoCount))
        }
        is AgentRow.Reply -> ChatBubble(
            BubbleRole.ASSISTANT,
            row.text,
            footer = if (row.route == null && retry == null) null else {
                {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(row.route.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (retry != null) RetryButton(retry)
                    }
                }
            },
        )
        // What the agent did (a tool) or what went wrong: a quiet line with its icon.
        is AgentRow.Note -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tool = row.tone == AgentRow.Tone.TOOL
            val color = if (tool) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            Icon(if (tool) AutobotIcons.Wrench else Icons.Outlined.Warning, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(row.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = color)
            if (retry != null) RetryButton(retry)
        }
        // Quiet: what the agent kept about the owner or the work, on its own.
        is AgentRow.Kept -> Text(
            row.text,
            Modifier.testTag("kept"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is AgentRow.Question -> QuestionCard(row, onAnswer, onInstead)
    }
}

@Composable
private fun RetryButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(AutobotIcons.Retry, contentDescription = "Retry on the node", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/**
 * Thumbnails of JPEGs, decoded small off the main thread (a node's chat can hold several photos:
 * decoding them while composing dropped frames on opening and scrolling it). The square keeps
 * its place while decoding; a photo that cannot be read takes none.
 */
@Composable
internal fun PhotoThumb(jpeg: ByteArray, side: Int, description: String) {
    val decoded by produceState<Thumb>(Thumb.Pending, jpeg, side) {
        value = withContext(Dispatchers.Default) { decodeThumb(jpeg, side * 3)?.let(Thumb::Ready) ?: Thumb.Unreadable }
    }
    val thumb = decoded
    if (thumb == Thumb.Unreadable) return
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier
            .size(side.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .semantics {
                contentDescription = description
                role = Role.Image
            },
    ) {
        if (thumb is Thumb.Ready) {
            Image(thumb.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** Test hook: told the thread each thumbnail is decoded on. Null in the app. */
internal var thumbDecodeProbe: ((Thread) -> Unit)? = null

private sealed interface Thumb {
    data object Pending : Thumb
    data object Unreadable : Thumb
    class Ready(val bitmap: ImageBitmap) : Thumb
}

@Composable
private fun SentPhotos(photos: List<ByteArray>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        photos.forEachIndexed { i, p -> PhotoThumb(p, if (photos.size == 1) 160 else 96, "Photo ${i + 1} sent") }
    }
}

@Composable
private fun PendingPhotos(photos: List<ByteArray>, problem: String?, encoding: Int, onRemove: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            photos.forEachIndexed { i, p ->
                Box {
                    PhotoThumb(p, 64, "Photo ${i + 1}")
                    IconButton(onClick = { onRemove(i) }, modifier = Modifier.size(28.dp).align(Alignment.TopEnd).background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove photo ${i + 1}", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
        when {
            problem != null -> Caption(problem, color = MaterialTheme.colorScheme.error)
            encoding > 0 -> Caption("preparing ${NodeImages.label(encoding)}…")
            else -> Caption("sent to the node at up to ${NodeImages.MAX_SIDE} px, without location")
        }
    }
}

/** Attach a photo: the camera, or the library (the system photo picker, no permission needed). */
@Composable
private fun PhotoButton(enabled: Boolean, onAttach: (Uri, Boolean) -> Unit, onProblem: (String) -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var shot by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { onAttach(it, false) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val uri = shot?.let(Uri::parse)
        if (taken && uri != null) onAttach(uri, true)
        shot = null
    }
    Box {
        IconButton(onClick = { menu = true }, enabled = enabled) {
            Icon(AutobotIcons.Image, contentDescription = "Attach a photo")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Take a photo") }, onClick = {
                menu = false
                runCatching {
                    val target = NodePhotos.cameraTarget(context)
                    shot = target.toString()
                    camera.launch(target)
                }.onFailure { onProblem("There is no camera app to take a photo with.") }
            })
            DropdownMenuItem(text = { Text("Choose from library") }, onClick = {
                menu = false
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
        }
    }
}

private fun decodeThumb(jpeg: ByteArray, maxPx: Int): ImageBitmap? {
    thumbDecodeProbe?.invoke(Thread.currentThread())
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
internal fun QuestionCard(row: AgentRow.Question, onAnswer: (String, ApprovalAnswer) -> Unit, onInstead: (String, String) -> Unit = { _, _ -> }) {
    val (title, detail) = when (val a = row.action) {
        is AgentAction.Command -> "Run a command?" to a.command
        is AgentAction.Write -> (if (a.inside) "Write a file?" else "Write outside the working directory?") to "${a.path} · ${a.bytes} bytes"
        is AgentAction.Read -> "Read outside the working directory?" to a.path
        is AgentAction.Cloud -> "Use a cloud model for this?" to a.reason
        is AgentAction.Fetch -> "Fetch a web page?" to a.url
        is AgentAction.Mcp -> "Use ${a.tool}?" to a.arguments
        is AgentAction.Other -> "Approve?" to row.text
    }.let { (title, detail) -> (row.agent?.let { "$it: $title" } ?: title) to detail }
    Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp).semantics { contentDescription = "Approval: $title $detail" }) {
        // Several bots: which one asks.
        if (row.bot != null) Text(labelCase("bot ${row.bot}"), Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = AutobotTheme.styles.code, modifier = Modifier.padding(vertical = 8.dp))
        if (row.instead != null) {
            Text("Asked instead: ${row.instead}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        when (row.answer) {
            null -> {
                var other by rememberSaveable(row.id) { mutableStateOf(false) }
                var text by rememberSaveable(row.id) { mutableStateOf("") }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAnswer(row.id, ApprovalAnswer.ONCE) }) { Text("Allow") }
                    OutlinedButton(onClick = { onAnswer(row.id, ApprovalAnswer.ALWAYS) }) { Text("Always") }
                    OutlinedButton(onClick = { onAnswer(row.id, ApprovalAnswer.DENY) }) { Text("Deny") }
                    // The quiet third answer: no, and do this instead.
                    if (!other) {
                        TextButton(onClick = { other = true }) { Text("Something else…") }
                    }
                }
                if (other) {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field(text, { text = it }, Modifier.weight(1f), placeholder = "What to do instead", singleLine = true)
                        FilledTonalButton(onClick = { onInstead(row.id, text) }, enabled = text.isNotBlank()) { Text("Send") }
                    }
                }
            }
            ApprovalAnswer.ONCE -> Text("Allowed", style = MaterialTheme.typography.labelLarge)
            ApprovalAnswer.ALWAYS -> Text("Allowed for this conversation", style = MaterialTheme.typography.labelLarge)
            ApprovalAnswer.DENY -> Text("Denied", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        }
    }
    }
}

/** A quiet caption line in sentence case. */
@Composable
private fun Caption(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(labelCase(text), style = MaterialTheme.typography.labelMedium, color = color)
}

package dev.wckdboy.autobot.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.Route
import dev.wckdboy.autobot.core.designsystem.component.RouteChip
import dev.wckdboy.autobot.core.designsystem.component.TonalLabel
import dev.wckdboy.autobot.core.designsystem.component.Wordmark
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.core.designsystem.component.readingWidth
import dev.wckdboy.autobot.core.designsystem.icon.MaterialSymbols
import dev.wckdboy.autobot.feature.home.node.NodeDigestCard
import dev.wckdboy.autobot.feature.home.node.NodeDigestCardState
import dev.wckdboy.autobot.feature.home.node.NodeDigestViewModel
import java.util.Locale

@Composable
fun RunRoute(
    onOpenSession: (String) -> Unit,
    onOpenImagine: () -> Unit,
    onOpenStudio: () -> Unit,
    onOpenAllRuns: () -> Unit,
    onOpenGalleryItem: (String) -> Unit,
    onOpenModels: () -> Unit,
    /**
     * The agent on a paired node; [open] is a sheet to open there (`activity`, `board`), or null;
     * [session] a conversation to open instead of this device's (where approvals wait).
     */
    onOpenNodeAgent: (providerId: String, open: String?, session: String?) -> Unit = { _, _, _ -> },
    viewModel: RunViewModel = hiltViewModel(),
    digestModel: NodeDigestViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val digest by digestModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { if (it is RunEvent.OpenSession) onOpenSession(it.conversationId) }
    }
    // Each time Run shows again: what the node did meanwhile.
    LifecycleResumeEffect(digestModel) {
        digestModel.refresh()
        onPauseOrDispose {}
    }
    RunScreen(
        state, viewModel, onOpenSession, onOpenImagine, onOpenStudio, onOpenAllRuns, onOpenGalleryItem, onOpenModels,
        digest = digest,
        onDigestNeedsYou = { d ->
            digestModel.markSeen()
            // Approvals hold a turn: the agent, in their conversation when they share one. Else the board's questions.
            if (d.digest.opensBoard) onOpenNodeAgent(d.providerId, NODE_SHEET_BOARD, null) else onOpenNodeAgent(d.providerId, null, d.digest.approvalSession)
        },
        onDigestDetails = { d ->
            digestModel.markSeen()
            onOpenNodeAgent(d.providerId, NODE_SHEET_ACTIVITY, null)
        },
        onDigestDismiss = digestModel::markSeen,
    )
}

/** Sheets the node agent screen opens on arrival. */
const val NODE_SHEET_ACTIVITY = "activity"
const val NODE_SHEET_BOARD = "board"

/**
 * The Run tab: what this phone can do, as Material cards (Image, Chat, Agent, Code, Studio) that
 * reflow from a phone's 2-column grid to one row on a wide window, and the recent runs as list items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    state: RunUiState,
    actions: RunViewModel,
    onOpenSession: (String) -> Unit,
    onOpenImagine: () -> Unit,
    onOpenStudio: () -> Unit,
    onOpenAllRuns: () -> Unit,
    onOpenGalleryItem: (String) -> Unit,
    onOpenModels: () -> Unit,
    digest: NodeDigestCardState? = null,
    onDigestNeedsYou: (NodeDigestCardState) -> Unit = {},
    onDigestDetails: (NodeDigestCardState) -> Unit = {},
    onDigestDismiss: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                // The home's title is the wordmark: the pixel a and the name.
                title = { Wordmark() },
                actions = { if (state.live > 0) TonalLabel("${state.live} live", Modifier.padding(end = 12.dp)) },
            )
        },
    ) { padding ->
        val large = isLargeText()
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    state.status.replaceFirstChar { it.titlecase(Locale.ROOT) },
                    Modifier.readingWidth().fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // What the paired node did meanwhile; nothing when there is no node or nothing new.
            if (digest != null) {
                item(key = "node-digest") {
                    Box(Modifier.readingWidth().fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                        NodeDigestCard(digest, { onDigestNeedsYou(digest) }, { onDigestDetails(digest) }, onDigestDismiss)
                    }
                }
            }
            item(key = "modes") {
                val last by produceState<ImageBitmap?>(null, state.lastImageId) { value = state.lastImageId?.let { actions.thumbnail(it) } }
                ModeGrid(
                    image = { m ->
                        ModeCard(
                            "Image", MaterialSymbols.Image, state.imageLabel, state.imageRoute, onOpenImagine, m,
                            // The last image, as it is, above the words; nothing when there is none.
                            picture = last?.let { image ->
                                {
                                    Image(
                                        image,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            },
                        )
                    },
                    chat = { m -> ModeCard("Chat", MaterialSymbols.Forum, state.chat?.label ?: "add a model", state.chat?.route, { if (state.chat != null) actions.start(RunMode.CHAT) else onOpenModels() }, m) },
                    agent = { m -> ModeCard("Agent", MaterialSymbols.AutoFixHigh, state.agent?.label ?: "add a model", state.agent?.route, { if (state.agent != null) actions.start(RunMode.AGENT) else onOpenModels() }, m) },
                    code = { m -> ModeCard("Code", MaterialSymbols.Code, state.code?.label ?: "add a code model", state.code?.route, { if (state.code != null) actions.start(RunMode.CODE) else onOpenModels() }, m) },
                    // Studio: image, audio, video, workflows and the library behind its own bar
                    // (images have their own card, so the line names what else is there).
                    studio = { m -> ModeCard("Studio", MaterialSymbols.Stacks, "image · audio · video", null, onOpenStudio, m) },
                    modifier = Modifier.readingWidth().fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                )
            }
            if (!state.hasAnything) {
                item {
                    Button(onClick = onOpenModels, modifier = Modifier.readingWidth().fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) { Text("Get a model") }
                }
            }
            // Always there: "All" is the way to every chat, archived ones too, even with nothing recent.
            item {
                Row(Modifier.readingWidth().fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Recent runs",
                        Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = onOpenAllRuns) { Text("All") }
                }
            }
            items(state.recent, key = { it.key }) { run ->
                ListItem(
                    headlineContent = { Text(run.title) },
                    supportingContent = {
                        // A large font moves the route under the text, where it has the row's width.
                        if (large) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (run.subtitle.isNotEmpty()) Text(run.subtitle)
                                RouteChip(run.route)
                            }
                        } else if (run.subtitle.isNotEmpty()) {
                            Text(run.subtitle)
                        }
                    },
                    trailingContent = if (large) null else ({ RouteChip(run.route) }),
                    modifier = Modifier.readingWidth().fillMaxWidth().clickable(role = Role.Button) {
                        run.conversationId?.let(onOpenSession)
                        run.galleryId?.let(onOpenGalleryItem)
                    },
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * The five modes. A phone: Image tall on the left beside Chat over Agent, then Code and Studio.
 * A wide window (600 dp and up): the five side by side.
 */
@Composable
private fun ModeGrid(
    image: @Composable (Modifier) -> Unit,
    chat: @Composable (Modifier) -> Unit,
    agent: @Composable (Modifier) -> Unit,
    code: @Composable (Modifier) -> Unit,
    studio: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 180.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER)) {
                listOf(image, chat, agent, code, studio).forEach { it(Modifier.weight(1f).fillMaxHeight()) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(GRID_GUTTER)) {
                // Grows past its minimum when a large font needs it (Chat and Agent each stack a route, title and model).
                Row(Modifier.fillMaxWidth().heightIn(min = 236.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER)) {
                    image(Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GRID_GUTTER)) {
                        chat(Modifier.weight(1f).fillMaxWidth())
                        agent(Modifier.weight(1f).fillMaxWidth())
                    }
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER)) {
                    code(Modifier.weight(1f).fillMaxHeight())
                    studio(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/**
 * A mode: its [icon] (the Material Symbol matching the iOS app's SF Symbol), where it runs, a
 * [picture] when there is one, the title and the model, on a filled card.
 */
@Composable
private fun ModeCard(
    title: String,
    icon: ImageVector,
    subtitle: String,
    route: Route?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    picture: (@Composable () -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("mode-icon-$title"))
            Spacer(Modifier.height(8.dp))
            // Where it runs, at the top: the titles below line up across cards with or without one.
            route?.let {
                RouteChip(it)
                Spacer(Modifier.height(12.dp))
            }
            if (picture != null) {
                Box(Modifier.fillMaxWidth().weight(1f, fill = true).padding(bottom = 12.dp)) { picture() }
            } else {
                Spacer(Modifier.weight(1f, fill = true))
            }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The gutter between the mode cards, both across and down. */
private val GRID_GUTTER = 8.dp

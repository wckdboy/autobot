package dev.wckdboy.autobot.feature.home

import dev.wckdboy.autobot.core.designsystem.component.ActionText
import androidx.compose.ui.Alignment
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.CountBadge
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.ModeCard
import dev.wckdboy.autobot.core.designsystem.component.PrimaryButton
import dev.wckdboy.autobot.core.designsystem.component.RouteTag
import dev.wckdboy.autobot.core.designsystem.component.RunRow
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel

@Composable
fun RunRoute(
    onOpenSession: (String) -> Unit,
    onOpenImagine: () -> Unit,
    onOpenStudio: () -> Unit,
    onOpenAllRuns: () -> Unit,
    onOpenGalleryItem: (String) -> Unit,
    onOpenModels: () -> Unit,
    viewModel: RunViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { if (it is RunEvent.OpenSession) onOpenSession(it.conversationId) }
    }
    RunScreen(state, viewModel, onOpenSession, onOpenImagine, onOpenStudio, onOpenAllRuns, onOpenGalleryItem, onOpenModels)
}

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
) {
    Scaffold(topBar = { AutobotTopBar(actions = { if (state.live > 0) CountBadge("${state.live} live") }) }) { padding ->
        run {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item {
                    SectionLabel(state.status.replaceFirstChar { it.titlecase(Locale.ROOT) })
                    Spacer(Modifier.height(14.dp))
                }
                // One 2-column grid: every row splits at the same x with the same gutter.
                item {
                    // Grows past 268 dp when a large font needs it (Chat and Agent each stack a tag, title and model).
                    Row(Modifier.fillMaxWidth().heightIn(min = 236.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER)) {
                        val last by produceState<ImageBitmap?>(null, state.lastImageId) { value = state.lastImageId?.let { actions.thumbnail(it) } }
                        ModeCard(
                            index = "01",
                            title = "Image",
                            subtitle = state.imageLabel,
                            route = state.imageRoute,
                            onClick = onOpenImagine,
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            // The last image, as it is, above the words; nothing when there is none.
                            background = last?.let { image ->
                                {
                                    androidx.compose.foundation.Image(
                                        image,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.extraSmall),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    )
                                }
                            },
                        )
                        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GRID_GUTTER)) {
                            ModeCard("02", "Chat", state.chat?.label ?: "add a model", state.chat?.route, { if (state.chat != null) actions.start(RunMode.CHAT) else onOpenModels() }, Modifier.weight(1f).fillMaxWidth(), living = true)
                            ModeCard("03", "Agent", state.agent?.label ?: "add a model", state.agent?.route, { if (state.agent != null) actions.start(RunMode.AGENT) else onOpenModels() }, Modifier.weight(1f).fillMaxWidth(), living = true)
                        }
                    }
                    Spacer(Modifier.height(GRID_GUTTER))
                    // Grows past 100 dp when a large font size needs it (the route tag pushes Code's model name down).
                    Row(Modifier.fillMaxWidth().heightIn(min = 100.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(GRID_GUTTER)) {
                        ModeCard("04", "Code", state.code?.label ?: "add a code model", state.code?.route, { if (state.code != null) actions.start(RunMode.CODE) else onOpenModels() }, Modifier.weight(1f).fillMaxSize(), living = true)
                        // Studio mode: image, audio, video, workflows and the library behind its own bar
                        // (images have their own card, so the line names what else is there and fits one line).
                        ModeCard("05", "Studio", "audio · video", null, onOpenStudio, Modifier.weight(1f).fillMaxSize(), living = true)
                    }
                    Spacer(Modifier.height(22.dp))
                }
                if (!state.hasAnything) {
                    item {
                        PrimaryButton("Get a model", onOpenModels, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(22.dp))
                    }
                }
                // Nothing run yet: no empty section (the cards above are the way in).
                if (state.recent.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Recent runs", Modifier.weight(1f))
                            TextButton(onClick = onOpenAllRuns) { ActionText("All", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                items(state.recent, key = { it.key }) { run ->
                    RunRow(
                        title = run.title,
                        subtitle = run.subtitle,
                        onClick = {
                            run.conversationId?.let(onOpenSession)
                            run.galleryId?.let(onOpenGalleryItem)
                        },
                    ) { RouteTag(run.route) }
                }
                item { Box(Modifier.height(16.dp)) }
            }
        }
    }
}

/** The gutter between the mode cards, both across and down. */
private val GRID_GUTTER = 8.dp

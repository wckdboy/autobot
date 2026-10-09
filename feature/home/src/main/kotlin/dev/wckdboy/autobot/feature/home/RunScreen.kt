package dev.wckdboy.autobot.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.CountBadge
import dev.wckdboy.autobot.core.designsystem.component.HalftoneField
import dev.wckdboy.autobot.core.designsystem.component.HalftoneImage
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
    onOpenAllRuns: () -> Unit,
    onOpenGalleryItem: (String) -> Unit,
    onOpenModels: () -> Unit,
    viewModel: RunViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { if (it is RunEvent.OpenSession) onOpenSession(it.conversationId) }
    }
    RunScreen(state, viewModel, onOpenSession, onOpenImagine, onOpenAllRuns, onOpenGalleryItem, onOpenModels)
}

@Composable
fun RunScreen(
    state: RunUiState,
    actions: RunViewModel,
    onOpenSession: (String) -> Unit,
    onOpenImagine: () -> Unit,
    onOpenAllRuns: () -> Unit,
    onOpenGalleryItem: (String) -> Unit,
    onOpenModels: () -> Unit,
) {
    Scaffold(topBar = { AutobotTopBar(actions = { if (state.live > 0) CountBadge("${state.live} LIVE") }) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                SectionLabel(state.status)
                Spacer(Modifier.height(10.dp))
                Text("Run\nanything.", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(18.dp))
            }
            item {
                Row(Modifier.fillMaxWidth().height(268.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val last by produceState<ImageBitmap?>(null, state.lastImageId) { value = state.lastImageId?.let { actions.thumbnail(it) } }
                    ModeCard(
                        index = "01",
                        title = "Image",
                        subtitle = state.imageLabel,
                        route = state.imageRoute,
                        onClick = onOpenImagine,
                        modifier = Modifier.weight(1.1f).fillMaxSize(),
                        background = {
                            val image = last
                            if (image != null) {
                                HalftoneImage(image, Modifier.fillMaxSize(), cell = 7.dp)
                            } else {
                                HalftoneField(Modifier.fillMaxSize(), cell = 8.dp)
                            }
                        },
                    )
                    Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeCard("02", "Chat", state.chat?.label ?: "add a model", state.chat?.route, { if (state.chat != null) actions.start(RunMode.CHAT) else onOpenModels() }, Modifier.weight(1f).fillMaxWidth())
                        ModeCard("03", "Agent", state.agent?.label ?: "add a model", state.agent?.route, { if (state.agent != null) actions.start(RunMode.AGENT) else onOpenModels() }, Modifier.weight(1f).fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(8.dp))
                ModeCard("04", "Code", state.code?.label ?: "add a code model", state.code?.route, { if (state.code != null) actions.start(RunMode.CODE) else onOpenModels() }, Modifier.fillMaxWidth().height(100.dp))
                Spacer(Modifier.height(22.dp))
            }
            if (!state.hasAnything) {
                item {
                    MicroLabel("no models yet — download one to run on this phone, or add a provider under remote")
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Get a model", onOpenModels, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(22.dp))
                }
            }
            item {
                Row {
                    MicroLabel("recent runs", Modifier.weight(1f).padding(top = 14.dp))
                    TextButton(onClick = onOpenAllRuns) { Text("ALL →", style = MaterialTheme.typography.labelMedium) }
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
            if (state.recent.isEmpty()) {
                item { MicroLabel("nothing yet", Modifier.padding(vertical = 12.dp)) }
            }
            item { Box(Modifier.height(16.dp)) }
        }
    }
}

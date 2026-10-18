package dev.wckdboy.autobot.feature.imagine.gallery

import android.content.ClipData
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.feature.imagine.viewer.ImageViewer
import dev.wckdboy.autobot.feature.imagine.viewer.ViewerAction
import androidx.compose.ui.platform.LocalContext
import dev.wckdboy.autobot.core.designsystem.component.isLargeText
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.rememberLocalText
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Readout
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.hairlineEdge
import dev.wckdboy.autobot.core.designsystem.component.InkLoaderBlock
import dev.wckdboy.autobot.core.designsystem.component.inkGround
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun GalleryRoute(
    onBack: (() -> Unit)?,
    onReuse: (galleryId: String, action: ViewerAction) -> Unit,
    openId: String? = null,
    /** Images to swipe through when opened on [openId] from elsewhere (a chat's batch); empty = the whole gallery. */
    scope: List<String> = emptyList(),
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(openId) { openId?.let { viewModel.open(it, scope, returnOnClose = true) } }
    GalleryScreen(state, viewModel, onBack, onReuse)
}

@Composable
fun GalleryScreen(
    state: GalleryUiState,
    actions: GalleryViewModel,
    onBack: (() -> Unit)?,
    onReuse: (galleryId: String, action: ViewerAction) -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            actions.consumeMessage()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding().hairlineEdge()) {
                // A large font leaves the title no room beside the count: the count goes on a line under it.
                val large = isLargeText()
                val count = "${state.items.size}/${state.total} · encrypted"
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    } else {
                        Spacer(Modifier.size(12.dp))
                    }
                    Text("GALLERY", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (!large) MicroLabel(count, Modifier.padding(end = 8.dp))
                    IconButton(onClick = { actions.setFavoritesOnly(!state.favoritesOnly) }) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Favorites only",
                            tint = if (state.favoritesOnly) AutobotTheme.inks.amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (large) MicroLabel(count, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                // Typed text stays here; the list filters a moment later (no dropped keys).
                val query = rememberLocalText(state.query, actions::setQuery)
                ConsoleTextField(
                    query.text,
                    query::edit,
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    placeholder = "search prompts / backends",
                    singleLine = true,
                    mono = true,
                )
            }
        },
    ) { padding ->
        if (state.loaded && state.items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().inkGround(), contentAlignment = Alignment.Center) {
                MicroLabel(if (state.total == 0) "nothing generated yet" else "no matches")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.items, key = { it.id }) { item -> Thumb(item, actions) { actions.open(item.id) } }
            }
        }
    }

    state.open?.let { item ->
        val context = LocalContext.current
        ImageViewer(
            items = state.viewerItems,
            initialId = item.id,
            loadImage = actions.images::image,
            onAction = { action, current ->
                if (action.handoff) {
                    actions.close()
                    onReuse(current.id, action)
                } else {
                    actions.act(action, current) { intent -> context.startActivity(intent) }
                }
            },
            onDismiss = { if (state.returnOnClose && onBack != null) onBack() else actions.close() },
            actionsFor = { ViewerAction.forImage(it.id, state.canUpscale) },
            params = actions.images::params,
            busy = state.busy,
        )
    }
}

@Composable
private fun Thumb(item: GalleryItem, actions: GalleryViewModel, onClick: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(null, item.id) { value = actions.thumbnail(item.id) }
    Box(
        Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick),
    ) {
        bitmap?.let { Image(it, contentDescription = item.prompt.take(60), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.align(Alignment.TopEnd).stamp(item.favorite, enabled = item.favorite)) {
            if (item.favorite) Icon(Icons.Filled.Star, null, tint = AutobotTheme.inks.amber, modifier = Modifier.padding(4.dp).size(14.dp))
        }
    }
}

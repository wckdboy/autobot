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
import dev.wckdboy.autobot.core.designsystem.component.ConsoleTextField
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Readout
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.component.hairlineEdge
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun GalleryRoute(
    onBack: (() -> Unit)?,
    onReuse: (galleryId: String, asInit: Boolean) -> Unit,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    GalleryScreen(state, viewModel, onBack, onReuse)
}

@Composable
fun GalleryScreen(
    state: GalleryUiState,
    actions: GalleryViewModel,
    onBack: (() -> Unit)?,
    onReuse: (galleryId: String, asInit: Boolean) -> Unit,
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    } else {
                        Spacer(Modifier.size(12.dp))
                    }
                    Text("GALLERY", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    MicroLabel("${state.items.size}/${state.total} · encrypted", Modifier.padding(end = 8.dp))
                    IconButton(onClick = { actions.setFavoritesOnly(!state.favoritesOnly) }) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "Favorites only",
                            tint = if (state.favoritesOnly) AutobotColors.Amber else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ConsoleTextField(
                    state.query,
                    actions::setQuery,
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    placeholder = "search prompts / backends",
                    singleLine = true,
                    mono = true,
                )
            }
        },
    ) { padding ->
        if (state.loaded && state.items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
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
        DetailDialog(
            item = item,
            image = state.openImage,
            parameters = actions.parametersText(item),
            onClose = actions::close,
            onFavorite = { actions.toggleFavorite(item) },
            onReuse = { actions.close(); onReuse(item.id, false) },
            onInit = { actions.close(); onReuse(item.id, true) },
            onExport = { actions.export(item) },
            onDelete = { actions.delete(item) },
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
        if (item.favorite) {
            Icon(Icons.Filled.Star, null, tint = AutobotColors.Amber, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailDialog(
    item: GalleryItem,
    image: ImageBitmap?,
    parameters: String,
    onClose: () -> Unit,
    onFavorite: () -> Unit,
    onReuse: () -> Unit,
    onInit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmExport by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    MicroLabel(DateUtils.getRelativeTimeSpanString(item.createdAt).toString(), Modifier.weight(1f))
                    IconButton(onClick = onFavorite) {
                        Icon(Icons.Filled.Star, "Favorite", tint = if (item.favorite) AutobotColors.Amber else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Hairline()
                ZoomableImage(image, Modifier.fillMaxWidth().weight(1f))
                Hairline()
                Column(
                    Modifier.fillMaxWidth().weight(0.8f).verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MicroLabel("Prompt")
                    Text(item.prompt, style = AutobotTheme.styles.code)
                    if (item.negativePrompt.isNotBlank()) {
                        MicroLabel("Negative")
                        Text(item.negativePrompt, style = AutobotTheme.styles.codeBlock, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Readout("Seed", item.seed.toString(), valueColor = MaterialTheme.colorScheme.primary)
                        Readout("Size", "${item.width}×${item.height}")
                        Readout("Steps", item.steps.toString())
                        Readout("CFG", String.format(Locale.ROOT, "%.1f", item.cfgScale))
                        Readout("Sampler", item.sampler)
                        Readout("Mode", item.mode.lowercase())
                        Readout("Engine", item.engine)
                        Readout("Time", String.format(Locale.ROOT, "%.1fs", item.durationMs / 1000f))
                        item.model?.let { Readout("Model", it) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag("reuse params", accent = MaterialTheme.colorScheme.primary, onClick = onReuse)
                        Tag("→ img2img", accent = AutobotColors.Uv, onClick = onInit)
                        Tag(
                            "copy params",
                            accent = MaterialTheme.colorScheme.onSurfaceVariant,
                            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("parameters", parameters))) } },
                        )
                        Tag("export", accent = AutobotColors.Amber, onClick = { confirmExport = true })
                        Tag("delete", accent = MaterialTheme.colorScheme.error, onClick = { confirmDelete = true })
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Export unencrypted copy?") },
            text = { Text("A plain image will be written to Pictures/Autobot, where other apps and backups can read it.") },
            confirmButton = { TextButton(onClick = { confirmExport = false; onExport() }) { Text("EXPORT") } },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("CANCEL") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete image?") },
            text = { Text("The encrypted file and its parameters are removed. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("DELETE", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun ZoomableImage(image: ImageBitmap?, modifier: Modifier) {
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(modifier.transformable(transform), contentAlignment = Alignment.Center) {
        if (image == null) {
            MicroLabel("decrypting…")
        } else {
            Image(
                image,
                contentDescription = "Generated image",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            )
        }
    }
}

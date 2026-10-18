package dev.wckdboy.autobot.feature.imagine.viewer

import android.content.ClipData
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.wckdboy.autobot.core.data.model.GalleryItem
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.InkLoaderBlock
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.Readout
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.motion.stamp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.diffusion.DiffusionMode
import dev.wckdboy.autobot.core.diffusion.DiffusionRequest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.launch

/**
 * Full-screen viewer over gallery images: pinch or double-tap to zoom, pan, swipe sideways
 * through [items], swipe down (unzoomed) or tap close to dismiss. The info sheet shows the file's
 * real pixel size and how it was made; the action row hands the image to the studio (img2img,
 * inpaint, vary, reuse, same seed) or acts on it (upscale, share, save, copy, favorite, delete).
 *
 * @param items the pages, in order (a deleted item drops out and the pager moves on).
 * @param params decoded settings of an item (denoise, sampler), or null for old rows.
 * @param actionsFor the actions an image offers ([ViewerAction.forImage]: an unsaved incognito image
 *   can only be kept, copied from or handed to the studio); [ViewerAction.FAVORITE] shows as the star.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImageViewer(
    items: List<GalleryItem>,
    initialId: String,
    loadImage: suspend (String) -> ViewerImage?,
    onAction: (ViewerAction, GalleryItem) -> Unit,
    onDismiss: () -> Unit,
    actionsFor: (GalleryItem) -> Set<ViewerAction> = { ViewerAction.forImage(it.id, canUpscale = false) },
    params: (GalleryItem) -> DiffusionRequest? = { null },
    busy: String? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ImageViewerContent(items, initialId, loadImage, onAction, onDismiss, actionsFor, params, busy)
    }
}

/** The viewer without its window (rendered directly in tests). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ImageViewerContent(
    items: List<GalleryItem>,
    initialId: String,
    loadImage: suspend (String) -> ViewerImage?,
    onAction: (ViewerAction, GalleryItem) -> Unit,
    onDismiss: () -> Unit,
    actionsFor: (GalleryItem) -> Set<ViewerAction> = { ViewerAction.forImage(it.id, canUpscale = false) },
    params: (GalleryItem) -> DiffusionRequest? = { null },
    busy: String? = null,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val start = items.indexOfFirst { it.id == initialId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var drag by remember { mutableFloatStateOf(0f) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<ViewerAction?>(null) }
    val pixels = remember { HashMap<String, Pair<Int, Int>>() }
    val dismissPx = with(LocalDensity.current) { DISMISS_DISTANCE.toPx() }
    val scope = rememberCoroutineScope()
    val current = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    val actions = actionsFor(current)
    val clipboard = LocalClipboard.current
    val ink = MaterialTheme.colorScheme.onBackground
    val paper = MaterialTheme.colorScheme.background
    val fade = (1f - abs(drag) / (dismissPx * 2.5f)).coerceIn(0f, 1f)

    fun act(action: ViewerAction) {
        when (action) {
            ViewerAction.DELETE, ViewerAction.SAVE -> confirm = action
            ViewerAction.COPY_PARAMS -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("parameters", GalleryImages.parametersText(current)))) }
            else -> onAction(action, current)
        }
    }

    Box(Modifier.fillMaxSize().background(paper.copy(alpha = fade)).testTag("image-viewer")) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
            key = { items[it].id },
        ) { page ->
            val item = items[page]
            val image by produceState<ViewerImage?>(null, item.id) { value = loadImage(item.id) }
            image?.let { pixels[item.id] = it.pixelWidth to it.pixelHeight }
            ZoomableImage(
                image = image,
                description = item.prompt.take(80).ifBlank { "Generated image" },
                active = page == pager.currentPage,
                onZoom = { if (page == pager.currentPage) zoomed = it },
                onTap = { chrome = !chrome },
                onDrag = { drag = it },
                onDragEnd = { if (abs(drag) > dismissPx) onDismiss() else scope.launch { animate(drag, 0f) { v, _ -> drag = v } } },
                offsetY = if (page == pager.currentPage) drag else 0f,
            )
        }

        AnimatedVisibility(chrome && drag == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().background(paper.copy(alpha = 0.92f)).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close viewer", tint = ink) }
                    Column(Modifier.weight(1f)) {
                        MicroLabel(if (items.size > 1) "${pager.currentPage + 1} / ${items.size}" else "image")
                        val px = pixels[current.id] ?: (current.width to current.height)
                        Text("${px.first}×${px.second}", style = AutobotTheme.styles.readout, color = ink)
                    }
                    if (ViewerAction.FAVORITE in actions) {
                        IconButton(onClick = { act(ViewerAction.FAVORITE) }) {
                            Icon(
                                Icons.Filled.Star,
                                contentDescription = if (current.favorite) "Remove from favorites" else "Add to favorites",
                                tint = if (current.favorite) AutobotTheme.inks.amber else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.stamp(current.favorite, enabled = current.favorite),
                            )
                        }
                    }
                    if (ViewerAction.SHARE in actions) IconButton(onClick = { act(ViewerAction.SHARE) }) { Icon(Icons.Filled.Share, contentDescription = "Share image", tint = ink) }
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, contentDescription = "Image info", tint = ink) }
                }
                Hairline(color = ink)
            }
        }

        AnimatedVisibility(chrome && drag == 0f, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(Modifier.fillMaxWidth().background(paper.copy(alpha = 0.92f)).navigationBarsPadding()) {
                Hairline(color = ink)
                busy?.let { MicroLabel(it, Modifier.padding(start = 16.dp, top = 8.dp), color = MaterialTheme.colorScheme.primary) }
                // The whole prompt, scrolling in a short box (large text never cuts it off).
                Text(
                    current.prompt.ifBlank { "no prompt" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .heightIn(max = 72.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ViewerAction.entries.filter { it in actions && it != ViewerAction.FAVORITE && it != ViewerAction.SHARE }.forEach { action ->
                        Tag(
                            action.label,
                            accent = when (action) {
                                ViewerAction.IMG2IMG -> AutobotTheme.inks.uv
                                ViewerAction.KEEP -> AutobotTheme.inks.amber
                                ViewerAction.DELETE -> MaterialTheme.colorScheme.error
                                ViewerAction.SAVE -> AutobotTheme.inks.amber
                                ViewerAction.COPY_PARAMS -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.primary
                            },
                            filled = action == ViewerAction.IMG2IMG,
                            onClick = { act(action) },
                        )
                    }
                }
            }
        }
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            InfoSheet(current, pixels[current.id], params(current), onCopy = { act(ViewerAction.COPY_PARAMS) })
        }
    }
    when (confirm) {
        ViewerAction.SAVE -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Save an unencrypted copy?") },
            text = { Text("A plain image is written to Pictures/Autobot, where other apps and backups can read it.") },
            confirmButton = { TextButton(onClick = { confirm = null; onAction(ViewerAction.SAVE, current) }) { Text("SAVE") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("CANCEL") } },
        )
        ViewerAction.DELETE -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete image?") },
            text = { Text("The encrypted file and its parameters are removed. This cannot be undone.") },
            confirmButton = { TextButton(onClick = { confirm = null; onAction(ViewerAction.DELETE, current) }) { Text("DELETE", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("CANCEL") } },
        )
        else -> Unit
    }
}

/** How [item] was made: prompt, negative, the file's real pixels and every setting. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InfoSheet(item: GalleryItem, pixels: Pair<Int, Int>?, params: DiffusionRequest?, onCopy: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("image info", Modifier.weight(1f))
            Tag("copy params", accent = MaterialTheme.colorScheme.primary, onClick = onCopy)
        }
        MicroLabel("prompt")
        Text(item.prompt.ifBlank { "—" }, style = AutobotTheme.styles.code)
        if (item.negativePrompt.isNotBlank()) {
            MicroLabel("negative")
            Text(item.negativePrompt, style = AutobotTheme.styles.codeBlock, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Hairline()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val (w, h) = pixels ?: (item.width to item.height)
            Readout("Pixels", "${w}×$h")
            Readout("Seed", item.seed.toString(), valueColor = MaterialTheme.colorScheme.primary)
            Readout("Steps", item.steps.toString())
            Readout("CFG", String.format(Locale.ROOT, "%.1f", item.cfgScale))
            Readout("Sampler", item.sampler.ifBlank { "default" })
            Readout("Mode", item.mode.lowercase(Locale.ROOT))
            if (params != null && params.mode != DiffusionMode.TXT2IMG) Readout("Denoise", String.format(Locale.ROOT, "%.2f", params.denoiseStrength))
            params?.loras?.takeIf { it.isNotEmpty() }?.let { loras -> Readout("Loras", loras.joinToString { "${it.name} ${it.weight}" }) }
            Readout("Model", item.model ?: "backend default")
            Readout("Engine", item.engine)
            Readout("Time", String.format(Locale.ROOT, "%.1f s", item.durationMs / 1000f))
            Readout("Made", DateUtils.getRelativeTimeSpanString(item.createdAt).toString())
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * One page: the image fitted to the screen. Two fingers zoom about their centre and pan; a
 * double tap zooms to [DOUBLE_TAP_ZOOM] at the tap (or back out); unzoomed, a vertical drag is
 * reported through [onDrag] (swipe to dismiss) and a sideways one is left to the pager.
 */
@Composable
private fun ZoomableImage(
    image: ViewerImage?,
    description: String,
    active: Boolean,
    onZoom: (Boolean) -> Unit,
    onTap: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    offsetY: Float,
) {
    var scale by remember(image) { mutableFloatStateOf(1f) }
    var offset by remember(image) { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(active) {
        // A page swiped away is shown fitted again when it comes back.
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val box = with(LocalDensity.current) { androidx.compose.ui.geometry.Size(maxWidth.toPx(), maxHeight.toPx()) }
        if (image == null) {
            InkLoaderBlock("decrypting")
            return@BoxWithConstraints
        }
        val fitted = fittedSize(box, image.bitmap.width.toFloat(), image.bitmap.height.toFloat())
        fun clamp(o: Offset, s: Float): Offset {
            val maxX = max(0f, (fitted.width * s - box.width) / 2f)
            val maxY = max(0f, (fitted.height * s - box.height) / 2f)
            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
        }
        fun zoomTo(target: Float, focus: Offset) {
            val startScale = scale
            val startOffset = offset
            val c = focus - Offset(box.width / 2f, box.height / 2f)
            val endOffset = clamp(c - (c - startOffset) * (target / startScale), target)
            scope.launch {
                animate(0f, 1f) { t, _ ->
                    scale = startScale + (target - startScale) * t
                    offset = startOffset + (endOffset - startOffset) * t
                }
                onZoom(target > 1.01f)
            }
        }
        Image(
            image.bitmap,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(image) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { tap -> zoomTo(if (scale > 1.05f) 1f else DOUBLE_TAP_ZOOM, tap) },
                    )
                }
                .pointerInput(image) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var travel = Offset.Zero
                        var vertical: Boolean? = null
                        var dragY = 0f
                        do {
                            val event = awaitPointerEvent()
                            val fingers = event.changes.count { it.pressed }
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (fingers > 1 || scale > 1.01f) {
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                val c = if (centroid == Offset.Unspecified) Offset.Zero else centroid - Offset(box.width / 2f, box.height / 2f)
                                offset = clamp(c - (c - offset) * (newScale / scale) + pan, newScale)
                                scale = newScale
                                onZoom(scale > 1.01f)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            } else if (vertical == null) {
                                travel += pan
                                if (travel.getDistance() > viewConfiguration.touchSlop) vertical = abs(travel.y) > abs(travel.x) * 1.2f
                                if (vertical == true) event.changes.forEach { it.consume() }
                            } else if (vertical == true) {
                                dragY += pan.y
                                onDrag(dragY)
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (vertical == true && scale <= 1.01f) onDragEnd()
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y + offsetY
                },
        )
    }
}

/** [w]×[h] fitted inside [box] (ContentScale.Fit). */
internal fun fittedSize(box: androidx.compose.ui.geometry.Size, w: Float, h: Float): androidx.compose.ui.geometry.Size {
    if (w <= 0f || h <= 0f || box.width <= 0f || box.height <= 0f) return box
    val s = minOf(box.width / w, box.height / h)
    return androidx.compose.ui.geometry.Size(w * s, h * s)
}

private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 8f
private val DISMISS_DISTANCE = 120.dp

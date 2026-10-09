package dev.wckdboy.autobot.feature.imagine.mask

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.wckdboy.autobot.core.designsystem.component.Hairline
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.ValueSlider
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import kotlin.math.min

/**
 * Full-screen inpaint mask editor.
 *
 * One finger paints (or erases); a second finger cancels the stroke in progress and switches to
 * pinch-zoom / pan for the rest of that gesture. Strokes are kept in image-normalized
 * coordinates so the overlay matches the exported mask exactly.
 */
@Composable
fun MaskEditorDialog(
    image: ImageBitmap,
    mask: MaskState,
    onStroke: (MaskStroke) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onInvert: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var erase by rememberSaveable { mutableStateOf(false) }
        var brushDp by rememberSaveable { mutableFloatStateOf(28f) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val live = remember { mutableStateListOf<Offset>() }
        val density = LocalDensity.current

        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                MicroLabel("// Mask", Modifier.padding(start = 8.dp).weight(1f), color = MaterialTheme.colorScheme.primary)
                MicroLabel(
                    if (mask.inverted) "inverted · ${mask.strokes.size} strokes" else "${mask.strokes.size} strokes",
                    Modifier.padding(end = 8.dp),
                )
                TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("FIT") }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.primary) }
            }
            Hairline()

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val fit = min(boxW / image.width, boxH / image.height)
                val drawW = image.width * fit
                val drawH = image.height * fit
                val brushPx = with(density) { brushDp.dp.toPx() }

                // Maps a pointer position in the box to normalized image coordinates.
                fun toImage(p: Offset): Pair<Float, Float> {
                    val cx = boxW / 2 + offset.x
                    val cy = boxH / 2 + offset.y
                    val x = ((p.x - cx) / scale + drawW / 2) / drawW
                    val y = ((p.y - cy) / scale + drawH / 2) / drawH
                    return x to y
                }

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(image, erase, brushPx) {
                            awaitEachGesture {
                                val first = awaitFirstDown()
                                live.clear()
                                live += first.position
                                var transforming = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size > 1) {
                                        transforming = true
                                        live.clear()
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val centroid = event.calculateCentroid()
                                        val newScale = (scale * zoom).coerceIn(1f, 8f)
                                        val center = Offset(boxW / 2, boxH / 2)
                                        offset = (offset + pan) + (centroid - center - offset) * (1 - newScale / scale)
                                        scale = newScale
                                        event.changes.forEach { it.consume() }
                                    } else if (!transforming) {
                                        val change = pressed.first()
                                        if (change.positionChange() != Offset.Zero) live += change.position
                                        change.consume()
                                    }
                                }
                                if (!transforming && live.isNotEmpty()) {
                                    onStroke(
                                        MaskStroke(
                                            points = live.map(::toImage),
                                            radius = brushPx / 2 / (drawW * scale),
                                            erase = erase,
                                        ),
                                    )
                                }
                                live.clear()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(with(density) { drawW.toDp() }, with(density) { drawH.toDp() })
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            },
                    ) {
                        Image(image, contentDescription = "Init image", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        MaskOverlay(mask, Modifier.fillMaxSize())
                    }
                    // In-progress stroke, drawn in screen space.
                    Canvas(Modifier.fillMaxSize()) {
                        if (live.size > 0) {
                            val path = Path().apply {
                                moveTo(live.first().x, live.first().y)
                                live.drop(1).forEach { lineTo(it.x, it.y) }
                                if (live.size == 1) lineTo(live.first().x + 0.1f, live.first().y)
                            }
                            drawPath(
                                path,
                                color = if (erase) Color.White.copy(alpha = 0.5f) else AutobotColors.Acid.copy(alpha = 0.6f),
                                style = Stroke(width = brushPx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
            }

            Hairline()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueSlider("Brush", brushDp, { brushDp = it }, 6f..90f, format = { "${it.toInt()} dp" })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconToggleButton(checked = !erase, onCheckedChange = { erase = false }) {
                        Icon(AutobotIcons.Brush, "Brush", tint = if (!erase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconToggleButton(checked = erase, onCheckedChange = { erase = true }) {
                        Icon(AutobotIcons.Eraser, "Eraser", tint = if (erase) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = onUndo, enabled = mask.strokes.isNotEmpty()) { Icon(AutobotIcons.Undo, "Undo") }
                    IconButton(onClick = onRedo, enabled = mask.redo.isNotEmpty()) {
                        Icon(AutobotIcons.Undo, "Redo", modifier = Modifier.graphicsLayer { scaleX = -1f })
                    }
                    IconButton(onClick = onInvert) {
                        Icon(AutobotIcons.Invert, "Invert", tint = if (mask.inverted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClear, enabled = mask.strokes.isNotEmpty() || mask.inverted) {
                        Text("CLEAR", color = MaterialTheme.colorScheme.error)
                    }
                }
                MicroLabel("1 finger paint · 2 fingers zoom/pan · painted = repainted")
            }
        }
    }
}

/** Renders [mask] over its image (acid tint), honoring erase strokes and invert. */
@Composable
fun MaskOverlay(mask: MaskState, modifier: Modifier = Modifier) {
    val tint = AutobotColors.Acid.copy(alpha = 0.5f)
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        if (mask.inverted) drawRect(tint)
        for (stroke in mask.strokes) {
            val path = Path()
            stroke.points.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(x * size.width, y * size.height) else path.lineTo(x * size.width, y * size.height)
            }
            if (stroke.points.size == 1) {
                val (x, y) = stroke.points.first()
                path.lineTo(x * size.width + 0.1f, y * size.height)
            }
            val removes = stroke.erase != mask.inverted
            drawPath(
                path,
                color = if (removes) Color.Black else tint,
                style = Stroke(width = stroke.radius * size.width * 2, cap = StrokeCap.Round, join = StrokeJoin.Round),
                blendMode = if (removes) BlendMode.Clear else BlendMode.Src,
            )
        }
    }
}

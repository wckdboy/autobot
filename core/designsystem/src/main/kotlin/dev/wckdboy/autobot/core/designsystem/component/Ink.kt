package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock
import dev.wckdboy.autobot.core.designsystem.theme.Motion

// Work in progress, drawn flat (BRAND.md: no halftones, no textures): a hairline bar, a plain
// preview, an image that fades in. Animated values are read only while drawing.

/**
 * Where an [srcWidth]×[srcHeight] image lands in a [dstWidth]×[dstHeight] box under [scale],
 * centred (as [Image] draws it). A plate sampling the image must use this, not the whole box:
 * with Crop or Fit into a box of another shape, dots sampled over the box show the picture
 * stretched, off register with the photo fading in under them.
 */
internal fun contentRect(srcWidth: Float, srcHeight: Float, dstWidth: Float, dstHeight: Float, scale: ContentScale): Rect {
    if (srcWidth <= 0f || srcHeight <= 0f || dstWidth <= 0f || dstHeight <= 0f) return Rect(0f, 0f, dstWidth, dstHeight)
    val f = scale.computeScaleFactor(Size(srcWidth, srcHeight), Size(dstWidth, dstHeight))
    val w = srcWidth * f.scaleX
    val h = srcHeight * f.scaleY
    return Rect((dstWidth - w) / 2f, (dstHeight - h) / 2f, (dstWidth + w) / 2f, (dstHeight + h) / 2f)
}

/**
 * A picture being made: a flat surfaceContainer panel, with the engine's latest [preview] (when
 * it sends one) shown plainly over it. Progress is the job's [InkProgressBar], not this panel.
 */
@Composable
fun DevelopingPreview(
    preview: ImageBitmap?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (preview != null) Image(preview, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A finished picture arriving: when [animate] (a live arrival) it fades in over `printMillis`;
 * otherwise (and with reduced motion, over `reducedMotionMillis`) it is simply shown.
 * [key] identifies the picture across re-decoded bitmaps (e.g. a gallery id) so it fades once.
 */
@Composable
fun PrintReveal(
    image: ImageBitmap,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    key: Any? = image,
) {
    val reduced = LocalInkMotion.current.reduced
    val fade = remember(key) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(key) {
        if (fade.value < 1f) fade.animateTo(1f, tween(if (reduced) Motion.reducedMotionMillis else Motion.printMillis / 2, easing = LinearEasing))
    }
    Image(
        image,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.graphicsLayer { alpha = fade.value },
    )
}

/** Paused bars are dimmed to this. */
private const val PAUSED_ALPHA = 0.45f

/** The indeterminate bar's moving segment, as a fraction of the bar. */
private const val SEGMENT = 0.3f

/** [InkProgressBar] states. [Idle]: static, full strength (nothing running yet / finished). */
enum class InkBarState { Running, Idle, Paused, Error }

/**
 * Progress as a flat bar: a [track] hairline the bar's height, filled from the start in [color]
 * (the running step's purple by default) up to [progress] (eased over `progressMillis`).
 * Indeterminate ([progress] null): a segment travels along the track, one pass per
 * `loaderCycleMillis` × 2, still with reduced motion. [InkBarState.Paused]: dimmed, with a "‖"
 * mark at the head. [InkBarState.Error]: the fill turns [errorColor] and holds.
 */
@Composable
fun InkProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    state: InkBarState = InkBarState.Running,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.outlineVariant,
    errorColor: Color = MaterialTheme.colorScheme.error,
) {
    val reduced = LocalInkMotion.current.reduced
    val error = state == InkBarState.Error
    val fill = animateFloatAsState(
        (progress ?: if (error) 1f else 0f).coerceIn(0f, 1f),
        tween(if (reduced) Motion.reducedMotionMillis else Motion.progressMillis),
        label = "inkProgress",
    )
    val indeterminate = progress == null && !error
    val clock = rememberInkClock(indeterminate && state == InkBarState.Running)
    Spacer(
        modifier.drawBehind {
            val alpha = if (state == InkBarState.Paused) PAUSED_ALPHA else 1f
            val ink = if (error) errorColor else color
            drawRect(track, alpha = alpha)
            if (indeterminate) {
                val cycle = Motion.loaderCycleMillis * 2f
                val t = if (reduced) 0f else (clock.time * 1000f % cycle) / cycle
                val w = size.width * SEGMENT
                val x = -w + (size.width + w) * t
                drawRect(ink, Offset(x.coerceAtLeast(0f), 0f), Size((x + w).coerceAtMost(size.width) - x.coerceAtLeast(0f), size.height), alpha = alpha)
                return@drawBehind
            }
            val head = size.width * fill.value
            if (head > 0f) drawRect(ink, Offset.Zero, Size(head, size.height), alpha = alpha)
            if (state == InkBarState.Paused) {
                // "‖": two hairlines just past the head.
                val x = minOf(head + 3.dp.toPx(), size.width - 4.dp.toPx())
                val markH = maxOf(size.height, 8.dp.toPx())
                val top = (size.height - markH) / 2f
                val bar = 1.dp.toPx()
                drawRect(color, Offset(x, top), Size(bar, markH))
                drawRect(color, Offset(x + bar * 2.5f, top), Size(bar, markH))
            }
        },
    )
}

package dev.wckdboy.autobot.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.motion.FlowPhase
import dev.wckdboy.autobot.core.designsystem.motion.InkClock
import dev.wckdboy.autobot.core.designsystem.motion.InkMath
import dev.wckdboy.autobot.core.designsystem.motion.InkTween
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// Procedural "ink" pieces (see the motion spec). Each canvas builds its dots into ONE reused
// Path per colour per frame, reads the clock only while drawing (a tick redraws, never
// recomposes) and stops ticking when its work is done.

/** Dots for one frame, added to a reused platform path (no per-dot allocations). */
internal class DotBatch {
    private val platform = android.graphics.Path()
    val path: Path = platform.asComposePath()

    fun reset() = platform.rewind()

    fun dot(x: Float, y: Float, radius: Float) {
        if (radius > 0.3f) platform.addCircle(x, y, radius, android.graphics.Path.Direction.CW)
    }
}

/** Downsampled luminance (0 dark … 1 light) of an image, sampled at normalised coordinates. */
internal class LumaGrid(private val w: Int, private val h: Int, private val values: FloatArray) {
    fun sample(x: Float, y: Float): Float {
        val ix = (x * w).toInt().coerceIn(0, w - 1)
        val iy = (y * h).toInt().coerceIn(0, h - 1)
        return values[iy * w + ix]
    }
}

private const val LUMA_LONG_SIDE = 96
private val lumaCache = WeakHashMap<ImageBitmap, LumaGrid>()

internal fun luminanceGrid(bitmap: Bitmap, longSide: Int = LUMA_LONG_SIDE): LumaGrid {
    val scale = longSide.toFloat() / max(bitmap.width, bitmap.height)
    val w = max(1, (bitmap.width * scale).toInt())
    val h = max(1, (bitmap.height * scale).toInt())
    val source = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
    val small = Bitmap.createScaledBitmap(source, w, h, true)
    val px = IntArray(w * h)
    small.getPixels(px, 0, w, 0, 0, w, h)
    val values = FloatArray(px.size) { i ->
        val p = px[i]
        ((p shr 16 and 0xFF) * 0.299f + (p shr 8 and 0xFF) * 0.587f + (p and 0xFF) * 0.114f) / 255f
    }
    return LumaGrid(w, h, values)
}

/** The image's luma grid, computed once per bitmap off the main thread (null until ready). */
@Composable
internal fun rememberLumaGrid(image: ImageBitmap?): State<LumaGrid?> =
    produceState(image?.let { synchronized(lumaCache) { lumaCache[it] } }, image) {
        if (image == null) {
            value = null
            return@produceState
        }
        synchronized(lumaCache) { lumaCache[image] }?.let {
            value = it
            return@produceState
        }
        val grid = withContext(Dispatchers.Default) { luminanceGrid(image.asAndroidBitmap()) }
        synchronized(lumaCache) { lumaCache[image] = grid }
        value = grid
    }

/** Clock time without subscribing the caller (for effects and composition-time bookkeeping). */
private val InkClock.now: Float get() = Snapshot.withoutReadObservation { time }

/**
 * Develop: a proof plate that flows while sampling runs ([flowing]) with its fill rising with
 * [progress], then washes the [preview]'s luma in along the flow diagonal when the first one
 * arrives, and morphs dot radii from each preview to the next. Slower and lighter while
 * [loading]. The field part is drawn at [fieldAlpha] and darkens to full ink as the image develops.
 */
@Composable
fun DevelopingHalftone(
    preview: ImageBitmap?,
    modifier: Modifier = Modifier,
    dot: Color = MaterialTheme.colorScheme.primary,
    cell: Dp = 5.dp,
    flowing: Boolean = true,
    progress: Float = 0f,
    loading: Boolean = false,
    fieldAlpha: Float = 0.55f,
    seed: Float = 0f,
) {
    val reduced = LocalInkMotion.current.reduced
    val grid by rememberLumaGrid(preview)
    var current by remember { mutableStateOf<LumaGrid?>(null) }
    var previous by remember { mutableStateOf<LumaGrid?>(null) }
    var start by remember { mutableFloatStateOf(0f) }
    var developing by remember { mutableStateOf(false) }
    val blend = remember { Animatable(1f) } // reduced motion: a short radius crossfade instead
    val clock = rememberInkClock(flowing || developing)
    val flow = remember { FlowPhase(seed) }
    val fill = remember { InkTween(InkMath.flowFill(progress, loading), Motion.developMillis / 1000f) }
    val targetFill = InkMath.flowFill(progress, loading)
    SideEffect { if (flowing && !reduced) fill.retarget(targetFill, clock.now) else fill.snap(targetFill) }

    LaunchedEffect(grid, preview == null) {
        developing = false
        val next = grid.takeIf { preview != null }
        if (next === current) return@LaunchedEffect
        previous = current?.takeIf { next != null }
        current = next
        if (next == null) return@LaunchedEffect
        if (reduced) {
            blend.snapTo(0f)
            blend.animateTo(1f, tween(Motion.reducedMotionMillis, easing = LinearEasing))
        } else {
            start = clock.now
            developing = true
            snapshotFlow { clock.time }.first { (it - start) * 1000f >= Motion.developMillis }
            developing = false
        }
    }

    val batch = remember { DotBatch() }
    Canvas(modifier.clipToBounds()) {
        val t = clock.time
        val cur = current
        val prev = previous
        val speed = when {
            cur != null -> InkMath.SHIMMER_SPEED
            loading -> InkMath.LOADING_SPEED
            else -> 1f
        }
        val phase = flow.at(t, speed)
        val dev = if (reduced) blend.value else InkMath.clamp01((t - start) * 1000f / Motion.developMillis)
        val f = fill.value(t)
        val c = InkMath.cappedCell(size.width, size.height, cell.toPx())
        val cols = max(1, (size.width / c).toInt())
        val rows = max(1, (size.height / c).toInt())
        val cw = size.width / cols
        val ch = size.height / rows
        val shimmer = cur != null && flowing && !reduced
        batch.reset()
        for (r in 0 until rows) for (col in 0 until cols) {
            val x = (col + 0.5f) / cols
            val y = (r + 0.5f) / rows
            var v = when {
                cur == null -> InkMath.field(x, y, phase) * f
                dev >= 1f -> 1f - cur.sample(x, y)
                prev != null -> InkMath.lerp(1f - prev.sample(x, y), 1f - cur.sample(x, y), InkMath.easeOutCubic(dev))
                else -> {
                    val local = if (reduced) dev else InkMath.staggered(dev, x, y)
                    InkMath.lerp(InkMath.field(x, y, phase) * f, 1f - cur.sample(x, y), InkMath.easeOutCubic(local))
                }
            }
            if (shimmer) v *= 1f - InkMath.SHIMMER_DEPTH * InkMath.wave(x, y, phase)
            batch.dot((col + 0.5f) * cw, (r + 0.5f) * ch, v * c * 0.62f)
        }
        val alpha = when {
            cur == null -> fieldAlpha
            prev == null -> InkMath.lerp(fieldAlpha, 1f, InkMath.easeOutCubic(dev))
            else -> 1f
        }
        drawPath(batch.path, dot, alpha = alpha)
    }
}

/**
 * Print: when [animate] (a live arrival), the image's halftone dots swell into solid ink while a
 * [plate]-colour plate settles into register and the full-colour image fades in underneath.
 * Ends as the plain image with nothing drawn over it. Without [animate] it is just the image.
 * [key] identifies the picture across re-decoded bitmaps (e.g. a gallery id) so it prints once.
 */
@Composable
fun PrintReveal(
    image: ImageBitmap,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    contentDescription: String? = null,
    contentScale: ContentScale = ContentScale.Crop,
    dot: Color = MaterialTheme.colorScheme.primary,
    plate: Color = MaterialTheme.colorScheme.primary,
    cell: Dp = 5.dp,
    key: Any? = image,
) {
    val reduced = LocalInkMotion.current.reduced
    var done by remember(key) { mutableStateOf(!animate) }
    val print = animate && !reduced && !done
    val grid by rememberLumaGrid(if (print) image else null)
    var start by remember(key) { mutableFloatStateOf(Float.NaN) }
    val fade = remember(key) { Animatable(if (animate) 0f else 1f) }
    val clock = rememberInkClock(print && grid != null)

    LaunchedEffect(key, grid != null) {
        if (done) return@LaunchedEffect
        if (reduced) {
            fade.animateTo(1f, tween(Motion.reducedMotionMillis, easing = LinearEasing))
            done = true
            return@LaunchedEffect
        }
        if (grid == null) return@LaunchedEffect
        val s = clock.now
        start = s
        snapshotFlow { clock.time }.first { (it - s) * 1000f >= Motion.printMillis }
        done = true
    }

    Box(modifier) {
        Image(
            image,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.matchParentSize().graphicsLayer {
                alpha = when {
                    done -> 1f
                    reduced -> fade.value
                    start.isNaN() -> 0f
                    else -> InkMath.printImageAlpha((clock.time - start) * 1000f)
                }
            },
        )
        val g = grid
        if (print && g != null) {
            val batch = remember { DotBatch() }
            Canvas(Modifier.matchParentSize().clipToBounds()) {
                val ms = if (start.isNaN()) 0f else (clock.time - start) * 1000f
                val merge = InkMath.printMerge(ms)
                val c = InkMath.cappedCell(size.width, size.height, cell.toPx())
                val cols = max(1, (size.width / c).toInt())
                val rows = max(1, (size.height / c).toInt())
                val cw = size.width / cols
                val ch = size.height / rows
                batch.reset()
                for (r in 0 until rows) for (col in 0 until cols) {
                    val v = 1f - g.sample((col + 0.5f) / cols, (r + 0.5f) / rows)
                    batch.dot((col + 0.5f) * cw, (r + 0.5f) * ch, v * c * 0.62f * merge)
                }
                val offset = Motion.registerOffset.dp.toPx() * InkMath.registerOffset(ms)
                translate(offset, offset) { drawPath(batch.path, plate, alpha = 0.5f * InkMath.plateAlpha(ms)) }
                drawPath(batch.path, dot, alpha = InkMath.printDotsAlpha(ms))
            }
        }
    }
}

/**
 * Thinking / composing indicator: a tiny halftone strip ([rows] × [cols] dots) rippling with the
 * flow phase while [active]; still dots otherwise (and with reduced motion).
 */
@Composable
fun HalftoneStrip(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    active: Boolean = true,
    cols: Int = 8,
    rows: Int = 3,
    cell: Dp = 4.dp,
) {
    val clock = rememberInkClock(active)
    val flow = remember { FlowPhase() }
    val batch = remember { DotBatch() }
    Canvas(modifier.size(cell * cols, cell * rows)) {
        val phase = flow.at(clock.time, STRIP_SPEED)
        val cw = size.width / cols
        val ch = size.height / rows
        val r0 = minOf(cw, ch) * 0.46f
        batch.reset()
        for (r in 0 until rows) for (col in 0 until cols) {
            val v = InkMath.wave(col / cols.toFloat(), r / rows.toFloat(), -phase)
            batch.dot((col + 0.5f) * cw, (r + 0.5f) * ch, r0 * InkMath.lerp(0.22f, 1f, v))
        }
        drawPath(batch.path, color)
    }
}

/** The strip ripples faster than the plate flow so it reads as alive at its tiny size. */
private const val STRIP_SPEED = 3f

/** Leading-edge flow on progress bars. */
private const val EDGE_SPEED = 4f
private const val EDGE_DOTS = 6

/** Dots trailing the indeterminate segment. */
private const val TRAIL_DOTS = 7

/** Paused bars are dimmed to this. */
private const val PAUSED_ALPHA = 0.45f

/** [InkProgressBar] states. [Idle]: static, full strength (nothing running yet / finished). */
enum class InkBarState { Running, Idle, Paused, Error }

/**
 * Progress as a thin printed rule. Determinate ([progress] 0…1): the width eases over
 * `progressMillis` (no jumps) and the filled part is a fine halftone that is densest at the
 * leading edge, with a small flowing dot cluster at the head while [state] is Running.
 * Indeterminate ([progress] null): a short ink segment sweeps with ease-in-out every
 * `indeterminateSweepMillis`, leaving a fading dot trail. [InkBarState.Paused]: static and dimmed
 * with a "‖" mark at the head. [InkBarState.Error]: fills in the error colour, then holds.
 * Animated values are read only while drawing; the clock runs only while Running.
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
    val width = animateFloatAsState(
        if (error) 1f else (progress ?: 0f).coerceIn(0f, 1f),
        tween(if (reduced) Motion.reducedMotionMillis else Motion.progressMillis),
        label = "inkProgress",
    )
    val running = state == InkBarState.Running
    val clock = rememberInkClock(running)
    val flow = remember { FlowPhase() }
    val batch = remember { DotBatch() }
    Canvas(modifier) {
        val h = size.height
        val alpha = if (state == InkBarState.Paused) PAUSED_ALPHA else 1f
        val ink = if (error) errorColor else color
        drawRect(track, alpha = alpha)
        if (progress == null && !error) {
            drawSweep(batch, ink, alpha, if (running && !reduced) clock.time * 1000f else Float.NaN)
        } else {
            val w = size.width * width.value
            drawHalftoneFill(batch, ink, alpha, w)
            if (running && !reduced && !error) {
                val phase = flow.at(clock.time, EDGE_SPEED)
                val spacing = h * 2.4f
                batch.reset()
                for (i in 0 until EDGE_DOTS) {
                    val x = w + spacing * (i + 0.6f)
                    if (x > size.width) break
                    val v = 0.5f + 0.5f * sin(Math.PI.toFloat() * (phase - i * 0.35f))
                    batch.dot(x, h / 2f, h * 0.75f * v * (1f - i / EDGE_DOTS.toFloat()))
                }
                drawPath(batch.path, ink)
            }
        }
        if (state == InkBarState.Paused) {
            // "‖": two hairlines just past the head, a little taller than a thin rule.
            val x = (if (progress == null) 0f else size.width * width.value) + 3.dp.toPx()
            val markH = max(h, 8.dp.toPx())
            val top = (h - markH) / 2f
            val bar = 1.dp.toPx()
            drawRect(color, Offset(x, top), Size(bar, markH))
            drawRect(color, Offset(x + bar * 2.5f, top), Size(bar, markH))
        }
    }
}

/** The determinate fill: rows of dots across [w] whose radius rises to solid ink at the head. */
private fun DrawScope.drawHalftoneFill(batch: DotBatch, ink: Color, alpha: Float, w: Float) {
    if (w <= 0f) return
    val h = size.height
    val rows = max(1, (h / 3.dp.toPx()).toInt())
    val cell = h / rows
    val cols = ceil(w / cell).toInt()
    batch.reset()
    for (r in 0 until rows) for (c in 0 until cols) {
        val x = (c + 0.5f) * cell
        if (x > w) break
        // 0.72 × cell covers the cell, so the head reads as a solid rule.
        batch.dot(x, (r + 0.5f) * cell, cell * 0.72f * InkMath.barDensity(x / w))
    }
    drawPath(batch.path, ink, alpha = alpha)
}

/** The indeterminate segment and its fading trail at [millis] (NaN: still, reduced motion). */
private fun DrawScope.drawSweep(batch: DotBatch, ink: Color, alpha: Float, millis: Float) {
    val w = size.width
    val h = size.height
    if (millis.isNaN()) {
        // Static: an even stipple along the track.
        batch.reset()
        var x = h
        while (x < w) {
            batch.dot(x, h / 2f, h * 0.4f)
            x += h * 2.4f
        }
        drawPath(batch.path, ink, alpha = alpha)
        return
    }
    val head = InkMath.sweepHead(millis) * w
    val tail = head - InkMath.SWEEP_SEGMENT * w
    val left = max(0f, tail)
    val right = minOf(w, head)
    if (right > left) drawRect(ink, Offset(left, 0f), Size(right - left, h), alpha = alpha)
    batch.reset()
    val spacing = h * 2.2f
    for (i in 0 until TRAIL_DOTS) {
        val x = tail - spacing * (i + 0.7f)
        if (x < 0f) break
        if (x > w) continue
        batch.dot(x, h / 2f, h * 0.6f * InkMath.sweepTrail(i, TRAIL_DOTS))
    }
    drawPath(batch.path, ink, alpha = alpha)
}

package dev.wckdboy.autobot.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.motion.DotGeometry
import dev.wckdboy.autobot.core.designsystem.motion.FlowPhase
import dev.wckdboy.autobot.core.designsystem.motion.InkClock
import dev.wckdboy.autobot.core.designsystem.motion.InkMath
import dev.wckdboy.autobot.core.designsystem.motion.InkTween
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import java.util.WeakHashMap
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

// Procedural "ink" pieces (see the motion spec). Each canvas collects its dots into ONE reused
// batch per colour per frame, reads the clock only while drawing (a tick redraws, never
// recomposes) and stops ticking when its work is done.

/**
 * Dots for one frame (reused arrays: no per-dot allocations). Drawn as analytic circles, which
 * the renderer batches, not as one many-contour path: a path of thousands of circles is
 * re-rasterised on the render thread every frame it changes or moves (it cost 7–36 ms a frame
 * on the paper studio of a Snapdragon 8 Elite Gen 5). Overlapping dots still merge into one
 * ink: translucent batches draw opaque circles into a single alpha layer, as the path's union did.
 */
internal class DotBatch {
    private var xs = FloatArray(INITIAL)
    private var ys = FloatArray(INITIAL)
    private var rs = FloatArray(INITIAL)
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    /** Dots in this frame. */
    var count = 0
        private set

    fun reset() {
        count = 0
    }

    fun dot(x: Float, y: Float, radius: Float) {
        if (radius <= 0.3f) return
        if (count == xs.size) {
            xs = xs.copyOf(count * 2)
            ys = ys.copyOf(count * 2)
            rs = rs.copyOf(count * 2)
        }
        xs[count] = x
        ys[count] = y
        rs[count] = radius
        count++
    }

    /** Draws the batch in [color] at [alpha] (with the colour's own alpha) on [scope]. */
    fun draw(scope: DrawScope, color: Color, alpha: Float = 1f) {
        val a = (alpha * color.alpha).coerceIn(0f, 1f)
        if (count == 0 || a <= 0f) return
        val canvas = scope.drawContext.canvas.nativeCanvas
        paint.color = color.copy(alpha = 1f).toArgb()
        val layer = a < 1f
        if (layer) canvas.saveLayerAlpha(null, (a * 255f + 0.5f).toInt())
        for (i in 0 until count) canvas.drawCircle(xs[i], ys[i], rs[i], paint)
        if (layer) canvas.restore()
    }

    private val platform = android.graphics.Path()
    private val composePath: Path = platform.asComposePath()

    /** The dots as one path, built on demand: only for clipping (a dissolve), never for drawing. */
    val path: Path
        get() {
            platform.rewind()
            for (i in 0 until count) platform.addCircle(xs[i], ys[i], rs[i], android.graphics.Path.Direction.CW)
            return composePath
        }

    private companion object {
        const val INITIAL = 256
    }
}

/**
 * One dot drawn as a circle. For dots that never overlap (bars, loaders, cards) this beats a
 * path of circles: the renderer draws circles analytically and batches them, while a changing
 * many-contour path is re-rasterised every frame. No allocation (Offset is a value class).
 */
internal fun DrawScope.inkDot(color: Color, x: Float, y: Float, radius: Float, alpha: Float = 1f) {
    if (radius > 0.3f) drawCircle(color, radius, Offset(x, y), alpha)
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
        batch.draw(this, dot, alpha)
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
                translate(offset, offset) { batch.draw(this, plate, 0.5f * InkMath.plateAlpha(ms)) }
                batch.draw(this, dot, InkMath.printDotsAlpha(ms))
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
    Canvas(modifier.size(cell * cols, cell * rows)) {
        val phase = flow.at(clock.time, STRIP_SPEED)
        val cw = size.width / cols
        val ch = size.height / rows
        val r0 = minOf(cw, ch) * 0.46f
        for (r in 0 until rows) for (col in 0 until cols) {
            val v = InkMath.wave(col / cols.toFloat(), r / rows.toFloat(), -phase)
            inkDot(color, (col + 0.5f) * cw, (r + 0.5f) * ch, r0 * InkMath.lerp(0.22f, 1f, v))
        }
    }
}

/** The strip ripples faster than the plate flow so it reads as alive at its tiny size. */
private const val STRIP_SPEED = 3f

/** Paused bars are dimmed to this. */
private const val PAUSED_ALPHA = 0.45f

/** Bars thinner than this still draw dots this big (centred, overflowing the bar a little). */
private val BAR_MIN_DOT = DotGeometry.BAR_MIN_DOT_DP.dp

/** [InkProgressBar] states. [Idle]: static, full strength (nothing running yet / finished). */
enum class InkBarState { Running, Idle, Paused, Error }

/** A bar's dot slots for one size ([DotGeometry.bar]), rebuilt only when the size changes. */
private class BarSlots {
    var width = -1f
    var height = -1f
    var geometry = DotGeometry.bar(0f, 0f, 0f)
    val count: Int get() = geometry.slots.count
    var xs = FloatArray(0)

    /** Every slot's pin as x,y pairs, drawn in one round-capped points call (filled dots cover theirs). */
    var pins = FloatArray(0)
    val pinPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND }

    fun update(w: Float, h: Float, minDot: Float) {
        if (w == width && h == height) return
        width = w
        height = h
        geometry = DotGeometry.bar(w, h, minDot)
        xs = FloatArray(count) { geometry.slots.at(it) }
        pins = FloatArray(count * 2) { if (it % 2 == 0) xs[it / 2] else geometry.cy }
    }
}

/**
 * Progress as a row of dots (spec v3 §1). Empty slots are faint pins in [track]. Determinate
 * ([progress] 0…1): slots up to the progress fill with dots that grow from `barFillStartScale`
 * at the start to full at the head, the head dots easing in as progress passes them (progress
 * eases over `progressMillis`); full is a row of full circles; while Running the filled dots
 * breathe ±`barRunningWaveAmplitude` with the wave. Indeterminate ([progress] null): every slot
 * swells with a travelling wave. [InkBarState.Paused]: static, dimmed, with a "‖" mark.
 * [InkBarState.Error]: the filled dots turn [errorColor] and hold.
 * Slot centres are cached; each frame only radii change (drawn as circles, never a path).
 * Animated values are read only while
 * drawing, on the shared clock, which runs only while Running.
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
    val running = state == InkBarState.Running
    val clock = rememberInkClock(running)
    val slots = remember { BarSlots() }
    val indeterminate = progress == null && !error
    Spacer(
        modifier.drawBehind {
            slots.update(size.width, size.height, BAR_MIN_DOT.toPx())
            val n = slots.count
            if (n == 0) return@drawBehind
            val bar = slots.geometry
            val cy = bar.cy
            val alpha = if (state == InkBarState.Paused) PAUSED_ALPHA else 1f
            val ink = if (error) errorColor else color
            val ms = clock.time * 1000f
            var head = 0f
            if (indeterminate) {
                for (i in 0 until n) inkDot(ink, slots.xs[i], cy, DotGeometry.barWaveRadius(bar, i, ms), alpha)
            } else {
                val p = fill.value
                val breathe = running && !reduced
                slots.pinPaint.color = track.copy(alpha = track.alpha * alpha).toArgb()
                slots.pinPaint.strokeWidth = bar.pin * 2f
                drawContext.canvas.nativeCanvas.drawPoints(slots.pins, slots.pinPaint)
                for (i in 0 until n) {
                    val r = DotGeometry.barRadius(bar, i, p, if (breathe) InkMath.dotWave(i.toFloat(), ms) else null)
                    if (r > 0f) {
                        inkDot(ink, slots.xs[i], cy, r, alpha)
                        head = slots.xs[i] + r
                    }
                }
            }
            if (state == InkBarState.Paused) {
                // "‖": two hairlines just past the head.
                val x = minOf(head + 3.dp.toPx(), size.width - 4.dp.toPx())
                val markH = max(size.height, 8.dp.toPx())
                val top = (size.height - markH) / 2f
                val bar = 1.dp.toPx()
                drawRect(color, Offset(x, top), Size(bar, markH))
                drawRect(color, Offset(x + bar * 2.5f, top), Size(bar, markH))
            }
        },
    )
}

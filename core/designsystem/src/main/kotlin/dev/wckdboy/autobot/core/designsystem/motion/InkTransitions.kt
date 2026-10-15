package dev.wckdboy.autobot.core.designsystem.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.component.DotBatch
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Screen, sheet and list transitions in the ink language (motion spec v2 §3).

/**
 * Screen-to-screen: the incoming screen fades in over `transitionMillis` (its red plate is drawn
 * by [misregisterEnter]); the outgoing one fades out faster. With reduced motion, a
 * `reducedMotionMillis` crossfade. Used for pushes and non-gesture pops; predictive back keeps
 * the platform's own gesture-driven spec.
 */
fun <T> inkScreenTransition(reduced: Boolean): AnimatedContentTransitionScope<T>.() -> ContentTransform = {
    if (reduced) {
        fadeIn(tween(Motion.reducedMotionMillis)) togetherWith fadeOut(tween(Motion.reducedMotionMillis))
    } else {
        fadeIn(tween(Motion.transitionMillis, easing = LinearEasing)) togetherWith
            fadeOut(tween(Motion.transitionMillis * 2 / 3, easing = LinearEasing))
    }
}

/**
 * The "misregistration push": when [animate] (first composition of a pushed screen), an ink
 * plate of the content — its text and marks in [plate] colour, the paper left clear — is drawn
 * `registerOffset` down-right and settles into register while fading, over `transitionMillis`.
 * One offscreen layer for those 240 ms, nothing after. Nothing with reduced motion.
 * [darkTheme] picks which pixels count as ink (light on dark, dark on light/paper).
 */
@Composable
fun Modifier.misregisterEnter(
    animate: Boolean,
    plate: Color = MaterialTheme.colorScheme.primary,
    darkTheme: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
): Modifier {
    val reduced = LocalInkMotion.current.reduced
    val progress = remember { Animatable(if (animate && !reduced) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, tween(Motion.transitionMillis, easing = LinearEasing))
    }
    return misregisterPlate({ progress.value }, plate, darkTheme)
}

/** The misregistration plate at [progress] (0…1 of the push; read only while drawing). */
internal fun Modifier.misregisterPlate(progress: () -> Float, plate: Color, darkTheme: Boolean): Modifier = drawWithCache {
    val paint = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(ColorMatrix(InkMath.inkPlateMatrix(plate.red, plate.green, plate.blue, darkTheme)))
    }
    val offset = Motion.registerOffset.dp.toPx()
    val bounds = Rect(Offset.Zero, size)
    onDrawWithContent {
        drawContent()
        val t = progress()
        if (t < 1f) {
            val d = offset * InkMath.misregisterOffset(t)
            paint.alpha = InkMath.misregisterAlpha(t)
            drawIntoCanvas { canvas ->
                canvas.saveLayer(bounds, paint)
                canvas.translate(d, d)
                drawContent()
                canvas.restore()
            }
        }
    }
}

/** Rows are given this long to leave the list after their delete before they show again (e.g. it failed). */
private const val DISSOLVE_RESTORE_MILLIS = 2_000L

/** Cell of the dissolve's dot mask. */
private val DISSOLVE_CELL = 6.dp

/**
 * List deletion: [dissolve] a row's key, and its row (wearing [inkDissolve]) shrinks into
 * halftone dots and collapses over `dissolveMillis`; then the real delete runs. With reduced
 * motion the delete runs at once.
 */
@Stable
class InkDissolveState internal constructor(private val scope: CoroutineScope, private val reduced: () -> Boolean) {
    private val dissolving = mutableStateMapOf<Any, Boolean>()

    fun isDissolving(key: Any): Boolean = dissolving[key] == true

    fun dissolve(key: Any, then: () -> Unit) {
        if (reduced()) {
            then()
            return
        }
        if (isDissolving(key)) return
        dissolving[key] = true
        scope.launch {
            delay(Motion.dissolveMillis.toLong())
            then()
            delay(DISSOLVE_RESTORE_MILLIS)
            dissolving.remove(key)
        }
    }
}

@Composable
fun rememberInkDissolve(): InkDissolveState {
    val scope = rememberCoroutineScope()
    val motion = LocalInkMotion.current
    val reduced = remember { arrayOf(motion.reduced) }
    reduced[0] = motion.reduced
    return remember(scope) { InkDissolveState(scope) { reduced[0] } }
}

/**
 * The row half of [InkDissolveState]: while its [key] dissolves, the row is masked by a dot grid
 * whose dots shrink to nothing (lightly staggered along the diagonal) and its height collapses.
 * Progress is read only in layout and draw; untouched rows pay nothing.
 */
@Composable
fun Modifier.inkDissolve(state: InkDissolveState, key: Any): Modifier {
    val active = state.isDissolving(key)
    val progress = remember(key) { Animatable(0f) }
    val batch = remember { DotBatch() }
    LaunchedEffect(active) {
        if (active) progress.animateTo(1f, tween(Motion.dissolveMillis, easing = LinearEasing)) else progress.snapTo(0f)
    }
    if (!active) return this
    return dissolveAt(batch) { progress.value }
}

/**
 * The dissolve at [progress] (0…1 of `dissolveMillis`; read only in layout and draw): the row is
 * drawn clipped to one path of dots that shrink to nothing (no offscreen layer), and its height
 * collapses. The bounds clip sits outside the collapsing layout so the row is cut to its height.
 */
internal fun Modifier.dissolveAt(batch: DotBatch, progress: () -> Float): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val h = (p.height * InkMath.dissolveHeight(progress())).roundToInt()
        layout(p.width, max(0, h)) { p.place(0, 0) }
    }
    .drawWithContent {
        val t = progress()
        val c = InkMath.cappedCell(size.width, size.height, DISSOLVE_CELL.toPx())
        val cols = (size.width / c).toInt() + 1
        val rows = (size.height / c).toInt() + 1
        batch.reset()
        for (r in 0 until rows) for (col in 0 until cols) {
            // 0.72 × cell covers the cell: at t = 0 the dots hide nothing.
            val m = InkMath.dissolveDot(t, col / cols.toFloat(), r / rows.toFloat())
            batch.dot((col + 0.5f) * c, (r + 0.5f) * c, c * 0.72f * m)
        }
        clipPath(batch.path) { this@drawWithContent.drawContent() }
    }

/**
 * A selection indicator's slide between slots (tabs, segmented controls): eases out over
 * `transitionMillis` from wherever it is, and for the first frame leaves a registration ghost at
 * the slot it left. Read [position] and [ghost] only while drawing. Reduced motion: it jumps.
 */
@Stable
class InkSlide internal constructor(initial: Float) {
    internal val t = Animatable(1f)
    internal var from by mutableFloatStateOf(initial)
    internal var to by mutableFloatStateOf(initial)

    /** Indicator position in slots (0 = first). */
    val position: Float get() = InkMath.lerp(from, to, InkMath.easeOutCubic(t.value))

    /** The slot the indicator left (where the ghost prints). */
    val ghostSlot: Float get() = from

    /** Whether the registration ghost shows this frame. */
    val ghost: Boolean get() = t.value < 1f && InkMath.ghostVisible(t.value * Motion.transitionMillis)
}

@Composable
fun rememberInkSlide(index: Int): InkSlide {
    val reduced = LocalInkMotion.current.reduced
    val slide = remember { InkSlide(index.toFloat()) }
    LaunchedEffect(index) {
        val target = index.toFloat()
        if (target == slide.to) return@LaunchedEffect
        slide.from = slide.position
        slide.to = target
        if (reduced) {
            slide.from = target
            slide.t.snapTo(1f)
            return@LaunchedEffect
        }
        slide.t.snapTo(0f)
        slide.t.animateTo(1f, tween(Motion.transitionMillis, easing = LinearEasing))
    }
    return slide
}

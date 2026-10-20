package dev.wckdboy.autobot.core.designsystem.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Screen, sheet and list transitions in the ink language (motion spec v2 §3).

/**
 * Screen-to-screen: the incoming screen fades in over `transitionMillis` (and settles into
 * register via [misregisterEnter]); the outgoing one fades out faster. With reduced motion, a
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
 * The push settling into register: when [animate] (first composition of a pushed screen), the
 * screen starts `registerOffset` down-right and settles to 0 over `transitionMillis`. v3 dropped
 * the red ink plate (an offscreen layer that drew the whole incoming screen twice per frame, on
 * the transition's busiest frames); this is a translation of the screen's own layer — no extra
 * drawing, no recomposition. Nothing after the push, or with reduced motion.
 */
@Composable
fun Modifier.misregisterEnter(animate: Boolean): Modifier {
    val reduced = LocalInkMotion.current.reduced
    val first = remember { animate && !reduced }
    if (!first) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(Motion.transitionMillis, easing = LinearEasing)) }
    return graphicsLayer {
        val d = Motion.registerOffset.dp.toPx() * InkMath.misregisterOffset(progress.value)
        translationX = d
        translationY = d
    }
}

/** Rows are given this long to leave the list after their delete before they show again (e.g. it failed). */
private const val DISSOLVE_RESTORE_MILLIS = 2_000L

/**
 * List deletion: [dissolve] a row's key, and its row (wearing [inkDissolve]) fades and
 * collapses over `dissolveMillis`; then the real delete runs. With reduced
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
 * The row half of [InkDissolveState]: while its [key] dissolves, the row fades out and its height
 * collapses. Progress is read only in layout and draw; untouched rows allocate nothing.
 */
@Composable
fun Modifier.inkDissolve(state: InkDissolveState, key: Any): Modifier {
    // Rows of a long list pay one map read; the animation exists only while dissolving.
    if (!state.isDissolving(key)) return this
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(Motion.dissolveMillis, easing = LinearEasing)) }
    return dissolveAt { progress.value }
}

/**
 * The dissolve at [progress] (0…1 of `dissolveMillis`; read only in layout and draw): the row
 * fades and its height collapses. The bounds clip sits outside the collapsing layout so the row
 * is cut to its height.
 */
internal fun Modifier.dissolveAt(progress: () -> Float): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val p = measurable.measure(constraints)
        val h = (p.height * InkMath.dissolveHeight(progress())).roundToInt()
        layout(p.width, max(0, h)) { p.place(0, 0) }
    }
    .graphicsLayer { alpha = 1f - InkMath.clamp01(progress() / InkMath.DISSOLVE_COLLAPSE_START) }

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

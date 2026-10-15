package dev.wckdboy.autobot.core.designsystem.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.Motion

// One-shot ink modifiers. Their progress is read only inside graphicsLayer / draw lambdas, so an
// animation frame never recomposes the content.

/**
 * Rubber stamp: when [key] changes to a new value while [enabled] (a live state change, never
 * the first composition), the content pops in from `stampScale` and `stampRotationDegrees`
 * to rest while fading in over `stampMillis`. With reduced motion it only fades.
 */
@Composable
fun Modifier.stamp(key: Any?, enabled: Boolean = true): Modifier {
    val reduced = LocalInkMotion.current.reduced
    val progress = remember { Animatable(1f) }
    val seen = remember { arrayOf(key) }
    LaunchedEffect(key) {
        if (seen[0] == key) return@LaunchedEffect
        seen[0] = key
        if (!enabled) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(if (reduced) Motion.reducedMotionMillis else Motion.stampMillis, easing = LinearEasing))
    }
    return graphicsLayer {
        val t = progress.value
        if (t < 1f) {
            alpha = InkMath.stampAlpha(t)
            if (!reduced) {
                scaleX = InkMath.stampScale(t)
                scaleY = scaleX
                rotationZ = InkMath.stampRotation(t)
            }
        }
    }
}

/**
 * New content: a `enterRise` rise and fade over `enterMillis` when [animate] is true on first
 * composition (use [InkEnterTracker] to decide). Reduced motion: a short fade only.
 */
@Composable
fun Modifier.inkEnter(animate: Boolean, delayMillis: Int = 0): Modifier {
    val reduced = LocalInkMotion.current.reduced
    val progress = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) {
            progress.animateTo(
                1f,
                tween(if (reduced) Motion.reducedMotionMillis else Motion.enterMillis, delayMillis = if (reduced) 0 else delayMillis, easing = LinearEasing),
            )
        }
    }
    return graphicsLayer {
        val t = progress.value
        if (t < 1f) {
            alpha = InkMath.enterAlpha(t)
            if (!reduced) translationY = Motion.enterRise.dp.toPx() * InkMath.enterRise(t)
        }
    }
}

/**
 * Sheet / modal section [index] (0-based): enters like new content, `staggerMillis` after the
 * section before it (sections past the sixth enter with the sixth). Only on first composition.
 */
@Composable
fun Modifier.inkStagger(index: Int): Modifier = inkEnter(animate = true, delayMillis = InkMath.staggerDelayMillis(index))

/**
 * Out-of-register entrance: when [animate], a [color] block offset by `registerOffset` sits
 * behind the content and settles to 0 over `registerMillis` (ease-out-back), then disappears.
 * Nothing with reduced motion.
 */
@Composable
fun Modifier.registerGhost(animate: Boolean, color: Color, shape: Shape = RectangleShape): Modifier {
    val reduced = LocalInkMotion.current.reduced
    val progress = remember { Animatable(if (animate && !reduced) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (progress.value < 1f) progress.animateTo(1f, tween(Motion.registerMillis, easing = LinearEasing))
    }
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val offset = Motion.registerOffset.dp.toPx()
        onDrawBehind {
            val t = progress.value
            if (t < 1f) {
                val d = offset * InkMath.registerOffset(t * Motion.registerMillis)
                translate(d, d) { drawOutline(outline, color) }
            }
        }
    }
}

/**
 * Decides which transcript items are new: everything present when the screen finished loading
 * (and anything composed before that) is history and never animates; each key added later
 * animates once, even if it scrolls away and back.
 */
@Stable
class InkEnterTracker internal constructor() {
    private val seen = HashSet<Any>()

    /** True once the screen has loaded: content from now on is live. */
    var armed: Boolean = false
        private set

    internal fun arm(initial: Collection<Any>) {
        if (armed) return
        seen.addAll(initial)
        armed = true
    }

    /** Whether [key] is new since loading; claims it, so it answers true only once. */
    fun claim(key: Any): Boolean = seen.add(key) && armed
}

/** Arms the tracker in the composition where [loaded] first becomes true, with the [keys] then present. */
@Composable
fun rememberInkEnterTracker(loaded: Boolean, keys: () -> Collection<Any>): InkEnterTracker {
    val tracker = remember { InkEnterTracker() }
    if (loaded && !tracker.armed) tracker.arm(keys())
    return tracker
}

/** `Modifier.inkEnter` for an item [key] judged by [tracker]; claims once per composition of the item. */
@Composable
fun Modifier.inkEnter(tracker: InkEnterTracker, key: Any): Modifier {
    val animate = remember(key) { tracker.claim(key) }
    return inkEnter(animate)
}

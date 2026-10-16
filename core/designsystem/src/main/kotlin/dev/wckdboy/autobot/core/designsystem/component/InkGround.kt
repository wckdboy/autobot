package dev.wckdboy.autobot.core.designsystem.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.wckdboy.autobot.core.designsystem.motion.InkClock
import dev.wckdboy.autobot.core.designsystem.motion.DotGeometry
import dev.wckdboy.autobot.core.designsystem.motion.InkMath
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Touch bookkeeping for an ambient ground: it goes [idle] `ambientIdleStopSeconds` after the last touch. */
@Stable
internal class AmbientIdle {
    private var lastTouch = SystemClock.uptimeMillis()
    var idle by mutableStateOf(false)
        private set

    fun touch() {
        lastTouch = SystemClock.uptimeMillis()
        if (idle) idle = false
    }

    suspend fun watch() {
        while (true) {
            val wait = InkMath.ambientIdleRemainingMillis(lastTouch, SystemClock.uptimeMillis())
            if (wait == 0L) {
                idle = true
                snapshotFlow { idle }.first { !it }
            } else {
                delay(wait)
            }
        }
    }
}

/** Ambient grounds use a coarse cell: big, quiet dots and few of them. */
private val GROUND_CELL = 14.dp

/** Ground dots are smaller than the hero's so the field never merges into blots behind content. */
private const val GROUND_DOT = 0.75f

/**
 * The ambient tier's clock: ≤10 fps (`ambientFrameIntervalMillis`), still `ambientIdleStopSeconds`
 * after the last touch on the screen wearing [ambientTouch] (a touch, or coming back to the
 * screen, resumes it), and never moving during heavy work, in Battery Saver, off screen or with
 * reduced motion. Read [time] only while drawing.
 */
@Stable
class InkAmbient internal constructor(internal val clock: InkClock, internal val idle: AmbientIdle) {
    val time: Float get() = clock.time
}

/**
 * The screen's ambient clock, for living surfaces deeper in the tree ([inkCard], [inkGround]) so a
 * screen runs one ambient clock however many cards it shows. Null: each surface makes its own.
 */
val LocalInkAmbient = staticCompositionLocalOf<InkAmbient?> { null }

/** Provides [ambient] as the screen's [LocalInkAmbient] to [content]. */
@Composable
fun ProvideInkAmbient(ambient: InkAmbient, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalInkAmbient provides ambient, content = content)
}

@Composable
fun rememberInkAmbient(heavyWork: Boolean = false): InkAmbient {
    val motion = LocalInkMotion.current
    val idle = remember { AmbientIdle() }
    LaunchedEffect(idle) { idle.watch() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { idle.touch() }
    val runs = InkMath.ambientRuns(motion.reduced, motion.lowPower, heavyWork, idle.idle)
    val clock = rememberInkClock(runs, Motion.ambientFrameIntervalMillis)
    return remember(clock, idle) { InkAmbient(clock, idle) }
}

/** Counts any touch inside this element as interaction for [ambient] (observed, never consumed). */
fun Modifier.ambientTouch(ambient: InkAmbient): Modifier = pointerInput(ambient) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial)
            ambient.idle.touch()
        }
    }
}

/**
 * Living paper behind a large empty area: the hero halftone field drifting one cycle per
 * `ambientCycleSeconds` in a low-contrast [dot] colour at `ambientOpacity`, with a [plate]
 * registration plate at half that strength breathing ±1dp every `ambientBreathSeconds`.
 * Runs on [rememberInkAmbient]'s budget; one dot path per frame, drawn twice. Touches inside
 * this element keep it awake; pass a screen-level [ambient] to share one clock.
 */
@Composable
fun Modifier.inkGround(
    heavyWork: Boolean = false,
    dot: Color = MaterialTheme.colorScheme.outline,
    plate: Color = MaterialTheme.colorScheme.primary,
    cell: Dp = GROUND_CELL,
    seed: Float = 0f,
    ambient: InkAmbient = LocalInkAmbient.current ?: rememberInkAmbient(heavyWork),
): Modifier {
    val batch = remember { DotBatch() }
    return this
        .ambientTouch(ambient)
        .drawBehind {
            val t = ambient.time
            buildField(batch, cell.toPx(), seed + InkMath.ambientPhase(t), GROUND_DOT)
            val shift = (Motion.registerOffset * 0.5f + InkMath.ambientBreath(t)).dp.toPx()
            translate(shift, shift) { drawPath(batch.path, plate, alpha = Motion.ambientOpacity * 0.5f) }
            drawPath(batch.path, dot, alpha = Motion.ambientOpacity)
        }
}

/**
 * A living card's dot field for one size ([DotGeometry.card]): centres and base radii are
 * computed once per size (at most `cardMaxDots`); each frame only the radii change, from
 * [DotGeometry.cardRadius]. Nothing is allocated per frame.
 */
internal class CardField {
    private var width = -1f
    private var height = -1f
    private var count = 0
    private var wavelength = 1.0
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var base = DoubleArray(0)

    fun update(w: Float, h: Float, pitchPx: Float) {
        if (w == width && h == height) return
        width = w
        height = h
        val grid = DotGeometry.card(w, h, pitchPx)
        wavelength = grid.wavelength
        count = grid.x.count * grid.y.count
        if (xs.size < count) {
            xs = FloatArray(count)
            ys = FloatArray(count)
            base = DoubleArray(count)
        }
        for (r in 0 until grid.y.count) for (c in 0 until grid.x.count) {
            val i = r * grid.x.count + c
            xs[i] = grid.x.at(c)
            ys[i] = grid.y.at(r)
            base[i] = DotGeometry.cardBase(grid.x.pitch, xs[i], ys[i], w, h)
        }
    }

    /** Draws the field at wave time [seconds] (dots never overlap: plain circles, one paint). */
    fun draw(scope: DrawScope, seconds: Float, color: Color, alpha: Float) {
        val t = seconds.toDouble()
        for (i in 0 until count) {
            val r = DotGeometry.cardRadius(base[i], xs[i].toDouble(), ys[i].toDouble(), t, wavelength)
            scope.inkDot(color, xs[i], ys[i], r.toFloat(), alpha)
        }
    }
}

/**
 * Living card background (spec v3 §2): a low-contrast halftone grid (`cardDotPitch`) in [dot] —
 * the card's ink — at `cardDotOpacity` (about 10% visual weight), whose dot radii follow a slow
 * travelling wave: one wavelength per card width, one cycle per `cardWavePeriodSeconds`, lighter
 * towards the top-left. Drawn behind the element's content (put it inside the card's shape and
 * background) on the screen's [LocalInkAmbient] clock, or its own on the same shared ambient
 * tier: ≤10 fps, still once idle, in Battery Saver, during heavy work and with reduced motion.
 * Reads the clock only while drawing. For a few hero surfaces per screen, never list rows.
 */
@Composable
fun Modifier.inkCard(
    dot: Color = MaterialTheme.colorScheme.onSurface,
    ambient: InkAmbient? = LocalInkAmbient.current,
): Modifier {
    val clock = ambient ?: rememberInkAmbient()
    val field = remember { CardField() }
    return drawBehind {
        field.update(size.width, size.height, Motion.cardDotPitch.dp.toPx())
        field.draw(this, clock.time, dot, Motion.cardDotOpacity)
    }
}

/** Fills [batch] with the hero field over this canvas at [phase]. */
private fun DrawScope.buildField(batch: DotBatch, cell: Float, phase: Float, scale: Float = 1f) {
    val grid = DotGeometry.ground(size.width, size.height, InkMath.cappedCell(size.width, size.height, cell), FIELD_DOT * min(scale, 1f))
    val c = grid.x.pitch
    batch.reset()
    for (r in 0 until grid.y.count) for (col in 0 until grid.x.count) {
        val v = InkMath.field(col / grid.x.count.toFloat(), r / grid.y.count.toFloat(), phase)
        batch.dot(grid.x.at(col), grid.y.at(r), v * c * FIELD_DOT * scale)
    }
}

/** Ground dots at full value fill this fraction of their pitch (as a radius). */
private const val FIELD_DOT = 0.6f

/**
 * The lock screen's paper printing away: when [locked] turns false, a [paper] sheet with the
 * ground's dots is drawn over the revealed content for `printMillis` — the dots swell into ink
 * and clear while the plate settles into register and the sheet lifts (the print curves).
 * Nothing while locked, before the first unlock, or with reduced motion.
 */
@Composable
fun InkPrintAway(
    locked: Boolean,
    paper: Color = MaterialTheme.colorScheme.background,
    dot: Color = MaterialTheme.colorScheme.outline,
    plate: Color = MaterialTheme.colorScheme.primary,
) {
    val reduced = LocalInkMotion.current.reduced
    var wasLocked by remember { mutableStateOf(locked) }
    var playing by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(locked) {
        if (locked) {
            wasLocked = true
            playing = false
            return@LaunchedEffect
        }
        if (!wasLocked) return@LaunchedEffect
        wasLocked = false
        if (reduced) return@LaunchedEffect
        progress.snapTo(0f)
        playing = true
        progress.animateTo(1f, tween(Motion.printMillis, easing = LinearEasing))
        playing = false
    }
    if (locked || !playing) return
    val batch = remember { DotBatch() }
    Canvas(Modifier.fillMaxSize()) {
        val ms = progress.value * Motion.printMillis
        val lift = InkMath.printDotsAlpha(ms)
        drawRect(paper, alpha = lift)
        buildField(batch, GROUND_CELL.toPx(), 0f, InkMath.printMerge(ms))
        val offset = Motion.registerOffset.dp.toPx() * InkMath.registerOffset(ms)
        translate(offset, offset) { drawPath(batch.path, plate, alpha = max(0f, 0.35f * InkMath.plateAlpha(ms))) }
        drawPath(batch.path, dot, alpha = Motion.ambientOpacity * 2f * lift)
    }
}

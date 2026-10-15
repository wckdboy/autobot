package dev.wckdboy.autobot.core.designsystem.motion

import dev.wckdboy.autobot.core.designsystem.theme.Motion
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The pure maths behind the "ink" motion spec: easing, the develop stagger, fills, frame pacing
 * and the one-shot curves (print, stamp, enter, register). No Android or Compose types, so it is
 * unit-tested on the JVM and every animation reads its numbers from one place.
 */
object InkMath {
    /** Most dots a procedural canvas draws per frame; [cappedCell] grows the cell to stay under. */
    const val MAX_DOTS = 2500

    /** Within this of the interval a frame counts as due (half a 120 Hz vsync). */
    private const val FRAME_TOLERANCE_NANOS = 4_000_000L

    /** Flow speed while a model is loading (no steps yet) / while previews shimmer. */
    const val LOADING_SPEED = 0.5f
    const val SHIMMER_SPEED = 0.15f

    /** How far the shimmer moves a developed dot (fraction of its radius). */
    const val SHIMMER_DEPTH = 0.08f

    fun clamp01(t: Float): Float = if (t < 0f) 0f else if (t > 1f) 1f else t

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun easeOutCubic(t: Float): Float {
        val u = 1f - clamp01(t)
        return 1f - u * u * u
    }

    /** Ease-out-back: overshoots slightly past 1 before settling (register marks). */
    fun easeOutBack(t: Float, overshoot: Float = 1.70158f): Float {
        val u = clamp01(t) - 1f
        return 1f + (overshoot + 1f) * u * u * u + overshoot * u * u
    }

    /**
     * Per-dot local progress of a develop: `clamp((t − s·(x+y)/2) / (1 − s), 0, 1)` for a dot at
     * normalised ([x], [y]), so the image washes in along the flow diagonal.
     */
    fun staggered(t: Float, x: Float, y: Float, stagger: Float = Motion.developStagger): Float {
        if (stagger >= 1f) return if (t >= 1f) 1f else 0f
        return clamp01((t - stagger * (x + y) * 0.5f) / (1f - stagger))
    }

    /** Dot radius multiplier of the flowing field for sampling [progress] (0…1). */
    fun flowFill(progress: Float, loading: Boolean = false): Float =
        if (loading) Motion.flowMinFill * 0.75f else lerp(Motion.flowMinFill, 1f, clamp01(progress))

    /** Phase advance per second at [speed] × the base flow (one cycle = 2 phase units). */
    fun phasePerSecond(speed: Float): Float = 2f * Motion.flowCyclesPerSecond * speed

    fun frameIntervalMillis(lowPower: Boolean): Int =
        if (lowPower) Motion.lowPowerFrameIntervalMillis else Motion.frameIntervalMillis

    /** Whether the vsync at [nowNanos] should produce a frame, given the last one at [lastNanos] (<0: none yet). */
    fun isFrameDue(lastNanos: Long, nowNanos: Long, intervalMillis: Int): Boolean =
        lastNanos < 0 || nowNanos - lastNanos >= intervalMillis * 1_000_000L - FRAME_TOLERANCE_NANOS

    /** How long the clock may sleep after a frame before it starts watching vsyncs again. */
    fun idleMillis(intervalMillis: Int): Long = max(0, intervalMillis - 16).toLong()

    /** [cell] (px) grown, if needed, so a [width]×[height] canvas holds at most [maxDots] dots. */
    fun cappedCell(width: Float, height: Float, cell: Float, maxDots: Int = MAX_DOTS): Float {
        if (width <= 0f || height <= 0f || cell <= 0f) return max(cell, 1f)
        var c = max(cell, sqrt(width * height / maxDots))
        while (dotCount(width, height, c) > maxDots) c *= 1.05f
        return c
    }

    fun dotCount(width: Float, height: Float, cell: Float): Int =
        (ceil(width / cell).toInt() + 1) * (ceil(height / cell).toInt() + 1)

    /** The diagonal wave (0…1) at normalised ([x], [y]). */
    fun wave(x: Float, y: Float, phase: Float): Float =
        0.5f + 0.5f * sin((x * 3.1f + y * 2.3f + phase) * PI.toFloat())

    /** The procedural halftone field's value (0…1): the wave plus a soft blob, lighter at the bottom. */
    fun field(x: Float, y: Float, phase: Float): Float {
        val blob = 1f - min(1f, sqrt((x - 0.62f) * (x - 0.62f) * 2.2f + (y - 0.38f) * (y - 0.38f) * 3.0f))
        return (0.55f * wave(x, y, phase) + 0.75f * blob - 0.25f * y).coerceIn(0f, 1f)
    }

    /** Status dot alpha [millis] into a live pulse: 1 → min → 1 every two pulses. */
    fun pulseAlpha(millis: Float): Float {
        val c = 0.5f + 0.5f * cos(PI.toFloat() * millis / Motion.statusDotPulseMillis)
        return lerp(Motion.statusDotPulseMinAlpha, 1f, c)
    }

    // --- one-shots: each takes elapsed milliseconds ---------------------------------------

    /** Print: dot growth (1 → [Motion.printMergeScale]) so the dots merge into solid ink. */
    fun printMerge(millis: Float): Float =
        lerp(1f, Motion.printMergeScale, easeOutCubic(millis / (Motion.printMillis * 0.6f)))

    /** Print: the full-colour image fades in underneath across the second half. */
    fun printImageAlpha(millis: Float): Float =
        easeOutCubic((millis - Motion.printMillis * 0.5f) / (Motion.printMillis * 0.5f))

    /** Print: the dots give way to the image as it arrives, ending fully clear. */
    fun printDotsAlpha(millis: Float): Float = 1f - printImageAlpha(millis)

    /** Register settle (print plate, approval ghost): offset fraction 1 → 0 with an overshoot. */
    fun registerOffset(millis: Float): Float = 1f - easeOutBack(millis / Motion.registerMillis)

    /** Print plate: fully inked until it is in register, then fades out by the end of the print. */
    fun plateAlpha(millis: Float): Float =
        1f - clamp01((millis - Motion.registerMillis) / (Motion.printMillis - Motion.registerMillis))

    /** Stamp at [t] (0…1): scale, rotation (degrees) and alpha. */
    fun stampScale(t: Float): Float = lerp(Motion.stampScale, 1f, easeOutCubic(t))
    fun stampRotation(t: Float): Float = lerp(Motion.stampRotationDegrees, 0f, easeOutCubic(t))
    fun stampAlpha(t: Float): Float = easeOutCubic(t)

    /** Enter at [t] (0…1): remaining rise as a fraction of [Motion.enterRise], and alpha. */
    fun enterRise(t: Float): Float = 1f - easeOutCubic(t)
    fun enterAlpha(t: Float): Float = easeOutCubic(t)
}

/**
 * Integrates the flow phase over clock time so speed changes (loading ×0.5, shimmer ×0.15)
 * never make the pattern jump. Idempotent for a repeated [time].
 */
class FlowPhase(initial: Float = 0f) {
    var phase: Float = initial
        private set
    private var last = Float.NaN

    fun at(time: Float, speed: Float): Float {
        if (!last.isNaN() && time > last) phase += (time - last) * InkMath.phasePerSecond(speed)
        last = time
        return phase
    }
}

/**
 * A value that eases from wherever it is to a new target over [durationSeconds] of clock time
 * (ease-out cubic); retargeting mid-flight starts from the current value, so there are no jumps.
 */
class InkTween(initial: Float, private val durationSeconds: Float) {
    private var from = initial
    private var to = initial
    private var start = 0f

    val target: Float get() = to

    fun retarget(target: Float, now: Float) {
        if (target == to) return
        from = value(now)
        to = target
        start = now
    }

    fun snap(value: Float) {
        from = value
        to = value
    }

    fun value(now: Float): Float =
        if (durationSeconds <= 0f) to else InkMath.lerp(from, to, InkMath.easeOutCubic((now - start) / durationSeconds))
}

/** [InkTween] for a row of values (the voice level meter): each slot eases to its new target. */
class InkTweenRow(private val size: Int, private val durationSeconds: Float) {
    private val from = FloatArray(size)
    private val to = FloatArray(size)
    private var start = 0f

    fun retarget(targets: FloatArray, now: Float) {
        val t = progress(now)
        for (i in 0 until size) {
            from[i] = InkMath.lerp(from[i], to[i], t)
            to[i] = targets.getOrElse(i) { 0f }
        }
        start = now
    }

    /** Jump straight to [targets] (clock stopped, reduced motion). */
    fun snap(targets: FloatArray) {
        for (i in 0 until size) {
            to[i] = targets.getOrElse(i) { 0f }
            from[i] = to[i]
        }
    }

    fun value(i: Int, now: Float): Float = InkMath.lerp(from[i], to[i], progress(now))

    private fun progress(now: Float) =
        if (durationSeconds <= 0f) 1f else InkMath.easeOutCubic((now - start) / durationSeconds)
}

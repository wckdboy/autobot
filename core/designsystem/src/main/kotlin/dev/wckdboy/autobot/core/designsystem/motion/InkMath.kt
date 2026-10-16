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

    // --- v2: loaders, bars, ambient grounds, transitions --------------------------------

    fun easeInCubic(t: Float): Float {
        val u = clamp01(t)
        return u * u * u
    }

    fun easeInOutCubic(t: Float): Float {
        val u = clamp01(t)
        return if (u < 0.5f) 4f * u * u * u else 1f - (-2f * u + 2f).let { it * it * it } / 2f
    }

    /** Fraction (0…1) of a repeating cycle of [cycleMillis] at [millis]. */
    fun cycle(millis: Float, cycleMillis: Int): Float {
        if (cycleMillis <= 0) return 0f
        val p = (millis % cycleMillis) / cycleMillis
        return if (p < 0f) p + 1f else p
    }

    /** Ambient ground: flow phase at [seconds] (one wave cycle = 2 phase units per `ambientCycleSeconds`). */
    fun ambientPhase(seconds: Float): Float = 2f * seconds / Motion.ambientCycleSeconds

    /** Ambient ground: the second plate's breath (−1…1) at [seconds], one breath per `ambientBreathSeconds`. */
    fun ambientBreath(seconds: Float): Float = sin(2f * PI.toFloat() * seconds / Motion.ambientBreathSeconds)

    /** Whether an ambient ground may move: never with reduced motion, in Battery Saver, during heavy work or once idle. */
    fun ambientRuns(reduced: Boolean, lowPower: Boolean, heavyWork: Boolean, idle: Boolean): Boolean =
        !reduced && !lowPower && !heavyWork && !idle

    /** Milliseconds until an ambient ground stops after the last touch at [lastTouchMillis] (0: stop now). */
    fun ambientIdleRemainingMillis(lastTouchMillis: Long, nowMillis: Long): Long =
        max(0L, lastTouchMillis + Motion.ambientIdleStopSeconds * 1000L - nowMillis)

    /** Push transition at [t] (0…1 of `transitionMillis`): the screen's offset as a fraction of `registerOffset`. */
    fun misregisterOffset(t: Float): Float = 1f - easeOutCubic(t)

    /** Most sheet sections that get their own stagger step; later ones enter with the sixth. */
    const val MAX_STAGGER_SECTIONS = 6

    /** Sheet section [index]'s enter delay: `staggerMillis` per section, capped at six sections. */
    fun staggerDelayMillis(index: Int): Int =
        index.coerceIn(0, MAX_STAGGER_SECTIONS - 1) * Motion.staggerMillis

    /** Tab/segment indicator: a registration ghost shows for the first frame of a slide only. */
    fun ghostVisible(elapsedMillis: Float, frameIntervalMillis: Int = Motion.frameIntervalMillis): Boolean =
        elapsedMillis >= 0f && elapsedMillis < frameIntervalMillis

    /** List dissolve at [t] (0…1 of `dissolveMillis`): dot radius multiplier for a dot at normalised ([x], [y]). */
    fun dissolveDot(t: Float, x: Float, y: Float): Float =
        1f - easeInCubic(staggered(t, x, y, DISSOLVE_STAGGER))
    const val DISSOLVE_STAGGER = 0.3f

    /** List dissolve at [t]: the row's height fraction; it collapses through the second part. */
    fun dissolveHeight(t: Float): Float = 1f - easeInOutCubic((t - DISSOLVE_COLLAPSE_START) / (1f - DISSOLVE_COLLAPSE_START))
    const val DISSOLVE_COLLAPSE_START = 0.35f

    // --- v3: the dot bar, the wave loader and living cards ---------------------------------

    /** Dot bar: how many dot slots fit a bar [width] wide whose dots are [dotHeight] across (pitch = height × `barDotPitch`). */
    fun barSlots(width: Float, dotHeight: Float): Int =
        if (width <= 0f || dotHeight <= 0f) 0 else max(1, (width / (dotHeight * Motion.barDotPitch)).toInt())

    /** Dot bar: a filled dot grows from nothing to its ramp size while the fill front moves this many slots past it. */
    const val BAR_HEAD_SLOTS = 2.5f

    /**
     * Dot bar: where the fill front is (in slots) at [progress] on a bar of [slots]. Scaled so the
     * last dot is fully grown exactly at 1, which makes growth continuous (no popping) and a full
     * bar a row of full circles.
     */
    fun barFront(progress: Float, slots: Int): Float = clamp01(progress) * (max(1, slots) - 1 + BAR_HEAD_SLOTS)

    /**
     * Dot bar: radius (fraction of a full dot) of slot [i] with the front at [front] and the bar at
     * [progress]; 0 for an unfilled slot. Filled dots ramp from `barFillStartScale` at the start to
     * full at the head, and the head dots ease in as the front passes them. The ramp evens out as
     * the bar completes, so 100% is all full circles.
     */
    fun barFillScale(i: Int, front: Float, progress: Float): Float {
        val grow = clamp01((front - i) / BAR_HEAD_SLOTS)
        if (grow <= 0f) return 0f
        val ramp = lerp(Motion.barFillStartScale, 1f, clamp01((i + 0.5f) / max(1f, front)))
        val p = clamp01(progress)
        val done = p * p * p * p
        return lerp(ramp, 1f, done) * easeOutCubic(grow)
    }

    /**
     * The "working" wave (0…1) at dot position [i] (in slots, along the travel direction) at
     * [millis]: one cycle per `loaderCycleMillis`, a wavelength of `barWaveSlots`, travelling
     * towards higher [i].
     */
    fun dotWave(i: Float, millis: Float): Float {
        val turns = i / Motion.barWaveSlots - cycle(millis, Motion.loaderCycleMillis)
        return 0.5f + 0.5f * sin(2f * PI.toFloat() * turns)
    }

    /** Wave dots at rest (fraction of a full dot) between swells. */
    const val WAVE_REST = 0.3f

    /** Radius (fraction of a full dot) of a loading dot at wave value [v]: swelling crests, small troughs. */
    fun waveScale(v: Float): Float = lerp(WAVE_REST, 1f, v * v)

    /** A determinate bar's running filled dots breathe with the wave by ±`barRunningWaveAmplitude`. */
    fun runningWave(v: Float): Float = 1f + Motion.barRunningWaveAmplitude * (2f * v - 1f)

    /** Living card: dot pitch (px) grown from [pitch] so a [width]×[height] card holds at most `cardMaxDots`. */
    fun cardPitch(width: Float, height: Float, pitch: Float): Float = cappedCell(width, height, pitch, Motion.cardMaxDots)

    /**
     * Living card: the travelling wave's spatial phase (radians) at ([x], [y]) px on a card [width]
     * wide: one wavelength per card width, running down-right at a shallow angle.
     */
    fun cardPhase(x: Float, y: Float, width: Float): Float {
        val k = 2f * PI.toFloat() / max(1f, width)
        return k * (x * CARD_DIR_X + y * CARD_DIR_Y)
    }
    private const val CARD_DIR_X = 0.866f
    private const val CARD_DIR_Y = 0.5f

    /** Living card: the wave's time phase (radians) at [seconds], one cycle per `cardWavePeriodSeconds`. */
    fun cardTimePhase(seconds: Float): Float =
        2f * PI.toFloat() * cycle(seconds * 1000f, Motion.cardWavePeriodSeconds * 1000)

    /**
     * Living card: radius factor r / base = [CARD_DOT_MEAN] + [CARD_DOT_SWING]·sin(spatial − time),
     * 0.24…1: every dot stays visible at the wave's low point.
     */
    fun cardDot(spatial: Float, time: Float): Float = (CARD_DOT_MEAN + CARD_DOT_SWING * sin(spatial - time)).toFloat()

    const val CARD_DOT_MEAN = 0.62
    const val CARD_DOT_SWING = 0.38

    /**
     * Living card: a dot's base radius factor by where it sits (normalised [x], [y]): lighter
     * towards the top-left, where titles and text usually start, full towards the bottom-right.
     */
    fun cardFalloff(x: Float, y: Float): Float = lerp(CARD_FALLOFF_MIN, 1f, clamp01((x + y) * 0.5f))
    const val CARD_FALLOFF_MIN = 0.35f
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

package dev.wckdboy.autobot.core.designsystem.motion

import dev.wckdboy.autobot.core.designsystem.theme.Motion
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * One axis of a dot grid in device pixels: [count] dots, the centre of dot i at
 * [first] + i·[pitch]. The pitch is a whole number of pixels, so every dot sits at the same
 * sub-pixel offset and rasterises identically (no shimmer, no uneven antialiasing).
 */
data class DotAxis(val count: Int, val pitch: Float, val first: Float) {
    fun at(i: Int): Float = first + i * pitch

    val last: Float get() = at(max(0, count - 1))
}

/**
 * Halftone geometry, in device pixels and free of Android so it is unit-tested directly. Every
 * grid fits a whole number of dots inside its bounds (no clipped half-dot at an edge), centred:
 * the space left over is split evenly between both sides.
 */
object DotGeometry {
    /** A pitch in whole pixels (at least 1). */
    fun pitchPx(px: Float): Float = max(1f, round(px))

    /** [v] on the half-pixel grid: centres of a centred grid on whole-pixel bounds already are. */
    fun snapHalf(v: Float): Float = round(v * 2f) / 2f

    /**
     * Dots of radius up to [maxRadius] at [pitch] (rounded to whole px) along [length] px:
     * N = ⌊(length − 2·maxRadius) / pitch⌋ + 1, centred. Empty when not even one dot fits.
     */
    fun axis(length: Float, pitch: Float, maxRadius: Float): DotAxis {
        val p = pitchPx(pitch)
        val usable = length - 2f * maxRadius
        if (usable < 0f) return DotAxis(0, p, 0f)
        val n = floor(usable / p).toInt() + 1
        return DotAxis(n, p, snapHalf((length - (n - 1) * p) / 2f))
    }

    /** Exactly [count] dots at [pitch] (whole px), centred on [length]. */
    fun axis(length: Float, pitch: Float, count: Int): DotAxis {
        val p = pitchPx(pitch)
        return DotAxis(count, p, snapHalf((length - (count - 1) * p) / 2f))
    }

    // --- the dot progress bar ------------------------------------------------------------

    /**
     * A bar's dots for one size: slots along x, every centre on the vertical centre line, a full
     * dot of radius [full] (the bar's height, or [minDotPx] for thinner bars, capped by the pitch)
     * and faint empty pins of radius [pin]. Margins leave room for the running wave's swell.
     */
    data class Bar(val slots: DotAxis, val cy: Float, val full: Float, val pin: Float)

    /** Bars thinner than this (dp) still draw dots this big. */
    const val BAR_MIN_DOT_DP = 4f

    fun bar(width: Float, height: Float, minDotPx: Float): Bar {
        val dot = max(height, minDotPx)
        val pitch = pitchPx(dot * Motion.barDotPitch)
        val full = min(dot, pitch) / 2f
        val swell = full * (1f + Motion.barRunningWaveAmplitude)
        val slots = if (width <= 0f || height <= 0f) DotAxis(0, pitch, 0f) else axis(width, pitch, swell)
        return Bar(slots, height / 2f, full, max(full * Motion.barEmptyDotScale, 0.6f))
    }

    /**
     * Radius of slot [i] on a determinate bar at [progress]; 0 for an empty slot. The fill front
     * moves linearly with progress; only the radius eases in as the front passes a slot.
     */
    fun barRadius(bar: Bar, i: Int, progress: Float, wave: Float? = null): Float {
        val k = InkMath.barFillScale(i, InkMath.barFront(progress, bar.slots.count), progress)
        if (k <= 0f) return 0f
        return bar.full * k * (wave?.let(InkMath::runningWave) ?: 1f)
    }

    /** Radius of slot [i] on an indeterminate bar at [millis]: the travelling wave. */
    fun barWaveRadius(bar: Bar, i: Int, millis: Float): Float = bar.full * InkMath.waveScale(InkMath.dotWave(i.toFloat(), millis))

    // --- the loader ------------------------------------------------------------------------

    /** A cols×rows loader centred in [width]×[height] px at a whole-px [pitch]; full dot radius [full]. */
    data class Loader(val x: DotAxis, val y: DotAxis, val full: Float)

    fun loader(width: Float, height: Float, pitch: Float, cols: Int, rows: Int, dotFraction: Float): Loader {
        // Rounding the pitch up must not push the outer dots past the box: shrink by whole px until they fit.
        var p = pitchPx(pitch)
        while (p > 1f && ((cols - 1) * p + 2f * p * dotFraction > width || (rows - 1) * p + 2f * p * dotFraction > height)) p -= 1f
        return Loader(axis(width, p, cols), axis(height, p, rows), p * dotFraction)
    }

    // --- living cards ----------------------------------------------------------------------

    /** Card dots at full swell fill this much of their pitch (diameter 0.72·pitch: never touching). */
    const val CARD_DOT = 0.36f

    /** The card wave's wavelength, in token pitches (≈ one phone-width card, whatever its size). */
    const val CARD_WAVELENGTH_PITCHES = 32

    /**
     * A living card's square grid: the token pitch in whole px (grown, still whole, when a big card
     * would exceed `cardMaxDots`), centred both ways.
     */
    data class Card(val x: DotAxis, val y: DotAxis, val wavelength: Double)

    fun card(width: Float, height: Float, tokenPitchPx: Float): Card {
        // Half a pitch around the field (a full halftone cell per dot), wider than any dot's radius.
        var p = pitchPx(tokenPitchPx)
        var x = axis(width, p, p / 2f)
        var y = axis(height, p, p / 2f)
        while (x.count * y.count > Motion.cardMaxDots) {
            p += 1f
            x = axis(width, p, p / 2f)
            y = axis(height, p, p / 2f)
        }
        return Card(x, y, CARD_WAVELENGTH_PITCHES * pitchPx(tokenPitchPx).toDouble())
    }

    /**
     * r = base·(0.55 + 0.45·sin(kx·x + ky·y − ωt)), in doubles: k = 2π/λ along a shallow
     * down-right direction, ω = 2π / `cardWavePeriodSeconds`. Depends on absolute position and the
     * clock only, so the wave is continuous across frames, pauses and size changes.
     */
    fun cardRadius(base: Double, x: Double, y: Double, seconds: Double, wavelength: Double): Double {
        val k = 2.0 * PI / wavelength
        val period = Motion.cardWavePeriodSeconds.toDouble()
        val t = seconds - floor(seconds / period) * period
        val phase = k * (x * CARD_DIR_X + y * CARD_DIR_Y) - 2.0 * PI * t / period
        return max(0.0, base * (0.55 + 0.45 * sin(phase)))
    }

    private const val CARD_DIR_X = 0.8660254037844386 // cos 30°
    private const val CARD_DIR_Y = 0.5

    /** Full-swell radius of a card dot at ([x], [y]) on a [width]×[height] card: lighter towards the top-left. */
    fun cardBase(pitch: Float, x: Float, y: Float, width: Float, height: Float): Double =
        pitch * CARD_DOT * InkMath.cardFalloff(x / max(1f, width), y / max(1f, height)).toDouble()

    // --- paper ground ------------------------------------------------------------------------

    /** The ground's square grid for dots up to [maxDotFraction]·pitch in radius, half a pitch (at least) from every edge. */
    data class Ground(val x: DotAxis, val y: DotAxis)

    fun ground(width: Float, height: Float, pitch: Float, maxDotFraction: Float): Ground {
        val p = pitchPx(pitch)
        val margin = p * max(0.5f, maxDotFraction)
        return Ground(axis(width, p, margin), axis(height, p, margin))
    }
}

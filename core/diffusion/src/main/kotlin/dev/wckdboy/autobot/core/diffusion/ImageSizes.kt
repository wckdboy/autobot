package dev.wckdboy.autobot.core.diffusion

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An image shape, [w]:[h] (16:9 is landscape). */
data class Aspect(val label: String, val w: Int, val h: Int) {
    val ratio: Double get() = w.toDouble() / h

    companion object {
        val SQUARE = Aspect("1:1", 1, 1)

        /** The shapes the studio offers, in the order shown. */
        val ALL = listOf(
            SQUARE,
            Aspect("2:3", 2, 3),
            Aspect("3:2", 3, 2),
            Aspect("3:4", 3, 4),
            Aspect("4:3", 4, 3),
            Aspect("9:16", 9, 16),
            Aspect("16:9", 16, 9),
        )

        /**
         * `16:9`, `16x9`, `16/9`, or a word (square, portrait, landscape, wide, tall); null when
         * [text] is none of these.
         */
        fun parse(text: String?): Aspect? {
            val t = text?.trim()?.lowercase() ?: return null
            when (t) {
                "square" -> return SQUARE
                "portrait", "tall", "vertical" -> return Aspect("2:3", 2, 3)
                "landscape", "horizontal" -> return Aspect("3:2", 3, 2)
                "wide", "widescreen", "cinematic" -> return Aspect("16:9", 16, 9)
                "story", "phone" -> return Aspect("9:16", 9, 16)
            }
            val m = Regex("^(\\d{1,3}(?:\\.\\d+)?)\\s*[:x×/]\\s*(\\d{1,3}(?:\\.\\d+)?)$").find(t) ?: return null
            val a = m.groupValues[1].toDouble()
            val b = m.groupValues[2].toDouble()
            if (a <= 0 || b <= 0 || a / b > 4 || b / a > 4) return null
            ALL.firstOrNull { abs(it.ratio - a / b) < 0.01 }?.let { return it }
            // A ratio not in the list (21:9): kept as given, in hundredths.
            return Aspect(t.replace(" ", ""), (a * 100).roundToInt(), (b * 100).roundToInt())
        }

        /** The listed shape [w]×[h] is (within 3%), or null for a size that matches none. */
        fun of(w: Int, h: Int): Aspect? {
            if (w <= 0 || h <= 0) return null
            val r = w.toDouble() / h
            return ALL.minByOrNull { abs(it.ratio - r) }?.takeIf { abs(it.ratio - r) / it.ratio < 0.03 }
        }
    }
}

/**
 * The sizes a backend or model can render. Engines differ: stable-diffusion.cpp takes any
 * multiple of 8 in 64–2048 px, MNN graphs multiples of 64 in 256–1536 px, and QNN context
 * binaries were compiled for one square ([fixedSide]) — other shapes are cut from that square.
 *
 * Sizes are planned at about [base]² pixels (the pixel budget a model was trained at: SDXL's
 * 16:9 bucket is 1344×768 for "1024"), never by squashing a side, so a 16:9 choice is always
 * landscape.
 */
data class SizeSupport(
    val multiple: Int = 8,
    val min: Int = 64,
    val max: Int = 2048,
    /** Renders only [fixedSide]² (QNN); every other shape is a centre crop of it. */
    val fixedSide: Int? = null,
) {
    val isFixed: Boolean get() = fixedSide != null

    /** The pixel budgets to choose from. */
    val bases: List<Int>
        get() = fixedSide?.let(::listOf) ?: BASES.filter { it in min..max }.ifEmpty { listOf(max) }

    /** [aspect] at about [base]² pixels (a fixed-size model: the largest crop of its square). */
    fun size(base: Int, aspect: Aspect): Pair<Int, Int> = size(base, aspect.ratio)

    fun size(base: Int, ratio: Double): Pair<Int, Int> {
        fixedSide?.let { side -> return crop(side, ratio) }
        val area = base.toDouble() * base
        return fit(sqrt(area * ratio), sqrt(area / ratio))
    }

    /**
     * What the engine returns for a [w]×[h] request: the size snapped to [multiple] and kept in
     * range without changing its shape, or (fixed) the crop of the square with that shape.
     */
    fun output(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return size(bases.last(), 1.0)
        fixedSide?.let { side -> return crop(side, w.toDouble() / h) }
        return fit(w.toDouble(), h.toDouble())
    }

    /** The base of [bases] nearest to [w]×[h]'s pixel count. */
    fun baseOf(w: Int, h: Int): Int {
        val side = sqrt(w.toDouble().coerceAtLeast(1.0) * h.coerceAtLeast(1))
        return bases.minBy { abs(it - side) }
    }

    /** Scales [w]×[h] into [min]..[max] keeping its shape, then snaps each side to [multiple]. */
    private fun fit(w: Double, h: Double): Pair<Int, Int> {
        var s = 1.0
        if (w > max || h > max) s = minOf(max / w, max / h)
        if (w * s < min || h * s < min) s = maxOf(min / w, min / h).coerceAtMost(minOf(max / w, max / h))
        return snap(w * s) to snap(h * s)
    }

    private fun snap(v: Double): Int = ((v / multiple).roundToInt() * multiple).coerceIn(min, max)

    /** The largest [ratio] rectangle inside a [side]² square, sides snapped to 8 px. */
    private fun crop(side: Int, ratio: Double): Pair<Int, Int> = cropOf(side, side, ratio)

    companion object {
        val BASES = listOf(512, 768, 1024)

        /** stable-diffusion.cpp on the phone. */
        val SD_CPP = SizeSupport(multiple = 8, min = 64, max = 2048)

        /** MNN graphs (GPU/CPU), which take latent sizes in steps of 8 latents. */
        val MNN = SizeSupport(multiple = 64, min = 256, max = 1536)

        /** The largest [ratio] rectangle inside [w]×[h], sides rounded to 8 px. */
        fun cropOf(w: Int, h: Int, ratio: Double): Pair<Int, Int> {
            val cw: Double
            val ch: Double
            if (w.toDouble() / h > ratio) {
                ch = h.toDouble(); cw = h * ratio
            } else {
                cw = w.toDouble(); ch = w / ratio
            }
            val rw = ((cw / 8).roundToInt() * 8).coerceIn(8, w)
            val rh = ((ch / 8).roundToInt() * 8).coerceIn(8, h)
            return rw to rh
        }

        /**
         * The centred [ratio] crop of a [w]×[h] image as `left, top, width, height`, or null when
         * the image already has that shape (within 2%).
         */
        fun centerCrop(w: Int, h: Int, ratio: Double): IntArray? {
            if (w <= 0 || h <= 0 || ratio <= 0) return null
            if (abs(w.toDouble() / h - ratio) / ratio < 0.02) return null
            val (cw, ch) = cropOf(w, h, ratio)
            return intArrayOf((w - cw) / 2, (h - ch) / 2, cw, ch)
        }
    }
}

/**
 * The size of an agent's or a recipe's image request: explicit [width] and [height] win; an
 * [aspect] with one side derives the other; an [aspect] alone keeps the [current] pixel budget;
 * one side alone keeps the [current] shape. Snapped and kept in range by [support].
 */
fun resolveSize(current: Pair<Int, Int>, width: Int?, height: Int?, aspect: Aspect?, support: SizeSupport = SizeSupport()): Pair<Int, Int> {
    val (cw, ch) = current
    val ratio = aspect?.ratio ?: (cw.toDouble() / ch.coerceAtLeast(1))
    return when {
        width != null && height != null -> support.output(width, height)
        width != null -> support.output(width, (width / ratio).roundToInt())
        height != null -> support.output((height * ratio).roundToInt(), height)
        aspect != null -> support.size(sqrt(cw.toDouble() * ch).roundToInt(), aspect)
        else -> support.output(cw, ch)
    }
}

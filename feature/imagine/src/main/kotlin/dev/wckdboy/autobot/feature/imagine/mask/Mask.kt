package dev.wckdboy.autobot.feature.imagine.mask

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.runtime.Immutable
import dev.wckdboy.autobot.core.diffusion.MaskEncoding

/**
 * One brush stroke in image-normalized coordinates (0..1 on both axes), so strokes survive
 * resolution changes and render identically on screen and in the exported mask.
 *
 * @param radius brush radius as a fraction of the image width.
 */
@Immutable
data class MaskStroke(val points: List<Pair<Float, Float>>, val radius: Float, val erase: Boolean)

/** Undoable stroke list plus an invert flag. */
@Immutable
data class MaskState(
    val strokes: List<MaskStroke> = emptyList(),
    val redo: List<MaskStroke> = emptyList(),
    val inverted: Boolean = false,
) {
    val isEmpty: Boolean get() = strokes.none { !it.erase } && !inverted

    fun add(stroke: MaskStroke) = copy(strokes = strokes + stroke, redo = emptyList())
    fun undo() = if (strokes.isEmpty()) this else copy(strokes = strokes.dropLast(1), redo = redo + strokes.last())
    fun redoLast() = if (redo.isEmpty()) this else copy(strokes = strokes + redo.last(), redo = redo.dropLast(1))
    fun clear() = MaskState()
    fun invert() = copy(inverted = !inverted)
}

object MaskRasterizer {
    /**
     * Renders [state] at [width]×[height] in the backend's [encoding]: painted area (after
     * invert) is white; the rest is black (opaque) or transparent (alpha).
     */
    fun render(state: MaskState, width: Int, height: Int, encoding: MaskEncoding): Bitmap {
        val coverage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(coverage)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in state.strokes) {
            paint.strokeWidth = stroke.radius * width * 2
            if (stroke.erase) {
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                paint.color = Color.TRANSPARENT
            } else {
                paint.xfermode = null
                paint.color = Color.WHITE
            }
            val path = Path()
            stroke.points.forEachIndexed { i, (x, y) ->
                if (i == 0) path.moveTo(x * width, y * height) else path.lineTo(x * width, y * height)
            }
            if (stroke.points.size == 1) {
                val (x, y) = stroke.points.first()
                path.lineTo(x * width + 0.01f, y * height)
            }
            canvas.drawPath(path, paint)
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        coverage.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val painted = (pixels[i] ushr 24) >= 128
            val repaint = painted != state.inverted
            pixels[i] = when (encoding) {
                MaskEncoding.OPAQUE_BLACK_WHITE -> if (repaint) Color.WHITE else Color.BLACK
                MaskEncoding.ALPHA_WHITE -> if (repaint) Color.WHITE else Color.TRANSPARENT
            }
        }
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        coverage.recycle()
        return out
    }
}

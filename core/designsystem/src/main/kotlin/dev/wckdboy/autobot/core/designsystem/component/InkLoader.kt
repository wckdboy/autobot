package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.motion.InkMath
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock

/** [InkLoader] sizes: inline in text (14dp), in place of a button label (16dp), a block (48dp). */
enum class InkLoaderSize(val size: Dp, val dots: Int) {
    Inline(14.dp, 4),
    Button(16.dp, 4),
    Block(48.dp, 5),
}

/**
 * Indeterminate work: a small square of halftone dots where a soft ink drop travels the diagonal
 * once per `loaderCycleMillis` and dots swell as it passes (the plate's flow, in miniature).
 * One path fill per frame on the throttled ink clock; still dots with reduced motion.
 */
@Composable
fun InkLoader(
    modifier: Modifier = Modifier,
    size: InkLoaderSize = InkLoaderSize.Inline,
    color: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = "working",
) {
    val reduced = LocalInkMotion.current.reduced
    val clock = rememberInkClock(true)
    val batch = remember { DotBatch() }
    val n = size.dots
    Canvas(
        modifier
            .size(size.size)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        // Reduced motion: the drop rests mid-plate, a static diagonal of ink.
        val drop = if (reduced) 0.5f else InkMath.loaderDrop(clock.time * 1000f)
        val cell = this.size.minDimension / n
        val r0 = cell * 0.5f
        batch.reset()
        for (row in 0 until n) for (col in 0 until n) {
            val swell = InkMath.loaderSwell(InkMath.loaderDiagonal(col, row, n), drop)
            batch.dot((col + 0.5f) * cell, (row + 0.5f) * cell, r0 * swell)
        }
        drawPath(batch.path, color)
    }
}

/** A block loader (48dp) with an optional [caption] in micro type below it. */
@Composable
fun InkLoaderBlock(caption: String? = null, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InkLoader(size = InkLoaderSize.Block, color = color, contentDescription = caption ?: "working")
        caption?.let { MicroLabel(it) }
    }
}

/**
 * A button label that becomes an inline [InkLoader] while [busy]. The label stays laid out
 * (invisible) so the button keeps its width; the loader takes the label's colour.
 */
@Composable
fun InkBusyLabel(busy: Boolean, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.alpha(if (busy) 0f else 1f)) { label() }
        if (busy) InkLoader(size = InkLoaderSize.Button, color = LocalContentColor.current)
    }
}

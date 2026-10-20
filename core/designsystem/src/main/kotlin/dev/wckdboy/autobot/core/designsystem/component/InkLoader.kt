package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.motion.InkMath
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock

/**
 * [InkLoader] sizes: three pixels in a row inline in text (14 dp tall), in place of a button
 * label (16 dp tall), and a larger row (24 dp tall) on its own.
 */
enum class InkLoaderSize(internal val cols: Int, internal val rows: Int, internal val pitch: Dp, internal val height: Dp) {
    Inline(3, 1, 7.dp, 14.dp),
    Button(3, 1, 8.dp, 16.dp),
    Block(3, 1, 12.dp, 24.dp),
}

/** A loader pixel's side, as a fraction of its pitch. */
private const val LOADER_PIXEL = 0.55f

/**
 * Indeterminate work: three square pixels (the symbols' pixel, never a dot field) whose
 * strength travels along the row, one cycle per `loaderCycleMillis`. Drawn on the shared
 * active-tier clock, read only while drawing; still with reduced motion.
 */
@Composable
fun InkLoader(
    modifier: Modifier = Modifier,
    size: InkLoaderSize = InkLoaderSize.Inline,
    color: Color = MaterialTheme.colorScheme.primary,
    contentDescription: String? = "working",
) {
    val clock = rememberInkClock(true)
    Spacer(
        modifier
            .size(size.pitch * size.cols, size.height)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .drawWithCache {
                val pitch = this.size.width / size.cols
                val side = (pitch * LOADER_PIXEL).let { kotlin.math.round(it) }.coerceAtLeast(1f)
                val top = kotlin.math.round((this.size.height - side) / 2f)
                onDrawBehind {
                    val ms = clock.time * 1000f
                    for (c in 0 until size.cols) {
                        val x = kotlin.math.round(c * pitch + (pitch - side) / 2f)
                        drawRect(color, Offset(x, top), Size(side, side), alpha = InkMath.waveScale(InkMath.dotWave(c.toFloat(), ms)))
                    }
                }
            },
    )
}

/** A block loader with an optional [caption] in micro type below it. */
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

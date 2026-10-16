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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.motion.InkMath
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock

/**
 * [InkLoader] sizes: a row of five dots inline in text (14dp tall) or in place of a button label
 * (16dp tall), and a 7×3 block (24dp tall) whose wave runs diagonally.
 */
enum class InkLoaderSize(internal val cols: Int, internal val rows: Int, internal val pitch: Dp, internal val height: Dp) {
    Inline(5, 1, 4.5.dp, 14.dp),
    Button(5, 1, 5.dp, 16.dp),
    Block(7, 3, 8.dp, 24.dp),
}

/** A loader dot at full swell fills this much of its pitch. */
private const val LOADER_DOT = 0.42f

/**
 * Indeterminate work: the same travelling wave of swelling dots as a loading [InkProgressBar]
 * (one cycle per `loaderCycleMillis`, a `barWaveSlots` wavelength) — one visual language for
 * "working". Dot centres are cached per size; each frame only radii change, drawn as circles
 * on the shared active-tier clock, read only while drawing. Still dots with reduced motion.
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
                val pitch = size.pitch.toPx()
                val n = size.cols * size.rows
                val xs = FloatArray(n)
                val ys = FloatArray(n)
                val along = FloatArray(n)
                val top = (this.size.height - pitch * size.rows) / 2f
                for (r in 0 until size.rows) for (c in 0 until size.cols) {
                    val i = r * size.cols + c
                    xs[i] = (c + 0.5f) * pitch
                    ys[i] = top + (r + 0.5f) * pitch
                    // The block's wave runs along the diagonal; a row's along the row.
                    along[i] = (c + r).toFloat()
                }
                val full = pitch * LOADER_DOT
                onDrawBehind {
                    val ms = clock.time * 1000f
                    for (i in 0 until n) inkDot(color, xs[i], ys[i], full * InkMath.waveScale(InkMath.dotWave(along[i], ms)))
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

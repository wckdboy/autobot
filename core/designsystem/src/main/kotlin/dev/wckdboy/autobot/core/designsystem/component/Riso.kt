package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.R
import dev.wckdboy.autobot.core.designsystem.motion.FlowPhase
import dev.wckdboy.autobot.core.designsystem.motion.DotGeometry
import dev.wckdboy.autobot.core.designsystem.motion.InkMath
import dev.wckdboy.autobot.core.designsystem.motion.InkTween
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import java.util.Locale
import kotlin.math.max

/** Where a run executes. Shown as a tag on every card and row. */
enum class Route(val glyph: String, val label: String, val local: Boolean) {
    LOCAL_NPU("●", "local · npu", true),
    LOCAL_GPU("●", "local · gpu", true),
    LOCAL_CPU("●", "local · cpu", true),

    /** On this phone, before the engine has said where the model loaded (llama.cpp's "auto"). */
    LOCAL("●", "local", true),
    PC_LAN("◐", "pc · lan", false),
    CLOUD_API("○", "cloud · api", false),
    ;

    companion object {
        /** On this phone on [accelerator] ("npu", "gpu", "cpu"); [LOCAL] when it is not known. */
        fun local(accelerator: String?): Route = when (accelerator) {
            "npu" -> LOCAL_NPU
            "gpu" -> LOCAL_GPU
            "cpu" -> LOCAL_CPU
            else -> LOCAL
        }
    }
}

/** `● LOCAL · CPU` (filled red) / `◐ PC · LAN` / `○ CLOUD · API` (outlined). */
@Composable
fun RouteTag(route: Route, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    val base = if (route.local) {
        modifier.background(MaterialTheme.colorScheme.primary, shape)
    } else {
        modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape)
    }
    Text(
        "${route.glyph} ${route.label}".uppercase(Locale.ROOT),
        modifier = base.padding(horizontal = 6.dp, vertical = 3.dp),
        style = AutobotTheme.styles.micro,
        color = if (route.local) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}

/** Red `// SECTION · DETAIL` line. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        "// ${text.uppercase(Locale.ROOT)}",
        modifier = modifier,
        style = AutobotTheme.styles.micro,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Full-width red block button with a mono caps label; an inline ink loader replaces it while [busy]. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = { if (!busy) onClick() },
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.extraSmall,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) { InkBusyLabel(busy) { Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge) } }
}

/** Outlined block button; [accent] tints border and label (e.g. red for DENY); ink loader while [busy]. */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    OutlinedButton(
        onClick = { if (!busy) onClick() },
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, if (enabled) accent else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { InkBusyLabel(busy) { Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge) } }
}

/** Cream block button (APPROVE on red cards). */
@Composable
fun PaperButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.extraSmall,
        colors = ButtonDefaults.buttonColors(containerColor = AutobotColors.Bone100, contentColor = AutobotColors.PaperInk),
    ) { Text(text.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge) }
}

/** The brand mark (halftone "a" on red). */
@Composable
fun AutobotMark(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Image(painterResource(R.drawable.ic_autobot_mark), contentDescription = null, modifier = modifier.size(size))
}

/** Top bar: mark + condensed wordmark (or a title) and trailing actions. */
@Composable
fun AutobotTopBar(
    modifier: Modifier = Modifier,
    title: String = "AUTOBOT",
    showMark: Boolean = true,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 52.dp).padding(start = if (navigation == null) 16.dp else 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            navigation?.invoke()
            if (showMark && navigation == null) AutobotMark()
            Text(
                title.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

/** Small boxed counter (e.g. `16+` / running jobs). */
@Composable
fun CountBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 3.dp),
        style = AutobotTheme.styles.micro,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * A mode tile on the Run screen: `03` / big title / model / route tag. [living]: a quiet
 * [inkCard] field behind the content (ignored when there is [background] art).
 */
@Composable
fun ModeCard(
    index: String,
    title: String,
    subtitle: String,
    route: Route?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: (@Composable () -> Unit)? = null,
    living: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        val card = MaterialTheme.colorScheme.surfaceContainerHigh
        Box(if (living && background == null) Modifier.inkCard() else Modifier) {
            if (background != null || living) {
                background?.invoke()
                // Fade the art (or the living field) into the card colour so the title stays legible.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            if (background != null) {
                                // Art also fades under the index and route tag at the top: no plate cutting a hole in the dots.
                                Brush.verticalGradient(0f to card.copy(alpha = 0.85f), 0.2f to Color.Transparent, 0.45f to Color.Transparent, 0.78f to card.copy(alpha = 0.92f), 1f to card)
                            } else {
                                Brush.verticalGradient(0.45f to Color.Transparent, 0.78f to card.copy(alpha = 0.92f), 1f to card)
                            },
                        ),
                )
            }
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        index,
                        style = AutobotTheme.styles.micro,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    route?.let { RouteTag(it) }
                }
                Spacer(Modifier.weight(1f, fill = true))
                Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    subtitle,
                    style = AutobotTheme.styles.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Hairline-separated list row: title, mono subtitle, trailing slot. */
@Composable
fun RunRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Empty: the row carries no state worth a second line.
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            trailing()
        }
        Hairline()
    }
}

/** [HalftoneField] dots at full value fill this fraction of their pitch (as a radius). */
private const val HALFTONE_DOT = 0.6f

/** `STEPS` over a big condensed number. */
@Composable
fun BigReadout(label: String, value: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Column(
        modifier.then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MicroLabel(label)
        Text(value, style = AutobotTheme.styles.number, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

/**
 * Renders an image as halftone dots (riso print look): the image is sampled on a grid and each
 * cell becomes a dot whose radius follows the cell's darkness. The luma grid is computed once
 * per bitmap off the main thread; the dots are one path fill.
 */
@Composable
fun HalftoneImage(
    image: ImageBitmap,
    modifier: Modifier = Modifier,
    dot: Color = MaterialTheme.colorScheme.primary,
    paper: Color = Color.Transparent,
    cell: Dp = 6.dp,
) {
    val samples by rememberLumaGrid(image)
    val batch = remember { DotBatch() }
    Canvas(modifier.background(paper).clipToBounds()) {
        val grid = samples ?: return@Canvas
        val c = InkMath.cappedCell(size.width, size.height, cell.toPx())
        val cols = max(1, (size.width / c).toInt())
        val rows = max(1, (size.height / c).toInt())
        // Centred: the leftover fraction of a cell is split between both edges.
        val x0 = (size.width - cols * c) / 2f
        val y0 = (size.height - rows * c) / 2f
        batch.reset()
        for (r in 0 until rows) for (col in 0 until cols) {
            val l = grid.sample((col + 0.5f) / cols, (r + 0.5f) / rows)
            batch.dot(x0 + (col + 0.5f) * c, y0 + (r + 0.5f) * c, (1f - l) * c * 0.62f)
        }
        drawPath(batch.path, dot)
    }
}

/**
 * Procedural halftone field (hero art): dots swell along a soft diagonal wave. While [flowing]
 * the wave's phase advances (ink moving over a drum) at [speed] × `flowCyclesPerSecond`, on the
 * throttled, lifecycle-aware ink clock; [fill] scales the dots and eases to new values. Static
 * (as before) when not flowing or with reduced motion. With an [ambient] clock the hero drifts
 * at the ambient tier's pace instead (one cycle per `ambientCycleSeconds`, ≤10 fps).
 */
@Composable
fun HalftoneField(
    modifier: Modifier = Modifier,
    dot: Color = MaterialTheme.colorScheme.primary,
    cell: Dp = 9.dp,
    phase: Float = 0f,
    flowing: Boolean = false,
    fill: Float = 1f,
    speed: Float = 1f,
    ambient: InkAmbient? = null,
) {
    val reduced = LocalInkMotion.current.reduced
    val clock = rememberInkClock(flowing)
    val flow = remember { FlowPhase() }
    val fillTween = remember { InkTween(fill, Motion.developMillis / 1000f) }
    SideEffect {
        if (flowing && !reduced) fillTween.retarget(fill, Snapshot.withoutReadObservation { clock.time }) else fillTween.snap(fill)
    }
    val batch = remember { DotBatch() }
    Canvas(modifier.clipToBounds()) {
        val t = clock.time
        val p = phase + flow.at(t, speed) + (ambient?.let { InkMath.ambientPhase(it.time) } ?: 0f)
        val f = if (flowing) fillTween.value(t) else fill
        // A centred grid with whole dots half a pitch from every edge: no half-clipped column or row
        // along an edge (a hard seam where the art meets the card).
        val grid = DotGeometry.ground(size.width, size.height, InkMath.cappedCell(size.width, size.height, cell.toPx()), HALFTONE_DOT)
        val c = grid.x.pitch
        val cols = grid.x.count
        val rows = grid.y.count
        batch.reset()
        for (r in 0 until rows) for (col in 0 until cols) {
            val v = InkMath.field(col / cols.toFloat(), r / rows.toFloat(), p)
            batch.dot(grid.x.at(col), grid.y.at(r), v * c * HALFTONE_DOT * f)
        }
        drawPath(batch.path, dot)
    }
}

@Preview(name = "Riso kit", showBackground = true, backgroundColor = 0xFF0E0E0E, widthDp = 380)
@Composable
private fun RisoPreview() {
    AutobotTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AutobotTopBar(actions = { CountBadge("16+") })
            SectionLabel("this phone · cpu idle · 9.1 gb free")
            Text("Run anything.", style = MaterialTheme.typography.displayMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Route.entries.forEach { RouteTag(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeCard("02", "Chat", "Qwen3 4B · Q4", Route.LOCAL_CPU, {}, Modifier.weight(1f).height(120.dp))
                ModeCard("03", "Agent", "Claude Sonnet", Route.CLOUD_API, {}, Modifier.weight(1f).height(120.dp))
            }
            RunRow("Isometric greenhouse, riso print", "IMAGE · 12 S") { RouteTag(Route.LOCAL_CPU) }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigReadout("Steps", "4")
                BigReadout("Seed", "4182")
                BigReadout("Size", "768²")
            }
            PrimaryButton("Set up this phone", {}, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Deny", {}, Modifier.weight(1f), accent = MaterialTheme.colorScheme.primary)
                PaperButton("Approve", {}, Modifier.weight(1f))
            }
            HalftoneField(Modifier.fillMaxWidth().height(120.dp))
            Spacer(Modifier.width(1.dp))
        }
    }
}

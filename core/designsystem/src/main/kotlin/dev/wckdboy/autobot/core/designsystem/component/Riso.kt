package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

    /** Prompt on the NPU, reply generated on the CPU (no "local ·": the filled dot says it, and it fits a half-width card). */
    LOCAL_NPU_CPU("●", "npu+cpu", true),

    /** On this phone, before the engine has said where the model loaded (llama.cpp's "auto"). */
    LOCAL("●", "local", true),
    PC_LAN("◐", "pc · lan", false),
    CLOUD_API("○", "cloud · api", false),
    ;

    companion object {
        /** On this phone on [accelerator] ("npu", "gpu", "cpu", "npu+cpu"); [LOCAL] when it is not known. */
        fun local(accelerator: String?): Route = when (accelerator) {
            "npu" -> LOCAL_NPU
            "npu+cpu" -> LOCAL_NPU_CPU
            "gpu" -> LOCAL_GPU
            "cpu" -> LOCAL_CPU
            else -> LOCAL
        }
    }
}

/**
 * `● local · cpu` / `◐ pc · lan` / `○ cloud · api`: a quiet hairline tag; the glyph says where
 * (filled: on this phone). Not in the signal colour: where a run goes is information, not an alarm.
 */
@Composable
fun RouteTag(route: Route, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.extraSmall
    val base = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    Text(
        "${route.glyph} ${route.label}",
        modifier = base.padding(horizontal = 6.dp, vertical = 3.dp),
        style = AutobotTheme.styles.micro,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // A large font wraps the tag ("● LOCAL ·" / "NPU") rather than cutting its end.
        maxLines = linesFor(1, 3),
    )
}

/** A quiet `SECTION · DETAIL` caption in mono. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(Locale.ROOT),
        modifier = modifier,
        style = AutobotTheme.styles.micro,
        color = color,
        maxLines = linesFor(1, 3),
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

/**
 * Where a halftone field used to be: an empty, quiet area (BRAND.md: no textures, the pixel
 * symbols are the only ornament). Takes [HalftoneField]'s arguments so screens swap names only.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun QuietField(
    modifier: Modifier = Modifier,
    dot: Color = Color.Unspecified,
    cell: Dp = 9.dp,
    phase: Float = 0f,
    flowing: Boolean = false,
    fill: Float = 1f,
    speed: Float = 1f,
    ambient: InkAmbient? = null,
) {
    Spacer(modifier)
}

/** The products' symbols, as pixels: Autobot's a and Invincible's i (BRAND.md). */
val GlyphA = listOf(".####.", "....##", ".#####", "##..##", "##..##", ".#####")
val GlyphI = listOf("##", "..", "##", "##", "##", "##")

/** A pixel symbol [size] tall, in [color]: square cells, no smoothing. */
@Composable
fun PixelSymbol(rows: List<String>, color: Color, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    val cell = size / rows.size
    androidx.compose.foundation.Canvas(modifier.size(width = cell * rows[0].length, height = size)) {
        val c = this.size.height / rows.size
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch -> if (ch == '#') drawRect(color, Offset(x * c, y * c), Size(c + 0.5f, c + 0.5f)) }
        }
    }
}

/** Autobot's symbol, the pixel a, in its purple. */
@Composable
fun AutobotMark(modifier: Modifier = Modifier, size: Dp = 12.dp, color: Color = AutobotColors.Purple) {
    PixelSymbol(GlyphA, color, modifier, size)
}

/** Invincible's symbol, the pixel i, in its orange: on anything about the node. */
@Composable
fun NodeMark(modifier: Modifier = Modifier, size: Dp = 12.dp) {
    PixelSymbol(GlyphI, AutobotTheme.node, modifier, size)
}

/** The pixel a and the lowercase mono name: Autobot's wordmark. */
@Composable
fun Wordmark(name: String = "autobot", modifier: Modifier = Modifier, color: Color = AutobotColors.Purple) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AutobotMark(color = color, size = 14.dp)
        Text(name, style = WordmarkStyle, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

private val WordmarkStyle = TextStyle(
    fontFamily = dev.wckdboy.autobot.core.designsystem.theme.CodeFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 17.sp,
    letterSpacing = 0.sp,
)

/** Top bar: mark + condensed wordmark (or a title) and trailing actions. */
@Composable
fun AutobotTopBar(
    modifier: Modifier = Modifier,
    title: String = WORDMARK,
    showMark: Boolean = true,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val large = isLargeText()
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = if (navigation == null) 16.dp else 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                navigation?.invoke()
                // The home bar is the wordmark; every other bar is its title, in sentence case.
                if (title == WORDMARK) {
                    Wordmark(modifier = Modifier.weight(1f))
                    if (!large) actions()
                    return@Row
                }
                // Steps down (to [TOP_BAR_TITLE_MIN]) before it truncates: at 360 dp a bar with a few
                // actions (chat: voice, route, more) left room for "AG…" of "AGENT RUN".
                val titleStyle = MaterialTheme.typography.titleLarge
                Text(
                    title,
                    style = titleStyle,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = linesFor(1, 2),
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = TOP_BAR_TITLE_MIN, maxFontSize = titleStyle.fontSize, stepSize = 1.sp),
                    modifier = Modifier.weight(1f),
                )
                if (!large) actions()
            }
            // A large font leaves no room for the actions beside the title: they take a row of
            // their own under it, at the trailing edge, and wrap if they still do not fit.
            if (large) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) { actions() }
            }
        }
    }
}

/** The default top bar title: shows the wordmark instead. */
const val WORDMARK = "autobot"

/** The smallest the top bar's title steps down to before it truncates. */
private val TOP_BAR_TITLE_MIN = 14.sp

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
 * A mode tile on the Run screen: title, model, and where it runs. Flat: a hairline-free panel,
 * no art behind the words. [index] and [living] are kept for callers and no longer drawn;
 * [background] art (a recent image) sits above the text, never behind it.
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
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(14.dp)) {
            // Where it runs, at the top: the titles below line up across cards with or without one.
            route?.let {
                RouteTag(it)
                Spacer(Modifier.height(10.dp))
            }
            if (background != null) {
                Box(Modifier.fillMaxWidth().weight(1f, fill = true).padding(bottom = 12.dp)) { background() }
            } else {
                Spacer(Modifier.weight(1f, fill = true))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            // A model name wraps to a second line on a half-width card rather than losing its end.
            Text(
                subtitle,
                style = AutobotTheme.styles.micro,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = linesFor(2, 4),
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A small paper plate in [ground] behind a label over a dot field, 4dp past its bounds with soft
 * corners, so no dot sits behind text. Drawn behind the label; layout is unchanged.
 */
internal fun Modifier.inkClearance(ground: Color): Modifier = drawBehind {
    val pad = 4.dp.toPx()
    drawRoundRect(ground, Offset(-pad, -pad), Size(size.width + 2 * pad, size.height + 2 * pad), CornerRadius(pad))
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
    val large = isLargeText()
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .heightIn(min = 48.dp)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // A large font wraps the title and subtitle rather than losing their ends.
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = linesFor(1, 3), overflow = TextOverflow.Ellipsis)
                // Empty: the row carries no state worth a second line.
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = AutobotTheme.styles.micro, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = linesFor(1, 3), overflow = TextOverflow.Ellipsis)
                }
                // A large font moves the trailing tags under the text, where they have the row's width.
                if (large) Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
            if (!large) trailing()
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
    contentScale: ContentScale = ContentScale.Crop,
) {
    val samples by rememberLumaGrid(image)
    val batch = remember { DotBatch() }
    Canvas(modifier.background(paper).clipToBounds()) {
        val grid = samples ?: return@Canvas
        // Whole-pixel pitch, centred, no dot cut by the edges ([proofGrid]).
        val dots = proofGrid(cell.toPx())
        val c = dots.x.pitch
        // Cropped (or fitted) like an image, not stretched to the box.
        val art = contentRect(image.width.toFloat(), image.height.toFloat(), size.width, size.height, contentScale)
        batch.reset()
        for (r in 0 until dots.y.count) for (col in 0 until dots.x.count) {
            val cx = dots.x.at(col)
            val cy = dots.y.at(r)
            if (!art.contains(Offset(cx, cy))) continue
            val l = grid.sample((cx - art.left) / art.width, (cy - art.top) / art.height)
            batch.dot(cx, cy, (1f - l) * c * PROOF_DOT)
        }
        batch.draw(this, dot)
    }
}

/**
 * Procedural halftone field (hero art): dots swell along a soft diagonal wave. While [flowing]
 * the wave's phase advances (ink moving over a drum) at [speed] × `flowCyclesPerSecond`, on the
 * throttled, lifecycle-aware ink clock; [fill] scales the dots and eases to new values. Static
 * (as before) when not flowing or with reduced motion. With an [ambient] clock the hero drifts
 * at the ambient tier's pace instead (one cycle per `ambientCycleSeconds`, ≤5 fps).
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
        batch.draw(this, dot)
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

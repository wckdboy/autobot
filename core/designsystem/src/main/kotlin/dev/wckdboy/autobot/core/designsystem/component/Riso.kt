package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
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

/*
 * The retired riso kit: kept only for the screens not yet moved to Material 3 Expressive
 * (Imagine, Studio, the lock screens, the welcome). New and converted screens use the kit in
 * Standard*.kt; the brand marks (AutobotMark, NodeMark, Wordmark, ScreenTitle's pixel dot) live
 * here and stay.
 */

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

/** A quiet `Section · detail` caption in mono, in sentence case ([labelCase]). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        labelCase(text),
        modifier = modifier,
        style = AutobotTheme.styles.micro,
        color = color,
        maxLines = linesFor(1, 3),
        overflow = TextOverflow.Ellipsis,
    )
}

/** The screen's primary action: a filled purple block with a sentence-case mono label; a loader replaces it while [busy]. */
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
    ) { InkBusyLabel(busy) { Text(text, style = AutobotTheme.styles.action, maxLines = linesFor(1, 3), textAlign = TextAlign.Center) } }
}

/** Outlined block button: a hairline and an [accent] label (e.g. the error colour for Deny); loader while [busy]. */
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
        border = BorderStroke(1.dp, if (enabled && accent != MaterialTheme.colorScheme.onSurface) accent else MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) { InkBusyLabel(busy) { Text(text, style = AutobotTheme.styles.action, maxLines = linesFor(1, 3), textAlign = TextAlign.Center) } }
}

/** A sentence-case action label for a [androidx.compose.material3.TextButton]: mono, untracked. */
@Composable
fun ActionText(text: String, color: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Text(text, modifier, style = AutobotTheme.styles.action, color = color, maxLines = linesFor(1, 3))
}

/**
 * Where nothing has been made yet (an image canvas, a preview): a flat panel in
 * surfaceContainer with the pixel a, small and quiet, and an optional one-line [hint].
 */
@Composable
fun EmptyCanvas(modifier: Modifier = Modifier, hint: String? = null) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AutobotMark(Modifier.graphicsLayer { alpha = 0.45f }, size = 18.dp)
            if (hint != null) {
                Text(
                    hint,
                    Modifier.padding(horizontal = 16.dp),
                    style = AutobotTheme.styles.micro,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = linesFor(2, 4),
                )
            }
        }
    }
}

/**
 * An empty state: the pixel a in purple, one plain sentence, and at most one action.
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AutobotMark(size = 24.dp)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null && onAction != null) {
            GhostButton(action, onAction)
        }
    }
}

/**
 * [text] followed by the title mark: a small solid purple square, about 0.3 em, sitting on the
 * baseline 0.15 em after the last letter (as the sites' h1, "autobot▪"). Inline content, so it
 * wraps, ellipsizes and auto-sizes with the text.
 */
@Composable
fun ScreenTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = MaterialTheme.colorScheme.onBackground,
    mark: Boolean = true,
    maxLines: Int = linesFor(1, 3),
    autoSize: TextAutoSize? = null,
) {
    val content = if (mark) TitleMarkContent else emptyMap()
    val annotated = remember(text, mark) {
        buildAnnotatedString {
            append(text)
            // A zero-width alternate: screen readers and copies get the title alone.
            if (mark) appendInlineContent(TITLE_MARK, "\u200B")
        }
    }
    Text(
        annotated,
        // Read as the title alone, and as a heading.
        modifier = modifier.clearAndSetSemantics {
            this.text = AnnotatedString(text)
            heading()
        },
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        inlineContent = content,
        autoSize = autoSize,
    )
}

private const val TITLE_MARK = "autobot.titleMark"

/** The title mark's placeholder: 0.15 em of gap and a 0.3 em square, its bottom on the baseline. */
private val TitleMarkContent = mapOf(
    TITLE_MARK to InlineTextContent(Placeholder(0.45.em, 0.3.em, PlaceholderVerticalAlign.AboveBaseline)) {
        Canvas(Modifier.fillMaxSize()) {
            val side = size.height
            drawRect(AutobotColors.Purple, Offset(size.width - side, 0f), Size(side, side))
        }
    },
)

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
    /** End the title with the purple title mark (tab roots and main screens). */
    mark: Boolean = false,
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
                val titleStyle = if (mark) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge
                ScreenTitle(
                    title,
                    style = titleStyle,
                    mark = mark,
                    maxLines = linesFor(1, 2),
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

/** A top bar's back arrow (its [AutobotTopBar] `navigation`). */
@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
}

/** The default top bar title: shows the wordmark instead. */
const val WORDMARK = "autobot"

/** The smallest the top bar's title steps down to before it truncates. */
private val TOP_BAR_TITLE_MIN = 14.sp

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

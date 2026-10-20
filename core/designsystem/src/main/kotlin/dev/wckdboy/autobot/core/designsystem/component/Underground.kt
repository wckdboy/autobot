package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.motion.InkMath
import dev.wckdboy.autobot.core.designsystem.motion.LocalInkMotion
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkClock
import dev.wckdboy.autobot.core.designsystem.motion.rememberInkSlide
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.Motion
import java.util.Locale

/** Untracked mono caption in sentence case ([labelCase]): `Sampler`, `Seed`, `CFG`. */
@Composable
fun MicroLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** One line for labels; more for a sentence that must be read whole (hints, errors). */
    maxLines: Int = 1,
) {
    Text(
        text = labelCase(text),
        modifier = modifier,
        style = AutobotTheme.styles.micro,
        color = color,
        // A large font wraps a label to up to three lines rather than cutting its end.
        maxLines = if (isLargeText()) maxOf(maxLines, 3) else maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 1dp separator in the outline-variant colour. */
@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.outlineVariant) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

/** Draws a hairline along the top or bottom edge without affecting layout. */
@Composable
fun Modifier.hairlineEdge(top: Boolean = false, bottom: Boolean = true): Modifier {
    val color = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        val stroke = 1.dp.toPx()
        if (top) drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
        if (bottom) drawLine(color, Offset(0f, size.height - stroke / 2), Offset(size.width, size.height - stroke / 2), stroke)
    }
}

/**
 * Filled print block (no border). Optional [title] row renders as a micro-label header with
 * [trailing] content (e.g. a readout or toggle) separated from the body by a hairline.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: Color? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    contentPadding: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            if (title != null || trailing != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp)
                        .padding(horizontal = contentPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (accent != null) Box(Modifier.size(6.dp).background(accent))
                    if (title != null) MicroLabel(title, Modifier.weight(1f))
                    trailing?.invoke(this)
                }
                Hairline()
            }
            Column(Modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

/** Stacked label/value telemetry: `SEED` over `418229931`. */
@Composable
fun Readout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        MicroLabel(label)
        Text(value, style = AutobotTheme.styles.readout, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Square-cornered mono tag: a state, as written (`ready`, `no build`, `lora 0.80`), or a short
 * action in sentence case ([labelCase]: `Get`, `Open`). Tags are never in the signal: an [accent] of the scheme's primary reads
 * as onSurface on a hairline (the purple is for marks). A [filled] tag that can be tapped is a
 * toggle and shows as selected (onSurface over the 2 dp purple bar, like [ChoiceChip]); one that
 * cannot is a badge over a picture, on a translucent plate of the surface.
 */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    filled: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** Show [text] exactly as given (a user's own name for something), never re-cased. */
    asWritten: Boolean = false,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val scheme = MaterialTheme.colorScheme
    val ink = if (accent == scheme.primary) scheme.onSurface else accent
    val selected = filled && onClick != null
    val badge = filled && onClick == null
    val base = modifier
        .then(
            when {
                badge -> Modifier.background(scheme.surface.copy(alpha = 0.85f), shape)
                else -> Modifier.border(1.dp, if (ink == scheme.onSurfaceVariant || selected) scheme.outlineVariant else ink.copy(alpha = 0.55f), shape)
            },
        )
        .clip(shape)
        .selectionBar(selected)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 6.dp, vertical = 3.dp)
    val textColor = when {
        badge || selected -> scheme.onSurface
        else -> ink
    }
    Text(
        // A tag that can be tapped is a button: sentence case. A state stays as written.
        if (onClick != null && !asWritten) labelCase(text) else text,
        modifier = base,
        style = if (selected) AutobotTheme.styles.micro.copy(fontWeight = FontWeight.Medium) else AutobotTheme.styles.micro,
        color = textColor,
        maxLines = 1,
    )
}

/**
 * Status dot; pulses (`statusDotPulseMillis`, down to `statusDotPulseMinAlpha`) while [live], on
 * the throttled ink clock, so it stops off screen, in the background and with reduced motion.
 */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, live: Boolean = false, size: Dp = 7.dp) {
    val clock = rememberInkClock(live)
    Box(
        modifier
            .size(size)
            .graphicsLayer { alpha = if (live) InkMath.pulseAlpha(clock.time * 1000f) else 1f }
            .background(color, CircleShape),
    )
}

/**
 * Labelled slider with a live mono readout: `CFG ············ 7.0`.
 *
 * @param steps discrete steps between the ends (as in [Slider]); 0 for continuous.
 */
@Composable
fun ValueSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    format: (Float) -> String = { String.format(Locale.ROOT, "%.2f", it) },
) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MicroLabel(label, Modifier.weight(1f))
            Text(format(value), style = AutobotTheme.styles.readout, color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(28.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
}

/**
 * Single-select segmented row: `[ txt2img | img2img | inpaint ]` in a hairline frame. The
 * selected option reads in onSurface (medium) over a 2 dp purple bar (as [selectionBar]); the
 * others in onSurfaceVariant. The bar slides to a new option (ease-out, `transitionMillis`).
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    val slide = rememberInkSlide(options.indexOf(selected).coerceAtLeast(0))
    val bar = AutobotColors.Purple
    val n = options.size.coerceAtLeast(1)
    Row(
        modifier = modifier
            // As tall as the tallest label: at a large font size a label wraps to a second line
            // instead of being cut off by a fixed height.
            .height(IntrinsicSize.Min)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clip(shape)
            .drawBehind {
                val divider = 1.dp.toPx()
                val slot = (size.width - divider * (n - 1)) / n
                val h = SelectionBarHeight.toPx()
                val inset = SelectionBarInset.toPx().coerceAtMost(slot / 4f)
                drawRect(bar, Offset(slide.position * (slot + divider) + inset, size.height - h), Size(slot - 2 * inset, h))
            }
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 12.dp).background(MaterialTheme.colorScheme.outlineVariant))
            val text by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                tween(if (LocalInkMotion.current.reduced) 0 else Motion.transitionMillis),
                label = "segment",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 48 dp: the smallest touch target Android's accessibility guidance allows.
                    .heightIn(min = 48.dp)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) })
                    .padding(horizontal = 2.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    labelCase(label(option)),
                    style = AutobotTheme.styles.micro.copy(fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal),
                    color = text,
                    textAlign = TextAlign.Center,
                    maxLines = linesFor(2, 4),
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The selection bar under a selected segment, chip or tab: 2 dp of the mark's purple. */
val SelectionBarHeight = 2.dp

/** The bar's inset from its segment's sides. */
private val SelectionBarInset = 12.dp

/** Draws the 2 dp purple selection bar along the bottom edge while [selected] (layout unchanged). */
fun Modifier.selectionBar(selected: Boolean, inset: Dp = 0.dp): Modifier = if (!selected) this else drawBehind {
    val h = SelectionBarHeight.toPx()
    val i = inset.toPx()
    drawRect(AutobotColors.Purple, Offset(i, size.height - h), Size(size.width - 2 * i, h))
}

/**
 * A choice in a row of chips (filters, styles, sub-tabs): a hairline-framed mono label. Selected
 * reads in onSurface (medium) over the 2 dp purple bar; unselected in onSurfaceVariant. The
 * touch target is 48 dp tall around a 32 dp chip.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** A second action on long press (e.g. a folder's menu). */
    onLongClick: (() -> Unit)? = null,
    /** Show [text] exactly as given (a user's own name for something), never re-cased. */
    asWritten: Boolean = false,
) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier
            .heightIn(min = 48.dp)
            .semantics { this.selected = selected; role = Role.Tab }
            .combinedClickable(enabled = enabled, onLongClick = onLongClick, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .clip(shape)
                .selectionBar(selected)
                .heightIn(min = 32.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (asWritten) text else labelCase(text),
                style = AutobotTheme.styles.micro.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
                color = when {
                    !enabled -> MaterialTheme.colorScheme.outline
                    selected -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = linesFor(1, 3),
            )
        }
    }
}

enum class BlockStatus { PENDING, RUNNING, OK, ERROR, DENIED }

@Composable
private fun BlockStatus.color(): Color = when (this) {
    BlockStatus.PENDING -> AutobotTheme.inks.amber
    BlockStatus.RUNNING -> AutobotColors.Purple
    BlockStatus.OK -> MaterialTheme.colorScheme.onSurfaceVariant
    BlockStatus.ERROR -> AutobotTheme.inks.signal
    BlockStatus.DENIED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Terminal-style block for tool invocations: a header line (`▸ fs.read  path=notes.md`) with a
 * status dot, and an optional collapsible mono body with the output.
 */
@Composable
fun TerminalBlock(
    title: String,
    status: BlockStatus,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    body: String? = null,
    expanded: Boolean = false,
    onToggle: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onToggle != null) Modifier.clickable(role = Role.Button, onClick = onToggle) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(status.color(), live = status == BlockStatus.RUNNING || status == BlockStatus.PENDING)
                Text(
                    "▸ $title",
                    style = AutobotTheme.styles.code,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        modifier = Modifier.weight(1f),
                        style = AutobotTheme.styles.codeBlock,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Box(Modifier.weight(1f))
                }
                MicroLabel(status.name.lowercase(Locale.ROOT), color = if (status == BlockStatus.ERROR) status.color() else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded && !body.isNullOrEmpty()) {
                Hairline()
                VerbatimText(body, AutobotTheme.styles.codeBlock, Modifier.padding(10.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actions != null) {
                Hairline()
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

@Preview(name = "Underground kit", showBackground = true, backgroundColor = 0xFF09090B, widthDp = 380)
@Composable
private fun UndergroundPreview() {
    AutobotTheme {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(title = "Sampler", trailing = { Tag("dpm++ 2m") }) {
                ValueSlider("Steps", 24f, {}, 1f..60f, steps = 58, format = { it.toInt().toString() })
                ValueSlider("CFG", 7f, {}, 1f..20f)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Readout("Seed", "418229931")
                    Readout("Size", "512×768")
                    Readout("Engine", "NPU", valueColor = AutobotTheme.inks.uv)
                }
            }
            Segmented(listOf("txt2img", "img2img", "inpaint"), "img2img", {}, { it }, Modifier.fillMaxWidth())
            TerminalBlock("fs.read", BlockStatus.OK, subtitle = "path=notes.md", body = "# Notes\n- item", expanded = true)
            TerminalBlock("shell.exec", BlockStatus.PENDING, subtitle = "rm -rf build/")
        }
    }
}

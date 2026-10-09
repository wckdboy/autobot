package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import java.util.Locale

/** Uppercase, tracked mono caption: `// SAMPLER`, `CTX`, `SEED`. */
@Composable
fun MicroLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text.uppercase(Locale.ROOT),
        modifier = modifier,
        style = AutobotTheme.styles.micro,
        color = color,
        maxLines = 1,
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

/** Square-cornered tag, e.g. `[TOOLS]`, `LORA 0.80`. */
@Composable
fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    filled: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.extraSmall
    val base = modifier
        .then(if (filled) Modifier.background(accent, shape) else Modifier.border(1.dp, accent.copy(alpha = 0.55f), shape))
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .padding(horizontal = 6.dp, vertical = 3.dp)
    // Filled tags are solid blocks; pick ink or bone text for contrast.
    val textColor = if (!filled) accent else if (accent.luminance() > 0.45f) AutobotColors.PaperInk else AutobotColors.Bone100
    Text(text.uppercase(Locale.ROOT), modifier = base, style = AutobotTheme.styles.micro, color = textColor, maxLines = 1)
}

/** Status dot; pulses while [live]. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, live: Boolean = false, size: Dp = 7.dp) {
    val alpha = if (live) {
        val transition = rememberInfiniteTransition(label = "dot")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "dotAlpha",
        )
        a
    } else {
        1f
    }
    Box(modifier.size(size).alpha(alpha).background(color, CircleShape))
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
            Text(format(value), style = AutobotTheme.styles.readout, color = MaterialTheme.colorScheme.primary)
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

/** Single-select segmented row: `[ TXT2IMG | IMG2IMG | INPAINT ]`. */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    Row(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            if (index > 0) Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option).uppercase(Locale.ROOT),
                    style = AutobotTheme.styles.micro,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

enum class BlockStatus { PENDING, RUNNING, OK, ERROR, DENIED }

private fun BlockStatus.color(): Color = when (this) {
    BlockStatus.PENDING -> AutobotColors.Amber
    BlockStatus.RUNNING -> AutobotColors.Uv
    BlockStatus.OK -> AutobotColors.Acid
    BlockStatus.ERROR -> AutobotColors.Signal
    BlockStatus.DENIED -> AutobotColors.Ash400
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
                MicroLabel(status.name, color = status.color())
            }
            if (expanded && !body.isNullOrEmpty()) {
                Hairline()
                Text(
                    body,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                    style = AutobotTheme.styles.codeBlock,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false,
                )
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
            Panel(title = "Sampler", accent = AutobotColors.Acid, trailing = { Tag("DPM++ 2M", accent = AutobotColors.Acid) }) {
                ValueSlider("Steps", 24f, {}, 1f..60f, steps = 58, format = { it.toInt().toString() })
                ValueSlider("CFG", 7f, {}, 1f..20f)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Readout("Seed", "418229931")
                    Readout("Size", "512×768")
                    Readout("Engine", "NPU", valueColor = AutobotColors.Uv)
                }
            }
            Segmented(listOf("txt2img", "img2img", "inpaint"), "img2img", {}, { it }, Modifier.fillMaxWidth())
            TerminalBlock("fs.read", BlockStatus.OK, subtitle = "path=notes.md", body = "# Notes\n- item", expanded = true)
            TerminalBlock("shell.exec", BlockStatus.PENDING, subtitle = "rm -rf build/")
        }
    }
}

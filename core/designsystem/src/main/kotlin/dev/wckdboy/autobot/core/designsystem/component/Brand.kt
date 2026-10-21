package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.CodeFontFamily

/*
 * The brand on top of Material (BRAND.md): the pixel symbols (Autobot's a, Invincible's i), the
 * wordmark, and the purple pixel dot after a top-level screen's title ([ScreenTitle], used by
 * [TitleWithDot]). Plus [Route], where a run goes, shown by [RouteChip].
 */

/** Where a run executes. Shown as a chip on cards and rows ([RouteChip]). */
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
 * [text] followed by the title mark: a small solid purple square, about 0.3 em, sitting on the
 * baseline 0.15 em after the last letter (as the sites' h1, "autobot▪"). Inline content, so it
 * wraps, ellipsizes and auto-sizes with the text. Screens use it through [TitleWithDot].
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
            if (mark) appendInlineContent(TITLE_MARK, "​")
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
    Canvas(modifier.size(width = cell * rows[0].length, height = size)) {
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
    fontFamily = CodeFontFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 17.sp,
    letterSpacing = 0.sp,
)

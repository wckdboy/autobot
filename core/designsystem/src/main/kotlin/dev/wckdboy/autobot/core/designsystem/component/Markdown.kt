package dev.wckdboy.autobot.core.designsystem.component

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isUnspecified
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.wckdboy.autobot.core.designsystem.icon.AutobotIcons
import dev.wckdboy.autobot.core.designsystem.markdown.MdAlign
import dev.wckdboy.autobot.core.designsystem.markdown.MdBlock
import dev.wckdboy.autobot.core.designsystem.markdown.MdLinks
import dev.wckdboy.autobot.core.designsystem.markdown.MdListItem
import dev.wckdboy.autobot.core.designsystem.markdown.MdSpan
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.CodeFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Colors and styles the markdown is drawn with, resolved once per theme. */
@Immutable
internal data class MdStyle(
    val body: TextStyle,
    val text: Color,
    val muted: Color,
    val codeBackground: Color,
    val code: Color,
    val link: Color,
    val rule: Color,
    val accent: Color,
    /** Drawn on [accent] (a done task's check). */
    val onAccent: Color,
    /** A table's header row. */
    val header: Color,
)

/**
 * The markdown's colours on [container]: the surface's roles when it is the screen's surface
 * (Unspecified), else tones of the container's own content colour (a user bubble's
 * onSecondaryContainer), so text, inline code, rules and marks all read on that container.
 */
@Composable
internal fun rememberMdStyle(container: Color = Color.Unspecified): MdStyle {
    val scheme = MaterialTheme.colorScheme
    val body = MaterialTheme.typography.bodyLarge
    return remember(scheme, body, container) {
        if (container.isUnspecified) {
            MdStyle(
                body = body,
                text = scheme.onSurface,
                muted = scheme.onSurfaceVariant,
                // Inline code: a tonal run.
                codeBackground = scheme.secondaryContainer,
                code = scheme.onSecondaryContainer,
                // Links read as text, marked by their underline (colour is for marks, not words).
                link = scheme.onSurface,
                rule = scheme.outlineVariant,
                accent = scheme.primary,
                onAccent = scheme.onPrimary,
                header = scheme.surfaceContainerHigh,
            )
        } else {
            val text = scheme.contentColorFor(container).takeOrElse { scheme.onSurface }
            MdStyle(
                body = body,
                text = text,
                muted = text.copy(alpha = 0.78f),
                codeBackground = text.copy(alpha = 0.12f),
                code = text,
                link = text,
                rule = text.copy(alpha = 0.32f),
                accent = text,
                onAccent = container,
                header = text.copy(alpha = 0.08f),
            )
        }
    }
}

/**
 * Chat markdown blocks, compact: 8 dp between top-level blocks, 4 dp inside lists and quotes.
 * [onLink] gets a tapped link's URL (nothing opens it here; see [LinkPrompt]). [container] is the
 * colour the blocks sit on when it is not the screen's surface (a user bubble): the text takes
 * that container's content colour.
 */
@Composable
fun MarkdownBlocks(blocks: List<MdBlock>, onLink: (String) -> Unit, modifier: Modifier = Modifier, container: Color = Color.Unspecified) {
    val style = rememberMdStyle(container)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { MarkdownBlock(it, style, onLink, depth = 0) }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, style: MdStyle, onLink: (String) -> Unit, depth: Int) {
    when (block) {
        is MdBlock.Paragraph -> {
            val annotated = remember(block, style) { block.spans.toAnnotated(style, onLink) }
            Text(annotated, style = style.body, color = style.text)
        }
        is MdBlock.Heading -> {
            val typography = MaterialTheme.typography
            val (textStyle, color) = when (block.level) {
                1 -> typography.titleLarge to style.text
                2 -> typography.titleMedium.copy(fontWeight = FontWeight.SemiBold) to style.text
                3 -> typography.titleSmall.copy(fontWeight = FontWeight.SemiBold) to style.text
                else -> typography.titleSmall to style.muted
            }
            val annotated = remember(block, style) { block.spans.toAnnotated(style, onLink) }
            Text(
                annotated,
                style = textStyle,
                color = color,
                modifier = Modifier.padding(top = if (block.level <= 2) 4.dp else 2.dp).semantics { heading() },
            )
        }
        is MdBlock.CodeBlock -> CodeBlockView(block)
        is MdBlock.Quote -> {
            val quoted = style.copy(text = style.muted)
            Row(
                Modifier.fillMaxWidth().drawBehind {
                    drawRect(style.rule, size = Size(2.dp.toPx(), size.height))
                },
            ) {
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.blocks.forEach { MarkdownBlock(it, quoted, onLink, depth) }
                }
            }
        }
        is MdBlock.ListBlock -> ListView(block, style, onLink, depth)
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp), color = style.rule)
        is MdBlock.Table -> TableView(block, style, onLink)
    }
}

@Composable
private fun ListView(list: MdBlock.ListBlock, style: MdStyle, onLink: (String) -> Unit, depth: Int) {
    val lineHeight = with(LocalDensity.current) { style.body.lineHeight.toDp() }
    val last = list.start + list.items.size - 1
    val markerWidth: Dp = if (list.ordered) (last.toString().length * 8 + 12).dp else 16.dp
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.items.forEachIndexed { index, item ->
            Row {
                // A box as tall as the first line, so markers sit on it whatever the item holds.
                Box(Modifier.width(markerWidth).height(lineHeight), contentAlignment = if (list.ordered) Alignment.CenterStart else Alignment.Center) {
                    when {
                        item.task != null -> TaskBox(item.task, style)
                        list.ordered -> Text("${list.start + index}.", style = style.body.copy(fontFeatureSettings = "tnum"), color = style.muted)
                        else -> Box(
                            Modifier.padding(top = 1.dp, end = 4.dp).size(if (depth == 0) 5.dp else 4.dp).clip(CircleShape)
                                .background(if (depth == 0) style.accent else style.muted),
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ItemBlocks(item, style, onLink, depth)
                }
            }
        }
    }
}

@Composable
private fun ItemBlocks(item: MdListItem, style: MdStyle, onLink: (String) -> Unit, depth: Int) {
    item.blocks.forEach { MarkdownBlock(it, style, onLink, depth + 1) }
}

/** A task's box: an outline, filled with a check when done. */
@Composable
private fun TaskBox(done: Boolean, style: MdStyle) {
    Box(
        Modifier.padding(end = 4.dp).size(12.dp)
            .semantics { contentDescription = if (done) "done" else "to do" }
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                if (done) {
                    drawRect(style.accent)
                    val check = Path().apply {
                        moveTo(size.width * 0.22f, size.height * 0.52f)
                        lineTo(size.width * 0.42f, size.height * 0.72f)
                        lineTo(size.width * 0.78f, size.height * 0.3f)
                    }
                    drawPath(check, style.onAccent, style = Stroke(stroke))
                } else {
                    drawRect(style.muted, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                }
            },
    )
}

@Composable
private fun TableView(table: MdBlock.Table, style: MdStyle, onLink: (String) -> Unit) {
    val cellStyle = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")
    val headerStyle = cellStyle.copy(fontWeight = FontWeight.SemiBold)
    val headerBackground = style.header
    val shape = MaterialTheme.shapes.medium
    // Row edges in px, written by the layout and read when drawing the same frame.
    val edges = remember { arrayOf(IntArray(0)) }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Layout(
            content = {
                val rows = listOf(table.header) + table.rows
                rows.forEachIndexed { r, row ->
                    row.forEachIndexed { c, cell ->
                        val annotated = remember(cell, style) { cell.toAnnotated(style, onLink) }
                        Text(
                            annotated,
                            style = if (r == 0) headerStyle else cellStyle,
                            color = style.text,
                            textAlign = when (table.align.getOrNull(c)) {
                                MdAlign.CENTER -> TextAlign.Center
                                MdAlign.RIGHT -> TextAlign.End
                                else -> TextAlign.Start
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            },
            modifier = Modifier
                .clip(shape)
                .border(1.dp, style.rule, shape)
                .drawBehind {
                    val rows = edges[0]
                    if (rows.isEmpty()) return@drawBehind
                    drawRect(headerBackground, size = Size(size.width, rows[0].toFloat()))
                    for (r in 0 until rows.size - 1) {
                        drawRect(style.rule, topLeft = Offset(0f, rows[r].toFloat()), size = Size(size.width, 1.dp.toPx()))
                    }
                },
        ) { measurables, _ ->
            val columns = table.header.size.coerceAtLeast(1)
            val maxCell = 240.dp.roundToPx()
            val widths = IntArray(columns)
            measurables.forEachIndexed { i, m ->
                val c = i % columns
                widths[c] = maxOf(widths[c], m.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxCell))
            }
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i % columns])) }
            val rowCount = placeables.size / columns
            val bottoms = IntArray(rowCount)
            var y = 0
            for (r in 0 until rowCount) {
                var h = 0
                for (c in 0 until columns) h = maxOf(h, placeables[r * columns + c].height)
                y += h
                bottoms[r] = y
            }
            edges[0] = bottoms
            layout(widths.sum(), y) {
                var top = 0
                for (r in 0 until rowCount) {
                    var x = 0
                    for (c in 0 until columns) {
                        placeables[r * columns + c].place(x, top)
                        x += widths[c]
                    }
                    top = bottoms[r]
                }
            }
        }
    }
}

/**
 * A code block: a surfaceContainerHigh card in the medium shape, the language and a Copy text
 * button over the verbatim code (mono, scrolling sideways).
 */
@Composable
internal fun CodeBlockView(block: MdBlock.CodeBlock) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(Unit) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    block.language ?: "code",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (block.closed) {
                    TextButton(
                        onClick = {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("code", block.code))) }
                            copied = true
                        },
                    ) { Text(if (copied) "Copied" else "Copy") }
                }
            }
            VerbatimText(block.code, AutobotTheme.styles.codeBlock, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Copies a message as it was written (its markdown source), for the message footer. Text
 * selection inside the bubble copies the rendered text instead.
 */
@Composable
fun CopyMessageButton(markdown: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    if (copied) {
        LaunchedEffect(Unit) {
            delay(1500)
            copied = false
        }
    }
    IconButton(
        onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", markdown))) }
            copied = true
        },
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            AutobotIcons.Copy,
            contentDescription = if (copied) "Copied" else "Copy message",
            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Asks before a link leaves the app: the full URL and its host, a warning for plain `http`, and
 * the system browser only on "Open". Autobot never fetches, previews or prefetches a link.
 */
@Composable
fun LinkPrompt(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val insecure = MdLinks.isInsecure(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open link?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(MdLinks.host(url).ifEmpty { url }, style = MaterialTheme.typography.titleMedium)
                SelectionContainer {
                    Text(url, style = AutobotTheme.styles.code, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (insecure) {
                    Text(
                        "Not encrypted (http): the network can read or change this page.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "Opens in your browser, outside Autobot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: ActivityNotFoundException) {
                    // No browser or mail app: nothing to open it with.
                }
            }) { Text(if (url.startsWith("mailto:", ignoreCase = true)) "Write email" else "Open in browser") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Spans as styled text. Links are clickable annotations (exposed to accessibility as links). */
internal fun List<MdSpan>.toAnnotated(style: MdStyle, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    appendSpans(this@toAnnotated, style, onLink)
}

private fun AnnotatedString.Builder.appendSpans(spans: List<MdSpan>, style: MdStyle, onLink: (String) -> Unit) {
    for (span in spans) {
        when (span) {
            is MdSpan.Text -> append(span.text)
            is MdSpan.InlineCode -> withStyle(SpanStyle(fontFamily = CodeFontFamily, fontSize = MONO_SCALE.em, background = style.codeBackground, color = style.code)) {
                append(span.code)
            }
            is MdSpan.Math -> withStyle(SpanStyle(fontFamily = CodeFontFamily, fontSize = MONO_SCALE.em, background = style.codeBackground, color = style.code)) {
                append(span.tex)
            }
            is MdSpan.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendSpans(span.children, style, onLink) }
            is MdSpan.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendSpans(span.children, style, onLink) }
            is MdSpan.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendSpans(span.children, style, onLink) }
            is MdSpan.Link -> {
                val url = span.url
                withLink(
                    LinkAnnotation.Clickable(
                        tag = url,
                        styles = TextLinkStyles(SpanStyle(color = style.link, textDecoration = TextDecoration.Underline)),
                        linkInteractionListener = { onLink(url) },
                    ),
                ) {
                    if (span.children.isEmpty()) append(url) else appendSpans(span.children, style, onLink)
                }
                // Plain http is marked where it is read, not only in the prompt.
                if (MdLinks.isInsecure(url)) withStyle(SpanStyle(color = style.muted, fontFamily = CodeFontFamily, fontSize = MONO_SCALE.em)) { append(" (http)") }
            }
            is MdSpan.Image -> withStyle(SpanStyle(color = style.muted, fontFamily = CodeFontFamily, fontSize = MONO_SCALE.em, background = style.codeBackground)) {
                // Never loaded: the alt text and where it would have come from.
                val host = MdLinks.host(span.url)
                append("[image")
                if (span.alt.isNotEmpty()) append(": ${span.alt}")
                if (host.isNotEmpty()) append(" · $host")
                append("]")
            }
        }
    }
}

/** Mono runs inside body text: one point under the body size (15 sp), as on iOS. */
private const val MONO_SCALE = 14f / 15f

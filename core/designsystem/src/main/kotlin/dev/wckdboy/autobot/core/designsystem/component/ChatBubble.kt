package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.markdown.LiteMarkdown
import dev.wckdboy.autobot.core.designsystem.markdown.MdBlock
import dev.wckdboy.autobot.core.designsystem.markdown.MdSpan
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import dev.wckdboy.autobot.core.designsystem.theme.CodeFontFamily

enum class BubbleRole { USER, ASSISTANT }

/**
 * A transcript message. User input is an outlined, right-aligned block with a mono `›` prompt
 * marker; assistant output is unframed full-width text with lightweight markdown (paragraphs,
 * inline code, fenced code blocks).
 *
 * @param footer optional content under the message (e.g. token rate, model).
 */
@Composable
fun ChatBubble(
    role: BubbleRole,
    text: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
) {
    val blocks = remember(text) { LiteMarkdown.parse(text) }
    val isUser = role == BubbleRole.USER
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = if (isUser) Modifier.widthIn(max = 340.dp) else Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = if (isUser) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = if (isUser) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        ) {
            SelectionContainer {
                Row(Modifier.padding(horizontal = if (isUser) 12.dp else 2.dp, vertical = if (isUser) 10.dp else 4.dp)) {
                    if (isUser) {
                        Text(
                            "›",
                            modifier = Modifier.padding(end = 8.dp),
                            style = AutobotTheme.styles.code,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        blocks.forEach { block -> MarkdownBlock(block) }
                        if (isStreaming && blocks.isEmpty()) {
                            Text("▍", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        }
                        footer?.invoke()
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock) {
    when (block) {
        is MdBlock.Paragraph -> {
            val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
            val codeColor = MaterialTheme.colorScheme.tertiary
            val annotated = remember(block, codeBackground, codeColor) {
                block.toAnnotatedString(codeBackground, codeColor)
            }
            Text(annotated, style = MaterialTheme.typography.bodyLarge)
        }
        is MdBlock.CodeBlock -> CodeBlockView(block)
    }
}

private fun MdBlock.Paragraph.toAnnotatedString(codeBackground: Color, codeColor: Color): AnnotatedString =
    buildAnnotatedString {
        spans.forEach { span ->
            when (span) {
                is MdSpan.Text -> append(span.text)
                is MdSpan.InlineCode -> withStyle(
                    SpanStyle(fontFamily = CodeFontFamily, background = codeBackground, color = codeColor),
                ) { append(span.code) }
            }
        }
    }

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MicroLabel(block.language ?: "code", color = MaterialTheme.colorScheme.tertiary)
            }
            Hairline()
            Text(
                text = block.code,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
                style = AutobotTheme.styles.codeBlock,
                softWrap = false,
            )
        }
    }
}

@Preview(name = "ChatBubble", showBackground = true, backgroundColor = 0xFF09090B, widthDp = 380)
@Composable
private fun ChatBubblePreview() {
    AutobotTheme {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
            ChatBubble(BubbleRole.USER, "How do I reverse a list in Kotlin?")
            ChatBubble(
                BubbleRole.ASSISTANT,
                "Use `reversed()` for a new list, or `reverse()` on a `MutableList`.\n\n" +
                    "```kotlin\nval xs = listOf(1, 2, 3)\nprintln(xs.reversed())\n```\n\nThat's it.",
                footer = { TokenRateLabel(tokensPerSecond = 21.3f, totalTokens = 48) },
            )
        }
    }
}

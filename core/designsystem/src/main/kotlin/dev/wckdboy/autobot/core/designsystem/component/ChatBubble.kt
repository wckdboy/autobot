package dev.wckdboy.autobot.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.wckdboy.autobot.core.designsystem.markdown.LiteMarkdown
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme

enum class BubbleRole { USER, ASSISTANT }

/**
 * A transcript message. User input is an outlined, right-aligned block with a mono `›` prompt
 * marker; assistant output is unframed full-width text with chat markdown ([MarkdownBlocks]:
 * headings, lists, quotes, tables, code, emphasis, links). A tapped link only opens after the
 * [LinkPrompt] confirms it; nothing in a message is ever fetched.
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
    // While a reply streams, only its last block is parsed again (and earlier ones keep their
    // identity); the finished reply and history reuse earlier parses (LiteMarkdown.cached).
    val blocks = remember(text) { LiteMarkdown.cached(text).blocks }
    var link by remember { mutableStateOf<String?>(null) }
    val onLink = remember { { url: String -> link = url } }
    link?.let { LinkPrompt(it, onDismiss = { link = null }) }
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
                        MarkdownBlocks(blocks, onLink)
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

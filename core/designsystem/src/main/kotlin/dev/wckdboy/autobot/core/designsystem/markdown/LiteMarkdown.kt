package dev.wckdboy.autobot.core.designsystem.markdown

import androidx.compose.runtime.Immutable

/** A block of the lightweight markdown subset used in chat bubbles. */
@Immutable
sealed interface MdBlock {
    @Immutable
    data class Paragraph(val spans: List<MdSpan>) : MdBlock

    /** A fenced code block. [closed] is `false` while the closing fence has not streamed in yet. */
    @Immutable
    data class CodeBlock(val language: String?, val code: String, val closed: Boolean) : MdBlock
}

@Immutable
sealed interface MdSpan {
    @Immutable
    data class Text(val text: String) : MdSpan

    @Immutable
    data class InlineCode(val code: String) : MdSpan
}

/**
 * Tiny, allocation-light parser for exactly three constructs: paragraphs (separated by blank
 * lines), inline `code` and ``` fenced code blocks. Unterminated fences (common mid-stream) are
 * rendered as an open code block. Everything else is kept as literal text — no HTML, no links,
 * no images, so model output can never trigger network fetches.
 */
object LiteMarkdown {

    fun parse(text: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        val lines = text.replace("\r\n", "\n").split('\n')
        var i = 0

        fun flushParagraph() {
            val content = paragraph.toString().trim('\n')
            if (content.isNotBlank()) blocks += MdBlock.Paragraph(parseInline(content))
            paragraph.clear()
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith(FENCE)) {
                flushParagraph()
                val language = trimmed.removePrefix(FENCE).trim().takeIf { it.isNotEmpty() }
                val code = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith(FENCE)) {
                        closed = true
                        break
                    }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += MdBlock.CodeBlock(language, code.toString(), closed)
                i++
                continue
            }
            if (line.isBlank()) {
                flushParagraph()
            } else {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    fun parseInline(text: String): List<MdSpan> {
        val spans = mutableListOf<MdSpan>()
        var cursor = 0
        while (cursor < text.length) {
            val open = text.indexOf('`', cursor)
            if (open < 0) break
            val close = text.indexOf('`', open + 1)
            if (close < 0) break
            if (open > cursor) spans += MdSpan.Text(text.substring(cursor, open))
            val code = text.substring(open + 1, close)
            if (code.isEmpty()) spans += MdSpan.Text("``") else spans += MdSpan.InlineCode(code)
            cursor = close + 1
        }
        if (cursor < text.length) spans += MdSpan.Text(text.substring(cursor))
        return spans
    }

    private const val FENCE = "```"
}

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

    fun parse(text: String): List<MdBlock> = parseFrom(text.replace("\r\n", "\n"), 0).blocks

    /**
     * A parse that a longer text can continue from: while a reply streams in, only its last
     * block is parsed again (earlier blocks are final once a blank line or fence follows them),
     * and the unchanged blocks keep their identity, so the UI does not re-render them.
     */
    class Parsed internal constructor(internal val text: String, val blocks: List<MdBlock>, internal val lastBlockStart: Int)

    /** [text] parsed, reusing [previous] when [text] extends it (the streaming case). */
    fun parse(text: String, previous: Parsed?): Parsed {
        val normalized = text.replace("\r\n", "\n")
        if (previous != null && previous.text == normalized) return previous
        if (previous == null || previous.blocks.isEmpty() || !normalized.startsWith(previous.text)) {
            return parseFrom(normalized, 0).let { Parsed(normalized, it.blocks, it.lastStart) }
        }
        val tail = parseFrom(normalized, previous.lastBlockStart)
        return Parsed(normalized, previous.blocks.dropLast(1) + tail.blocks, tail.lastStart)
    }

    /** Recent parses, most recent last; guarded by itself. */
    private val recent = ArrayDeque<Parsed>()

    /**
     * [text] parsed through a small shared cache: a bubble that was already parsed (history
     * warmed off the main thread, a scrolled-back message) is not parsed again, and the final
     * reply continues from the last streamed frame's parse instead of starting over, so the
     * end of a reply costs one block's parse, not the whole answer's, in the frame it lands.
     */
    fun cached(text: String): Parsed {
        val normalized = text.replace("\r\n", "\n")
        val base = synchronized(recent) {
            recent.lastOrNull { it.text == normalized }?.let { hit ->
                recent.remove(hit)
                recent.addLast(hit)
                return hit
            }
            // The longest earlier parse this text extends (a streamed frame of the same reply).
            recent.filter { it.blocks.isNotEmpty() && normalized.startsWith(it.text) }.maxByOrNull { it.text.length }
        }
        val parsed = parse(normalized, base)
        synchronized(recent) {
            // A streamed frame is superseded by the longer text: keep one entry per reply.
            if (base != null && parsed !== base) recent.remove(base)
            recent.addLast(parsed)
            while (recent.size > MAX_CACHED) recent.removeFirst()
        }
        return parsed
    }

    private const val MAX_CACHED = 96

    private class Result(val blocks: List<MdBlock>, val lastStart: Int)

    /** Parses [text] from offset [from], which must be the start of a top-level block (or 0). */
    private fun parseFrom(text: String, from: Int): Result {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = StringBuilder()
        var paragraphStart = from
        var lastStart = from
        val lines = text.substring(from).split('\n')
        val starts = IntArray(lines.size)
        var offset = from
        for (k in lines.indices) {
            starts[k] = offset
            offset += lines[k].length + 1
        }
        var i = 0

        fun flushParagraph() {
            val content = paragraph.toString().trim('\n')
            if (content.isNotBlank()) {
                blocks += MdBlock.Paragraph(parseInline(content))
                lastStart = paragraphStart
            }
            paragraph.clear()
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith(FENCE)) {
                flushParagraph()
                val fenceStart = starts[i]
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
                lastStart = fenceStart
                i++
                continue
            }
            if (line.isBlank()) {
                flushParagraph()
            } else {
                if (paragraph.isEmpty()) paragraphStart = starts[i] else paragraph.append('\n')
                paragraph.append(line)
            }
            i++
        }
        flushParagraph()
        return Result(blocks, lastStart)
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

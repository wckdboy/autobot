package dev.wckdboy.autobot.core.designsystem.markdown

import androidx.compose.runtime.Immutable

/**
 * A block of chat markdown. The same tree (and the same parser rules) exists on iOS
 * (`ios/App/Design/LiteMarkdown.swift`); `shared/design/markdown-cases.json` holds both apps to it.
 */
@Immutable
sealed interface MdBlock {
    @Immutable
    data class Paragraph(val spans: List<MdSpan>) : MdBlock

    /** `#`–`######`. */
    @Immutable
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock

    /**
     * A fenced code block (``` or ~~~), or display math (`$$…$$`, [language] `math`). [closed] is
     * `false` while the closing fence has not streamed in yet.
     */
    @Immutable
    data class CodeBlock(val language: String?, val code: String, val closed: Boolean) : MdBlock

    @Immutable
    data class Quote(val blocks: List<MdBlock>) : MdBlock

    /** A bullet ([ordered] false) or numbered list starting at [start]. */
    @Immutable
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<MdListItem>) : MdBlock

    @Immutable
    data object Rule : MdBlock

    /** A GFM table; every row has as many cells as [header]. */
    @Immutable
    data class Table(val align: List<MdAlign>, val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock
}

/** A list item; [task] is null for a plain item, else whether its `[x]` box is checked. */
@Immutable
data class MdListItem(val task: Boolean?, val blocks: List<MdBlock>)

enum class MdAlign { NONE, LEFT, CENTER, RIGHT }

@Immutable
sealed interface MdSpan {
    @Immutable
    data class Text(val text: String) : MdSpan

    @Immutable
    data class InlineCode(val code: String) : MdSpan

    /** `$…$` / `$$…$$` inside a paragraph, shown as code (there is no math engine). */
    @Immutable
    data class Math(val tex: String) : MdSpan

    @Immutable
    data class Strong(val children: List<MdSpan>) : MdSpan

    @Immutable
    data class Emphasis(val children: List<MdSpan>) : MdSpan

    @Immutable
    data class Strike(val children: List<MdSpan>) : MdSpan

    /** Only `http`, `https` and `mailto` links exist; nothing is fetched until the user opens one. */
    @Immutable
    data class Link(val url: String, val children: List<MdSpan>) : MdSpan

    /** `![alt](url)`: never loaded, shown as its alt text and the host. */
    @Immutable
    data class Image(val alt: String, val url: String) : MdSpan
}

/**
 * Chat markdown: headings, paragraphs, lists (nested, numbered, `- [ ]` tasks), block quotes,
 * rules, ``` fences, `$$` math and GFM tables; inline **strong**, *emphasis*, ~~strike~~, `code`,
 * `$math$`, links and autolinks, with backslash escapes. It is private by construction: HTML is
 * literal text, images are never loaded (alt text and host only), and links are inert spans the
 * UI asks about before anything opens them. Nothing in a message can cause a network request.
 *
 * Built for streaming: unclosed emphasis, code or links stay literal until they close, an open
 * fence is an open code block, a table shows its header as soon as its first line is there, and
 * [parse] with a previous parse re-parses only the tail after the last stable block boundary.
 */
object LiteMarkdown {

    fun parse(text: String): List<MdBlock> = parseFrom(normalize(text), 0).blocks

    /**
     * A parse that a longer text can continue from: while a reply streams in, only its tail is
     * parsed again (from the last block that starts after a blank line on a finished line; the
     * blocks before it can no longer change), and the unchanged blocks keep their identity, so
     * the UI does not re-render them.
     */
    class Parsed internal constructor(
        internal val text: String,
        val blocks: List<MdBlock>,
        /** Where parsing resumes, and how many leading [blocks] end before it. */
        internal val resumeAt: Int,
        internal val kept: Int,
    )

    /** [text] parsed, reusing [previous] when [text] extends it (the streaming case). */
    fun parse(text: String, previous: Parsed?): Parsed {
        val normalized = normalize(text)
        if (previous != null && previous.text == normalized) return previous
        if (previous == null || !normalized.startsWith(previous.text)) {
            return parseFrom(normalized, 0).let { Parsed(normalized, it.blocks, it.resumeAt, it.kept) }
        }
        val tail = parseFrom(normalized, previous.resumeAt)
        val blocks = if (previous.kept == 0) tail.blocks else previous.blocks.subList(0, previous.kept) + tail.blocks
        return Parsed(normalized, blocks, tail.resumeAt, previous.kept + tail.kept)
    }

    /** Recent parses, most recent last; guarded by itself. */
    private val recent = ArrayDeque<Parsed>()

    /**
     * [text] parsed through a small shared cache: a bubble that was already parsed (history
     * warmed off the main thread, a scrolled-back message) is not parsed again, and the final
     * reply continues from the last streamed frame's parse instead of starting over.
     */
    fun cached(text: String): Parsed {
        val normalized = normalize(text)
        val base = synchronized(recent) {
            recent.lastOrNull { it.text == normalized }?.let { hit ->
                recent.remove(hit)
                recent.addLast(hit)
                return hit
            }
            // The longest earlier parse this text extends (a streamed frame of the same reply).
            recent.filter { normalized.startsWith(it.text) }.maxByOrNull { it.text.length }
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

    /** Forgets every cached parse (the app locks: chat text must not outlive it in memory). */
    fun clearCache() = synchronized(recent) { recent.clear() }

    private const val MAX_CACHED = 96

    /** Inline markdown of one paragraph's text. */
    fun parseInline(text: String): List<MdSpan> = Inline.parse(text, links = true)

    /** The text without markdown (what selection and accessibility read). */
    fun plainText(blocks: List<MdBlock>): String = MdPlain.text(blocks)

    /**
     * What read-aloud speaks for [markdown]: no symbols, code blocks as "code block.", links as
     * their text and bare URLs as "link"; headings, list items and table rows end with a full
     * stop so the voice pauses there. One line per spoken line.
     */
    fun speakable(markdown: String): String = MdPlain.speakable(parse(markdown))

    private fun normalize(text: String): String =
        if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n").replace('\r', '\n')

    private class Result(val blocks: List<MdBlock>, val resumeAt: Int, val kept: Int)

    /** Parses [text] from offset [from], which must be the start of a line where a block starts (or 0). */
    private fun parseFrom(text: String, from: Int): Result {
        val lines = ArrayList<String>()
        val starts = ArrayList<Int>()
        var at = from
        while (true) {
            val end = text.indexOf('\n', at)
            starts += at
            if (end < 0) {
                lines += text.substring(at)
                break
            }
            lines += text.substring(at, end)
            at = end + 1
        }
        val parser = BlockParser(lines, nested = false).run()
        val blocks = parser.blocks
        // The last block that starts after a blank line can be re-parsed alone: nothing after it
        // changes how the blocks before it end. Its first line must be finished when the block
        // before it is a list (an unfinished "2" may still become the list's next item, "2.").
        for (b in blocks.indices.reversed()) {
            if (b == 0) break
            val line = parser.starts[b]
            if (!isBlank(lines[line - 1])) continue
            if (line == lines.size - 1 && blocks[b - 1] is MdBlock.ListBlock) continue
            return Result(blocks, starts[line], b)
        }
        return Result(blocks, from, 0)
    }
}

// ---- Blocks -------------------------------------------------------------------------------

internal fun isSpace(c: Char) = c == ' ' || c == '\t'

internal fun isBlank(line: String): Boolean {
    for (c in line) if (!isSpace(c)) return false
    return true
}

internal fun trimSpaces(s: String): String {
    var a = 0
    var b = s.length
    while (a < b && isSpace(s[a])) a++
    while (b > a && isSpace(s[b - 1])) b--
    return s.substring(a, b)
}

private fun trimStartSpaces(s: String): String {
    var a = 0
    while (a < s.length && isSpace(s[a])) a++
    return s.substring(a)
}

/** Leading whitespace in columns (a tab advances to the next multiple of four). */
private fun indentOf(line: String): Int {
    var col = 0
    for (c in line) {
        when (c) {
            ' ' -> col++
            '\t' -> col += 4 - col % 4
            else -> return col
        }
    }
    return col
}

/** [line] without up to [n] columns of leading whitespace (a tab that crosses [n] leaves spaces). */
private fun stripIndent(line: String, n: Int): String {
    var col = 0
    var i = 0
    while (i < line.length && col < n) {
        when (line[i]) {
            ' ' -> {
                col++
                i++
            }
            '\t' -> {
                val w = 4 - col % 4
                if (col + w > n) return " ".repeat(col + w - n) + line.substring(i + 1)
                col += w
                i++
            }
            else -> return line.substring(i)
        }
    }
    return line.substring(i)
}

private class Fence(val char: Char, val length: Int, val info: String)

private class Marker(
    val bullet: Boolean,
    val number: Int,
    val indent: Int,
    val contentIndent: Int,
    val first: String,
    val empty: Boolean,
)

private class BlockParser(private val lines: List<String>, private val nested: Boolean) {
    val blocks = ArrayList<MdBlock>()

    /** The line each of [blocks] starts on. */
    val starts = ArrayList<Int>()

    fun run(): BlockParser {
        var i = 0
        while (i < lines.size) i = block(i)
        return this
    }

    private fun add(line: Int, block: MdBlock) {
        blocks += block
        starts += line
    }

    private fun block(i: Int): Int {
        val line = lines[i]
        if (isBlank(line)) return i + 1
        val indent = indentOf(line)
        if (indent <= 3) {
            val s = stripIndent(line, indent)
            fence(s)?.let { return fenced(i, indent, it) }
            if (s.startsWith("$$")) math(i, s)?.let { return it }
            val level = headingLevel(s)
            if (level > 0) {
                add(i, MdBlock.Heading(level, Inline.parse(headingText(s, level), links = true)))
                return i + 1
            }
            if (isRule(s)) {
                add(i, MdBlock.Rule)
                return i + 1
            }
            if (s.startsWith('>')) return quote(i)
            marker(line)?.let { return list(i, it) }
            if (tableStart(i, pending = true)) return table(i)
        }
        return paragraph(i)
    }

    /** Whether line [i] ends a paragraph (or a lazy list continuation) above it. */
    private fun interrupts(i: Int): Boolean {
        val line = lines[i]
        if (isBlank(line)) return true
        val indent = indentOf(line)
        if (indent > 3) return false
        val s = stripIndent(line, indent)
        if (fence(s) != null) return true
        if (s.startsWith("$$") && mathOpens(s)) return true
        if (headingLevel(s) > 0 || isRule(s) || s.startsWith('>')) return true
        val m = marker(line)
        // "1984. A year" must not start a list mid-paragraph at the top level.
        if (m != null && !m.empty && (m.bullet || m.number == 1 || nested)) return true
        return tableStart(i, pending = false)
    }

    // -- fences and math

    private fun fence(s: String): Fence? {
        if (s.isEmpty()) return null
        val c = s[0]
        if (c != '`' && c != '~') return null
        var n = 0
        while (n < s.length && s[n] == c) n++
        if (n < 3) return null
        val info = trimSpaces(s.substring(n))
        if (c == '`' && info.indexOf('`') >= 0) return null
        return Fence(c, n, info)
    }

    private fun closesFence(line: String, f: Fence): Boolean {
        val indent = indentOf(line)
        if (indent > 3) return false
        val s = stripIndent(line, indent)
        var n = 0
        while (n < s.length && s[n] == f.char) n++
        return n >= f.length && isBlank(s.substring(n))
    }

    private fun fenced(i: Int, indent: Int, f: Fence): Int {
        val code = StringBuilder()
        var j = i + 1
        var closed = false
        while (j < lines.size) {
            if (closesFence(lines[j], f)) {
                closed = true
                break
            }
            if (j > i + 1) code.append('\n')
            code.append(stripIndent(lines[j], indent))
            j++
        }
        var end = 0
        while (end < f.info.length && !isSpace(f.info[end])) end++
        val language = f.info.substring(0, end).takeIf { it.isNotEmpty() }
        add(i, MdBlock.CodeBlock(language, code.toString(), closed))
        return if (closed) j + 1 else lines.size
    }

    /** `$$` opens display math unless the line is `$$x$$ and more text` (inline math then). */
    private fun mathOpens(s: String): Boolean {
        val rest = s.substring(2)
        val close = rest.indexOf("$$")
        if (close < 0) return true
        return trimSpaces(rest.substring(0, close)).isNotEmpty() && isBlank(rest.substring(close + 2))
    }

    private fun math(i: Int, s: String): Int? {
        if (!mathOpens(s)) return null
        val rest = s.substring(2)
        val close = rest.indexOf("$$")
        if (close >= 0) {
            add(i, MdBlock.CodeBlock(MATH, trimSpaces(rest.substring(0, close)), closed = true))
            return i + 1
        }
        val body = ArrayList<String>()
        if (!isBlank(rest)) body += trimSpaces(rest)
        var j = i + 1
        var closed = false
        // A blank line ends unclosed math: a stray `$$` must not swallow the rest of the answer.
        while (j < lines.size && !isBlank(lines[j])) {
            val line = trimSpaces(lines[j])
            val at = line.indexOf("$$")
            if (at >= 0) {
                val head = trimSpaces(line.substring(0, at))
                if (head.isNotEmpty()) body += head
                closed = true
                j++
                break
            }
            body += line
            j++
        }
        add(i, MdBlock.CodeBlock(MATH, body.joinToString("\n"), closed))
        return j
    }

    // -- headings and rules

    private fun headingLevel(s: String): Int {
        var n = 0
        while (n < s.length && s[n] == '#') n++
        if (n == 0 || n > 6) return 0
        return if (n == s.length || isSpace(s[n])) n else 0
    }

    private fun headingText(s: String, level: Int): String {
        var t = trimSpaces(s.substring(level))
        var end = t.length
        while (end > 0 && t[end - 1] == '#') end--
        if (end < t.length && (end == 0 || isSpace(t[end - 1]))) t = trimSpaces(t.substring(0, end))
        return t
    }

    private fun isRule(s: String): Boolean {
        if (s.isEmpty()) return false
        val c = s[0]
        if (c != '-' && c != '*' && c != '_') return false
        var n = 0
        for (ch in s) {
            if (ch == c) n++ else if (!isSpace(ch)) return false
        }
        return n >= 3
    }

    // -- quotes

    private fun quote(i: Int): Int {
        val inner = ArrayList<String>()
        var j = i
        while (j < lines.size) {
            val line = lines[j]
            val indent = indentOf(line)
            if (indent > 3) break
            val s = stripIndent(line, indent)
            if (!s.startsWith('>')) break
            inner += stripIndent(s.substring(1), 1)
            j++
        }
        add(i, MdBlock.Quote(BlockParser(inner, nested = true).run().blocks))
        return j
    }

    // -- lists

    private fun marker(line: String): Marker? {
        val indent = indentOf(line)
        if (indent > 3) return null
        val s = stripIndent(line, indent)
        if (s.isEmpty()) return null
        val bullet: Boolean
        var number = 0
        var len: Int
        if (s[0] == '-' || s[0] == '+' || s[0] == '*') {
            bullet = true
            len = 1
        } else {
            len = 0
            while (len < s.length && len < 9 && s[len] in '0'..'9') len++
            if (len == 0 || len >= s.length || (s[len] != '.' && s[len] != ')')) return null
            number = s.substring(0, len).toInt()
            bullet = false
            len++
        }
        val after = s.substring(len)
        if (after.isNotEmpty() && !isSpace(after[0])) return null
        val empty = isBlank(after)
        val w = indentOf(after)
        return when {
            empty -> Marker(bullet, number, indent, indent + len + 1, "", true)
            w > 4 -> Marker(bullet, number, indent, indent + len + 1, stripIndent(after, 1), false)
            else -> Marker(bullet, number, indent, indent + len + w, stripIndent(after, w), false)
        }
    }

    private fun list(i: Int, first: Marker): Int {
        val items = ArrayList<MdListItem>()
        var m = first
        var j = i
        while (true) {
            val body = arrayListOf(m.first)
            var k = j + 1
            var blanks = 0
            var lastBlank = m.empty
            while (k < lines.size) {
                val line = lines[k]
                if (isBlank(line)) {
                    blanks++
                    k++
                    continue
                }
                val indent = indentOf(line)
                val nestedItem = indent > m.indent && indent < m.contentIndent && marker(stripIndent(line, indent)) != null
                if (indent >= m.contentIndent || nestedItem) {
                    repeat(blanks) { body += "" }
                    blanks = 0
                    body += stripIndent(line, if (nestedItem) indent else m.contentIndent)
                    lastBlank = false
                    k++
                    continue
                }
                // A lazy continuation line of the item's paragraph.
                if (blanks == 0 && !lastBlank && marker(line) == null && !interrupts(k)) {
                    body += trimStartSpaces(line)
                    k++
                    continue
                }
                break
            }
            items += item(body)
            if (k >= lines.size) {
                j = k
                break
            }
            val next = lines[k]
            val nm = marker(next)
            if (nm == null || nm.bullet != first.bullet || isRule(stripIndent(next, indentOf(next)))) {
                j = k
                break
            }
            m = nm
            j = k
        }
        add(i, MdBlock.ListBlock(!first.bullet, first.number, items))
        return j
    }

    private fun item(body: ArrayList<String>): MdListItem {
        val s = body[0]
        var task: Boolean? = null
        if (s.length >= 3 && s[0] == '[' && s[2] == ']' && (s[1] == ' ' || s[1] == 'x' || s[1] == 'X') &&
            (s.length == 3 || isSpace(s[3]))
        ) {
            task = s[1] != ' '
            body[0] = trimStartSpaces(s.substring(3))
        }
        return MdListItem(task, BlockParser(body, nested = true).run().blocks)
    }

    // -- tables

    /**
     * A table starts on [i] when it has a pipe and the next line is a delimiter row with as many
     * cells. [pending] (at a block start): a `|` line followed by nothing but blank lines, or by a
     * last line that is still becoming the delimiter row, is a table already (no flicker).
     */
    private fun tableStart(i: Int, pending: Boolean): Boolean {
        val line = lines[i]
        if (line.indexOf('|') < 0) return false
        if (i + 1 < lines.size) {
            val align = delimiterRow(lines[i + 1])
            if (align != null && align.size == splitRow(line).size) return true
        }
        return pending && pendingRows(i) >= 0
    }

    /** For a pending table at [i]: how many lines it has (1 or 2), or -1. */
    private fun pendingRows(i: Int): Int {
        if (!trimSpaces(lines[i]).startsWith('|')) return -1
        var last = lines.size - 1
        while (last > i && isBlank(lines[last])) last--
        if (last == i) return 1
        if (last != i + 1) return -1
        val d = trimSpaces(lines[i + 1])
        return if (d.all { it == '|' || it == ':' || it == '-' || isSpace(it) }) 2 else -1
    }

    private fun delimiterRow(line: String): List<MdAlign>? {
        if (line.indexOf('|') < 0) return null
        val cells = splitRow(line)
        val out = ArrayList<MdAlign>(cells.size)
        for (cell in cells) {
            if (cell.isEmpty()) return null
            val left = cell[0] == ':'
            val right = cell.length > 1 && cell[cell.length - 1] == ':'
            val dashes = cell.substring(if (left) 1 else 0, cell.length - if (right) 1 else 0)
            if (dashes.isEmpty() || dashes.any { it != '-' }) return null
            out += when {
                left && right -> MdAlign.CENTER
                right -> MdAlign.RIGHT
                left -> MdAlign.LEFT
                else -> MdAlign.NONE
            }
        }
        return out
    }

    private fun table(i: Int): Int {
        val header = splitRow(lines[i])
        val align: List<MdAlign>
        var j: Int
        val delimiter = if (i + 1 < lines.size) delimiterRow(lines[i + 1]) else null
        if (delimiter != null && delimiter.size == header.size) {
            align = delimiter
            j = i + 2
        } else {
            align = List(header.size) { MdAlign.NONE }
            j = i + pendingRows(i)
        }
        val rows = ArrayList<List<List<MdSpan>>>()
        while (j < lines.size) {
            val line = lines[j]
            if (isBlank(line) || line.indexOf('|') < 0) break
            val indent = indentOf(line)
            if (indent <= 3) {
                val s = stripIndent(line, indent)
                if (fence(s) != null || headingLevel(s) > 0 || s.startsWith('>')) break
            }
            val cells = splitRow(line)
            rows += List(header.size) { c -> cells.getOrNull(c)?.let(::cell) ?: emptyList() }
            j++
        }
        add(i, MdBlock.Table(align, header.map(::cell), rows))
        return j
    }

    private fun cell(text: String): List<MdSpan> = Inline.parse(text.replace("\\|", "|"), links = true)

    /** A row's cells, trimmed: split at pipes that are not escaped or inside a code span. */
    private fun splitRow(line: String): List<String> {
        var s = trimSpaces(line)
        if (s.startsWith('|')) s = s.substring(1)
        if (s.endsWith('|') && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val cells = ArrayList<String>()
        var start = 0
        var k = 0
        while (k < s.length) {
            val c = s[k]
            when {
                c == '\\' -> k += 2
                c == '`' -> {
                    var run = 0
                    while (k + run < s.length && s[k + run] == '`') run++
                    val close = Inline.closingBackticks(s, k + run, run)
                    k = if (close >= 0) close + run else k + run
                }
                c == '|' -> {
                    cells += trimSpaces(s.substring(start, k))
                    k++
                    start = k
                }
                else -> k++
            }
        }
        cells += trimSpaces(s.substring(minOf(start, s.length)))
        return cells
    }

    // -- paragraphs

    private fun paragraph(i: Int): Int {
        val text = StringBuilder(trimSpaces(lines[i]))
        var j = i + 1
        while (j < lines.size && !interrupts(j)) {
            text.append('\n').append(trimSpaces(lines[j]))
            j++
        }
        add(i, MdBlock.Paragraph(Inline.parse(text.toString(), links = true)))
        return j
    }

    private companion object {
        const val MATH = "math"
    }
}

package dev.wckdboy.autobot.core.designsystem.markdown

/**
 * Inline markdown: code spans, `$math$`, links, images, autolinks, backslash escapes, and
 * emphasis by CommonMark's delimiter-run rules (so `snake_case` and `2 * 3 * 4` stay text). An
 * unmatched delimiter stays literal, which is what a half-streamed `**bold` shows until it closes.
 * Characters are classified by ASCII only (anything else counts as a letter), exactly as the
 * Swift port does on UTF-8 bytes.
 */
internal object Inline {
    private sealed class Node

    private class TextNode(val text: String) : Node()

    /** A run of `*`, `_` or `~~` that may open or close emphasis; [count] is what is left of it. */
    private class Run(val char: Char, var count: Int, val length: Int, val canOpen: Boolean, val canClose: Boolean) : Node()

    private class Done(val span: MdSpan) : Node()

    fun parse(s: String, links: Boolean): List<MdSpan> {
        val nodes = ArrayList<Node>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                nodes += TextNode(text.toString())
                text.setLength(0)
            }
        }
        val n = s.length
        var i = 0
        while (i < n) {
            val c = s[i]
            when {
                c == '\\' -> {
                    if (i + 1 < n && isPunct(s[i + 1])) {
                        text.append(s[i + 1])
                        i += 2
                    } else if (i + 1 < n && s[i + 1] == '\n') {
                        text.append('\n')
                        i += 2
                    } else {
                        text.append(c)
                        i++
                    }
                }
                c == '`' -> {
                    val run = runLength(s, i, '`')
                    val close = closingBackticks(s, i + run, run)
                    if (close < 0) {
                        text.append(s, i, i + run)
                    } else {
                        flush()
                        nodes += Done(MdSpan.InlineCode(codeContent(s.substring(i + run, close))))
                        i = close
                    }
                    i += run
                }
                c == '$' -> {
                    val end = mathEnd(s, i)
                    if (end < 0) {
                        text.append(c)
                        i++
                    } else {
                        flush()
                        val display = s[i + 1] == '$'
                        val tex = if (display) trimWs(s.substring(i + 2, end - 2)) else s.substring(i + 1, end - 1)
                        nodes += Done(MdSpan.Math(tex))
                        i = end
                    }
                }
                c == '*' || c == '_' || c == '~' -> {
                    val run = runLength(s, i, c)
                    if (c == '~' && run != 2) {
                        text.append(s, i, i + run)
                    } else {
                        val before = if (i == 0) ' ' else s[i - 1]
                        val after = if (i + run >= n) ' ' else s[i + run]
                        val left = !isWs(after) && (!isPunct(after) || isWs(before) || isPunct(before))
                        val right = !isWs(before) && (!isPunct(before) || isWs(after) || isPunct(after))
                        val open = if (c == '_') left && (!right || isPunct(before)) else left
                        val close = if (c == '_') right && (!left || isPunct(after)) else right
                        if (open || close) {
                            flush()
                            nodes += Run(c, run, run, open, close)
                        } else {
                            text.append(s, i, i + run)
                        }
                    }
                    i += run
                }
                c == '!' && i + 1 < n && s[i + 1] == '[' -> {
                    val link = link(s, i + 1)
                    if (link == null) {
                        text.append(c)
                        i++
                    } else {
                        flush()
                        val alt = MdPlain.spans(parse(s.substring(i + 2, link.labelEnd), links = false), speech = false)
                        nodes += Done(MdSpan.Image(alt, link.url))
                        i = link.end
                    }
                }
                c == '[' && links -> {
                    val link = link(s, i)
                    if (link == null) {
                        text.append(c)
                        i++
                    } else {
                        flush()
                        val label = parse(s.substring(i + 1, link.labelEnd), links = false)
                        // Other schemes (javascript:, file:, intent:, relative paths) are not links.
                        if (isAllowed(link.url)) nodes += Done(MdSpan.Link(link.url, label)) else label.forEach { nodes += Done(it) }
                        i = link.end
                    }
                }
                c == '<' && links -> {
                    val end = s.indexOf('>', i + 1)
                    val inner = if (end < 0) null else s.substring(i + 1, end)
                    val url = when {
                        inner == null || inner.any { isWs(it) || it == '<' } -> null
                        isAllowed(inner) && isUri(inner) -> inner
                        isEmail(inner) -> "mailto:$inner"
                        else -> null
                    }
                    if (url == null) {
                        text.append(c)
                        i++
                    } else {
                        flush()
                        nodes += Done(MdSpan.Link(url, listOf(MdSpan.Text(inner!!))))
                        i = end + 1
                    }
                }
                // Not right after "](": a half-streamed [label](url must not flash as a bare link.
                c == 'h' && links && (i == 0 || isWs(s[i - 1]) || s[i - 1] in "*_~(") && !(i >= 2 && s[i - 1] == '(' && s[i - 2] == ']') -> {
                    val end = bareUrlEnd(s, i)
                    if (end < 0) {
                        text.append(c)
                        i++
                    } else {
                        flush()
                        val url = s.substring(i, end)
                        nodes += Done(MdSpan.Link(url, listOf(MdSpan.Text(url))))
                        i = end
                    }
                }
                else -> {
                    text.append(c)
                    i++
                }
            }
        }
        flush()
        emphasis(nodes)
        return spans(nodes)
    }

    /** CommonMark's "process emphasis", without the openers-bottom optimisation. */
    private fun emphasis(nodes: ArrayList<Node>) {
        var ci = 0
        while (ci < nodes.size) {
            val closer = nodes[ci] as? Run
            if (closer == null || !closer.canClose || closer.count == 0) {
                ci++
                continue
            }
            var oi = ci - 1
            var opener: Run? = null
            while (oi >= 0) {
                val o = nodes[oi] as? Run
                if (o != null && o.char == closer.char && o.canOpen && o.count > 0) {
                    val odd = closer.char != '~' && (o.canClose || closer.canOpen) &&
                        (o.length + closer.length) % 3 == 0 && !(o.length % 3 == 0 && closer.length % 3 == 0)
                    if (!odd) {
                        opener = o
                        break
                    }
                }
                oi--
            }
            if (opener == null) {
                ci++
                continue
            }
            val use = if (closer.char == '~' || (opener.count >= 2 && closer.count >= 2)) 2 else 1
            opener.count -= use
            closer.count -= use
            val range = nodes.subList(oi + 1, ci)
            val inner = spans(range)
            range.clear()
            nodes.add(
                oi + 1,
                Done(
                    when {
                        closer.char == '~' -> MdSpan.Strike(inner)
                        use == 2 -> MdSpan.Strong(inner)
                        else -> MdSpan.Emphasis(inner)
                    },
                ),
            )
            ci = oi + 2
            if (opener.count == 0) {
                nodes.removeAt(oi)
                ci--
            }
            if (closer.count == 0) nodes.removeAt(ci)
        }
    }

    /** Nodes as spans: leftover delimiters are text, and neighbouring text is one span. */
    private fun spans(nodes: List<Node>): List<MdSpan> {
        val out = ArrayList<MdSpan>(nodes.size)
        val text = StringBuilder()
        for (node in nodes) {
            when (node) {
                is TextNode -> text.append(node.text)
                is Run -> repeat(node.count) { text.append(node.char) }
                is Done -> {
                    val span = node.span
                    if (span is MdSpan.Text) {
                        text.append(span.text)
                    } else {
                        if (text.isNotEmpty()) out += MdSpan.Text(text.toString())
                        text.setLength(0)
                        out += span
                    }
                }
            }
        }
        if (text.isNotEmpty()) out += MdSpan.Text(text.toString())
        return out
    }

    private fun runLength(s: String, at: Int, c: Char): Int {
        var n = 0
        while (at + n < s.length && s[at + n] == c) n++
        return n
    }

    /** Start of the next run of exactly [run] backticks at or after [from], or -1. */
    fun closingBackticks(s: String, from: Int, run: Int): Int {
        var k = from
        while (k < s.length) {
            if (s[k] == '`') {
                val r = runLength(s, k, '`')
                if (r == run) return k
                k += r
            } else {
                k++
            }
        }
        return -1
    }

    private fun codeContent(raw: String): String {
        val s = raw.replace('\n', ' ')
        return if (s.length >= 2 && s[0] == ' ' && s[s.length - 1] == ' ' && s.any { it != ' ' }) s.substring(1, s.length - 1) else s
    }

    /**
     * End (exclusive) of math opening at [i], or -1. `$$…$$` closes at the next `$$`; `$…$` needs
     * no space inside either end and no digit after it, so "$5 and $10" stays text.
     */
    private fun mathEnd(s: String, i: Int): Int {
        val n = s.length
        if (i + 1 < n && s[i + 1] == '$') {
            val close = s.indexOf("$$", i + 2)
            return if (close < 0 || trimWs(s.substring(i + 2, close)).isEmpty()) -1 else close + 2
        }
        if (i + 1 >= n || isWs(s[i + 1])) return -1
        var j = i + 1
        while (j < n) {
            val c = s[j]
            if (c == '\\') {
                j += 2
                continue
            }
            if (c == '$' && !isWs(s[j - 1]) && (j + 1 >= n || s[j + 1] !in '0'..'9')) return j + 1
            j++
        }
        return -1
    }

    class LinkParts(val labelEnd: Int, val url: String, val end: Int)

    /** `[label](destination "title")` with `[` at [at], or null. */
    private fun link(s: String, at: Int): LinkParts? {
        val n = s.length
        var j = at + 1
        var depth = 0
        while (j < n) {
            val c = s[j]
            if (c == '\\') {
                j += 2
                continue
            }
            if (c == '`') {
                val run = runLength(s, j, '`')
                val close = closingBackticks(s, j + run, run)
                j = (if (close >= 0) close else j) + run
                continue
            }
            if (c == '[') {
                depth++
            } else if (c == ']') {
                if (depth == 0) break
                depth--
            }
            j++
        }
        if (j >= n) return null
        val labelEnd = j
        var k = j + 1
        if (k >= n || s[k] != '(') return null
        k++
        while (k < n && isWs(s[k])) k++
        val dest: String
        if (k < n && s[k] == '<') {
            var e = k + 1
            while (e < n && s[e] != '>' && s[e] != '<' && s[e] != '\n') e++
            if (e >= n || s[e] != '>') return null
            dest = s.substring(k + 1, e)
            k = e + 1
        } else {
            val start = k
            var parens = 0
            while (k < n) {
                val c = s[k]
                if (c == '\\' && k + 1 < n && isPunct(s[k + 1])) {
                    k += 2
                    continue
                }
                if (isWs(c)) break
                if (c == '(') {
                    parens++
                } else if (c == ')') {
                    if (parens == 0) break
                    parens--
                }
                k++
            }
            if (parens != 0) return null
            dest = s.substring(start, k)
        }
        while (k < n && isWs(s[k])) k++
        if (k < n && (s[k] == '"' || s[k] == '\'' || s[k] == '(')) {
            val closeChar = if (s[k] == '(') ')' else s[k]
            k++
            while (k < n && s[k] != closeChar) k += if (s[k] == '\\') 2 else 1
            if (k >= n) return null
            k++
            while (k < n && isWs(s[k])) k++
        }
        if (k >= n || s[k] != ')') return null
        return LinkParts(labelEnd, unescape(dest), k + 1)
    }

    /** End of a bare `http(s)://` URL at [i] (GFM's trailing-punctuation rules), or -1. */
    private fun bareUrlEnd(s: String, i: Int): Int {
        val scheme = when {
            s.startsWith("https://", i) -> 8
            s.startsWith("http://", i) -> 7
            else -> return -1
        }
        var j = i
        while (j < s.length && !isWs(s[j]) && s[j] != '<') j++
        while (j > i) {
            val c = s[j - 1]
            if (c in "?!.,:*_~'\"") {
                j--
            } else if (c == ')') {
                var open = 0
                var close = 0
                for (k in i until j) {
                    if (s[k] == '(') open++ else if (s[k] == ')') close++
                }
                if (close > open) j-- else break
            } else {
                break
            }
        }
        if (j - i <= scheme || !isAlnum(s[i + scheme])) return -1
        return j
    }

    private fun isAllowed(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:")
    }

    private fun isUri(s: String): Boolean {
        val colon = s.indexOf(':')
        if (colon < 2 || colon > 32 || !s[0].isAsciiLetter()) return false
        for (k in 1 until colon) {
            val c = s[k]
            if (!(isAlnum(c) || c == '+' || c == '.' || c == '-')) return false
        }
        return true
    }

    private fun isEmail(s: String): Boolean {
        val at = s.indexOf('@')
        if (at < 1 || at == s.length - 1) return false
        for (k in 0 until at) {
            val c = s[k]
            if (!(isAlnum(c) || c in ".!#$%&'*+/=?^_`{|}~-")) return false
        }
        if (!isAlnum(s[at + 1])) return false
        for (k in at + 1 until s.length) {
            val c = s[k]
            if (!(isAlnum(c) || c == '-' || c == '.')) return false
        }
        return true
    }

    private fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val out = StringBuilder(s.length)
        var k = 0
        while (k < s.length) {
            if (s[k] == '\\' && k + 1 < s.length && isPunct(s[k + 1])) k++
            out.append(s[k])
            k++
        }
        return out.toString()
    }

    private fun trimWs(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isWs(s[a])) a++
        while (b > a && isWs(s[b - 1])) b--
        return s.substring(a, b)
    }

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'

    private fun isAlnum(c: Char) = c.isAsciiLetter() || c in '0'..'9'

    private fun isWs(c: Char) = c == ' ' || c == '\t' || c == '\n'

    private fun isPunct(c: Char) = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
}

/** Markdown trees as plain text: for selection, accessibility and read-aloud. */
internal object MdPlain {
    fun spans(spans: List<MdSpan>, speech: Boolean): String = StringBuilder().also { append(it, spans, speech) }.toString()

    private fun append(out: StringBuilder, spans: List<MdSpan>, speech: Boolean) {
        for (span in spans) {
            when (span) {
                is MdSpan.Text -> out.append(span.text)
                is MdSpan.InlineCode -> out.append(span.code)
                is MdSpan.Math -> out.append(span.tex)
                is MdSpan.Strong -> append(out, span.children, speech)
                is MdSpan.Emphasis -> append(out, span.children, speech)
                is MdSpan.Strike -> append(out, span.children, speech)
                is MdSpan.Link -> when {
                    speech && isBare(span) -> out.append("link")
                    span.children.isEmpty() -> out.append(if (speech) "link" else span.url)
                    else -> append(out, span.children, speech)
                }
                is MdSpan.Image -> out.append(span.alt)
            }
        }
    }

    /** An autolink: its text is its URL. */
    fun isBare(link: MdSpan.Link): Boolean {
        val only = link.children.singleOrNull() as? MdSpan.Text ?: return false
        return only.text == link.url || "mailto:${only.text}" == link.url
    }

    fun text(blocks: List<MdBlock>): String = buildList { lines(blocks, this) }.joinToString("\n")

    private fun lines(blocks: List<MdBlock>, out: MutableList<String>) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> out += spans(block.spans, speech = false)
                is MdBlock.Heading -> out += spans(block.spans, speech = false)
                is MdBlock.CodeBlock -> out += block.code
                is MdBlock.Quote -> lines(block.blocks, out)
                is MdBlock.ListBlock -> block.items.forEach { lines(it.blocks, out) }
                MdBlock.Rule -> Unit
                is MdBlock.Table -> (listOf(block.header) + block.rows).forEach { row ->
                    out += row.joinToString("\t") { spans(it, speech = false) }
                }
            }
        }
    }

    fun speakable(blocks: List<MdBlock>): String = buildList { speak(blocks, this) }.joinToString("\n")

    private fun speak(blocks: List<MdBlock>, out: MutableList<String>) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> spans(block.spans, speech = true).split('\n').forEach { line ->
                    clean(line).takeIf { it.isNotEmpty() }?.let(out::add)
                }
                is MdBlock.Heading -> clean(spans(block.spans, speech = true)).takeIf { it.isNotEmpty() }?.let { out += stop(it) }
                is MdBlock.CodeBlock -> out += if (block.language == "math") "formula." else "code block."
                is MdBlock.Quote -> speak(block.blocks, out)
                is MdBlock.ListBlock -> block.items.forEach { item ->
                    // The item's first block ends with a stop (its sentence may wrap over lines).
                    val at = out.size
                    speak(item.blocks.take(1), out)
                    if (out.size > at) out[out.size - 1] = stop(out[out.size - 1])
                    speak(item.blocks.drop(1), out)
                }
                MdBlock.Rule -> Unit
                is MdBlock.Table -> (listOf(block.header) + block.rows).forEach { row ->
                    val cells = row.map { clean(spans(it, speech = true)) }.filter { it.isNotEmpty() }
                    if (cells.isNotEmpty()) out += stop(cells.joinToString(", "))
                }
            }
        }
    }

    private fun stop(line: String): String = if (line.last() in ".!?:;,…") line else "$line."

    /** One spoken line: no HTML tags, no stray `*`, `` ` `` or `~`, single spaces. */
    private fun clean(line: String): String {
        val out = StringBuilder(line.length)
        var space = false
        var k = 0
        while (k < line.length) {
            val c = line[k]
            if (c == '<') {
                val end = tagEnd(line, k)
                if (end > 0) {
                    k = end
                    continue
                }
            }
            when {
                c == '*' || c == '`' || c == '~' -> Unit
                c == ' ' || c == '\t' -> space = out.isNotEmpty()
                else -> {
                    if (space) out.append(' ')
                    space = false
                    out.append(c)
                }
            }
            k++
        }
        return out.toString()
    }

    /** End of an HTML tag (`<b>`, `</div>`, `<br/>`) at [at], or -1. */
    private fun tagEnd(s: String, at: Int): Int {
        var k = at + 1
        if (k < s.length && s[k] == '/') k++
        if (k >= s.length || !(s[k] in 'a'..'z' || s[k] in 'A'..'Z')) return -1
        val end = s.indexOf('>', k)
        return if (end < 0) -1 else end + 1
    }
}

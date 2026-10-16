import Foundation

/// A block of chat markdown. Port of `LiteMarkdown.kt`; `shared/design/markdown-cases.json` holds
/// both apps to the same trees.
indirect enum MdBlock: Equatable, Sendable {
    case paragraph([MdSpan])
    /// `#`–`######`.
    case heading(level: Int, spans: [MdSpan])
    /// A fenced code block (``` or ~~~), or display math (`$$…$$`, language `math`). `closed` is
    /// `false` while the closing fence has not streamed in yet.
    case codeBlock(language: String?, code: String, closed: Bool)
    case quote([MdBlock])
    /// A bullet (`ordered` false) or numbered list starting at `start`.
    case list(ordered: Bool, start: Int, items: [MdListItem])
    case rule
    case table(MdTable)
}

/// A list item; `task` is nil for a plain item, else whether its `[x]` box is checked.
struct MdListItem: Equatable, Sendable {
    let task: Bool?
    let blocks: [MdBlock]
}

/// A GFM table; every row has as many cells as `header`.
struct MdTable: Equatable, Sendable {
    let align: [MdAlign]
    let header: [[MdSpan]]
    let rows: [[[MdSpan]]]
}

enum MdAlign: String, Sendable { case none, left, center, right }

indirect enum MdSpan: Equatable, Sendable {
    case text(String)
    case inlineCode(String)
    /// `$…$` / `$$…$$` inside a paragraph, shown as code (there is no math engine).
    case math(String)
    case strong([MdSpan])
    case emphasis([MdSpan])
    case strike([MdSpan])
    /// Only `http`, `https` and `mailto` links exist; nothing is fetched until the user opens one.
    case link(url: String, [MdSpan])
    /// `![alt](url)`: never loaded, shown as its alt text and the host.
    case image(alt: String, url: String)
}

/**
 Chat markdown: headings, paragraphs, lists (nested, numbered, `- [ ]` tasks), block quotes,
 rules, ``` fences, `$$` math and GFM tables; inline **strong**, *emphasis*, ~~strike~~, `code`,
 `$math$`, links and autolinks, with backslash escapes. Private by construction: HTML is literal
 text, images are never loaded (alt text and host only), and links are inert until the user
 confirms one. Nothing in a message can cause a network request.

 Built for streaming: unclosed emphasis, code or links stay literal until they close, an open
 fence is an open code block, a table shows its header as soon as its first line is there, and
 `parse(_:previous:)` re-parses only the tail after the last stable block boundary.

 Port of `LiteMarkdown.kt`, on UTF-8 bytes: every syntax character is ASCII, and characters are
 classified by ASCII only on both platforms (anything else counts as a letter).
 */
enum LiteMarkdown {
    static func parse(_ text: String) -> [MdBlock] {
        parseFrom(normalize(text), base: 0).blocks
    }

    /// A parse a longer text can continue from (see `LiteMarkdown.kt`'s `Parsed`).
    struct Parsed: Sendable {
        let text: String
        let blocks: [MdBlock]
        /// Byte offset where parsing resumes, and how many leading `blocks` end before it.
        let resumeAt: Int
        let kept: Int
    }

    /**
     `text` parsed, reusing `previous` when `text` extends it (the streaming case). Per update
     only the tail after `previous.resumeAt` is copied and parsed; the whole-text checks (a
     carriage return? still the same reply?) are `memchr` / `memcmp`.
     */
    static func parse(_ text: String, previous: Parsed?) -> Parsed {
        var text = text
        text.makeContiguousUTF8()
        if Utf8.hasCR(text) {
            // Rare (pasted text): normalized once, then parsed as usual.
            return parse(String(decoding: normalize(text), as: UTF8.self), previous: previous)
        }
        if let previous, Utf8.isPrefix(previous.text, of: text) {
            if previous.text.utf8.count == text.utf8.count { return previous }
            let tail = parseFrom(Utf8.bytes(text, from: previous.resumeAt), base: previous.resumeAt)
            let blocks = previous.kept == 0 ? tail.blocks : Array(previous.blocks.prefix(previous.kept)) + tail.blocks
            return Parsed(text: text, blocks: blocks, resumeAt: tail.resumeAt, kept: previous.kept + tail.kept)
        }
        let r = parseFrom(Utf8.bytes(text, from: 0), base: 0)
        return Parsed(text: text, blocks: r.blocks, resumeAt: r.resumeAt, kept: r.kept)
    }

    /// Inline markdown of one paragraph's text.
    static func parseInline(_ text: String) -> [MdSpan] {
        MdInline.parse(Array(text.utf8), links: true)
    }

    /// The text without markdown (what accessibility reads).
    static func plainText(_ blocks: [MdBlock]) -> String { MdPlain.text(blocks) }

    /// What read-aloud speaks for `markdown` (see `LiteMarkdown.kt`'s `speakable`).
    static func speakable(_ markdown: String) -> String { MdPlain.speakable(parse(markdown)) }

    private static func normalize(_ text: String) -> [UInt8] {
        let utf8 = text.utf8
        guard utf8.contains(13) else { return Array(utf8) }
        var out: [UInt8] = []
        out.reserveCapacity(utf8.count)
        var it = utf8.makeIterator()
        var pending: UInt8? = it.next()
        while let c = pending {
            pending = it.next()
            if c == 13 {
                if pending == 10 { continue }
                out.append(10)
            } else {
                out.append(c)
            }
        }
        return out
    }

    private struct Result {
        let blocks: [MdBlock]
        let resumeAt: Int
        let kept: Int
    }

    /// Parses `text`, the bytes from offset `base` on, which must be the start of a line where a block starts (or 0).
    private static func parseFrom(_ text: [UInt8], base: Int) -> Result {
        var lines: [[UInt8]] = []
        var starts: [Int] = []
        var at = 0
        while true {
            starts.append(at)
            guard let end = text[at...].firstIndex(of: 10) else {
                lines.append(Array(text[at...]))
                break
            }
            lines.append(Array(text[at ..< end]))
            at = end + 1
        }
        let parser = MdBlockParser(lines, nested: false)
        parser.run()
        let blocks = parser.blocks
        // See `LiteMarkdown.kt`: resume at the last block after a blank line (on a finished line
        // when the block before it is a list).
        var b = blocks.count - 1
        while b > 0 {
            let line = parser.starts[b]
            if isBlank(lines[line - 1]) {
                if line == lines.count - 1, case .list = blocks[b - 1] {
                    b -= 1
                    continue
                }
                return Result(blocks: blocks, resumeAt: base + starts[line], kept: b)
            }
            b -= 1
        }
        return Result(blocks: blocks, resumeAt: base, kept: 0)
    }
}

/// Whole-text byte checks without walking the text byte by byte.
enum Utf8 {
    static func hasCR(_ s: String) -> Bool {
        s.utf8.withContiguousStorageIfAvailable { b in
            guard let base = b.baseAddress, b.count > 0 else { return false }
            return memchr(base, 13, b.count) != nil
        } ?? s.utf8.contains(13)
    }

    /// Whether `p` is a prefix of `s` (or equal to it).
    static func isPrefix(_ p: String, of s: String) -> Bool {
        let n = p.utf8.count
        guard n <= s.utf8.count else { return false }
        if n == 0 { return true }
        let fast: Bool?? = p.utf8.withContiguousStorageIfAvailable { pb in
            s.utf8.withContiguousStorageIfAvailable { sb in memcmp(pb.baseAddress!, sb.baseAddress!, n) == 0 }
        }
        if let fast, let fast { return fast }
        return s.utf8.starts(with: p.utf8)
    }

    static func bytes(_ s: String, from: Int) -> [UInt8] {
        s.utf8.withContiguousStorageIfAvailable { b in Array(UnsafeBufferPointer(rebasing: b[from...])) }
            ?? Array(s.utf8.dropFirst(from))
    }
}

// MARK: - Byte helpers

@inline(__always) func mdA(_ c: Unicode.Scalar) -> UInt8 { UInt8(ascii: c) }

@inline(__always) private func isSpace(_ c: UInt8) -> Bool { c == 32 || c == 9 }

private func isBlank(_ line: [UInt8]) -> Bool { line.allSatisfy(isSpace) }

private func trimSpaces(_ s: ArraySlice<UInt8>) -> [UInt8] {
    var a = s.startIndex
    var b = s.endIndex
    while a < b && isSpace(s[a]) { a += 1 }
    while b > a && isSpace(s[b - 1]) { b -= 1 }
    return Array(s[a ..< b])
}

private func trimSpaces(_ s: [UInt8]) -> [UInt8] { trimSpaces(s[...]) }

private func trimStartSpaces(_ s: [UInt8]) -> [UInt8] {
    var a = 0
    while a < s.count && isSpace(s[a]) { a += 1 }
    return Array(s[a...])
}

private func indentOf(_ line: [UInt8]) -> Int {
    var col = 0
    for c in line {
        if c == 32 { col += 1 } else if c == 9 { col += 4 - col % 4 } else { return col }
    }
    return col
}

private func stripIndent(_ line: [UInt8], _ n: Int) -> [UInt8] {
    var col = 0
    var i = 0
    while i < line.count && col < n {
        if line[i] == 32 {
            col += 1
            i += 1
        } else if line[i] == 9 {
            let w = 4 - col % 4
            if col + w > n { return [UInt8](repeating: 32, count: col + w - n) + line[(i + 1)...] }
            col += w
            i += 1
        } else {
            return Array(line[i...])
        }
    }
    return Array(line[i...])
}

private func hasPrefix(_ s: [UInt8], _ p: String, at: Int = 0) -> Bool {
    let u = Array(p.utf8)
    guard at + u.count <= s.count else { return false }
    for k in 0 ..< u.count where s[at + k] != u[k] { return false }
    return true
}

/// First index of `needle` (two bytes) at or after `from`, or -1.
private func indexOfPair(_ s: [UInt8], _ a: UInt8, _ b: UInt8, from: Int) -> Int {
    var k = max(from, 0)
    while k + 1 < s.count {
        if s[k] == a && s[k + 1] == b { return k }
        k += 1
    }
    return -1
}

@inline(__always) private func str(_ b: some Collection<UInt8>) -> String { String(decoding: b, as: UTF8.self) }

// MARK: - Blocks

private final class MdBlockParser {
    private let lines: [[UInt8]]
    private let nested: Bool
    var blocks: [MdBlock] = []
    /// The line each of `blocks` starts on.
    var starts: [Int] = []

    init(_ lines: [[UInt8]], nested: Bool) {
        self.lines = lines
        self.nested = nested
    }

    func run() {
        var i = 0
        while i < lines.count { i = block(i) }
    }

    static func parse(_ lines: [[UInt8]]) -> [MdBlock] {
        let p = MdBlockParser(lines, nested: true)
        p.run()
        return p.blocks
    }

    private func add(_ line: Int, _ block: MdBlock) {
        blocks.append(block)
        starts.append(line)
    }

    private func block(_ i: Int) -> Int {
        let line = lines[i]
        if isBlank(line) { return i + 1 }
        let indent = indentOf(line)
        if indent <= 3 {
            let s = stripIndent(line, indent)
            if let f = fence(s) { return fenced(i, indent, f) }
            if hasPrefix(s, "$$"), let end = math(i, s) { return end }
            let level = headingLevel(s)
            if level > 0 {
                add(i, .heading(level: level, spans: MdInline.parse(headingText(s, level), links: true)))
                return i + 1
            }
            if isRule(s) {
                add(i, .rule)
                return i + 1
            }
            if s.first == mdA(">") { return quote(i) }
            if let m = marker(line) { return list(i, m) }
            if tableStart(i, pending: true) { return table(i) }
        }
        return paragraph(i)
    }

    private func interrupts(_ i: Int) -> Bool {
        let line = lines[i]
        if isBlank(line) { return true }
        let indent = indentOf(line)
        if indent > 3 { return false }
        let s = stripIndent(line, indent)
        if fence(s) != nil { return true }
        if hasPrefix(s, "$$") && mathOpens(s) { return true }
        if headingLevel(s) > 0 || isRule(s) || s.first == mdA(">") { return true }
        if let m = marker(line), !m.empty, m.bullet || m.number == 1 || nested { return true }
        return tableStart(i, pending: false)
    }

    // MARK: fences and math

    private struct Fence {
        let char: UInt8
        let length: Int
        let info: [UInt8]
    }

    private func fence(_ s: [UInt8]) -> Fence? {
        guard let c = s.first, c == mdA("`") || c == mdA("~") else { return nil }
        var n = 0
        while n < s.count && s[n] == c { n += 1 }
        if n < 3 { return nil }
        let info = trimSpaces(s[n...])
        if c == mdA("`") && info.contains(mdA("`")) { return nil }
        return Fence(char: c, length: n, info: info)
    }

    private func closesFence(_ line: [UInt8], _ f: Fence) -> Bool {
        let indent = indentOf(line)
        if indent > 3 { return false }
        let s = stripIndent(line, indent)
        var n = 0
        while n < s.count && s[n] == f.char { n += 1 }
        return n >= f.length && s[n...].allSatisfy(isSpace)
    }

    private func fenced(_ i: Int, _ indent: Int, _ f: Fence) -> Int {
        var code: [UInt8] = []
        var j = i + 1
        var closed = false
        while j < lines.count {
            if closesFence(lines[j], f) {
                closed = true
                break
            }
            if j > i + 1 { code.append(10) }
            code += stripIndent(lines[j], indent)
            j += 1
        }
        var end = 0
        while end < f.info.count && !isSpace(f.info[end]) { end += 1 }
        add(i, .codeBlock(language: end == 0 ? nil : str(f.info[..<end]), code: str(code), closed: closed))
        return closed ? j + 1 : lines.count
    }

    private func mathOpens(_ s: [UInt8]) -> Bool {
        let rest = Array(s[2...])
        let close = indexOfPair(rest, mdA("$"), mdA("$"), from: 0)
        if close < 0 { return true }
        return !trimSpaces(rest[..<close]).isEmpty && rest[(close + 2)...].allSatisfy(isSpace)
    }

    private func math(_ i: Int, _ s: [UInt8]) -> Int? {
        guard mathOpens(s) else { return nil }
        let rest = Array(s[2...])
        let close = indexOfPair(rest, mdA("$"), mdA("$"), from: 0)
        if close >= 0 {
            add(i, .codeBlock(language: "math", code: str(trimSpaces(rest[..<close])), closed: true))
            return i + 1
        }
        var body: [[UInt8]] = []
        if !isBlank(rest) { body.append(trimSpaces(rest)) }
        var j = i + 1
        var closed = false
        while j < lines.count && !isBlank(lines[j]) {
            let line = trimSpaces(lines[j])
            let at = indexOfPair(line, mdA("$"), mdA("$"), from: 0)
            if at >= 0 {
                let head = trimSpaces(line[..<at])
                if !head.isEmpty { body.append(head) }
                closed = true
                j += 1
                break
            }
            body.append(line)
            j += 1
        }
        add(i, .codeBlock(language: "math", code: body.map { str($0) }.joined(separator: "\n"), closed: closed))
        return j
    }

    // MARK: headings and rules

    private func headingLevel(_ s: [UInt8]) -> Int {
        var n = 0
        while n < s.count && s[n] == mdA("#") { n += 1 }
        if n == 0 || n > 6 { return 0 }
        return n == s.count || isSpace(s[n]) ? n : 0
    }

    private func headingText(_ s: [UInt8], _ level: Int) -> [UInt8] {
        var t = trimSpaces(s[level...])
        var end = t.count
        while end > 0 && t[end - 1] == mdA("#") { end -= 1 }
        if end < t.count && (end == 0 || isSpace(t[end - 1])) { t = trimSpaces(t[..<end]) }
        return t
    }

    private func isRule(_ s: [UInt8]) -> Bool {
        guard let c = s.first, c == mdA("-") || c == mdA("*") || c == mdA("_") else { return false }
        var n = 0
        for ch in s {
            if ch == c { n += 1 } else if !isSpace(ch) { return false }
        }
        return n >= 3
    }

    // MARK: quotes

    private func quote(_ i: Int) -> Int {
        var inner: [[UInt8]] = []
        var j = i
        while j < lines.count {
            let line = lines[j]
            let indent = indentOf(line)
            if indent > 3 { break }
            let s = stripIndent(line, indent)
            guard s.first == mdA(">") else { break }
            inner.append(stripIndent(Array(s[1...]), 1))
            j += 1
        }
        add(i, .quote(MdBlockParser.parse(inner)))
        return j
    }

    // MARK: lists

    private struct Marker {
        let bullet: Bool
        let number: Int
        let indent: Int
        let contentIndent: Int
        let first: [UInt8]
        let empty: Bool
    }

    private func marker(_ line: [UInt8]) -> Marker? {
        let indent = indentOf(line)
        if indent > 3 { return nil }
        let s = stripIndent(line, indent)
        guard let c0 = s.first else { return nil }
        let bullet: Bool
        var number = 0
        var len = 0
        if c0 == mdA("-") || c0 == mdA("+") || c0 == mdA("*") {
            bullet = true
            len = 1
        } else {
            while len < s.count && len < 9 && s[len] >= 48 && s[len] <= 57 { len += 1 }
            if len == 0 || len >= s.count || (s[len] != mdA(".") && s[len] != mdA(")")) { return nil }
            number = Int(str(s[..<len])) ?? 0
            bullet = false
            len += 1
        }
        let after = Array(s[len...])
        if let a0 = after.first, !isSpace(a0) { return nil }
        let empty = isBlank(after)
        let w = indentOf(after)
        if empty { return Marker(bullet: bullet, number: number, indent: indent, contentIndent: indent + len + 1, first: [], empty: true) }
        if w > 4 { return Marker(bullet: bullet, number: number, indent: indent, contentIndent: indent + len + 1, first: stripIndent(after, 1), empty: false) }
        return Marker(bullet: bullet, number: number, indent: indent, contentIndent: indent + len + w, first: stripIndent(after, w), empty: false)
    }

    private func list(_ i: Int, _ first: Marker) -> Int {
        var items: [MdListItem] = []
        var m = first
        var j = i
        while true {
            var body: [[UInt8]] = [m.first]
            var k = j + 1
            var blanks = 0
            let lastBlank = m.empty
            var sawLine = false
            while k < lines.count {
                let line = lines[k]
                if isBlank(line) {
                    blanks += 1
                    k += 1
                    continue
                }
                let indent = indentOf(line)
                let nestedItem = indent > m.indent && indent < m.contentIndent && marker(stripIndent(line, indent)) != nil
                if indent >= m.contentIndent || nestedItem {
                    body += [[UInt8]](repeating: [], count: blanks)
                    blanks = 0
                    body.append(stripIndent(line, nestedItem ? indent : m.contentIndent))
                    sawLine = true
                    k += 1
                    continue
                }
                if blanks == 0 && !(lastBlank && !sawLine) && marker(line) == nil && !interrupts(k) {
                    body.append(trimStartSpaces(line))
                    k += 1
                    continue
                }
                break
            }
            items.append(item(body))
            if k >= lines.count {
                j = k
                break
            }
            let next = lines[k]
            guard let nm = marker(next), nm.bullet == first.bullet, !isRule(stripIndent(next, indentOf(next))) else {
                j = k
                break
            }
            m = nm
            j = k
        }
        add(i, .list(ordered: !first.bullet, start: first.number, items: items))
        return j
    }

    private func item(_ lines: [[UInt8]]) -> MdListItem {
        var body = lines
        let s = body[0]
        var task: Bool?
        if s.count >= 3, s[0] == mdA("["), s[2] == mdA("]"), s[1] == 32 || s[1] == mdA("x") || s[1] == mdA("X"),
           s.count == 3 || isSpace(s[3]) {
            task = s[1] != 32
            body[0] = trimStartSpaces(Array(s[3...]))
        }
        return MdListItem(task: task, blocks: MdBlockParser.parse(body))
    }

    // MARK: tables

    private func tableStart(_ i: Int, pending: Bool) -> Bool {
        let line = lines[i]
        if !line.contains(mdA("|")) { return false }
        if i + 1 < lines.count, let align = delimiterRow(lines[i + 1]), align.count == splitRow(line).count { return true }
        return pending && pendingRows(i) >= 0
    }

    private func pendingRows(_ i: Int) -> Int {
        guard trimSpaces(lines[i]).first == mdA("|") else { return -1 }
        var last = lines.count - 1
        while last > i && isBlank(lines[last]) { last -= 1 }
        if last == i { return 1 }
        if last != i + 1 { return -1 }
        let d = trimSpaces(lines[i + 1])
        return d.allSatisfy { $0 == mdA("|") || $0 == mdA(":") || $0 == mdA("-") || isSpace($0) } ? 2 : -1
    }

    private func delimiterRow(_ line: [UInt8]) -> [MdAlign]? {
        if !line.contains(mdA("|")) { return nil }
        var out: [MdAlign] = []
        for cell in splitRow(line) {
            if cell.isEmpty { return nil }
            let left = cell[0] == mdA(":")
            let right = cell.count > 1 && cell[cell.count - 1] == mdA(":")
            let dashes = cell[(left ? 1 : 0) ..< (cell.count - (right ? 1 : 0))]
            if dashes.isEmpty || dashes.contains(where: { $0 != mdA("-") }) { return nil }
            out.append(left && right ? .center : right ? .right : left ? .left : .none)
        }
        return out
    }

    private func table(_ i: Int) -> Int {
        let header = splitRow(lines[i])
        let align: [MdAlign]
        var j: Int
        if i + 1 < lines.count, let d = delimiterRow(lines[i + 1]), d.count == header.count {
            align = d
            j = i + 2
        } else {
            align = Array(repeating: .none, count: header.count)
            j = i + pendingRows(i)
        }
        var rows: [[[MdSpan]]] = []
        while j < lines.count {
            let line = lines[j]
            if isBlank(line) || !line.contains(mdA("|")) { break }
            let indent = indentOf(line)
            if indent <= 3 {
                let s = stripIndent(line, indent)
                if fence(s) != nil || headingLevel(s) > 0 || s.first == mdA(">") { break }
            }
            let cells = splitRow(line)
            rows.append((0 ..< header.count).map { c in c < cells.count ? cell(cells[c]) : [] })
            j += 1
        }
        add(i, .table(MdTable(align: align, header: header.map(cell), rows: rows)))
        return j
    }

    private func cell(_ text: [UInt8]) -> [MdSpan] {
        var out: [UInt8] = []
        out.reserveCapacity(text.count)
        var k = 0
        while k < text.count {
            if text[k] == mdA("\\"), k + 1 < text.count, text[k + 1] == mdA("|") {
                out.append(mdA("|"))
                k += 2
            } else {
                out.append(text[k])
                k += 1
            }
        }
        return MdInline.parse(out, links: true)
    }

    private func splitRow(_ line: [UInt8]) -> [[UInt8]] {
        var s = trimSpaces(line)
        if s.first == mdA("|") { s.removeFirst() }
        if s.last == mdA("|") && !(s.count >= 2 && s[s.count - 2] == mdA("\\")) { s.removeLast() }
        var cells: [[UInt8]] = []
        var start = 0
        var k = 0
        while k < s.count {
            let c = s[k]
            if c == mdA("\\") {
                k += 2
            } else if c == mdA("`") {
                var run = 0
                while k + run < s.count && s[k + run] == mdA("`") { run += 1 }
                let close = MdInline.closingBackticks(s, k + run, run)
                k = close >= 0 ? close + run : k + run
            } else if c == mdA("|") {
                cells.append(trimSpaces(s[start ..< k]))
                k += 1
                start = k
            } else {
                k += 1
            }
        }
        cells.append(trimSpaces(s[min(start, s.count)...]))
        return cells
    }

    // MARK: paragraphs

    private func paragraph(_ i: Int) -> Int {
        var text = trimSpaces(lines[i])
        var j = i + 1
        while j < lines.count && !interrupts(j) {
            text.append(10)
            text += trimSpaces(lines[j])
            j += 1
        }
        add(i, .paragraph(MdInline.parse(text, links: true)))
        return j
    }
}

// MARK: - Inline

/// Inline markdown (see `MdInline.kt`): code, math, links, images, autolinks, escapes, and
/// CommonMark emphasis by delimiter runs; unmatched delimiters stay literal.
enum MdInline {
    private final class Run {
        let char: UInt8
        var count: Int
        let length: Int
        let canOpen: Bool
        let canClose: Bool

        init(_ char: UInt8, _ count: Int, _ canOpen: Bool, _ canClose: Bool) {
            self.char = char
            self.count = count
            length = count
            self.canOpen = canOpen
            self.canClose = canClose
        }
    }

    private enum Node {
        case text(String)
        case run(Run)
        case done(MdSpan)
    }

    @inline(__always) private static func isWs(_ c: UInt8) -> Bool { c == 32 || c == 9 || c == 10 }
    @inline(__always) private static func isDigit(_ c: UInt8) -> Bool { c >= 48 && c <= 57 }
    @inline(__always) private static func isLetter(_ c: UInt8) -> Bool { (c >= 65 && c <= 90) || (c >= 97 && c <= 122) }
    @inline(__always) private static func isAlnum(_ c: UInt8) -> Bool { isLetter(c) || isDigit(c) }
    @inline(__always) private static func isPunct(_ c: UInt8) -> Bool {
        (c >= 33 && c <= 47) || (c >= 58 && c <= 64) || (c >= 91 && c <= 96) || (c >= 123 && c <= 126)
    }

    static func parse(_ s: [UInt8], links: Bool) -> [MdSpan] {
        var nodes: [Node] = []
        var text: [UInt8] = []
        func flush() {
            if !text.isEmpty {
                nodes.append(.text(str(text)))
                text.removeAll(keepingCapacity: true)
            }
        }
        let n = s.count
        var i = 0
        while i < n {
            let c = s[i]
            if c == mdA("\\") {
                if i + 1 < n && isPunct(s[i + 1]) {
                    text.append(s[i + 1])
                    i += 2
                } else if i + 1 < n && s[i + 1] == 10 {
                    text.append(10)
                    i += 2
                } else {
                    text.append(c)
                    i += 1
                }
            } else if c == mdA("`") {
                let run = runLength(s, i, c)
                let close = closingBackticks(s, i + run, run)
                if close < 0 {
                    text += s[i ..< (i + run)]
                } else {
                    flush()
                    nodes.append(.done(.inlineCode(codeContent(s[(i + run) ..< close]))))
                    i = close
                }
                i += run
            } else if c == mdA("$") {
                let end = mathEnd(s, i)
                if end < 0 {
                    text.append(c)
                    i += 1
                } else {
                    flush()
                    let display = s[i + 1] == mdA("$")
                    let tex = display ? str(trimWs(s[(i + 2) ..< (end - 2)])) : str(s[(i + 1) ..< (end - 1)])
                    nodes.append(.done(.math(tex)))
                    i = end
                }
            } else if c == mdA("*") || c == mdA("_") || c == mdA("~") {
                let run = runLength(s, i, c)
                if c == mdA("~") && run != 2 {
                    text += s[i ..< (i + run)]
                } else {
                    let before = i == 0 ? 32 : s[i - 1]
                    let after = i + run >= n ? 32 : s[i + run]
                    let left = !isWs(after) && (!isPunct(after) || isWs(before) || isPunct(before))
                    let right = !isWs(before) && (!isPunct(before) || isWs(after) || isPunct(after))
                    let open = c == mdA("_") ? left && (!right || isPunct(before)) : left
                    let close = c == mdA("_") ? right && (!left || isPunct(after)) : right
                    if open || close {
                        flush()
                        nodes.append(.run(Run(c, run, open, close)))
                    } else {
                        text += s[i ..< (i + run)]
                    }
                }
                i += run
            } else if c == mdA("!") && i + 1 < n && s[i + 1] == mdA("[") {
                if let link = link(s, i + 1) {
                    flush()
                    let alt = MdPlain.spans(parse(Array(s[(i + 2) ..< link.labelEnd]), links: false), speech: false)
                    nodes.append(.done(.image(alt: alt, url: link.url)))
                    i = link.end
                } else {
                    text.append(c)
                    i += 1
                }
            } else if c == mdA("[") && links {
                if let link = link(s, i) {
                    flush()
                    let label = parse(Array(s[(i + 1) ..< link.labelEnd]), links: false)
                    // Other schemes (javascript:, file:, intent:, relative paths) are not links.
                    if isAllowed(Array(link.url.utf8)) {
                        nodes.append(.done(.link(url: link.url, label)))
                    } else {
                        nodes += label.map { .done($0) }
                    }
                    i = link.end
                } else {
                    text.append(c)
                    i += 1
                }
            } else if c == mdA("<") && links {
                var url: String?
                var end = -1
                if let e = s[(i + 1)...].firstIndex(of: mdA(">")) {
                    end = e
                    let inner = Array(s[(i + 1) ..< e])
                    if !inner.contains(where: { isWs($0) || $0 == mdA("<") }) {
                        if isAllowed(inner) && isUri(inner) {
                            url = str(inner)
                        } else if isEmail(inner) {
                            url = "mailto:" + str(inner)
                        }
                    }
                }
                if let url {
                    flush()
                    nodes.append(.done(.link(url: url, [.text(str(s[(i + 1) ..< end]))])))
                    i = end + 1
                } else {
                    text.append(c)
                    i += 1
                }
            } else if c == mdA("h") && links && (i == 0 || isWs(s[i - 1]) || [mdA("*"), mdA("_"), mdA("~"), mdA("(")].contains(s[i - 1]))
                && !(i >= 2 && s[i - 1] == mdA("(") && s[i - 2] == mdA("]")) {
                let end = bareUrlEnd(s, i)
                if end < 0 {
                    text.append(c)
                    i += 1
                } else {
                    flush()
                    let url = str(s[i ..< end])
                    nodes.append(.done(.link(url: url, [.text(url)])))
                    i = end
                }
            } else {
                text.append(c)
                i += 1
            }
        }
        flush()
        emphasis(&nodes)
        return spans(nodes[...])
    }

    private static func emphasis(_ nodes: inout [Node]) {
        var ci = 0
        while ci < nodes.count {
            guard case .run(let closer) = nodes[ci], closer.canClose, closer.count > 0 else {
                ci += 1
                continue
            }
            var oi = ci - 1
            var opener: Run?
            while oi >= 0 {
                if case .run(let o) = nodes[oi], o.char == closer.char, o.canOpen, o.count > 0 {
                    let odd = closer.char != mdA("~") && (o.canClose || closer.canOpen)
                        && (o.length + closer.length) % 3 == 0 && !(o.length % 3 == 0 && closer.length % 3 == 0)
                    if !odd {
                        opener = o
                        break
                    }
                }
                oi -= 1
            }
            guard let opener else {
                ci += 1
                continue
            }
            let use = closer.char == mdA("~") || (opener.count >= 2 && closer.count >= 2) ? 2 : 1
            opener.count -= use
            closer.count -= use
            let inner = spans(nodes[(oi + 1) ..< ci])
            let span: MdSpan = closer.char == mdA("~") ? .strike(inner) : use == 2 ? .strong(inner) : .emphasis(inner)
            nodes.replaceSubrange((oi + 1) ..< ci, with: [.done(span)])
            ci = oi + 2
            if opener.count == 0 {
                nodes.remove(at: oi)
                ci -= 1
            }
            if closer.count == 0 { nodes.remove(at: ci) }
        }
    }

    private static func spans(_ nodes: ArraySlice<Node>) -> [MdSpan] {
        var out: [MdSpan] = []
        var text = ""
        for node in nodes {
            switch node {
            case .text(let t): text += t
            case .run(let r): text += String(repeating: Character(Unicode.Scalar(r.char)), count: r.count)
            case .done(let span):
                if case .text(let t) = span {
                    text += t
                } else {
                    if !text.isEmpty { out.append(.text(text)) }
                    text = ""
                    out.append(span)
                }
            }
        }
        if !text.isEmpty { out.append(.text(text)) }
        return out
    }

    private static func runLength(_ s: [UInt8], _ at: Int, _ c: UInt8) -> Int {
        var n = 0
        while at + n < s.count && s[at + n] == c { n += 1 }
        return n
    }

    /// Start of the next run of exactly `run` backticks at or after `from`, or -1.
    static func closingBackticks(_ s: [UInt8], _ from: Int, _ run: Int) -> Int {
        var k = from
        while k < s.count {
            if s[k] == mdA("`") {
                let r = runLength(s, k, mdA("`"))
                if r == run { return k }
                k += r
            } else {
                k += 1
            }
        }
        return -1
    }

    private static func codeContent(_ raw: ArraySlice<UInt8>) -> String {
        var s = raw.map { $0 == 10 ? 32 : $0 }
        if s.count >= 2 && s[0] == 32 && s[s.count - 1] == 32 && s.contains(where: { $0 != 32 }) {
            s = Array(s[1 ..< (s.count - 1)])
        }
        return str(s)
    }

    private static func mathEnd(_ s: [UInt8], _ i: Int) -> Int {
        let n = s.count
        if i + 1 < n && s[i + 1] == mdA("$") {
            let close = indexOfPair(s, mdA("$"), mdA("$"), from: i + 2)
            return close < 0 || trimWs(s[(i + 2) ..< close]).isEmpty ? -1 : close + 2
        }
        if i + 1 >= n || isWs(s[i + 1]) { return -1 }
        var j = i + 1
        while j < n {
            let c = s[j]
            if c == mdA("\\") {
                j += 2
                continue
            }
            if c == mdA("$") && !isWs(s[j - 1]) && (j + 1 >= n || !isDigit(s[j + 1])) { return j + 1 }
            j += 1
        }
        return -1
    }

    private struct LinkParts {
        let labelEnd: Int
        let url: String
        let end: Int
    }

    private static func link(_ s: [UInt8], _ at: Int) -> LinkParts? {
        let n = s.count
        var j = at + 1
        var depth = 0
        while j < n {
            let c = s[j]
            if c == mdA("\\") {
                j += 2
                continue
            }
            if c == mdA("`") {
                let run = runLength(s, j, c)
                let close = closingBackticks(s, j + run, run)
                j = (close >= 0 ? close : j) + run
                continue
            }
            if c == mdA("[") {
                depth += 1
            } else if c == mdA("]") {
                if depth == 0 { break }
                depth -= 1
            }
            j += 1
        }
        if j >= n { return nil }
        let labelEnd = j
        var k = j + 1
        if k >= n || s[k] != mdA("(") { return nil }
        k += 1
        while k < n && isWs(s[k]) { k += 1 }
        let dest: ArraySlice<UInt8>
        if k < n && s[k] == mdA("<") {
            var e = k + 1
            while e < n && s[e] != mdA(">") && s[e] != mdA("<") && s[e] != 10 { e += 1 }
            if e >= n || s[e] != mdA(">") { return nil }
            dest = s[(k + 1) ..< e]
            k = e + 1
        } else {
            let start = k
            var parens = 0
            while k < n {
                let c = s[k]
                if c == mdA("\\") && k + 1 < n && isPunct(s[k + 1]) {
                    k += 2
                    continue
                }
                if isWs(c) { break }
                if c == mdA("(") {
                    parens += 1
                } else if c == mdA(")") {
                    if parens == 0 { break }
                    parens -= 1
                }
                k += 1
            }
            if parens != 0 { return nil }
            dest = s[start ..< min(k, n)]
        }
        while k < n && isWs(s[k]) { k += 1 }
        if k < n && (s[k] == mdA("\"") || s[k] == mdA("'") || s[k] == mdA("(")) {
            let closeChar = s[k] == mdA("(") ? mdA(")") : s[k]
            k += 1
            while k < n && s[k] != closeChar { k += s[k] == mdA("\\") ? 2 : 1 }
            if k >= n { return nil }
            k += 1
            while k < n && isWs(s[k]) { k += 1 }
        }
        if k >= n || s[k] != mdA(")") { return nil }
        return LinkParts(labelEnd: labelEnd, url: unescape(dest), end: k + 1)
    }

    private static func bareUrlEnd(_ s: [UInt8], _ i: Int) -> Int {
        let scheme: Int
        if hasPrefix(s, "https://", at: i) {
            scheme = 8
        } else if hasPrefix(s, "http://", at: i) {
            scheme = 7
        } else {
            return -1
        }
        var j = i
        while j < s.count && !isWs(s[j]) && s[j] != mdA("<") { j += 1 }
        let trailing = Array("?!.,:*_~'\"".utf8)
        while j > i {
            let c = s[j - 1]
            if trailing.contains(c) {
                j -= 1
            } else if c == mdA(")") {
                var open = 0
                var close = 0
                for k in i ..< j {
                    if s[k] == mdA("(") { open += 1 } else if s[k] == mdA(")") { close += 1 }
                }
                if close > open { j -= 1 } else { break }
            } else {
                break
            }
        }
        if j - i <= scheme || !isAlnum(s[i + scheme]) { return -1 }
        return j
    }

    private static func isAllowed(_ url: [UInt8]) -> Bool {
        let lower = url.prefix(8).map { isLetter($0) ? $0 | 0x20 : $0 }
        return hasPrefix(lower, "https://") || hasPrefix(lower, "http://") || hasPrefix(lower, "mailto:")
    }

    private static func isUri(_ s: [UInt8]) -> Bool {
        guard let colon = s.firstIndex(of: mdA(":")), colon >= 2, colon <= 32, isLetter(s[0]) else { return false }
        for k in 1 ..< colon {
            let c = s[k]
            if !(isAlnum(c) || c == mdA("+") || c == mdA(".") || c == mdA("-")) { return false }
        }
        return true
    }

    private static func isEmail(_ s: [UInt8]) -> Bool {
        guard let at = s.firstIndex(of: mdA("@")), at >= 1, at != s.count - 1 else { return false }
        let local = Array(".!#$%&'*+/=?^_`{|}~-".utf8)
        for k in 0 ..< at where !(isAlnum(s[k]) || local.contains(s[k])) { return false }
        if !isAlnum(s[at + 1]) { return false }
        for k in (at + 1) ..< s.count where !(isAlnum(s[k]) || s[k] == mdA("-") || s[k] == mdA(".")) { return false }
        return true
    }

    private static func unescape(_ s: ArraySlice<UInt8>) -> String {
        guard s.contains(mdA("\\")) else { return str(s) }
        var out: [UInt8] = []
        var k = s.startIndex
        while k < s.endIndex {
            if s[k] == mdA("\\") && k + 1 < s.endIndex && isPunct(s[k + 1]) { k += 1 }
            out.append(s[k])
            k += 1
        }
        return str(out)
    }

    private static func trimWs(_ s: ArraySlice<UInt8>) -> ArraySlice<UInt8> {
        var a = s.startIndex
        var b = s.endIndex
        while a < b && isWs(s[a]) { a += 1 }
        while b > a && isWs(s[b - 1]) { b -= 1 }
        return s[a ..< b]
    }
}

// MARK: - Plain text

/// Markdown trees as plain text: for accessibility and read-aloud (port of `MdPlain` in `MdInline.kt`).
enum MdPlain {
    static func spans(_ spans: [MdSpan], speech: Bool) -> String {
        var out = ""
        append(&out, spans, speech)
        return out
    }

    private static func append(_ out: inout String, _ spans: [MdSpan], _ speech: Bool) {
        for span in spans {
            switch span {
            case .text(let t): out += t
            case .inlineCode(let c): out += c
            case .math(let t): out += t
            case .strong(let c), .emphasis(let c), .strike(let c): append(&out, c, speech)
            case .link(let url, let children):
                if speech && isBare(url, children) {
                    out += "link"
                } else if children.isEmpty {
                    out += speech ? "link" : url
                } else {
                    append(&out, children, speech)
                }
            case .image(let alt, _): out += alt
            }
        }
    }

    /// An autolink: its text is its URL.
    static func isBare(_ url: String, _ children: [MdSpan]) -> Bool {
        guard children.count == 1, case .text(let t) = children[0] else { return false }
        return t == url || "mailto:" + t == url
    }

    static func text(_ blocks: [MdBlock]) -> String {
        var out: [String] = []
        lines(blocks, &out)
        return out.joined(separator: "\n")
    }

    private static func lines(_ blocks: [MdBlock], _ out: inout [String]) {
        for block in blocks {
            switch block {
            case .paragraph(let s), .heading(_, let s): out.append(spans(s, speech: false))
            case .codeBlock(_, let code, _): out.append(code)
            case .quote(let inner): lines(inner, &out)
            case .list(_, _, let items): for item in items { lines(item.blocks, &out) }
            case .rule: break
            case .table(let t):
                for row in [t.header] + t.rows { out.append(row.map { spans($0, speech: false) }.joined(separator: "\t")) }
            }
        }
    }

    static func speakable(_ blocks: [MdBlock]) -> String {
        var out: [String] = []
        speak(blocks, &out)
        return out.joined(separator: "\n")
    }

    private static func speak(_ blocks: [MdBlock], _ out: inout [String]) {
        for block in blocks {
            switch block {
            case .paragraph(let s):
                for line in spans(s, speech: true).split(separator: "\n", omittingEmptySubsequences: false) {
                    let l = clean(String(line))
                    if !l.isEmpty { out.append(l) }
                }
            case .heading(_, let s):
                let l = clean(spans(s, speech: true))
                if !l.isEmpty { out.append(stop(l)) }
            case .codeBlock(let language, _, _): out.append(language == "math" ? "formula." : "code block.")
            case .quote(let inner): speak(inner, &out)
            case .list(_, _, let items):
                for item in items {
                    // The item's first block ends with a stop (its sentence may wrap over lines).
                    let at = out.count
                    speak(Array(item.blocks.prefix(1)), &out)
                    if out.count > at { out[out.count - 1] = stop(out[out.count - 1]) }
                    speak(Array(item.blocks.dropFirst()), &out)
                }
            case .rule: break
            case .table(let t):
                for row in [t.header] + t.rows {
                    let cells = row.map { clean(spans($0, speech: true)) }.filter { !$0.isEmpty }
                    if !cells.isEmpty { out.append(stop(cells.joined(separator: ", "))) }
                }
            }
        }
    }

    private static func stop(_ line: String) -> String {
        guard let last = line.last else { return line }
        return ".!?:;,…".contains(last) ? line : line + "."
    }

    /// One spoken line: no HTML tags, no stray `*`, `` ` `` or `~`, single spaces.
    private static func clean(_ line: String) -> String {
        let s = Array(line.utf8)
        var out: [UInt8] = []
        out.reserveCapacity(s.count)
        var space = false
        var k = 0
        while k < s.count {
            let c = s[k]
            if c == mdA("<") {
                let end = tagEnd(s, k)
                if end > 0 {
                    k = end
                    continue
                }
            }
            if c == mdA("*") || c == mdA("`") || c == mdA("~") {
                // dropped
            } else if c == 32 || c == 9 {
                space = !out.isEmpty
            } else {
                if space { out.append(32) }
                space = false
                out.append(c)
            }
            k += 1
        }
        return str(out)
    }

    private static func tagEnd(_ s: [UInt8], _ at: Int) -> Int {
        var k = at + 1
        if k < s.count && s[k] == mdA("/") { k += 1 }
        guard k < s.count, (s[k] >= 65 && s[k] <= 90) || (s[k] >= 97 && s[k] <= 122) else { return -1 }
        guard let end = s[k...].firstIndex(of: mdA(">")) else { return -1 }
        return end + 1
    }
}

/// What the "open this link?" prompt shows about a URL (string rules only, no lookups).
enum MdLinks {
    /// `example.com` for `https://user@Example.com:8080/a?b`, the address for `mailto:`.
    static func host(_ url: String) -> String {
        if url.lowercased().hasPrefix("mailto:") {
            return String(url.dropFirst(7).split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
        }
        guard let scheme = url.range(of: "://") else { return "" }
        var authority = Substring(url[scheme.upperBound...])
        if let end = authority.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) { authority = authority[..<end] }
        if let at = authority.lastIndex(of: "@") { authority = authority[authority.index(after: at)...] }
        if !authority.hasPrefix("["), let colon = authority.firstIndex(of: ":") { authority = authority[..<colon] }
        return authority.lowercased()
    }

    /// Plain `http`: anyone on the network can read or change what it loads.
    static func isInsecure(_ url: String) -> Bool { url.lowercased().hasPrefix("http://") }
}

/**
 Parsed markdown per text, so a bubble is parsed once, not on every render. Finished messages hit
 the cache by their text; a streaming message (each frame's text extends the last one) continues
 from the previous frame's parse, re-parsing only the tail (`LiteMarkdown.parse(_:previous:)`).
 */
@MainActor
final class MarkdownCache {
    static let shared = MarkdownCache()

    private let limit: Int
    private var entries: [String: [MdBlock]] = [:]
    /// Insertion order, oldest first (evicted beyond [limit]).
    private var order: [String] = []
    /// The last parse, which a streamed extension continues from.
    private var last: LiteMarkdown.Parsed?

    init(limit: Int = 256) { self.limit = limit }

    func blocks(_ text: String) -> [MdBlock] {
        if let hit = entries[text] { return hit }
        let base = last.flatMap { Utf8.isPrefix($0.text, of: text) ? $0 : nil }
        let parsed = LiteMarkdown.parse(text, previous: base)
        last = parsed
        store(text, parsed.blocks)
        return parsed.blocks
    }

    private func store(_ text: String, _ blocks: [MdBlock]) {
        // Streamed prefixes would crowd the cache out: keep one entry per growing message.
        if let last = order.last, text.hasPrefix(last) {
            entries[last] = nil
            order.removeLast()
        }
        entries[text] = blocks
        order.append(text)
        if order.count > limit {
            let drop = order.count - limit
            order.prefix(drop).forEach { entries[$0] = nil }
            order.removeFirst(drop)
        }
    }

    func removeAll() {
        entries.removeAll()
        order.removeAll()
        last = nil
    }

    var count: Int { entries.count }
}

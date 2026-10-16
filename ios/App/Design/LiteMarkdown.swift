import Foundation

/// A block of the lightweight markdown subset used in chat bubbles.
enum MdBlock: Equatable, Sendable {
    case paragraph([MdSpan])
    /// A fenced code block. `closed` is `false` while the closing fence has not streamed in yet.
    case codeBlock(language: String?, code: String, closed: Bool)
}

enum MdSpan: Equatable, Sendable {
    case text(String)
    case inlineCode(String)
}

/**
 Tiny parser for exactly three constructs: paragraphs (separated by blank lines), inline `code`
 and ``` fenced code blocks. Unterminated fences (common mid-stream) are rendered as an open
 code block. Everything else is kept as literal text — no HTML, no links, no images, so model
 output can never trigger network fetches. Port of `LiteMarkdown.kt`.
 */
enum LiteMarkdown {
    private static let fence = "```"

    static func parse(_ text: String) -> [MdBlock] {
        var blocks: [MdBlock] = []
        var paragraph = ""
        let lines = text.replacingOccurrences(of: "\r\n", with: "\n").split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
        var i = 0

        func flushParagraph() {
            let content = paragraph.trimmingCharacters(in: CharacterSet(charactersIn: "\n"))
            if !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { blocks.append(.paragraph(parseInline(content))) }
            paragraph = ""
        }

        while i < lines.count {
            let line = lines[i]
            let trimmed = line.drop(while: { $0.isWhitespace })
            if trimmed.hasPrefix(fence) {
                flushParagraph()
                let language = trimmed.dropFirst(fence.count).trimmingCharacters(in: .whitespaces)
                var code = ""
                var closed = false
                i += 1
                while i < lines.count {
                    if lines[i].drop(while: { $0.isWhitespace }).hasPrefix(fence) {
                        closed = true
                        break
                    }
                    if !code.isEmpty { code += "\n" }
                    code += lines[i]
                    i += 1
                }
                blocks.append(.codeBlock(language: language.isEmpty ? nil : language, code: code, closed: closed))
                i += 1
                continue
            }
            if line.trimmingCharacters(in: .whitespaces).isEmpty {
                flushParagraph()
            } else {
                if !paragraph.isEmpty { paragraph += "\n" }
                paragraph += line
            }
            i += 1
        }
        flushParagraph()
        return blocks
    }

    static func parseInline(_ text: String) -> [MdSpan] {
        var spans: [MdSpan] = []
        var cursor = text.startIndex
        while cursor < text.endIndex {
            guard let open = text[cursor...].firstIndex(of: "`") else { break }
            let afterOpen = text.index(after: open)
            guard let close = text[afterOpen...].firstIndex(of: "`") else { break }
            if open > cursor { spans.append(.text(String(text[cursor ..< open]))) }
            let code = String(text[afterOpen ..< close])
            spans.append(code.isEmpty ? .text("``") : .inlineCode(code))
            cursor = text.index(after: close)
        }
        if cursor < text.endIndex { spans.append(.text(String(text[cursor...]))) }
        return spans
    }
}


/**
 Parsed markdown per text, so a bubble is parsed once, not on every render. Finished messages hit
 the cache by their text; a streaming message (each frame's text extends the last one) re-parses
 only its tail: blocks before the last blank line outside a code fence never change as more
 text arrives, so they are kept and the rest is parsed and appended (`parse(head + tail) ==
 parse(head) + parse(tail)` when head ends at such a line).
 */
@MainActor
final class MarkdownCache {
    static let shared = MarkdownCache()

    private let limit: Int
    private var entries: [String: [MdBlock]] = [:]
    /// Insertion order, oldest first (evicted beyond [limit]).
    private var order: [String] = []
    /// The streamed text's stable head (ends at a safe split) and its blocks.
    private var stream: (head: String, blocks: [MdBlock])?

    init(limit: Int = 256) { self.limit = limit }

    func blocks(_ text: String) -> [MdBlock] {
        if let hit = entries[text] { return hit }
        let blocks: [MdBlock]
        if let s = stream, !s.head.isEmpty, text.utf8.count > s.head.utf8.count, text.utf8.starts(with: s.head.utf8), !text.utf8.contains(13) {
            let tail = Substring(text.utf8.dropFirst(s.head.utf8.count))
            let split = Self.safeSplit(tail)
            let stable = split.map { s.blocks + LiteMarkdown.parse(String(tail[..<$0])) } ?? s.blocks
            if let split { stream = (s.head + String(tail[..<split]), stable) }
            blocks = stable + LiteMarkdown.parse(String(split.map { tail[$0...] } ?? tail))
        } else {
            if !text.utf8.contains(13), let split = Self.safeSplit(Substring(text)) {
                // Parsed in two parts (the same work): the head is kept for a streamed extension.
                let head = String(text[..<split])
                let headBlocks = LiteMarkdown.parse(head)
                stream = (head, headBlocks)
                blocks = headBlocks + LiteMarkdown.parse(String(text[split...]))
            } else {
                stream = nil
                blocks = LiteMarkdown.parse(text)
            }
        }
        store(text, blocks)
        return blocks
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
        stream = nil
    }

    var count: Int { entries.count }

    /**
     The index just after the last blank line of [text] that is outside a code fence (nil: none),
     assuming [text] starts outside a fence. Mirrors [LiteMarkdown.parse]'s line rules.
     */
    nonisolated static func safeSplit(_ text: Substring) -> Substring.Index? {
        var inFence = false
        var split: Substring.Index?
        var lineStart = text.startIndex
        while lineStart < text.endIndex {
            guard let newline = text[lineStart...].firstIndex(of: "\n") else { break }
            let line = text[lineStart ..< newline]
            if line.drop(while: { $0.isWhitespace }).hasPrefix("```") {
                inFence.toggle()
            } else if !inFence && line.allSatisfy({ $0 == " " || $0 == "\t" }) {
                split = text.index(after: newline)
            }
            lineStart = text.index(after: newline)
        }
        return split
    }
}

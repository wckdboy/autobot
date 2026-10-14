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

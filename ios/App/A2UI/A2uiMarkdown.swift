import SwiftUI

/**
 The simple Markdown an A2UI `Text` may carry: headings (`#`–`######`), bullet and numbered
 lines, and inline emphasis, code and links through `AttributedString`. Anything it cannot parse
 falls back to the text with the common markers stripped (the catalog guide's rule). Links are
 shown but only http(s) ones stay tappable.
 */
enum A2uiMarkdown {
    enum Block: Equatable {
        case heading(level: Int, text: String)
        case bullet(marker: String, text: String)
        case paragraph(String)
    }

    static func blocks(_ text: String) -> [Block] {
        var out: [Block] = []
        var paragraph: [String] = []
        func flush() {
            if !paragraph.isEmpty { out.append(.paragraph(paragraph.joined(separator: "\n"))) }
            paragraph = []
        }
        for raw in text.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { flush(); continue }
            if line.hasPrefix("#") {
                let hashes = line.prefix { $0 == "#" }.count
                let rest = line.dropFirst(hashes)
                if hashes <= 6, rest.first == " " {
                    flush()
                    out.append(.heading(level: hashes, text: rest.trimmingCharacters(in: .whitespaces)))
                    continue
                }
            }
            if line.hasPrefix("- ") || line.hasPrefix("* ") || line.hasPrefix("+ ") {
                flush()
                out.append(.bullet(marker: "•", text: String(line.dropFirst(2))))
                continue
            }
            if let dot = line.firstIndex(where: { $0 == "." || $0 == ")" }), dot > line.startIndex,
               line[..<dot].allSatisfy(\.isNumber), line[line.index(after: dot)...].first == " " {
                flush()
                out.append(.bullet(marker: String(line[...dot]), text: line[line.index(after: dot)...].trimmingCharacters(in: .whitespaces)))
                continue
            }
            paragraph.append(raw)
        }
        flush()
        return out
    }

    /// Inline Markdown as an AttributedString; non-http(s) links lose their link.
    static func inline(_ text: String, codeFont: Font = .body.monospaced()) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace, failurePolicy: .returnPartiallyParsedIfPossible)
        guard var parsed = try? AttributedString(markdown: text, options: options) else {
            return AttributedString(stripped(text))
        }
        for run in parsed.runs {
            if let url = run.link, !["http", "https"].contains(url.scheme?.lowercased() ?? "") {
                parsed[run.range].link = nil
            }
            if run.inlinePresentationIntent?.contains(.code) == true {
                parsed[run.range].font = codeFont
            }
        }
        return parsed
    }

    /// The text without the common Markdown markers (fallback).
    static func stripped(_ text: String) -> String {
        var s = text
        for marker in ["**", "__", "`", "~~"] { s = s.replacingOccurrences(of: marker, with: "") }
        return s.components(separatedBy: "\n").map { line in
            var l = Substring(line)
            while l.first == "#" { l = l.dropFirst() }
            return l.trimmingCharacters(in: .whitespaces)
        }.joined(separator: "\n")
    }

    static func font(heading level: Int) -> Font {
        switch level {
        case 1: .title.bold()
        case 2: .title2.bold()
        case 3: .title3.weight(.semibold)
        default: .headline
        }
    }
}

/// An A2UI `Text`: Markdown blocks in system text styles (Dynamic Type), colour inherited.
struct A2uiMarkdownText: View {
    let text: String
    let caption: Bool

    var body: some View {
        let blocks = A2uiMarkdown.blocks(text)
        if blocks.count == 1, case .paragraph(let p) = blocks[0] {
            paragraph(p)
        } else {
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                    switch block {
                    case .heading(let level, let t):
                        Text(A2uiMarkdown.inline(t))
                            .font(A2uiMarkdown.font(heading: level))
                            .accessibilityAddTraits(.isHeader)
                    case .bullet(let marker, let t):
                        HStack(alignment: .firstTextBaseline, spacing: 6) {
                            Text(marker).accessibilityHidden(true)
                            Text(A2uiMarkdown.inline(t))
                        }
                        .font(caption ? .caption : .body)
                    case .paragraph(let p):
                        paragraph(p)
                    }
                }
            }
        }
    }

    private func paragraph(_ p: String) -> some View {
        Text(A2uiMarkdown.inline(p, codeFont: caption ? .caption.monospaced() : .body.monospaced()))
            .font(caption ? .caption : .body)
            .foregroundStyle(caption ? HierarchicalShapeStyle.secondary : HierarchicalShapeStyle.primary)
    }
}

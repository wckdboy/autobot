import SwiftUI
import UIKit
import UniformTypeIdentifiers

/**
 Chat markdown blocks (port of `Markdown.kt`) in the system's text styles: 8 pt between top-level
 blocks, 4 pt inside lists and quotes; code blocks on the secondary system background, inline code
 in a light tinted capsule, tables with system separators. Links are `AttributedString` links;
 whoever shows this view handles `openURL` (`ChatBubble` asks first), so nothing here opens or
 fetches anything.
 */
struct MarkdownBlocks: View {
    let blocks: [MdBlock]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in
                MarkdownBlockView(block: block, depth: 0, muted: false)
            }
        }
    }
}

private struct MarkdownBlockView: View {
    let block: MdBlock
    let depth: Int
    /// Inside a quote: secondary text color.
    let muted: Bool

    var body: some View {
        switch block {
        case .paragraph(let spans):
            MdText.text(spans, style: .body)
                .font(.body)
                .foregroundStyle(muted ? .secondary : .primary)
                .textRenderer(InlineCodeRenderer())
                .fixedSize(horizontal: false, vertical: true)
        case .heading(let level, let spans):
            let (style, weight): (Font.TextStyle, Font.Weight) = switch level {
            case 1: (.title3, .semibold)
            case 2: (.headline, .semibold)
            case 3: (.subheadline, .semibold)
            default: (.subheadline, .regular)
            }
            MdText.text(spans, style: style, weight: weight)
                .font(.system(style, weight: weight))
                .foregroundStyle(level >= 4 || muted ? .secondary : .primary)
                .textRenderer(InlineCodeRenderer())
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, level <= 2 ? 4 : 2)
                .accessibilityAddTraits(.isHeader)
                .accessibilityHeading(level == 1 ? .h1 : level == 2 ? .h2 : level == 3 ? .h3 : level == 4 ? .h4 : level == 5 ? .h5 : .h6)
        case .codeBlock(let language, let code, let closed):
            CodeBlockView(language: language, code: code, closed: closed)
        case .quote(let inner):
            AnyView(
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(inner.enumerated()), id: \.offset) { _, b in
                        MarkdownBlockView(block: b, depth: depth, muted: true)
                    }
                }
                .padding(.leading, 12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .overlay(alignment: .leading) { Capsule().fill(.quaternary).frame(width: 3) }
            )
        case .list(let ordered, let start, let items):
            AnyView(ListView(ordered: ordered, start: start, items: items, depth: depth, muted: muted))
        case .rule:
            Divider().padding(.vertical, 4)
        case .table(let table):
            TableView(table: table)
        }
    }
}

private struct ListView: View {
    let ordered: Bool
    let start: Int
    let items: [MdListItem]
    let depth: Int
    let muted: Bool
    @ScaledMetric(relativeTo: .body) private var digit: CGFloat = 9

    var body: some View {
        let last = start + items.count - 1
        let markerWidth: CGFloat = items.contains { $0.task != nil } ? digit * 2 + 10 : ordered ? CGFloat(String(last).count) * digit + 14 : 18
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                HStack(alignment: .firstTextBaseline, spacing: 0) {
                    marker(item, number: start + index).frame(width: markerWidth, alignment: .leading)
                    VStack(alignment: .leading, spacing: 4) {
                        ForEach(Array(item.blocks.enumerated()), id: \.offset) { _, b in
                            MarkdownBlockView(block: b, depth: depth + 1, muted: muted)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
    }

    @ViewBuilder private func marker(_ item: MdListItem, number: Int) -> some View {
        if let done = item.task {
            Image(systemName: done ? "checkmark.square.fill" : "square")
                .font(.body)
                .foregroundStyle(done ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                .accessibilityLabel(done ? "done" : "to do")
        } else if ordered {
            Text("\(number).").font(.body.monospacedDigit()).foregroundStyle(.secondary)
        } else {
            Text(depth == 0 ? "•" : "◦").font(.body).foregroundStyle(.secondary)
        }
    }
}

private struct TableView: View {
    let table: MdTable

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 10, style: .continuous)
        ScrollView(.horizontal, showsIndicators: false) {
            Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                GridRow {
                    ForEach(Array(table.header.enumerated()), id: \.offset) { col, spans in
                        cellView(spans, col: col, header: true)
                    }
                }
                .background(Color(.secondarySystemBackground))
                ForEach(Array(table.rows.enumerated()), id: \.offset) { _, row in
                    Divider().gridCellUnsizedAxes(.horizontal)
                    GridRow {
                        ForEach(Array(row.enumerated()), id: \.offset) { col, spans in
                            cellView(spans, col: col, header: false)
                        }
                    }
                }
            }
            .fixedSize()
            .clipShape(shape)
            .overlay(shape.strokeBorder(Color(.separator), lineWidth: 1))
        }
    }

    private func cellView(_ spans: [MdSpan], col: Int, header: Bool) -> some View {
        let align = col < table.align.count ? table.align[col] : .none
        let alignment: Alignment = align == .center ? .center : align == .right ? .trailing : .leading
        // Short cells keep one line; long ones wrap at 220 pt.
        let long = MdPlain.spans(spans, speech: false).count > 28
        return MdText.text(spans, style: .subheadline, weight: header ? .semibold : nil)
            .font(.subheadline.monospacedDigit().weight(header ? .semibold : .regular))
            .textRenderer(InlineCodeRenderer())
            .multilineTextAlignment(align == .center ? .center : align == .right ? .trailing : .leading)
            .frame(width: long ? 220 : nil, alignment: alignment)
            .fixedSize(horizontal: !long, vertical: true)
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity, alignment: alignment)
    }
}

/// A code block: language label and a copy action over the verbatim code, on the secondary background.
private struct CodeBlockView: View {
    let language: String?
    let code: String
    let closed: Bool
    @State private var copied = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(language ?? "code").font(.caption.monospaced()).foregroundStyle(.secondary)
                Spacer()
                if closed {
                    Button {
                        UIPasteboard.general.setItems([[UTType.plainText.identifier: code]], options: [.localOnly: true])
                        copied = true
                        Task {
                            try? await Task.sleep(for: .milliseconds(1500))
                            copied = false
                        }
                    } label: {
                        Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
                            .font(.caption)
                    }
                    .buttonStyle(.borderless)
                    .foregroundStyle(copied ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    .accessibilityLabel(copied ? "Copied" : "Copy code")
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code)
                    .font(.system(.callout, design: .monospaced))
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: true, vertical: false)
                    .padding(.horizontal, 12)
                    .padding(.top, 6)
                    .padding(.bottom, 12)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// Marks a run of inline code (or a never-loaded image's label) for [InlineCodeRenderer].
private struct InlineCodeRun: TextAttribute {}

/// Draws a light tinted capsule behind each inline-code run, then the text.
private struct InlineCodeRenderer: TextRenderer {
    func draw(layout: Text.Layout, in ctx: inout GraphicsContext) {
        for line in layout {
            for run in line {
                if run[InlineCodeRun.self] != nil {
                    let rect = run.typographicBounds.rect.insetBy(dx: -3, dy: 0)
                    ctx.fill(Path(roundedRect: rect, cornerRadius: min(rect.height / 2, 7), style: .continuous), with: .color(Color.brandPurple.opacity(0.13)))
                }
                ctx.draw(run)
            }
        }
    }
}

/// Spans as one `Text` in a base text style: plain runs as an `AttributedString`, inline code as
/// monospaced runs marked for the capsule ([InlineCodeRenderer]).
enum MdText {
    private struct Flags {
        var bold = false
        var italic = false
        var strike = false
        var link: URL?
    }

    private struct Builder {
        let style: Font.TextStyle
        let weight: Font.Weight?
        var parts: [Text] = []
        var pending = AttributedString()

        mutating func flush() {
            guard !pending.characters.isEmpty else { return }
            parts.append(Text(pending))
            pending = AttributedString()
        }

        mutating func code(_ a: AttributedString) {
            flush()
            parts.append(Text(a).customAttribute(InlineCodeRun()))
        }

        var codeFont: Font { .system(style == .body ? .callout : style == .subheadline ? .footnote : style, design: .monospaced) }
    }

    static func text(_ spans: [MdSpan], style: Font.TextStyle, weight: Font.Weight? = nil) -> Text {
        var b = Builder(style: style, weight: weight)
        append(&b, spans, Flags())
        b.flush()
        guard var out = b.parts.first else { return Text(verbatim: "") }
        for part in b.parts.dropFirst() { out = Text("\(out)\(part)") }
        return out
    }

    private static func append(_ b: inout Builder, _ spans: [MdSpan], _ f: Flags) {
        for span in spans {
            switch span {
            case .text(let t):
                b.pending += run(t, f, b)
            case .inlineCode(let t), .math(let t):
                var a = AttributedString(t)
                a.font = b.codeFont
                if let link = f.link { a.link = link }
                b.code(a)
            case .strong(let children):
                var g = f
                g.bold = true
                append(&b, children, g)
            case .emphasis(let children):
                var g = f
                g.italic = true
                append(&b, children, g)
            case .strike(let children):
                var g = f
                g.strike = true
                append(&b, children, g)
            case .link(let url, let children):
                var g = f
                g.link = URL(string: url) ?? URL(string: url.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")
                if children.isEmpty {
                    b.pending += run(url, g, b)
                } else {
                    append(&b, children, g)
                }
                // Plain http is marked where it is read, not only in the prompt.
                if MdLinks.isInsecure(url) {
                    var mark = AttributedString(" (http)")
                    mark.font = b.codeFont
                    mark.foregroundColor = .secondary
                    b.pending += mark
                }
            case .image(let alt, let url):
                // Never loaded: the alt text and where it would have come from.
                let host = MdLinks.host(url)
                var label = "[image"
                if !alt.isEmpty { label += ": " + alt }
                if !host.isEmpty { label += " · " + host }
                label += "]"
                var a = AttributedString(label)
                a.font = b.codeFont
                a.foregroundColor = .secondary
                b.code(a)
            }
        }
    }

    private static func run(_ text: String, _ f: Flags, _ b: Builder) -> AttributedString {
        var a = AttributedString(text)
        if f.bold || f.italic {
            var font = Font.system(b.style, weight: f.bold ? .semibold : b.weight ?? .regular)
            if f.italic { font = font.italic() }
            a.font = font
        }
        if f.strike { a.strikethroughStyle = .single }
        if let link = f.link {
            a.link = link
            a.underlineStyle = .single
        }
        return a
    }
}

/// Asks before a link leaves the app (see `ChatBubble`): the alert's message for `url`.
enum LinkPrompt {
    static func message(_ url: URL) -> String {
        let s = url.absoluteString
        var lines = [MdLinks.host(s).isEmpty ? s : MdLinks.host(s), "", s]
        if MdLinks.isInsecure(s) { lines += ["", "Not encrypted (http): the network can read or change this page."] }
        lines += ["", "Opens in your browser, outside Autobot."]
        return lines.joined(separator: "\n")
    }

    static func action(_ url: URL) -> String {
        url.scheme?.lowercased() == "mailto" ? "Write email" : "Open in browser"
    }
}

/// Copies a message as it was written (its markdown source), for the message footer. Text
/// selection inside the bubble copies the rendered text instead. Local only (no Universal Clipboard).
struct CopyMessageButton: View {
    let markdown: String
    @State private var copied = false

    var body: some View {
        Button {
            UIPasteboard.general.setItems([[UTType.plainText.identifier: markdown]], options: [.localOnly: true])
            copied = true
            Task {
                try? await Task.sleep(for: .milliseconds(1500))
                copied = false
            }
        } label: {
            Image(systemName: copied ? "checkmark" : "doc.on.doc")
        }
        .messageAction(active: copied)
        .accessibilityLabel(copied ? "Copied" : "Copy message")
    }
}

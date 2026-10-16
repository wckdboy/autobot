import SwiftUI
import UIKit
import UniformTypeIdentifiers
import os

/**
 Chat markdown blocks (port of `Markdown.kt`), compact: `chatBlockGap` between top-level blocks,
 4 pt inside lists and quotes. Links are `AttributedString` links; whoever shows this view handles
 `openURL` (`ChatBubble` asks first), so nothing here opens or fetches anything.
 */
struct MarkdownBlocks: View {
    let blocks: [MdBlock]

    var body: some View {
        VStack(alignment: .leading, spacing: Tokens.Spacing.chatBlockGap) {
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
    @Environment(\.scheme) private var c

    var body: some View {
        switch block {
        case .paragraph(let spans):
            Text(MdText.attributed(spans, base: Tokens.Typography.bodyLarge, c: c))
                .textStyle(.bodyLarge)
                .foregroundStyle(muted ? c.onSurfaceVariant : c.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        case .heading(let level, let spans):
            let (style, type, weight): (Tokens.TypeStyle, AutobotType, CGFloat?) = switch level {
            case 1: (Tokens.Typography.titleLarge, .titleLarge, nil)
            case 2: (Tokens.Typography.titleMedium, .titleMedium, 600)
            case 3: (Tokens.Typography.titleSmall, .titleSmall, 600)
            default: (Tokens.Typography.titleSmall, .titleSmall, nil)
            }
            Text(MdText.attributed(spans, base: style, weight: weight, c: c))
                .textStyle(type, weight: weight)
                .foregroundStyle(level >= 4 || muted ? c.onSurfaceVariant : c.onSurface)
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
                .overlay(alignment: .leading) { Rectangle().fill(c.outlineVariant).frame(width: 2) }
            )
        case .list(let ordered, let start, let items):
            AnyView(ListView(ordered: ordered, start: start, items: items, depth: depth, muted: muted))
        case .rule:
            Hairline().padding(.vertical, 4)
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
    @Environment(\.scheme) private var c

    var body: some View {
        let last = start + items.count - 1
        let markerWidth: CGFloat = ordered ? CGFloat(String(last).count * 8 + 12) : 16
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
            TaskBox(done: done)
                // The box sits on the baseline, like a capital letter.
                .alignmentGuide(.firstTextBaseline) { d in d[.bottom] - 0.5 }
        } else if ordered {
            Text("\(number).").textStyle(.readout).foregroundStyle(c.onSurfaceVariant)
        } else {
            // A small halftone dot, centred on the x-height of the first line.
            Circle()
                .fill(depth == 0 ? c.primary : c.onSurfaceVariant)
                .frame(width: depth == 0 ? 5 : 4, height: depth == 0 ? 5 : 4)
                .alignmentGuide(.firstTextBaseline) { d in d[VerticalAlignment.center] + 4 }
        }
    }
}

private struct TaskBox: View {
    let done: Bool
    @Environment(\.scheme) private var c

    var body: some View {
        ZStack {
            if done {
                Rectangle().fill(c.primary)
                Path { p in
                    p.move(to: CGPoint(x: 2.6, y: 6.2))
                    p.addLine(to: CGPoint(x: 5, y: 8.6))
                    p.addLine(to: CGPoint(x: 9.4, y: 3.6))
                }
                .stroke(c.onPrimary, lineWidth: 1.5)
            } else {
                Rectangle().strokeBorder(c.onSurfaceVariant, lineWidth: 1.5)
            }
        }
        .frame(width: 12, height: 12)
        .accessibilityElement()
        .accessibilityLabel(done ? "done" : "to do")
    }
}

private struct TableView: View {
    let table: MdTable
    @Environment(\.scheme) private var c

    private static let cell = Tokens.TypeStyle(font: .sans, size: 14, lineHeight: 20, weight: 400, letterSpacing: 0.2, trim: false, tabular: true)
    private static let head = Tokens.TypeStyle(font: .sans, size: 14, lineHeight: 20, weight: 600, letterSpacing: 0.2, trim: false, tabular: true)

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                GridRow {
                    ForEach(Array(table.header.enumerated()), id: \.offset) { col, spans in
                        cellView(spans, col: col, style: Self.head)
                            .background(c.surfaceContainerLow)
                    }
                }
                ForEach(Array(table.rows.enumerated()), id: \.offset) { _, row in
                    Rectangle().fill(c.outlineVariant).frame(height: Tokens.Stroke.hairline).gridCellUnsizedAxes(.horizontal)
                    GridRow {
                        ForEach(Array(row.enumerated()), id: \.offset) { col, spans in
                            cellView(spans, col: col, style: Self.cell)
                        }
                    }
                }
            }
            .fixedSize()
            .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.small))
            .overlay(RoundedRectangle(cornerRadius: AutobotShapes.small).strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border))
        }
    }

    private func cellView(_ spans: [MdSpan], col: Int, style: Tokens.TypeStyle) -> some View {
        let align = col < table.align.count ? table.align[col] : .none
        let alignment: Alignment = align == .center ? .center : align == .right ? .trailing : .leading
        // Short cells keep one line; long ones wrap at 220 pt.
        let long = MdPlain.spans(spans, speech: false).count > 28
        return Text(MdText.attributed(spans, base: style, c: c))
            .font(Font(AutobotFonts.uiFont(style)))
            .foregroundStyle(c.onSurface)
            .multilineTextAlignment(align == .center ? .center : align == .right ? .trailing : .leading)
            .frame(width: long ? 220 : nil, alignment: alignment)
            .fixedSize(horizontal: !long, vertical: true)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: alignment)
    }
}

/// A code block: language label and a copy action over the verbatim code.
private struct CodeBlockView: View {
    let language: String?
    let code: String
    let closed: Bool
    @State private var copied = false
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                MicroLabel(language ?? "code", color: c.tertiary)
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
                        MicroLabel(copied ? "copied" : "copy", color: copied ? c.primary : c.onSurfaceVariant)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(copied ? "Copied" : "Copy code")
                }
            }
            .padding(.horizontal, Tokens.Spacing.codeHeaderPaddingHorizontal)
            .padding(.vertical, Tokens.Spacing.codeHeaderPaddingVertical)
            .background(c.surfaceContainerLow)
            Hairline()
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code)
                    .textStyle(.codeBlock)
                    .foregroundStyle(c.onSurface)
                    .fixedSize(horizontal: true, vertical: false)
                    .padding(Tokens.Spacing.codePadding)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: AutobotShapes.small).fill(c.surfaceContainerLowest))
        .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.small))
        .overlay(RoundedRectangle(cornerRadius: AutobotShapes.small).strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border))
    }
}

/// Spans as an `AttributedString` in a base type style.
enum MdText {
    private struct Flags {
        var bold = false
        var italic = false
        var strike = false
        var link: URL?
    }

    static func attributed(_ spans: [MdSpan], base: Tokens.TypeStyle, weight: CGFloat? = nil, c: AutobotScheme) -> AttributedString {
        var out = AttributedString()
        append(&out, spans, Flags(), base, weight, c)
        return out
    }

    private static func append(_ out: inout AttributedString, _ spans: [MdSpan], _ f: Flags, _ base: Tokens.TypeStyle, _ weight: CGFloat?, _ c: AutobotScheme) {
        for span in spans {
            switch span {
            case .text(let t):
                out += run(t, f, base, weight, c)
            case .inlineCode(let t), .math(let t):
                var a = run(t, f, base, weight, c)
                a.font = Font(AutobotFonts.uiFont(Tokens.Typography.code, size: base.size - 1))
                a.foregroundColor = f.link == nil ? c.tertiary : c.primary
                a.backgroundColor = c.surfaceContainerHighest
                out += a
            case .strong(let children):
                var g = f
                g.bold = true
                append(&out, children, g, base, weight, c)
            case .emphasis(let children):
                var g = f
                g.italic = true
                append(&out, children, g, base, weight, c)
            case .strike(let children):
                var g = f
                g.strike = true
                append(&out, children, g, base, weight, c)
            case .link(let url, let children):
                var g = f
                g.link = URL(string: url) ?? URL(string: url.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? "")
                if children.isEmpty {
                    out += run(url, g, base, weight, c)
                } else {
                    append(&out, children, g, base, weight, c)
                }
                // Plain http is marked where it is read, not only in the prompt.
                if MdLinks.isInsecure(url) {
                    var mark = AttributedString(" (http)")
                    mark.font = Font(AutobotFonts.uiFont(Tokens.Typography.code, size: base.size - 1))
                    mark.foregroundColor = c.onSurfaceVariant
                    out += mark
                }
            case .image(let alt, let url):
                // Never loaded: the alt text and where it would have come from.
                let host = MdLinks.host(url)
                var label = "[image"
                if !alt.isEmpty { label += ": " + alt }
                if !host.isEmpty { label += " · " + host }
                label += "]"
                var a = AttributedString(label)
                a.font = Font(AutobotFonts.uiFont(Tokens.Typography.code, size: base.size - 1))
                a.foregroundColor = c.onSurfaceVariant
                a.backgroundColor = c.surfaceContainerHighest
                out += a
            }
        }
    }

    private static func run(_ text: String, _ f: Flags, _ base: Tokens.TypeStyle, _ weight: CGFloat?, _ c: AutobotScheme) -> AttributedString {
        var a = AttributedString(text)
        if f.bold || f.italic {
            a.font = Font(font(base, weight: f.bold ? max(600, weight ?? base.weight) : weight, italic: f.italic))
        }
        if f.strike { a.swiftUI.strikethroughStyle = .single }
        if let link = f.link {
            a.link = link
            a.foregroundColor = c.primary
            a.swiftUI.underlineStyle = .single
        }
        return a
    }

    private static let italics = OSAllocatedUnfairLock(initialState: [String: UIFont]())

    private static func font(_ base: Tokens.TypeStyle, weight: CGFloat?, italic: Bool) -> UIFont {
        let plain = AutobotFonts.uiFont(base, weight: weight)
        guard italic else { return plain }
        let key = "\(plain.fontName)/\(plain.pointSize)"
        if let hit = italics.withLock({ $0[key] }) { return hit }
        let descriptor = plain.fontDescriptor.withSymbolicTraits(plain.fontDescriptor.symbolicTraits.union(.traitItalic)) ?? plain.fontDescriptor
        let made = UIFont(descriptor: descriptor, size: plain.pointSize)
        italics.withLock { $0[key] = made }
        return made
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
    @Environment(\.scheme) private var c

    var body: some View {
        IconAction(.copy, label: copied ? "Copied" : "Copy message", tint: copied ? c.primary : c.onSurfaceVariant, size: 18) {
            UIPasteboard.general.setItems([[UTType.plainText.identifier: markdown]], options: [.localOnly: true])
            copied = true
            Task {
                try? await Task.sleep(for: .milliseconds(1500))
                copied = false
            }
        }
        .frame(width: 32, height: 32)
    }
}

import SwiftUI

enum BubbleRole { case user, assistant }

/**
 A transcript message. User input is an outlined, right-aligned block with a mono `›` prompt
 marker; assistant output is unframed full-width text with lightweight markdown (paragraphs,
 inline code, fenced code blocks).
 */
struct ChatBubble<Footer: View>: View {
    let role: BubbleRole
    let text: String
    var isStreaming = false
    var hasFooter = true
    @ViewBuilder var footer: () -> Footer
    @Environment(\.scheme) private var c

    var body: some View {
        let blocks = LiteMarkdown.parse(text)
        let isUser = role == .user
        HStack {
            if isUser { Spacer(minLength: 0) }
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                if isUser {
                    Text("›").textStyle(.code).foregroundStyle(c.primary).padding(.trailing, 8)
                }
                VStack(alignment: .leading, spacing: Tokens.Spacing.chatBlockGap) {
                    ForEach(Array(blocks.enumerated()), id: \.offset) { _, block in MarkdownBlockView(block: block) }
                    if isStreaming && blocks.isEmpty {
                        HalftoneStrip(color: c.primary).padding(.vertical, 6)
                    }
                    if hasFooter { footer() }
                }
                .frame(maxWidth: isUser ? nil : .infinity, alignment: .leading)
            }
            .textSelection(.enabled)
            .padding(.horizontal, isUser ? Tokens.Spacing.chatUserPaddingHorizontal : Tokens.Spacing.chatAssistantPaddingHorizontal)
            .padding(.vertical, isUser ? Tokens.Spacing.chatUserPaddingVertical : Tokens.Spacing.chatAssistantPaddingVertical)
            .background {
                if isUser {
                    RoundedRectangle(cornerRadius: AutobotShapes.medium).fill(c.surfaceContainerHigh)
                    RoundedRectangle(cornerRadius: AutobotShapes.medium).strokeBorder(c.outlineVariant, lineWidth: Tokens.Stroke.border)
                }
            }
            .frame(maxWidth: isUser ? Tokens.Spacing.chatUserMaxWidth : .infinity, alignment: isUser ? .trailing : .leading)
            if !isUser { Spacer(minLength: 0) }
        }
        .frame(maxWidth: .infinity)
    }
}

extension ChatBubble where Footer == EmptyView {
    init(role: BubbleRole, text: String, isStreaming: Bool = false) {
        self.init(role: role, text: text, isStreaming: isStreaming, hasFooter: false, footer: { EmptyView() })
    }
}

private struct MarkdownBlockView: View {
    let block: MdBlock
    @Environment(\.scheme) private var c

    var body: some View {
        switch block {
        case .paragraph(let spans):
            Text(attributed(spans))
                .textStyle(.bodyLarge)
                .foregroundStyle(c.onSurface)
                .fixedSize(horizontal: false, vertical: true)
        case .codeBlock(let language, let code, _):
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    MicroLabel(language ?? "code", color: c.tertiary)
                    Spacer()
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

    private func attributed(_ spans: [MdSpan]) -> AttributedString {
        var out = AttributedString()
        for span in spans {
            switch span {
            case .text(let t):
                out += AttributedString(t)
            case .inlineCode(let code):
                var a = AttributedString(code)
                a.font = AutobotFonts.uiFont(Tokens.Typography.code, size: Tokens.Typography.bodyLarge.size - 1)
                a.foregroundColor = c.tertiary
                a.backgroundColor = c.surfaceContainerHighest
                out += a
            }
        }
        return out
    }
}

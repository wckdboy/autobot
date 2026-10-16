import SwiftUI

enum BubbleRole { case user, assistant }

/**
 A transcript message. User input is an outlined, right-aligned block with a mono `›` prompt
 marker; assistant output is unframed full-width text with chat markdown (`MarkdownBlocks`:
 headings, lists, quotes, tables, code, emphasis, links). A tapped link only opens after the
 prompt confirms it; nothing in a message is ever fetched.
 */
struct ChatBubble<Footer: View>: View {
    let role: BubbleRole
    let text: String
    var isStreaming = false
    var hasFooter = true
    @ViewBuilder var footer: () -> Footer
    @Environment(\.scheme) private var c
    /// The link the user tapped, waiting for "Open in browser".
    @State private var link: URL?

    var body: some View {
        let _ = InkProbe.hit("body.chatBubble")
        let blocks = MarkdownCache.shared.blocks(text)
        let isUser = role == .user
        HStack {
            if isUser { Spacer(minLength: 0) }
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                if isUser {
                    Text("›").textStyle(.code).foregroundStyle(c.primary).padding(.trailing, 8)
                }
                VStack(alignment: .leading, spacing: Tokens.Spacing.chatBlockGap) {
                    MarkdownBlocks(blocks: blocks)
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
        // Links never open by themselves: a tap only asks.
        .environment(\.openURL, OpenURLAction { url in
            link = url
            return .handled
        })
        .alert("Open link?", isPresented: Binding(get: { link != nil }, set: { if !$0 { link = nil } }), presenting: link) { url in
            Button(LinkPrompt.action(url)) { UIApplication.shared.open(url) }
            Button("Cancel", role: .cancel) {}
        } message: { url in
            Text(LinkPrompt.message(url))
        }
    }
}

extension ChatBubble where Footer == EmptyView {
    init(role: BubbleRole, text: String, isStreaming: Bool = false) {
        self.init(role: role, text: text, isStreaming: isStreaming, hasFooter: false, footer: { EmptyView() })
    }
}

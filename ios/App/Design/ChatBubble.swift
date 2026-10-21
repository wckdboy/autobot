import SwiftUI

/**
 An answer in a transcript: unframed full-width text with chat markdown (`MarkdownBlocks`:
 headings, lists, quotes, tables, code, emphasis, links) and an optional [footer] (status and
 actions). While it streams with no words yet, a small system spinner stands where the text will
 start. A tapped link only opens after the prompt confirms it; nothing in a message is ever
 fetched. The owner's prompts are [PromptLine]s (or the run header), not bubbles.
 */
struct ChatBubble<Footer: View>: View {
    let text: String
    var isStreaming = false
    var hasFooter = true
    @ViewBuilder var footer: () -> Footer
    /// The link the user tapped, waiting for "Open in browser".
    @State private var link: URL?

    var body: some View {
        let _ = InkProbe.hit("body.chatBubble")
        let blocks = MarkdownCache.shared.blocks(text)
        VStack(alignment: .leading, spacing: 8) {
            MarkdownBlocks(blocks: blocks)
            if isStreaming && blocks.isEmpty { TypingIndicator() }
            if hasFooter { footer() }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
        .padding(.horizontal, 2)
        .padding(.vertical, 4)
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
    init(text: String, isStreaming: Bool = false) {
        self.init(text: text, isStreaming: isStreaming, hasFooter: false, footer: { EmptyView() })
    }
}

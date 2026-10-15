import SwiftUI

/**
 Collapsible reasoning trace: an ultraviolet rule with a `∴ reasoning` header. Collapsed it
 shows the last line as a ticker while [isThinking]; expanded it shows the full trace.
 */
struct ThinkingDisclosure: View {
    let reasoning: String
    var isThinking = false
    @State private var expanded: Bool
    @Environment(\.scheme) private var c

    init(reasoning: String, isThinking: Bool = false, initiallyExpanded: Bool = false) {
        self.reasoning = reasoning
        self.isThinking = isThinking
        _expanded = State(initialValue: initiallyExpanded)
    }

    var body: some View {
        let accent = c.secondary
        HStack(alignment: .top, spacing: 0) {
            Rectangle().fill(accent.opacity(0.6)).frame(width: Tokens.Stroke.thinkingRule)
            VStack(alignment: .leading, spacing: 0) {
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
                } label: {
                    HStack(spacing: 8) {
                        if isThinking { HalftoneStrip(color: accent, cell: 3) }
                        MicroLabel(isThinking ? "∴ thinking" : "∴ reasoning", color: accent)
                        MicroLabel("\(reasoning.count / 4) tok", color: c.outline)
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.down")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(c.onSurfaceVariant)
                            .rotationEffect(.degrees(expanded ? 180 : 0))
                    }
                    .padding(.horizontal, Tokens.Spacing.thinkingPaddingHorizontal)
                    .padding(.vertical, Tokens.Spacing.thinkingPaddingVertical)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .accessibilityLabel(expanded ? "Hide reasoning" : "Show reasoning")
                .accessibilityValue(expanded ? "Expanded" : "Collapsed")

                if !expanded && isThinking {
                    let tail = reasoning.trimmingCharacters(in: .whitespacesAndNewlines).components(separatedBy: "\n").last ?? ""
                    Text(String(tail.suffix(160)))
                        .textStyle(.codeBlock)
                        .foregroundStyle(c.onSurfaceVariant)
                        .lineLimit(1)
                        .padding(.horizontal, Tokens.Spacing.thinkingPaddingHorizontal)
                        .padding(.bottom, Tokens.Spacing.thinkingPaddingVertical)
                }
                if expanded {
                    Text(reasoning)
                        .textStyle(.codeBlock)
                        .foregroundStyle(c.onSurfaceVariant)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.horizontal, Tokens.Spacing.thinkingPaddingHorizontal)
                        .padding(.bottom, 10)
                        .transition(.opacity.combined(with: .move(edge: .top)))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

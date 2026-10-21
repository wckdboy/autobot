import SwiftUI

/**
 Collapsible reasoning trace: a `Thinking · N tokens` header with a chevron. Collapsed it shows
 the last line as a ticker while [isThinking]; expanded it shows the full trace. [tokens] is the
 engine's count (without one the size is an estimate, marked ≈); [stop] says why the engine ended
 the reasoning early ("budget": "stopped at budget", "loop": "stopped: repeating").
 */
struct ThinkingDisclosure: View {
    let reasoning: String
    var isThinking = false
    var tokens: Int? = nil
    var stop: String? = nil
    @State private var expanded: Bool

    init(reasoning: String, isThinking: Bool = false, tokens: Int? = nil, stop: String? = nil, initiallyExpanded: Bool = false) {
        self.reasoning = reasoning
        self.isThinking = isThinking
        self.tokens = tokens
        self.stop = stop
        _expanded = State(initialValue: initiallyExpanded)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { expanded.toggle() }
            } label: {
                HStack(spacing: 8) {
                    if isThinking { ProgressView().controlSize(.mini) } else { Image(systemName: "brain").foregroundStyle(.tint) }
                    Text([isThinking ? "Thinking" : "Thought", Self.size(tokens: tokens, reasoning: reasoning), Self.stopNote(stop)].compactMap { $0 }.joined(separator: " · "))
                        .lineLimit(2)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.semibold))
                        .rotationEffect(.degrees(expanded ? 180 : 0))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(expanded ? "Hide reasoning" : "Show reasoning")
            .accessibilityValue(expanded ? "Expanded" : "Collapsed")

            if !expanded && isThinking {
                Text(String(Self.lastLine(reasoning).suffix(160)))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
            }
            if expanded {
                Text(reasoning)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 10)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .fixedSize(horizontal: false, vertical: true)
    }

    /// "412 tokens" when the engine counted them, else "≈412 tok" from the text's size.
    static func size(tokens: Int?, reasoning: String) -> String {
        if let tokens, tokens > 0 { return tokens == 1 ? "1 token" : "\(tokens) tokens" }
        return "≈\(reasoning.utf8.count / 4) tok"
    }

    static func stopNote(_ stop: String?) -> String? {
        switch stop {
        case "budget": "stopped at budget"
        case "loop": "stopped: repeating"
        default: nil
        }
    }

    /// The last non-blank line (trimmed), found from the end: the trace can be long and this runs
    /// on every streamed chunk.
    static func lastLine(_ text: String) -> Substring {
        guard let end = text.lastIndex(where: { !$0.isWhitespace && !$0.isNewline }) else { return "" }
        let upTo = text[...end]
        let start = upTo.lastIndex(where: \.isNewline).map(text.index(after:)) ?? text.startIndex
        return upTo[start...].drop(while: { $0.isWhitespace })
    }
}

import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The terminal's step grammar in the transcript: `▪ Read file` / `└ read · notes.md`.
enum StepGrammar {
    static let mark = "▪"
    static let approvalNeeded = "Approval needed"

    /// The line under a step's title: what it called, hung off the step.
    static func detail(name: String, summary: String) -> String {
        "└ " + [name, summary].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: " · ")
    }
}

/// Where "Retry" goes: the transcript's last answer (or the failed turn's error), and the prompt it sends again.
enum AnswerRetry {
    struct Target: Equatable {
        /// The row that offers Retry.
        let key: String
        /// The last prompt, sent again as a new turn.
        let prompt: String
    }

    /**
     The last answer or failed-turn error, when nothing runs: trailing info and warning notices
     ("stopped") are skipped; a step still at the end means the turn did not finish with an answer.
     */
    static func target(_ items: [SessionItem], running: Bool) -> Target? {
        guard !running else { return nil }
        var key: String?
        var index = items.count - 1
        while index >= 0 {
            switch items[index] {
            case .assistant(let k, let text, _, _, _, _, _, _, _) where !text.isEmpty: key = k
            case .notice(let k, _, .error): key = k
            case .notice: index -= 1; continue
            default: return nil
            }
            break
        }
        guard let key else { return nil }
        for item in items[..<index].reversed() {
            if case .user(_, let text, _) = item {
                let prompt = text.trimmingCharacters(in: .whitespacesAndNewlines)
                return prompt.isEmpty ? nil : Target(key: key, prompt: prompt)
            }
        }
        return nil
    }
}

/// Share, Retry and the long-press menu of a message, the same on every row (as on Android).
@MainActor
struct MessageActions {
    let text: String
    /// Sends the last prompt again (the last answer only).
    var onRetry: (() -> Void)? = nil
    /// Reads the answer aloud (assistant messages only).
    var onSpeak: (() -> Void)? = nil
    /// Opens the message where any part of it can be selected (the long press is the menu's).
    var onSelect: (() -> Void)? = nil

    /// Copy, Share, Retry, Read aloud, Select text: the long-press menu, in that order (as Android's).
    @ViewBuilder func menu() -> some View {
        Button {
            UIPasteboard.general.setItems([[UTType.plainText.identifier: text]], options: [.localOnly: true])
        } label: { Label("Copy", systemImage: "doc.on.doc") }
        ShareLink(item: text) { Label("Share", systemImage: "square.and.arrow.up") }
        if let onRetry {
            Button(action: onRetry) { Label("Retry", systemImage: "arrow.clockwise") }
        }
        if let onSpeak {
            Button(action: onSpeak) { Label("Read aloud", systemImage: "speaker.wave.2") }
        }
        if let onSelect {
            Button(action: onSelect) { Label("Select text", systemImage: "selection.pin.in.out") }
        }
    }
}

/// A message opened for "Select text".
struct SelectableText: Identifiable {
    let id = UUID()
    let text: String
}

/// The message as plain, selectable text (any part of it), with Done.
struct SelectTextSheet: View {
    let text: String
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                SectionLabel("select text")
                Spacer()
                TextAction("Done", color: c.onSurface) { dismiss() }
            }
            .padding(Tokens.Spacing.gutter)
            SelectableTextView(text: text, color: UIColor(c.onSurface))
                .padding(.horizontal, Tokens.Spacing.gutter - 4)
        }
        .background(c.background)
        .presentationDetents([.medium, .large])
    }
}

/// A read-only UITextView: SwiftUI's `Text` selects only as a whole.
private struct SelectableTextView: UIViewRepresentable {
    let text: String
    let color: UIColor

    func makeUIView(context: Context) -> UITextView {
        let view = UITextView()
        view.isEditable = false
        view.isSelectable = true
        view.backgroundColor = .clear
        view.font = .preferredFont(forTextStyle: .body)
        view.adjustsFontForContentSizeCategory = true
        return view
    }

    func updateUIView(_ view: UITextView, context: Context) {
        view.text = text
        view.textColor = color
    }
}


/// The share icon under an answer: the system share sheet with the message as written (markdown).
struct ShareMessageButton: View {
    let markdown: String
    @Environment(\.scheme) private var c

    var body: some View {
        ShareLink(item: markdown) {
            Image(systemName: "square.and.arrow.up")
                .symbolSize(17)
                .foregroundStyle(c.onSurfaceVariant)
        }
        .accessibilityLabel("Share message")
        .compactActionFrame()
    }
}

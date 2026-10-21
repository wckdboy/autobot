import AutobotShared
import SwiftUI

/*
 The composer every chat shares (the phone's chats and the node's agent): one Liquid Glass panel
 floating over the transcript's end, holding the tools, a field that grows to
 `Composer.MAX_LINES` lines and a round Send (or Stop). Place it with
 `.safeAreaInset(edge: .bottom)` on the transcript.
 */

/// The composer's growing text field.
struct ComposerField: View {
    @Binding var text: String
    let placeholder: String
    var focused: FocusState<Bool>.Binding

    var body: some View {
        TextField(placeholder, text: $text, axis: .vertical)
            .lineLimit(1 ... Int(Composer.shared.MAX_LINES))
            .focused(focused)
            .textInputAutocapitalization(.sentences)
            .padding(.vertical, 10)
            .padding(.leading, 4)
    }
}

/// Send (or Queue while a turn runs): a round prominent glass button with an arrow.
struct ComposerSendButton: View {
    let enabled: Bool
    var label = "Send"
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "arrow.up").font(.body.weight(.semibold)).frame(width: 22, height: 22)
        }
        .glassProminentButton()
        .buttonBorderShape(.circle)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// Stops the running turn: a round glass button with a square.
struct ComposerStopButton: View {
    var label = "Stop"
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "stop.fill").font(.body.weight(.semibold)).frame(width: 22, height: 22)
        }
        .glassButton()
        .buttonBorderShape(.circle)
        .accessibilityLabel(label)
    }
}

/// A tool beside the field (attach, dictate): a 40 pt icon in the tint.
struct ComposerToolLabel: View {
    let systemImage: String

    var body: some View {
        Image(systemName: systemImage)
            .font(.title3)
            // At least 40 pt: at the largest text sizes the symbol is bigger and must not spill out.
            .frame(minWidth: 40, minHeight: 40)
            .contentShape(Rectangle())
    }
}

/// While an answer has no words yet: the system's spinner, small, where the text will start.
struct TypingIndicator: View {
    var body: some View {
        ProgressView()
            .controlSize(.small)
            .frame(height: 24, alignment: .leading)
            .accessibilityLabel("Writing")
    }
}

/// A prompt the owner sent, after the first: a tinted `›` and the words in semibold.
struct PromptLine: View {
    let text: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Image(systemName: "chevron.right").font(.caption.weight(.bold)).foregroundStyle(.tint).accessibilityHidden(true)
            Text(text).font(.body.weight(.semibold)).foregroundStyle(.primary).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

extension View {
    /// The composer's ground: one glass panel floating over the transcript's end.
    func composerPanel() -> some View {
        glassSurface(in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .padding(.horizontal, 10)
            .padding(.bottom, 6)
    }
}

/**
 What was typed in each chat's composer and not sent yet, kept while the app runs: leaving a chat
 (back, another chat in the iPad list, voice mode and back, another tab's shortcut) and coming back
 finds the words where they were. Memory only, like the transcript on screen: nothing is written
 to disk, and a panic wipe drops it with the container.
 */
@MainActor
final class ComposerDrafts {
    private var texts: [String: String] = [:]

    func text(_ key: String) -> String { texts[key] ?? "" }

    func set(_ key: String, _ text: String) {
        texts[key] = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text
    }

    func removeAll() { texts = [:] }
}

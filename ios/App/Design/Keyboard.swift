import AutobotShared
import SwiftUI
import UIKit

/*
 The keyboard pattern every screen with a field uses (Android: `Keyboard.kt`).

 - A chat: the transcript scrolls, `.scrollDismissesKeyboard(.interactively)`, anchored at the
   bottom; the composer sits in `.safeAreaInset(edge: .bottom)` ([ComposerBar]), so it rides on the
   keyboard and the last message stays right above it.
 - A form (API key, pairing code, bot settings): [KeyboardForm], whose focused field scrolls into
   view and whose primary button stays above the keyboard.
 - While `@Environment(\.keyboardUp)` is true, hide what is not the field or what it answers
   (chips, banners, tips, empty-state art): `if Composer.shared.shows(part: .banner, keyboardUp: up)`
   (the rule lives in `shared/composer/Composer.kt`). The root publishes it (`.tracksKeyboard()`).
 */

extension EnvironmentValues {
    /// Whether the software keyboard is on screen (it takes about a third of it).
    @Entry var keyboardUp = false
}

/// Publishes `\.keyboardUp` to everything under it. Applied once, at the app's root.
struct KeyboardTracker: ViewModifier {
    @State private var up = false

    func body(content: Content) -> some View {
        content
            .environment(\.keyboardUp, up)
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
                // A hardware keyboard leaves only the shortcut bar on screen: that is not "up".
                guard let frame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
                let screen = (note.object as? UIScreen)?.bounds ?? UIScreen.main.bounds
                let shown = Self.isUp(frame: frame, screen: screen)
                if shown != up { up = shown }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
                if up { up = false }
            }
    }

    /// Taller than the hardware keyboard's shortcut bar, shorter than any software keyboard.
    static let minimumHeight: CGFloat = 120

    /// Whether a keyboard ending at [frame] is up: on screen and taller than the shortcut bar.
    static func isUp(frame: CGRect, screen: CGRect) -> Bool {
        frame.minY < screen.maxY && frame.height > minimumHeight
    }
}

extension View {
    /// Publishes whether the keyboard is up as `\.keyboardUp` (once, at the root).
    func tracksKeyboard() -> some View { modifier(KeyboardTracker()) }
}

/**
 The composer of every chat: [leading] buttons (attach, dictate), the field (grows to
 `Composer.MAX_LINES` lines, then scrolls inside) and [trailing] (a [SendButton] or [StopButton]).
 [above] sits between the hairline and the field (attachments, slash commands, words heard).
 Place it with `.safeAreaInset(edge: .bottom)` on the transcript.
 */
struct ComposerBar<Above: View, Leading: View, Trailing: View>: View {
    @Binding var text: String
    let placeholder: String
    var enabled = true
    var focused: FocusState<Bool>.Binding? = nil
    @ViewBuilder var above: () -> Above
    @ViewBuilder var leading: () -> Leading
    @ViewBuilder var trailing: () -> Trailing
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(spacing: 0) {
            above()
            HStack(alignment: .bottom, spacing: 8) {
                leading()
                ConsoleTextField(text: $text, placeholder: placeholder, maxLines: Int(Composer.shared.MAX_LINES), enabled: enabled, focus: focused)
                trailing()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .background(c.background)
        .hairlineEdge(top: true, bottom: false)
    }
}

extension ComposerBar where Above == EmptyView {
    init(text: Binding<String>, placeholder: String, enabled: Bool = true, focused: FocusState<Bool>.Binding? = nil,
         @ViewBuilder leading: @escaping () -> Leading, @ViewBuilder trailing: @escaping () -> Trailing) {
        self.init(text: text, placeholder: placeholder, enabled: enabled, focused: focused, above: { EmptyView() }, leading: leading, trailing: trailing)
    }
}

/// The side of the composer's buttons, the field's one-line height.
let composerButtonSize: CGFloat = 52

/// Send (or Queue while a turn runs): filled primary when it can send.
struct SendButton: View {
    let enabled: Bool
    var label = "Send"
    let action: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        Button(action: action) {
            // Pointing right, as Android's Material "Send".
            Image(systemName: "paperplane.fill")
                .symbolSize(18, weight: .semibold)
                .rotationEffect(.degrees(45))
                .foregroundStyle(enabled ? c.onPrimary : c.onSurface.opacity(0.38))
                .frame(width: composerButtonSize, height: composerButtonSize)
                .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(enabled ? c.primary : c.onSurface.opacity(0.12)))
        }
        .buttonStyle(PressStyle())
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

/// Stops the running turn: a primary square on a raised ground.
struct StopButton: View {
    var label = "Stop"
    let action: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        Button(action: action) {
            Rectangle().fill(c.primary).frame(width: 14, height: 14)
                .frame(width: composerButtonSize, height: composerButtonSize)
                .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainerHigh))
        }
        .buttonStyle(PressStyle())
        .accessibilityLabel(label)
    }
}

/**
 A form with fields (API key, pairing code, bot settings): [content] scrolls, a focused field
 scrolls into view above the keyboard, and [footer] (the primary button) stays pinned above the
 keyboard, never under it. A drag down the form lowers the keyboard.
 */
struct KeyboardForm<Content: View, Footer: View>: View {
    @ViewBuilder var content: () -> Content
    @ViewBuilder var footer: () -> Footer
    @Environment(\.scheme) private var c

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) { content() }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if Footer.self != EmptyView.self {
                VStack(alignment: .leading, spacing: 8) { footer() }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .frame(maxWidth: .infinity)
                    .background(c.background)
                    .hairlineEdge(top: true, bottom: false)
            }
        }
    }
}

extension KeyboardForm where Footer == EmptyView {
    init(@ViewBuilder content: @escaping () -> Content) {
        self.init(content: content, footer: { EmptyView() })
    }
}

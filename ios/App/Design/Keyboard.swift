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

import SwiftUI

/// The panic wipe confirmation (Settings › Privacy & security): type WIPE, then every key and all
/// data are destroyed and the app closes.
private struct PanicWipeAlert: ViewModifier {
    @Binding var isPresented: Bool
    @Environment(AppContainer.self) private var app
    @State private var typed = ""
    @State private var wiping = false

    func body(content: Content) -> some View {
        content.alert("Wipe everything?", isPresented: $isPresented) {
            TextField("Confirmation", text: $typed)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            Button("Wipe", role: .destructive) { wipe() }
                .disabled(typed != PanicWipeConfirmation.word)
            Button("Cancel", role: .cancel) { typed = "" }
        } message: {
            Text("All keys and data will be destroyed and the app will close. Type WIPE to confirm.")
        }
        .onChange(of: isPresented) { _, shown in if shown { typed = "" } }
    }

    /// Wipes all local data and keys, then kills the process. Requires the typed confirmation.
    private func wipe() {
        guard typed == PanicWipeConfirmation.word, !wiping else { return }
        wiping = true
        Task {
            _ = await app.panicWipe.wipe()
            app.panicWipe.killProcess()
        }
    }
}

enum PanicWipeConfirmation {
    static let word = "WIPE"
}

extension View {
    func panicWipeAlert(isPresented: Binding<Bool>) -> some View { modifier(PanicWipeAlert(isPresented: isPresented)) }
}

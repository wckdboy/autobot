import SwiftUI

/// Shown instead of all app content while locked. Prompts automatically on first display. The
/// paper drifts while it waits for biometrics, and prints away on unlock ([AnyTransition.inkPrintAway]).
struct LockScreen: View {
    let message: String?
    let onUnlock: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            AutobotMark(size: 36)
            Text("Autobot is locked").textStyle(.headlineSmall).foregroundStyle(c.onSurface)
            Text("Your conversations are encrypted on this device")
                .textStyle(.bodyMedium)
                .foregroundStyle(c.onSurfaceVariant)
                .multilineTextAlignment(.center)
            if let message {
                Text(message).textStyle(.bodySmall).foregroundStyle(c.error).multilineTextAlignment(.center)
            }
            PrimaryButton("Unlock", action: onUnlock).frame(maxWidth: 240)
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .inkGround()
        .background(c.background.ignoresSafeArea())
        .task { onUnlock() }
    }
}

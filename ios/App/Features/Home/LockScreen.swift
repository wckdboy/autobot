import SwiftUI

/// Shown instead of all app content while locked. Prompts automatically on first display.
struct LockScreen: View {
    let message: String?
    let onUnlock: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "lock.fill")
                .font(.system(size: 40, weight: .regular))
                .foregroundStyle(c.primary)
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
        .background(c.background.ignoresSafeArea())
        .task { onUnlock() }
    }
}

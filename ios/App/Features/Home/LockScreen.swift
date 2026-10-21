import SwiftUI

/// Shown instead of all app content while locked. Prompts automatically on first display.
struct LockScreen: View {
    let message: String?
    let onUnlock: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            AutobotMark(size: 36)
            Text("Autobot is locked").font(.title2.weight(.semibold))
            Text("Your conversations are encrypted on this device")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let message {
                Text(message).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center)
            }
            Button(action: onUnlock) {
                Text("Unlock").frame(maxWidth: 240)
            }
            .glassProminentButton()
            .controlSize(.large)
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground).ignoresSafeArea())
        .task { onUnlock() }
    }
}

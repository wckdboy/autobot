import AutobotShared
import SwiftUI

/// First-run welcome: the large purple pixel a, the wordmark, one sentence, one primary button.
/// Pairing a PC or adding a key stay one quiet link away.
struct WelcomeScreen: View {
    let onSetUp: () -> Void
    let onPairPc: () -> Void
    let onAddApiKey: () -> Void

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            // At large text sizes the column scrolls rather than pushing the button off the screen.
            ViewThatFits(in: .vertical) {
                column(fill: true)
                ScrollView { column(fill: false).padding(.top, 48) }.scrollBounceBehavior(.basedOnSize)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .frame(maxWidth: 520)
        }
    }

    private func column(fill: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if fill { Spacer() }
            AutobotMark(size: 72)
            Spacer().frame(height: 28)
            Wordmark()
            Spacer().frame(height: 12)
            Text("Private AI on your phone.")
                .font(.largeTitle.weight(.bold))
                .fixedSize(horizontal: false, vertical: true)
            if fill { Spacer() }
            Spacer().frame(height: 32)
            Button(action: onSetUp) {
                Text("Set up this phone").frame(maxWidth: .infinity)
            }
            .glassProminentButton()
            .controlSize(.large)
            Spacer().frame(height: 8)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 24) {
                    Spacer()
                    actions
                    Spacer()
                }
                VStack(spacing: 4) { actions }.frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.secondary)
            .controlSize(.large)
        }
    }

    @ViewBuilder private var actions: some View {
        Button(NodePairingWords.shared.REMOTE_ROW, action: onPairPc)
        Button("Add an API key", action: onAddApiKey)
    }
}

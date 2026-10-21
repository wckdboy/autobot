import AutobotShared
import SwiftUI

/// First-run welcome (App UI v4): the large purple pixel a, the wordmark, one sentence, one
/// primary button. Pairing a PC or adding a key stay one quiet link away.
struct WelcomeScreen: View {
    let onSetUp: () -> Void
    let onPairPc: () -> Void
    let onAddApiKey: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        ZStack {
            c.background.ignoresSafeArea()
            // At large text sizes the column scrolls rather than pushing the button off the screen.
            ViewThatFits(in: .vertical) {
                column(fill: true)
                ScrollView { column(fill: false).padding(.top, 48) }.scrollBounceBehavior(.basedOnSize)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
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
                .textStyle(.displaySmall)
                .foregroundStyle(c.onBackground)
                .fixedSize(horizontal: false, vertical: true)
            if fill { Spacer() }
            Spacer().frame(height: 32)
            PrimaryButton("Set up this phone", action: onSetUp)
            Spacer().frame(height: 8)
            ViewThatFits(in: .horizontal) {
                HStack {
                    Spacer()
                    actions
                    Spacer()
                }
                VStack(spacing: 4) { actions }.frame(maxWidth: .infinity)
            }
        }
    }

    @ViewBuilder private var actions: some View {
        TextAction(NodePairingWords.shared.REMOTE_ROW, color: c.onSurfaceVariant, action: onPairPc)
        TextAction("Add an API key", color: c.onSurfaceVariant, action: onAddApiKey)
    }
}

import SwiftUI

/// First-run welcome: the wordmark, the promise, three routes, set up.
struct WelcomeScreen: View {
    let onSetUp: () -> Void
    let onPairPc: () -> Void
    let onAddApiKey: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        ZStack(alignment: .topLeading) {
            c.background.ignoresSafeArea()
            Wordmark()
                .padding(16)

            // At large text sizes the column scrolls rather than pushing the routes and the set-up
            // button off the screen; at the standard sizes it sits at the bottom, as designed.
            ViewThatFits(in: .vertical) {
                column(fill: true)
                ScrollView { column(fill: false).padding(.top, 72) }.scrollBounceBehavior(.basedOnSize)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
    }

    private func column(fill: Bool) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            if fill { Spacer() }
            SectionLabel("welcome")
            Text("Local or cloud? Both.").textStyle(.displayMedium).foregroundStyle(c.onBackground)
                .fixedSize(horizontal: false, vertical: true)
            Text("Small models run on this phone. Big ones run on your own machine. The cloud is optional, and you hold the keys.")
                .textStyle(.bodyLarge)
                .foregroundStyle(c.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            // The three routes on one line, or stacked when the text is too big for one.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) { routes }
                VStack(alignment: .leading, spacing: 6) { routes }
            }
            Spacer().frame(height: 6)
            PrimaryButton("Set up this phone", action: onSetUp)
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

    @ViewBuilder private var routes: some View {
        RouteTag(route: .localGpu)
        RouteTag(route: .pcLan)
        RouteTag(route: .cloudApi)
    }

    @ViewBuilder private var actions: some View {
        TextAction("Pair a PC", color: c.primary, action: onPairPc)
        TextAction("Add an API key", color: c.primary, action: onAddApiKey)
    }
}

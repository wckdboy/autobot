import SwiftUI

/// First-run welcome (mockup 01): halftone hero, the promise, three routes, set up.
struct WelcomeScreen: View {
    let onSetUp: () -> Void
    let onPairPc: () -> Void
    let onAddApiKey: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        ZStack(alignment: .topLeading) {
            c.background.ignoresSafeArea()
            ZStack {
                // The hero field drifts, very slowly (living paper).
                InkGround(plate: false, hero: true, cell: 10)
                LinearGradient(stops: [.init(color: .clear, location: 0.5), .init(color: c.background, location: 1)], startPoint: .top, endPoint: .bottom)
            }
            .frame(height: 380 + 60)
            .frame(maxWidth: .infinity)
            .ignoresSafeArea(edges: .top)

            HStack(spacing: 0) {
                AutobotMark()
                Text("  AUTOBOT").textStyle(.titleLarge).foregroundStyle(c.onBackground)
            }
            .padding(16)

            VStack(alignment: .leading, spacing: 14) {
                Spacer()
                SectionLabel("01 — welcome")
                Text("Local or\ncloud? Both.").textStyle(.displayMedium).foregroundStyle(c.onBackground)
                Text("Small models run on this phone. Big ones run on your own machine. The cloud is optional, and you hold the keys.")
                    .textStyle(.bodyLarge)
                    .foregroundStyle(c.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 6) {
                    RouteTag(route: .localGpu)
                    RouteTag(route: .pcLan)
                    RouteTag(route: .cloudApi)
                }
                Spacer().frame(height: 6)
                PrimaryButton("Set up this phone", action: onSetUp)
                HStack {
                    Spacer()
                    TextAction("Pair a PC", color: c.primary, action: onPairPc)
                    TextAction("Add an API key", color: c.primary, action: onAddApiKey)
                    Spacer()
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
    }
}

import SwiftUI

/**
 One line in a sheet that needs the network (pairing a node, adding a cloud provider) while
 Offline mode is on, with the one tap that allows it (Direct; Privacy Center has the other routes).
 The same line as Android's `OfflineNotice`.
 */
struct OfflineNotice: View {
    @Environment(AppContainer.self) private var app

    var body: some View {
        if app.settings.settings.mode == .offline {
            VStack(alignment: .leading, spacing: 6) {
                Label("Offline mode is on, so this can't reach the network.", systemImage: "wifi.slash")
                    .font(.subheadline)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
                Button("Allow network") { app.settings.setNetworkMode(.direct) }
                    .buttonStyle(.borderless)
                    .font(.subheadline.weight(.semibold))
            }
        }
    }
}

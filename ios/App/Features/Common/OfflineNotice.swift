import SwiftUI

/**
 One line in a sheet that needs the network (pairing a node, adding a cloud provider) while
 Offline mode is on, with the one tap that allows it (Direct; Privacy Center has the other routes).
 The same line as Android's `OfflineNotice`.
 */
struct OfflineNotice: View {
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    var body: some View {
        if app.settings.settings.mode == .offline {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("Offline mode is on, so this can't reach the network.")
                    .textStyle(.bodySmall).foregroundStyle(c.inks.amber)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                TextAction("Allow network", color: c.onSurface) { app.settings.setNetworkMode(.direct) }
            }
        }
    }
}

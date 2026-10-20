import AutobotShared
import SwiftUI
import UIKit

/**
 Pairs this iPhone with a computer running Invincible ("My node"): the code from `invincible pair`
 (pasted, or filled in by an `invincible://pair?…` link), the node's key fingerprint to compare
 with what the computer printed, and this phone's name there. Pairing runs only when the user taps
 Pair; the pairing is stored in the Keychain as the provider's secret and never sent as a key.
 */
struct NodePairingSheet: View {
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var code: String
    @State private var deviceName = UIDevice.current.name
    @State private var pairing = false
    @State private var problem: String?
    let onDone: () -> Void

    init(code: String = "", onDone: @escaping () -> Void) {
        _code = State(initialValue: code)
        self.onDone = onDone
    }

    private var runtime: SharedAgentRuntime? { app.agent as? SharedAgentRuntime }
    private var fingerprint: String? { runtime?.pairingFingerprint(code) }

    var body: some View {
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel("pair with my node")
                Text("My node (Invincible)").textStyle(.titleLarge).foregroundStyle(c.onSurface)
                Text("On the computer, run `invincible pair`. Point the Camera at the QR code it shows, or paste the code here.")
                    .textStyle(.bodySmall)
                    .foregroundStyle(c.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                OfflineNotice()
                ConsoleTextField(text: $code, label: "Pairing code", kind: .mono)

                if let fingerprint {
                    MicroLabel("node key")
                    Text(fingerprint).textStyle(.titleMedium).foregroundStyle(c.onSurface).monospaced()
                    MicroLabel("compare with the key the computer printed")
                } else if !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Text("That is not an Invincible pairing code (invincible://pair?…).").textStyle(.bodySmall).foregroundStyle(c.error)
                }
                ConsoleTextField(text: $deviceName, label: "This phone's name on the node")
                Text("Pairing is end-to-end encrypted (Noise) and needs no account. The kill switch and the network route apply, and the connection shows in the Privacy Center.")
                    .textStyle(.bodySmall)
                    .foregroundStyle(c.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                if let problem {
                    Text(problem).textStyle(.bodySmall).foregroundStyle(c.error).fixedSize(horizontal: false, vertical: true)
                }
                PrimaryButton("Pair", enabled: fingerprint != nil && !deviceName.trimmingCharacters(in: .whitespaces).isEmpty, busy: pairing) { pair() }
                GhostButton("Cancel") { onDone() }
            }
            .padding(Tokens.Spacing.gutter)
        }
        .background(c.background)
        .presentationDetents([.large])
    }

    private func pair() {
        guard let runtime, !pairing else { return }
        pairing = true
        problem = nil
        let name = deviceName.trimmingCharacters(in: .whitespaces)
        Task {
            let result = await runtime.pairNode(code: code, deviceName: name)
            pairing = false
            guard let secret = result.secret, let node = result.node else {
                problem = result.error ?? "Pairing failed"
                return
            }
            let provider = Provider(
                id: app.providers.newId(),
                kind: .invincibleNode,
                displayName: node,
                baseUrl: "http://invincible/v1",
                defaultModel: "auto",
                presetId: "invincible",
            )
            do {
                let stored = try app.providers.save(provider, apiKey: .set(Secret(secret)))
                let spec = ProviderSpec(
                    id: stored.id, kind: SharedMapping.kind(stored.kind), displayName: stored.displayName, baseUrl: stored.baseUrl,
                    apiKey: secret, defaultModel: stored.defaultModel, route: stored.routing.rawValue, presetId: stored.presetId,
                    modelsJson: nil, helperModel: nil,
                )
                let providers = app.providers
                Task {
                    let (json, _) = await runtime.listModels(spec)
                    if let json, let cache = ModelCache.companion.decode(json: json) { providers.saveModels(stored.id, fetchedAt: cache.fetchedAt, json: json) }
                }
                onDone()
            } catch {
                problem = saveFailure(error)
            }
        }
    }
}

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
        let offline = app.settings.settings.mode == .offline
        NavigationStack {
            Form {
                Section {
                    // Invincible's pixel i: this is about the node.
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        NodeMark()
                        Text("My node (Invincible)").font(.title3.weight(.semibold))
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.isHeader)
                    Text("On the computer, run `invincible pair`. Point the Camera at the QR code it shows, or paste the code here.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                if offline {
                    Section { OfflineNotice() }
                }
                Section {
                    TextField("Pairing code", text: $code, axis: .vertical)
                        .font(.body.monospaced())
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .lineLimit(1 ... 4)
                } header: {
                    Text("Pairing code")
                } footer: {
                    if fingerprint == nil, !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text("That is not an Invincible pairing code (invincible://pair?…).").foregroundStyle(.red)
                    }
                }
                if let fingerprint {
                    Section {
                        Text(fingerprint).font(.title3.monospaced()).textSelection(.enabled)
                    } header: {
                        Text("Node key")
                    } footer: {
                        Text("Compare with the key the computer printed")
                    }
                }
                Section {
                    TextField("This phone's name on the node", text: $deviceName)
                } header: {
                    Text("This phone's name on the node")
                } footer: {
                    Text("Pairing is end-to-end encrypted (Noise) and needs no account. The kill switch and the network route apply, and the connection shows in the Privacy Center.")
                }
                if let problem {
                    Section { Text(problem).foregroundStyle(.red) }
                }
                Section {
                    Button { pair() } label: {
                        Group { if pairing { ProgressView() } else { Text("Pair") } }.frame(maxWidth: .infinity)
                    }
                    .primaryActionStyle()
                    .controlSize(.large)
                    .disabled(fingerprint == nil || deviceName.trimmingCharacters(in: .whitespaces).isEmpty || pairing)
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            .formStyle(.grouped)
            .navigationTitle("Pair with my node")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { onDone() } }
            }
        }
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

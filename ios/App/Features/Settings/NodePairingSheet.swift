import AutobotShared
import SwiftUI
import UIKit

/**
 Pairs this iPhone with a computer running Invincible ("Pair a node"): the code from `invincible pair`
 (pasted, or filled in by an `invincible://pair?…` link), the node's key fingerprint to compare
 with what the computer printed, and this phone's name there. Pairing runs only when the user taps
 Pair; the pairing is stored in the Keychain as the provider's secret and never sent as a key.
 Pairing again with a node already here replaces that provider's pairing (its chats stay), and
 Cancel stops a pairing still running. The words are shared with Android (`NodePairingWords`).
 */
struct NodePairingSheet: View {
    @Environment(AppContainer.self) private var app
    @State private var code: String
    @State private var deviceName = UIDevice.current.name
    @State private var pairing: Task<Void, Never>?
    @State private var problem: String?
    let onDone: () -> Void

    private let words = NodePairingWords.shared

    init(code: String = "", onDone: @escaping () -> Void) {
        _code = State(initialValue: code)
        self.onDone = onDone
    }

    private var runtime: SharedAgentRuntime? { app.agent as? SharedAgentRuntime }
    private var fingerprint: String? { runtime?.pairingFingerprint(code) }
    private var addresses: [String] { PairingCode.companion.parse(text: code.trimmingCharacters(in: .whitespacesAndNewlines))?.addresses ?? [] }

    var body: some View {
        let offline = app.settings.settings.mode == .offline
        NavigationStack {
            Form {
                Section {
                    // Invincible's pixel i: this is about the node.
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        NodeMark()
                        Text(LocalizedStringKey(words.HOW))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                if offline {
                    Section { OfflineNotice() }
                }
                Section {
                    TextField(words.CODE, text: $code, axis: .vertical)
                        .font(.body.monospaced())
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        .lineLimit(1 ... 4)
                } header: {
                    Text(words.CODE)
                } footer: {
                    if fingerprint == nil, !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        Text(words.NOT_A_CODE).foregroundStyle(.red)
                    }
                }
                if let fingerprint {
                    Section {
                        Text(fingerprint).font(.title3.monospaced()).textSelection(.enabled)
                    } header: {
                        Text(words.KEY)
                    } footer: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(LocalizedStringKey(words.COMPARE))
                            // Where the node is: the addresses its code carries.
                            if !addresses.isEmpty { Text(addresses.joined(separator: " · ")).monospaced() }
                        }
                    }
                }
                Section {
                    TextField(words.NAME, text: $deviceName)
                } header: {
                    Text(words.NAME)
                } footer: {
                    Text(words.NOTE)
                }
                if let problem {
                    Section { Text(problem).foregroundStyle(.red) }
                }
                Section {
                    Button { pair() } label: {
                        Group { if pairing != nil { ProgressView() } else { Text(words.PAIR) } }.frame(maxWidth: .infinity)
                    }
                    .glassProminentButton()
                    .controlSize(.large)
                    .disabled(fingerprint == nil || deviceName.trimmingCharacters(in: .whitespaces).isEmpty || pairing != nil)
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            .formStyle(.grouped)
            .navigationTitle(words.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // Cancel means cancel: a pairing still running stops and saves nothing.
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { cancel() } }
            }
        }
        .presentationDetents([.large])
        .onDisappear { pairing?.cancel() }
    }

    /// Cancel means cancel: a pairing still running saves nothing.
    private func cancel() {
        pairing?.cancel()
        pairing = nil
        onDone()
    }

    private func pair() {
        guard let runtime, pairing == nil else { return }
        problem = nil
        let name = deviceName.trimmingCharacters(in: .whitespaces)
        let providers = app.providers
        // A node already paired: the new pairing replaces that provider's (same key on the node).
        let nodes = providers.providers.filter { $0.kind == .invincibleNode }
        let secrets = nodes.map { providers.apiKey($0)?.reveal() ?? "" }
        let existing = runtime.pairedNodeIndex(code, secrets: secrets).map { nodes[$0] }
        let previous = existing.flatMap { providers.apiKey($0)?.reveal() }
        let code = code
        pairing = Task {
            let result = await runtime.pairNode(code: code, deviceName: name, previous: previous)
            guard !Task.isCancelled else { return }
            pairing = nil
            guard let secret = result.secret, let node = result.node else {
                problem = result.error ?? "Pairing failed"
                return
            }
            let provider = existing ?? Provider(
                id: providers.newId(),
                kind: .invincibleNode,
                displayName: node,
                baseUrl: "http://invincible/v1",
                defaultModel: "auto",
                presetId: "invincible",
            )
            do {
                let stored = try providers.save(provider, apiKey: .set(Secret(secret)))
                let spec = ProviderSpec(
                    id: stored.id, kind: SharedMapping.kind(stored.kind), displayName: stored.displayName, baseUrl: stored.baseUrl,
                    apiKey: secret, defaultModel: stored.defaultModel, route: stored.routing.rawValue, presetId: stored.presetId,
                    modelsJson: nil, helperModel: nil,
                )
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

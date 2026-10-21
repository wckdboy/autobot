import SwiftUI

/// Model-hub accounts (port of `AccountsScreen.kt`): tokens are verified against the hub, then
/// kept in the Keychain.
struct AccountsScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var hfToken = ""
    @State private var civitaiKey = ""
    @State private var busy = false
    /// The panel whose check runs (its button shows the loader).
    @State private var busyPanel = ""
    @State private var message: String?
    @State private var confirmMature = false

    var body: some View {
        let accounts = app.accounts.state
        VStack(spacing: 0) {
            AutobotTopBar(title: "Accounts", onBack: { nav.back() })
            Form {
                if let message {
                    Section {
                        HStack {
                            Text(message).foregroundStyle(.tint).frame(maxWidth: .infinity, alignment: .leading)
                            Button("OK") { self.message = nil }.buttonStyle(.borderless)
                        }
                    }
                }
                Section {
                    InfoTitle(
                        accounts.huggingFaceUser.map { "Signed in as \($0)" } ?? "Optional read token",
                        info: "A read token lifts rate limits and unlocks gated models (Llama, Gemma 3…) once you accept their terms on huggingface.co. Create one at huggingface.co/settings/tokens (fine-grained, read access to public gated repos).",
                    )
                    .foregroundStyle(.secondary)
                    if accounts.huggingFaceUser == nil {
                        FormField("hf_…", text: $hfToken, kind: .secret)
                        verifyButton(enabled: !hfToken.trimmingCharacters(in: .whitespaces).isEmpty, panel: "hf") {
                            let token = Secret(hfToken.trimmingCharacters(in: .whitespacesAndNewlines))
                            hfToken = ""
                            run {
                                let user = try await HuggingFaceClient(clients: app.clients, accounts: app.accounts).whoami(token)
                                try app.accounts.setHuggingFace(token, user: user.name)
                                return "Signed in to Hugging Face as \(user.name)"
                            }
                        }
                    } else {
                        Button("Sign out", role: .destructive) { app.accounts.clearHuggingFace() }
                    }
                } header: {
                    Text("Hugging Face")
                } footer: {
                    Text("Model hubs · encrypted")
                }
                Section("Civitai") {
                    InfoTitle(
                        accounts.civitaiUser.map { "Key saved · \($0)" } ?? "Optional API key",
                        info: "Some creators require login to download. Create a key in civitai.com → Account settings → API keys.",
                    )
                    .foregroundStyle(.secondary)
                    if accounts.civitaiUser == nil {
                        FormField("API key", text: $civitaiKey, kind: .secret)
                        verifyButton(enabled: !civitaiKey.trimmingCharacters(in: .whitespaces).isEmpty, panel: "civitai") {
                            let key = Secret(civitaiKey.trimmingCharacters(in: .whitespacesAndNewlines))
                            civitaiKey = ""
                            run {
                                let user = try await CivitaiClient(clients: app.clients, accounts: app.accounts).me(key)
                                try app.accounts.setCivitai(key, user: user)
                                return "Civitai key saved for \(user)"
                            }
                        }
                    } else {
                        Button("Remove key", role: .destructive) { app.accounts.clearCivitai() }
                    }
                    Picker("Host", selection: Binding(get: { accounts.civitaiHost }, set: { app.accounts.setCivitaiHost($0) })) {
                        ForEach(CivitaiHost.allCases, id: \.self) { Text($0.host).tag($0) }
                    }
                    Toggle(isOn: Binding(
                        get: { accounts.showMature },
                        set: { on in if on { confirmMature = true } else { app.accounts.setShowMature(false) } },
                    )) {
                        Text("Mature content")
                        Text("18+ · previews blurred")
                    }
                }
            }
            .formStyle(.grouped)
            .scrollDismissesKeyboard(.interactively)
        }
        .confirmAlert(
            $confirmMature,
            title: "Are you 18 or older?",
            message: "Mature models and their (blurred) previews will appear in Civitai results. Content flagged as depicting minors or real people is never shown.",
            confirm: "I am 18+",
            destructive: false,
        ) { app.accounts.setShowMature(true) }
    }

    /// "Verify & save": the panel's primary action; its loader shows while that panel's check runs.
    private func verifyButton(enabled: Bool, panel: String, action: @escaping () -> Void) -> some View {
        Button {
            busyPanel = panel
            action()
        } label: {
            Group {
                if busy && busyPanel == panel { ProgressView() } else { Text("Verify & save") }
            }
            .frame(maxWidth: .infinity)
        }
        .glassProminentButton()
        .disabled(!enabled || busy)
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
    }

    private func run(_ block: @escaping () async throws -> String) {
        busy = true
        Task {
            do {
                message = try await block()
            } catch let e as HubError {
                message = switch e.code {
                case .unauthorized: "That token was rejected"
                case .blocked: "Network is off — allow a network mode in Privacy Center first"
                default: e.message
                }
            } catch {
                message = error.localizedDescription
            }
            busy = false
        }
    }
}

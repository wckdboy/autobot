import SwiftUI

/// Model-hub accounts (port of `AccountsScreen.kt`): tokens are verified against the hub, then
/// kept in the Keychain.
struct AccountsScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
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
            // The shared form: a focused token field scrolls into view above the keyboard.
            KeyboardForm {
                SectionLabel("Model hubs · encrypted")
                if let message {
                    HStack {
                        MicroLabel(message, color: c.primary, lines: 3).frame(maxWidth: .infinity, alignment: .leading)
                        TextAction("OK", color: c.primary) { self.message = nil }
                    }
                }
                Panel(title: "Hugging Face") {
                    HStack(spacing: 0) {
                        Text(accounts.huggingFaceUser.map { "Signed in as \($0)" } ?? "Optional read token")
                            .textStyle(.bodySmall)
                            .foregroundStyle(c.onSurfaceVariant)
                        InfoButton(title: "Hugging Face", text: "A read token lifts rate limits and unlocks gated models (Llama, Gemma 3…) once you accept their terms on huggingface.co. Create one at huggingface.co/settings/tokens (fine-grained, read access to public gated repos).")
                    }
                    if accounts.huggingFaceUser == nil {
                        ConsoleTextField(text: $hfToken, placeholder: "hf_…", kind: .secret)
                        GhostButton("Verify & save", accent: c.primary, enabled: !hfToken.trimmingCharacters(in: .whitespaces).isEmpty && !busy, busy: busy && busyPanel == "hf") {
                            busyPanel = "hf"
                            let token = Secret(hfToken.trimmingCharacters(in: .whitespacesAndNewlines))
                            hfToken = ""
                            run {
                                let user = try await HuggingFaceClient(clients: app.clients, accounts: app.accounts).whoami(token)
                                try app.accounts.setHuggingFace(token, user: user.name)
                                return "Signed in to Hugging Face as \(user.name)"
                            }
                        }
                    } else {
                        GhostButton("Sign out") { app.accounts.clearHuggingFace() }
                    }
                }
                Panel(title: "Civitai") {
                    HStack(spacing: 0) {
                        Text(accounts.civitaiUser.map { "Key saved · \($0)" } ?? "Optional API key")
                            .textStyle(.bodySmall)
                            .foregroundStyle(c.onSurfaceVariant)
                        InfoButton(title: "Civitai", text: "Some creators require login to download. Create a key in civitai.com → Account settings → API keys.")
                    }
                    if accounts.civitaiUser == nil {
                        ConsoleTextField(text: $civitaiKey, placeholder: "API key", kind: .secret)
                        GhostButton("Verify & save", accent: c.primary, enabled: !civitaiKey.trimmingCharacters(in: .whitespaces).isEmpty && !busy, busy: busy && busyPanel == "civitai") {
                            busyPanel = "civitai"
                            let key = Secret(civitaiKey.trimmingCharacters(in: .whitespacesAndNewlines))
                            civitaiKey = ""
                            run {
                                let user = try await CivitaiClient(clients: app.clients, accounts: app.accounts).me(key)
                                try app.accounts.setCivitai(key, user: user)
                                return "Civitai key saved for \(user)"
                            }
                        }
                    } else {
                        GhostButton("Remove key") { app.accounts.clearCivitai() }
                    }
                    MicroLabel("host").padding(.top, 4)
                    Segmented(options: CivitaiHost.allCases, selected: accounts.civitaiHost, label: \.host) { app.accounts.setCivitaiHost($0) }
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Mature content").textStyle(.titleSmall).foregroundStyle(c.onSurface)
                            Text("18+ · previews blurred").textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        RisoToggle(isOn: Binding(
                            get: { accounts.showMature },
                            set: { on in if on { confirmMature = true } else { app.accounts.setShowMature(false) } },
                        ))
                    }
                }
            }
        }
        .background(c.background)
        .risoAlert(
            $confirmMature,
            title: "Are you 18 or older?",
            message: "Mature models and their (blurred) previews will appear in Civitai results. Content flagged as depicting minors or real people is never shown.",
            confirm: "I am 18+",
            destructive: false,
        ) { app.accounts.setShowMature(true) }
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

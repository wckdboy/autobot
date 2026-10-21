import AutobotShared
import SwiftUI

/// What the sheet edits. [apiKey] is only what was typed now; the stored key is never shown.
struct WebSearchDraft: Equatable, CustomStringConvertible {
    var provider = "NONE"
    var baseUrl = ""
    var route: ProviderRouting = .inherit
    var apiKey = ""
    var hasStoredKey = false

    /// Why the draft cannot be saved, or nil (the shared `WebSearchCodec.problem`).
    var problem: String? {
        guard provider != "NONE" else { return nil }
        let key = apiKey.isEmpty ? (hasStoredKey ? "stored" : "") : apiKey
        let config = WebSearchConfig(provider: SettingsSchema.shared.searchProvider(value: provider), baseUrl: baseUrl, apiKey: key, route: route.rawValue)
        return WebSearchCodec.shared.problem(config: config)
    }

    var description: String { "WebSearchDraft(\(provider), \(baseUrl), \(route), key=\(apiKey.isEmpty ? "" : Secret.redacted))" }

    static let providers: [(name: String, label: String)] = [("NONE", "Off"), ("SEARXNG", "SearXNG"), ("BRAVE", "Brave Search")]

    static func label(_ name: String) -> String { providers.first { $0.name == name }?.label ?? "Off" }
}

/**
 Web search setup (port of Android's `WebSearchDialog`): off by default; SearXNG (instance URL) or
 Brave Search (API key in the Keychain). Only queries go to the provider, through the chosen route.
 */
struct WebSearchSheet: View {
    let onDismiss: () -> Void
    @State private var draft = WebSearchDraft()
    @State private var loaded = false
    @State private var error: String?
    @Environment(AppContainer.self) private var app

    private static let keyRef = AutobotShared.AppSettings.companion.SEARCH_API_KEY_REF

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Provider", selection: $draft.provider) {
                        ForEach(WebSearchDraft.providers, id: \.name) { Text($0.label).tag($0.name) }
                    }
                } footer: {
                    Text("Gives the agent a web_search tool. Each search sends only the query to the provider you choose, through your network route, and needs approval like web_fetch.")
                }
                if draft.provider == "SEARXNG" {
                    Section {
                        FormField("Instance URL", text: $draft.baseUrl, prompt: "https://searx.example.org", kind: .url)
                    } footer: {
                        Text("the instance must allow the json format (search.formats)")
                    }
                }
                if draft.provider == "BRAVE" {
                    Section {
                        FormField("API key", text: $draft.apiKey, prompt: draft.hasStoredKey ? "•••••••• saved — type to replace" : "subscription token", kind: .secret)
                    } footer: {
                        Text("kept in the keychain on this device · never logged or exported")
                    }
                }
                if draft.provider != "NONE" {
                    Section {
                        Picker("Routing", selection: $draft.route) {
                            ForEach(ProviderRouting.allCases, id: \.self) { Text($0.label).tag($0) }
                        }
                    }
                }
                if draft.problem != nil || error != nil {
                    Section {
                        if let problem = draft.problem { Text(problem).foregroundStyle(.red) }
                        if let error { Text(error).foregroundStyle(.red) }
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Web search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDismiss) }
                ToolbarItem(placement: .confirmationAction) { Button("Save", action: save).disabled(draft.problem != nil) }
            }
        }
        .presentationDetents([.large])
        .onAppear {
            guard !loaded else { return }
            loaded = true
            let s = app.settings.settings
            draft = WebSearchDraft(provider: s.searchProvider, baseUrl: s.searchBaseUrl, route: s.searchRoute, hasStoredKey: app.secrets.contains(Self.keyRef))
        }
    }

    private func save() {
        let key = draft.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if draft.provider == "NONE" {
            // Turning search off also forgets the Brave key.
            app.secrets.delete(Self.keyRef)
        } else if !key.isEmpty {
            do {
                try app.secrets.put(Self.keyRef, Secret(key))
            } catch {
                // Keep the sheet (and the typed key) so the user can retry.
                self.error = saveFailure(error)
                return
            }
        }
        app.settings.setWebSearch(provider: draft.provider, baseUrl: draft.baseUrl, route: draft.route)
        onDismiss()
    }
}

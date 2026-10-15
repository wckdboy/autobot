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
    @Environment(\.scheme) private var c

    private static let keyRef = AutobotShared.AppSettings.companion.SEARCH_API_KEY_REF

    var body: some View {
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel("web search")
                Text("Gives the agent a web_search tool. Each search sends only the query to the provider you choose, through your network route, and needs approval like web_fetch.")
                    .textStyle(.bodySmall)
                    .foregroundStyle(c.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                MicroLabel("provider")
                ChipRow(items: WebSearchDraft.providers.map(\.name), selected: draft.provider, label: WebSearchDraft.label) { draft.provider = $0 }
                if draft.provider == "SEARXNG" {
                    ConsoleTextField(text: $draft.baseUrl, placeholder: "https://searx.example.org", label: "Instance URL", kind: .url)
                    MicroLabel("the instance must allow the json format (search.formats)", lines: 2)
                }
                if draft.provider == "BRAVE" {
                    ConsoleTextField(text: $draft.apiKey, placeholder: draft.hasStoredKey ? "•••••••• saved — type to replace" : "subscription token", label: "API key", kind: .secret)
                    MicroLabel("kept in the keychain on this device · never logged or exported", lines: 2)
                }
                if draft.provider != "NONE" {
                    MicroLabel("routing")
                    ChipRow(items: ProviderRouting.allCases, selected: draft.route, label: { $0.label }) { draft.route = $0 }
                }
                if let problem = draft.problem { MicroLabel(problem, color: c.error, lines: 3) }
                if let error { MicroLabel(error, color: c.error, lines: 3) }
                PrimaryButton("Save", enabled: draft.problem == nil, action: save)
                TextAction("Cancel", color: c.onSurfaceVariant, action: onDismiss).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .background(c.surfaceContainerLow.ignoresSafeArea())
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

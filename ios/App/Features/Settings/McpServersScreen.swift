import SwiftUI
#if canImport(AutobotShared)
import AutobotShared
#endif

/// Editor state. [tokenInput] is only held in memory and is redacted from [description].
struct McpServerDraft: Equatable, CustomStringConvertible {
    var id: String? = nil
    var name = ""
    var url = ""
    var routing: ProviderRouting = .inherit
    var enabled = true
    var trusted = false
    var tokenInput = ""
    var hasStoredToken = false
    var clearStoredToken = false

    var isNew: Bool { id == nil }

    /// An absolute http(s) URL without credentials (the shared `McpEndpoint` rules).
    var urlValid: Bool {
        guard let url = URL(string: url.trimmingCharacters(in: .whitespaces)), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", let host = url.host(), !host.isEmpty, url.user == nil, url.password == nil else { return false }
        return true
    }

    var canSave: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty && urlValid && ProviderDraft.cleartextProblem(url) == nil }

    var description: String {
        "McpServerDraft(id=\(id ?? "nil"), name=\(name), url=\(url), routing=\(routing), enabled=\(enabled), trusted=\(trusted), " +
            "tokenInput=\(tokenInput.isEmpty ? "" : Secret.redacted), hasStoredToken=\(hasStoredToken))"
    }
}

/// MCP servers (port of `McpServersScreen.kt` + `McpServersViewModel.kt`).
struct McpServersScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var editor: McpServerDraft?
    @State private var deleting: McpServer?
    @State private var error: String?

    var body: some View {
        let servers = app.mcpServers.servers
        VStack(spacing: 0) {
            AutobotTopBar(title: "MCP servers", onBack: { nav.back() }) {
                Button { editor = McpServerDraft() } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add MCP server")
            }
            if servers.isEmpty {
                // One line and one action, as on Android; what a trusted server does is on its switch in the editor.
                MarkUnavailable("No MCP servers yet.") {
                    Button("Add MCP server") { editor = McpServerDraft() }.glassProminentButton()
                }
            } else {
                List {
                    if let error {
                        Section { Text(error).foregroundStyle(.red) }
                    }
                    Section {
                        ForEach(servers) { row($0) }
                    }
                }
                .listStyle(.insetGrouped)
                .animation(.default, value: servers.map(\.id))
            }
        }
        .sheet(item: Binding(get: { editor.map(EditorItem.init) }, set: { if $0 == nil { editor = nil } })) { item in
            McpServerEditorSheet(initial: item.draft) { editor = nil }
        }
        .alert(
            "Delete \(deleting?.name ?? "MCP server")?",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            presenting: deleting,
        ) { s in
            Button("Delete", role: .destructive) { app.mcpServers.delete(s.id) }
            Button("Cancel", role: .cancel) {}
        } message: { s in
            Text("Its tools leave agent sessions" + (s.tokenRef != nil ? " and its token is removed from the Keychain." : "."))
        }
    }

    private func row(_ s: McpServer) -> some View {
        HStack(spacing: 12) {
            Button { startEdit(s) } label: {
                VStack(alignment: .leading, spacing: 3) {
                    Text(s.name).font(.headline).lineLimit(1)
                    Text(s.url).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Text(summary(s)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    if let status = status(s) {
                        Text(status.text).font(.caption).foregroundStyle(status.error ? Color.red : .secondary).lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.primary)
            Toggle("Enable \(s.name)", isOn: Binding(get: { s.enabled }, set: { v in
                var next = s
                next.enabled = v
                do {
                    try app.mcpServers.save(next)
                    error = nil
                } catch {
                    self.error = saveFailure(error)
                }
            }))
            .labelsHidden()
        }
        .swipeActions {
            Button("Delete", systemImage: "trash", role: .destructive) { deleting = s }
        }
        .contextMenu {
            Button("Edit", systemImage: "pencil") { startEdit(s) }
            Button("Delete \(s.name)", systemImage: "trash", role: .destructive) { deleting = s }
        }
        .accessibilityAction(named: "Delete \(s.name)") { deleting = s }
    }

    private func summary(_ s: McpServer) -> String {
        [URL(string: s.url)?.host() ?? "", s.routing.label, s.trusted ? "trusted · follows preset" : "asks each call", s.tokenRef != nil ? "token ✓" : nil]
            .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// The agent runtime's view of the server, once a session has tried it.
    private func status(_ s: McpServer) -> (text: String, error: Bool)? {
        #if canImport(AutobotShared)
        guard let status = (app.agent as? SharedAgentRuntime)?.mcpStatus[s.id] else { return nil }
        switch status.state {
        case .ready: return ("connected · \(counted(Int(status.toolCount), "tool"))" + (status.protocolVersion.map { " · mcp \($0)" } ?? ""), false)
        case .unavailable: return ("unavailable · \(withNetworkHint(status.message ?? "error"))", true)
        default: return nil
        }
        #else
        return nil
        #endif
    }

    private func startEdit(_ s: McpServer) {
        editor = McpServerDraft(
            id: s.id, name: s.name, url: s.url, routing: s.routing, enabled: s.enabled, trusted: s.trusted,
            hasStoredToken: app.mcpServers.hasToken(s),
        )
    }

    private struct EditorItem: Identifiable {
        let draft: McpServerDraft
        var id: String { draft.id ?? "new" }
    }
}

private struct McpServerEditorSheet: View {
    let onDismiss: () -> Void
    @State private var draft: McpServerDraft
    @State private var test: ProviderTestState = .idle
    @State private var saving = false
    @State private var error: String?
    @State private var testTask: Task<Void, Never>?
    @Environment(AppContainer.self) private var app

    init(initial: McpServerDraft, onDismiss: @escaping () -> Void) {
        _draft = State(initialValue: initial)
        self.onDismiss = onDismiss
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    FormField("Name", text: field(\.name), prompt: "Name")
                } footer: {
                    Text("tools appear to the agent as mcp__<name>__<tool>")
                }
                Section {
                    FormField("URL", text: field(\.url), prompt: "https://", kind: .url)
                } footer: {
                    if !draft.url.isEmpty && !draft.urlValid {
                        Text("invalid url").foregroundStyle(.red)
                    } else if let problem = ProviderDraft.cleartextProblem(draft.url) {
                        Text(problem).foregroundStyle(.red)
                    } else if let note = ProviderDraft.loopbackNote(draft.url) {
                        Text(note)
                    } else {
                        Text("the server's mcp endpoint, e.g. https://example.com/mcp · cleartext http only for 127.0.0.1 / localhost")
                    }
                }
                Section {
                    FormField(
                        "Bearer token (optional)",
                        text: Binding(get: { draft.tokenInput }, set: { v in update { $0.tokenInput = v; $0.clearStoredToken = false } }),
                        prompt: draft.hasStoredToken && !draft.clearStoredToken ? "•••••••• saved — type to replace" : "Optional",
                        kind: .secret,
                    )
                    if draft.hasStoredToken && draft.tokenInput.isEmpty {
                        Button(draft.clearStoredToken ? "Keep saved token" : "Remove saved token", role: draft.clearStoredToken ? nil : .destructive) {
                            update { $0.clearStoredToken.toggle() }
                        }
                        .foregroundStyle(draft.clearStoredToken ? Color.accentColor : .red)
                    }
                } footer: {
                    Text("kept in the keychain on this device · sent only to this server's host · never logged or displayed")
                }
                Section {
                    Picker("Routing", selection: Binding(get: { draft.routing }, set: { r in update { $0.routing = r } })) {
                        ForEach(ProviderRouting.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                } footer: {
                    Text("Offline mode always blocks remote requests. Direct cannot bypass a global Tor/SOCKS5 mode.")
                }
                Section {
                    Toggle("Enabled", isOn: Binding(get: { draft.enabled }, set: { v in update { $0.enabled = v } }))
                    InfoToggle(
                        title: "Trusted",
                        info: "Off: every tool call asks for approval first. On: calls follow the permission preset (full access runs them, the other presets ask).",
                        isOn: Binding(get: { draft.trusted }, set: { v in update { $0.trusted = v } }),
                    )
                }
                Section {
                    Button(action: testConnection) {
                        HStack {
                            Text("Test connection")
                            if test == .running { ProgressView().padding(.leading, 4) }
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .glassButton()
                    .disabled(!draft.urlValid || test == .running)
                    testResult
                    if let error { Text(error).foregroundStyle(.red) }
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }
            .formStyle(.grouped)
            .navigationTitle(draft.isNew ? "Add MCP server" : "Edit MCP server")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDismiss) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save).disabled(!draft.canSave || saving)
                }
            }
        }
        .presentationDetents([.large])
        .onDisappear { testTask?.cancel() }
    }

    @ViewBuilder private var testResult: some View {
        switch test {
        case .idle: EmptyView()
        // The Test button carries the loader.
        case .running: EmptyView()
        case .success(let m): Text("✓ \(m)").font(.footnote).foregroundStyle(.secondary)
        case .failure(let m): Text(m).font(.footnote).foregroundStyle(.red)
        }
    }

    private func field(_ path: WritableKeyPath<McpServerDraft, String>) -> Binding<String> {
        Binding(get: { draft[keyPath: path] }, set: { v in update { $0[keyPath: path] = v } })
    }

    private func update(_ change: (inout McpServerDraft) -> Void) {
        change(&draft)
        testTask?.cancel()
        test = .idle
    }

    private func server() -> McpServer {
        let existing = draft.id.flatMap(app.mcpServers.get)
        return McpServer(
            id: draft.id ?? app.mcpServers.newId(),
            name: draft.name.trimmingCharacters(in: .whitespaces),
            url: draft.url.trimmingCharacters(in: .whitespaces),
            routing: draft.routing,
            enabled: draft.enabled,
            trusted: draft.trusted,
            tokenRef: existing?.tokenRef,
            updatedAt: existing?.updatedAt ?? 0,
        )
    }

    private func save() {
        guard draft.canSave, !saving else { return }
        saving = true
        defer { saving = false }
        let token: ApiKeyUpdate = !draft.tokenInput.trimmingCharacters(in: .whitespaces).isEmpty
            ? .set(Secret(draft.tokenInput.trimmingCharacters(in: .whitespacesAndNewlines)))
            : draft.clearStoredToken ? .clear : .keep
        do {
            try app.mcpServers.save(server(), token: token)
            onDismiss()
        } catch {
            self.error = saveFailure(error)
        }
    }

    /// `initialize` + `tools/list` through the policy-enforcing client (blocked while Offline).
    private func testConnection() {
        guard draft.urlValid else {
            test = .failure("Invalid URL")
            return
        }
        testTask?.cancel()
        test = .running
        let s = server()
        let existing = draft.id.flatMap(app.mcpServers.get)
        let token: Secret? = !draft.tokenInput.isEmpty ? Secret(draft.tokenInput.trimmingCharacters(in: .whitespacesAndNewlines))
            : draft.clearStoredToken ? nil : existing.flatMap(app.mcpServers.token)
        testTask = Task {
            var result = ProviderTestState.failure("MCP needs the shared agent runtime")
            #if canImport(AutobotShared)
            if let shared = app.agent as? SharedAgentRuntime {
                let (ok, message) = await shared.testMcpServer(s, token: token)
                result = ok ? .success("Connected · \(message)") : .failure(message)
            }
            #endif
            if !Task.isCancelled { test = result }
        }
    }
}

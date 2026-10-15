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

    var canSave: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty && urlValid }

    var description: String {
        "McpServerDraft(id=\(id ?? "nil"), name=\(name), url=\(url), routing=\(routing), enabled=\(enabled), trusted=\(trusted), " +
            "tokenInput=\(tokenInput.isEmpty ? "" : Secret.redacted), hasStoredToken=\(hasStoredToken))"
    }
}

/// MCP servers (port of `McpServersScreen.kt` + `McpServersViewModel.kt`).
struct McpServersScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var editor: McpServerDraft?

    var body: some View {
        let servers = app.mcpServers.servers
        VStack(spacing: 0) {
            AutobotTopBar(title: "MCP servers", onBack: { nav.back() }) {
                IconAction(symbol: "plus", label: "Add MCP server", tint: c.primary) { editor = McpServerDraft() }
            }
            if servers.isEmpty {
                VStack(spacing: 10) {
                    Spacer()
                    Text("No MCP servers yet. Tap + to add a Model Context Protocol server (Streamable HTTP).\nIts tools join agent sessions; every call asks first; a trusted server follows the permission preset instead.")
                        .textStyle(.bodyMedium)
                        .foregroundStyle(c.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                    PrimaryButton("Add MCP server") { editor = McpServerDraft() }.frame(maxWidth: 260)
                    Spacer()
                }
                .padding(32)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        SectionLabel("remote tools · \(servers.count)").padding(.vertical, 8)
                        ForEach(servers) { s in row(s) }
                    }
                    .padding(.horizontal, Tokens.Spacing.gutter)
                    .padding(.bottom, 96)
                }
            }
        }
        .background(c.background)
        .sheet(item: Binding(get: { editor.map(EditorItem.init) }, set: { if $0 == nil { editor = nil } })) { item in
            McpServerEditorSheet(initial: item.draft) { editor = nil }
        }
    }

    private func row(_ s: McpServer) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button { startEdit(s) } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        MicroLabel(s.url)
                        Text(s.name).textStyle(.titleMedium).foregroundStyle(c.onSurface).lineLimit(1)
                        Text(summary(s))
                            .textStyle(.micro)
                            .foregroundStyle(c.onSurfaceVariant)
                            .lineLimit(1)
                        if let status = status(s) {
                            MicroLabel(status.text, color: status.error ? c.error : nil, lines: 2)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                Toggle("", isOn: Binding(get: { s.enabled }, set: { v in
                    var next = s
                    next.enabled = v
                    _ = try? app.mcpServers.save(next)
                }))
                .labelsHidden()
                .tint(c.primary)
                .accessibilityLabel("Enable \(s.name)")
                IconAction(symbol: "trash", label: "Delete \(s.name)", tint: c.onSurfaceVariant, size: 16) { app.mcpServers.delete(s.id) }
            }
            .padding(.vertical, 12)
            Hairline()
        }
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
        case .ready: return ("connected · \(status.toolCount) tools" + (status.protocolVersion.map { " · mcp \($0)" } ?? ""), false)
        case .unavailable: return ("unavailable · \(status.message ?? "error")", true)
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
    @Environment(\.scheme) private var c

    init(initial: McpServerDraft, onDismiss: @escaping () -> Void) {
        _draft = State(initialValue: initial)
        self.onDismiss = onDismiss
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SectionLabel(draft.isNew ? "add mcp server" : "edit mcp server")

                VStack(alignment: .leading, spacing: 4) {
                    ConsoleTextField(text: field(\.name), label: "Name")
                    MicroLabel("tools appear to the agent as mcp__<name>__<tool>", lines: 2)
                }
                VStack(alignment: .leading, spacing: 4) {
                    ConsoleTextField(text: field(\.url), label: "URL", kind: .url)
                    MicroLabel(
                        !draft.url.isEmpty && !draft.urlValid ? "invalid url" : "the server's mcp endpoint, e.g. https://example.com/mcp · cleartext http only for 127.0.0.1 / localhost",
                        color: !draft.url.isEmpty && !draft.urlValid ? c.error : nil,
                        lines: 2,
                    )
                }

                VStack(alignment: .leading, spacing: 4) {
                    ConsoleTextField(
                        text: Binding(get: { draft.tokenInput }, set: { v in update { $0.tokenInput = v; $0.clearStoredToken = false } }),
                        placeholder: draft.hasStoredToken && !draft.clearStoredToken ? "•••••••• saved — type to replace" : nil,
                        label: "Bearer token (optional)",
                        kind: .secret,
                    )
                    MicroLabel("kept in the keychain on this device · sent only to this server's host · never logged or displayed", lines: 2)
                    if draft.hasStoredToken && draft.tokenInput.isEmpty {
                        TextAction(draft.clearStoredToken ? "Keep saved token" : "Remove saved token", color: draft.clearStoredToken ? c.primary : c.error) {
                            update { $0.clearStoredToken.toggle() }
                        }
                    }
                }

                MicroLabel("routing")
                ChipRow(items: ProviderRouting.allCases, selected: draft.routing, label: { $0.label }) { r in update { $0.routing = r } }
                Text("Offline mode always blocks remote requests. Direct cannot bypass a global Tor/SOCKS5 mode.")
                    .textStyle(.bodySmall)
                    .foregroundStyle(c.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                SwitchRow(title: "Enabled", subtitle: "Agent sessions get this server's tools", isOn: Binding(get: { draft.enabled }, set: { v in update { $0.enabled = v } }))
                SwitchRow(
                    title: "Trusted",
                    subtitle: draft.trusted ? "Calls follow the permission preset instead of always asking (like web fetch: full-access runs them, the other presets ask)" : "Every tool call asks for approval first",
                    isOn: Binding(get: { draft.trusted }, set: { v in update { $0.trusted = v } }),
                )

                testResult
                if let error { MicroLabel(error, color: c.error, lines: 3) }

                HStack(spacing: 8) {
                    GhostButton(test == .running ? "Testing…" : "Test connection", accent: c.onSurface, enabled: draft.urlValid && test != .running, action: testConnection)
                    PrimaryButton("Save", enabled: draft.canSave && !saving, action: save)
                }
                TextAction("Cancel", color: c.onSurfaceVariant, action: onDismiss).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .background(c.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents([.large])
        .onDisappear { testTask?.cancel() }
    }

    @ViewBuilder private var testResult: some View {
        switch test {
        case .idle: EmptyView()
        case .running: HStack(spacing: 8) { ProgressView().tint(c.primary); Text("Testing…").textStyle(.bodySmall).foregroundStyle(c.onSurface) }
        case .success(let m): Text(m).textStyle(.bodySmall).foregroundStyle(AutobotColors.green)
        case .failure(let m): Text(m).textStyle(.bodySmall).foregroundStyle(c.error)
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
            self.error = "Could not save: \(error.localizedDescription)"
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

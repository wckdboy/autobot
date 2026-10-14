import SwiftUI
#if canImport(AutobotShared)
import AutobotShared
#endif

/// Editor state. [apiKeyInput] is only held in memory and is redacted from [description].
struct ProviderDraft: Equatable, CustomStringConvertible {
    var id: String? = nil
    var kind: ProviderKind = .deepseek
    var displayName = ""
    var baseUrl = ""
    var defaultModel = ""
    var routing: ProviderRouting = .inherit
    var apiKeyInput = ""
    var hasStoredKey = false
    var clearStoredKey = false

    var isNew: Bool { id == nil }

    var baseUrlValid: Bool {
        guard let url = URL(string: baseUrl.trimmingCharacters(in: .whitespaces)), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", let host = url.host(), !host.isEmpty else { return false }
        return true
    }

    var canSave: Bool {
        !displayName.trimmingCharacters(in: .whitespaces).isEmpty && baseUrlValid && !defaultModel.trimmingCharacters(in: .whitespaces).isEmpty
    }

    var description: String {
        "ProviderDraft(id=\(id ?? "nil"), kind=\(kind), displayName=\(displayName), baseUrl=\(baseUrl), model=\(defaultModel), " +
            "routing=\(routing), apiKeyInput=\(apiKeyInput.isEmpty ? "" : Secret.redacted), hasStoredKey=\(hasStoredKey))"
    }

    static func fromPreset(_ kind: ProviderKind) -> ProviderDraft {
        let preset = ProviderPresets.forKind(kind)
        return ProviderDraft(kind: kind, displayName: preset.displayName, baseUrl: preset.baseUrl, defaultModel: preset.defaultModel)
    }
}

enum ProviderTestState: Equatable {
    case idle, running
    case success(String)
    case failure(String)
}

/// Model providers (port of `ProvidersScreen.kt` + `ProvidersViewModel.kt`).
struct ProvidersScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var editor: ProviderDraft?

    var body: some View {
        let providers = app.providers.providers
        VStack(spacing: 0) {
            AutobotTopBar(title: "Providers", onBack: { nav.back() }) {
                IconAction(symbol: "plus", label: "Add provider", tint: c.primary) { editor = ProviderDraft.fromPreset(.deepseek) }
            }
            if providers.isEmpty {
                VStack(spacing: 10) {
                    Spacer()
                    Text("No providers yet. Tap + to add DeepSeek, OpenRouter, Ollama or any OpenAI-compatible endpoint.\nAPI keys are kept in the Keychain on this device.")
                        .textStyle(.bodyMedium)
                        .foregroundStyle(c.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                    PrimaryButton("Add provider") { editor = ProviderDraft.fromPreset(.deepseek) }.frame(maxWidth: 260)
                    Spacer()
                }
                .padding(32)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        SectionLabel("chat endpoints · \(providers.count)").padding(.vertical, 8)
                        ForEach(providers) { p in row(p) }
                    }
                    .padding(.horizontal, Tokens.Spacing.gutter)
                    .padding(.bottom, 96)
                }
            }
        }
        .background(c.background)
        .sheet(item: Binding(get: { editor.map(EditorItem.init) }, set: { if $0 == nil { editor = nil } })) { item in
            ProviderEditorSheet(initial: item.draft) { editor = nil }
        }
    }

    private func row(_ p: Provider) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button { startEdit(p) } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        MicroLabel(p.baseUrl)
                        Text(p.displayName).textStyle(.titleMedium).foregroundStyle(c.onSurface).lineLimit(1)
                        Text("\(ProviderPresets.forKind(p.kind).displayName) · \(p.defaultModel.isEmpty ? "—" : p.defaultModel) · \(p.routing.label)" + (p.apiKeyRef != nil ? " · key ✓" : ""))
                            .textStyle(.micro)
                            .foregroundStyle(c.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                if p.kind == .local {
                    RouteTag(route: .localGpu)
                } else {
                    IconAction(symbol: "trash", label: "Delete \(p.displayName)", tint: c.onSurfaceVariant, size: 16) { app.providers.delete(p.id) }
                }
            }
            .padding(.vertical, 12)
            Hairline()
        }
    }

    private func startEdit(_ p: Provider) {
        guard p.kind != .local else { return }
        editor = ProviderDraft(
            id: p.id, kind: p.kind, displayName: p.displayName, baseUrl: p.baseUrl, defaultModel: p.defaultModel,
            routing: p.routing, hasStoredKey: app.providers.hasApiKey(p),
        )
    }

    private struct EditorItem: Identifiable {
        let draft: ProviderDraft
        var id: String { draft.id ?? "new" }
    }
}

private struct ProviderEditorSheet: View {
    let onDismiss: () -> Void
    @State private var draft: ProviderDraft
    @State private var test: ProviderTestState = .idle
    @State private var saving = false
    @State private var error: String?
    @State private var testTask: Task<Void, Never>?
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    init(initial: ProviderDraft, onDismiss: @escaping () -> Void) {
        _draft = State(initialValue: initial)
        self.onDismiss = onDismiss
    }

    var body: some View {
        let preset = ProviderPresets.forKind(draft.kind)
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SectionLabel(draft.isNew ? "add provider" : "edit provider")
                MicroLabel("type")
                ChipRow(items: ProviderKind.allCases.filter { $0 != .local }, selected: draft.kind, label: { ProviderPresets.forKind($0).displayName }) { changeKind($0) }

                ConsoleTextField(text: field(\.displayName), label: "Name")
                VStack(alignment: .leading, spacing: 4) {
                    ConsoleTextField(text: field(\.baseUrl), label: "Base URL", kind: .url)
                    MicroLabel(
                        !draft.baseUrl.isEmpty && !draft.baseUrlValid ? "invalid url" : "requests go to {base}/chat/completions · cleartext http only for 127.0.0.1 / localhost",
                        color: !draft.baseUrl.isEmpty && !draft.baseUrlValid ? c.error : nil,
                        lines: 2,
                    )
                }
                ConsoleTextField(text: field(\.defaultModel), label: "Default model", kind: .mono)
                if !preset.suggestedModels.isEmpty {
                    ChipRow(items: preset.suggestedModels, selected: draft.defaultModel, label: { $0 }) { m in update { $0.defaultModel = m } }
                }

                VStack(alignment: .leading, spacing: 4) {
                    ConsoleTextField(
                        text: Binding(get: { draft.apiKeyInput }, set: { v in update { $0.apiKeyInput = v; $0.clearStoredKey = false } }),
                        placeholder: draft.hasStoredKey && !draft.clearStoredKey ? "•••••••• saved — type to replace" : nil,
                        label: preset.requiresApiKey ? "API key" : "API key (optional)",
                        kind: .secret,
                    )
                    MicroLabel("kept in the keychain on this device · never logged or displayed", lines: 2)
                    if draft.hasStoredKey && draft.apiKeyInput.isEmpty {
                        TextAction(draft.clearStoredKey ? "Keep saved key" : "Remove saved key", color: draft.clearStoredKey ? c.primary : c.error) {
                            update { $0.clearStoredKey.toggle() }
                        }
                    }
                }

                MicroLabel("routing")
                ChipRow(items: ProviderRouting.allCases, selected: draft.routing, label: { $0.label }) { r in update { $0.routing = r } }
                Text("Offline mode always blocks remote requests. Direct cannot bypass a global Tor/SOCKS5 mode.")
                    .textStyle(.bodySmall)
                    .foregroundStyle(c.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)

                testResult
                if let error { MicroLabel(error, color: c.error, lines: 3) }

                HStack(spacing: 8) {
                    GhostButton(test == .running ? "Testing…" : "Test connection", accent: c.onSurface, enabled: draft.baseUrlValid && test != .running, action: testConnection)
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

    private func field(_ path: WritableKeyPath<ProviderDraft, String>) -> Binding<String> {
        Binding(get: { draft[keyPath: path] }, set: { v in update { $0[keyPath: path] = v } })
    }

    private func update(_ change: (inout ProviderDraft) -> Void) {
        change(&draft)
        resetTest()
    }

    private func resetTest() {
        testTask?.cancel()
        test = .idle
    }

    /// Switching kind on a new provider re-applies that kind's preset.
    private func changeKind(_ kind: ProviderKind) {
        if draft.isNew {
            var next = ProviderDraft.fromPreset(kind)
            next.apiKeyInput = draft.apiKeyInput
            next.routing = draft.routing
            draft = next
        } else {
            draft.kind = kind
        }
        resetTest()
    }

    private func provider() -> Provider {
        Provider(
            id: draft.id ?? app.providers.newId(),
            kind: draft.kind,
            displayName: draft.displayName.trimmingCharacters(in: .whitespaces),
            baseUrl: draft.baseUrl.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")),
            defaultModel: draft.defaultModel.trimmingCharacters(in: .whitespaces),
            routing: draft.routing,
            apiKeyRef: draft.id.flatMap(app.providers.get)?.apiKeyRef,
        )
    }

    private func save() {
        guard draft.canSave, !saving else { return }
        saving = true
        defer { saving = false }
        let key: ApiKeyUpdate = !draft.apiKeyInput.trimmingCharacters(in: .whitespaces).isEmpty
            ? .set(Secret(draft.apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines)))
            : draft.clearStoredKey ? .clear : .keep
        do {
            try app.providers.save(provider(), apiKey: key)
            onDismiss()
        } catch {
            self.error = "Could not save: \(error.localizedDescription)"
        }
    }

    /// Calls `GET /models` through the policy-enforcing client (blocked while Offline).
    private func testConnection() {
        guard draft.baseUrlValid else {
            test = .failure("Invalid base URL")
            return
        }
        testTask?.cancel()
        test = .running
        let p = provider()
        let existing = draft.id.flatMap(app.providers.get)
        let key: Secret? = !draft.apiKeyInput.isEmpty ? Secret(draft.apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines))
            : draft.clearStoredKey ? nil : existing.flatMap(app.providers.apiKey)
        testTask = Task {
            let result: ProviderTestState
            #if canImport(AutobotShared)
            if let shared = app.agent as? SharedAgentRuntime {
                let spec = ProviderSpec(
                    id: p.id, kind: SharedMapping.kind(p.kind), displayName: p.displayName, baseUrl: p.baseUrl,
                    apiKey: key?.reveal(), defaultModel: p.defaultModel, route: p.routing.rawValue,
                )
                let (ok, message) = await shared.testConnection(spec)
                result = ok ? .success("Connected · \(message)") : .failure(message)
            } else {
                result = await Self.direct(app.providerFactory.create(p, apiKey: key))
            }
            #else
            result = await Self.direct(app.providerFactory.create(p, apiKey: key))
            #endif
            if !Task.isCancelled { test = result }
        }
    }

    private static func direct(_ provider: ChatProvider) async -> ProviderTestState {
        switch await provider.testConnection() {
        case .success(let count): .success(count.map { "Connected · \($0) models available" } ?? "Connected")
        case .failure(_, let message): .failure(message)
        }
    }
}

/// Wrapping row of selectable tags (Material FilterChips in riso type).
struct ChipRow<T: Hashable>: View {
    let items: [T]
    let selected: T
    let label: (T) -> String
    let onSelect: (T) -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        ChipFlowLayout(spacing: 6) {
            ForEach(items, id: \.self) { item in
                let active = item == selected
                Tag(label(item), accent: active ? c.primary : c.onSurfaceVariant, filled: active) { onSelect(item) }
            }
        }
    }
}

/// Simple flow layout: places subviews left to right, wrapping to new lines.
struct ChipFlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width {
                x = 0
                y += row + spacing
                row = 0
            }
            x += size.width + spacing
            maxX = max(maxX, x - spacing)
            row = max(row, size.height)
        }
        return CGSize(width: proposal.width ?? maxX, height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX {
                x = bounds.minX
                y += row + spacing
                row = 0
            }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            row = max(row, size.height)
        }
    }
}

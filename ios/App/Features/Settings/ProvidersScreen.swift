import AutobotShared
import SwiftUI

/// Editor state. [apiKeyInput] is only held in memory and is redacted from [description].
struct ProviderDraft: Equatable, CustomStringConvertible {
    var id: String? = nil
    var presetId = "openai-compatible"
    var kind: ProviderKind = .openAICompatible
    var displayName = ""
    var baseUrl = ""
    var defaultModel = ""
    /// Subagents and summaries; empty = the chat's own model.
    var helperModel = ""
    var routing: ProviderRouting = .inherit
    var apiKeyInput = ""
    var hasStoredKey = false
    var clearStoredKey = false

    var isNew: Bool { id == nil }

    var preset: ProviderPreset {
        ProviderPresets.resolve(Provider(id: id ?? "", kind: kind, displayName: displayName, baseUrl: baseUrl, defaultModel: defaultModel, presetId: presetId))
    }

    var baseUrlValid: Bool {
        guard let url = URL(string: baseUrl.trimmingCharacters(in: .whitespaces)), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", let host = url.host(), !host.isEmpty else { return false }
        return true
    }

    /// Why the base URL cannot work from this phone (the kill switch refuses cleartext to anything but loopback).
    var cleartextProblem: String? { ProviderDraft.cleartextProblem(baseUrl) }

    var canSave: Bool {
        !displayName.trimmingCharacters(in: .whitespaces).isEmpty && baseUrlValid && cleartextProblem == nil
            && !defaultModel.trimmingCharacters(in: .whitespaces).isEmpty
    }

    /// The preset needs a key and none is typed or kept.
    var missingKey: Bool {
        preset.requiresApiKey && apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && (!hasStoredKey || clearStoredKey)
    }

    var description: String {
        "ProviderDraft(id=\(id ?? "nil"), preset=\(presetId), displayName=\(displayName), baseUrl=\(baseUrl), model=\(defaultModel), helper=\(helperModel), " +
            "routing=\(routing), apiKeyInput=\(apiKeyInput.isEmpty ? "" : Secret.redacted), hasStoredKey=\(hasStoredKey))"
    }

    /// `http://` to a host other than loopback: the shared kill switch blocks it on every request.
    static func cleartextProblem(_ url: String) -> String? {
        guard let u = URL(string: url.trimmingCharacters(in: .whitespaces)), u.scheme?.lowercased() == "http", let host = u.host(), !host.isEmpty,
              !Loopback.isLoopback(host) else { return nil }
        return "Use https — plain http only works for servers on this phone; use Tailscale or a reverse proxy"
    }

    /// Loopback on a phone is the phone itself, not the PC the server usually runs on.
    static func loopbackNote(_ url: String) -> String? {
        guard let host = URL(string: url.trimmingCharacters(in: .whitespaces))?.host(), Loopback.isLoopback(host) else { return nil }
        return "\(host) is this iPhone — the server has to run on the phone itself; for a PC use its https address"
    }

    static func fromPreset(_ preset: ProviderPreset) -> ProviderDraft {
        ProviderDraft(
            presetId: preset.id, kind: preset.kind, displayName: preset.displayName, baseUrl: preset.baseUrl, defaultModel: preset.defaultModel,
            helperModel: preset.helperModel ?? "",
        )
    }
}

/// The model list in the editor: fetched (with its `ModelCache` JSON and age) or the preset's offline list.
struct EditorModels: Equatable {
    var models: [RemoteModelItem] = []
    var json: String? = nil
    var fetchedAt: Int64? = nil
    var loading = false
    var error: String? = nil
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
    @State private var picking = false
    @State private var deleting: Provider?

    var body: some View {
        let providers = app.providers.providers
        VStack(spacing: 0) {
            AutobotTopBar(title: "Providers", onBack: { nav.back() }) {
                IconAction(symbol: "plus", label: "Add provider", tint: c.primary) { picking = true }
            }
            if providers.isEmpty {
                VStack(spacing: 10) {
                    Spacer()
                    Text("No providers yet. Tap + to pick a cloud API, an open-model host or a server on your network.\nAPI keys are kept in the Keychain on this device.")
                        .textStyle(.bodyMedium)
                        .foregroundStyle(c.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                    PrimaryButton("Add provider") { picking = true }.frame(maxWidth: 260)
                    Spacer()
                }
                .padding(32)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        SectionLabel("chat endpoints · \(providers.count)").padding(.vertical, 8)
                        ForEach(providers) { p in row(p).transition(.inkDissolve) }
                    }
                    .animation(.inkDissolve, value: providers.map(\.id))
                    .padding(.horizontal, Tokens.Spacing.gutter)
                    .padding(.bottom, 96)
                }
            }
        }
        .background(c.background)
        .sheet(isPresented: $picking) {
            PresetPickerSheet { preset in
                picking = false
                editor = ProviderDraft.fromPreset(preset)
            }
        }
        .sheet(item: Binding(get: { editor.map(EditorItem.init) }, set: { if $0 == nil { editor = nil } })) { item in
            ProviderEditorSheet(initial: item.draft) { editor = nil }
        }
        .alert(
            "Delete \(deleting?.displayName ?? "provider")?",
            isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
            presenting: deleting,
        ) { p in
            Button("Delete", role: .destructive) { app.providers.delete(p.id) }
            Button("Cancel", role: .cancel) {}
        } message: { p in
            Text(deleteMessage(p))
        }
    }

    /// Its API key goes with it; chats on it move to the next provider (`ChatRoutes`).
    private func deleteMessage(_ p: Provider) -> String {
        let chats = app.conversations.conversations.filter { $0.providerId == p.id }.count
        let next = app.providers.providers.first { $0.id != p.id }
        var text = "Its API key is removed from the Keychain."
        if chats > 0 {
            let used = chats == 1 ? "1 chat uses it" : "\(chats) chats use it"
            text += next.map { " \(used) and will switch to \($0.displayName)." } ?? " \(used); it will have no provider until you add one."
        }
        return text
    }

    private func row(_ p: Provider) -> some View {
        let preset = ProviderPresets.resolve(p)
        let models = app.providers.modelLists[p.id]
        return VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button { startEdit(p) } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        MicroLabel(p.baseUrl)
                        Text(p.displayName).textStyle(.titleMedium).foregroundStyle(c.onSurface).lineLimit(1)
                        HStack(spacing: 0) {
                            Text("\(preset.displayName) · \(p.defaultModel.isEmpty ? "—" : p.defaultModel) · \(p.routing.label)" + (p.apiKeyRef != nil ? " · key ✓" : ""))
                                .foregroundStyle(c.onSurfaceVariant)
                                .lineLimit(1)
                            if preset.requiresApiKey && p.apiKeyRef == nil {
                                Text(" · no key").foregroundStyle(c.error).fixedSize()
                            }
                        }
                        .textStyle(.micro)
                        if let models, let list = ModelLists.models(models.json) {
                            MicroLabel("\(list.count) models · \(ModelLists.age(fetchedAt: models.fetchedAt))")
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                if p.kind == .local {
                    RouteTag(route: .localGpu)
                } else {
                    IconAction(symbol: "trash", label: "Delete \(p.displayName)", tint: c.onSurfaceVariant, size: 16) { deleting = p }
                }
            }
            .padding(.vertical, 12)
            Hairline()
        }
    }

    private func startEdit(_ p: Provider) {
        guard p.kind != .local else { return }
        editor = ProviderDraft(
            id: p.id, presetId: ProviderPresets.resolve(p).id, kind: p.kind, displayName: p.displayName, baseUrl: p.baseUrl,
            defaultModel: p.defaultModel, helperModel: p.helperModel ?? "", routing: p.routing, hasStoredKey: app.providers.hasApiKey(p),
        )
    }

    private struct EditorItem: Identifiable {
        let draft: ProviderDraft
        var id: String { draft.id ?? "new-\(draft.presetId)" }
    }
}

/// "Add provider": every shared preset, searchable, grouped Cloud · Open-model hosts · Local servers · This phone.
private struct PresetPickerSheet: View {
    let onPick: (ProviderPreset) -> Void
    @State private var query = ""
    @Environment(\.scheme) private var c

    var body: some View {
        let groups = ProviderPresets.search(query)
        InkStack(alignment: .leading, spacing: 8) {
            SectionLabel("add provider")
            ConsoleTextField(text: $query, placeholder: "Search providers or hosts")
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if groups.isEmpty { MicroLabel("no match · try “Other OpenAI-compatible”").padding(.vertical, 16) }
                    ForEach(groups, id: \.group) { group in
                        MicroLabel("// \(group.group) · \(group.presets.count)", color: c.primary).padding(.top, 14).padding(.bottom, 4)
                        if group.group == AutobotShared.PresetGroup.localServers.label {
                            Text("127.0.0.1 is this iPhone: these presets only reach a server running on the phone. For a server on your PC or network use its https address (Tailscale or a reverse proxy) — plain http only works on the phone itself.")
                                .textStyle(.bodySmall)
                                .foregroundStyle(c.onSurfaceVariant)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.bottom, 4)
                        }
                        ForEach(group.presets) { preset in
                            Button { if !preset.isThisPhone { onPick(preset) } } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    HStack(spacing: 8) {
                                        Text(preset.displayName).textStyle(.titleMedium).foregroundStyle(c.onSurface)
                                        if preset.kind == .anthropic { Tag("native") }
                                        if !preset.requiresApiKey && !preset.isThisPhone { Tag("no key") }
                                    }
                                    MicroLabel(preset.isThisPhone ? "added automatically" : preset.host)
                                    if let note = preset.note {
                                        Text(note).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.vertical, 10)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(PressStyle())
                            .disabled(preset.isThisPhone)
                            .accessibilityIdentifier("preset-\(preset.id)")
                            Hairline()
                        }
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 20)
        .background(c.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents([.large])
    }
}

private struct ProviderEditorSheet: View {
    let onDismiss: () -> Void
    @State private var draft: ProviderDraft
    @State private var models = EditorModels()
    @State private var test: ProviderTestState = .idle
    @State private var saving = false
    @State private var error: String?
    @State private var confirmNoKey = false
    /// A tapped model whose provider trains on prompts, and the field it goes to.
    @State private var confirmTrains: (model: String, helper: Bool)?
    @State private var testTask: Task<Void, Never>?
    @State private var modelsTask: Task<Void, Never>?
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c

    init(initial: ProviderDraft, onDismiss: @escaping () -> Void) {
        _draft = State(initialValue: initial)
        self.onDismiss = onDismiss
    }

    var body: some View {
        let preset = draft.preset
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel(draft.isNew ? "add provider" : "edit provider")
                HStack(spacing: 8) {
                    Text(preset.displayName).textStyle(.titleLarge).foregroundStyle(c.onSurface)
                    Spacer(minLength: 0)
                    Tag(preset.group)
                }
                if let note = preset.note {
                    Text(note).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                }

                ConsoleTextField(text: field(\.displayName), label: "Name")
                VStack(alignment: .leading, spacing: 4) {
                    ConsoleTextField(text: field(\.baseUrl), label: "Base URL", kind: .url)
                    if !draft.baseUrl.isEmpty && !draft.baseUrlValid {
                        MicroLabel("invalid url", color: c.error, lines: 2)
                    } else if let problem = draft.cleartextProblem {
                        MicroLabel(problem, color: c.error, lines: 3)
                    } else if let note = ProviderDraft.loopbackNote(draft.baseUrl) {
                        MicroLabel(note, lines: 3)
                    } else {
                        MicroLabel("cleartext http only for 127.0.0.1 / localhost", lines: 2)
                    }
                }

                VStack(alignment: .leading, spacing: 4) {
                    ConsoleTextField(
                        text: Binding(get: { draft.apiKeyInput }, set: { v in update { $0.apiKeyInput = v; $0.clearStoredKey = false } }),
                        placeholder: draft.hasStoredKey && !draft.clearStoredKey ? "•••••••• saved — type to replace" : nil,
                        label: preset.requiresApiKey ? "API key" : "API key (optional)",
                        kind: .secret,
                    )
                    if draft.missingKey { MicroLabel("This provider needs an API key", color: c.error, lines: 2) }
                    MicroLabel("kept in the keychain on this device · sent only to \(preset.host.isEmpty ? "this host" : preset.host)", lines: 2)
                    if draft.hasStoredKey && draft.apiKeyInput.isEmpty {
                        TextAction(draft.clearStoredKey ? "Keep saved key" : "Remove saved key", color: draft.clearStoredKey ? c.primary : c.error) {
                            update { $0.clearStoredKey.toggle() }
                        }
                    }
                }

                modelPicker(preset)
                helperField


                MicroLabel("routing")
                ChipRow(items: ProviderRouting.allCases, selected: draft.routing, label: { $0.label }) { r in update { $0.routing = r } }
                Text("Offline mode always blocks remote requests. Direct cannot bypass a global Tor/SOCKS5 mode.")
                    .textStyle(.bodySmall)
                    .foregroundStyle(c.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
                if let privacy = preset.privacyNote {
                    VStack(alignment: .leading, spacing: 2) {
                        MicroLabel("privacy")
                        Text(privacy).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                        if let url = preset.privacyUrl { MicroLabel(url, lines: 2) }
                    }
                }

                testResult
                if let error { MicroLabel(error, color: c.error, lines: 3) }

                HStack(spacing: 8) {
                    GhostButton("Test connection", accent: c.onSurface, enabled: draft.baseUrlValid && test != .running, busy: test == .running, action: testConnection)
                    PrimaryButton("Save", enabled: draft.canSave && !saving) {
                        if draft.missingKey { confirmNoKey = true } else { save() }
                    }
                }
                TextAction("Cancel", color: c.onSurfaceVariant, action: onDismiss).frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .background(c.surfaceContainerLow.ignoresSafeArea())
        .presentationDetents([.large])
        .onAppear(perform: loadModels)
        .onDisappear {
            testTask?.cancel()
            modelsTask?.cancel()
        }
        .alert("Save without an API key?", isPresented: $confirmNoKey) {
            Button("Save anyway", action: save)
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("\(preset.displayName) needs an API key; requests will be refused until you add one.")
        }
        .alert(
            "Use \(confirmTrains?.model ?? "this model")?",
            isPresented: Binding(get: { confirmTrains != nil }, set: { if !$0 { confirmTrains = nil } }),
            presenting: confirmTrains,
        ) { pick in
            Button("Use anyway") { update { if pick.helper { $0.helperModel = pick.model } else { $0.defaultModel = pick.model } } }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text(ModelPrivacy.trainsWarning + " Anything you send with it may end up in a future model.")
        }
    }

    /// Helper model: optional; typing searches the same list (a few matches), picking fills it in.
    @ViewBuilder private var helperField: some View {
        VStack(alignment: .leading, spacing: 6) {
            ConsoleTextField(text: field(\.helperModel), label: "Helper model (optional)", kind: .mono)
            let helper = draft.helperModel.trimmingCharacters(in: .whitespaces)
            let chosen = models.models.first { $0.id == helper }
            if let chosen, !chosen.caption.isEmpty { MicroLabel(chosen.caption, color: chosen.trains ? c.error : nil, lines: 2) }
            Text("Used for subagents and context summaries, to save quota. Empty: the chat's model.")
                .textStyle(.bodySmall)
                .foregroundStyle(c.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
            if !helper.isEmpty && chosen == nil {
                ForEach(ModelLists.filter(models.models, helper, limit: 5)) { m in
                    modelRow(m, active: false) { pick(m, helper: true) }
                }
            }
        }
    }

    private func modelRow(_ m: RemoteModelItem, active: Bool, onTap: @escaping () -> Void) -> some View {
        Button(action: onTap) {
            VStack(alignment: .leading, spacing: 1) {
                Text((active ? "● " : "") + m.id).textStyle(.code).foregroundStyle(active ? c.primary : c.onSurface).lineLimit(1)
                if !m.caption.isEmpty { MicroLabel(m.caption, color: m.trains ? c.error : nil) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle())
    }

    /// Fills in a tapped model, asking first when its provider trains on prompts.
    private func pick(_ m: RemoteModelItem, helper: Bool) {
        if m.trains {
            confirmTrains = (m.id, helper)
        } else {
            update { if helper { $0.helperModel = m.id } else { $0.defaultModel = m.id } }
        }
    }

    /// Default model: a field that also searches the fetched (or offline) list below it.
    @ViewBuilder private func modelPicker(_ preset: ProviderPreset) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            ConsoleTextField(text: field(\.defaultModel), label: "Default model", kind: .mono)
            HStack(spacing: 8) {
                let status: String = if models.loading {
                    "fetching models"
                } else if let e = models.error {
                    e
                } else if let at = models.fetchedAt {
                    "\(models.models.count) models · \(ModelLists.age(fetchedAt: at))"
                } else if preset.hasModelsEndpoint {
                    "offline list · refresh to fetch"
                } else {
                    "no models endpoint · documented list"
                }
                MicroLabel(status, color: models.error != nil ? c.error : nil, lines: 2)
                Spacer(minLength: 0)
                if preset.hasModelsEndpoint {
                    TextAction("Refresh", color: c.primary, enabled: draft.baseUrlValid && !models.loading, busy: models.loading, action: refreshModels)
                }
            }
            let query = models.models.contains { $0.id == draft.defaultModel } ? "" : draft.defaultModel
            let all = ModelLists.filter(models.models, query, limit: .max)
            ForEach(all.prefix(24)) { m in
                modelRow(m, active: m.id == draft.defaultModel) { pick(m, helper: false) }
            }
            if all.count > 24 { MicroLabel("+\(all.count - 24) more · type to search") }
        }
    }

    @ViewBuilder private var testResult: some View {
        switch test {
        case .idle: EmptyView()
        // The Test button carries the loader.
        case .running: EmptyView()
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

    private func loadModels() {
        if let id = draft.id, let stored = app.providers.modelLists[id], let list = ModelLists.models(stored.json, presetId: draft.presetId) {
            models = EditorModels(models: list, json: stored.json, fetchedAt: stored.fetchedAt)
        } else {
            models = EditorModels(models: draft.preset.suggestedModels.map { RemoteModelItem(id: $0, presetId: draft.presetId) })
        }
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
            presetId: draft.presetId,
            helperModel: helperModel(),
        )
    }

    /// The helper to store: nil when empty or the default model itself.
    private func helperModel() -> String? {
        let helper = draft.helperModel.trimmingCharacters(in: .whitespaces)
        return helper.isEmpty || helper == draft.defaultModel.trimmingCharacters(in: .whitespaces) ? nil : helper
    }

    private func draftKey() -> Secret? {
        let existing = draft.id.flatMap(app.providers.get)
        return !draft.apiKeyInput.isEmpty ? Secret(draft.apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines))
            : draft.clearStoredKey ? nil : existing.flatMap(app.providers.apiKey)
    }

    private func spec(_ p: Provider, _ key: Secret?) -> ProviderSpec {
        ProviderSpec(
            id: p.id, kind: SharedMapping.kind(p.kind), displayName: p.displayName, baseUrl: p.baseUrl,
            apiKey: key?.reveal(), defaultModel: p.defaultModel, route: p.routing.rawValue, presetId: p.presetId, modelsJson: nil,
            helperModel: p.helperModel,
        )
    }

    private func save() {
        guard draft.canSave, !saving else { return }
        saving = true
        defer { saving = false }
        let trimmed = draft.apiKeyInput.trimmingCharacters(in: .whitespacesAndNewlines)
        let key: ApiKeyUpdate = !trimmed.isEmpty ? .set(Secret(trimmed)) : draft.clearStoredKey ? .clear : .keep
        do {
            let stored = try app.providers.save(provider(), apiKey: key)
            if let json = models.json, let at = models.fetchedAt, !draft.clearStoredKey {
                // A list fetched in the editor (test / refresh) is kept as is.
                app.providers.saveModels(stored.id, fetchedAt: at, json: json)
            } else if case .set = key {
                fetchInBackground(stored)
            } else if !draft.preset.requiresApiKey {
                fetchInBackground(stored)
            }
            onDismiss()
        } catch {
            self.error = saveFailure(error)
        }
    }

    /// A new key (or a provider without keys): fetch its model list once saved.
    private func fetchInBackground(_ stored: Provider) {
        guard draft.preset.hasModelsEndpoint, let shared = app.agent as? SharedAgentRuntime else { return }
        let providers = app.providers
        let spec = spec(stored, providers.apiKey(stored))
        Task {
            let (json, _) = await shared.listModels(spec)
            if let json, let cache = ModelCache.companion.decode(json: json) { providers.saveModels(stored.id, fetchedAt: cache.fetchedAt, json: json) }
        }
    }

    private func accept(_ json: String?) {
        guard let json, let cache = ModelCache.companion.decode(json: json) else { return }
        let list = cache.models.map { RemoteModelItem($0, presetId: draft.presetId) }
        models = EditorModels(models: list, json: json, fetchedAt: cache.fetchedAt)
        // Servers whose model is whatever is loaded (llama.cpp, vLLM, LM Studio) start without one;
        // never one whose provider trains on prompts (that takes the user's own choice).
        if draft.defaultModel.isEmpty, let first = list.first(where: { !$0.trains }) { draft.defaultModel = first.id }
    }

    private func refreshModels() {
        guard draft.baseUrlValid, let shared = app.agent as? SharedAgentRuntime else { return }
        modelsTask?.cancel()
        models.loading = true
        models.error = nil
        let spec = spec(provider(), draftKey())
        modelsTask = Task {
            let (json, error) = await shared.listModels(spec)
            if Task.isCancelled { return }
            models.loading = false
            if let error { models.error = error } else { accept(json) }
        }
    }

    /// Calls the models endpoint through the policy-enforcing client (blocked while Offline).
    private func testConnection() {
        guard draft.baseUrlValid else {
            test = .failure("Invalid base URL")
            return
        }
        testTask?.cancel()
        test = .running
        let p = provider()
        let key = draftKey()
        testTask = Task {
            let result: ProviderTestState
            if let shared = app.agent as? SharedAgentRuntime {
                let outcome = await shared.testConnection(spec(p, key))
                if outcome.ok { accept(outcome.modelsJson) }
                result = outcome.ok ? .success(outcome.message) : .failure(outcome.message)
            } else {
                result = await Self.direct(app.providerFactory.create(p, apiKey: key))
            }
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

/// Why a settings editor could not save: a Keychain write (the sheet stays open) or the store.
func saveFailure(_ error: Error) -> String {
    error is KeychainError ? "Couldn't store the key in the Keychain: \(error.localizedDescription)" : "Could not save: \(error.localizedDescription)"
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

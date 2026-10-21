import SwiftUI

/// Image backends: on-device, A1111 / Forge API, local SSE engines (port of `BackendsScreen.kt`).
struct BackendsScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var editing: EditTarget?
    @State private var testing: String?
    @State private var results: [String: String] = [:]

    var body: some View {
        let repo = app.backends
        VStack(spacing: 0) {
            AutobotTopBar(title: "Image backends", onBack: { nav.back() })
            List {
                ForEach(repo.backends) { backend in
                    let result = results[backend.id]
                    let isDefault = backend.id == repo.defaultBackend?.id
                    Section {
                        HStack(spacing: 12) {
                            Button { repo.setDefault(backend.id) } label: {
                                Image(systemName: isDefault ? "checkmark.circle.fill" : "circle")
                                    .font(.title3)
                                    .foregroundStyle(isDefault ? AnyShapeStyle(.tint) : AnyShapeStyle(Color(uiColor: .tertiaryLabel)))
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel("Default backend")
                            .accessibilityAddTraits(isDefault ? .isSelected : [])
                            VStack(alignment: .leading, spacing: 2) {
                                Text(backend.name)
                                Text(backend.baseUrl).font(.footnote.monospaced()).foregroundStyle(.secondary).lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .onTapGesture { if backend.kind != .onDevice { editing = EditTarget(backend: backend, isNew: false) } }
                            if backend.kind != .onDevice {
                                Button { editing = EditTarget(backend: backend, isNew: false) } label: {
                                    Image(systemName: "pencil").minimumHitTarget()
                                }
                                .buttonStyle(.borderless)
                                .accessibilityLabel("Edit \(backend.name)")
                            }
                        }
                        HStack(spacing: 10) {
                            Button {
                                test(backend)
                            } label: {
                                if testing == backend.id { ProgressView() } else { Text("Test") }
                            }
                            .buttonStyle(.borderless)
                            .disabled(testing != nil)
                            if let result {
                                Text(result.sentenceCased)
                                    .font(.footnote)
                                    .foregroundStyle(result.hasPrefix("ok") ? AnyShapeStyle(.tint) : AnyShapeStyle(.red))
                                    .lineLimit(2)
                            }
                        }
                    } header: {
                        HStack {
                            Text(backend.kind.label)
                            Spacer()
                            CapsuleTag(backend.isLoopback ? "loopback" : "network", color: backend.isLoopback ? .secondary : .orange)
                        }
                    }
                }
                Section {
                    ForEach(DiffusionBackendRepository.presets, id: \.1) { kind, url in
                        Button {
                            editing = EditTarget(
                                backend: DiffusionBackend(id: repo.newId(), kind: kind, name: kind == .localSse ? "On-device" : "Forge", baseUrl: url),
                                isNew: true,
                            )
                        } label: {
                            LabeledContent {
                                Text(url).font(.footnote.monospaced()).lineLimit(1)
                            } label: {
                                Label(kind.label, systemImage: "plus.circle")
                            }
                        }
                    }
                } header: {
                    HStack(spacing: 4) {
                        Text("Add backend")
                        RowInfo(title: "Backends", text: "Loopback backends keep working in Offline mode. Anything else must be HTTPS and follows your network mode and kill switch.")
                    }
                } footer: {
                    Text("Loopback or HTTPS")
                }
            }
            .listStyle(.insetGrouped)
            .animation(.default, value: repo.backends.map(\.id))
        }
        .sheet(item: $editing) { target in
            BackendEditor(initial: target.backend, isNew: target.isNew) { saved in
                repo.save(saved)
                editing = nil
            } onDelete: {
                repo.delete(target.backend.id)
                editing = nil
            } onCancel: {
                editing = nil
            }
            .presentationDetents([.medium, .large])
        }
    }

    /// Probes the backend (health / catalog) through the network policy.
    private func test(_ backend: DiffusionBackend) {
        testing = backend.id
        let engine = app.generator.engine(for: backend)
        Task {
            let result: String
            do {
                let c = try await engine.catalog()
                result = ["ok", c.models.isEmpty ? nil : counted(c.models.count, "model"), c.loras.isEmpty ? nil : counted(c.loras.count, "lora"),
                          c.samplers.isEmpty ? nil : counted(c.samplers.count, "sampler")].compactMap { $0 }.joined(separator: " · ")
            } catch {
                result = "fail · \(error.localizedDescription)"
            }
            results[backend.id] = result
            testing = nil
        }
    }
}

private struct EditTarget: Identifiable {
    let backend: DiffusionBackend
    let isNew: Bool
    var id: String { backend.id }
}

private struct BackendEditor: View {
    let initial: DiffusionBackend
    let isNew: Bool
    let onSave: (DiffusionBackend) -> Void
    let onDelete: () -> Void
    let onCancel: () -> Void
    @State private var name = ""
    @State private var url = ""
    @State private var kind: BackendKind = .sdApi
    @State private var routing: RouteOverride = .inherit

    var body: some View {
        let trimmed = url.trimmingCharacters(in: .whitespaces)
        let cleartextRemote = trimmed.lowercased().hasPrefix("http://") && !Loopback.isLoopbackURL(trimmed)
        NavigationStack {
            Form {
                Section {
                    Picker("Kind", selection: $kind) {
                        Text("A1111 API").tag(BackendKind.sdApi)
                        Text("Local SSE").tag(BackendKind.localSse)
                    }
                    .pickerStyle(.segmented)
                    FormField("Name", text: $name)
                    FormField("Base URL", text: $url, kind: .url)
                } footer: {
                    if let problem = ProviderDraft.cleartextProblem(trimmed) {
                        Text(problem).foregroundStyle(.red)
                    } else if let note = ProviderDraft.loopbackNote(trimmed) {
                        Text(note)
                    }
                }
                Section {
                    Picker("Route", selection: $routing) {
                        ForEach(RouteOverride.allCases, id: \.self) { Text($0.rawValue.lowercased().sentenceCased).tag($0) }
                    }
                }
                if !isNew {
                    Section {
                        Button("Delete backend", role: .destructive, action: onDelete)
                    }
                }
            }
            .navigationTitle(isNew ? "New backend" : "Edit backend")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        var b = initial
                        b.name = name.trimmingCharacters(in: .whitespaces).isEmpty ? kind.label : name.trimmingCharacters(in: .whitespaces)
                        b.baseUrl = trimmed
                        b.kind = kind
                        b.routing = routing.rawValue
                        onSave(b)
                    }
                    .disabled(trimmed.isEmpty || cleartextRemote)
                }
            }
        }
        .onAppear {
            name = initial.name
            url = initial.baseUrl
            kind = initial.kind
            routing = initial.routeOverride
        }
    }
}

import SwiftUI

/// Image backends: on-device, A1111 / Forge API, local SSE engines (port of `BackendsScreen.kt`).
struct BackendsScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var editing: EditTarget?
    @State private var testing: String?
    @State private var results: [String: String] = [:]

    var body: some View {
        let repo = app.backends
        VStack(spacing: 0) {
            AutobotTopBar(title: "Image backends", onBack: { nav.back() })
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(repo.backends) { backend in
                        let result = results[backend.id]
                        Panel(title: backend.kind.label, accent: backend.kind == .localSse ? AutobotColors.uv : AutobotColors.cyan) {
                            Tag(backend.isLoopback ? "loopback" : "network", accent: backend.isLoopback ? c.primary : c.inks.amber)
                        } content: {
                            HStack(spacing: 10) {
                                Button { repo.setDefault(backend.id) } label: {
                                    Image(systemName: backend.id == repo.defaultBackend?.id ? "largecircle.fill.circle" : "circle")
                                        .symbolSize(20)
                                        .foregroundStyle(backend.id == repo.defaultBackend?.id ? c.primary : c.onSurfaceVariant)
                                        .frame(width: 44, height: 44)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(PressStyle())
                                .accessibilityLabel("Default backend")
                                .accessibilityAddTraits(backend.id == repo.defaultBackend?.id ? .isSelected : [])
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(backend.name).textStyle(.titleSmall).foregroundStyle(c.onSurface)
                                    Text(backend.baseUrl).textStyle(.codeBlock).foregroundStyle(c.onSurfaceVariant).lineLimit(1)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .contentShape(Rectangle())
                            .onTapGesture { if backend.kind != .onDevice { editing = EditTarget(backend: backend, isNew: false) } }
                            HStack(spacing: 8) {
                                GhostButton("Test", enabled: testing == nil, busy: testing == backend.id) { test(backend) }
                                    .frame(width: 120)
                                if let result {
                                    MicroLabel(result, color: result.hasPrefix("ok") ? c.primary : c.error, lines: 2)
                                }
                                Spacer(minLength: 0)
                            }
                        }
                        .transition(.inkDissolve)
                    }
                    Panel(title: "Add backend", accent: AutobotColors.acid) {
                        HStack(spacing: 0) {
                            Text("Loopback or HTTPS").textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
                            InfoButton(title: "Backends", text: "Loopback backends keep working in Offline mode. Anything else must be HTTPS and follows your network mode and kill switch.")
                        }
                        ForEach(DiffusionBackendRepository.presets, id: \.1) { kind, url in
                            Button {
                                editing = EditTarget(
                                    backend: DiffusionBackend(id: repo.newId(), kind: kind, name: kind == .localSse ? "On-device" : "Forge", baseUrl: url),
                                    isNew: true,
                                )
                            } label: {
                                Text("\(kind.label)  ·  \(url)")
                                    .textStyle(.labelMedium)
                                    .foregroundStyle(c.primary)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.8)
                                    .frame(maxWidth: .infinity, minHeight: 40)
                                    .overlay(RoundedRectangle(cornerRadius: AutobotShapes.small).strokeBorder(c.outline, lineWidth: 1))
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(PressStyle())
                        }
                    }
                }
                .padding(12)
                .animation(.inkDissolve, value: repo.backends.map(\.id))
            }
        }
        .background(c.background)
        .sheet(item: $editing) { target in
            BackendEditor(initial: target.backend, isNew: target.isNew) { saved in
                repo.save(saved)
                editing = nil
            } onDelete: {
                repo.delete(target.backend.id)
                editing = nil
            }
            .presentationDetents([.medium, .large])
            .presentationBackground(c.surfaceContainerLow)
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
    @Environment(\.scheme) private var c
    @State private var name = ""
    @State private var url = ""
    @State private var kind: BackendKind = .sdApi
    @State private var routing: RouteOverride = .inherit

    var body: some View {
        let trimmed = url.trimmingCharacters(in: .whitespaces)
        let cleartextRemote = trimmed.lowercased().hasPrefix("http://") && !Loopback.isLoopbackURL(trimmed)
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                MicroLabel(isNew ? "new backend" : "edit backend")
                Segmented(options: [BackendKind.sdApi, .localSse], selected: kind, label: { $0 == .sdApi ? "A1111 API" : "Local SSE" }) { kind = $0 }
                ConsoleTextField(text: $name, label: "Name")
                ConsoleTextField(text: $url, label: "Base URL", kind: .url)
                if let problem = ProviderDraft.cleartextProblem(trimmed) {
                    MicroLabel(problem, color: c.error, lines: 3)
                } else if let note = ProviderDraft.loopbackNote(trimmed) {
                    MicroLabel(note, lines: 3)
                }
                SelectField(label: "Route", value: routing.rawValue.lowercased(), options: RouteOverride.allCases, optionLabel: { $0.rawValue.lowercased() }) { routing = $0 }
                HStack {
                    if !isNew { TextAction("Delete", color: c.error, action: onDelete) }
                    Spacer()
                    PrimaryButton("Save", enabled: !trimmed.isEmpty && !cleartextRemote) {
                        var b = initial
                        b.name = name.trimmingCharacters(in: .whitespaces).isEmpty ? kind.label : name.trimmingCharacters(in: .whitespaces)
                        b.baseUrl = trimmed
                        b.kind = kind
                        b.routing = routing.rawValue
                        onSave(b)
                    }
                    .frame(width: 140)
                }
            }
            .padding(16)
        }
        .background(c.surfaceContainerLow)
        .onAppear {
            name = initial.name
            url = initial.baseUrl
            kind = initial.kind
            routing = initial.routeOverride
        }
    }
}

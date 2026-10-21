import AutobotShared
import SwiftUI
import UniformTypeIdentifiers

/**
 Workflows (spec §5): the ComfyUI PCs (same URL rules as the other backends: https, or plain http
 only on this phone), saved workflows (import by paste, Files or the share sheet; detected
 parameters to review, expose and rename; validation against the server's `/object_info`; run
 from controls rendered from the schema) and the built-in multi-step recipes.
 */
struct WorkflowsRoom: View {
    let session: StudioSession
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var editing: ServerEdit?
    @State private var testing: String?
    @State private var results: [String: (Bool, String)] = [:]
    @State private var importing: ImportStart?
    @State private var opened: String?
    @State private var running: Pipeline?

    var body: some View {
        let studio = app.studio
        List {
            Section("ComfyUI PCs") {
                if studio.comfyServers.isEmpty {
                    StudioEmpty(StudioRoomWords.shared.workflowsNoPc)
                }
                ForEach(studio.comfyServers) { s in
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(s.name).font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                            RouteBadge(route: s.route)
                        }
                        Text(s.baseUrl).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                        HStack(spacing: 12) {
                            Button {
                                test(s)
                            } label: {
                                if testing == s.id { ProgressView().controlSize(.small) } else { Text("Test") }
                            }
                            .glassButton()
                            .disabled(testing != nil)
                            Button("Edit") { editing = ServerEdit(server: s, isNew: false) }
                                .buttonStyle(.borderless)
                            if let r = results[s.id] {
                                Text(r.1).font(.caption).foregroundStyle(r.0 ? AnyShapeStyle(.tint) : AnyShapeStyle(.red)).lineLimit(2)
                            }
                        }
                    }
                    .padding(.vertical, 4)
                    .swipeActions {
                        Button("Edit", systemImage: "pencil") { editing = ServerEdit(server: s, isNew: false) }
                    }
                }
                Button("Add ComfyUI PC", systemImage: "plus") {
                    editing = ServerEdit(server: ComfyServer(id: UUID().uuidString.lowercased(), name: "Studio PC", baseUrl: "https://"), isNew: true)
                }
            }

            Section {
                if studio.library.workflows.isEmpty {
                    Text("In ComfyUI, enable dev mode and use Workflow › Export (API). Paste that JSON, open the file, or share it to Autobot. Prompts, seeds, steps, sizes, models, LoRAs and input images are detected.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                ForEach(studio.library.workflows, id: \.id) { w in
                    Button { opened = w.id } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(w.name).font(.body)
                                Text("\(w.output.name.lowercased()) · \(counted(w.params.count, "param")) · v\(w.version) · \(studio.comfyServer(w.backendId)?.name ?? "any pc")")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            CapsuleTag(w.output.name.lowercased())
                        }
                    }
                    .tint(.primary)
                }
                Button("Import", systemImage: "square.and.arrow.down") { importing = ImportStart(json: "") }
            } header: {
                Text("Workflows · \(studio.library.workflows.count)")
            }

            Section {
                ForEach(BuiltInPipelines.shared.all, id: \.id) { p in
                    let missing = missingEngines(p)
                    Button { running = p } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.name).font(.body)
                                Text(missing.isEmpty ? p.description_ : "needs \(missing.joined(separator: ", "))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if missing.isEmpty { CapsuleTag("run") } else { CapsuleTag("unavailable", color: .secondary) }
                        }
                    }
                    .tint(.primary)
                    .disabled(!missing.isEmpty)
                }
            } header: {
                Text("Recipes")
            } footer: {
                Text("Multi-step: each step's result lands in the project.")
            }
        }
        .listStyle(.insetGrouped)
        .animation(.smooth, value: studio.comfyServers.map(\.id))
        .sheet(item: $editing) { edit in
            ComfyServerEditor(initial: edit.server, isNew: edit.isNew) { saved, header in
                studio.saveComfy(saved, headerValue: header)
                editing = nil
            } onDelete: {
                studio.deleteComfy(edit.server.id)
                editing = nil
            }
            .presentationDetents([.medium, .large])
        }
        .sheet(item: $importing) { start in
            WorkflowImportSheet(initialJson: start.json) { importing = nil; if let id = $0 { opened = id } }
                .presentationDetents([.large])
        }
        .sheet(item: Binding(get: { opened.map(Opened.init) }, set: { opened = $0?.id })) { o in
            WorkflowDetail(workflowId: o.id) { opened = nil }
                .presentationDetents([.large])
        }
        .sheet(item: $running) { p in
            PipelineRunSheet(pipeline: p) { running = nil }
                .presentationDetents([.medium, .large])
        }
        .task(id: nav.pendingWorkflow) {
            if let json = nav.pendingWorkflow {
                nav.pendingWorkflow = nil
                importing = ImportStart(json: json)
            }
        }
    }

    private func test(_ s: ComfyServer) {
        testing = s.id
        Task {
            let r = await app.studio.testComfy(s)
            results[s.id] = (r.ok, r.ok ? r.text : "fail · \(r.text)")
            testing = nil
        }
    }

    /// Engine kinds a recipe needs that are not available now.
    private func missingEngines(_ p: Pipeline) -> [String] {
        let names: [String: String] = [EngineKinds.shared.DIFFUSION: "an image engine", EngineKinds.shared.UPSCALE: "the upscaler",
                                       EngineKinds.shared.AUDIO: "the audio model", EngineKinds.shared.SPEECH: "the voice"]
        return Array(Set(p.steps.map(\.engine))).filter { !app.studio.has($0) }.compactMap { names[$0] ?? $0 }.sorted()
    }
}

private struct ServerEdit: Identifiable {
    let server: ComfyServer
    let isNew: Bool
    var id: String { server.id }
}

private struct ImportStart: Identifiable {
    let id = UUID()
    let json: String
}

private struct Opened: Identifiable {
    let id: String
}

extension Pipeline: @retroactive Identifiable {}

// MARK: - Server editor

private struct ComfyServerEditor: View {
    let initial: ComfyServer
    let isNew: Bool
    let onSave: (ComfyServer, String?) -> Void
    let onDelete: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var url = ""
    @State private var routing: RouteOverride = .inherit
    @State private var headerName = ""
    @State private var headerValue = ""

    var body: some View {
        let trimmed = url.trimmingCharacters(in: .whitespaces)
        let problem = ProviderDraft.cleartextProblem(trimmed)
        let valid = URL(string: trimmed).flatMap { $0.host() }.map { !$0.isEmpty } ?? false
        NavigationStack {
            Form {
                Section {
                    FormField("Name", text: $name)
                    FormField("Base URL", text: $url, prompt: "https://studio-pc.tailnet.ts.net", kind: .url)
                    Picker("Route", selection: $routing) {
                        ForEach(RouteOverride.allCases, id: \.self) { Text($0.rawValue.lowercased().sentenceCased).tag($0) }
                    }
                } footer: {
                    if let problem {
                        Text(problem).foregroundStyle(.red)
                    } else if let note = ProviderDraft.loopbackNote(trimmed) {
                        Text(note)
                    } else {
                        Text("Progress over wss · previews stream into the queue · every request follows your kill switch and is in the audit log")
                    }
                }
                Section("Auth header · optional") {
                    FormField("Header", text: $headerName, prompt: "Authorization", kind: .mono)
                    FormField("Value", text: $headerValue, prompt: initial.headerRef != nil ? "Saved · leave empty to keep" : "Bearer …", kind: .secret)
                }
                if !isNew {
                    Section {
                        Button("Delete", role: .destructive, action: onDelete)
                    }
                }
            }
            .navigationTitle(isNew ? "New ComfyUI PC" : "Edit ComfyUI PC")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        var s = initial
                        s.name = name.trimmingCharacters(in: .whitespaces).isEmpty ? "ComfyUI" : name.trimmingCharacters(in: .whitespaces)
                        s.baseUrl = trimmed
                        s.routing = routing.rawValue
                        s.headerName = headerName.trimmingCharacters(in: .whitespaces).isEmpty ? nil : headerName.trimmingCharacters(in: .whitespaces)
                        onSave(s, s.headerName == nil ? "" : headerValue.isEmpty ? nil : headerValue)
                    }
                    .disabled(!valid || problem != nil)
                }
            }
        }
        .onAppear {
            name = initial.name
            url = initial.baseUrl
            routing = initial.routeOverride
            headerName = initial.headerName ?? ""
        }
    }
}

// MARK: - Import

/// Paste or open an API-format workflow; review what was detected; save.
struct WorkflowImportSheet: View {
    let initialJson: String
    let onDone: (String?) -> Void
    @Environment(AppContainer.self) private var app
    @State private var json = ""
    @State private var name = ""
    @State private var serverId: String?
    @State private var result: StudioHub.Imported?
    @State private var working = false
    @State private var showFiles = false

    var body: some View {
        let studio = app.studio
        NavigationStack {
            Form {
                Section {
                    FormField("Name", text: $name, prompt: "Portrait SDXL")
                    HStack(spacing: 12) {
                        Button("Paste", systemImage: "doc.on.clipboard") { json = UIPasteboard.general.string ?? json }
                            .glassButton()
                        Button("Files", systemImage: "folder") { showFiles = true }
                            .glassButton()
                    }
                    StackedField(label: "Workflow JSON", text: $json, prompt: "{\"3\": {\"class_type\": \"KSampler\", …}}", kind: .mono, lines: 4 ... 10, code: true)
                    if !studio.comfyServers.isEmpty {
                        OptionPicker("Check against", selection: serverId ?? "", options: [""] + studio.comfyServers.map(\.id),
                                     optionLabel: { id in id.isEmpty ? "Don't check" : studio.comfyServer(id)?.name ?? id }) { serverId = $0.isEmpty ? nil : $0 }
                    }
                } footer: {
                    Text("API format: ComfyUI › Workflow › Export (API).")
                }
                Section {
                    Button {
                        detect()
                    } label: {
                        HStack {
                            Text("Detect parameters")
                            if working { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || working)
                }
                if let result {
                    if let error = result.error {
                        Section { Text(error).foregroundStyle(.red) }
                    } else if let w = result.workflow {
                        Section {
                            ForEach(w.params, id: \.name) { p in
                                LabeledContent {
                                    Text(p.default_?.display().prefix(24).description ?? "").lineLimit(1)
                                } label: {
                                    Text(p.label)
                                    Text("\(p.type.name.lowercased())\(p.role.map { " · \($0)" } ?? "")")
                                }
                            }
                            if w.params.isEmpty { Text("Nothing detected · expose inputs after saving").foregroundStyle(.secondary) }
                        } header: {
                            Text("Detected · makes \(w.output.name.lowercased())")
                        } footer: {
                            if let v = result.validation {
                                Text(v).foregroundStyle(.red)
                            } else if result.checked {
                                Text("✓ Every node and model is on \(studio.comfyServer(serverId)?.name ?? "the server")").foregroundStyle(.tint)
                            } else {
                                Text("Not checked against a server yet")
                            }
                        }
                        Section {
                            Button("Save workflow") {
                                studio.saveWorkflow(w)
                                onDone(w.id)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Import workflow")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { onDone(nil) } } }
        }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.json, .plainText, .data]) { r in
            guard case .success(let url) = r else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url), data.count < 8_000_000, let text = String(data: data, encoding: .utf8) {
                json = text
                if name.isEmpty { name = url.deletingPathExtension().lastPathComponent }
            }
        }
        .onAppear {
            json = initialJson
            serverId = studio.comfyServers.first?.id
            if !initialJson.isEmpty { detect() }
        }
    }

    private func detect() {
        working = true
        let label = name.trimmingCharacters(in: .whitespaces).isEmpty ? "Workflow" : name
        Task {
            result = await app.studio.importWorkflow(name: label, json: json, backendId: serverId)
            working = false
        }
    }
}

// MARK: - Review & run

/// A saved workflow: run it from its controls, review / expose / rename parameters, validate, delete.
struct WorkflowDetail: View {
    let workflowId: String
    let onDone: () -> Void
    @Environment(AppContainer.self) private var app
    @State private var tab = "run"
    @State private var values: [String: ParamValue] = [:]
    @State private var validation: String?
    @State private var validating = false
    @State private var renaming: WorkflowParam?
    @State private var newLabel = ""
    @State private var confirmDelete = false

    var body: some View {
        let studio = app.studio
        if let w = studio.library.workflow(workflowId) {
            let server = studio.comfyServer(w.backendId) ?? studio.comfyServers.first
            NavigationStack {
                Form {
                    Section {
                        if let server { RunsOnTag(label: server.name, route: server.route) } else { Text("Add a ComfyUI PC to run it").foregroundStyle(.red) }
                        Picker("View", selection: $tab) {
                            Text("Run").tag("run")
                            Text("Parameters").tag("parameters")
                        }
                        .pickerStyle(.segmented)
                        .labelsHidden()
                    }
                    if tab == "run" {
                        Section {
                            WorkflowForm(workflow: w, values: $values)
                        }
                        Section {
                            Button("Queue") {
                                let seed = w.params.first { $0.type == .seed }.flatMap { values[$0.name]?.asLong()?.int64Value }.flatMap { $0 >= 0 ? $0 : nil }
                                let r = Recipe(engine: EngineKinds.shared.COMFYUI, backendId: server?.id, model: nil, params: values, inputs: [], workflowId: w.id,
                                               workflowVersion: KotlinInt(value: w.version), seed: seed.map { KotlinLong(value: $0) }, output: w.output)
                                studio.submit(r, label: w.name)
                                onDone()
                            }
                            .disabled(server == nil)
                        }
                    } else {
                        review(w)
                    }
                    Section {
                        if let server {
                            Button {
                                validating = true
                                Task {
                                    let r = await studio.validate(w, on: server.id)
                                    validation = r.error.map { "Can't reach \(server.name): \($0)" } ?? r.message ?? "✓ ready on \(server.name)"
                                    let refreshed = await studio.refreshOptions(w, on: server.id)
                                    if refreshed !== w { studio.saveWorkflow(refreshed) }
                                    validating = false
                                }
                            } label: {
                                HStack {
                                    Text("Validate")
                                    if validating { Spacer(); ProgressView() }
                                }
                            }
                            .disabled(validating)
                        }
                        Button("Delete", role: .destructive) { confirmDelete = true }
                    } footer: {
                        if let validation {
                            Text(validation).foregroundStyle(validation.hasPrefix("✓") ? AnyShapeStyle(.tint) : AnyShapeStyle(.red))
                        }
                    }
                }
                .navigationTitle("\(w.name) · v\(w.version)")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done", action: onDone) } }
            }
            .confirmAlert($confirmDelete, title: "Delete workflow?", message: "Assets it made stay in the library.", confirm: "Delete") {
                studio.deleteWorkflow(w.id)
                onDone()
            }
            .alert("Rename", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Label", text: $newLabel)
                Button("Save") {
                    if let p = renaming { studio.saveWorkflow(WorkflowSchema.shared.rename(workflow: w, name: p.name, label: newLabel, now: nowMillis())) }
                    renaming = nil
                }
                Button("Cancel", role: .cancel) { renaming = nil }
            }
        } else {
            Color.clear.onAppear(perform: onDone)
        }
    }

    /// The schema: rename, reorder, remove, mark advanced; expose any other literal input.
    @ViewBuilder private func review(_ w: StudioWorkflow) -> some View {
        let studio = app.studio
        Section("Parameters") {
            ForEach(Array(w.params.enumerated()), id: \.element.name) { i, p in
                HStack(spacing: 4) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.label)
                        Text("\(p.name) · \(p.type.name.lowercased())\(p.advanced ? " · more" : "") · \(p.targets.count) input\(p.targets.count == 1 ? "" : "s")")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Button { studio.saveWorkflow(WorkflowSchema.shared.move(workflow: w, name: p.name, index: Int32(i - 1), now: nowMillis())) } label: {
                        Image(systemName: "arrow.up")
                    }
                    .buttonStyle(.borderless)
                    .disabled(i == 0)
                    .accessibilityLabel("Move \(p.label) up")
                    Menu {
                        Button("Rename", systemImage: "pencil") { newLabel = p.label; renaming = p }
                        Button(p.advanced ? "Show up front" : "Move under More", systemImage: p.advanced ? "eye" : "eye.slash") {
                            let updated = p.doCopy(name: p.name, label: p.label, type: p.type, targets: p.targets, default: p.default_, min: p.min, max: p.max, step: p.step,
                                                   options: p.options, multiline: p.multiline, role: p.role, advanced: !p.advanced)
                            if let next = try? WorkflowSchema.shared.update(workflow: w, param: updated, now: nowMillis()) { studio.saveWorkflow(next) }
                        }
                        Button("Remove", systemImage: "minus.circle", role: .destructive) {
                            studio.saveWorkflow(WorkflowSchema.shared.remove(workflow: w, name: p.name, now: nowMillis()))
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel("Edit \(p.label)")
                }
                .swipeActions {
                    Button("Remove \(p.label)", systemImage: "minus.circle", role: .destructive) {
                        studio.saveWorkflow(WorkflowSchema.shared.remove(workflow: w, name: p.name, now: nowMillis()))
                    }
                }
            }
        }
        let exposable = WorkflowSchema.shared.exposable(workflow: w, info: nil)
        if !exposable.isEmpty {
            Section("Expose an input · \(exposable.count)") {
                ForEach(Array(exposable.prefix(40).enumerated()), id: \.offset) { _, e in
                    Button {
                        if let next = try? WorkflowSchema.shared.expose(workflow: w, nodeId: e.nodeId, input: e.input, label: nil, info: nil, now: nowMillis()) { studio.saveWorkflow(next) }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(e.nodeTitle) · \(e.input)").lineLimit(1)
                                Text("\(e.type.name.lowercased()) · \(e.value.content.prefix(30))").font(.caption).foregroundStyle(.secondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            Image(systemName: "plus.circle").foregroundStyle(.tint)
                        }
                    }
                    .tint(.primary)
                    .accessibilityLabel("Expose \(e.input) of \(e.nodeTitle)")
                }
            }
        }
    }
}

// MARK: - Recipes

/// Runs a built-in recipe: its variables, the input asset when it needs one.
private struct PipelineRunSheet: View {
    let pipeline: Pipeline
    let onDone: () -> Void
    @Environment(AppContainer.self) private var app
    @State private var vars: [String: String] = [:]
    @State private var input: ParamValue?
    @State private var running = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(pipeline.description_).foregroundStyle(.secondary)
                } footer: {
                    Text(pipeline.steps.map(\.label).joined(separator: " → "))
                }
                Section {
                    ForEach(pipeline.variables, id: \.name) { v in
                        StackedField(label: v.label + (v.required ? "" : " · optional"), text: Binding(get: { vars[v.name] ?? v.default_ }, set: { vars[v.name] = $0 }),
                                     kind: v.multiline ? .prose : .mono, lines: v.multiline ? 2 ... 6 : nil)
                    }
                    if let type = pipeline.input {
                        AssetParamPicker(label: "Input \(type.name.lowercased())", type: type, role: "image", value: $input)
                    }
                }
            }
            .navigationTitle(pipeline.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onDone) }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Run") {
                        running = true
                        let ids = (input as? ParamValue.AssetValue).map { [$0.ref.assetId] } ?? []
                        Task {
                            let r = await app.studio.runPipeline(pipeline, vars: vars, input: ids)
                            app.studio.notice(r.ok ? "\(pipeline.name): \(r.message)" : "\(pipeline.name): \(r.message)")
                            running = false
                        }
                        onDone()
                    }
                    .disabled(running)
                }
            }
        }
    }
}

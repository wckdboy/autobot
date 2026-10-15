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
    @Environment(\.scheme) private var c
    @State private var editing: ServerEdit?
    @State private var testing: String?
    @State private var results: [String: (Bool, String)] = [:]
    @State private var importing: ImportStart?
    @State private var opened: String?
    @State private var running: Pipeline?

    var body: some View {
        let studio = app.studio
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SectionLabel("comfyui pcs")
                if studio.comfyServers.isEmpty {
                    Text("Workflows run on your own PC with ComfyUI. Add its address: https (Tailscale, a reverse proxy), or http://127.0.0.1 only for a server on this iPhone.")
                        .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                }
                ForEach(studio.comfyServers) { s in
                    Panel(title: s.name, accent: AutobotColors.cyan) {
                        RouteTag(route: s.route)
                    } content: {
                        Text(s.baseUrl).textStyle(.codeBlock).foregroundStyle(c.onSurfaceVariant).lineLimit(1)
                        HStack(spacing: 8) {
                            GhostButton("Test", enabled: testing == nil, busy: testing == s.id) { test(s) }.frame(width: 110)
                            TextAction("Edit") { editing = ServerEdit(server: s, isNew: false) }
                            if let r = results[s.id] { MicroLabel(r.1, color: r.0 ? c.primary : c.error, lines: 2) }
                            Spacer(minLength: 0)
                        }
                    }
                    .transition(.inkDissolve)
                }
                GhostButton("+ Add ComfyUI PC") {
                    editing = ServerEdit(server: ComfyServer(id: UUID().uuidString.lowercased(), name: "Studio PC", baseUrl: "https://"), isNew: true)
                }

                HStack {
                    SectionLabel("workflows · \(studio.library.workflows.count)")
                    TextAction("Import", color: c.primary) { importing = ImportStart(json: "") }
                }
                if studio.library.workflows.isEmpty {
                    Text("In ComfyUI, enable dev mode and use Workflow › Export (API). Paste that JSON, open the file, or share it to Autobot. Prompts, seeds, steps, sizes, models, LoRAs and input images are detected.")
                        .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                }
                ForEach(studio.library.workflows, id: \.id) { w in
                    RunRow(title: w.name, subtitle: "\(w.output.name.lowercased()) · \(w.params.count) params · v\(w.version) · \(studio.comfyServer(w.backendId)?.name ?? "any pc")", action: { opened = w.id }) {
                        Tag(w.output.name.lowercased(), accent: c.primary)
                    }
                }

                SectionLabel("recipes")
                MicroLabel("multi-step · each step's result lands in the project", lines: 2)
                ForEach(BuiltInPipelines.shared.all, id: \.id) { p in
                    let missing = missingEngines(p)
                    RunRow(title: p.name, subtitle: missing.isEmpty ? p.description_ : "needs \(missing.joined(separator: ", "))", action: missing.isEmpty ? { running = p } : nil) {
                        if missing.isEmpty { Tag("run", accent: c.primary) } else { Tag("unavailable") }
                    }
                }
                Spacer().frame(height: 12)
            }
            .padding(.horizontal, Tokens.Spacing.gutter)
            .padding(.top, 8)
            .animation(.inkDissolve, value: studio.comfyServers.map(\.id))
        }
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
    @Environment(\.scheme) private var c
    @State private var name = ""
    @State private var url = ""
    @State private var routing: RouteOverride = .inherit
    @State private var headerName = ""
    @State private var headerValue = ""

    var body: some View {
        let trimmed = url.trimmingCharacters(in: .whitespaces)
        let problem = ProviderDraft.cleartextProblem(trimmed)
        let valid = URL(string: trimmed).flatMap { $0.host() }.map { !$0.isEmpty } ?? false
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                MicroLabel(isNew ? "// new comfyui pc" : "// edit comfyui pc", color: c.primary)
                ConsoleTextField(text: $name, label: "Name")
                ConsoleTextField(text: $url, placeholder: "https://studio-pc.tailnet.ts.net", label: "Base URL", kind: .url)
                if let problem {
                    MicroLabel(problem, color: c.error, lines: 3)
                } else if let note = ProviderDraft.loopbackNote(trimmed) {
                    MicroLabel(note, lines: 3)
                } else {
                    MicroLabel("progress over wss · previews stream into the queue · every request follows your kill switch and is in the audit log", lines: 3)
                }
                SelectField(label: "Route", value: routing.rawValue.lowercased(), options: RouteOverride.allCases, optionLabel: { $0.rawValue.lowercased() }) { routing = $0 }
                SectionLabel("auth header · optional")
                ConsoleTextField(text: $headerName, placeholder: "Authorization", label: "Header", kind: .mono)
                ConsoleTextField(text: $headerValue, placeholder: initial.headerRef != nil ? "saved · leave empty to keep" : "Bearer …", label: "Value", kind: .secret)
                HStack {
                    if !isNew { TextAction("Delete", color: c.error, action: onDelete) }
                    Spacer()
                    PrimaryButton("Save", enabled: valid && problem == nil) {
                        var s = initial
                        s.name = name.trimmingCharacters(in: .whitespaces).isEmpty ? "ComfyUI" : name.trimmingCharacters(in: .whitespaces)
                        s.baseUrl = trimmed
                        s.routing = routing.rawValue
                        s.headerName = headerName.trimmingCharacters(in: .whitespaces).isEmpty ? nil : headerName.trimmingCharacters(in: .whitespaces)
                        onSave(s, s.headerName == nil ? "" : headerValue.isEmpty ? nil : headerValue)
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
    @Environment(\.scheme) private var c
    @State private var json = ""
    @State private var name = ""
    @State private var serverId: String?
    @State private var result: StudioHub.Imported?
    @State private var working = false
    @State private var showFiles = false

    var body: some View {
        let studio = app.studio
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel("import workflow")
                MicroLabel("api format · comfyui › workflow › export (api)", lines: 2)
                ConsoleTextField(text: $name, placeholder: "Portrait SDXL", label: "Name")
                HStack(spacing: 8) {
                    GhostButton("Paste") { json = UIPasteboard.general.string ?? json }
                    GhostButton("Files") { showFiles = true }
                }
                ConsoleTextField(text: $json, placeholder: "{\"3\": {\"class_type\": \"KSampler\", …}}", label: "Workflow JSON", kind: .mono, minLines: 4, maxLines: 10)
                if !studio.comfyServers.isEmpty {
                    SelectField(label: "Check against", value: studio.comfyServer(serverId)?.name ?? "don't check", options: [""] + studio.comfyServers.map(\.id),
                                optionLabel: { id in id.isEmpty ? "don't check" : studio.comfyServer(id)?.name ?? id }) { serverId = $0.isEmpty ? nil : $0 }
                }
                PrimaryButton("Detect parameters", enabled: !json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, busy: working) { detect() }
                if let result {
                    if let error = result.error {
                        Text(error).textStyle(.bodySmall).foregroundStyle(c.error).fixedSize(horizontal: false, vertical: true)
                    } else if let w = result.workflow {
                        SectionLabel("detected · makes \(w.output.name.lowercased())")
                        ForEach(w.params, id: \.name) { p in
                            HStack {
                                Text(p.label).textStyle(.titleSmall).foregroundStyle(c.onSurface)
                                MicroLabel("\(p.type.name.lowercased())\(p.role.map { " · \($0)" } ?? "")")
                                Spacer()
                                MicroLabel(p.default_?.display().prefix(24).description ?? "")
                            }
                        }
                        if w.params.isEmpty { MicroLabel("nothing detected · expose inputs after saving", lines: 2) }
                        if let v = result.validation {
                            Text(v).textStyle(.bodySmall).foregroundStyle(c.error).fixedSize(horizontal: false, vertical: true)
                        } else if result.checked {
                            MicroLabel("✓ every node and model is on \(studio.comfyServer(serverId)?.name ?? "the server")", color: c.primary, lines: 2)
                        } else {
                            MicroLabel("not checked against a server yet", lines: 2)
                        }
                        PrimaryButton("Save workflow") {
                            studio.saveWorkflow(w)
                            onDone(w.id)
                        }
                    }
                }
            }
            .padding(16)
        }
        .background(c.surfaceContainerLow)
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
    @Environment(\.scheme) private var c
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
            ScrollView {
                InkStack(alignment: .leading, spacing: 12) {
                    HStack {
                        SectionLabel("\(w.name) · v\(w.version)")
                        TextAction("Done", action: onDone)
                    }
                    if let server { RunsOnTag(label: server.name, route: server.route) } else { MicroLabel("add a comfyui pc to run it", color: c.error) }
                    Segmented(options: ["run", "parameters"], selected: tab, label: { $0 }) { tab = $0 }
                    if tab == "run" {
                        WorkflowForm(workflow: w, values: $values)
                        PrimaryButton("Queue", enabled: server != nil) {
                            let seed = w.params.first { $0.type == .seed }.flatMap { values[$0.name]?.asLong()?.int64Value }.flatMap { $0 >= 0 ? $0 : nil }
                            let r = Recipe(engine: EngineKinds.shared.COMFYUI, backendId: server?.id, model: nil, params: values, inputs: [], workflowId: w.id,
                                           workflowVersion: KotlinInt(value: w.version), seed: seed.map { KotlinLong(value: $0) }, output: w.output)
                            studio.submit(r, label: w.name)
                            onDone()
                        }
                    } else {
                        review(w)
                    }
                    HStack(spacing: 8) {
                        if let server {
                            GhostButton("Validate", busy: validating) {
                                validating = true
                                Task {
                                    let r = await studio.validate(w, on: server.id)
                                    validation = r.error.map { "Can't reach \(server.name): \($0)" } ?? r.message ?? "✓ ready on \(server.name)"
                                    let refreshed = await studio.refreshOptions(w, on: server.id)
                                    if refreshed !== w { studio.saveWorkflow(refreshed) }
                                    validating = false
                                }
                            }
                        }
                        TextAction("Delete", color: c.error) { confirmDelete = true }
                    }
                    if let validation {
                        Text(validation).textStyle(.bodySmall).foregroundStyle(validation.hasPrefix("✓") ? c.primary : c.error).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(16)
            }
            .background(c.surfaceContainerLow)
            .risoAlert($confirmDelete, title: "Delete workflow?", message: "Assets it made stay in the library.", confirm: "Delete") {
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
        ForEach(Array(w.params.enumerated()), id: \.element.name) { i, p in
            HStack(spacing: 4) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.label).textStyle(.titleSmall).foregroundStyle(c.onSurface)
                    MicroLabel("\(p.name) · \(p.type.name.lowercased())\(p.advanced ? " · more" : "") · \(p.targets.count) input\(p.targets.count == 1 ? "" : "s")")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                IconAction(symbol: "arrow.up", label: "Move \(p.label) up", size: 13, enabled: i > 0) {
                    studio.saveWorkflow(WorkflowSchema.shared.move(workflow: w, name: p.name, index: Int32(i - 1), now: nowMillis()))
                }
                IconAction(symbol: "pencil", label: "Rename \(p.label)", size: 13) { newLabel = p.label; renaming = p }
                IconAction(symbol: p.advanced ? "eye" : "eye.slash", label: p.advanced ? "Show up front" : "Move under More", size: 13) {
                    let updated = p.doCopy(name: p.name, label: p.label, type: p.type, targets: p.targets, default: p.default_, min: p.min, max: p.max, step: p.step,
                                           options: p.options, multiline: p.multiline, role: p.role, advanced: !p.advanced)
                    if let next = try? WorkflowSchema.shared.update(workflow: w, param: updated, now: nowMillis()) { studio.saveWorkflow(next) }
                }
                IconAction(symbol: "minus.circle", label: "Remove \(p.label)", size: 13) {
                    studio.saveWorkflow(WorkflowSchema.shared.remove(workflow: w, name: p.name, now: nowMillis()))
                }
            }
            Hairline()
        }
        let exposable = WorkflowSchema.shared.exposable(workflow: w, info: nil)
        if !exposable.isEmpty {
            SectionLabel("expose an input · \(exposable.count)")
            ForEach(Array(exposable.prefix(40).enumerated()), id: \.offset) { _, e in
                Button {
                    if let next = try? WorkflowSchema.shared.expose(workflow: w, nodeId: e.nodeId, input: e.input, label: nil, info: nil, now: nowMillis()) { studio.saveWorkflow(next) }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(e.nodeTitle) · \(e.input)").textStyle(.labelMedium).foregroundStyle(c.onSurface).lineLimit(1)
                            MicroLabel("\(e.type.name.lowercased()) · \(e.value.content.prefix(30))")
                        }
                        Spacer()
                        Text("+").textStyle(.titleMedium).foregroundStyle(c.primary)
                    }
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressStyle())
                .accessibilityLabel("Expose \(e.input) of \(e.nodeTitle)")
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
    @Environment(\.scheme) private var c
    @State private var vars: [String: String] = [:]
    @State private var input: ParamValue?
    @State private var running = false

    var body: some View {
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                SectionLabel(pipeline.name)
                Text(pipeline.description_).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
                MicroLabel(pipeline.steps.map(\.label).joined(separator: " → "))
                ForEach(pipeline.variables, id: \.name) { v in
                    ConsoleTextField(text: Binding(get: { vars[v.name] ?? v.default_ }, set: { vars[v.name] = $0 }), label: v.label + (v.required ? "" : " · optional"),
                                     kind: v.multiline ? .prose : .mono, minLines: v.multiline ? 2 : 1, maxLines: v.multiline ? 6 : 1)
                }
                if let type = pipeline.input {
                    AssetParamPicker(label: "Input \(type.name.lowercased())", type: type, role: "image", value: $input)
                }
                PrimaryButton("Run", busy: running) {
                    running = true
                    let ids = (input as? ParamValue.AssetValue).map { [$0.ref.assetId] } ?? []
                    Task {
                        let r = await app.studio.runPipeline(pipeline, vars: vars, input: ids)
                        app.studio.notice(r.ok ? "\(pipeline.name): \(r.message)" : "\(pipeline.name): \(r.message)")
                        running = false
                    }
                    onDone()
                }
            }
            .padding(16)
        }
        .background(c.surfaceContainerLow)
    }
}

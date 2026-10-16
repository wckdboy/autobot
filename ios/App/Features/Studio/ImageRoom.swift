import AutobotShared
import Observation
import PhotosUI
import SwiftUI

/// Where the image room's next job runs: an image backend (on device, A1111, Local SSE) or a ComfyUI workflow.
enum ImageTarget: Hashable {
    case backend(String)
    case workflow(String)
}

enum ImageMode: String, CaseIterable, Hashable {
    case txt2img, img2img, inpaint, outpaint
}

/**
 Image room state (spec §2): every control the chosen backend supports, the input image, mask and
 outpaint margins, prompt tools, seed mode, batch, the upscale pass and the X/Y grid. The request
 is shared with Imagine (saved as the last request).
 */
@MainActor
@Observable
final class ImageRoomModel: MaskEditing {
    static let sizes: [(String, Int, Int)] = [("1:1", 1024, 1024), ("1:1 s", 512, 512), ("2:3", 832, 1216), ("3:2", 1216, 832), ("9:16", 768, 1344), ("16:9", 1344, 768), ("3:4", 896, 1152), ("4:3", 1152, 896)]

    var request: DiffusionRequest
    var target: ImageTarget?
    var mode: ImageMode = .txt2img
    var seedMode: SeedMode = .random
    var seed: Int64 = Int64.random(in: 0 ... 4_294_967_295)
    var batch = 1
    var upscalePass = false
    private(set) var initAssetId: String?
    private(set) var initImage: UIImage?
    private(set) var mask = MaskState()
    var outpaint = StudioRecipes.Margins(right: 256)
    /// The result on the canvas (nil: the newest image of the project).
    var canvasId: String?
    var workflowValues: [String: ParamValue] = [:]
    private(set) var catalogs: [String: EngineCatalog] = [:]
    private(set) var catalogError: String?
    /// Jobs this room queued (the canvas follows the newest running one).
    private(set) var jobIds: [String] = []
    var gridRunId: String?

    @ObservationIgnored private let app: AppContainer
    @ObservationIgnored private var saveTask: Task<Void, Never>?

    init(app: AppContainer) {
        self.app = app
        var r = app.backends.lastRequest
        r.batchCount = 1
        request = r
        if r.seed >= 0 { seed = r.seed; seedMode = .locked }
        if let b = app.backends.defaultBackend { target = .backend(b.id) } else if let w = app.studio.library.workflows.first(where: { $0.output == .image }) { target = .workflow(w.id) }
    }

    // MARK: Derived

    var backends: [DiffusionBackend] { app.backends.backends }
    var workflows: [StudioWorkflow] { app.studio.library.workflows.filter { $0.output == .image } }

    var backend: DiffusionBackend? {
        guard case .backend(let id) = target else { return nil }
        return backends.first { $0.id == id }
    }

    var workflow: StudioWorkflow? {
        guard case .workflow(let id) = target else { return nil }
        return app.studio.library.workflow(id)
    }

    var capabilities: EngineCapabilities? { backend.map { app.generator.engine(for: $0).capabilities } }
    var catalog: EngineCatalog { backend.flatMap { catalogs[$0.id] } ?? EngineCatalog() }

    var modes: [ImageMode] {
        guard let caps = capabilities else { return [.txt2img] }
        var m: [ImageMode] = [.txt2img]
        if caps.modes.contains(.img2img) { m.append(.img2img) }
        if caps.modes.contains(.inpaint) { m += [.inpaint, .outpaint] }
        return m
    }

    /// On-device Core ML image models (the size is fixed by the model).
    var onDeviceModels: [InstalledModel] { app.library.models.filter { $0.isReady && $0.kind == .image && $0.engine == .coreML } }

    var runsOn: (label: String, route: Route)? {
        if let backend {
            if backend.kind == .onDevice { return ("this iphone", .localNpu) }
            return (backend.name, backend.isLoopback ? .localCpu : .pcLan)
        }
        if let workflow, let s = app.studio.comfyServer(workflow.backendId) ?? app.studio.comfyServers.first { return (s.name, s.route) }
        return nil
    }

    var blocker: String? {
        if target == nil { return "Get an image model or add a PC" }
        if workflow != nil && app.studio.comfyServers.isEmpty { return "Add your ComfyUI PC in Workflows" }
        if backend != nil && request.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "Type a prompt" }
        if mode != .txt2img && initAssetId == nil && backend != nil { return "\(mode.rawValue) needs an input image" }
        if mode == .inpaint && mask.isEmpty && backend != nil { return "Paint a mask over the part to repaint" }
        return nil
    }

    /// The newest job of this room that is still waiting or running.
    var runningJob: StudioJob? {
        jobIds.reversed().lazy.compactMap { self.app.studio.job($0) }.first { !$0.state.isFinished }
    }

    var projectImages: [StudioAsset] {
        app.studio.library.assets.filter { $0.type == .image && $0.projectId == app.studio.projectId && !StudioRecipes.hiddenEngines.contains($0.recipe.engine) }
    }

    var canvasAsset: StudioAsset? {
        if let canvasId, let a = app.studio.library.asset(canvasId) { return a }
        return projectImages.first
    }

    // MARK: Edits

    func edit(_ change: (inout DiffusionRequest) -> Void) {
        change(&request)
        let snapshot = request
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            self?.app.backends.saveLastRequest(snapshot)
        }
    }

    func select(_ t: ImageTarget) {
        target = t
        if case .backend(let id) = t {
            app.backends.setDefault(id)
            if !modes.contains(mode) { mode = .txt2img }
            refreshCatalog()
        } else if let w = workflow {
            workflowValues = workflowValues.filter { w.param(name: $0.key) != nil }
        }
    }

    func selectOnDeviceModel(_ m: InstalledModel) {
        edit { r in
            r.model = m.id
            if let rec = m.manifest.recommended {
                r.steps = rec.steps ?? r.steps
                r.cfgScale = rec.cfg ?? r.cfgScale
                r.sampler = rec.sampler ?? r.sampler
                r.scheduler = rec.scheduler ?? r.scheduler
                r.width = rec.width ?? r.width
                r.height = rec.height ?? r.height
            }
        }
    }

    func refreshCatalog() {
        guard let backend, backend.kind != .onDevice else { return }
        let engine = app.generator.engine(for: backend)
        catalogError = nil
        Task {
            do { catalogs[backend.id] = try await engine.catalog() } catch { catalogError = error.localizedDescription }
        }
    }

    func setSize(_ w: Int, _ h: Int) {
        let m = capabilities?.sizeMultiple ?? 8
        edit { $0.width = PromptSyntax.snap(w, multiple: m); $0.height = PromptSyntax.snap(h, multiple: m) }
    }

    func addLora(_ name: String) { edit { r in if !r.loras.contains(where: { $0.name == name }) { r.loras.append(LoraRef(name: name, weight: 0.8)) } } }
    func setLora(_ name: String, _ w: Double) { edit { r in r.loras = r.loras.map { $0.name == name ? LoraRef(name: name, weight: (w * 20).rounded() / 20) : $0 } } }
    func removeLora(_ name: String) { edit { r in r.loras.removeAll { $0.name == name } } }

    // MARK: Input image and mask

    /// "Use as input": a library image becomes the init image (img2img unless a mode is already set).
    func useAsInput(_ asset: StudioAsset) async {
        guard asset.type == .image, let data = await app.studio.bytes(asset.id), let image = await decodeImage(data, maxSide: 1536) else { return }
        initAssetId = asset.id
        initImage = image
        mask = MaskState()
        if mode == .txt2img { mode = .img2img }
        if backend?.kind != .onDevice {
            let long = min(max(max(asset.width, asset.height), 512), 1024)
            let scale = Double(long) / Double(max(asset.width, asset.height, 1))
            setSize(Int(Double(asset.width) * scale), Int(Double(asset.height) * scale))
        }
    }

    /// A picked photo goes into the project first (re-encoded, encrypted), then becomes the input.
    func usePhoto(_ data: Data) async {
        guard let image = await Task.detached(operation: { ImageCodec.decode(data, maxSide: 2048) }).value,
              let upright = ImageCodec.upright(image, maxSide: 2048), let png = ImageCodec.png(upright) else {
            app.studio.notice("Could not read that photo")
            return
        }
        let recipe = Recipe(engine: "import", backendId: nil, model: nil, params: [:], inputs: [], workflowId: nil, workflowVersion: nil, seed: nil, output: .image)
        if let a = try? await app.studio.addAsset(png, type: .image, mime: "image/png", recipe: recipe, width: upright.width, height: upright.height, label: "photo") {
            await useAsInput(a)
        }
    }

    func clearInput() {
        initAssetId = nil
        initImage = nil
        mask = MaskState()
        mode = .txt2img
    }

    func addStroke(_ stroke: MaskStroke) { mask = mask.add(stroke) }
    func undoStroke() { mask = mask.undo() }
    func redoStroke() { mask = mask.redoLast() }
    func clearMask() { mask = mask.clear() }
    func invertMask() { mask = mask.invert() }

    // MARK: Reuse

    /// "Reuse recipe": the form takes an asset's recipe (backend, params, input, seed locked).
    func reuse(_ recipe: Recipe) async {
        if recipe.engine == EngineKinds.shared.COMFYUI, let id = recipe.workflowId {
            target = .workflow(id)
            workflowValues = recipe.params
        } else {
            var r = StudioRecipes.request(recipe)
            r.batchCount = 1
            edit { $0 = r }
            if let id = recipe.backendId { target = .backend(id) } else if let on = backends.first(where: { $0.kind == .onDevice }) { target = .backend(on.id) }
            if let input = recipe.input("image"), let a = app.studio.library.asset(input) {
                await useAsInput(a)
                mode = recipe.text("outpaint") != nil ? .outpaint : recipe.text("mask") != nil ? .img2img : .img2img
                if let m = StudioRecipes.Margins(recipe.text("outpaint")) { outpaint = m }
            } else {
                clearInput()
            }
        }
        if let s = recipe.seedValue { seed = s; seedMode = .locked }
    }

    // MARK: Run

    /// The recipe the form describes now (seed per [seedMode] is applied by the batch planner).
    func recipe() -> Recipe? {
        if let workflow {
            var params = workflowValues.filter { workflow.param(name: $0.key) != nil }
            if workflow.param(name: "prompt") != nil, params["prompt"] == nil { params["prompt"] = .text(request.prompt) }
            if workflow.param(name: "negative") != nil, params["negative"] == nil { params["negative"] = .text(request.negativePrompt) }
            let server = workflow.backendId.flatMap { app.studio.comfyServer($0) } ?? app.studio.comfyServers.first
            return Recipe(engine: EngineKinds.shared.COMFYUI, backendId: server?.id, model: nil, params: params, inputs: [], workflowId: workflow.id,
                          workflowVersion: KotlinInt(value: workflow.version), seed: seedMode == .random ? nil : KotlinLong(value: seed), output: .image)
        }
        guard let backend else { return nil }
        var q = request
        q.prompt = q.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        q.seed = seedMode == .random ? -1 : seed
        if mode == .outpaint, let a = initAssetId.flatMap({ app.studio.library.asset($0) }) {
            // The source fitted to the current size's long side, plus the margins.
            let long = Double(max(q.width, q.height))
            let scale = long / Double(max(a.width, a.height, 1))
            let m = capabilities?.sizeMultiple ?? 8
            q.width = PromptSyntax.snap(Int(Double(a.width) * scale) + outpaint.left + outpaint.right, multiple: m, max: 2048)
            q.height = PromptSyntax.snap(Int(Double(a.height) * scale) + outpaint.top + outpaint.bottom, multiple: m, max: 2048)
        }
        var r = StudioRecipes.recipe(q, backendId: backend.kind == .onDevice ? nil : backend.id)
        if mode != .txt2img, let id = initAssetId {
            r = r.withInput(asset: id, role: "image")
            r = r.with(name: "strength", value: .double(mode == .outpaint ? 1 : q.denoiseStrength))
            if mode == .inpaint, let png = MaskRasterizer.render(mask, width: q.width, height: q.height, encoding: .alphaWhite).flatMap(ImageCodec.png) {
                r = r.with(name: "mask", value: .text(png.base64EncodedString()))
                r = r.with(name: "mask_blur", value: .int(q.maskBlur))
            }
            if mode == .outpaint { r = r.with(name: "outpaint", value: .text(outpaint.text)) }
        }
        return r
    }

    func generate() {
        guard blocker == nil, let recipe = recipe() else { return }
        app.backends.saveLastRequest(request)
        let studio = app.studio
        let upscale = upscalePass && studio.has(EngineKinds.shared.UPSCALE)
        let jobs = studio.submitBatch(recipe, count: batch, mode: seedMode, parent: mode == .txt2img ? nil : initAssetId, then: upscale ? { asset in
            let r = Recipe(engine: EngineKinds.shared.UPSCALE, backendId: nil, model: nil, params: ["scale": .int(4)], inputs: [AssetRef(assetId: asset.id, role: "image")],
                           workflowId: nil, workflowVersion: nil, seed: asset.recipe.seed, output: .image)
            studio.submit(r, label: "upscale 4×", parent: asset.id)
        } : nil)
        jobIds += jobs.map(\.id)
        if seedMode == .walk { seed = (seed + Int64(jobs.count)) % 4_294_967_296 }
        canvasId = nil
    }

    func grid(x: GridAxis, y: GridAxis?) {
        guard let recipe = recipe() else { return }
        do {
            let run = try app.studio.submitGrid(recipe, x: x, y: y, title: request.prompt)
            gridRunId = run.id
            jobIds += run.jobIds.flatMap { $0 }
        } catch {
            app.studio.notice(error.localizedDescription)
        }
    }

    var gridParams: [GridParam] {
        if let workflow {
            var out = workflow.params.filter { p in p.type == .int_ || p.type == .float_ || p.type == .enum_ || p.type == .seed }.map {
                GridParam(name: $0.name, label: $0.label.lowercased(), integer: $0.type == .int_ || $0.type == .seed, hint: $0.type == .enum_ ? $0.options.prefix(3).joined(separator: ", ") : "10, 20, 30")
            }
            if !out.contains(where: { $0.name == "seed" }) { out.append(GridParam(name: "seed", label: "seed", integer: true, hint: "1-4")) }
            return out
        }
        var p = [GridParam(name: "steps", label: "steps", integer: true, hint: "10, 20, 30"), GridParam(name: "cfg", label: "cfg", integer: false, hint: "4-8 (+2)"),
                 GridParam(name: "seed", label: "seed", integer: true, hint: "1-4")]
        let samplers = backend?.kind == .onDevice ? LocalDiffusion.samplers : catalog.samplers
        if !samplers.isEmpty { p.append(GridParam(name: "sampler", label: "sampler", integer: false, hint: samplers.prefix(3).joined(separator: ", "))) }
        if mode != .txt2img { p.append(GridParam(name: "strength", label: "denoise", integer: false, hint: "0.3-0.9 (+0.2)")) }
        for l in request.loras { p.append(GridParam(name: StudioRecipes.loraPrefix + l.name, label: "lora \(l.name)", integer: false, hint: "0.2-1 (+0.2)")) }
        if backend?.kind != .onDevice, !catalog.models.isEmpty { p.append(GridParam(name: "model", label: "model", integer: false, hint: catalog.models.prefix(2).joined(separator: ", "))) }
        return p
    }
}

// MARK: - View

struct ImageRoom: View {
    @Bindable var model: ImageRoomModel
    let session: StudioSession
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var showMask = false
    @State private var showGrid = false
    @State private var showLoras = false
    @State private var picked: PhotosPickerItem?

    var body: some View {
        if model.backends.isEmpty && model.workflows.isEmpty {
            ScrollView {
                StudioEmpty(title: "no image engine yet",
                            text: "Download a Core ML image model to run on this iPhone, point Autobot at a PC (A1111 / Forge / Local Dream), or add a ComfyUI workflow.",
                            action: "Get a model") {
                    nav.leaveStudio()
                    nav.switchTo(.models)
                }
                HStack(spacing: 8) {
                    GhostButton("Add a pc") { nav.leaveStudio(); nav.reset(.run, [.imageBackends]) }
                    GhostButton("Workflows") { nav.studio = .workflows }
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
            }
        } else {
            VStack(spacing: 0) {
                canvas
                    .frame(maxHeight: .infinity)
                StudioPanel(title: "controls", collapsed: Binding(get: { session.collapsed }, set: { session.collapsed = $0 })) { controls }
                    .frame(maxHeight: session.collapsed ? nil : 320)
                    // The controls get their height first; the canvas takes the rest.
                    .layoutPriority(1)
                runBar
            }
            .fullScreenCover(isPresented: $showMask) {
                if let image = model.initImage { MaskEditor(image: image, model: model) { showMask = false }.darkroomTheme() }
            }
            .sheet(isPresented: $showGrid) {
                GridBuilderSheet(params: model.gridParams) { x, y in
                    model.grid(x: x, y: y)
                    showGrid = false
                }
                .presentationDetents([.medium, .large])
            }
            .sheet(isPresented: $showLoras) {
                LoraPicker(available: model.catalog.loras, selected: Set(model.request.loras.map(\.name))) {
                    model.addLora($0)
                    showLoras = false
                }
                .presentationDetents([.medium, .large])
            }
            .onChange(of: picked) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) { await model.usePhoto(data) }
                    picked = nil
                }
            }
            .task { model.refreshCatalog() }
        }
    }

    // MARK: Canvas

    private var canvas: some View {
        let running = model.runningJob
        let asset = model.canvasAsset
        return VStack(spacing: 6) {
            if let grid = model.gridRunId.flatMap({ id in app.studio.grids.first { $0.id == id } }) {
                ScrollView {
                    HStack {
                        SectionLabel("grid · \(grid.title.prefix(30))")
                        TextAction("Close") { model.gridRunId = nil }
                    }
                    GridResultView(run: grid) { a in model.canvasId = a.id; model.gridRunId = nil }
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
            } else {
                ZStack(alignment: .bottomLeading) {
                    c.surfaceContainerLowest
                    if let running {
                        RunningPlate(job: running)
                    } else if let asset {
                        CanvasImage(asset: asset)
                    } else {
                        Color.clear.inkCard(seed: 0.6, fade: .bottomLeading, cornerRadius: 0)
                        MicroLabel("the canvas · results print here").padding(10)
                    }
                    if let running {
                        Tag(running.state == .queued ? "queued" : running.steps > 0 ? "step \(running.step)/\(running.steps)" : (running.status ?? "loading"),
                            accent: c.primary, filled: true, busy: running.steps == 0 && running.state == .running).padding(8)
                    }
                }
                .frame(minHeight: 170)
                .clipped()
                .padding(.horizontal, Tokens.Spacing.gutter)
                .padding(.top, 6)
                if !model.projectImages.isEmpty && running == nil { contactSheet(selected: asset) }
            }
        }
    }

    /// Recent images of the project (tap to put one on the canvas) and actions for the one shown.
    private func contactSheet(selected: StudioAsset?) -> some View {
        VStack(spacing: 6) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(model.projectImages.prefix(30), id: \.id) { a in
                        AssetThumb(asset: a, size: 48)
                            .overlay { if a.id == selected?.id { Rectangle().strokeBorder(c.primary, lineWidth: Tokens.Stroke.selection) } }
                            .onTapGesture { model.canvasId = a.id }
                            .accessibilityAddTraits(.isButton)
                    }
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
            }
            if let selected {
                HStack(spacing: 6) {
                    MicroLabel(StudioText.meta(selected)).frame(maxWidth: .infinity, alignment: .leading)
                    Tag("→ input") { Task { await model.useAsInput(selected) } }
                    Tag("↺ recipe", accent: c.primary) { Task { await model.reuse(selected.recipe) } }
                    if app.studio.has(EngineKinds.shared.UPSCALE) {
                        Tag("4×") {
                            app.studio.submit(Recipe(engine: EngineKinds.shared.UPSCALE, backendId: nil, model: nil, params: [:], inputs: [AssetRef(assetId: selected.id, role: "image")],
                                                     workflowId: nil, workflowVersion: nil, seed: nil, output: .image), label: "upscale 4×", parent: selected.id)
                        }
                    }
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
            }
        }
        .padding(.bottom, 6)
    }

    // MARK: Controls

    @ViewBuilder private var controls: some View {
        targetRow
        if let w = model.workflow {
            PromptBox(prompt: Binding(get: { (model.workflowValues["prompt"] as? ParamValue.StringValue)?.value ?? model.request.prompt },
                                      set: { model.workflowValues["prompt"] = .text($0) }),
                      negative: Binding(get: { (model.workflowValues["negative"] as? ParamValue.StringValue)?.value ?? model.request.negativePrompt },
                                        set: { model.workflowValues["negative"] = .text($0) }),
                      showNegative: w.param(name: "negative") != nil)
            WorkflowForm(workflow: w, values: $model.workflowValues) { $0.name == "prompt" || $0.name == "negative" || $0.type == .seed }
        } else {
            PromptBox(prompt: Binding(get: { model.request.prompt }, set: { v in model.edit { $0.prompt = v } }),
                      negative: Binding(get: { model.request.negativePrompt }, set: { v in model.edit { $0.negativePrompt = v } }))
            backendControls
        }
        SeedControl(mode: $model.seedMode, seed: $model.seed)
        HStack(alignment: .bottom, spacing: 12) {
            StepperField(label: "Batch", value: model.batch, range: 1 ... 8) { model.batch = $0 }
            if app.studio.has(EngineKinds.shared.UPSCALE) {
                VStack(alignment: .leading, spacing: 4) {
                    MicroLabel("upscale pass 4×")
                    RisoToggle(isOn: $model.upscalePass)
                }
            }
            Spacer()
        }
    }

    private var targetRow: some View {
        VStack(alignment: .leading, spacing: 6) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(model.backends) { b in
                        chip(b.kind == .onDevice ? "this iphone" : b.name, active: model.target == .backend(b.id)) { model.select(.backend(b.id)) }
                    }
                    ForEach(model.workflows, id: \.id) { w in
                        chip("◐ \(w.name)", active: model.target == .workflow(w.id)) { model.select(.workflow(w.id)) }
                    }
                }
            }
            if let runs = model.runsOn { RunsOnTag(label: runs.label, route: runs.route) }
            if let error = model.catalogError {
                HStack {
                    MicroLabel("can't reach \(model.backend?.name ?? "the backend"): \(error)", color: c.error, lines: 2)
                    TextAction("Retry") { model.refreshCatalog() }
                }
            }
        }
    }

    private func chip(_ label: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label.uppercased())
                .textStyle(.micro)
                .lineLimit(1)
                .foregroundStyle(active ? c.background : c.onBackground)
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(active ? c.onBackground : Color.clear))
                .overlay(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).strokeBorder(c.onBackground, lineWidth: 1))
        }
        .buttonStyle(PressStyle())
        .accessibilityAddTraits(active ? .isSelected : [])
    }

    @ViewBuilder private var backendControls: some View {
        let r = model.request
        let caps = model.capabilities
        let kind = model.backend?.kind
        if kind == .onDevice {
            if model.onDeviceModels.count > 1 {
                SelectField(label: "Model", value: model.onDeviceModels.first { $0.id == r.model }?.title ?? model.onDeviceModels.first?.title ?? "", options: model.onDeviceModels.map(\.id),
                            optionLabel: { id in model.onDeviceModels.first { $0.id == id }?.title ?? id }) { id in
                    if let m = model.onDeviceModels.first(where: { $0.id == id }) { model.selectOnDeviceModel(m) }
                }
            }
        } else if !model.catalog.models.isEmpty {
            SelectField(label: "Checkpoint", value: shortModelName(r.model ?? model.catalog.currentModel ?? ""), options: model.catalog.models, optionLabel: shortModelName) { v in model.edit { $0.model = v } }
        }
        Segmented(options: model.modes, selected: model.mode, label: { $0.rawValue }) { m in model.mode = m }
        if model.mode != .txt2img { inputStrip }
        let (samplers, value): ([String], String) = switch kind {
        case .onDevice: (LocalDiffusion.samplers, r.sampler)
        case .localSse: (LocalSseEngine.schedulers, LocalSseEngine.schedulerId(r.sampler, r.scheduler))
        default: (model.catalog.samplers.isEmpty ? ["DPM++ 2M", "Euler a", "Euler", "DDIM", "UniPC"] : model.catalog.samplers, r.sampler)
        }
        HStack(alignment: .top, spacing: 8) {
            SelectField(label: "Sampler", value: value, options: samplers) { v in model.edit { $0.sampler = v; if kind == .localSse { $0.scheduler = nil } } }
            let schedulers: [String] = kind == .onDevice ? LocalDiffusion.schedulers : kind == .sdApi ? model.catalog.schedulers : []
            if !schedulers.isEmpty { SelectField(label: "Schedule", value: r.scheduler ?? "default", options: schedulers) { v in model.edit { $0.scheduler = v } } }
        }
        ValueSlider(label: "Steps", value: Binding(get: { Double(model.request.steps) }, set: { v in model.edit { $0.steps = Int(v) } }),
                    range: 1 ... Double(caps?.maxSteps ?? 150), step: 1, format: { "\(Int($0))" })
        ValueSlider(label: "CFG", value: Binding(get: { model.request.cfgScale }, set: { v in model.edit { $0.cfgScale = (v * 2).rounded() / 2 } }), range: 1 ... 20, format: { String(format: "%.1f", $0) })
        if kind == .onDevice {
            MicroLabel("size · \(r.width)×\(r.height) · fixed by the core ml model", lines: 2)
        } else {
            MicroLabel("size · \(r.width)×\(r.height)")
            ImagineTagFlow(spacing: 6) {
                ForEach(ImageRoomModel.sizes, id: \.0) { label, w, h in
                    let active = w == r.width && h == r.height
                    Tag("\(label) \(w)", accent: active ? c.primary : c.onSurfaceVariant, filled: active) { model.setSize(w, h) }
                }
            }
            HStack(spacing: 8) {
                StepperField(label: "Width ÷64", value: r.width / 64, range: 4 ... 32) { model.setSize($0 * 64, r.height) }
                StepperField(label: "Height ÷64", value: r.height / 64, range: 4 ... 32) { model.setSize(r.width, $0 * 64) }
            }
        }
        HStack {
            MicroLabel("lora stack").frame(maxWidth: .infinity, alignment: .leading)
            if caps?.runtimeLora == true { Tag("+ add", accent: c.primary) { showLoras = true } }
        }
        if caps?.runtimeLora == false { MicroLabel("this backend runs a fixed model; merge loras before conversion", lines: 2) }
        ForEach(r.loras, id: \.name) { lora in
            HStack {
                ValueSlider(label: lora.name, value: Binding(get: { lora.weight }, set: { model.setLora(lora.name, $0) }), range: -1 ... 2)
                IconAction(symbol: "xmark", label: "Remove \(lora.name)", size: 14) { model.removeLora(lora.name) }
            }
        }
        Tag("x/y grid", accent: c.primary) { showGrid = true }.accessibilityLabel("Build an X/Y grid")
    }

    private var inputStrip: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                PhotosPicker(selection: $picked, matching: .images, photoLibrary: .shared()) {
                    ZStack {
                        c.surfaceContainer
                        if let image = model.initImage {
                            Image(uiImage: image).resizable().scaledToFill()
                            if model.mode == .inpaint { MaskOverlay(mask: model.mask) }
                        } else {
                            AutobotIconView(icon: .image, color: c.onSurfaceVariant)
                        }
                    }
                    .frame(width: 72, height: 72)
                    .clipped()
                }
                .accessibilityLabel("Pick an input photo")
                VStack(alignment: .leading, spacing: 4) {
                    MicroLabel(model.initAssetId == nil ? "input · pick a photo or “→ input” a result" : "input · \(model.mode.rawValue)", lines: 2)
                    if model.mode != .outpaint {
                        ValueSlider(label: "Denoise", value: Binding(get: { model.request.denoiseStrength }, set: { v in model.edit { $0.denoiseStrength = (v * 100).rounded() / 100 } }), range: 0.05 ... 1)
                    }
                }
                if model.initAssetId != nil {
                    IconAction(symbol: "xmark", label: "Remove the input", size: 14) { model.clearInput() }
                }
            }
            if model.mode == .inpaint && model.initImage != nil {
                GhostButton(model.mask.isEmpty ? "Paint the mask" : "Edit mask · \(counted(model.mask.strokes.count, "stroke"))") { showMask = true }
            }
            if model.mode == .outpaint {
                MicroLabel("extend the canvas · px added on each side")
                HStack(spacing: 6) {
                    StepperField(label: "Left", value: model.outpaint.left / 64, range: 0 ... 8) { model.outpaint.left = $0 * 64 }
                    StepperField(label: "Right", value: model.outpaint.right / 64, range: 0 ... 8) { model.outpaint.right = $0 * 64 }
                }
                HStack(spacing: 6) {
                    StepperField(label: "Top", value: model.outpaint.top / 64, range: 0 ... 8) { model.outpaint.top = $0 * 64 }
                    StepperField(label: "Bottom", value: model.outpaint.bottom / 64, range: 0 ... 8) { model.outpaint.bottom = $0 * 64 }
                }
            }
        }
    }

    // MARK: Run bar

    private var runBar: some View {
        let blocker = model.blocker
        return VStack(spacing: 6) {
            HStack {
                Text(blocker ?? summary).textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 8) {
                if model.workflow != nil { GhostButton("Grid") { showGrid = true }.frame(width: 96) }
                PrimaryButton(model.batch > 1 ? "Generate × \(model.batch)" : "Generate", enabled: blocker == nil) { model.generate() }
            }
        }
        .padding(.horizontal, Tokens.Spacing.gutter)
        .padding(.vertical, 8)
        .background(c.background)
        .hairlineEdge(top: true, bottom: false)
    }

    private var summary: String {
        if let w = model.workflow { return "\(w.name) · v\(w.version) · seed \(model.seedMode == .random ? "random" : "\(model.seed)")" }
        let r = model.request
        return "\(model.mode.rawValue) · \(counted(r.steps, "step")) · cfg \(String(format: "%.1f", r.cfgScale)) · \(r.width)×\(r.height)"
    }
}

/// A running job on the canvas: the ink plate flowing with its progress, developing into previews.
struct RunningPlate: View {
    let job: StudioJob
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var preview: PlateImage?
    @State private var serial = 0

    var body: some View {
        let data = app.studio.previews[job.id]
        let ink: PlateInk = {
            if let preview, data != nil { return .preview(preview) }
            if job.state == .queued { return .idle }
            return .flow(progress: Double(job.progress), loading: job.steps == 0)
        }()
        InkPlate(ink: ink, dot: c.primary, idleDot: c.outlineVariant, plate: c.primary)
            .task(id: data) {
                guard let data, let image = await decodeImage(data, maxSide: 768), let cg = image.cgImage, let grid = LumaGrid(image: cg) else { return }
                serial += 1
                preview = PlateImage(id: "\(job.id)-\(serial)", grid: grid)
            }
            .accessibilityElement()
            .accessibilityLabel(job.state == .queued ? "Queued" : "Generating, \(Int(job.progress * 100)) percent")
    }
}

/// A finished image on the canvas (prints from the plate the first time it shows).
struct CanvasImage: View {
    let asset: StudioAsset
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image, let grid = LumaCache.grid(image) {
                InkPlate(ink: .final(PlateImage(id: asset.id, grid: grid, image: image)), dot: c.primary, idleDot: c.outlineVariant, plate: c.primary)
                    .aspectRatio(CGFloat(max(asset.width, 1)) / CGFloat(max(asset.height, 1)), contentMode: .fit)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement()
        .accessibilityLabel(StudioText.describe(asset))
        .task(id: asset.id) {
            guard let data = await app.studio.bytes(asset.id) else { return }
            image = await decodeImage(data, maxSide: 1536)
        }
    }
}

private struct LoraPicker: View {
    let available: [String]
    let selected: Set<String>
    let onPick: (String) -> Void
    @State private var query = ""
    @Environment(\.scheme) private var c

    var body: some View {
        let filtered = available.filter { query.isEmpty || $0.localizedCaseInsensitiveContains(query) }
        VStack(alignment: .leading, spacing: 8) {
            SectionLabel("lora library · \(available.count)")
            ConsoleTextField(text: $query, placeholder: "filter", kind: .mono)
            if filtered.isEmpty { MicroLabel(available.isEmpty ? "no loras — add them to your pc backend" : "no match") }
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(filtered, id: \.self) { name in
                        Button { onPick(name) } label: {
                            HStack {
                                Text(name).textStyle(.code).foregroundStyle(c.onSurface).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                                if selected.contains(name) { MicroLabel("added", color: c.primary) }
                            }
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(PressStyle())
                        .disabled(selected.contains(name))
                    }
                }
            }
        }
        .padding(16)
        .background(c.surfaceContainerLow)
    }
}

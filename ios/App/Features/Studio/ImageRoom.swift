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
    /// The settings the last model selection left in the form (to tell the user's edits apart).
    @ObservationIgnored private(set) var appliedDefaults: Recommended?

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
            let model = backend.kind == .onDevice ? request.model.flatMap { app.library.get($0) } ?? onDeviceModels.first : nil
            return (backend.kind == .onDevice ? "this iphone" : backend.name, Route.imageBackend(backend, model: model))
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

    func selectOnDeviceModel(_ m: InstalledModel) { selectModel(m.id, ImageDefaults.recommended(for: m)) }

    /// A checkpoint of the backend (by the name it lists).
    func selectRemoteModel(_ name: String) {
        selectModel(name, app.library.get(name).map(ImageDefaults.recommended(for:)) ?? ImageDefaults.recommended(remoteModel: name))
    }

    /// Selects a model with its recommended settings, except the ones the user changed since the
    /// last model selection.
    private func selectModel(_ id: String, _ rec: Recommended) {
        let kept = ImageDefaults.edited(request, since: appliedDefaults)
        edit { r in
            r.model = id
            ImageDefaults.apply(rec, to: &r, keeping: kept)
        }
        appliedDefaults = ImageDefaults.snapshot(request)
    }

    func refreshCatalog() {
        guard let backend, backend.kind != .onDevice else { return }
        let engine = app.generator.engine(for: backend)
        catalogError = nil
        Task {
            do { catalogs[backend.id] = try await engine.catalog() } catch { catalogError = error.localizedDescription }
        }
    }

    /// The canvas the on-device model is compiled for (nil on a PC backend: any size works).
    var nativeSize: (width: Int, height: Int)? {
        guard backend?.kind == .onDevice, let model = request.model.flatMap({ id in onDeviceModels.first { $0.id == id } }) ?? onDeviceModels.first else { return nil }
        return OnDeviceSizing.native(model)
    }

    func setSize(_ w: Int, _ h: Int) {
        let m = capabilities?.sizeMultiple ?? 8
        if let native = nativeSize {
            // What the fixed-size model really delivers for this shape (cut from its canvas).
            let plan = ImageSizing.fixed(native: native, requested: (w, h))
            edit { $0.width = plan.width; $0.height = plan.height }
            return
        }
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
        // The input's own shape (a fixed-size model cuts it from its canvas instead of squashing it).
        let pixels = (Double(max(asset.width, 1)) * Double(max(asset.height, 1))).squareRoot()
        let scale = min(max(pixels, 512), 1024) / pixels
        setSize(Int((Double(asset.width) * scale).rounded()), Int((Double(asset.height) * scale).rounded()))
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
                // A recipe keeps its mask only as a raster, not as strokes: an inpaint recipe comes back
                // as inpaint with an empty mask, so the form asks for the mask instead of quietly
                // running img2img over the whole picture.
                let masked = recipe.text("mask") != nil || recipe.input("mask") != nil
                mode = recipe.text("outpaint") != nil ? .outpaint : masked ? .inpaint : .img2img
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
    @State private var showMask = false
    @State private var showGrid = false
    @State private var showLoras = false
    @State private var picked: PhotosPickerItem?

    var body: some View {
        if model.backends.isEmpty && model.workflows.isEmpty {
            // One primary action; a PC is the quiet second path (as on Android and in Imagine).
            VStack(spacing: 8) {
                StudioEmpty(StudioRoomWords.shared.imageNoEngine) {
                    nav.leaveStudio()
                    nav.switchTo(.models)
                }
                .frame(maxHeight: .infinity)
                Button("Use a PC instead") { nav.leaveStudio(); nav.reset(.run, [.imageBackends]) }
                    .buttonStyle(.borderless)
                    .padding(.bottom, 24)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color(.systemGroupedBackground).ignoresSafeArea())
        } else {
            StudioRoomLayout(collapsed: Binding(get: { session.collapsed }, set: { session.collapsed = $0 })) {
                canvas
            } controls: {
                controls
            } runBar: {
                runBar
            }
            .fullScreenCover(isPresented: $showMask) {
                if let image = model.initImage { MaskEditor(image: image, model: model) { showMask = false } }
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
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text("Grid · \(grid.title.prefix(30))").font(.headline).frame(maxWidth: .infinity, alignment: .leading)
                            Button("Close") { model.gridRunId = nil }.buttonStyle(.borderless)
                        }
                        GridResultView(run: grid) { a in model.canvasId = a.id; model.gridRunId = nil }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }
            } else {
                ZStack(alignment: .bottomLeading) {
                    RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(.secondarySystemGroupedBackground))
                    if let running {
                        RunningPlate(job: running)
                    } else if let asset {
                        CanvasImage(asset: asset)
                    } else {
                        AutobotMark(size: 18)
                            .opacity(0.5)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .accessibilityElement()
                            .accessibilityLabel("Empty canvas")
                    }
                    if let running {
                        HStack(spacing: 6) {
                            if running.steps == 0 && running.state == .running { ProgressView().controlSize(.mini) }
                            Text(running.state == .queued ? "Queued" : running.steps > 0 ? "Step \(running.step)/\(running.steps)" : (running.status ?? "Loading"))
                        }
                        .font(.caption.weight(.medium))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .glassSurface(in: Capsule())
                        .padding(10)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .frame(minHeight: 170)
                .padding(.horizontal, 16)
                .padding(.top, 8)
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
                            .overlay {
                                if a.id == selected?.id { RoundedRectangle(cornerRadius: 6, style: .continuous).strokeBorder(.tint, lineWidth: 2) }
                            }
                            .onTapGesture { model.canvasId = a.id }
                            .accessibilityAddTraits(a.id == selected?.id ? [.isButton, .isSelected] : .isButton)
                    }
                }
                .padding(.horizontal, 16)
            }
            if let selected {
                HStack(spacing: 6) {
                    Text(StudioText.meta(selected)).font(.caption).foregroundStyle(.secondary).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                    StudioChip("→ Input") { Task { await model.useAsInput(selected) } }
                    StudioChip("↺ Recipe", active: true) { Task { await model.reuse(selected.recipe) } }
                    if app.studio.has(EngineKinds.shared.UPSCALE) {
                        StudioChip("4×") {
                            app.studio.submit(Recipe(engine: EngineKinds.shared.UPSCALE, backendId: nil, model: nil, params: [:], inputs: [AssetRef(assetId: selected.id, role: "image")],
                                                     workflowId: nil, workflowVersion: nil, seed: nil, output: .image), label: "upscale 4×", parent: selected.id)
                        }
                        .accessibilityLabel("Upscale 4×")
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        .padding(.bottom, 6)
    }

    // MARK: Controls

    @ViewBuilder private var controls: some View {
        targetSection
        if let w = model.workflow {
            Section("Prompt") {
                PromptBox(prompt: Binding(get: { (model.workflowValues["prompt"] as? ParamValue.StringValue)?.value ?? model.request.prompt },
                                          set: { model.workflowValues["prompt"] = .text($0) }),
                          negative: Binding(get: { (model.workflowValues["negative"] as? ParamValue.StringValue)?.value ?? model.request.negativePrompt },
                                            set: { model.workflowValues["negative"] = .text($0) }),
                          showNegative: w.param(name: "negative") != nil)
            }
            Section(w.name) {
                WorkflowForm(workflow: w, values: $model.workflowValues) { $0.name == "prompt" || $0.name == "negative" || $0.type == .seed }
            }
        } else {
            Section("Prompt") {
                PromptBox(prompt: Binding(get: { model.request.prompt }, set: { v in model.edit { $0.prompt = v } }),
                          negative: Binding(get: { model.request.negativePrompt }, set: { v in model.edit { $0.negativePrompt = v } }))
            }
            backendControls
        }
        SeedControl(mode: $model.seedMode, seed: $model.seed)
        Section {
            Stepper("Batch · \(model.batch)", value: $model.batch, in: 1 ... 8)
            if app.studio.has(EngineKinds.shared.UPSCALE) {
                Toggle("Upscale pass 4×", isOn: $model.upscalePass)
            }
        }
    }

    private var targetSection: some View {
        Section {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(model.backends) { b in
                        StudioChip(b.kind == .onDevice ? "This iPhone" : b.name, active: model.target == .backend(b.id)) { model.select(.backend(b.id)) }
                    }
                    ForEach(model.workflows, id: \.id) { w in
                        StudioChip("◐ \(w.name)", active: model.target == .workflow(w.id)) { model.select(.workflow(w.id)) }
                    }
                }
            }
            if let runs = model.runsOn { RouteBadge(route: runs.route).accessibilityLabel("Runs on \(runs.label)") }
            if let error = model.catalogError {
                HStack {
                    Text("Can't reach \(model.backend?.name ?? "the backend"): \(error)").font(.footnote).foregroundStyle(.red).lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button("Retry") { model.refreshCatalog() }.buttonStyle(.borderless)
                }
            }
        } header: {
            Text("Runs on")
        }
    }

    @ViewBuilder private var backendControls: some View {
        let r = model.request
        let caps = model.capabilities
        let kind = model.backend?.kind
        Section("Model") {
            if kind == .onDevice {
                if model.onDeviceModels.count > 1 {
                    OptionPicker("Model", selection: r.model ?? model.onDeviceModels.first?.id, options: model.onDeviceModels.map(\.id),
                                 optionLabel: { id in model.onDeviceModels.first { $0.id == id }?.title ?? id }) { id in
                        if let m = model.onDeviceModels.first(where: { $0.id == id }) { model.selectOnDeviceModel(m) }
                    }
                }
            } else if !model.catalog.models.isEmpty {
                OptionPicker("Checkpoint", selection: r.model ?? model.catalog.currentModel, options: model.catalog.models, optionLabel: shortModelName) { v in model.selectRemoteModel(v) }
            }
            Picker("Mode", selection: $model.mode) {
                ForEach(model.modes, id: \.self) { Text($0.rawValue.sentenceCased).tag($0) }
            }
            .pickerStyle(.segmented)
        }
        if model.mode != .txt2img { inputSection }
        let (samplers, value): ([String], String) = switch kind {
        case .onDevice: (LocalDiffusion.samplers, r.sampler)
        case .localSse: (LocalSseEngine.schedulers, LocalSseEngine.schedulerId(r.sampler, r.scheduler))
        default: (model.catalog.samplers.isEmpty ? ["DPM++ 2M", "Euler a", "Euler", "DDIM", "UniPC"] : model.catalog.samplers, r.sampler)
        }
        Section("Sampling") {
            OptionPicker("Sampler", selection: value, options: samplers) { v in model.edit { $0.sampler = v; if kind == .localSse { $0.scheduler = nil } } }
            let schedulers: [String] = kind == .onDevice ? LocalDiffusion.schedulers : kind == .sdApi ? model.catalog.schedulers : []
            if !schedulers.isEmpty { OptionPicker("Schedule", selection: r.scheduler ?? "default", options: schedulers) { v in model.edit { $0.scheduler = v } } }
            LabeledSlider("Steps", value: Binding(get: { Double(model.request.steps) }, set: { v in model.edit { $0.steps = Int(v) } }),
                          in: 1 ... Double(caps?.maxSteps ?? 150), step: 1, format: { "\(Int($0))" })
            LabeledSlider("CFG", value: Binding(get: { model.request.cfgScale }, set: { v in model.edit { $0.cfgScale = (v * 2).rounded() / 2 } }), in: 1 ... 20, format: { String(format: "%.1f", $0) })
        }
        if kind == .onDevice, let native = model.nativeSize {
            let out = ImageSizing.fixed(native: native, requested: (r.width, r.height))
            Section {
                ImagineTagFlow(spacing: 6) {
                    ForEach(AspectRatio.all) { aspect in
                        let s = ImageSizing.fixedSize(native: native, aspect: aspect)
                        StudioChip("\(aspect.label) · \(s.width)×\(s.height)", active: s.width == out.width && s.height == out.height) { model.setSize(s.width, s.height) }
                            .accessibilityLabel("\(aspect.spoken), \(s.width) by \(s.height)")
                    }
                }
                .padding(.vertical, 4)
            } header: {
                Text("Size · \(out.width)×\(out.height)")
            } footer: {
                Text(ImageSizing.compiledNote(native))
            }
        } else {
            Section("Size · \(r.width)×\(r.height)") {
                ImagineTagFlow(spacing: 6) {
                    ForEach(ImageRoomModel.sizes, id: \.0) { label, w, h in
                        StudioChip("\(label) \(w)", active: w == r.width && h == r.height) { model.setSize(w, h) }
                    }
                }
                .padding(.vertical, 4)
                Stepper("Width · \(r.width)", value: Binding(get: { r.width / 64 }, set: { model.setSize($0 * 64, r.height) }), in: 4 ... 32)
                Stepper("Height · \(r.height)", value: Binding(get: { r.height / 64 }, set: { model.setSize(r.width, $0 * 64) }), in: 4 ... 32)
            }
        }
        Section {
            ForEach(r.loras, id: \.name) { lora in
                HStack(alignment: .center) {
                    LabeledSlider(lora.name, value: Binding(get: { lora.weight }, set: { model.setLora(lora.name, $0) }), in: -1 ... 2)
                    Button { model.removeLora(lora.name) } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("Remove \(lora.name)")
                }
            }
            if caps?.runtimeLora == true {
                Button("Add LoRA", systemImage: "plus") { showLoras = true }
            }
        } header: {
            Text("LoRA stack")
        } footer: {
            if caps?.runtimeLora == false { Text("This backend runs a fixed model; merge LoRAs before conversion.") }
        }
        Section {
            Button("X/Y grid", systemImage: "square.grid.3x3") { showGrid = true }
                .accessibilityLabel("Build an X/Y grid")
        }
    }

    private var inputSection: some View {
        Section {
            HStack(spacing: 12) {
                PhotosPicker(selection: $picked, matching: .images, photoLibrary: .shared()) {
                    ZStack {
                        Color(.tertiarySystemFill)
                        if let image = model.initImage {
                            Image(uiImage: image).resizable().scaledToFill()
                            if model.mode == .inpaint { MaskOverlay(mask: model.mask) }
                        } else {
                            Image(systemName: "photo.badge.plus").font(.title2).foregroundStyle(.secondary)
                        }
                    }
                    .frame(width: 72, height: 72)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Pick an input photo")
                Text(model.initAssetId == nil ? "Pick a photo or “→ Input” a result" : model.mode.rawValue.sentenceCased)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if model.initAssetId != nil {
                    Button { model.clearInput() } label: { Image(systemName: "xmark.circle.fill") }
                        .buttonStyle(.borderless)
                        .foregroundStyle(.secondary)
                        .accessibilityLabel("Remove the input")
                }
            }
            if model.mode != .outpaint {
                LabeledSlider("Denoise", value: Binding(get: { model.request.denoiseStrength }, set: { v in model.edit { $0.denoiseStrength = (v * 100).rounded() / 100 } }), in: 0.05 ... 1)
            }
            if model.mode == .inpaint && model.initImage != nil {
                Button(model.mask.isEmpty ? "Paint the mask" : "Edit mask · \(counted(model.mask.strokes.count, "stroke"))", systemImage: "paintbrush.pointed") { showMask = true }
            }
            if model.mode == .outpaint {
                Stepper("Left · \(model.outpaint.left) px", value: Binding(get: { model.outpaint.left / 64 }, set: { model.outpaint.left = $0 * 64 }), in: 0 ... 8)
                Stepper("Right · \(model.outpaint.right) px", value: Binding(get: { model.outpaint.right / 64 }, set: { model.outpaint.right = $0 * 64 }), in: 0 ... 8)
                Stepper("Top · \(model.outpaint.top) px", value: Binding(get: { model.outpaint.top / 64 }, set: { model.outpaint.top = $0 * 64 }), in: 0 ... 8)
                Stepper("Bottom · \(model.outpaint.bottom) px", value: Binding(get: { model.outpaint.bottom / 64 }, set: { model.outpaint.bottom = $0 * 64 }), in: 0 ... 8)
            }
        } header: {
            Text("Input")
        } footer: {
            if model.mode == .outpaint { Text("Extend the canvas: pixels added on each side.") }
        }
    }

    // MARK: Run bar

    private var runBar: some View {
        let blocker = model.blocker
        return StudioRunBar(status: blocker ?? summary) {
            if model.workflow != nil {
                Button("Grid") { showGrid = true }.glassButton()
            }
            Button { model.generate() } label: {
                Text(model.batch > 1 ? "Generate × \(model.batch)" : "Generate").frame(maxWidth: .infinity)
            }
            .glassProminentButton()
            .disabled(blocker != nil)
        }
    }

    private var summary: String {
        if let w = model.workflow { return "\(w.name) · v\(w.version) · seed \(model.seedMode == .random ? "random" : "\(model.seed)")" }
        let r = model.request
        return "\(model.mode.rawValue) · \(counted(r.steps, "step")) · cfg \(String(format: "%.1f", r.cfgScale)) · \(r.width)×\(r.height)"
    }
}

/// A running job on the canvas: the backend's latest preview as it arrives, a progress ring until then.
struct RunningPlate: View {
    let job: StudioJob
    @Environment(AppContainer.self) private var app
    @State private var preview: UIImage?

    var body: some View {
        let data = app.studio.previews[job.id]
        ZStack {
            if let preview, data != nil {
                Image(uiImage: preview).resizable().scaledToFit()
            } else if job.state == .queued {
                ProgressView().controlSize(.large).tint(.secondary)
            } else if job.steps > 0 {
                ProgressView(value: Double(job.progress)).progressViewStyle(.circular).controlSize(.large)
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: data) {
            guard let data, let image = await decodeImage(data, maxSide: 768) else { return }
            preview = image
        }
        .accessibilityElement()
        .accessibilityLabel(job.state == .queued ? "Queued" : "Generating, \(Int(job.progress * 100)) percent")
    }
}

/// A finished image on the canvas, fitted.
struct CanvasImage: View {
    let asset: StudioAsset
    @Environment(AppContainer.self) private var app
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(CGFloat(max(asset.width, 1)) / CGFloat(max(asset.height, 1)), contentMode: .fit)
                    .transition(.opacity)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement()
        .accessibilityLabel(StudioText.describe(asset))
        .task(id: asset.id) {
            guard let data = await app.studio.bytes(asset.id) else { return }
            let decoded = await decodeImage(data, maxSide: 1536)
            withAnimation(.smooth) { image = decoded }
        }
    }
}

private struct LoraPicker: View {
    let available: [String]
    let selected: Set<String>
    let onPick: (String) -> Void
    @State private var query = ""

    var body: some View {
        let filtered = available.filter { query.isEmpty || $0.localizedCaseInsensitiveContains(query) }
        NavigationStack {
            List(filtered, id: \.self) { name in
                Button { onPick(name) } label: {
                    HStack {
                        Text(name).font(.body.monospaced()).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                        if selected.contains(name) { Text("Added").font(.caption).foregroundStyle(.secondary) }
                    }
                }
                .tint(.primary)
                .disabled(selected.contains(name))
            }
            .overlay {
                if filtered.isEmpty {
                    ContentUnavailableView(available.isEmpty ? "No LoRAs" : "No match",
                                           systemImage: "square.stack.3d.up",
                                           description: Text(available.isEmpty ? "Add them to your PC backend." : "Try another filter."))
                }
            }
            .searchable(text: $query, prompt: "Filter")
            .navigationTitle("LoRA library · \(available.count)")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

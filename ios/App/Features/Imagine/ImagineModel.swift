import AutobotShared
import Foundation
import Observation
import UIKit

/// A model choice in the studio's chip row (backend + model).
struct ModelChip: Hashable {
    let backendId: String
    let model: String?
    let label: String
    let onDevice: Bool
    /// Where an on-device model runs: "npu" or "gpu".
    var accelerator: String? = nil
}

struct ResultUi: Identifiable, Equatable {
    var id: String { galleryId }
    let galleryId: String
    let image: UIImage
    let seed: Int64
    let width: Int
    let height: Int
    let durationMs: Int64
}

enum CatalogStatus { case idle, loading, ready, error }

/**
 Imagine screen state (port of `ImagineViewModel.kt`). The request is persisted (debounced) so the
 next session starts from the same settings. The init image only lives in memory.
 */
@MainActor
@Observable
final class ImagineModel {
    static let maxBatch = 8
    static let maxSeed: Int64 = 4_294_967_295
    private nonisolated static let maxInitSide: CGFloat = 2048

    private(set) var request: DiffusionRequest
    private(set) var selectedBackendId: String?
    private(set) var initImage: UIImage?
    private(set) var mask = MaskState()
    /// Show the init image (and mask) instead of the latest result.
    private(set) var preferInit = false
    private(set) var catalogs: [String: EngineCatalog] = [:]
    private(set) var catalogStatus: CatalogStatus = .idle
    private(set) var catalogError: String?
    private(set) var localError: String?
    private(set) var results: [ResultUi] = []
    /// An ahead-of-time model load runs ([warmUp]): what it does, for the status line.
    private(set) var warming: String?

    @ObservationIgnored private let app: AppContainer
    @ObservationIgnored private var initSource: CGImage?
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private var catalogTask: Task<Void, Never>?
    /// Engine capabilities by backend kind (they are constants of the engine type; building an
    /// engine to read them on every render was wasted work).
    @ObservationIgnored private var capabilityCache: [BackendKind: EngineCapabilities] = [:]
    /// The on-device model of the last warm-up and when it was asked for (see [warmUp]).
    @ObservationIgnored private var warmedModel: String?
    @ObservationIgnored private var warmedAt: ContinuousClock.Instant?
    /// The warm-up under way, and whether it reached the engine (before that, a switch cancels it).
    @ObservationIgnored private var warmTask: Task<Void, Never>?
    @ObservationIgnored private var warmTaskID = 0
    @ObservationIgnored private var warmSent = true
    /// The ahead-of-time load whose phases [warming] shows (see [warmPhase]).
    @ObservationIgnored private var warmGeneration = 0
    /// Settings the user changed by hand this session: choosing a model keeps them.
    @ObservationIgnored private(set) var userEdited: Set<ImageSetting> = []

    init(app: AppContainer) {
        self.app = app
        request = app.backends.lastRequest
    }

    // MARK: Derived

    var backends: [DiffusionBackend] { app.backends.backends }

    var backend: DiffusionBackend? {
        if let id = selectedBackendId, let chosen = backends.first(where: { $0.id == id }) { return chosen }
        return app.backends.startingBackend(offline: app.settings.settings.mode == .offline)
    }

    var capabilities: EngineCapabilities? {
        guard let backend else { return nil }
        if let cached = capabilityCache[backend.kind] { return cached }
        let caps = app.generator.engine(for: backend).capabilities
        capabilityCache[backend.kind] = caps
        return caps
    }

    var catalog: EngineCatalog { backend.flatMap { catalogs[$0.id] } ?? EngineCatalog() }

    var chips: [ModelChip] {
        let onDevice = app.library.models.filter { $0.isReady && $0.kind == .image && $0.engine == .coreML }
        return backends.flatMap { b -> [ModelChip] in
            if b.kind == .onDevice {
                return onDevice.map { m in
                    let accel = Route.onDeviceImage(m).accelerator ?? "npu"
                    return ModelChip(backendId: b.id, model: m.id, label: "\(m.title) · \(accel)", onDevice: true, accelerator: accel)
                }
            }
            guard let models = catalogs[b.id]?.models, !models.isEmpty else { return [ModelChip(backendId: b.id, model: nil, label: b.name, onDevice: false)] }
            return models.prefix(6).map { ModelChip(backendId: b.id, model: $0, label: "\(shortModelName($0)) · \(b.name)", onDevice: false) }
        }
    }

    var selectedChip: ModelChip? {
        let mine = chips.filter { $0.backendId == backend?.id }
        return mine.first { $0.model == request.model || (request.model == nil && $0.model == catalog.currentModel) } ?? mine.first
    }

    var running: GenerationState { app.generator.state }
    /// Model loading before the first step, when the backend reports it.
    var phase: String? { app.generator.phase }
    /// Not per step: reads the generator's stored busy flag, not its progress state.
    var isRunning: Bool { app.generator.isBusy }
    var error: String? { localError ?? app.generator.lastError }
    var tokenEstimate: Int { PromptSyntax.estimateTokens(request.prompt) }

    /// `{a|b}` in the prompt: what the run will make (`wildcards · 2 prompts × 2 = 4 images`), or nil.
    var wildcards: String? { PromptRuns.shared.summary(template: request.prompt, batch: Int32(request.batchCount), maxImages: PromptRuns.shared.MAX_IMAGES) }

    /// Built-in styles for the style row, and the negative presets that fit images.
    static let styles: [PromptSnippet] = PromptLibrary.shared.builtInStyles
    static let negativePresets: [PromptSnippet] = PromptLibrary.shared.negativePresets.filter { ["neg-general", "neg-anatomy", "neg-photo"].contains($0.id) }

    var canGenerate: Bool { !isRunning && generateBlocker == nil }

    /// Why Generate is disabled (shown in place of the settings line), or nil when it can run.
    var generateBlocker: String? {
        if backend == nil { return "No image engine" }
        if request.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return "" }
        if request.mode != .txt2img && initImage == nil { return "\(request.mode.label) needs an input image" }
        if request.mode == .inpaint && mask.isEmpty { return "Paint a mask over the part to repaint" }
        return nil
    }

    /// A photo from the picker could not be loaded (e.g. it is still in iCloud).
    func photoLoadFailed() {
        localError = "Couldn't load that photo (it may still be downloading from iCloud)"
    }

    /// The route tag of the current backend.
    var route: Route? {
        guard let backend else { return nil }
        return Route.imageBackend(backend, model: backend.kind == .onDevice ? selectedChip?.model.flatMap { app.library.get($0) } : nil)
    }

    // MARK: Lifecycle

    func onAppear() {
        if let backend { refreshCatalog(backend) }
        sizeForModel()
    }

    /**
     The starting model (picked last session, or the backend's loaded checkpoint) must not run
     below its native size: an SDXL-class model left at an SD 1.5 512² request gets its own size.
     A size the user set by hand this session stays.
     */
    func sizeForModel() {
        guard !userEdited.contains(.size), let chip = selectedChip, let rec = recommended(chip),
              let w = rec.width, let h = rec.height, w * h > request.width * request.height else { return }
        edit { $0.width = w; $0.height = h }
    }

    /// Loads the decoded images of the latest results (kept by the generator across screens).
    func syncResults() async {
        let wanted = app.generator.lastResults
        var out: [ResultUi] = []
        for r in wanted {
            if let existing = results.first(where: { $0.galleryId == r.galleryId }) {
                out.append(existing)
                continue
            }
            guard let bytes = await app.gallery.image(r.galleryId), let image = await decodeImage(bytes, maxSide: 1536) else { continue }
            out.append(ResultUi(galleryId: r.galleryId, image: image, seed: r.seed, width: r.width, height: r.height, durationMs: r.durationMs))
        }
        results = out
    }

    // MARK: Request edits

    private func edit(_ change: (inout DiffusionRequest) -> Void) {
        change(&request)
        let snapshot = request
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled else { return }
            self?.app.backends.saveLastRequest(snapshot)
        }
    }

    func setMode(_ mode: DiffusionMode) {
        edit { $0.mode = mode }
        preferInit = mode != .txt2img
    }

    func setPrompt(_ text: String) {
        edit { $0.prompt = text }
        warmUp()
    }

    /**
     Whether to load the on-device model ahead of Generate: a prompt is being written for an
     on-device model not warmed yet, and no image job runs. Time to first image is mostly the
     pipeline load (and, the first time on a phone, the Neural Engine compile).
     */
    nonisolated static func shouldWarm(onDevice: Bool, model: String?, warmed: String?, busy: Bool, prompt: String) -> Bool {
        guard onDevice, let model, model != warmed, !busy else { return false }
        return !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// A warm-up starts this long after the model is chosen (or the prompt first typed): a quick
    /// switch to another model cancels it before the first one loads for nothing.
    nonisolated static let warmDelay: Duration = .milliseconds(500)
    /// The engine is asked again this long after the last warm-up: a model freed since (memory
    /// warning, the background, another engine) loads again; one still loaded costs nothing.
    nonisolated static let warmRecheck: Duration = .seconds(20)
    /// After a warm-up that could not run (another engine held the memory) or failed.
    nonisolated static let warmRetry: Duration = .seconds(2)

    /// Loads the selected on-device model while the prompt is written (see [shouldWarm]). Skipped
    /// while chat, voice, upscaling or audio hold the memory; the engine memory coordinator makes
    /// room for it like for a generation and takes it back on a memory warning or in the background.
    func warmUp() {
        guard let backend, let model = selectedChip?.model else { return }
        let now = ContinuousClock.now
        let recent = warmTask != nil || warmedAt.map { now - $0 < Self.warmRecheck } == true ? warmedModel : nil
        guard Self.shouldWarm(onDevice: backend.kind == .onDevice, model: model, warmed: recent, busy: isRunning, prompt: request.prompt),
              let engine = app.generator.engine(for: backend) as? OnDeviceEngine else { return }
        // Not once the engine has it: the load runs to its end either way, and the engine skips
        // a warm-up still waiting behind it when a newer one asks for another model.
        if !warmSent { warmTask?.cancel() }
        warmedModel = model
        warmedAt = now
        warmSent = false
        warmTaskID += 1
        let id = warmTaskID
        let r = request
        warmTask = Task { [weak self] in
            try? await Task.sleep(for: Self.warmDelay)
            guard let self, !Task.isCancelled else { return }
            self.warmSent = true
            var ready = false
            if await !EngineMemory.shared.isBusy(any: [.llm, .whisper, .tts, .upscaler, .audio, .diffusion]) {
                let generation = self.beginWarm()
                do {
                    try await engine.prepare(r) { phase in
                        Task { @MainActor [weak self] in self?.warmPhase(phase, generation: generation) }
                    }
                    ready = true
                } catch {}
                self.warmEnded(generation: generation)
            }
            guard id == self.warmTaskID else { return }
            self.warmTask = nil
            // Skipped or failed: tried again soon, not after the full recheck interval.
            self.warmedAt = ready ? ContinuousClock.now : ContinuousClock.now - Self.warmRecheck + Self.warmRetry
        }
    }

    /// Starts a new ahead-of-time load: phases of an older one no longer show.
    func beginWarm() -> Int {
        warmGeneration += 1
        return warmGeneration
    }

    /**
     A phase of load [generation]. Phases hop to the main actor in their own tasks, so one can land
     after its load ended (or after a newer load began): only the current, unfinished load shows,
     else the status line and the indeterminate bar stayed on "Loading…" for good.
     */
    func warmPhase(_ phase: String, generation: Int) {
        guard generation == warmGeneration else { return }
        warming = phase
    }

    func warmEnded(generation: Int) {
        guard generation == warmGeneration else { return }
        warmGeneration += 1
        warming = nil
    }

    /// Wraps the prompt in [style] (`{prompt}` placeholder) and merges its negatives.
    func applyStyle(_ style: PromptSnippet) {
        let styled = PromptLibrary.shared.applyStyle(prompt: request.prompt, negative: request.negativePrompt, style: style)
        edit {
            $0.prompt = styled.first as String? ?? $0.prompt
            $0.negativePrompt = styled.second as String? ?? $0.negativePrompt
        }
    }

    /// Adds a negative preset's terms (ones already there are not repeated).
    func addNegative(_ preset: PromptSnippet) {
        edit { $0.negativePrompt = PromptLibrary.shared.joinNegative(current: $0.negativePrompt, add: preset.text) }
    }
    func setNegative(_ text: String) { edit { $0.negativePrompt = text } }
    func setSteps(_ steps: Int) {
        userEdited.insert(.steps)
        edit { $0.steps = steps }
    }

    func setCfg(_ cfg: Double) {
        userEdited.insert(.cfg)
        edit { $0.cfgScale = (cfg * 2).rounded(.down) / 2 }
    }

    func setSize(_ width: Int, _ height: Int) {
        userEdited.insert(.size)
        resize(width, height)
    }

    private func resize(_ width: Int, _ height: Int) {
        let multiple = capabilities?.sizeMultiple ?? 8
        edit {
            $0.width = PromptSyntax.snap(width, multiple: multiple)
            $0.height = PromptSyntax.snap(height, multiple: multiple)
        }
    }

    func setSeed(_ seed: Int64) { edit { $0.seed = seed } }
    func randomizeSeed() { edit { $0.seed = Int64.random(in: 0 ..< Self.maxSeed) } }
    func setRandomSeed() { edit { $0.seed = -1 } }
    func setSampler(_ sampler: String, scheduler: String?) {
        userEdited.insert(.sampler)
        edit { $0.sampler = sampler; $0.scheduler = scheduler }
    }

    func setScheduler(_ scheduler: String?) {
        userEdited.insert(.sampler)
        edit { $0.scheduler = scheduler }
    }
    func setBatch(_ count: Int) { edit { $0.batchCount = min(max(count, 1), Self.maxBatch) } }
    func setDenoise(_ value: Double) { edit { $0.denoiseStrength = (value * 100).rounded(.down) / 100 } }
    func setMaskBlur(_ px: Int) { edit { $0.maskBlur = px } }
    func setOnlyMasked(_ enabled: Bool) { edit { $0.inpaintOnlyMasked = enabled } }
    func setModel(_ model: String?) { edit { $0.model = model } }

    func addLora(_ name: String) { edit { r in if !r.loras.contains(where: { $0.name == name }) { r.loras.append(LoraRef(name: name, weight: 0.8)) } } }
    func setLoraWeight(_ name: String, _ weight: Double) {
        edit { r in r.loras = r.loras.map { $0.name == name ? LoraRef(name: name, weight: (weight * 20).rounded(.down) / 20) : $0 } }
    }

    func removeLora(_ name: String) { edit { r in r.loras.removeAll { $0.name == name } } }

    /// Picks a backend + model; the model brings its recommended settings ([ImageDefaults]),
    /// except the ones the user changed by hand in this session.
    func selectChip(_ chip: ModelChip) {
        if backend?.id != chip.backendId { selectBackend(chip.backendId) }
        setModel(chip.model)
        defer { warmUp() }
        guard let rec = recommended(chip) else { return }
        let kept = userEdited
        edit { ImageDefaults.apply(rec, to: &$0, keeping: kept) }
    }

    /// The recommended settings of [chip]'s model: an installed model's catalog values, else its
    /// architecture's (by the name a backend lists).
    func recommended(_ chip: ModelChip) -> Recommended? {
        guard let id = chip.model else { return nil }
        if let installed = app.library.get(id) { return ImageDefaults.recommended(for: installed) }
        return chip.onDevice ? nil : ImageDefaults.recommended(remoteModel: id)
    }

    /// Selects an installed on-device model (tapped on the Models screen) with its recommended settings.
    func useModel(_ id: String) {
        guard let chip = chips.first(where: { $0.onDevice && $0.model == id }) else {
            localError = "\(app.library.get(id)?.title ?? "That model") can't run in the studio on this phone"
            return
        }
        selectChip(chip)
    }

    // MARK: Backend

    func selectBackend(_ id: String) {
        selectedBackendId = id
        app.backends.setDefault(id)
        if let backend { refreshCatalog(backend) }
    }

    func refreshCatalog() {
        if let backend { refreshCatalog(backend) }
    }

    private func refreshCatalog(_ backend: DiffusionBackend) {
        catalogTask?.cancel()
        let engine = app.generator.engine(for: backend)
        catalogStatus = .loading
        catalogError = nil
        catalogTask = Task { [weak self] in
            do {
                let catalog = try await engine.catalog()
                guard !Task.isCancelled else { return }
                self?.catalogs[backend.id] = catalog
                self?.catalogStatus = .ready
                self?.sizeForModel()
            } catch {
                guard !Task.isCancelled else { return }
                self?.catalogStatus = .error
                self?.catalogError = error.localizedDescription
            }
        }
    }

    // MARK: Init image and mask

    /// Takes picked image data as the init image (re-encoded: no EXIF/GPS survives).
    func setInitImage(_ data: Data) async {
        guard let image = await Task.detached(operation: { ImageCodec.decode(data, maxSide: Self.maxInitSide) }).value,
              let upright = ImageCodec.upright(image, maxSide: Self.maxInitSide) else {
            localError = "Could not read that image"
            return
        }
        useInit(upright)
    }

    /// Takes a gallery image as the init image (img2img), keeping its aspect ratio.
    func useAsInit(_ galleryId: String) async {
        guard let bytes = await app.gallery.image(galleryId),
              let image = await Task.detached(operation: { ImageCodec.decode(bytes, maxSide: Self.maxInitSide)?.cgImage }).value else { return }
        useInit(image)
    }

    private func useInit(_ image: CGImage) {
        initSource = image
        initImage = UIImage(cgImage: image)
        mask = MaskState()
        preferInit = true
        let longSide = min(max(max(image.width, image.height), 512), 1024)
        let scale = Double(longSide) / Double(max(image.width, image.height))
        resize(Int(Double(image.width) * scale), Int(Double(image.height) * scale))
        if request.mode == .txt2img { setMode(.img2img) }
    }

    func clearInitImage() {
        initSource = nil
        initImage = nil
        mask = MaskState()
        setMode(.txt2img)
    }

    /// Copies every parameter of a gallery item into the form.
    func reuseParams(_ galleryId: String) {
        guard let item = app.gallery.get(galleryId), var params = ImageGenerator.decodeParams(item) else { return }
        params.batchCount = 1
        edit { $0 = params }
    }

    func addStroke(_ stroke: MaskStroke) {
        mask = mask.add(stroke)
        preferInit = true
    }

    func undoStroke() { mask = mask.undo() }
    func redoStroke() { mask = mask.redoLast() }
    func clearMask() { mask = mask.clear() }
    func invertMask() { mask = mask.invert() }

    // MARK: Run

    func generate() {
        guard let backend, canGenerate else { return }
        var trimmed = request
        trimmed.prompt = trimmed.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let r = trimmed
        let encoding = capabilities?.maskEncoding
        nonisolated(unsafe) let source = initSource
        let maskState = mask
        Task {
            let inputs: DiffusionInputs = await Task.detached {
                Self.inputs(r, source, maskState, encoding)
            }.value
            preferInit = false
            results = []
            app.backends.saveLastRequest(r)
            app.generator.start(backend, r, inputs)
        }
    }

    /// PNG init image (cover-cropped to the output size) and mask in the backend's encoding.
    private nonisolated static func inputs(_ r: DiffusionRequest, _ source: CGImage?, _ mask: MaskState, _ encoding: MaskEncoding?) -> DiffusionInputs {
        guard r.mode != .txt2img, let source, let encoding,
              let covered = ImageCodec.cover(source, width: r.width, height: r.height) else { return DiffusionInputs() }
        let png = r.mode == .inpaint ? MaskRasterizer.render(mask, width: r.width, height: r.height, encoding: encoding).flatMap(ImageCodec.png) : nil
        return DiffusionInputs(initImage: ImageCodec.png(covered), mask: png)
    }

    /// Same settings again (a random seed stays random).
    func runAgain() { generate() }

    /// Same settings, new random seed.
    func variations() {
        setRandomSeed()
        generate()
    }

    func cancel() { app.generator.cancel() }

    func dismissError() {
        localError = nil
        app.generator.dismissError()
    }
}

/// `sd_xl_turbo_1.0_fp16.safetensors [abc]` → `sd xl turbo 1.0 fp16`.
func shortModelName(_ name: String) -> String {
    var n = name.components(separatedBy: " [").first ?? name
    n = n.split(separator: "/").last.map(String.init) ?? n
    if let dot = n.lastIndex(of: ".") { n = String(n[..<dot]) }
    return String(n.replacingOccurrences(of: "_", with: " ").prefix(28))
}

import Foundation

/**
 "This phone": Core ML Stable Diffusion on the Neural Engine (split-einsum builds) or the GPU,
 with models from the Models tab. The chat model is unloaded first so both never compete for
 memory (as on Android). Core ML models are compiled for one resolution, LoRAs must be merged
 before conversion, and nothing touches the network.
 */
struct OnDeviceEngine: DiffusionEngine {
    let library: ModelLibrary
    let llm: LocalLlm
    let diffusion: LocalDiffusion
    var backendFor: @MainActor @Sendable (InstalledModel) -> Backend = { $0.backend }

    let capabilities = EngineCapabilities(
        modes: Set(DiffusionMode.allCases),
        runtimeLora: false,
        modelSwitching: true,
        livePreview: true,
        maskEncoding: .opaqueBlackWhite,
        maxSteps: 50,
    )

    func catalog() async throws -> EngineCatalog {
        let models = await MainActor.run { library.models.filter { $0.isReady && $0.kind == .image && $0.engine == .coreML }.map(\.id) }
        return EngineCatalog(models: models, currentModel: models.first, samplers: LocalDiffusion.samplers, schedulers: LocalDiffusion.schedulers)
    }

    func generate(_ request: DiffusionRequest, inputs: DiffusionInputs) -> AsyncThrowingStream<DiffusionEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try await run(request, inputs) { continuation.yield($0) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// The installed model [request] runs (its own, else the first ready one) and the backend it runs on.
    @MainActor
    func target(_ request: DiffusionRequest) -> (dir: URL, backend: Backend)? {
        let ready = library.models.filter { $0.isReady && $0.kind == .image && $0.engine == .coreML }
        guard let model = ready.first(where: { $0.id == request.model }) ?? ready.first else { return nil }
        return (ModelLibrary.dir(of: model.id), backendFor(model))
    }

    /**
     Loads the model [request] would run ahead of Generate (the prompt is being written), so the
     first image starts sampling sooner. Does nothing without a usable model; [onPhase] gets the
     loading phase when a load actually starts.
     */
    func prepare(_ request: DiffusionRequest, onPhase: @escaping @Sendable (String) -> Void = { _ in }) async throws {
        guard Engines.linked, let resolved = await MainActor.run(body: { target(request) }),
              let folder = LocalDiffusion.resourceFolder(in: resolved.dir) else { return }
        let info = try diffusion.inspect(folder)
        try await diffusion.prepare(info, backend: resolved.backend, onPhase: onPhase)
    }

    private func run(_ request: DiffusionRequest, _ inputs: DiffusionInputs, emit: @escaping @Sendable (DiffusionEvent) -> Void) async throws {
        if request.mode != .txt2img && inputs.initImage == nil { throw DiffusionError(message: "\(request.mode.label) needs an input image", code: .unsupported) }
        if request.mode == .inpaint && inputs.mask == nil { throw DiffusionError(message: "Paint a mask first", code: .unsupported) }
        guard Engines.linked else { throw DiffusionError(message: EngineUnavailable().errorDescription ?? "", code: .unsupported) }
        let resolved = await MainActor.run { target(request) }
        guard let resolved else {
            throw DiffusionError(message: "No image model on this phone yet — get one in the Models tab", code: .unsupported)
        }
        guard let folder = LocalDiffusion.resourceFolder(in: resolved.dir) else {
            throw DiffusionError(message: "The model files are incomplete; download it again", code: .backend)
        }
        let info: LocalDiffusionModelInfo
        do { info = try diffusion.inspect(folder) } catch { throw DiffusionError(message: error.localizedDescription, code: .unsupported) }
        if request.mode != .txt2img && !info.supportsImageToImage {
            throw DiffusionError(message: "This model has no image encoder; it only does txt2img", code: .unsupported)
        }
        // The chat model and the pipeline never share memory on a phone: LocalDiffusion unloads it
        // (EngineMemory) before the pipeline loads.
        // The model renders its compiled canvas; another aspect is cut from its middle (and an
        // img2img input placed there), never squashed or silently returned square.
        let plan = ImageSizing.fixed(native: (info.width, info.height), requested: (request.width, request.height))
        var sized = request
        sized.width = plan.nativeWidth
        sized.height = plan.nativeHeight
        let placed = try Self.place(inputs, plan)
        do {
            try await diffusion.generate(info, request: sized, inputs: placed, backend: resolved.backend) { event in
                emit(Self.fit(event, plan))
            }
        } catch let e as LocalEngineError where e.code == "CANCELLED" {
            throw CancellationError()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw DiffusionError(message: error.localizedDescription, code: .backend)
        }
    }

    /// The img2img input and inpaint mask (at the requested size) on the model's canvas.
    static func place(_ inputs: DiffusionInputs, _ plan: ImageSizing.FixedPlan) throws -> DiffusionInputs {
        guard plan.cropped, inputs.initImage != nil || inputs.mask != nil else { return inputs }
        var out = DiffusionInputs()
        if let data = inputs.initImage {
            guard let image = ImageCodec.decode(data)?.cgImage, let placed = ImageSizing.embed(image, in: plan).flatMap(ImageCodec.png) else {
                throw DiffusionError(message: "Could not read the input image", code: .unsupported)
            }
            out.initImage = placed
        }
        if let data = inputs.mask {
            guard let mask = ImageCodec.decode(data)?.cgImage, let placed = ImageSizing.embedMask(mask, in: plan).flatMap(ImageCodec.png) else {
                throw DiffusionError(message: "Could not read the mask", code: .unsupported)
            }
            out.mask = placed
        }
        return out
    }

    /// A finished image cut to the requested aspect (progress and phases pass through).
    static func fit(_ event: DiffusionEvent, _ plan: ImageSizing.FixedPlan) -> DiffusionEvent {
        guard plan.cropped, case .result(let index, let image, let seed, _, _, let durationMs, let info) = event,
              let decoded = ImageCodec.decode(image)?.cgImage, let cut = ImageSizing.crop(decoded, to: plan.crop),
              let png = ImageCodec.png(cut) else { return event }
        return .result(index: index, image: png, seed: seed, width: cut.width, height: cut.height, durationMs: durationMs, info: info)
    }
}

/// The canvas each on-device model is compiled for (read from its files once), for the size pickers.
@MainActor
enum OnDeviceSizing {
    private static var cache: [String: (width: Int, height: Int)] = [:]

    static func native(_ model: InstalledModel) -> (width: Int, height: Int) {
        if let known = cache[model.id] { return known }
        var size: (width: Int, height: Int)?
        if Engines.linked, let folder = LocalDiffusion.resourceFolder(in: ModelLibrary.dir(of: model.id)),
           let info = try? LocalDiffusion().inspect(folder) {
            size = (info.width, info.height)
        }
        let rec = ImageDefaults.recommended(for: model)
        let resolved = size ?? (rec.width ?? 512, rec.height ?? 512)
        if size != nil { cache[model.id] = resolved }
        return resolved
    }
}

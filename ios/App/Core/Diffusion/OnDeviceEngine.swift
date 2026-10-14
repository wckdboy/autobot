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

    private func run(_ request: DiffusionRequest, _ inputs: DiffusionInputs, emit: @escaping @Sendable (DiffusionEvent) -> Void) async throws {
        if request.mode != .txt2img && inputs.initImage == nil { throw DiffusionError(message: "\(request.mode.label) needs an input image", code: .unsupported) }
        if request.mode == .inpaint && inputs.mask == nil { throw DiffusionError(message: "Paint a mask first", code: .unsupported) }
        guard Engines.linked else { throw DiffusionError(message: EngineUnavailable().errorDescription ?? "", code: .unsupported) }
        let resolved: (dir: URL, backend: Backend)? = await MainActor.run {
            let ready = library.models.filter { $0.isReady && $0.kind == .image && $0.engine == .coreML }
            guard let model = ready.first(where: { $0.id == request.model }) ?? ready.first else { return nil }
            return (ModelLibrary.dir(of: model.id), model.backend)
        }
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
        // The chat model and the pipeline never share memory on a phone.
        await llm.unload()
        var sized = request
        sized.width = info.width
        sized.height = info.height
        do {
            try await diffusion.generate(info, request: sized, inputs: inputs, backend: resolved.backend, onEvent: emit)
        } catch let e as LocalEngineError where e.code == "CANCELLED" {
            throw CancellationError()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw DiffusionError(message: error.localizedDescription, code: .backend)
        }
    }
}

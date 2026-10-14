import Foundation
#if canImport(AutobotEngines)
import AutobotEngines
#endif

/// What a Core ML Stable Diffusion folder contains (from `CoreMLDiffusionModel.inspect`).
struct LocalDiffusionModelInfo: Sendable, Equatable {
    let folder: URL
    let sdxl: Bool
    let width: Int
    let height: Int
    let supportsImageToImage: Bool
    /// `npu` for split-einsum builds (Neural Engine), `gpu` for original ones.
    let preferred: Backend
}

/**
 Core ML Stable Diffusion on this device (Apple's ml-stable-diffusion through `AutobotEngines`):
 the Neural Engine for `split_einsum` models, Metal for `original` ones. The iOS counterpart of
 Android's on-device image engines. Nothing is fetched or sent.
 */
final class LocalDiffusion: Sendable {
    /// Samplers / schedulers the Core ML pipeline offers.
    static var samplers: [String] {
        #if canImport(AutobotEngines)
        AutobotEngines.DiffusionEngine.samplers
        #else
        ["DPM++ 2M", "PNDM"]
        #endif
    }

    static var schedulers: [String] {
        #if canImport(AutobotEngines)
        AutobotEngines.DiffusionEngine.schedulers
        #else
        ["Karras", "Leading", "Linspace"]
        #endif
    }

    /// Finds the Core ML resources (the folder holding `VAEDecoder.mlmodelc`) under [root].
    static func resourceFolder(in root: URL) -> URL? {
        let fm = FileManager.default
        if fm.fileExists(atPath: root.appendingPathComponent("VAEDecoder.mlmodelc").path) { return root }
        guard let walker = fm.enumerator(at: root, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else { return nil }
        for case let url as URL in walker {
            if url.lastPathComponent == "VAEDecoder.mlmodelc" { return url.deletingLastPathComponent() }
            if url.pathExtension == "mlmodelc" { walker.skipDescendants() }
        }
        return nil
    }

    func inspect(_ folder: URL) throws -> LocalDiffusionModelInfo {
        #if canImport(AutobotEngines)
        do {
            let m = try CoreMLDiffusionModel.inspect(folder)
            return LocalDiffusionModelInfo(
                folder: m.folder, sdxl: m.arch == .sdxl, width: m.width, height: m.height,
                supportsImageToImage: m.supportsImageToImage, preferred: m.preferredBackend == .gpu ? .gpu : .npu,
            )
        } catch {
            throw Engines.map(error)
        }
        #else
        throw EngineUnavailable()
        #endif
    }

    /// Generates the batch; [onEvent] receives progress and each image as it finishes.
    func generate(
        _ model: LocalDiffusionModelInfo,
        request: DiffusionRequest,
        inputs: DiffusionInputs,
        backend: Backend,
        onEvent: @escaping @Sendable (DiffusionEvent) -> Void,
    ) async throws {
        #if canImport(AutobotEngines)
        let engineModel = try CoreMLDiffusionModel.inspect(model.folder)
        let r = AutobotEngines.DiffusionRequest(
            mode: AutobotEngines.DiffusionMode(rawValue: request.mode.rawValue.lowercased()) ?? .txt2img,
            prompt: PromptSyntax.stripLoraTags(request.prompt),
            negativePrompt: request.negativePrompt,
            width: request.width,
            height: request.height,
            steps: request.steps,
            cfgScale: Float(request.cfgScale),
            seed: request.seed,
            sampler: request.sampler,
            scheduler: request.scheduler,
            batchCount: max(1, request.batchCount),
            denoiseStrength: Float(request.denoiseStrength),
            maskBlur: request.maskBlur,
            model: request.model,
        )
        let engine = AutobotEngines.DiffusionEngine.shared
        do {
            _ = try await withTaskCancellationHandler {
                try await engine.generate(
                    model: engineModel,
                    request: r,
                    inputs: AutobotEngines.DiffusionInputs(initImage: inputs.initImage, mask: inputs.mask),
                    backend: ComputeBackend(rawValue: backend.rawValue) ?? .auto,
                    previewEvery: 4,
                ) { event in
                    switch event {
                    case .progress(let image, let step, let total, let preview):
                        onEvent(.progress(image: image, step: step, totalSteps: total, preview: preview))
                    case .result(let img):
                        onEvent(.result(index: img.index, image: img.image, seed: img.seed, width: img.width, height: img.height, durationMs: img.durationMs, info: img.info))
                    }
                }
            } onCancel: {
                engine.cancel()
            }
        } catch {
            throw Engines.map(error)
        }
        #else
        throw EngineUnavailable()
        #endif
    }

    func unload() async {
        #if canImport(AutobotEngines)
        await AutobotEngines.DiffusionEngine.shared.unload()
        #endif
    }
}

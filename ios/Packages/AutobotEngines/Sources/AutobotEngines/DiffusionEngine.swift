import CoreGraphics
import CoreImage
import CoreML
import Foundation
import ImageIO
@preconcurrency import StableDiffusion
import Synchronization
import UniformTypeIdentifiers

public enum DiffusionMode: String, Sendable, Codable, CaseIterable {
    case txt2img, img2img, inpaint
}

/// Generation parameters, shaped like Android's `DiffusionRequest`. Pixel inputs travel
/// separately in [DiffusionInputs].
public struct DiffusionRequest: Sendable, Codable, Hashable {
    public var mode: DiffusionMode = .txt2img
    public var prompt: String = ""
    public var negativePrompt: String = ""
    /// Core ML models are compiled for one resolution; these are ignored when they differ.
    public var width: Int = 512
    public var height: Int = 512
    public var steps: Int = 20
    public var cfgScale: Float = 7
    /// `-1` for random.
    public var seed: Int64 = -1
    public var sampler: String = "DPM++ 2M"
    public var scheduler: String? = "Karras"
    public var batchCount: Int = 1
    public var denoiseStrength: Float = 0.6
    /// Feather radius (px) of the inpaint mask edge.
    public var maskBlur: Int = 4
    public var model: String?

    public init(
        mode: DiffusionMode = .txt2img, prompt: String = "", negativePrompt: String = "", width: Int = 512, height: Int = 512,
        steps: Int = 20, cfgScale: Float = 7, seed: Int64 = -1, sampler: String = "DPM++ 2M", scheduler: String? = "Karras",
        batchCount: Int = 1, denoiseStrength: Float = 0.6, maskBlur: Int = 4, model: String? = nil,
    ) {
        self.mode = mode
        self.prompt = prompt
        self.negativePrompt = negativePrompt
        self.width = width
        self.height = height
        self.steps = steps
        self.cfgScale = cfgScale
        self.seed = seed
        self.sampler = sampler
        self.scheduler = scheduler
        self.batchCount = batchCount
        self.denoiseStrength = denoiseStrength
        self.maskBlur = maskBlur
        self.model = model
    }
}

/// Encoded images (PNG/JPEG) for img2img and inpaint. Mask: white = repaint, black = keep.
public struct DiffusionInputs: Sendable {
    public var initImage: Data?
    public var mask: Data?

    public init(initImage: Data? = nil, mask: Data? = nil) {
        self.initImage = initImage
        self.mask = mask
    }
}

public enum DiffusionEvent: Sendable {
    /// The pipeline is being loaded. [firstRun]: Core ML is compiling this model for these compute
    /// units for the first time on this phone, which can take minutes (later loads are seconds).
    case loading(firstRun: Bool)
    /// Sampling progress of image [image]; [preview] is a JPEG when previews are on.
    case progress(image: Int, step: Int, totalSteps: Int, preview: Data?)
    /// One finished image.
    case result(DiffusionImage)
}

public struct DiffusionImage: Sendable, Hashable {
    public let index: Int
    /// PNG bytes.
    public let image: Data
    public let seed: Int64
    public let width: Int
    public let height: Int
    public let durationMs: Int64
    public let info: String?
}

/// A Core ML Stable Diffusion model folder (compiled `.mlmodelc` resources, as produced by
/// `python_coreml_stable_diffusion.torch2coreml --bundle-resources-for-swift-cli`).
public struct CoreMLDiffusionModel: Sendable, Hashable {
    public enum Arch: String, Sendable { case sd, sdxl }

    /// `split_einsum` runs well on the Neural Engine, `original` on the GPU.
    public enum Attention: String, Sendable { case splitEinsum = "split_einsum", original }

    public let folder: URL
    public let arch: Arch
    public let width: Int
    public let height: Int
    /// A VAE encoder is bundled (needed for img2img and inpaint).
    public let supportsImageToImage: Bool
    public let attention: Attention?

    /// Where [ComputeBackend.auto] runs this model.
    public var preferredBackend: ComputeBackend {
        attention == .original ? .gpu : .npu
    }

    public static func inspect(_ folder: URL) throws -> CoreMLDiffusionModel {
        func has(_ name: String) -> Bool { FileManager.default.fileExists(atPath: folder.appending(path: name).path) }
        guard has("VAEDecoder.mlmodelc"), has("vocab.json"), has("merges.txt"),
              has("Unet.mlmodelc") || (has("UnetChunk1.mlmodelc") && has("UnetChunk2.mlmodelc"))
        else {
            if has("MultiModalDiffusionTransformer.mlmodelc") {
                throw EngineError("UNSUPPORTED", "Stable Diffusion 3 models are not supported")
            }
            throw EngineError("UNSUPPORTED", "\(folder.lastPathComponent) is not a Core ML Stable Diffusion model")
        }
        let arch: Arch = has("TextEncoder2.mlmodelc") ? .sdxl : .sd
        let native = arch == .sdxl ? 1024 : 512
        let size = (try? Data(contentsOf: folder.appending(path: "VAEDecoder.mlmodelc/metadata.json")))
            .flatMap(Self.latentSize(metadata:))
        let name = folder.path.lowercased()
        let attention: Attention? = name.contains("split_einsum") || name.contains("split-einsum") ? .splitEinsum
            : name.contains("original") ? .original : nil
        return CoreMLDiffusionModel(
            folder: folder,
            arch: arch,
            width: size.map { $0.width * 8 } ?? native,
            height: size.map { $0.height * 8 } ?? native,
            supportsImageToImage: has("VAEEncoder.mlmodelc"),
            attention: attention,
        )
    }

    /// Latent size from a compiled VAE decoder's `metadata.json` (input `z`, shape `[1, 4, H, W]`).
    static func latentSize(metadata: Data) -> (width: Int, height: Int)? {
        guard let root = try? JSONSerialization.jsonObject(with: metadata) as? [[String: Any]],
              let inputs = root.first?["inputSchema"] as? [[String: Any]]
        else { return nil }
        let z = inputs.first { $0["name"] as? String == "z" } ?? inputs.first
        guard let shape = z?["shape"] as? String else { return nil }
        let dims = shape.split { !"0123456789".contains($0) }.compactMap { Int($0) }
        guard dims.count == 4, dims[2] > 0, dims[3] > 0 else { return nil }
        return (dims[3], dims[2])
    }
}

/**
 * On-device image generation with Core ML (Apple's ml-stable-diffusion): the Neural Engine for
 * `split_einsum` models, Metal for `original` ones. The iOS counterpart of Android's QNN NPU
 * path. Supports SD 1.5/2.x and SDXL, txt2img, img2img and inpaint (masked img2img: the result
 * is blended back into the init image through the feathered mask). Nothing is fetched or sent.
 * Callers should unload the chat model first so both never compete for RAM, as Android does.
 */
public actor DiffusionEngine {
    public static let shared = DiffusionEngine()

    /// Samplers the Core ML pipeline offers; other names map to the closest one (see [schedulerFor]).
    public static let samplers = ["DPM++ 2M", "PNDM"]
    public static let schedulers = ["Karras", "Leading", "Linspace"]

    private let worker = NativeWorker("autobot.diffusion")
    private let state = PipelineState()

    public init() {}

    /**
     * Generates `request.batchCount` images (seeds seed, seed+1, …). [backend] picks the Core ML
     * compute units (`.npu` = CPU + Neural Engine, `.gpu` = CPU + GPU, `.auto` = the model's
     * preferred one). With [previewEvery] > 0 a low-resolution JPEG preview is approximated from
     * the latents every N steps (no VAE pass, so it costs next to nothing).
     * Cancelling the calling task (or [cancel]) stops after the current step; a cancel that arrives
     * while the pipeline loads (the first Neural Engine compile cannot be interrupted) stops the
     * request as soon as loading is done.
     */
    public func generate(
        model: CoreMLDiffusionModel,
        request: DiffusionRequest,
        inputs: DiffusionInputs = DiffusionInputs(),
        backend: ComputeBackend = .auto,
        previewEvery: Int = 0,
        onEvent: @escaping @Sendable (DiffusionEvent) -> Void = { _ in },
    ) async throws -> [DiffusionImage] {
        try Task.checkCancellation()
        let state = state
        let units = Self.computeUnits(model, backend: backend)
        let token = state.begin()
        defer { state.end(token) }
        return try await worker.run({
            try state.generate(model: model, units: units, request: request, inputs: inputs, previewEvery: previewEvery, token: token, onEvent: onEvent)
        }, onCancel: {
            state.cancel(token)
        })
    }

    /**
     * Loads [model]'s pipeline for [backend] ahead of the first image (the user is writing a
     * prompt), so Generate starts sampling instead of loading; on a phone that never ran the model
     * this is where the minutes-long first Neural Engine compile happens. [onEvent] gets
     * `.loading(firstRun:)` when a load starts (nothing when the pipeline is already loaded). A
     * generate issued meanwhile waits for the load on the same thread and reuses it. A prepare
     * still waiting when a newer one is issued does nothing (the user switched models: loading
     * this one would be wasted); a load already under way runs to its end.
     */
    public func prepare(
        model: CoreMLDiffusionModel,
        backend: ComputeBackend = .auto,
        onEvent: @escaping @Sendable (DiffusionEvent) -> Void = { _ in },
    ) async throws {
        try Task.checkCancellation()
        let state = state
        let units = Self.computeUnits(model, backend: backend)
        let id = state.wantPrepare()
        try await worker.run {
            guard state.isLatestPrepare(id) else { return }
            _ = try state.load(model, units: units, onEvent: onEvent)
        }
    }

    /// Stops the running generation and those waiting to run.
    public nonisolated func cancel() {
        state.cancelAll()
    }

    /// Frees the loaded pipeline.
    public func unload() async {
        let state = state
        _ = try? await worker.run { state.unload() }
    }

    /// Core ML compute units for [model] on [backend] (`.auto` = the model's preferred one).
    public static func computeUnits(_ model: CoreMLDiffusionModel, backend: ComputeBackend) -> MLComputeUnits {
        (backend == .auto ? model.preferredBackend : backend).computeUnits
    }

    /**
     Whether the pipeline loads each stage lazily and drops it after use. SDXL always does, and SD
     on phones under 8 GB (6 GB models report about 5.6 GiB). Otherwise the whole pipeline (~1.2 GB
     palettized for SD 1.5 / 2.1) stays resident: reloading the UNet onto the Neural Engine for
     every image costs seconds each time.
     */
    public static func reduceMemory(arch: CoreMLDiffusionModel.Arch, physicalMemory: UInt64) -> Bool {
        arch == .sdxl || physicalMemory < 7 << 30
    }

    /// A1111-style sampler/scheduler names → what the Core ML pipeline offers.
    static func schedulerFor(sampler: String, scheduler: String?) -> (StableDiffusionScheduler, TimeStepSpacing, label: String) {
        let s = sampler.lowercased()
        if s.contains("pndm") || s.contains("plms") { return (.pndmScheduler, .linspace, "PNDM") }
        let n = (scheduler ?? "").lowercased()
        if n.contains("karras") || s.contains("karras") { return (.dpmSolverMultistepScheduler, .karras, "DPM++ 2M Karras") }
        if n.contains("linspace") { return (.dpmSolverMultistepScheduler, .linspace, "DPM++ 2M") }
        return (.dpmSolverMultistepScheduler, .leading, "DPM++ 2M")
    }
}

/// The loaded pipeline, confined to the diffusion thread except for the requests' cancel flags.
private final class PipelineState: @unchecked Sendable {
    private var pipeline: (any StableDiffusionPipelineProtocol)?
    private var key: (URL, MLComputeUnits)?
    /// Requests issued and not finished, by token; true once cancelled.
    private let requests = Mutex<(next: UInt64, cancelled: [UInt64: Bool])>((1, [:]))
    /// The latest prepare issued (see `DiffusionEngine.prepare`).
    private let latestPrepare = Mutex<UInt64>(0)

    func wantPrepare() -> UInt64 {
        latestPrepare.withLock { id in
            id += 1
            return id
        }
    }

    func isLatestPrepare(_ id: UInt64) -> Bool {
        latestPrepare.withLock { $0 == id }
    }

    func begin() -> UInt64 {
        requests.withLock { r in
            let token = r.next
            r.next += 1
            r.cancelled[token] = false
            return token
        }
    }

    func end(_ token: UInt64) {
        _ = requests.withLock { $0.cancelled.removeValue(forKey: token) }
    }

    func cancel(_ token: UInt64) {
        requests.withLock { r in
            if r.cancelled[token] != nil { r.cancelled[token] = true }
        }
    }

    func cancelAll() {
        requests.withLock { r in
            for key in r.cancelled.keys { r.cancelled[key] = true }
        }
    }

    private func isCancelled(_ token: UInt64) -> Bool {
        requests.withLock { $0.cancelled[token] ?? true }
    }

    func unload() {
        pipeline?.unloadResources()
        pipeline = nil
        key = nil
    }

    fileprivate func load(
        _ model: CoreMLDiffusionModel, units: MLComputeUnits, onEvent: @Sendable (DiffusionEvent) -> Void,
    ) throws -> any StableDiffusionPipelineProtocol {
        if let pipeline, let key, key.0 == model.folder, key.1 == units { return pipeline }
        unload()
        // Core ML caches the device-specific compilation; a marker per compute-unit set tells
        // the UI whether this load is the slow first one.
        let marker = model.folder.appending(path: ".autobot-loaded-\(units.rawValue)")
        onEvent(.loading(firstRun: !FileManager.default.fileExists(atPath: marker.path)))
        let config = MLModelConfiguration()
        config.computeUnits = units
        let reduceMemory = DiffusionEngine.reduceMemory(arch: model.arch, physicalMemory: ProcessInfo.processInfo.physicalMemory)
        let loaded: any StableDiffusionPipelineProtocol
        switch model.arch {
        case .sd:
            loaded = try StableDiffusionPipeline(
                resourcesAt: model.folder, controlNet: [], configuration: config, disableSafety: true, reduceMemory: reduceMemory)
        case .sdxl:
            loaded = try StableDiffusionXLPipeline(resourcesAt: model.folder, configuration: config, reduceMemory: reduceMemory)
        }
        try loaded.loadResources()
        FileManager.default.createFile(atPath: marker.path, contents: nil)
        pipeline = loaded
        key = (model.folder, units)
        return loaded
    }

    func generate(
        model: CoreMLDiffusionModel,
        units: MLComputeUnits,
        request: DiffusionRequest,
        inputs: DiffusionInputs,
        previewEvery: Int,
        token: UInt64,
        onEvent: @Sendable (DiffusionEvent) -> Void,
    ) throws -> [DiffusionImage] {
        if isCancelled(token) { throw EngineError.cancelled }
        let pipeline = try load(model, units: units, onEvent: onEvent)
        // Loading (the first compile for the Neural Engine takes minutes) cannot be interrupted.
        if isCancelled(token) { throw EngineError.cancelled }
        let w = model.width, h = model.height

        var initImage: CGImage?
        var mask: CIImage?
        if request.mode != .txt2img, let data = inputs.initImage {
            guard model.supportsImageToImage else {
                throw EngineError("UNSUPPORTED", "this model has no VAE encoder, so it cannot do img2img")
            }
            guard let decoded = Images.decode(data) else { throw EngineError("UNSUPPORTED", "unreadable init image") }
            initImage = Images.scaled(decoded, w, h)
            if request.mode == .inpaint, let maskData = inputs.mask {
                guard let decodedMask = Images.decode(maskData) else { throw EngineError("UNSUPPORTED", "unreadable mask") }
                mask = Images.featheredMask(decodedMask, w, h, blur: request.maskBlur)
            }
        }

        let (schedulerType, spacing, samplerLabel) = DiffusionEngine.schedulerFor(sampler: request.sampler, scheduler: request.scheduler)
        let baseSeed = request.seed < 0 ? UInt32.random(in: 0..<UInt32(Int32.max)) : UInt32(truncatingIfNeeded: request.seed)
        let units = units == .cpuAndNeuralEngine ? "npu" : units == .cpuAndGPU ? "gpu" : units == .cpuOnly ? "cpu" : "auto"
        let info = [model.folder.lastPathComponent, units, samplerLabel].joined(separator: " · ")
        let scale: Float32 = model.arch == .sdxl ? 0.13025 : 0.18215

        var out: [DiffusionImage] = []
        for index in 0..<max(1, min(request.batchCount, 8)) {
            let started = DispatchTime.now()
            var config = PipelineConfiguration(prompt: request.prompt)
            config.negativePrompt = request.negativePrompt
            config.stepCount = max(1, request.steps)
            config.guidanceScale = request.cfgScale
            config.seed = baseSeed &+ UInt32(index)
            config.imageCount = 1
            config.disableSafety = true
            config.schedulerType = schedulerType
            config.schedulerTimestepSpacing = spacing
            config.rngType = .torchRNG
            config.encoderScaleFactor = scale
            config.decoderScaleFactor = scale
            // Previews show the denoised estimate, which reads as an image long before the end.
            config.useDenoisedIntermediates = previewEvery > 0
            if model.arch == .sdxl {
                config.originalSize = Float32(max(w, h))
                config.targetSize = Float32(max(w, h))
            }
            if let initImage {
                config.startingImage = initImage
                config.strength = min(max(request.denoiseStrength, 0.01), 0.99)
            }

            let images = try pipeline.generateImages(configuration: config) { progress in
                var preview: Data?
                if previewEvery > 0, progress.step % previewEvery == 0, let latent = progress.currentLatentSamples.first,
                   let image = LatentPreview.image(latent, sdxl: model.arch == .sdxl) {
                    preview = Images.encode(image, type: .jpeg)
                }
                onEvent(.progress(image: index, step: progress.step + 1, totalSteps: progress.stepCount, preview: preview))
                return !isCancelled(token)
            }
            if isCancelled(token) { throw EngineError.cancelled }
            guard var image = images.first ?? nil else { throw EngineError("BACKEND", "the model produced no image") }
            if let initImage, let mask, let blended = Images.blend(image, over: initImage, mask: mask) {
                image = blended
            }
            guard let png = Images.encode(image, type: .png) else { throw EngineError("BACKEND", "could not encode the image") }
            let result = DiffusionImage(
                index: index,
                image: png,
                seed: Int64(config.seed),
                width: image.width,
                height: image.height,
                durationMs: Int64((DispatchTime.now().uptimeNanoseconds - started.uptimeNanoseconds) / 1_000_000),
                info: info,
            )
            onEvent(.result(result))
            out.append(result)
        }
        return out
    }
}

/**
 * A preview straight from the latents: a fixed linear map from the 4 latent channels to RGB
 * (the "latent2rgb" approximation), at latent resolution (1/8 of the output). Decoding with the
 * VAE instead would load a second model onto the Neural Engine on every preview.
 */
enum LatentPreview {
    private static let sd: [[Float]] = [[0.3512, 0.2297, 0.3227], [0.3250, 0.4974, 0.2350], [-0.2829, 0.1762, 0.2721], [-0.2120, -0.2616, -0.7177]]
    private static let sdxl: [[Float]] = [[0.3651, 0.4232, 0.4341], [-0.2533, -0.0042, 0.1068], [0.1076, 0.1111, -0.0362], [-0.3165, -0.2492, -0.2188]]
    private static let sdxlBias: [Float] = [0.1084, -0.0175, -0.0011]

    /// [latent] has shape `[1, 4, h, w]` (or `[4, h, w]`).
    static func image(_ latent: MLShapedArray<Float32>, sdxl isXL: Bool) -> CGImage? {
        let shape = latent.shape
        guard shape.count >= 3, shape[shape.count - 3] == 4 else { return nil }
        let h = shape[shape.count - 2], w = shape[shape.count - 1], plane = w * h
        let scalars = latent.scalars
        guard scalars.count >= 4 * plane else { return nil }
        let factors = isXL ? sdxl : sd
        let bias = isXL ? sdxlBias : [0, 0, 0]
        var rgba = [UInt8](repeating: 255, count: plane * 4)
        for i in 0..<plane {
            for c in 0..<3 {
                var v = bias[c]
                for k in 0..<4 { v += scalars[k * plane + i] * factors[k][c] }
                rgba[i * 4 + c] = UInt8(max(0, min(255, (v + 1) * 127.5)))
            }
        }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData) else { return nil }
        return CGImage(
            width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue), provider: provider, decode: nil,
            shouldInterpolate: true, intent: .defaultIntent,
        )
    }
}

enum Images {
    static let context = CIContext(options: [.cacheIntermediates: false])

    static func decode(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }

    static func scaled(_ image: CGImage, _ w: Int, _ h: Int) -> CGImage? {
        if image.width == w, image.height == h { return image }
        guard let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        else { return nil }
        ctx.interpolationQuality = .high
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return ctx.makeImage()
    }

    /// White (painted) = repaint, black = keep; the edge is feathered by [blur] px.
    static func featheredMask(_ mask: CGImage, _ w: Int, _ h: Int, blur: Int) -> CIImage? {
        guard let scaled = scaled(mask, w, h) else { return nil }
        let extent = CGRect(x: 0, y: 0, width: w, height: h)
        var image = CIImage(cgImage: scaled).applyingFilter("CIColorControls", parameters: [kCIInputSaturationKey: 0])
        if blur > 0 {
            image = image.clampedToExtent().applyingGaussianBlur(sigma: Double(blur) / 2).cropped(to: extent)
        }
        return image
    }

    static func blend(_ generated: CGImage, over original: CGImage, mask: CIImage) -> CGImage? {
        let top = CIImage(cgImage: generated)
        let blended = top.applyingFilter("CIBlendWithMask", parameters: [
            kCIInputBackgroundImageKey: CIImage(cgImage: original),
            kCIInputMaskImageKey: mask,
        ])
        return context.createCGImage(blended, from: top.extent)
    }

    static func encode(_ image: CGImage, type: UTType) -> Data? {
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, type.identifier as CFString, 1, nil) else { return nil }
        let options: [CFString: Any] = type == .jpeg ? [kCGImageDestinationLossyCompressionQuality: 0.7] : [:]
        CGImageDestinationAddImage(dest, image, options as CFDictionary)
        return CGImageDestinationFinalize(dest) ? data as Data : nil
    }
}

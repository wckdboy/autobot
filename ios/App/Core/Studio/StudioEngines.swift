import AutobotShared
import CoreGraphics
import Foundation
import UIKit
#if canImport(AutobotEngines)
import AutobotEngines
#endif

// The Studio's engines on iOS: Swift `HostStudioEngine`s the shared queue runs (on-device Core ML
// diffusion, the A1111 / Local SSE backends, the 4× upscaler, Stable Audio Open Small and the
// Kokoro voiceover). ComfyUI is the shared `ComfyEngine` over `URLSessionTransport`.

/// A failure an engine explains to the user.
struct StudioFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

// MARK: - Recipe values

extension Recipe {
    func double(_ name: String) -> Double? { value(name: name)?.asDouble()?.doubleValue }
    func int(_ name: String) -> Int? { value(name: name)?.asLong().map { Int($0.int64Value) } }
    func text(_ name: String) -> String? { string(name: name) }
    func flag(_ name: String) -> Bool? { (params[name] as? ParamValue.BoolValue)?.value }
    var seedValue: Int64? { seed?.int64Value }
    func input(_ role: String) -> String? { inputs.first { $0.role == role }?.assetId }
}

extension ParamValue {
    static func int(_ v: Int) -> ParamValue { ParamValue.IntValue(value: Int64(v)) }
    static func int64(_ v: Int64) -> ParamValue { ParamValue.IntValue(value: v) }
    static func double(_ v: Double) -> ParamValue { ParamValue.FloatValue(value: v) }
    static func text(_ v: String) -> ParamValue { ParamValue.StringValue(value: v) }
    static func flag(_ v: Bool) -> ParamValue { ParamValue.BoolValue(value: v) }
}

/**
 Studio recipe ↔ the app's image request, in the same vocabulary as Android's `StudioRecipes` (so
 "reuse recipe" works on an export from either): `prompt`, `negative`, `steps`, `cfg`, `sampler`,
 `scheduler`, `width`, `height`, `strength`, `mask_blur`, `clip_skip`, one `lora.<name>` per LoRA
 with its weight (a grid can vary it). Inputs: role `image` (img2img) and role `mask` (a mask
 asset, Android's inpaint). iOS also writes the inpaint mask inline (`mask`, an alpha PNG in
 base64) and the outpaint margins (`outpaint`, `left,top,right,bottom`).
 */
enum StudioRecipes {
    static let loraPrefix = "lora."
    /// Helper assets (Android's masks and extended canvases) the library hides.
    static let hiddenEngines: Set<String> = ["mask"]

    /// A recipe's LoRA stack: `name:0.80,other:0.50`.
    static func encodeLoras(_ loras: [LoraRef]) -> String {
        loras.map { "\($0.name):\(String(format: "%.2f", $0.weight))" }.joined(separator: ",")
    }

    static func decodeLoras(_ text: String?) -> [LoraRef] {
        (text ?? "").split(separator: ",").compactMap { part in
            let pieces = part.split(separator: ":")
            guard let name = pieces.dropLast(pieces.count > 1 ? 1 : 0).joined(separator: ":").nonEmpty else { return nil }
            return LoraRef(name: name, weight: pieces.count > 1 ? Double(pieces.last!) ?? 1 : 1)
        }
    }

    /// Outpaint margins `left,top,right,bottom` (pixels of the output).
    struct Margins: Equatable, Sendable {
        var left = 0, top = 0, right = 0, bottom = 0
        var isEmpty: Bool { left + top + right + bottom == 0 }
        var text: String { "\(left),\(top),\(right),\(bottom)" }

        init(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
            self.left = left
            self.top = top
            self.right = right
            self.bottom = bottom
        }

        init?(_ text: String?) {
            let n = (text ?? "").split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
            guard n.count == 4 else { return nil }
            self.init(left: max(0, n[0]), top: max(0, n[1]), right: max(0, n[2]), bottom: max(0, n[3]))
        }
    }

    /// The image request a diffusion recipe describes (seed -1 = random).
    static func request(_ r: Recipe) -> DiffusionRequest {
        var q = DiffusionRequest()
        q.prompt = r.text(Recipe.companion.PROMPT) ?? ""
        q.negativePrompt = r.text(Recipe.companion.NEGATIVE) ?? ""
        q.steps = r.int("steps") ?? q.steps
        q.cfgScale = r.double("cfg") ?? q.cfgScale
        q.sampler = r.text("sampler") ?? q.sampler
        if let scheduler = r.text("scheduler") { q.scheduler = scheduler.isEmpty ? nil : scheduler }
        q.width = r.int("width") ?? q.width
        q.height = r.int("height") ?? q.height
        q.denoiseStrength = r.double("strength") ?? q.denoiseStrength
        q.maskBlur = r.int("mask_blur") ?? q.maskBlur
        q.clipSkip = r.int("clip_skip")
        let loras = r.params.filter { $0.key.hasPrefix(loraPrefix) }.sorted { $0.key < $1.key }
            .map { LoraRef(name: String($0.key.dropFirst(loraPrefix.count)), weight: $0.value.asDouble()?.doubleValue ?? 1) }
        q.loras = loras.isEmpty ? decodeLoras(r.text("loras")) : loras
        q.model = r.model
        q.seed = r.seedValue ?? -1
        q.batchCount = 1
        if r.input("image") != nil {
            q.mode = r.text("mask") != nil || r.input("mask") != nil || Margins(r.text("outpaint")).map { !$0.isEmpty } == true ? .inpaint : .img2img
            if Margins(r.text("outpaint")).map({ !$0.isEmpty }) == true { q.denoiseStrength = r.double("strength") ?? 1 }
        }
        return q
    }

    /// A diffusion recipe from the image room's request (inputs, mask and outpaint added by the caller).
    static func recipe(_ q: DiffusionRequest, backendId: String?) -> Recipe {
        var params: [String: ParamValue] = [
            Recipe.companion.PROMPT: .text(q.prompt),
            "steps": .int(q.steps),
            "cfg": .double(q.cfgScale),
            "sampler": .text(q.sampler),
            "width": .int(q.width),
            "height": .int(q.height),
        ]
        if !q.negativePrompt.isEmpty { params[Recipe.companion.NEGATIVE] = .text(q.negativePrompt) }
        if let s = q.scheduler { params["scheduler"] = .text(s) }
        for l in q.loras { params[loraPrefix + l.name] = .double(l.weight) }
        if let clip = q.clipSkip { params["clip_skip"] = .int(clip) }
        return Recipe(engine: EngineKinds.shared.DIFFUSION, backendId: backendId, model: q.model, params: params, inputs: [], workflowId: nil,
                      workflowVersion: nil, seed: q.seed >= 0 ? KotlinLong(value: q.seed) : nil, output: .image)
    }
}

private extension String {
    var nonEmpty: String? { isEmpty ? nil : self }
}

// MARK: - Base

/**
 A Swift engine for the shared queue: [perform] runs in its own task (cancelled with the job),
 reports through [Events] and returns the outputs. Errors become the job's message.
 */
class StudioHostEngine: NSObject, HostStudioEngine, @unchecked Sendable {
    let capabilities: AutobotShared.EngineCapabilities

    init(capabilities: AutobotShared.EngineCapabilities) {
        self.capabilities = capabilities
    }

    /// What a running job reports.
    struct Events: @unchecked Sendable {
        let sink: HostEngineSink
        func progress(_ value: Int, _ max: Int) { sink.onProgress(value: Int32(value), max: Int32(max)) }
        func fraction(_ f: Double) { progress(Int((min(max(f, 0), 1) * 1000).rounded()), 1000) }
        func status(_ text: String) { sink.onStatus(text: text) }
        func preview(_ data: Data) { sink.onPreview(mimeType: ImageCodec.isPNG(data) ? "image/png" : "image/jpeg", bytes: BackupBytes.shared.fromData(data: data)) }
    }

    func run(jobJson: String, sink: HostEngineSink) -> any AutobotShared.Cancellable {
        let events = Events(sink: sink)
        nonisolated(unsafe) let sink = sink
        let task = Task.detached { [self] in
            do {
                let job = try StudioCodec.shared.decodeJob(json: jobJson)
                let outputs = try await perform(job, events)
                try Task.checkCancellation()
                outputs.forEach { sink.onOutput(output: $0) }
                sink.onComplete()
            } catch is CancellationError {
                // The queue cancelled the job and already marked it.
            } catch {
                if !Task.isCancelled { sink.onFailure(message: error.localizedDescription) }
            }
        }
        return TaskHandle(task)
    }

    func perform(_ job: StudioJob, _ events: Events) async throws -> [EngineOutput] { [] }

    static func output(_ type: AssetType, _ mime: String, _ data: Data, width: Int = 0, height: Int = 0, durationMs: Int64 = 0) -> EngineOutput {
        EngineOutput(type: type, mimeType: mime, bytes: BackupBytes.shared.fromData(data: data), width: Int32(width), height: Int32(height), durationMs: durationMs, name: nil)
    }

    static func capabilities(kind: String, backendId: String?, label: String, outputs: Set<AssetType>, onDevice: Bool, params: Set<String> = [], previews: Bool = false) -> AutobotShared.EngineCapabilities {
        AutobotShared.EngineCapabilities(kind: kind, backendId: backendId, label: label, outputs: outputs, onDevice: onDevice, params: params, maxBatch: 1, previews: previews)
    }
}

/// On-device work waits while Imagine's own job holds the Neural Engine / GPU.
@MainActor
private func waitForImagine(_ generator: ImageGenerator, _ events: StudioHostEngine.Events) async throws {
    var told = false
    while generator.isBusy {
        if !told { events.status("Waiting for Imagine to finish"); told = true }
        try await Task.sleep(for: .milliseconds(400))
    }
}

/// "Stopped in the background" for on-device engines (iOS gives no GPU / Neural Engine time there).
private func onDeviceError(_ error: Error, since epoch: Int) async -> Error {
    if error is CancellationError { return error }
    let interrupted = await MainActor.run { AppActivity.shared.interrupted(since: epoch) }
    return interrupted ? StudioFailure(message: "Stopped: iOS doesn't allow Neural Engine or GPU work while Autobot is in the background. Retry it with the app open.") : error
}

// MARK: - Diffusion

/**
 Text / image to image on one backend: on this phone (Core ML), or an A1111 / Local SSE server.
 Inputs: `image` (img2img; with `mask` = inpaint, `outpaint` = extended canvas), the mask as an
 alpha PNG in `mask` (base64), converted to what the backend wants.
 */
final class DiffusionStudioEngine: StudioHostEngine, @unchecked Sendable {
    let backend: DiffusionBackend
    private let make: @MainActor () -> any DiffusionEngine
    private let generator: ImageGenerator
    private let files: StudioFilesHost

    @MainActor
    init(backend: DiffusionBackend, generator: ImageGenerator, files: StudioFilesHost) {
        self.backend = backend
        self.generator = generator
        self.files = files
        make = { [generator] in generator.engine(for: backend) }
        let onDevice = backend.kind == .onDevice
        super.init(capabilities: Self.capabilities(
            kind: EngineKinds.shared.DIFFUSION, backendId: onDevice ? nil : backend.id, label: onDevice ? "This iPhone" : backend.name,
            outputs: [.image], onDevice: onDevice, previews: true,
        ))
    }

    override func perform(_ job: StudioJob, _ events: Events) async throws -> [EngineOutput] {
        let onDevice = backend.kind == .onDevice
        if onDevice { try await waitForImagine(generator, events) }
        let engine = await MainActor.run { make() }
        var request = StudioRecipes.request(job.recipe)
        let inputs = try Self.inputs(job.recipe, &request, files: files, encoding: engine.capabilities.maskEncoding)
        if request.mode != .txt2img && !engine.capabilities.modes.contains(request.mode) {
            throw StudioFailure(message: "\(backend.name) can't do \(request.mode.label)")
        }
        let epoch = await MainActor.run { AppActivity.shared.epoch }
        var outputs: [EngineOutput] = []
        do {
            for try await event in engine.generate(request, inputs: inputs) {
                switch event {
                case .phase(let text): events.status(text)
                case .progress(_, let step, let total, let preview):
                    events.progress(step, total)
                    if let preview { events.preview(preview) }
                case .result(_, let image, _, let width, let height, _, _):
                    outputs.append(Self.output(.image, ImageCodec.isPNG(image) ? "image/png" : "image/jpeg", image, width: width, height: height))
                }
            }
        } catch {
            throw onDevice ? await onDeviceError(error, since: epoch) : error
        }
        if outputs.isEmpty { throw StudioFailure(message: "\(backend.name) finished without an image") }
        return outputs
    }

    /// The PNG init image (cover-cropped to the output size, or placed on an extended canvas) and mask.
    static func inputs(_ recipe: Recipe, _ request: inout DiffusionRequest, files: StudioFilesHost, encoding: MaskEncoding) throws -> DiffusionInputs {
        guard request.mode != .txt2img, let id = recipe.input("image") else { return DiffusionInputs() }
        guard let bytes = files.bytes(id), let image = ImageCodec.decode(bytes, maxSide: 2048)?.cgImage else {
            throw StudioFailure(message: "The input image is gone from the library")
        }
        if let margins = StudioRecipes.Margins(recipe.text("outpaint")), !margins.isEmpty {
            guard let canvas = Outpaint.canvas(image, width: request.width, height: request.height, margins: margins),
                  let mask = Outpaint.mask(width: request.width, height: request.height, margins: margins, encoding: encoding) else {
                throw StudioFailure(message: "Could not extend the canvas")
            }
            return DiffusionInputs(initImage: ImageCodec.png(canvas), mask: ImageCodec.png(mask))
        }
        guard let covered = ImageCodec.cover(image, width: request.width, height: request.height) else { throw StudioFailure(message: "Could not read the input image") }
        var mask: Data?
        if let encoded = recipe.text("mask"), let png = Data(base64Encoded: encoded) {
            mask = encoding == .opaqueBlackWhite ? Outpaint.opaque(png, width: request.width, height: request.height) : png
        } else if let maskId = recipe.input("mask"), let png = files.bytes(maskId) {
            // A mask asset (Android): white = repaint, opaque or with alpha.
            mask = encoding == .opaqueBlackWhite ? Outpaint.opaque(png, width: request.width, height: request.height)
                : Outpaint.alpha(png, width: request.width, height: request.height)
        }
        return DiffusionInputs(initImage: ImageCodec.png(covered), mask: mask)
    }
}

/// Outpainting: the source on a larger canvas, the new border as the mask.
enum Outpaint {
    /// The source in the inner rect; the border filled with a stretched, blurred copy (a tone to paint over).
    static func canvas(_ source: CGImage, width: Int, height: Int, margins m: StudioRecipes.Margins) -> CGImage? {
        guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let inner = CGRect(x: m.left, y: m.bottom, width: max(1, width - m.left - m.right), height: max(1, height - m.top - m.bottom))
        ctx.interpolationQuality = .low
        ctx.setAlpha(0.85)
        ctx.draw(source, in: CGRect(x: 0, y: 0, width: width, height: height))
        ctx.setAlpha(1)
        ctx.interpolationQuality = .high
        let cropped = ImageCodec.cover(source, width: Int(inner.width), height: Int(inner.height)) ?? source
        ctx.draw(cropped, in: inner)
        return ctx.makeImage()
    }

    /// White (or opaque alpha) = repaint: everything outside the source, 8 px into it to blend the seam.
    static func mask(width: Int, height: Int, margins m: StudioRecipes.Margins, encoding: MaskEncoding) -> CGImage? {
        guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        let opaque = encoding == .opaqueBlackWhite
        ctx.setFillColor(opaque ? UIColor.white.cgColor : UIColor.white.cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let seam = 8
        let keep = CGRect(x: m.left + (m.left > 0 ? seam : 0), y: m.bottom + (m.bottom > 0 ? seam : 0),
                          width: width - m.left - m.right - (m.left > 0 ? seam : 0) - (m.right > 0 ? seam : 0),
                          height: height - m.top - m.bottom - (m.top > 0 ? seam : 0) - (m.bottom > 0 ? seam : 0))
        if opaque {
            ctx.setFillColor(UIColor.black.cgColor)
            ctx.fill(keep)
        } else {
            ctx.clear(keep)
        }
        return ctx.makeImage()
    }

    /// A white-on-black (or alpha) mask PNG as alpha-white: coverage from the luminance.
    static func alpha(_ png: Data, width: Int, height: Int) -> Data? {
        guard let opaquePng = opaque(png, width: width, height: height), let source = UIImage(data: opaquePng)?.cgImage,
              let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue), let data = ctx.data else { return nil }
        ctx.draw(source, in: CGRect(x: 0, y: 0, width: width, height: height))
        let p = data.bindMemory(to: UInt8.self, capacity: width * height * 4)
        for i in 0 ..< width * height {
            let v = p[i * 4]
            p[i * 4 + 3] = v
        }
        return ctx.makeImage().flatMap(ImageCodec.png)
    }

    /// An alpha mask PNG as an opaque black / white one (A1111).
    static func opaque(_ png: Data, width: Int, height: Int) -> Data? {
        guard let source = UIImage(data: png)?.cgImage,
              let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.setFillColor(UIColor.black.cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        ctx.draw(source, in: CGRect(x: 0, y: 0, width: width, height: height))
        return ctx.makeImage().flatMap(ImageCodec.png)
    }
}

// MARK: - Upscale

/// 4× on the Neural Engine (Real-ESRGAN, Core ML): input `image`.
final class UpscaleStudioEngine: StudioHostEngine, @unchecked Sendable {
    private let folder: URL
    private let generator: ImageGenerator
    private let files: StudioFilesHost
    private let upscaler = LocalUpscale()

    @MainActor
    init(folder: URL, generator: ImageGenerator, files: StudioFilesHost) {
        self.folder = folder
        self.generator = generator
        self.files = files
        super.init(capabilities: Self.capabilities(kind: EngineKinds.shared.UPSCALE, backendId: nil, label: "This iPhone", outputs: [.image], onDevice: true))
    }

    override func perform(_ job: StudioJob, _ events: Events) async throws -> [EngineOutput] {
        guard let id = job.recipe.input("image"), let bytes = files.bytes(id) else { throw StudioFailure(message: "The input image is gone from the library") }
        try await waitForImagine(generator, events)
        guard let size = UIImage(data: bytes)?.size, LocalUpscale.fits(width: Int(size.width), height: Int(size.height)) else {
            throw StudioFailure(message: "This image is too large to upscale on this phone")
        }
        events.status("Upscaling 4×")
        let epoch = await MainActor.run { AppActivity.shared.epoch }
        do {
            let result = try await withTaskCancellationHandler {
                try await upscaler.upscale(folder: folder, image: bytes) { done, total in events.progress(done, total) }
            } onCancel: { [upscaler] in upscaler.cancel() }
            return [Self.output(.image, "image/png", result.png, width: result.width, height: result.height)]
        } catch {
            throw await onDeviceError(error, since: epoch)
        }
    }
}

// MARK: - Audio

/**
 Text to audio on this phone: Stable Audio Open Small (Stability AI Community License). Params
 `prompt`, `seconds` (0.1–11.89), `steps` (1–50, default 8), `cfg` (default 1), `count` (a batch:
 one clip per seed, walking from the recipe's seed). 44.1 kHz stereo WAV.
 */
final class AudioStudioEngine: StudioHostEngine, @unchecked Sendable {
    static let maxSeconds = 11.89
    static let maxSteps = 50
    static let maxCount = 4

    private let folder: URL
    private let generator: ImageGenerator

    @MainActor
    init(folder: URL, generator: ImageGenerator) {
        self.folder = folder
        self.generator = generator
        super.init(capabilities: Self.capabilities(
            kind: EngineKinds.shared.AUDIO, backendId: nil, label: "This iPhone", outputs: [.audio], onDevice: true,
            params: ["prompt", "seconds", "steps", "cfg", "count"],
        ))
    }

    override func perform(_ job: StudioJob, _ events: Events) async throws -> [EngineOutput] {
        let r = job.recipe
        let prompt = (r.text("prompt") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty else { throw StudioFailure(message: "Describe the sound first") }
        let seconds = min(max(r.double("seconds") ?? 10, 0.1), Self.maxSeconds)
        let steps = min(max(r.int("steps") ?? LocalAudioGen.defaultSteps, 1), Self.maxSteps)
        let cfg = Float(min(max(r.double("cfg") ?? 1, 0), 20))
        let count = min(max(r.int("count") ?? 1, 1), Self.maxCount)
        let base = UInt64(max(r.seedValue ?? Int64.random(in: 0 ..< 4_294_967_295), 0))
        try await waitForImagine(generator, events)
        let epoch = await MainActor.run { AppActivity.shared.epoch }
        var outputs: [EngineOutput] = []
        #if canImport(AutobotEngines)
        do {
            for i in 0 ..< count {
                try Task.checkCancellation()
                if count > 1 { events.status("Clip \(i + 1) of \(count)") }
                let audio = try await EngineMemory.using(.audio) {
                    try await LocalAudioGen.shared.generate(folder: folder, prompt: prompt, seconds: seconds, steps: steps, cfg: cfg, seed: base &+ UInt64(i)) { p in
                        events.fraction((Double(i) + p.fraction) / Double(count))
                        if p.stage == .decoding { events.status("Decoding") }
                    }
                }
                outputs.append(Self.output(.audio, "audio/wav", audio.wav(), durationMs: Int64(seconds * 1000)))
            }
        } catch {
            throw await onDeviceError(error, since: epoch)
        }
        #else
        throw EngineUnavailable()
        #endif
        return outputs
    }
}

// MARK: - Voiceover

/// Kokoro voiceover on this phone: `text`, `voice` (`af_heart`), `speed` (0.5–2). 24 kHz mono WAV.
final class SpeechStudioEngine: StudioHostEngine, @unchecked Sendable {
    private let folder: URL

    @MainActor
    init(folder: URL) {
        self.folder = folder
        super.init(capabilities: Self.capabilities(
            kind: EngineKinds.shared.SPEECH, backendId: nil, label: "This iPhone", outputs: [.audio], onDevice: true, params: ["text", "voice", "speed"],
        ))
    }

    override func perform(_ job: StudioJob, _ events: Events) async throws -> [EngineOutput] {
        let r = job.recipe
        let text = (r.text("text") ?? r.text("prompt") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw StudioFailure(message: "Type the text to read") }
        let voice = r.text("voice").flatMap { $0.isEmpty ? nil : $0 } ?? LocalVoice.defaultVoice
        let speed = Float(min(max(r.double("speed") ?? 1, 0.5), 2))
        let total = max(1, (try? await LocalVoice.shared.pieces(folder: folder, text: text, voice: voice).count) ?? 1)
        var samples: [Float] = []
        var done = 0
        events.progress(0, total)
        for try await block in LocalVoice.shared.synthesize(folder: folder, text: text, voice: voice, speed: speed) {
            samples += block
            done += 1
            events.progress(min(done, total), total)
        }
        try Task.checkCancellation()
        guard !samples.isEmpty else { throw StudioFailure(message: "The voice made no audio") }
        let ms = Int64(Double(samples.count) / Double(LocalVoice.sampleRate) * 1000)
        return [Self.output(.audio, "audio/wav", StudioAudio.wav(mono: samples, sampleRate: LocalVoice.sampleRate), durationMs: ms)]
    }
}

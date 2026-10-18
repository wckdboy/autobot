import CoreGraphics
import Foundation
import Testing
import UIKit
@testable import Autobot

/// An engine that records what reaches it and returns images of the requested size (or of one
/// fixed square, like a server whose model only renders 512²).
final class RecordingEngine: DiffusionEngine, @unchecked Sendable {
    private let lock = NSLock()
    private var _requests: [DiffusionRequest] = []
    private var _inputs: [DiffusionInputs] = []
    let square: Int?

    init(square: Int? = nil) { self.square = square }

    var requests: [DiffusionRequest] { lock.withLock { _requests } }
    var inputs: [DiffusionInputs] { lock.withLock { _inputs } }

    var capabilities: EngineCapabilities {
        EngineCapabilities(modes: Set(DiffusionMode.allCases), runtimeLora: true, modelSwitching: true, livePreview: false, maskEncoding: .opaqueBlackWhite)
    }

    func catalog() async throws -> EngineCatalog { EngineCatalog() }

    func generate(_ request: DiffusionRequest, inputs: DiffusionInputs) -> AsyncThrowingStream<DiffusionEvent, Error> {
        lock.withLock {
            _requests.append(request)
            _inputs.append(inputs)
        }
        let w = square ?? request.width, h = square ?? request.height
        return AsyncThrowingStream { c in
            for i in 0 ..< max(request.batchCount, 1) {
                // Reports the requested size whatever it rendered, like a backend that does not say.
                c.yield(.result(index: i, image: ImageParamsTests.png(w, h), seed: request.seed < 0 ? 7 + Int64(i) : request.seed + Int64(i),
                                width: request.width, height: request.height, durationMs: 1, info: nil))
            }
            c.finish()
        }
    }
}

/// Image parameters end to end: aspect × resolution → request → every backend's wire format, the
/// fixed-size Core ML path, real pixel sizes in the gallery, and gallery → Imagine handoffs.
@MainActor
struct ImageParamsTests {
    nonisolated static func png(_ w: Int, _ h: Int, color: CGColor = CGColor(red: 0.8, green: 0.2, blue: 0.1, alpha: 1)) -> Data {
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        ctx.setFillColor(color)
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        return ImageCodec.png(ctx.makeImage()!)!
    }

    private static let cases: [(AspectRatio, Int)] = AspectRatio.all.flatMap { a in ImageSizing.resolutions.map { (a, $0) } }

    // MARK: Sizes

    @Test func theResolutionIsAPixelBudgetAndTheAspectHolds() {
        #expect(ImageSizing.size(AspectRatio(w: 16, h: 9), resolution: 1024) == (1368, 768))
        #expect(ImageSizing.size(AspectRatio(w: 9, h: 16), resolution: 1024) == (768, 1368))
        #expect(ImageSizing.size(AspectRatio(w: 2, h: 3), resolution: 768) == (624, 944))
        #expect(ImageSizing.size(.square, resolution: 1024) == (1024, 1024))
        for (aspect, res) in Self.cases {
            let s = ImageSizing.size(aspect, resolution: res)
            let side = (Double(s.width) * Double(s.height)).squareRoot()
            #expect(abs(side - Double(res)) / Double(res) < 0.03, "\(aspect.label) @ \(res) → \(s)")
            #expect(s.width % 8 == 0 && s.height % 8 == 0)
            #expect(abs(Double(s.width) / Double(s.height) - aspect.value) / aspect.value < 0.02, "\(aspect.label) @ \(res) → \(s)")
            if aspect.w != aspect.h { #expect(s.width != s.height, "\(aspect.label) @ \(res) must not be square") }
            #expect((s.width > s.height) == (aspect.w > aspect.h))
            // The pickers find their way back from the size.
            #expect(ImageSizing.aspect(width: s.width, height: s.height) == aspect)
            #expect(ImageSizing.resolution(width: s.width, height: s.height) == res)
        }
    }

    @Test func imagineSizesPerBackendKind() {
        let wide = AspectRatio(w: 16, h: 9)
        // A PC backend: any size.
        #expect(ImagineModel.size(aspect: wide, resolution: 1024, native: nil, multiple: 8) == (1368, 768))
        // Core ML: cut from the compiled canvas, never square for a wide aspect.
        #expect(ImagineModel.size(aspect: wide, resolution: 1024, native: (1024, 1024), multiple: 8) == (1024, 576))
        #expect(ImagineModel.size(aspect: wide, resolution: 1024, native: (512, 512), multiple: 8) == (512, 288))
        #expect(ImagineModel.size(aspect: AspectRatio(w: 9, h: 16), resolution: 512, native: (512, 512), multiple: 8) == (288, 512))
        // A request left at another size is shown as what the model really delivers.
        #expect(ImagineModel.outputSize(request: (1024, 576), native: (512, 512)) == (512, 288))
        #expect(ImagineModel.outputSize(request: (1024, 576), native: nil) == (1024, 576))
        #expect(ImagineModel.outputSize(request: (1024, 1024), native: (512, 512)) == (512, 512))
    }

    // MARK: Wire formats

    private func request(_ aspect: AspectRatio, _ res: Int) -> DiffusionRequest {
        var r = DiffusionRequest()
        let s = ImageSizing.size(aspect, resolution: res)
        r.width = s.width
        r.height = s.height
        r.prompt = "a lighthouse"
        r.negativePrompt = "blurry"
        r.steps = 31
        r.cfgScale = 5.5
        r.seed = 1234
        r.sampler = "Euler a"
        r.scheduler = "Karras"
        r.batchCount = 3
        r.loras = [LoraRef(name: "ink", weight: 0.6)]
        return r
    }

    @Test func sdApiCarriesEveryAspectAndResolution() {
        for (aspect, res) in Self.cases {
            let r = request(aspect, res)
            let body = SdApiEngine.requestBody(r, DiffusionInputs())
            #expect(body["width"] as? Int == r.width, "\(aspect.label) @ \(res)")
            #expect(body["height"] as? Int == r.height, "\(aspect.label) @ \(res)")
            #expect(body["steps"] as? Int == 31)
            #expect(body["cfg_scale"] as? Double == 5.5)
            #expect(body["seed"] as? Int64 == 1234)
            #expect(body["sampler_name"] as? String == "Euler a")
            #expect(body["scheduler"] as? String == "Karras")
            #expect(body["negative_prompt"] as? String == "blurry")
            #expect(body["n_iter"] as? Int == 3)
            #expect(body["prompt"] as? String == "a lighthouse <lora:ink:0.60>")
        }
        let wide = SdApiEngine.requestBody(request(AspectRatio(w: 16, h: 9), 1024), DiffusionInputs())
        #expect(wide["width"] as? Int == 1368 && wide["height"] as? Int == 768)
    }

    @Test func localSseCarriesEveryAspectAndResolution() {
        for (aspect, res) in Self.cases {
            let r = request(aspect, res)
            let body = LocalSseEngine.requestBody(r, DiffusionInputs(), seed: 1235)
            #expect(body["width"] as? Int == r.width, "\(aspect.label) @ \(res)")
            #expect(body["height"] as? Int == r.height, "\(aspect.label) @ \(res)")
            #expect(body["steps"] as? Int == 31)
            #expect(body["cfg"] as? Double == 5.5)
            #expect(body["seed"] as? Int64 == 1235)
            #expect(body["scheduler"] as? String == "euler_a_karras")
            #expect(body["negative_prompt"] as? String == "blurry")
        }
    }

    @Test func img2imgCarriesTheInputAndStrengthOnEveryPcBackend() {
        var r = request(AspectRatio(w: 16, h: 9), 1024)
        r.mode = .img2img
        r.denoiseStrength = 0.55
        let input = DiffusionInputs(initImage: Data([1, 2, 3]))
        let a1111 = SdApiEngine.requestBody(r, input)
        #expect(a1111["init_images"] as? [String] == [Data([1, 2, 3]).base64EncodedString()])
        #expect(a1111["denoising_strength"] as? Double == 0.55)
        #expect(a1111["width"] as? Int == 1368 && a1111["height"] as? Int == 768)
        let sse = LocalSseEngine.requestBody(r, input, seed: -1)
        #expect(sse["image"] as? String == Data([1, 2, 3]).base64EncodedString())
        #expect(sse["denoise_strength"] as? Double == 0.55)
    }

    // MARK: Core ML (fixed size)

    @Test func aFixedSizeModelCutsTheAspectFromItsCanvas() {
        for native in [(512, 512), (768, 768), (1024, 1024)] {
            for aspect in AspectRatio.all {
                let plan = ImageSizing.fixed(native: native, requested: (aspect.w * 100, aspect.h * 100))
                #expect(plan.nativeWidth == native.0 && plan.nativeHeight == native.1)
                #expect(plan.width <= native.0 && plan.height <= native.1)
                #expect(max(plan.width, plan.height) == native.0, "uses the whole canvas side")
                #expect(abs(Double(plan.width) / Double(plan.height) - aspect.value) / aspect.value < 0.03, "\(aspect.label) on \(native)")
                #expect(plan.cropped == (aspect.w != aspect.h))
                // Centred.
                #expect(Int(plan.crop.minX) * 2 + plan.width == native.0 || abs(Int(plan.crop.minX) * 2 + plan.width - native.0) <= 1)
            }
        }
        let wide = ImageSizing.fixed(native: (1024, 1024), requested: (1024, 576))
        #expect((wide.width, wide.height) == (1024, 576))
        #expect(wide.crop.minY == 224)
    }

    @Test func onDeviceResultsComeBackInTheRequestedShape() throws {
        let plan = ImageSizing.fixed(native: (512, 512), requested: (1024, 576))
        let square = DiffusionEvent.result(index: 0, image: Self.png(512, 512), seed: 9, width: 512, height: 512, durationMs: 3, info: "x")
        guard case .result(_, let image, let seed, let w, let h, _, _) = OnDeviceEngine.fit(square, plan) else { Issue.record("not a result"); return }
        #expect((w, h) == (512, 288))
        #expect(ImageSizing.pixelSize(image)! == (512, 288))
        #expect(seed == 9)
        // A square request on a square model passes through untouched.
        let same = ImageSizing.fixed(native: (512, 512), requested: (512, 512))
        guard case .result(_, let untouched, _, _, _, _, _) = OnDeviceEngine.fit(square, same) else { return }
        #expect(ImageSizing.pixelSize(untouched)! == (512, 512))
    }

    @Test func onDeviceImg2imgInputsArePlacedNotSquashed() throws {
        let plan = ImageSizing.fixed(native: (512, 512), requested: (512, 288))
        let white = CGColor(red: 1, green: 1, blue: 1, alpha: 1)
        let placed = try OnDeviceEngine.place(DiffusionInputs(initImage: Self.png(512, 288), mask: Self.png(512, 288, color: white)), plan)
        let image = try #require(placed.initImage.flatMap { ImageCodec.decode($0)?.cgImage })
        let mask = try #require(placed.mask.flatMap { ImageCodec.decode($0)?.cgImage })
        #expect((image.width, image.height) == (512, 512))
        #expect((mask.width, mask.height) == (512, 512))
        // The mask keeps the margins (black) and repaints the cut-out (white).
        #expect(luma(mask, x: 256, y: 10) < 0.1)
        #expect(luma(mask, x: 256, y: 256) > 0.9)
        // Without a crop the inputs go through as they are.
        let square = ImageSizing.fixed(native: (512, 512), requested: (512, 512))
        let untouched = try OnDeviceEngine.place(DiffusionInputs(initImage: Data([1])), square)
        #expect(untouched.initImage == Data([1]))
    }

    private func luma(_ image: CGImage, x: Int, y: Int) -> Double {
        var px = [UInt8](repeating: 0, count: 4)
        let ctx = CGContext(data: &px, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        ctx.draw(image, in: CGRect(x: -x, y: -(image.height - 1 - y), width: image.width, height: image.height))
        return (Double(px[0]) + Double(px[1]) + Double(px[2])) / 765
    }

    // MARK: Generator, agent tool

    private func parts() -> (ImageGenerator, DiffusionBackendRepository, GalleryRepository) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("params-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let keys = KeyManager()
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: keys)
        let backends = DiffusionBackendRepository(preferences: Preferences(url: dir.appendingPathComponent("prefs.json")))
        let clients = HttpClientFactory(policy: NetworkPolicy(), auditLog: AuditLog())
        let gallery = GalleryRepository(store: store, keys: keys)
        let generator = ImageGenerator(engines: DiffusionEngineFactory(clients: clients, llm: LocalLlm()), gallery: gallery,
                                       backends: backends, library: ModelLibrary(store: store))
        return (generator, backends, gallery)
    }

    @Test(.timeLimit(.minutes(2))) func theGalleryStoresTheRealPixelSizeAndSaysWhenTheShapeIsWrong() async throws {
        let (generator, _, gallery) = parts()
        let fixed = RecordingEngine(square: 512)
        generator.engineOverride = { _ in fixed }
        let backend = DiffusionBackend(id: "pc", kind: .localSse, name: "Local Dream", baseUrl: "http://127.0.0.1:8081")
        var r = request(AspectRatio(w: 16, h: 9), 1024)
        r.batchCount = 1
        let images = try await generator.generate(backend, r)
        #expect(fixed.requests.first.map { ($0.width, $0.height) }! == (1368, 768))
        // The backend said 1024×576 but sent 512²: the gallery has the truth, and the user is told.
        #expect((images[0].width, images[0].height) == (512, 512))
        #expect(gallery.get(images[0].galleryId).map { ($0.width, $0.height) }! == (512, 512))
        #expect(ImageGenerator.sizeNotice(backend: "Local Dream", asked: (1024, 576), got: (512, 512))?.contains("512×512 instead of 1024×576") == true)
        #expect(ImageGenerator.sizeNotice(backend: "x", asked: (1024, 576), got: (512, 288)) == nil)
    }

    @Test(.timeLimit(.minutes(2))) func theAgentToolSendsItsSizeAndSaysWhenItCouldNotKeepIt() async throws {
        let (generator, backends, _) = parts()
        backends.save(DiffusionBackend(id: "pc", kind: .sdApi, name: "Forge", baseUrl: "http://127.0.0.1:7860"))
        let engine = RecordingEngine()
        generator.engineOverride = { _ in engine }
        let out = await GenerateImageTool.run(["prompt": "tram", "width": 1024, "height": 576, "steps": 12, "seed": 5], generator: generator, backends: backends)
        #expect(!out.isError)
        let sent = try #require(engine.requests.first)
        #expect((sent.width, sent.height, sent.steps, sent.seed, sent.mode) == (1024, 576, 12, 5, .txt2img))
        #expect(out.content.contains("1024×576"))
        #expect(!out.content.contains("Note:"))

        let square = RecordingEngine(square: 512)
        generator.engineOverride = { _ in square }
        let off = await GenerateImageTool.run(["prompt": "tram", "width": 1024, "height": 576], generator: generator, backends: backends)
        #expect(off.content.contains("Note: the backend rendered 512×512, not the requested 1024×576."))

        // aspect_ratio keeps the user's pixel budget; one side keeps the shape; nonsense is refused.
        var base = DiffusionRequest()
        base.width = 1024
        base.height = 1024
        let wide = GenerateImageTool.request(["prompt": "x", "aspect_ratio": "16:9"], base: base)
        #expect((wide.width, wide.height) == (1368, 768))
        let tall = GenerateImageTool.request(["prompt": "x", "aspect_ratio": "portrait", "width": 832], base: base)
        #expect((tall.width, tall.height) == (832, 1248))
        #expect(AspectRatio.parse("21:9") == AspectRatio(w: 2100, h: 900))
        #expect(AspectRatio.parse("16x9") == AspectRatio(w: 16, h: 9))
        let bad = await GenerateImageTool.run(["prompt": "x", "aspect_ratio": "banana"], generator: generator, backends: backends)
        #expect(bad.code == "BAD_ASPECT")
    }

    // MARK: Imagine end to end

    @Test(.timeLimit(.minutes(2))) func imagineSends16by9At1024AndImg2imgWithItsStrength() async throws {
        let app = AppContainer()
        let engine = RecordingEngine()
        app.generator.engineOverride = { _ in engine }
        let backend = DiffusionBackend(id: "test-pc-\(UUID().uuidString.prefix(6))", kind: .sdApi, name: "Test PC", baseUrl: "http://127.0.0.1:7860")
        app.backends.save(backend)
        let previousDefault = app.backends.defaultBackendId
        let previousRequest = app.backends.lastRequest
        defer {
            app.backends.saveLastRequest(previousRequest)
            app.backends.delete(backend.id)
            if let previousDefault, previousDefault != backend.id { app.backends.setDefault(previousDefault) }
        }
        let m = ImagineModel(app: app)
        m.selectBackend(backend.id)
        m.clearInitImage()
        m.setPrompt("a lighthouse at dusk")
        m.setNegative("blurry")
        m.setSteps(28)
        m.setCfg(6.5)
        m.setSeed(4242)
        m.setBatch(2)
        m.setAspect(AspectRatio(w: 16, h: 9), resolution: 1024)
        #expect((m.request.width, m.request.height) == (1368, 768))
        #expect(m.aspect == AspectRatio(w: 16, h: 9) && m.resolution == 1024)
        m.generate()
        let ids = try await waitForResults(app, count: 2)
        let sent = try #require(engine.requests.first)
        #expect((sent.width, sent.height) == (1368, 768))
        #expect((sent.steps, sent.cfgScale, sent.seed, sent.batchCount, sent.negativePrompt) == (28, 6.5, 4242, 2, "blurry"))
        #expect(app.generator.lastResults.allSatisfy { ($0.width, $0.height) == (1368, 768) })

        // The result straight back in as img2img: the image, its prompt, its shape, a strength.
        await m.handoff(ids[0], .img2img)
        #expect(m.request.mode == .img2img)
        #expect(m.initImage != nil)
        #expect(m.request.prompt == "a lighthouse at dusk")
        #expect((m.request.width, m.request.height) == (1368, 768))
        m.setDenoise(0.55)
        m.generate()
        _ = try await waitForRequests(engine, count: 2)
        let i2i = try #require(engine.requests.last)
        let input = try #require(engine.inputs.last?.initImage)
        #expect(i2i.mode == .img2img)
        #expect(i2i.denoiseStrength == 0.55)
        #expect(ImageSizing.pixelSize(input)! == (1368, 768))
        _ = try await waitForResults(app, count: 1)
        for id in app.generator.lastResults.map(\.galleryId) + ids { app.gallery.delete(id) }
    }

    private func waitForRequests(_ engine: RecordingEngine, count: Int) async throws -> [DiffusionRequest] {
        for _ in 0 ..< 500 where engine.requests.count < count { try await Task.sleep(for: .milliseconds(10)) }
        return engine.requests
    }

    private func waitForResults(_ app: AppContainer, count: Int) async throws -> [String] {
        for _ in 0 ..< 500 {
            if !app.generator.isBusy, app.generator.lastResults.count >= count { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(app.generator.lastError == nil)
        return app.generator.lastResults.map(\.galleryId)
    }

    // MARK: Handoffs

    @Test func choosingAModelKeepsTheShapeAtItsBudget() {
        var r = DiffusionRequest()
        r.width = 912
        r.height = 512
        ImageDefaults.apply(ImageDefaults.sdxl, to: &r)
        #expect((r.width, r.height) == (1368, 768))
        var square = DiffusionRequest()
        ImageDefaults.apply(ImageDefaults.sdxl, to: &square)
        #expect((square.width, square.height) == (1024, 1024))
        #expect(ImageSizing.compiledNote((512, 512)) == "compiled for 512² · other shapes are cut from the square")
    }

    @Test func eachHandoffBringsTheRightSettings() throws {
        var params = DiffusionRequest()
        params.prompt = "fox"
        params.width = 1024
        params.height = 576
        params.seed = 99
        params.steps = 22
        params.mode = .img2img
        params.batchCount = 1
        let item = GalleryItem(id: "g1", createdAt: 1, mode: "IMG2IMG", prompt: "fox", negativePrompt: "", width: 1024, height: 576, seed: 99, steps: 22,
                               cfgScale: 7, sampler: "DPM++ 2M", engine: "Forge", model: nil, durationMs: 1,
                               paramsJson: String(decoding: try JSONEncoder().encode(params), as: UTF8.self))
        let reuse = try #require(ImagineModel.handoffRequest(item, .reuse))
        #expect((reuse.seed, reuse.mode, reuse.width, reuse.height, reuse.steps) == (-1, .txt2img, 1024, 576, 22))
        let same = try #require(ImagineModel.handoffRequest(item, .sameSeed))
        #expect((same.seed, same.mode) == (99, .txt2img))
        let i2i = try #require(ImagineModel.handoffRequest(item, .img2img))
        #expect((i2i.seed, i2i.mode, i2i.prompt) == (-1, .img2img, "fox"))
        let vary = try #require(ImagineModel.handoffRequest(item, .vary))
        #expect((vary.mode, vary.denoiseStrength) == (.img2img, ImageHandoff.varyStrength))
        #expect(try #require(ImagineModel.handoffRequest(item, .inpaint)).mode == .inpaint)
    }

    @Test func theViewerOpensOnTheTappedImage() {
        #expect(ImageViewerTarget(ids: ["a", "b"], start: "b").ids == ["a", "b"])
        #expect(ImageViewerTarget(ids: ["a", "b"], start: "c").ids == ["c", "a", "b"])
    }
}

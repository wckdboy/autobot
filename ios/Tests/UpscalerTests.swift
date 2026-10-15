import Foundation
import Testing
@testable import Autobot

@MainActor
struct UpscalerTests {
    @Test func catalogEntryIsACoreMLUpscalerPackage() throws {
        let plan = try #require(CoreMLCatalog.upscalers.first)
        #expect(plan.kind == .upscaler)
        #expect(plan.engine == .coreML)
        #expect(engineFor(.upscaler, .coreML) == .coreML)
        #expect(plan.license == "bsd-3-clause")
        #expect(plan.manifest.files.allSatisfy { $0.keepName && $0.name.hasPrefix("RealESRGAN_x4.mlpackage/") && $0.url.contains("/resolve/f1693203") })
        let weights = try #require(plan.manifest.files.first { $0.name.hasSuffix("model.mlmodel") })
        #expect(weights.sizeBytes == 66_857_221)
        #expect(weights.sha256 == "6107dc417de87bf974e5b225a2632e2c78f2849265dc897981f482e922050ec9")
        // Stored as a real .mlpackage folder inside the model's directory.
        let path = ModelLibrary.file(of: plan.id, weights).path
        #expect(path.hasSuffix("/RealESRGAN_x4.mlpackage/Data/com.apple.CoreML/model.mlmodel"))
    }

    @Test func galleryUpscaleNeedsTheModel() async {
        let app = AppContainer()
        let item = GalleryItem(id: "x", createdAt: 0, mode: "TXT2IMG", prompt: "p", negativePrompt: "", width: 512, height: 512, seed: 1, steps: 4,
                               cfgScale: 7, sampler: "DPM++ 2M", engine: "This phone", model: nil, durationMs: 1, paramsJson: "{}")
        if GalleryUpscale.upscaler(app) == nil {
            await #expect(throws: GalleryUpscale.Failure.self) {
                _ = try await GalleryUpscale.run(item, app: app, upscale: LocalUpscale()) { _, _ in }
            }
        }
    }
}

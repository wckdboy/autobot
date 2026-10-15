import Foundation
import UIKit
#if canImport(AutobotEngines)
import AutobotEngines
#endif

/// 4× super-resolution on the Neural Engine (`AutobotEngines.UpscaleEngine`, tiled Core ML).
final class LocalUpscale: Sendable {
    /// Upscales an encoded image with the model in [folder]; returns PNG bytes.
    func upscale(folder: URL, image: Data, onProgress: @escaping @Sendable (Int, Int) -> Void) async throws -> (png: Data, width: Int, height: Int) {
        #if canImport(AutobotEngines)
        guard let source = UIImage(data: image).flatMap({ ImageCodec.upright($0, maxSide: .greatestFiniteMagnitude) }) else {
            throw LocalEngineError(code: "BAD_IMAGE", message: "cannot read the image")
        }
        do {
            let out = try await UpscaleEngine.shared.upscale(folder: folder, image: source, onProgress: onProgress)
            guard let png = ImageCodec.png(out) else { throw LocalEngineError(code: "BAD_IMAGE", message: "cannot encode the result") }
            return (png, out.width, out.height)
        } catch {
            throw Engines.map(error)
        }
        #else
        throw EngineUnavailable()
        #endif
    }

    func cancel() {
        #if canImport(AutobotEngines)
        UpscaleEngine.shared.cancel()
        #endif
    }

    func unload() async {
        #if canImport(AutobotEngines)
        await UpscaleEngine.shared.unload()
        #endif
    }
}

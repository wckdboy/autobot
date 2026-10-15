import Foundation
import UIKit
#if canImport(AutobotEngines)
import AutobotEngines
#endif

/// 4× super-resolution on the Neural Engine (`AutobotEngines.UpscaleEngine`, tiled Core ML).
final class LocalUpscale: Sendable {
    /// The upscaler's factor (Real-ESRGAN x4).
    static let scale = 4

    /// Whether a [width]×[height] image fits in memory for a 4× upscale right now.
    static func fits(width: Int, height: Int) -> Bool {
        #if canImport(AutobotEngines)
        UpscaleEngine.fits(width: width, height: height, scale: scale)
        #else
        false
        #endif
    }

    /// Upscales an encoded image with the model in [folder]; returns PNG bytes.
    func upscale(folder: URL, image: Data, onProgress: @escaping @Sendable (Int, Int) -> Void) async throws -> (png: Data, width: Int, height: Int) {
        #if canImport(AutobotEngines)
        guard let source = UIImage(data: image).flatMap({ ImageCodec.upright($0, maxSide: .greatestFiniteMagnitude) }) else {
            throw LocalEngineError(code: "BAD_IMAGE", message: "cannot read the image")
        }
        do {
            return try await EngineMemory.using(.upscaler) {
                let out = try await UpscaleEngine.shared.upscale(folder: folder, image: source, onProgress: onProgress)
                guard let png = ImageCodec.png(out) else { throw LocalEngineError(code: "BAD_IMAGE", message: "cannot encode the result") }
                return (png, out.width, out.height)
            }
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
        await EngineMemory.shared.release([.upscaler])
    }
}

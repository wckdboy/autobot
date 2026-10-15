import Foundation

/**
 The gallery's "upscale 4×" (Android: NPU upscaler): runs the installed Core ML upscaler on the
 Neural Engine and stores the result as a new encrypted gallery item with the same parameters.
 */
@MainActor
enum GalleryUpscale {
    static let label = "4× ane"

    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    static func upscaler(_ app: AppContainer) -> InstalledModel? {
        app.library.models.first { $0.isReady && $0.kind == .upscaler && $0.engine == .coreML }
    }

    static func run(_ item: GalleryItem, app: AppContainer, upscale: LocalUpscale, onProgress: @escaping @Sendable (Int, Int) -> Void) async throws -> GalleryItem {
        guard let model = upscaler(app) else { throw Failure(message: "Get the upscaler in Models → upscale first") }
        guard !app.generator.isBusy else { throw Failure(message: "An image is being generated; try again when it is done") }
        guard let bytes = await app.gallery.image(item.id) else { throw Failure(message: "image unavailable") }
        // The chat model and the upscaler should not compete for memory.
        await app.llm.unload()
        let started = Date()
        let result = try await upscale.upscale(folder: ModelLibrary.dir(of: model.id), image: bytes, onProgress: onProgress)
        let thumb = await Task.detached { ImageCodec.thumbnail(result.png) }.value ?? result.png
        var copy = item
        copy.id = app.gallery.newId()
        copy.createdAt = nowMillis()
        copy.width = result.width
        copy.height = result.height
        copy.engine = "\(item.engine) · \(label)"
        copy.durationMs = Int64(Date().timeIntervalSince(started) * 1000)
        copy.favorite = false
        try await app.gallery.save(copy, image: result.png, thumbnail: thumb)
        return copy
    }
}

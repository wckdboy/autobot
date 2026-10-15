import CoreGraphics
import CoreML
import CoreVideo
import Foundation
import os
import Synchronization

/**
 Tiling for a fixed-size super-resolution model: the image is cut into model-sized tiles that
 overlap, each tile is upscaled on its own, and the overlaps are cross-faded so seams vanish.
 Tiles advance by `tile - overlap`; the last tile in a row or column is pulled back to end at
 the image edge, so its overlap with the previous one can be larger.
 */
public struct UpscaleTiling: Sendable, Equatable {
    public struct Tile: Sendable, Equatable {
        /// Source rectangle (input pixels). Smaller than the model's tile only when the image is.
        public let x: Int, y: Int, width: Int, height: Int
        /// Input pixels shared with the tile to the left / above (0 on the first column / row).
        public let overlapLeft: Int, overlapTop: Int
    }

    public let tile: Int
    public let overlap: Int
    public let scale: Int

    public init(tile: Int, overlap: Int, scale: Int) {
        precondition(tile > 0 && overlap >= 0 && overlap < tile && scale > 0)
        self.tile = tile
        self.overlap = overlap
        self.scale = scale
    }

    /// Tile origins along one axis of [length] pixels.
    func origins(_ length: Int) -> [Int] {
        guard length > tile else { return [0] }
        let stride = tile - overlap
        var out: [Int] = []
        var p = 0
        while p + tile < length {
            out.append(p)
            p += stride
        }
        out.append(length - tile)
        return out
    }

    /// Every tile of a [width]×[height] image, row by row.
    public func tiles(width: Int, height: Int) -> [Tile] {
        let xs = origins(width), ys = origins(height)
        let w = min(tile, width), h = min(tile, height)
        var out: [Tile] = []
        for (row, y) in ys.enumerated() {
            for (col, x) in xs.enumerated() {
                out.append(Tile(
                    x: x, y: y, width: w, height: h,
                    overlapLeft: col == 0 ? 0 : xs[col - 1] + w - x,
                    overlapTop: row == 0 ? 0 : ys[row - 1] + h - y,
                ))
            }
        }
        return out
    }

    /**
     Weight of a tile's output pixel at ([dx], [dy]) (output pixels from the tile's corner) when it
     is laid over what earlier tiles wrote: a linear ramp from 0 to 1 across the shared region on
     the left and top edges, 1 elsewhere.
     */
    public func weight(_ t: Tile, dx: Int, dy: Int) -> Float {
        func ramp(_ d: Int, _ shared: Int) -> Float {
            let span = shared * scale
            guard span > 0, d < span else { return 1 }
            return (Float(d) + 0.5) / Float(span)
        }
        return ramp(dx, t.overlapLeft) * ramp(dy, t.overlapTop)
    }
}

/**
 4× super-resolution on the Apple Neural Engine (`.cpuAndNeuralEngine`) with a Core ML model that
 takes a fixed-size BGRA image (Real-ESRGAN x4: 512 → 2048). Large images are tiled with
 overlapping tiles and cross-faded; small ones are edge-padded to the model size. The iOS side
 of Android's NPU upscaler. Nothing is fetched or sent.
 */
public actor UpscaleEngine {
    public static let shared = UpscaleEngine()
    public static let overlap = 32
    /// Largest output side: beyond it the encoded result alone takes hundreds of megabytes.
    public static let maxOutputSide = 8192

    private var loaded: (url: URL, model: MLModel, input: String, output: String, tile: Int, scale: Int)?
    /// Requests running, by token; true once cancelled.
    private nonisolated let requests = Mutex<(next: UInt64, cancelled: [UInt64: Bool])>((1, [:]))

    public init() {}

    /**
     Whether a [width]×[height] image can be upscaled [scale]× within [availableBytes] of memory
     (default: what the system grants this process now, `os_proc_available_memory`; where that is
     unknown, as in the Simulator, half the RAM). Counts the
     decoded source twice (the image and its RGBA copy) and the result once (encoded and sealed
     afterwards; the pixels themselves go to a file), with headroom for the model.
     */
    public static func fits(width: Int, height: Int, scale: Int, availableBytes: Int = os_proc_available_memory()) -> Bool {
        guard width > 0, height > 0, scale > 0 else { return false }
        let (outW, o1) = width.multipliedReportingOverflow(by: scale)
        let (outH, o2) = height.multipliedReportingOverflow(by: scale)
        guard !o1, !o2, max(outW, outH) <= maxOutputSide else { return false }
        let available = availableBytes > 0 ? availableBytes : Int(ProcessInfo.processInfo.physicalMemory / 2)
        let needed = width * height * 8 + outW * outH * 4 + 256 << 20
        return needed <= available / 10 * 8
    }

    /// Upscales [image] with the model in [folder] (a `.mlmodelc`, or an `.mlpackage` compiled on
    /// first use and kept next to it). [onProgress] gets (done, total) tiles. The result is backed
    /// by a temporary file mapped into memory, so its pixels never count as the app's own memory.
    public func upscale(folder: URL, image: CGImage, onProgress: @escaping @Sendable (Int, Int) -> Void = { _, _ in }) async throws -> CGImage {
        let token = requests.withLock { r in
            let token = r.next
            r.next += 1
            r.cancelled[token] = false
            return token
        }
        defer { _ = requests.withLock { $0.cancelled.removeValue(forKey: token) } }
        let m = try await load(folder)
        guard Self.fits(width: image.width, height: image.height, scale: m.scale) else {
            throw EngineError("TOO_LARGE", "this image is too large to upscale on this phone right now")
        }
        let tiling = UpscaleTiling(tile: m.tile, overlap: min(Self.overlap, m.tile / 4), scale: m.scale)
        let width = image.width, height = image.height
        let source = try Self.rgba(image)
        let outW = width * m.scale, outH = height * m.scale
        let out = try MappedPixels(count: outW * outH * 4)
        let tiles = tiling.tiles(width: width, height: height)
        for (i, t) in tiles.enumerated() {
            try Task.checkCancellation()
            if requests.withLock({ $0.cancelled[token] ?? true }) { throw EngineError.cancelled }
            let input = try Self.tileBuffer(source, width: width, tile: t, size: m.tile)
            let result = try Self.predict(m.model, MLDictionaryFeatureProvider(dictionary: [m.input: MLFeatureValue(pixelBuffer: input)]))
            guard let buffer = result.featureValue(for: m.output)?.imageBufferValue else {
                throw EngineError("BAD_RESULT", "the upscaler returned no image")
            }
            Self.blend(buffer, into: out.pixels, outWidth: outW, tile: t, tiling: tiling)
            onProgress(i + 1, tiles.count)
        }
        return try Self.image(out.finish(), width: outW, height: outH)
    }

    /// Stops the running upscale after the current tile.
    public nonisolated func cancel() {
        requests.withLock { r in
            for key in r.cancelled.keys { r.cancelled[key] = true }
        }
    }

    /// Frees the model.
    public func unload() { loaded = nil }

    private func load(_ folder: URL) async throws -> (url: URL, model: MLModel, input: String, output: String, tile: Int, scale: Int) {
        if let loaded, loaded.url == folder { return loaded }
        let fm = FileManager.default
        let items = (try? fm.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        var compiled = items.first { $0.pathExtension == "mlmodelc" }
        if compiled == nil, let package = items.first(where: { $0.pathExtension == "mlpackage" }) {
            let temp = try await MLModel.compileModel(at: package)
            let kept = folder.appending(path: package.deletingPathExtension().lastPathComponent + ".mlmodelc")
            try? fm.removeItem(at: kept)
            try fm.moveItem(at: temp, to: kept)
            compiled = kept
        }
        guard let compiled else { throw EngineError("UNSUPPORTED", "no Core ML upscaler in \(folder.lastPathComponent)") }
        let config = MLModelConfiguration()
        config.computeUnits = .cpuAndNeuralEngine
        let model = try MLModel(contentsOf: compiled, configuration: config)
        let d = model.modelDescription
        guard let (input, inDesc) = d.inputDescriptionsByName.first(where: { $0.value.imageConstraint != nil }),
              let (output, outDesc) = d.outputDescriptionsByName.first(where: { $0.value.imageConstraint != nil }),
              let inSize = inDesc.imageConstraint?.pixelsWide, let outSize = outDesc.imageConstraint?.pixelsWide, inSize > 0, outSize % inSize == 0
        else { throw EngineError("UNSUPPORTED", "the upscaler must take and return fixed-size images") }
        let l = (folder, model, input, output, inSize, outSize / inSize)
        loaded = l
        return l
    }

    /// Synchronous prediction on the actor (the model never leaves it).
    private static func predict(_ model: MLModel, _ input: MLFeatureProvider) throws -> MLFeatureProvider {
        try model.prediction(from: input)
    }

    // MARK: Pixels

    static func rgba(_ image: CGImage) throws -> [UInt8] {
        var data = [UInt8](repeating: 0, count: image.width * image.height * 4)
        let ok = data.withUnsafeMutableBytes { buf -> Bool in
            guard let ctx = CGContext(data: buf.baseAddress, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: image.width * 4,
                                      space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
            return true
        }
        guard ok else { throw EngineError("BAD_IMAGE", "cannot read the image") }
        return data
    }

    /// The tile as a [size]² BGRA buffer; a tile smaller than the model repeats its last row and
    /// column so the network sees no hard black border.
    static func tileBuffer(_ src: [UInt8], width: Int, tile t: UpscaleTiling.Tile, size: Int) throws -> CVPixelBuffer {
        var pb: CVPixelBuffer?
        CVPixelBufferCreate(nil, size, size, kCVPixelFormatType_32BGRA, [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &pb)
        guard let pb else { throw EngineError("NO_MEMORY", "cannot allocate a tile") }
        CVPixelBufferLockBaseAddress(pb, [])
        defer { CVPixelBufferUnlockBaseAddress(pb, []) }
        let row = CVPixelBufferGetBytesPerRow(pb)
        let dst = CVPixelBufferGetBaseAddress(pb)!.assumingMemoryBound(to: UInt8.self)
        for y in 0 ..< size {
            let sy = t.y + min(y, t.height - 1)
            for x in 0 ..< size {
                let sx = t.x + min(x, t.width - 1)
                let s = (sy * width + sx) * 4, d = y * row + x * 4
                dst[d] = src[s + 2]
                dst[d + 1] = src[s + 1]
                dst[d + 2] = src[s]
                dst[d + 3] = 255
            }
        }
        return pb
    }

    /// Cross-fades the tile's output into [dst] (RGBA) with the tiling's edge ramps.
    static func blend(_ buffer: CVPixelBuffer, into dst: UnsafeMutableBufferPointer<UInt8>, outWidth: Int, tile t: UpscaleTiling.Tile, tiling: UpscaleTiling) {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let row = CVPixelBufferGetBytesPerRow(buffer)
        let src = CVPixelBufferGetBaseAddress(buffer)!.assumingMemoryBound(to: UInt8.self)
        let bgra = CVPixelBufferGetPixelFormatType(buffer) == kCVPixelFormatType_32BGRA
        let s = tiling.scale
        let (ri, bi) = bgra ? (2, 0) : (0, 2)
        do {
            for dy in 0 ..< t.height * s {
                for dx in 0 ..< t.width * s {
                    let w = tiling.weight(t, dx: dx, dy: dy)
                    let p = dy * row + dx * 4
                    let o = ((t.y * s + dy) * outWidth + t.x * s + dx) * 4
                    let r = src[p + ri], g = src[p + 1], b = src[p + bi]
                    if w >= 1 {
                        dst[o] = r
                        dst[o + 1] = g
                        dst[o + 2] = b
                    } else {
                        dst[o] = UInt8((Float(dst[o]) * (1 - w) + Float(r) * w).rounded())
                        dst[o + 1] = UInt8((Float(dst[o + 1]) * (1 - w) + Float(g) * w).rounded())
                        dst[o + 2] = UInt8((Float(dst[o + 2]) * (1 - w) + Float(b) * w).rounded())
                    }
                    dst[o + 3] = 255
                }
            }
        }
    }

    static func image(_ rgba: Data, width: Int, height: Int) throws -> CGImage {
        guard let provider = CGDataProvider(data: rgba as CFData),
              let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                                  space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                                  provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)
        else { throw EngineError("BAD_IMAGE", "cannot build the upscaled image") }
        return image
    }
}

/**
 The upscaled pixels in a temporary file mapped into memory: written tile by tile, then handed
 over as mapped `Data` (the file is unlinked at once; the mapping keeps it alive). File-backed
 pages can be written out and dropped under pressure, so a large result does not push the app
 over its memory limit.
 */
final class MappedPixels {
    let pixels: UnsafeMutableBufferPointer<UInt8>
    private let url: URL
    private var mapped: Bool

    init(count: Int) throws {
        url = FileManager.default.temporaryDirectory.appending(path: "upscale-\(UUID().uuidString).rgba")
        let fd = open(url.path, O_RDWR | O_CREAT | O_EXCL, 0o600)
        guard fd >= 0 else { throw EngineError("NO_MEMORY", "cannot create the output file") }
        defer { close(fd) }
        guard ftruncate(fd, off_t(count)) == 0,
              let base = mmap(nil, count, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0), base != UnsafeMutableRawPointer(bitPattern: -1)
        else {
            unlink(url.path)
            throw EngineError("NO_MEMORY", "not enough storage for the upscaled image")
        }
        pixels = UnsafeMutableBufferPointer(start: base.assumingMemoryBound(to: UInt8.self), count: count)
        mapped = true
    }

    /// The pixels as file-backed `Data`; the writable mapping and the file name are gone afterwards.
    func finish() throws -> Data {
        unmap()
        defer { unlink(url.path) }
        return try Data(contentsOf: url, options: .alwaysMapped)
    }

    private func unmap() {
        guard mapped else { return }
        munmap(pixels.baseAddress, pixels.count)
        mapped = false
    }

    deinit {
        unmap()
        unlink(url.path)
    }
}

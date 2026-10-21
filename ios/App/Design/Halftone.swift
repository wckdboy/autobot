import SwiftUI
import UIKit

/// Downsampled luminance grid used by [HalftoneImage].
struct LumaGrid: Sendable, Equatable {
    let width: Int
    let height: Int
    let values: [Float]

    func sample(_ x: Float, _ y: Float) -> Float {
        let ix = min(max(Int(x * Float(width)), 0), width - 1)
        let iy = min(max(Int(y * Float(height)), 0), height - 1)
        return values[iy * width + ix]
    }

    /// Scales [image] so its long side is [longSide] pixels and reads Rec. 601 luma.
    init?(image: CGImage, longSide: Int = Int(Tokens.Halftone.imageSampleLongSide)) {
        let scale = Double(longSide) / Double(max(image.width, image.height))
        let w = max(1, Int(Double(image.width) * scale))
        let h = max(1, Int(Double(image.height) * scale))
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue,
            ) else { return false }
            context.interpolationQuality = .medium
            context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
            return true
        }
        guard drawn else { return nil }
        let r = Float(Tokens.Halftone.lumaR), g = Float(Tokens.Halftone.lumaG), b = Float(Tokens.Halftone.lumaB)
        var values = [Float](repeating: 0, count: w * h)
        for i in 0 ..< w * h {
            values[i] = (Float(pixels[i * 4]) * r + Float(pixels[i * 4 + 1]) * g + Float(pixels[i * 4 + 2]) * b) / 255
        }
        self.width = w
        self.height = h
        self.values = values
    }
}

/**
 Luma grids by image identity, so redrawing a card does not resample its bitmap. Each entry holds
 its image weakly and is only a hit while that same image is alive: a freed image's address can be
 reused by a new one, which must not inherit the old image's grid.
 */
@MainActor
enum LumaCache {
    private struct Entry {
        weak var image: UIImage?
        let grid: LumaGrid
    }

    private static var grids: [ObjectIdentifier: Entry] = [:]
    static let capacity = 64

    static func grid(_ image: UIImage) -> LumaGrid? {
        let key = ObjectIdentifier(image)
        if let cached = grids[key], cached.image === image { return cached.grid }
        guard let grid = image.cgImage.flatMap({ LumaGrid(image: $0) }) else { return nil }
        if grids.count >= capacity {
            // Drop the entries whose images are gone first; everything if all are still alive.
            grids = grids.filter { $0.value.image != nil }
            if grids.count >= capacity { grids.removeAll() }
        }
        grids[key] = Entry(image: image, grid: grid)
        return grid
    }

    #if DEBUG
    static func reset() { grids.removeAll() }
    #endif
}

/**
 Renders an image as halftone dots (riso print look): the image is sampled on a grid and each
 cell becomes a dot whose radius follows the cell's darkness.
 */
struct HalftoneImage: View {
    let grid: LumaGrid?
    var dot: Color? = nil
    var paper: Color = .clear
    var cell: CGFloat = Tokens.Halftone.imageCell
    @Environment(\.scheme) private var scheme

    init(image: UIImage?, dot: Color? = nil, paper: Color = .clear, cell: CGFloat = Tokens.Halftone.imageCell) {
        self.grid = image.flatMap(LumaCache.grid)
        self.dot = dot
        self.paper = paper
        self.cell = cell
    }

    init(grid: LumaGrid?, dot: Color? = nil, paper: Color = .clear, cell: CGFloat = Tokens.Halftone.imageCell) {
        self.grid = grid
        self.dot = dot
        self.paper = paper
        self.cell = cell
    }

    var body: some View {
        Canvas { context, size in
            guard let grid else { return }
            // Centred on a whole-pixel grid ([InkGrid.centres]), sampled at each dot's centre.
            let w = Double(size.width), h = Double(size.height)
            let dots = InkGrid.centres(width: w, height: h, pitch: Double(cell), scale: Double(context.environment.displayScale))
            let factor = dots.pitch * Double(Tokens.Halftone.imageRadiusFactor)
            var path = Path()
            for y in dots.ys {
                for x in dots.xs {
                    let l = grid.sample(Float(x / max(w, 1)), Float(y / max(h, 1)))
                    let radius = Double(1 - l) * factor
                    if radius > Double(Tokens.Halftone.imageMinRadius) {
                        path.addEllipse(in: CGRect(x: x - radius, y: y - radius, width: radius * 2, height: radius * 2))
                    }
                }
            }
            context.fill(path, with: .color(dot ?? scheme.primary))
        }
        .background(paper)
        .clipped()
        .accessibilityHidden(true)
    }
}

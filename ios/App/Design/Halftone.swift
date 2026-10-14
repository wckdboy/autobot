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

/// Luma grids by image identity, so redrawing a card does not resample its bitmap.
@MainActor
enum LumaCache {
    private static var grids: [ObjectIdentifier: LumaGrid] = [:]

    static func grid(_ image: UIImage) -> LumaGrid? {
        let key = ObjectIdentifier(image)
        if let cached = grids[key] { return cached }
        guard let grid = image.cgImage.flatMap({ LumaGrid(image: $0) }) else { return nil }
        if grids.count > 64 { grids.removeAll() }
        grids[key] = grid
        return grid
    }
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
            let cols = max(1, Int(size.width / cell))
            let rows = max(1, Int(size.height / cell))
            var path = Path()
            for r in 0 ..< rows {
                for c in 0 ..< cols {
                    let l = grid.sample((Float(c) + 0.5) / Float(cols), (Float(r) + 0.5) / Float(rows))
                    let radius = CGFloat(1 - l) * cell * Tokens.Halftone.imageRadiusFactor
                    if radius > Tokens.Halftone.imageMinRadius {
                        let x = (CGFloat(c) + 0.5) * cell, y = (CGFloat(r) + 0.5) * cell
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

/// Procedural halftone field (hero art): dots swell along a soft diagonal wave.
struct HalftoneField: View {
    var dot: Color? = nil
    var cell: CGFloat = Tokens.Halftone.fieldCell
    var phase: CGFloat = 0
    @Environment(\.scheme) private var scheme

    var body: some View {
        Canvas { context, size in
            let cols = Int(size.width / cell) + 1
            let rows = Int(size.height / cell) + 1
            var path = Path()
            for r in 0 ..< rows {
                for c in 0 ..< cols {
                    let x = CGFloat(c) / CGFloat(cols)
                    let y = CGFloat(r) / CGFloat(rows)
                    let wave = 0.5 + 0.5 * sin((x * Tokens.Halftone.fieldWaveX + y * Tokens.Halftone.fieldWaveY + phase) * 3.14159)
                    let dx = x - Tokens.Halftone.fieldBlobX, dy = y - Tokens.Halftone.fieldBlobY
                    let blob = 1 - min(1, sqrt(dx * dx * Tokens.Halftone.fieldBlobWeightX + dy * dy * Tokens.Halftone.fieldBlobWeightY))
                    let v = min(max(Tokens.Halftone.fieldWaveMix * wave + Tokens.Halftone.fieldBlobMix * blob - Tokens.Halftone.fieldFalloffY * y, 0), 1)
                    let radius = v * cell * Tokens.Halftone.fieldRadiusFactor
                    if radius > Tokens.Halftone.fieldMinRadius {
                        let cx = CGFloat(c) * cell, cy = CGFloat(r) * cell
                        path.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
                    }
                }
            }
            context.fill(path, with: .color(dot ?? scheme.primary))
        }
        .clipped()
        .accessibilityHidden(true)
    }
}

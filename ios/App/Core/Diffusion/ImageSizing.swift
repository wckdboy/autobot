import CoreGraphics
import Foundation
import ImageIO

/// An aspect ratio offered by the size pickers (`16:9` = 16 wide, 9 high).
struct AspectRatio: Hashable, Sendable, Identifiable {
    let w: Int
    let h: Int
    var id: String { label }
    var label: String { "\(w):\(h)" }
    var value: Double { Double(w) / Double(h) }

    static let square = AspectRatio(w: 1, h: 1)
    static let all: [AspectRatio] = [
        .square, AspectRatio(w: 2, h: 3), AspectRatio(w: 3, h: 2), AspectRatio(w: 3, h: 4), AspectRatio(w: 4, h: 3),
        AspectRatio(w: 9, h: 16), AspectRatio(w: 16, h: 9),
    ]

    /**
     `16:9`, `16x9`, `16/9`, or a word (square, portrait, landscape, wide, tall, story), as
     Android's `Aspect.parse`; nil when [text] is none of these. A ratio not listed (21:9) is kept.
     */
    static func parse(_ text: String?) -> AspectRatio? {
        guard let t = text?.trimmingCharacters(in: .whitespaces).lowercased(), !t.isEmpty else { return nil }
        switch t {
        case "square": return .square
        case "portrait", "tall", "vertical": return AspectRatio(w: 2, h: 3)
        case "landscape", "horizontal": return AspectRatio(w: 3, h: 2)
        case "wide", "widescreen", "cinematic": return AspectRatio(w: 16, h: 9)
        case "story", "phone": return AspectRatio(w: 9, h: 16)
        default: break
        }
        let parts = t.split(whereSeparator: { ":x×/".contains($0) }).map { $0.trimmingCharacters(in: .whitespaces) }
        guard parts.count == 2, let a = Double(parts[0]), let b = Double(parts[1]), a > 0, b > 0, a / b <= 4, b / a <= 4,
              parts.allSatisfy({ $0.count <= 6 }) else { return nil }
        if let listed = all.first(where: { abs($0.value - a / b) < 0.01 }) { return listed }
        return AspectRatio(w: Int((a * 100).rounded()), h: Int((b * 100).rounded()))
    }

    /// How VoiceOver reads it ("16 by 9, landscape").
    var spoken: String {
        "\(w) by \(h)" + (w == h ? ", square" : w > h ? ", landscape" : ", portrait")
    }
}

/**
 Image sizes from an aspect and a resolution, the same rules on every screen and for every backend
 (Android's `SizeSupport`).

 The resolution is a pixel budget: `16:9 · 1024` is about 1024² pixels in that shape, 1368×768,
 never a squashed side. Both sides snap to the backend's size multiple within 64–2048 px. A Core
 ML model is compiled for one canvas, so it renders that and the requested shape is cut from its
 middle ([fixed]); the pickers show that size instead of pretending the model renders another.
 */
enum ImageSizing {
    static let resolutions = [512, 768, 1024]

    static let minSide = 64
    static let maxSide = 2048

    /// [aspect] at about [resolution]² pixels, sides snapped to [multiple].
    static func size(_ aspect: AspectRatio, resolution: Int, multiple: Int = 8) -> (width: Int, height: Int) {
        let area = Double(resolution) * Double(resolution)
        return fit(multiple: multiple, w: (area * aspect.value).squareRoot(), h: (area / aspect.value).squareRoot())
    }

    /// [w]×[h] snapped to [multiple] and kept within 64–2048 px without changing its shape.
    static func output(width: Int, height: Int, multiple: Int = 8) -> (width: Int, height: Int) {
        guard width > 0, height > 0 else { return (512, 512) }
        return fit(multiple: multiple, w: Double(width), h: Double(height))
    }

    private static func fit(multiple: Int, w: Double, h: Double) -> (width: Int, height: Int) {
        let lo = Double(minSide), hi = Double(maxSide)
        var s = 1.0
        if w > hi || h > hi { s = min(hi / w, hi / h) }
        if w * s < lo || h * s < lo { s = min(max(lo / w, lo / h), min(hi / w, hi / h)) }
        func snap(_ v: Double) -> Int { min(max(Int((v / Double(multiple)).rounded()) * multiple, minSide), maxSide) }
        return (snap(w * s), snap(h * s))
    }

    /**
     The size of an agent's request (Android's `resolveSize`): explicit width and height win; an
     aspect with one side derives the other; an aspect alone keeps [current]'s pixel budget; one
     side alone keeps [current]'s shape.
     */
    static func resolve(current: (Int, Int), width: Int?, height: Int?, aspect: AspectRatio?, multiple: Int = 8) -> (width: Int, height: Int) {
        let ratio = aspect?.value ?? Double(current.0) / Double(max(current.1, 1))
        if let width, let height { return output(width: width, height: height, multiple: multiple) }
        if let width { return output(width: width, height: Int((Double(width) / ratio).rounded()), multiple: multiple) }
        if let height { return output(width: Int((Double(height) * ratio).rounded()), height: height, multiple: multiple) }
        if let aspect { return size(aspect, resolution: Int((Double(current.0) * Double(current.1)).squareRoot().rounded()), multiple: multiple) }
        return output(width: current.0, height: current.1, multiple: multiple)
    }

    /// The listed aspect [width]×[height] is (within 3 %), or nil for an odd size.
    static func aspect(width: Int, height: Int) -> AspectRatio? {
        guard width > 0, height > 0 else { return nil }
        let ratio = Double(width) / Double(height)
        return AspectRatio.all.min { abs($0.value - ratio) < abs($1.value - ratio) }.flatMap { abs($0.value - ratio) / $0.value < 0.03 ? $0 : nil }
    }

    /// The listed pixel budget nearest to [width]×[height]'s pixel count.
    static func resolution(width: Int, height: Int) -> Int {
        let side = (Double(max(width, 1)) * Double(max(height, 1))).squareRoot()
        return resolutions.min { abs(Double($0) - side) < abs(Double($1) - side) } ?? 512
    }

    /// [width]×[height]'s shape at [rec]'s pixel budget: choosing a model keeps the chosen shape.
    static func keepingShape(width: Int, height: Int, budget rec: (Int, Int), multiple: Int = 8) -> (width: Int, height: Int) {
        guard let aspect = aspect(width: width, height: height), aspect != .square, rec.0 > 0, rec.1 > 0 else { return rec }
        return size(aspect, resolution: Int((Double(rec.0) * Double(rec.1)).squareRoot().rounded()), multiple: multiple)
    }

    /// Whether two sizes have the same shape (within 2 %).
    static func sameAspect(_ a: (Int, Int), _ b: (Int, Int)) -> Bool {
        guard a.0 > 0, a.1 > 0, b.0 > 0, b.1 > 0 else { return false }
        let x = Double(a.0) / Double(a.1), y = Double(b.0) / Double(b.1)
        return abs(x - y) / y < 0.02
    }

    // MARK: Fixed-size models (Core ML)

    /// What a model compiled for [native] does with a request for [requested].
    struct FixedPlan: Equatable, Sendable {
        let nativeWidth: Int
        let nativeHeight: Int
        /// The requested aspect cut from the middle of the native canvas (top-left origin).
        let crop: CGRect
        var width: Int { Int(crop.width) }
        var height: Int { Int(crop.height) }
        var cropped: Bool { width != nativeWidth || height != nativeHeight }
    }

    /// The largest rectangle of [requested]'s aspect centred in [native] (sides multiples of 8).
    static func fixed(native: (width: Int, height: Int), requested: (width: Int, height: Int)) -> FixedPlan {
        let nw = max(native.width, 8), nh = max(native.height, 8)
        guard requested.width > 0, requested.height > 0, !sameAspect((nw, nh), (requested.width, requested.height)) else {
            return FixedPlan(nativeWidth: nw, nativeHeight: nh, crop: CGRect(x: 0, y: 0, width: nw, height: nh))
        }
        let ratio = Double(requested.width) / Double(requested.height)
        var w = Double(nw), h = w / ratio
        if h > Double(nh) { h = Double(nh); w = h * ratio }
        func floor8(_ v: Double, _ limit: Int) -> Int { min(max(Int((v / 8).rounded()) * 8, 8), limit) }
        let cw = floor8(w, nw), ch = floor8(h, nh)
        return FixedPlan(nativeWidth: nw, nativeHeight: nh, crop: CGRect(x: (nw - cw) / 2, y: (nh - ch) / 2, width: cw, height: ch))
    }

    /// "compiled for 512² · other shapes are cut from the square" (the Tune line of a fixed model).
    static func compiledNote(_ native: (width: Int, height: Int)) -> String {
        native.width == native.height ? "compiled for \(native.width)² · other shapes are cut from the square"
            : "compiled for \(native.width)×\(native.height) · other shapes are cut from it"
    }

    /// The size a fixed-size model will deliver for [aspect].
    static func fixedSize(native: (width: Int, height: Int), aspect: AspectRatio) -> (width: Int, height: Int) {
        let plan = fixed(native: native, requested: (aspect.w * 64, aspect.h * 64))
        return (plan.width, plan.height)
    }

    // MARK: Pixels

    /// The pixel size an encoded image really has (read from its header, nothing decoded).
    static func pixelSize(_ data: Data) -> (width: Int, height: Int)? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue,
              let h = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue, w > 0, h > 0 else { return nil }
        return (w, h)
    }

    /// [image] cut to [rect] (top-left origin).
    static func crop(_ image: CGImage, to rect: CGRect) -> CGImage? {
        if Int(rect.width) == image.width && Int(rect.height) == image.height { return image }
        // A model that returned another size than its compiled one: cut the same share of it.
        let sx = CGFloat(image.width) / (rect.maxX + rect.minX), sy = CGFloat(image.height) / (rect.maxY + rect.minY)
        let scaled = CGRect(x: rect.minX * sx, y: rect.minY * sy, width: rect.width * sx, height: rect.height * sy).integral
        return image.cropping(to: scaled)
    }

    /**
     An img2img input of the requested aspect placed on a fixed-size model's canvas: the image
     fills [plan]'s crop rectangle exactly, and the margins around it hold the same picture scaled
     to cover the canvas, so the model sees one continuous scene and the cut-out matches the input.
     */
    static func embed(_ image: CGImage, in plan: FixedPlan) -> CGImage? {
        guard let ctx = canvas(plan) else { return nil }
        let full = CGRect(x: 0, y: 0, width: plan.nativeWidth, height: plan.nativeHeight)
        let scale = max(full.width / CGFloat(image.width), full.height / CGFloat(image.height))
        let w = CGFloat(image.width) * scale, h = CGFloat(image.height) * scale
        ctx.draw(image, in: CGRect(x: (full.width - w) / 2, y: (full.height - h) / 2, width: w, height: h))
        ctx.draw(image, in: flipped(plan.crop, height: plan.nativeHeight))
        return ctx.makeImage()
    }

    /// A white-on-black inpaint mask placed in [plan]'s crop rectangle; the margins are kept (black).
    static func embedMask(_ mask: CGImage, in plan: FixedPlan) -> CGImage? {
        guard let ctx = canvas(plan) else { return nil }
        ctx.setFillColor(CGColor(gray: 0, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: plan.nativeWidth, height: plan.nativeHeight))
        ctx.draw(mask, in: flipped(plan.crop, height: plan.nativeHeight))
        return ctx.makeImage()
    }

    private static func canvas(_ plan: FixedPlan) -> CGContext? {
        let ctx = CGContext(data: nil, width: plan.nativeWidth, height: plan.nativeHeight, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
        ctx?.interpolationQuality = .high
        return ctx
    }

    /// Core Graphics draws bottom-up.
    private static func flipped(_ rect: CGRect, height: Int) -> CGRect {
        CGRect(x: rect.minX, y: CGFloat(height) - rect.maxY, width: rect.width, height: rect.height)
    }
}

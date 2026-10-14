import CoreGraphics
import Foundation

/**
 One brush stroke in image-normalized coordinates (0…1 on both axes), so strokes survive
 resolution changes and render identically on screen and in the exported mask.
 [radius] is a fraction of the image width.
 */
struct MaskStroke: Equatable, Sendable {
    var points: [CGPoint]
    var radius: CGFloat
    var erase: Bool
}

/// Undoable stroke list plus an invert flag.
struct MaskState: Equatable, Sendable {
    var strokes: [MaskStroke] = []
    var redo: [MaskStroke] = []
    var inverted = false

    var isEmpty: Bool { !strokes.contains { !$0.erase } && !inverted }

    func add(_ stroke: MaskStroke) -> MaskState { MaskState(strokes: strokes + [stroke], redo: [], inverted: inverted) }
    func undo() -> MaskState {
        guard let last = strokes.last else { return self }
        return MaskState(strokes: Array(strokes.dropLast()), redo: redo + [last], inverted: inverted)
    }

    func redoLast() -> MaskState {
        guard let last = redo.last else { return self }
        return MaskState(strokes: strokes + [last], redo: Array(redo.dropLast()), inverted: inverted)
    }

    func clear() -> MaskState { MaskState() }
    func invert() -> MaskState { MaskState(strokes: strokes, redo: redo, inverted: !inverted) }
}

enum MaskRasterizer {
    /**
     Renders [state] at [width]×[height] in the backend's [encoding]: the painted area (after
     invert) is white; the rest is black (opaque) or transparent (alpha).
     */
    static func render(_ state: MaskState, width: Int, height: Int, encoding: MaskEncoding) -> CGImage? {
        // Coverage pass: alpha only.
        guard let coverage = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue,
        ) else { return nil }
        // Draw in top-left-origin coordinates like the on-screen overlay.
        coverage.translateBy(x: 0, y: CGFloat(height))
        coverage.scaleBy(x: 1, y: -1)
        coverage.setLineCap(.round)
        coverage.setLineJoin(.round)
        for stroke in state.strokes {
            coverage.setLineWidth(stroke.radius * CGFloat(width) * 2)
            coverage.setBlendMode(stroke.erase ? .clear : .normal)
            coverage.setStrokeColor(CGColor(gray: 1, alpha: 1))
            let path = CGMutablePath()
            for (i, p) in stroke.points.enumerated() {
                let point = CGPoint(x: p.x * CGFloat(width), y: p.y * CGFloat(height))
                if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
            }
            if stroke.points.count == 1, let p = stroke.points.first {
                path.addLine(to: CGPoint(x: p.x * CGFloat(width) + 0.01, y: p.y * CGFloat(height)))
            }
            coverage.addPath(path)
            coverage.strokePath()
        }
        guard let data = coverage.data else { return nil }
        let pixels = data.bindMemory(to: UInt8.self, capacity: width * height * 4)
        var out = [UInt8](repeating: 0, count: width * height * 4)
        for i in 0 ..< width * height {
            let painted = pixels[i * 4 + 3] >= 128
            let repaint = painted != state.inverted
            switch encoding {
            case .opaqueBlackWhite:
                let v: UInt8 = repaint ? 255 : 0
                out[i * 4] = v; out[i * 4 + 1] = v; out[i * 4 + 2] = v; out[i * 4 + 3] = 255
            case .alphaWhite:
                let v: UInt8 = repaint ? 255 : 0
                out[i * 4] = v; out[i * 4 + 1] = v; out[i * 4 + 2] = v; out[i * 4 + 3] = v
            }
        }
        return out.withUnsafeMutableBytes { buffer in
            CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue,
            )?.makeImage()
        }
    }
}

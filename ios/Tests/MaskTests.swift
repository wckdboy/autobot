import CoreGraphics
import Foundation
import Testing
@testable import Autobot

struct MaskTests {
    /// RGBA bytes of [image].
    private func pixels(_ image: CGImage) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: image.width * image.height * 4)
        out.withUnsafeMutableBytes { buffer in
            let ctx = CGContext(data: buffer.baseAddress, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: image.width * 4,
                                space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        }
        return out
    }

    /// Pixel at image coordinates (top-left origin).
    private func px(_ p: [UInt8], _ w: Int, _ x: Int, _ y: Int) -> (UInt8, UInt8) {
        let i = (y * w + x) * 4
        return (p[i], p[i + 3])
    }

    private let stroke = MaskStroke(points: [CGPoint(x: 0.25, y: 0.25)], radius: 0.1, erase: false)

    @Test func opaqueEncodingIsWhiteOnBlack() throws {
        let image = try #require(MaskRasterizer.render(MaskState().add(stroke), width: 64, height: 64, encoding: .opaqueBlackWhite))
        let p = pixels(image)
        #expect(px(p, 64, 16, 16) == (255, 255))
        #expect(px(p, 64, 48, 48) == (0, 255))
    }

    @Test func alphaEncodingIsTransparentWhereKept() throws {
        let image = try #require(MaskRasterizer.render(MaskState().add(stroke), width: 64, height: 64, encoding: .alphaWhite))
        let p = pixels(image)
        #expect(px(p, 64, 16, 16) == (255, 255))
        #expect(px(p, 64, 48, 48).1 == 0)
    }

    @Test func invertAndEraseFlipCoverage() throws {
        let erased = MaskState().add(stroke).add(MaskStroke(points: [CGPoint(x: 0.25, y: 0.25)], radius: 0.05, erase: true))
        let a = pixels(try #require(MaskRasterizer.render(erased, width: 64, height: 64, encoding: .opaqueBlackWhite)))
        #expect(px(a, 64, 16, 16).0 == 0)
        let inverted = MaskState().add(stroke).invert()
        let b = pixels(try #require(MaskRasterizer.render(inverted, width: 64, height: 64, encoding: .opaqueBlackWhite)))
        #expect(px(b, 64, 16, 16).0 == 0)
        #expect(px(b, 64, 48, 48).0 == 255)
    }

    @Test func undoRedoAndEmptiness() {
        var m = MaskState()
        #expect(m.isEmpty)
        m = m.add(stroke)
        #expect(!m.isEmpty)
        m = m.undo()
        #expect(m.isEmpty && m.redo.count == 1)
        m = m.redoLast()
        #expect(m.strokes.count == 1 && m.redo.isEmpty)
        #expect(!MaskState().invert().isEmpty)
        #expect(MaskState().add(MaskStroke(points: [.zero], radius: 0.1, erase: true)).isEmpty)
    }
}

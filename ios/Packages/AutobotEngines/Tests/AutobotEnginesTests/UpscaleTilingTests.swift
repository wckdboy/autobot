import CoreGraphics
import Foundation
import Testing

@testable import AutobotEngines

@Suite struct UpscaleTilingTests {
    let tiling = UpscaleTiling(tile: 512, overlap: 32, scale: 4)

    @Test func smallImageIsOneClippedTile() {
        let tiles = tiling.tiles(width: 300, height: 200)
        #expect(tiles == [.init(x: 0, y: 0, width: 300, height: 200, overlapLeft: 0, overlapTop: 0)])
        #expect(tiling.weight(tiles[0], dx: 0, dy: 0) == 1)
    }

    @Test func exactFitIsOneTile() {
        #expect(tiling.tiles(width: 512, height: 512).count == 1)
    }

    @Test func tilesCoverTheImageAndStayInside() {
        for (w, h) in [(513, 512), (1024, 768), (1000, 1999), (2048, 2048)] {
            let tiles = tiling.tiles(width: w, height: h)
            #expect(tiles.allSatisfy { $0.x >= 0 && $0.y >= 0 && $0.x + $0.width <= w && $0.y + $0.height <= h && $0.width == 512 && $0.height == 512 })
            var missing = 0
            for y in stride(from: 0, to: h, by: 5) {
                for x in stride(from: 0, to: w, by: 5) where !tiles.contains(where: { x >= $0.x && x < $0.x + $0.width && y >= $0.y && y < $0.y + $0.height }) {
                    missing += 1
                }
            }
            #expect(missing == 0, "uncovered pixels in \(w)×\(h)")
            // Corners and the far edge are covered.
            #expect(tiles.contains { $0.x + $0.width == w && $0.y + $0.height == h })
        }
    }

    @Test func overlapsAreAtLeastTheConfiguredOverlap() {
        // 1024: stride 480 → origins 0, 480, then the last tile pulled back to 512.
        let xs = tiling.tiles(width: 1024, height: 512).map(\.x)
        #expect(xs == [0, 480, 512])
        let tiles = tiling.tiles(width: 1024, height: 512)
        #expect(tiles.map(\.overlapLeft) == [0, 32, 480])
        #expect(tiles.allSatisfy { $0.overlapTop == 0 })
    }

    @Test func rampCrossFadesTheSharedRegionOnly() {
        let t = UpscaleTiling.Tile(x: 480, y: 0, width: 512, height: 512, overlapLeft: 32, overlapTop: 0)
        let span = 32 * 4
        #expect(tiling.weight(t, dx: 0, dy: 10) < 0.01)
        #expect(abs(tiling.weight(t, dx: span / 2, dy: 10) - 0.5) < 0.01)
        #expect(tiling.weight(t, dx: span - 1, dy: 10) > 0.99)
        #expect(tiling.weight(t, dx: span, dy: 10) == 1)
        let corner = UpscaleTiling.Tile(x: 480, y: 480, width: 512, height: 512, overlapLeft: 32, overlapTop: 32)
        #expect(abs(tiling.weight(corner, dx: span / 2, dy: span / 2) - 0.25) < 0.01)
    }

    /// Runs the real model when it is present (set AUTOBOT_UPSCALER=<folder with the .mlpackage>).
    @Test func upscalesWithTheRealModel() async throws {
        guard let path = ProcessInfo.processInfo.environment["AUTOBOT_UPSCALER"] else { return }
        let side = 600
        let ctx = CGContext(data: nil, width: side, height: side, bitsPerComponent: 8, bytesPerRow: 0, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(CGColor(srgbRed: 0.9, green: 0.3, blue: 0.1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
        ctx.setFillColor(CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1))
        ctx.fillEllipse(in: CGRect(x: 150, y: 150, width: 300, height: 300))
        let out = try await UpscaleEngine.shared.upscale(folder: URL(fileURLWithPath: path), image: ctx.makeImage()!)
        #expect(out.width == side * 4 && out.height == side * 4)
    }
}

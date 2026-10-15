import CoreGraphics
import QuartzCore
import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Per-frame draw cost of the ink elements, v2 (as shipped before v3, reproduced here) against v3:
 building the frame's paths and filling them into a 3× bitmap, the work a Canvas does per tick.
 A and B alternate every iteration so machine load hits both alike; medians in µs. Skipped
 unless `AUTOBOT_PERF_DIR` is set; writes bench.json.
 */
@MainActor
struct MotionBenchTests {
    struct Row: Codable {
        var element: String
        var v2Micros: Double
        var v3Micros: Double
        var v2Dots: Int
        var v3Dots: Int
    }

    @Test func frameCost() throws {
        guard let dir = ProcessInfo.processInfo.environment["AUTOBOT_PERF_DIR"] else { return }
        var rows: [Row] = []

        // Living paper behind a full screen (empty chat list / gallery / lock).
        let screen = CGSize(width: 393, height: 852)
        let grid = InkDotGrid()
        rows.append(compare("ground 393×852", size: screen, v2: { t in
            let pitch = InkMath.dotCell(width: 393, height: 852, base: 12)
            let field = HalftonePaths.field(size: screen, cell: pitch, phase: t * 0.04, fill: 1)
            return [field, field] // the field and its breathing register plate
        }, v3: { t in
            grid.layout(screen, base: 12, maxDots: Int(Tokens.Motion.cardMaxDots), fade: .center)
            return [InkField.path(grid, seconds: t, period: 48, scale: 0.4)]
        }))

        // Run: v2 drew the paper behind the hero (240pt, under a gradient mask); v3 animates the
        // three text mode cards.
        let cards = [CGSize(width: 175, height: 130), CGSize(width: 175, height: 130), CGSize(width: 361, height: 100)].map { size -> InkDotGrid in
            let g = InkDotGrid()
            g.layout(size, base: 11, maxDots: 600, fade: UnitPoint(x: 0.2, y: 0.95))
            return g
        }
        rows.append(compare("run hero", size: CGSize(width: 393, height: 240), v2: { t in
            let size = CGSize(width: 393, height: 240)
            let field = HalftonePaths.field(size: size, cell: InkMath.dotCell(width: 393, height: 240, base: 12), phase: t * 0.04, fill: 1)
            return [field, field]
        }, v3: { t in cards.map { InkField.path($0, seconds: t, period: 12) } }))

        // A running 3pt progress bar, 361pt wide.
        rows.append(compare("bar running", size: CGSize(width: 361, height: 9), v2: { t in
            [V2.barFill(size: CGSize(width: 361, height: 3), width: 180), V2.head(t)]
        }, v3: { t in [Self.bar(.progress(0.5, running: true), t)] }))

        rows.append(compare("bar loading", size: CGSize(width: 361, height: 4.5), v2: { t in
            [V2.sweep(size: CGSize(width: 361, height: 3), t: InkMath.cycle(t / 1.4))]
        }, v3: { t in [Self.bar(.indeterminate, t)] }))

        rows.append(compare("loader inline", size: CGSize(width: 21, height: 12), v2: { t in
            [V2.loader(side: 12, count: 4, t: InkMath.cycle(t / 1.1))]
        }, v3: { t in [InkLoader.dots(.inline, in: CGSize(width: 21, height: 12), seconds: t)] }))

        // A flowing proof plate (175pt square at the image cell): the field per dot per frame.
        rows.append(compare("plate flow", size: CGSize(width: 175, height: 175), v2: { t in
            [V2.plateFlow(size: CGSize(width: 175, height: 175), phase: t * 0.36, precomputed: false)]
        }, v3: { t in
            [V2.plateFlow(size: CGSize(width: 175, height: 175), phase: t * 0.36, precomputed: true)]
        }))

        try JSONEncoder().encode(rows).write(to: URL(fileURLWithPath: "\(dir)/bench.json"))
        for r in rows {
            print(String(format: "BENCH %@ v2=%.0fµs (%d dots) v3=%.0fµs (%d dots)", r.element, r.v2Micros, r.v2Dots, r.v3Micros, r.v3Dots))
        }
    }

    private static func bar(_ state: InkBarState, _ t: Double) -> Path {
        // InkBar.draw fills into a GraphicsContext; rebuild its geometry for the bitmap.
        let d = 4.5, (count, pitch, first) = InkMath.barLayout(width: 361, diameter: d)
        let head = InkMath.barHead(fraction: state.value, count: count)
        var path = Path()
        for i in 0 ..< count {
            let c = Double(i) + 0.5
            var s: Double
            if state == .indeterminate {
                s = InkMath.barLoadingScale(slot: c, seconds: t)
            } else {
                s = max(InkMath.barFillScale(slot: c, head: head) * InkMath.barRunningScale(slot: c, seconds: t), Double(Tokens.Motion.barEmptyDotScale))
            }
            let r = d / 2 * s, cx = first + Double(i) * pitch
            path.addEllipse(in: CGRect(x: cx - r, y: d / 2 - r, width: r * 2, height: r * 2))
        }
        return path
    }

    private func compare(_ name: String, size: CGSize, v2: (Double) -> [Path], v3: (Double) -> [Path]) -> Row {
        let scale: CGFloat = 3
        let context = CGContext(data: nil, width: Int(size.width * scale), height: Int(size.height * scale), bitsPerComponent: 8, bytesPerRow: 0,
                                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.scaleBy(x: scale, y: scale)
        context.setFillColor(UIColor.red.cgColor)
        func run(_ frame: (Double) -> [Path], _ t: Double) -> Double {
            let start = CACurrentMediaTime()
            context.clear(CGRect(origin: .zero, size: size))
            for path in frame(t) {
                context.addPath(path.cgPath)
                context.fillPath()
            }
            return (CACurrentMediaTime() - start) * 1e6
        }
        var a: [Double] = [], b: [Double] = []
        for i in 0 ..< 240 {
            let t = Double(i) / 30
            a.append(run(v2, t))
            b.append(run(v3, t))
        }
        func median(_ xs: [Double]) -> Double { xs.sorted()[xs.count / 2] }
        func dots(_ frame: (Double) -> [Path]) -> Int {
            var n = 0
            for p in frame(0.5) { p.forEach { if case .move = $0 { n += 1 } } }
            return n
        }
        return Row(element: name, v2Micros: median(a), v3Micros: median(b), v2Dots: dots(v2), v3Dots: dots(v3))
    }
}

/// The v2 drawing code (before v3), for the comparison.
@MainActor
private enum V2 {
    static func barFill(size: CGSize, width: CGFloat) -> Path {
        path(size: size, width: width) { 0.3 + 0.7 * pow(InkMath.clamp01(Double($0 / width)), 1.6) }
    }

    static func sweep(size: CGSize, t: Double) -> Path {
        path(size: size, width: size.width) { x in
            let u = Double(x / size.width), head = InkMath.easeInOutCubic(t) * 1.44, tail = head - 0.2
            if u > head { return 0 }
            if u >= tail { return 1 }
            let behind = (tail - u) / 0.24
            return behind >= 1 ? 0 : (1 - behind) * (1 - behind)
        }
    }

    static func path(size: CGSize, width: CGFloat, density: (CGFloat) -> Double) -> Path {
        let pitch = max(min(size.height, 4), 2)
        let rows = max(1, Int((size.height / pitch).rounded(.down)))
        let oy = (size.height - CGFloat(rows) * pitch) / 2
        let cols = Int((width / pitch).rounded(.up))
        var path = Path()
        for col in 0 ..< cols {
            let cx = (CGFloat(col) + 0.5) * pitch
            let d = density(min(cx, width))
            guard d > 0.02 else { continue }
            let radius = pitch * 0.5 * CGFloat(0.4 + 0.85 * d)
            for r in 0 ..< rows {
                let cy = oy + (CGFloat(r) + 0.5) * pitch
                path.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
            }
        }
        return path
    }

    /// The flowing head cluster (18 × 9, 3 × 6 dots).
    static func head(_ t: Double) -> Path {
        var path = Path()
        for r in 0 ..< 3 {
            for col in 0 ..< 6 {
                let distance = Double(col) / 5
                let wave = 0.5 + 0.5 * sin((Double(col) * 0.7 + Double(r) * 0.5 - t * 0.72) * .pi)
                let radius = 1.5 * CGFloat((1 - distance) * (0.35 + 0.65 * wave))
                guard radius > 0.25 else { continue }
                let cx = 180 + (CGFloat(col) + 0.5) * 3, cy = 4.5 + (CGFloat(r) - 1) * 3
                path.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
            }
        }
        return path
    }

    static func loader(side: CGFloat, count n: Int, t: Double) -> Path {
        let cell = side / CGFloat(n)
        var path = Path()
        for r in 0 ..< n {
            for col in 0 ..< n {
                let x = (Double(col) + 0.5) / Double(n), y = (Double(r) + 0.5) / Double(n)
                let center = -0.34 + 1.68 * InkMath.smoothstep(t)
                let d = ((x + y) / 2 - center) / 0.34
                let radius = cell * 0.5 * CGFloat(0.4 + 0.68 * exp(-2 * d * d))
                let cx = (CGFloat(col) + 0.5) * cell, cy = (CGFloat(r) + 0.5) * cell
                path.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
            }
        }
        return path
    }

    private static var plateCache: (args: [Double], statics: [Double])?

    /// A flowing plate frame: v2 evaluates the whole field per dot; v3 reads cached constants.
    static func plateFlow(size: CGSize, phase: Double, precomputed: Bool) -> Path {
        let pitch = InkMath.dotCell(width: size.width, height: size.height, base: Tokens.Halftone.imageCell)
        let cols = Int(size.width / pitch), rows = Int(size.height / pitch)
        let maxRadius = Double(pitch * Tokens.Halftone.imageRadiusFactor) * 0.7
        typealias H = Tokens.Halftone
        if precomputed && plateCache == nil {
            var args: [Double] = [], statics: [Double] = []
            for r in 0 ..< rows {
                let y = (Double(r) + 0.5) / Double(rows)
                for c in 0 ..< cols {
                    let x = (Double(c) + 0.5) / Double(cols)
                    args.append((x * Double(H.fieldWaveX) + y * Double(H.fieldWaveY)) * .pi)
                    let dx = x - Double(H.fieldBlobX), dy = y - Double(H.fieldBlobY)
                    let blob = 1 - min(1, (dx * dx * Double(H.fieldBlobWeightX) + dy * dy * Double(H.fieldBlobWeightY)).squareRoot())
                    statics.append(Double(H.fieldBlobMix) * blob - Double(H.fieldFalloffY) * y)
                }
            }
            plateCache = (args, statics)
        }
        var path = Path()
        for r in 0 ..< rows {
            let y = (Double(r) + 0.5) / Double(rows)
            for c in 0 ..< cols {
                let i = r * cols + c
                let v = precomputed
                    ? InkMath.clamp01(Double(H.fieldWaveMix) * (0.5 + 0.5 * sin(plateCache!.args[i] - phase * .pi)) + plateCache!.statics[i])
                    : InkMath.fieldValue(x: (Double(c) + 0.5) / Double(cols), y: y, phase: phase)
                let radius = CGFloat(v * maxRadius)
                guard radius > H.imageMinRadius else { continue }
                let cx = (CGFloat(c) + 0.5) * pitch, cy = (CGFloat(r) + 0.5) * pitch
                path.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
            }
        }
        return path
    }
}

import Foundation
import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Dot precision: the pure geometry ([InkGrid], the bar and card formulas, the wave clock) and
 raster checks. Each ink component is rendered with ImageRenderer at several sizes and at 1×,
 2× and 3×; dot centroids and radii are measured from the pixels and compared with the geometry:
 equal spacing, symmetric margins, vertical centring, the radius of every slot and no clipping,
 all within half a device pixel. With `AUTOBOT_SNAPSHOT_DIR` set the renders and a report of the
 measured errors are kept.
 */
@Suite(.serialized, .timeLimit(.minutes(3)))
@MainActor
struct InkPrecisionTests {
    // MARK: Pure geometry

    @Test func axisHasWholePixelPitchAndIsCentred() {
        for length in stride(from: 20.0, through: 1200, by: 7.25) {
            for pitch in [3, 6, 11, 19, 33] {
                for margin in [0.0, 1.5, 2, 5.5, Double(pitch) / 2] {
                    for odd in [false, true] {
                        let a = InkGrid.axis(lengthPx: length, pitchPx: pitch, marginPx: margin, oddDiameter: odd)
                        guard length - 2 * margin >= 0 else {
                            #expect(a.count == 0)
                            continue
                        }
                        // N = ⌊(L − 2m) / p⌋ + 1.
                        #expect(a.count == Int(((length - 2 * margin) / Double(pitch)).rounded(.down)) + 1)
                        #expect(a.pitchPx == pitch)
                        // Centred within half a pixel, snapped to the pixel (or pixel-centre) grid.
                        #expect(a.centringErrorPx <= 0.5 + 1e-9)
                        let frac = a.firstPx - a.firstPx.rounded(.down)
                        #expect(abs(frac - (odd ? 0.5 : 0)) < 1e-9)
                        // Every centre keeps its margin when the leftover allows it (never a clipped dot).
                        let leftover = length - 2 * margin - Double((a.count - 1) * pitch)
                        if leftover >= 1 {
                            #expect(a.leadingMarginPx >= margin - 1e-9 && a.trailingMarginPx >= margin - 1e-9)
                        }
                    }
                }
            }
        }
    }

    @Test func barGeometryFollowsTheTokens() {
        for scale in [1.0, 2, 3] {
            for height in [2.0, 3, 4, 6] {
                for width in [64.0, 120, 243, 361, 402.5] {
                    let bar = InkGrid.bar(widthPt: width, height: height, scale: scale)
                    let d = Int((max(height * 1.5, 4) * scale).rounded())
                    #expect(bar.diameterPx == d)
                    #expect(bar.axis.pitchPx == Int((Double(d) * Double(Tokens.Motion.barDotPitch)).rounded()))
                    // The outer dots touch the ends: margin d/2, the leftover split evenly.
                    #expect(bar.axis.count == Int(((width * scale - Double(d)) / Double(bar.axis.pitchPx)).rounded(.down)) + 1)
                    #expect(bar.axis.centringErrorPx <= 0.5)
                    #expect(bar.axis.leadingMarginPx >= Double(d) / 2 - 1e-9 && bar.axis.trailingMarginPx >= Double(d) / 2 - 1e-9)
                    // Vertically centred on a pixel boundary (even) or a pixel centre (odd).
                    #expect(bar.centerYPx * 2 == Double(d))
                    #expect(abs(bar.heightPt * scale - Double(d)) < 1e-9)
                    // The view's frame and the layout agree.
                    #expect(abs(Double(InkProgressBar.diameter(CGFloat(height), scale: CGFloat(scale))) - bar.heightPt) < 1e-9)
                }
            }
        }
    }

    @Test func barRadiiFollowTheFormulasExactly() {
        let full = 6.0, n = 40
        let pin = full * Double(Tokens.Motion.barEmptyDotScale)
        // Empty: every slot a pin.
        for slot in InkBar.radii(count: n, fullRadius: full, state: .progress(0, running: false), fraction: 0, seconds: 0) {
            #expect(slot?.radius == pin && slot?.filled == false)
        }
        // Filled slots: barFillStart + (1 − barFillStart)·min(c/head, 1), eased in at the head.
        for fraction in [0.1, 0.37, 0.5, 0.83, 1] {
            let head = InkMath.barHead(fraction: fraction, count: n)
            // The head is linear in progress (no rounding).
            #expect(abs(head - fraction * (Double(n) + InkMath.barHeadSlots - 0.5)) < 1e-12)
            let slots = InkBar.radii(count: n, fullRadius: full, state: .progress(fraction, running: false), fraction: fraction, seconds: 0)
            for (i, slot) in slots.enumerated() {
                let c = Double(i) + 0.5
                let start = Double(Tokens.Motion.barFillStartScale)
                let expected = (start + (1 - start) * min(c / head, 1)) * InkMath.smoothstep((head - c + 0.5) / InkMath.barHeadSlots) * full
                #expect(abs(slot!.radius - max(expected, pin)) < 1e-12)
            }
        }
        // Loading and running waves: `barWaveSlots` long, the running amplitude `barRunningWaveAmplitude`.
        let t = 0.37, slots = Double(Tokens.Motion.barWaveSlots)
        for (i, slot) in InkBar.radii(count: n, fullRadius: full, state: .indeterminate, fraction: 0, seconds: t).enumerated() {
            let w = 0.5 + 0.5 * sin(2 * .pi * ((Double(i) + 0.5) / slots - t / InkMath.loaderCycleSeconds))
            #expect(abs(slot!.radius - full * (pin / full + (1 - pin / full) * w * w)) < 1e-12)
        }
        let running = InkBar.radii(count: n, fullRadius: full, state: .progress(0.9, running: true), fraction: 0.9, seconds: t)
        let still = InkBar.radii(count: n, fullRadius: full, state: .progress(0.9, running: false), fraction: 0.9, seconds: t)
        for i in 0 ..< n {
            let w = InkMath.barWave(slot: Double(i) + 0.5, seconds: t)
            #expect(abs(running[i]!.radius - max(still[i]!.radius * (1 - Double(Tokens.Motion.barRunningWaveAmplitude) * (1 - w)), pin)) < 1e-12)
        }
    }

    @Test func cardFieldIsExactAndNeverOverlaps() {
        let base = Double(Tokens.Motion.cardDotPitch), cap = Int(Tokens.Motion.cardMaxDots)
        for scale in [1.0, 2, 3] {
            for (w, h) in [(160.0, 130.0), (183, 268), (361, 100), (393, 852), (1024, 1366)] {
                let grid = InkDotGrid()
                grid.layout(CGSize(width: w, height: h), base: base, maxDots: cap, fade: nil, scale: scale)
                let pitchPx = grid.pitch * scale
                #expect(abs(pitchPx - pitchPx.rounded()) < 1e-9)
                #expect(grid.count <= cap)
                if (w / base) * (h / base) <= Double(cap) { #expect(abs(grid.pitch - base) < 1e-9) }
                let cols = grid.columns!, rows = grid.rows!
                #expect(cols.count == Int((w * scale / pitchPx).rounded(.down)))
                #expect(cols.centringErrorPx <= 0.5 && rows.centringErrorPx <= 0.5)
                // Max diameter < pitch (no overlap), r ≥ 0, every dot inside the surface.
                for cycle in stride(from: 0.0, to: 1, by: 0.05) {
                    let radii = InkField.radii(grid, cycle: cycle)
                    #expect(radii.allSatisfy { $0 >= 0 && 2 * $0 < grid.pitch })
                    for (i, r) in radii.enumerated() {
                        #expect(grid.xs[i] - r >= 0 && grid.xs[i] + r <= w && grid.ys[i] - r >= 0 && grid.ys[i] + r <= h)
                    }
                }
            }
        }
    }

    @Test func cardWaveHasAnExactPeriodAndAFixedWaveVector() {
        let period = Double(Tokens.Motion.cardWavePeriodSeconds)
        // r = base·(0.62 + 0.38·sin(kx·x + ky·y − ωt)), ω = 2π / period.
        for (x, y) in [(0.0, 0.0), (37.5, 81), (300, 12)] {
            for t in [0.0, 1.3, 7.9, 811_000_123.4] {
                let r = InkGrid.cardRadius(base: 4, x: x, y: y, cycle: InkMath.cycle(t / period))
                let reference = 4 * (0.62 + 0.38 * sin(InkGrid.cardKx * x + InkGrid.cardKy * y - 2 * .pi * InkMath.cycle(t / period)))
                #expect(abs(r - reference) < 1e-12)
                #expect(abs(InkMath.cardWave(phase: 1, seconds: t, period: period) - InkMath.cardWave(phase: 1, seconds: t + period, period: period)) < 1e-9)
                #expect(r >= 0.24 * 4 - 1e-12 && r <= 4 + 1e-12)
            }
        }
        // A dot keeps its phase when the surface resizes: the phase is kx·x + ky·y in points.
        let a = InkDotGrid(), b = InkDotGrid()
        a.layout(CGSize(width: 160, height: 130), base: 11, maxDots: 600, fade: nil, scale: 3)
        b.layout(CGSize(width: 182, height: 130), base: 11, maxDots: 600, fade: nil, scale: 3)
        for i in [0, 5, 17] {
            #expect(abs(a.phases[i] - (InkGrid.cardKx * a.xs[i] + InkGrid.cardKy * a.ys[i])) < 1e-12)
            #expect(abs(b.phases[i] - (InkGrid.cardKx * b.xs[i] + InkGrid.cardKy * b.ys[i])) < 1e-12)
        }
    }

    @Test func waveClockIsContinuousAcrossPausesAndExactWhileRunning() {
        let period = Double(Tokens.Motion.cardWavePeriodSeconds)
        let clock = InkWaveClock()
        let t0 = Date(timeIntervalSinceReferenceDate: 800_000_000)
        clock.advance(to: t0, period: period)
        // Running at the ambient rate for exactly one period: back where it started. Dates this far
        // from the reference resolve to ~0.1 µs, so 1e-6 of a cycle (12 µs) is the honest bound.
        let step = InkMath.ambientFrameInterval
        var t = t0
        for _ in 0 ..< Int((period / step).rounded()) {
            t = t.addingTimeInterval(step)
            clock.advance(to: t, period: period)
        }
        #expect(abs(clock.cycle) < 1e-6 || abs(clock.cycle - 1) < 1e-6, "cycle \(clock.cycle)")
        // A pause of a minute moves the wave at most one frame.
        let before = clock.cycle
        clock.advance(to: t.addingTimeInterval(60), period: period)
        #expect(InkMath.cycle(clock.cycle - before) <= InkWaveClock.maxStep / period + 1e-12)
        // A resize relays the grid but keeps the clock (and so the wave's place).
        let grid = InkDotGrid()
        grid.layout(CGSize(width: 160, height: 130), base: 11, maxDots: 600, fade: nil, scale: 3)
        grid.clock.advance(to: t0, period: period)
        grid.clock.advance(to: t0.addingTimeInterval(0.1), period: period)
        let kept = grid.clock.cycle
        grid.layout(CGSize(width: 200, height: 130), base: 11, maxDots: 600, fade: nil, scale: 3)
        #expect(grid.clock.cycle == kept)
    }

    // MARK: Raster

    /// A measured dot: centroid and radius (from the inked area), in device pixels.
    struct Blob {
        var x: Double
        var y: Double
        var r: Double
        var minX: Int
        var maxX: Int
        var minY: Int
        var maxY: Int
    }

    /// The worst errors seen per check, for the report.
    private static var worst: [String: Double] = [:]

    private func record(_ key: String, _ value: Double) {
        Self.worst[key] = max(Self.worst[key] ?? 0, value)
    }

    private var dir: String? { ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] }

    /// How long a blank render is retried (see [raster]).
    static let renderDeadline: Duration = .seconds(90)

    /// Set once a render stayed blank for [renderDeadline]: later blank renders fail at once.
    private static var rendererStuck = false

    /**
     Renders [view] (black ink on clear) at [scale] and returns its alpha (0…1) row by row.

     ImageRenderer rasterises through RenderBox on the GPU. On a loaded machine (typically while
     its pipelines are first prepared in the process) a render can block for many seconds and then
     return a fully transparent picture: a renderer timeout, not a drawing. Every subject in this
     suite has ink (even an empty bar's pins), so a blank picture is rendered again, for up to
     [renderDeadline], instead of being measured; a subject that really draws nothing still fails.
     Each render first yields the main actor, so the `@MainActor` suites running alongside are not
     starved while this one rasterises.
     */
    private func raster(_ view: some View, size: CGSize, scale: Double, name: String) async throws -> (alpha: [Double], width: Int, height: Int) {
        let clock = ContinuousClock()
        let deadline = clock.now + Self.renderDeadline
        var attempts = 0
        while true {
            await Task.yield()
            attempts += 1
            let renderer = ImageRenderer(content: view.frame(width: size.width, height: size.height).environment(\.displayScale, CGFloat(scale)))
            renderer.scale = CGFloat(scale)
            renderer.isOpaque = false
            let image = try #require(renderer.cgImage)
            let (alpha, w, h) = Self.alpha(image)
            let blank = !alpha.contains { $0 > 0.004 }
            if blank && !Self.rendererStuck && clock.now < deadline { continue }
            if blank && !Self.rendererStuck {
                Self.rendererStuck = true
                Issue.record("\(name): the renderer returned a blank picture \(attempts) times in \(Self.renderDeadline)")
            }
            if attempts > 1 { record("blank renders retried", Double(attempts - 1)) }
            if let dir { try UIImage(cgImage: image).pngData()?.write(to: URL(fileURLWithPath: "\(dir)/precision-\(name).png")) }
            return (alpha, w, h)
        }
    }

    /// The alpha channel of [image] (0…1), row by row.
    private static func alpha(_ image: CGImage) -> (alpha: [Double], width: Int, height: Int) {
        let w = image.width, h = image.height
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        pixels.withUnsafeMutableBytes { buffer in
            let context = CGContext(data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                    space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        }
        return ((0 ..< w * h).map { Double(pixels[$0 * 4 + 3]) / 255 }, w, h)
    }

    /// 4-connected components of inked pixels: centroid (alpha-weighted, pixel centres at +0.5)
    /// and the radius of a disc of the same area.
    private func blobs(_ alpha: [Double], width w: Int, height h: Int) -> [Blob] {
        var seen = [Bool](repeating: false, count: w * h)
        var out: [Blob] = []
        for start in 0 ..< w * h where alpha[start] > 0.004 && !seen[start] {
            var stack = [start]
            seen[start] = true
            var sum = 0.0, sx = 0.0, sy = 0.0
            var blob = Blob(x: 0, y: 0, r: 0, minX: .max, maxX: .min, minY: .max, maxY: .min)
            while let p = stack.popLast() {
                let x = p % w, y = p / w, a = alpha[p]
                sum += a
                sx += a * (Double(x) + 0.5)
                sy += a * (Double(y) + 0.5)
                blob.minX = min(blob.minX, x); blob.maxX = max(blob.maxX, x)
                blob.minY = min(blob.minY, y); blob.maxY = max(blob.maxY, y)
                for (nx, ny) in [(x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)] where nx >= 0 && ny >= 0 && nx < w && ny < h {
                    let q = ny * w + nx
                    if !seen[q] && alpha[q] > 0.004 {
                        seen[q] = true
                        stack.append(q)
                    }
                }
            }
            blob.x = sx / sum
            blob.y = sy / sum
            blob.r = (sum / .pi).squareRoot()
            out.append(blob)
        }
        return out.sorted { ($0.y.rounded(), $0.x) < ($1.y.rounded(), $1.x) }
    }

    /// Dots smaller than this (radius, device pixels: under 1.5 px across) are at the display's resolution: the
    /// rasteriser may drop them, so they are not required (they are still checked when found).
    static let subPixel = 0.75

    /**
     Matches measured blobs to the expected centres (pixels): every blob must sit within half a
     pixel of a distinct expected dot, and every expected dot of at least [subPixel] radius must be
     found. Returns the blob for each expected index.
     */
    private func match(_ found: [Blob], expected: [(x: Double, y: Double, r: Double)], tag: String) -> [Int: Blob] {
        var matched: [Int: Blob] = [:]
        for blob in found {
            let nearest = expected.indices.min { hypot(expected[$0].x - blob.x, expected[$0].y - blob.y) < hypot(expected[$1].x - blob.x, expected[$1].y - blob.y) }!
            let e = expected[nearest]
            let off = max(abs(blob.x - e.x), abs(blob.y - e.y))
            // A dot under a pixel across lands in one pixel: its centroid is that pixel's centre,
            // so only resolvable dots are held to half a pixel (sub-pixel ones to one pixel).
            let kind = String(tag.split(separator: " ").first!)
            if e.r >= Self.subPixel {
                record("\(kind) position", off)
                #expect(off < 0.5, "\(tag): a dot \(off) px from its place")
            } else {
                record("\(kind) position (sub-pixel dots)", off)
                #expect(off <= 1, "\(tag): a sub-pixel dot \(off) px from its place")
            }
            #expect(matched[nearest] == nil, "\(tag): two blobs for one dot")
            matched[nearest] = blob
        }
        for (i, e) in expected.enumerated() where e.r >= Self.subPixel {
            #expect(matched[i] != nil, "\(tag): dot \(i) (r \(e.r) px) missing")
        }
        return matched
    }

    @Test func barRasterIsPixelExact() async throws {
        let colors = InkBarColors(ink: .black, pin: .black, mark: .black)
        for scale in [1.0, 2, 3] {
            for height in [2.0, 3] {
                for width in [120.0, 243, 361] {
                    for (label, state) in [("empty", InkBarState.progress(0, running: false)), ("half", .progress(0.5, running: false)), ("full", .progress(1, running: false))] {
                        let bar = InkGrid.bar(widthPt: width, height: height, scale: scale)
                        let size = CGSize(width: width, height: bar.heightPt)
                        let canvas = Canvas { context, s in
                            InkBar.draw(&context, size: s, state: state, fraction: state.value, seconds: 0, colors: colors, scale: scale)
                        }
                        let (alpha, w, h) = try await raster(canvas, size: size, scale: scale, name: "bar-\(label)-\(Int(width))x\(Int(height))@\(Int(scale))x")
                        let tag = "bar \(label) \(width)×\(height) @\(scale)×"
                        #expect(h == bar.diameterPx, "\(tag): height \(h) px")
                        let radii = InkBar.radii(count: bar.axis.count, fullRadius: bar.fullRadiusPx, state: state, fraction: state.value, seconds: 0)
                        let expected = (0 ..< bar.axis.count).map { (x: bar.axis.centerPx($0), y: bar.centerYPx, r: radii[$0]!.radius) }
                        let matched = match(blobs(alpha, width: w, height: h), expected: expected, tag: tag)
                        for (i, blob) in matched {
                            // On the mid-line, of the formula's radius (so not clipped).
                            record("bar vertical centring", abs(blob.y - Double(h) / 2))
                            #expect(abs(blob.y - Double(h) / 2) < 0.5, "\(tag) slot \(i): y \(blob.y) of \(h)")
                            record("bar radius", abs(blob.r - expected[i].r))
                            #expect(abs(blob.r - expected[i].r) < 0.5, "\(tag) slot \(i): r \(blob.r) vs \(expected[i].r)")
                            // Equal spacing: neighbours one whole-pixel pitch apart.
                            if let next = matched[i + 1] {
                                let off = abs(next.x - blob.x - Double(bar.axis.pitchPx))
                                record("bar spacing", off)
                                #expect(off < 0.5, "\(tag): spacing off by \(off) px")
                            }
                        }
                        // Symmetric margins: the row is centred to within half a pixel.
                        if let first = matched[0], let last = matched[bar.axis.count - 1] {
                            let centring = abs(first.x - (Double(w) - last.x)) / 2
                            record("bar centring", centring)
                            #expect(centring <= 0.5 + 0.05, "\(tag): margins \(first.x) / \(Double(w) - last.x)")
                        }
                    }
                }
            }
        }
    }

    @Test func cardFieldRasterIsPixelExact() async throws {
        let base = Double(Tokens.Motion.cardDotPitch)
        for scale in [1.0, 2, 3] {
            for (width, height) in [(160.0, 130.0), (183, 268), (361, 100)] {
                for cycle in [0.0, 0.3, 0.75] {
                    let grid = InkDotGrid()
                    let size = CGSize(width: width, height: height)
                    grid.layout(size, base: base, maxDots: Int(Tokens.Motion.cardMaxDots), fade: nil, scale: scale)
                    let canvas = Canvas { context, _ in context.fill(InkField.path(grid, cycle: cycle), with: .color(.black)) }
                    let (alpha, w, h) = try await raster(canvas, size: size, scale: scale, name: "card-\(Int(width))x\(Int(height))-\(Int(cycle * 100))@\(Int(scale))x")
                    let tag = "card \(width)×\(height) t\(cycle) @\(scale)×"
                    let radii = InkField.radii(grid, cycle: cycle)
                    let expected = (0 ..< grid.count).map { (x: grid.xs[$0] * scale, y: grid.ys[$0] * scale, r: radii[$0] * scale) }
                    let found = blobs(alpha, width: w, height: h)
                    let matched = match(found, expected: expected, tag: tag)
                    let cols = grid.columns!.count, pitch = grid.pitch * scale
                    for (i, blob) in matched {
                        record("card radius", abs(blob.r - expected[i].r))
                        #expect(abs(blob.r - expected[i].r) < 0.5, "\(tag) dot \(i): r \(blob.r) vs \(expected[i].r)")
                        // No dot touches the surface's edge (none clipped).
                        #expect(blob.minX > 0 && blob.minY > 0 && blob.maxX < w - 1 && blob.maxY < h - 1, "\(tag) dot \(i) clipped")
                        let resolvable = { (j: Int) in expected[j].r >= Self.subPixel }
                        if i % cols != cols - 1, let right = matched[i + 1], resolvable(i), resolvable(i + 1) {
                            let off = abs(right.x - blob.x - pitch)
                            record("card spacing", off)
                            #expect(off < 0.5, "\(tag): spacing off by \(off) px")
                        }
                        if let below = matched[i + cols], resolvable(i), resolvable(i + cols) {
                            let off = abs(below.y - blob.y - pitch)
                            record("card spacing", off)
                            #expect(off < 0.5, "\(tag): spacing off by \(off) px")
                        }
                    }
                    // Symmetric margins, measured on the outer columns and rows (resolvable dots).
                    let rowCount = grid.rows!.count
                    func mean(_ indices: [Int], _ value: (Blob) -> Double) -> Double? {
                        let values = indices.filter { expected[$0].r >= Self.subPixel }.compactMap { matched[$0] }.map(value)
                        return values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
                    }
                    let firstCol = (0 ..< rowCount).map { $0 * cols }, lastCol = (0 ..< rowCount).map { $0 * cols + cols - 1 }
                    let firstRow = Array(0 ..< cols), lastRow = (0 ..< cols).map { (rowCount - 1) * cols + $0 }
                    if let l = mean(firstCol, \.x), let r = mean(lastCol, \.x), let t = mean(firstRow, \.y), let b = mean(lastRow, \.y) {
                        let cx = abs(l - (Double(w) - r)) / 2, cy = abs(t - (Double(h) - b)) / 2
                        record("card centring", max(cx, cy))
                        #expect(cx <= 0.5 + 0.05 && cy <= 0.5 + 0.05, "\(tag): margins \(cx) \(cy)")
                    }
                }
            }
        }
    }

    @Test func loaderRasterIsPixelExact() async throws {
        for scale in [1.0, 2, 3] {
            for size in [InkLoader.Size.inline, .button, .block] {
                let frame = InkLoader.frame(size, scale: CGFloat(scale))
                let canvas = Canvas { context, s in context.fill(InkLoader.dots(size, in: s, seconds: 0.2, scale: scale), with: .color(.black)) }
                let (alpha, w, h) = try await raster(canvas, size: frame, scale: scale, name: "loader-\(size.cols)x\(size.rows)@\(Int(scale))x")
                let found = blobs(alpha, width: w, height: h)
                let (d, cols, rows) = InkLoader.layout(size, in: frame, scale: scale)
                try #require(found.count == cols.count * rows.count)
                for (i, blob) in found.enumerated() {
                    let ex = cols.centerPx(i % cols.count), ey = rows.centerPx(i / cols.count)
                    record("loader position", max(abs(blob.x - ex), abs(blob.y - ey)))
                    #expect(abs(blob.x - ex) < 0.5 && abs(blob.y - ey) < 0.5)
                    let position = Double(i % cols.count) + Double(i / cols.count) * 0.75
                    let r = Double(d) / 2 * InkMath.loaderScale(position: position, wavelength: size.wavelength, seconds: 0.2)
                    record("loader radius", abs(blob.r - r))
                    #expect(abs(blob.r - r) < 0.5)
                }
            }
        }
    }

    /**
     The image proof plate (idle and flowing) lands on the same whole-pixel grid as every other
     field ([InkGrid.field]): one blob per grid dot, each within half a pixel of its centre, an
     exact pitch both ways. Includes plates large enough for the dot cap to grow the pitch (it
     used to become fractional there, so neighbouring dots antialiased differently and the
     screen beat against itself).
     */
    @Test func plateRasterIsPixelExact() async throws {
        let base = Double(Tokens.Halftone.imageCell)
        let now = Date(timeIntervalSinceReferenceDate: 1000)
        for scale in [2.0, 3] {
            for (width, height) in [(180.0, 180.0), (366, 366), (240, 360)] {
                for ink in [PlateInk.idle, .flow(progress: 0.3, loading: false)] {
                    let engine = PlateEngine()
                    _ = engine.receive(ink, at: now)
                    let size = CGSize(width: width, height: height)
                    let canvas = Canvas { context, s in
                        engine.draw(&context, size: s, at: now, cell: CGFloat(base), phase: 0.3, dot: .black, idleDot: .black, plate: .black)
                    }
                    let tag = "plate \(ink == .idle ? "idle" : "flow") \(width)×\(height) @\(scale)×"
                    let (alpha, w, h) = try await raster(canvas, size: size, scale: scale, name: "plate-\(ink == .idle ? "idle" : "flow")-\(Int(width))x\(Int(height))@\(Int(scale))x")
                    let (cols, rows) = InkGrid.field(width: width, height: height, base: base, maxDots: InkMath.maxDots, scale: scale)
                    #expect(cols.count * rows.count <= InkMath.maxDots, "\(tag): \(cols.count * rows.count) dots")
                    // Every inked pixel goes to its nearest grid dot (dots near full size may touch):
                    // each dot's centroid must be its grid centre. A plate off the grid, a fractional
                    // pitch or a second, offset copy of the dots pulls centroids away.
                    var sums = [(a: Double, x: Double, y: Double)](repeating: (0, 0, 0), count: cols.count * rows.count)
                    var stray = 0.0
                    for y in 0 ..< h {
                        for x in 0 ..< w where alpha[y * w + x] > 0.004 {
                            let px = Double(x) + 0.5, py = Double(y) + 0.5, a = alpha[y * w + x]
                            let c = Int(((px - cols.firstPx) / Double(cols.pitchPx)).rounded()), r = Int(((py - rows.firstPx) / Double(rows.pitchPx)).rounded())
                            guard c >= 0, c < cols.count, r >= 0, r < rows.count else { stray += a; continue }
                            sums[r * cols.count + c].a += a
                            sums[r * cols.count + c].x += a * px
                            sums[r * cols.count + c].y += a * py
                        }
                    }
                    #expect(stray < 1, "\(tag): ink outside the grid (\(stray) px)")
                    var resolvable = 0
                    for (i, s) in sums.enumerated() where (s.a / .pi).squareRoot() >= 1.5 {
                        resolvable += 1
                        let off = max(abs(s.x / s.a - cols.centerPx(i % cols.count)), abs(s.y / s.a - rows.centerPx(i / cols.count)))
                        record("plate position", off)
                        #expect(off < 0.5, "\(tag): dot \(i) is \(off) px off its grid centre")
                    }
                    #expect(resolvable > cols.count * rows.count / 4, "\(tag): only \(resolvable) dots")
                }
            }
        }
    }

    /// Runs last (alphabetically after the raster tests in this serialized suite): the report.
    @Test func zzReport() throws {
        let lines = Self.worst.sorted { $0.key < $1.key }.map { String(format: "%@: %.3f px", $0.key, $0.value) }
        print("INK PRECISION (worst error)\n" + lines.joined(separator: "\n"))
        if let dir { try lines.joined(separator: "\n").write(toFile: "\(dir)/precision-report.txt", atomically: true, encoding: .utf8) }
    }
}

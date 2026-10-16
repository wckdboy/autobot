import Foundation

/**
 Pixel-exact dot geometry for the ink components (the dot bar, the loader, living cards and paper).
 Everything is laid out in device pixels and converted back to points at the end, so:

 - the pitch is a whole number of pixels, so every dot sits at the same sub-pixel phase and
   antialiases identically;
 - a row holds N = ⌊(L − 2·margin) / pitch⌋ + 1 dots, the leftover split evenly between the two
   ends (the grid is centred), so there are never clipped half-dots;
 - centres snap to the pixel grid (even diameters) or to pixel centres (odd diameters).

 Pure functions, shared by the views and the raster tests. Android mirrors these rules.
 */
enum InkGrid {
    /// One axis of a dot grid, in device pixels.
    struct Axis: Equatable {
        let count: Int
        /// Whole pixels between neighbouring centres.
        let pitchPx: Int
        /// Centre of the first dot, in pixels from the start of the axis.
        let firstPx: Double
        let lengthPx: Double

        func centerPx(_ i: Int) -> Double { firstPx + Double(i * pitchPx) }

        /// Centre of dot [i] in points.
        func center(_ i: Int, scale: Double) -> Double { centerPx(i) / scale }

        /// Distance from each end of the axis to the nearest centre (pixels).
        var leadingMarginPx: Double { firstPx }
        var trailingMarginPx: Double { count == 0 ? 0 : lengthPx - centerPx(count - 1) }
        /// How far the grid's centre is from the axis's centre (pixels, ≤ 0.5 by construction).
        var centringErrorPx: Double { abs(leadingMarginPx - trailingMarginPx) / 2 }
    }

    /// [points] in whole device pixels at [scale], never below [minimum].
    static func pixels(_ points: Double, scale: Double, minimum: Int = 1) -> Int {
        max(minimum, Int((points * scale).rounded()))
    }

    /**
     Fits dots along [lengthPx] pixels at [pitchPx] whole pixels, keeping every centre at least
     [marginPx] from both ends, centred, with centres snapped to the pixel grid ([oddDiameter]: to
     pixel centres). Snapping moves the grid by at most half a pixel and never past the margin.
     */
    static func axis(lengthPx: Double, pitchPx: Int, marginPx: Double, oddDiameter: Bool) -> Axis {
        let pitch = max(pitchPx, 1)
        let available = lengthPx - 2 * marginPx
        guard available >= -1e-9, lengthPx > 0 else { return Axis(count: 0, pitchPx: pitch, firstPx: 0, lengthPx: max(lengthPx, 0)) }
        let count = Int((max(available, 0) / Double(pitch) + 1e-9).rounded(.down)) + 1
        return centred(count: count, pitchPx: pitch, lengthPx: lengthPx, marginPx: marginPx, oddDiameter: oddDiameter)
    }

    /// [count] dots at [pitchPx], centred in [lengthPx] and snapped (see [axis]).
    static func centred(count: Int, pitchPx: Int, lengthPx: Double, marginPx: Double = 0, oddDiameter: Bool) -> Axis {
        let pitch = max(pitchPx, 1)
        guard count > 0 else { return Axis(count: 0, pitchPx: pitch, firstPx: 0, lengthPx: lengthPx) }
        let span = Double((count - 1) * pitch)
        let ideal = (lengthPx - span) / 2
        let offset = oddDiameter ? 0.5 : 0
        var first = (ideal - offset).rounded() + offset
        // Snapping never pushes a dot past a margin when there is room on the other side.
        if first < marginPx - 1e-9 && first + 1 + span <= lengthPx - marginPx + 1e-9 { first += 1 }
        if first + span > lengthPx - marginPx + 1e-9 && first - 1 >= marginPx - 1e-9 { first -= 1 }
        return Axis(count: count, pitchPx: pitch, firstPx: first, lengthPx: lengthPx)
    }

    // MARK: Bar

    /// The bar's geometry at one scale: dot diameter and pitch in whole pixels, the slots.
    struct Bar: Equatable {
        let scale: Double
        /// Full dot diameter (pixels); also the bar's height.
        let diameterPx: Int
        let axis: Axis

        var heightPt: Double { Double(diameterPx) / scale }
        var fullRadiusPx: Double { Double(diameterPx) / 2 }
        /// Every centre sits on the bar's mid-line: a pixel boundary (even) or centre (odd).
        var centerYPx: Double { Double(diameterPx) / 2 }
        var pinRadiusPx: Double { fullRadiusPx * InkMath.barEmptyScale }
    }

    /// Dot diameter (points) of a bar of nominal [height]: dots under 4pt read as a dotted hairline.
    static func barDiameter(height: Double) -> Double { max(height * 1.5, 4) }

    /**
     A bar [widthPt] wide for a nominal [height] at [scale]: diameter d = round(max(1.5·h, 4)·s) px,
     pitch round(`barDotPitch`·d) px, the outer dots touching the ends (margin d/2).
     */
    static func bar(widthPt: Double, height: Double, scale: Double) -> Bar {
        let d = pixels(barDiameter(height: height), scale: scale)
        let pitch = max(d + 1, Int((Double(d) * InkMath.barPitch).rounded()))
        let axis = axis(lengthPx: widthPt * scale, pitchPx: pitch, marginPx: Double(d) / 2, oddDiameter: d % 2 == 1)
        return Bar(scale: scale, diameterPx: d, axis: axis)
    }

    // MARK: Fields

    /// The smallest whole-pixel pitch ≥ [base] points that keeps a [width] × [height] field under [maxDots].
    static func fieldPitchPx(width: Double, height: Double, base: Double, maxDots: Int, scale: Double) -> Int {
        let wanted = InkMath.dotCell(width: width, height: height, base: base, maxDots: maxDots)
        let exact = pixels(base, scale: scale)
        // The token pitch when it fits; else rounded up, so the cap still holds.
        return wanted <= base ? exact : max(exact, Int((wanted * scale - 1e-9).rounded(.up)))
    }

    /// A field's two axes: whole-pixel pitch, half a pitch of margin (no dot ever clipped), centred.
    static func field(width: Double, height: Double, base: Double, maxDots: Int, scale: Double) -> (x: Axis, y: Axis) {
        let pitch = fieldPitchPx(width: width, height: height, base: base, maxDots: maxDots, scale: scale)
        let margin = Double(pitch) / 2
        return (
            axis(lengthPx: width * scale, pitchPx: pitch, marginPx: margin, oddDiameter: false),
            axis(lengthPx: height * scale, pitchPx: pitch, marginPx: margin, oddDiameter: false),
        )
    }

    /// Dot centres (points) of a [width] × [height] field at a [pitch]-point grid (whole pixels),
    /// centred with half a pitch of margin, and the pitch actually used (points).
    static func centres(width: Double, height: Double, pitch: Double, scale: Double) -> (xs: [Double], ys: [Double], pitch: Double) {
        let (cols, rows) = field(width: width, height: height, base: pitch, maxDots: .max, scale: scale)
        return ((0 ..< cols.count).map { cols.center($0, scale: scale) }, (0 ..< rows.count).map { rows.center($0, scale: scale) }, Double(cols.pitchPx) / scale)
    }

    // MARK: Card wave

    /// The card wave's wavelength (points) along its direction: fixed, so a dot keeps its phase
    /// when the card resizes (the wave does not depend on the card's size).
    static let cardWavelength = 320.0
    /// The wave travels down-right along this angle (radians from the x axis).
    static let cardWaveAngle = 0.45
    static var cardKx: Double { 2 * .pi / cardWavelength * cos(cardWaveAngle) }
    static var cardKy: Double { 2 * .pi / cardWavelength * sin(cardWaveAngle) }

    /// The share of the pitch a living card's largest dot reaches (diameter 0.72 pitch < pitch).
    static let cardRadiusShare = 0.36

    /// r = base·(0.62 + 0.38·sin(kx·x + ky·y − 2π·τ)), [cycle] τ = elapsed / period (0…1).
    static func cardRadius(base: Double, x: Double, y: Double, cycle: Double) -> Double {
        base * (0.62 + 0.38 * sin(cardKx * x + cardKy * y - 2 * .pi * cycle))
    }
}

/**
 The living field's own clock: the wave's position in its cycle (0…1), advanced by the time
 between frames. Gaps (paused, off screen, a redraw after a resize) count as at most one frame,
 so the wave never jumps; while it runs it moves exactly one cycle per [period] seconds.
 */
final class InkWaveClock {
    private(set) var cycle: Double
    private var last: Date?
    /// The longest step one frame may take (the ambient tier's frame interval).
    static var maxStep: Double { max(InkMath.ambientFrameInterval, 0.1) }

    init(cycle: Double = 0) { self.cycle = cycle }

    @discardableResult
    func advance(to date: Date, period: Double) -> Double {
        defer { last = date }
        guard let last, period > 0 else { return cycle }
        let dt = min(max(date.timeIntervalSince(last), 0), Self.maxStep)
        cycle = InkMath.cycle(cycle + dt / period)
        return cycle
    }
}

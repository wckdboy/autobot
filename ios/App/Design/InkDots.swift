import SwiftUI

// Ink motion v3 (motion spec v3): the dot-row progress bar, the wave loader and living card
// backgrounds. One visual language for "working": a wave of swelling dots. Everything draws in
// one Canvas pass (one path per colour) from the shared tier clock ([InkClock]); geometry is
// cached per size and only radii change per frame.

extension InkMath {
    // MARK: Bar

    static var barPitch: Double { Double(Tokens.Motion.barDotPitch) }
    static var barEmptyScale: Double { Double(Tokens.Motion.barEmptyDotScale) }
    static var barFillStart: Double { Double(Tokens.Motion.barFillStartScale) }
    static var barWaveSlots: Double { Double(Tokens.Motion.barWaveSlots) }
    static var barRunningAmplitude: Double { Double(Tokens.Motion.barRunningWaveAmplitude) }
    /// Slots at the head that ease in as progress advances (growth is continuous, never pops).
    static let barHeadSlots = 2.0

    /// A bar of [width] × [diameter] (points) at [scale]: how many dot slots fit (the outer dots
    /// touch the ends), their pitch and the first centre, all in points and pixel-snapped
    /// ([InkGrid.bar]).
    static func barLayout(width: Double, diameter: Double, scale: Double = 1) -> (count: Int, pitch: Double, first: Double) {
        guard width >= diameter, diameter > 0 else { return (0, diameter * barPitch, 0) }
        let d = InkGrid.pixels(diameter, scale: scale)
        let pitch = max(d + 1, Int((Double(d) * barPitch).rounded()))
        let axis = InkGrid.axis(lengthPx: width * scale, pitchPx: pitch, marginPx: Double(d) / 2, oddDiameter: d % 2 == 1)
        return (axis.count, Double(pitch) / scale, axis.firstPx / scale)
    }

    /// Where the fill's head is (in slots) at [fraction] of a [count]-slot bar: 0 shows nothing,
    /// 1 has grown every slot to full size.
    static func barHead(fraction: Double, count: Int) -> Double {
        clamp01(fraction) * (Double(count) + barHeadSlots - 0.5)
    }

    /// Radius (share of the full dot) of the filled slot centred at [c] slots when the head is at
    /// [head]: grows from `barFillStartScale` at the start to full size at the head, the last
    /// slots easing in. 0 for slots the fill has not reached.
    static func barFillScale(slot c: Double, head: Double) -> Double {
        guard head > 0 else { return 0 }
        let edge = smoothstep((head - c + 0.5) / barHeadSlots)
        let ramp = barFillStart + (1 - barFillStart) * min(c / head, 1)
        return ramp * edge
    }

    /// The travelling wave (0…1) at slot [c] at [seconds]: `barWaveSlots` slots long, one cycle
    /// per `loaderCycleMillis`, moving toward the end of the bar.
    static func barWave(slot c: Double, seconds: Double) -> Double {
        0.5 + 0.5 * sin(2 * .pi * (c / barWaveSlots - seconds / loaderCycleSeconds))
    }

    /// Loading: every slot swells as the wave passes (from just above a pin to full size).
    static func barLoadingScale(slot c: Double, seconds: Double) -> Double {
        let w = barWave(slot: c, seconds: seconds)
        return barEmptyScale + (1 - barEmptyScale) * w * w
    }

    /// Running fill: a subtle wave, `barRunningWaveAmplitude` of the radius (never larger than full).
    static func barRunningScale(slot c: Double, seconds: Double) -> Double {
        1 - barRunningAmplitude * (1 - barWave(slot: c, seconds: seconds))
    }

    static var loaderCycleSeconds: Double { Double(Tokens.Motion.loaderCycleMillis) / 1000 }

    /// Loader cycle position (0…1) at [seconds].
    static func loaderPhase(_ seconds: Double) -> Double { cycle(seconds / loaderCycleSeconds) }

    /// Loader dot radius (share of full) at [position] along its wave of [wavelength] dots.
    static func loaderScale(position: Double, wavelength: Double, seconds: Double) -> Double {
        let w = 0.5 + 0.5 * sin(2 * .pi * (position / wavelength - seconds / loaderCycleSeconds))
        return 0.28 + 0.72 * w * w
    }

    // MARK: Living cards

    /// Living card dots: radius multiplier (0.24…1, so every dot stays visible at the wave's low
    /// point) of a dot whose wave phase is [phase] (radians)
    /// at [seconds], one cycle per [period] s. The time is reduced to the cycle first, so the
    /// period is exact whatever the size of [seconds].
    static func cardWave(phase: Double, seconds: Double, period: Double) -> Double {
        cardWave(phase: phase, cycle: cycle(seconds / period))
    }

    /// The same at a [cycle] position (0…1).
    static func cardWave(phase: Double, cycle: Double) -> Double {
        0.62 + 0.38 * sin(phase - 2 * .pi * cycle)
    }
}

// MARK: - Progress bar

/// What an [InkProgressBar] shows.
enum InkBarState: Equatable {
    /// [fraction] done; [running] adds a subtle wave to the filled dots.
    case progress(Double, running: Bool)
    /// Working, amount unknown: a wave of swelling dots runs along the bar.
    case indeterminate
    /// Stopped at [fraction]: static, dimmed, with a `‖` mark.
    case paused(Double)
    /// Failed at [fraction]: the filled dots turn the error colour and hold.
    case error(Double)
}

extension InkBarState {
    var value: Double {
        switch self {
        case .progress(let f, _), .paused(let f), .error(let f): InkMath.clamp01(f)
        case .indeterminate: 0
        }
    }

    var isError: Bool { if case .error = self { true } else { false } }
    var isPaused: Bool { if case .paused = self { true } else { false } }
    /// Animates (running fill, loading wave).
    var isLive: Bool {
        switch self {
        case .progress(let f, let running): running && f < 1
        case .indeterminate: true
        case .paused, .error: false
        }
    }

    var label: String {
        switch self {
        case .progress: "Progress"
        case .indeterminate: "Working"
        case .paused: "Paused"
        case .error: "Failed"
        }
    }
}

/**
 Progress bar. App UI v4: a flat bar (no dot fill), a filled run in the ink over a flat track in
 the pin colour, [height] points tall; indeterminate is a short run sliding along the track. The
 dot geometry below ([InkBar.radii], [InkBar.draw]) is kept for the motion tests.

 Before v4, a row of dots (motion spec v3 §1). Empty: faint pin dots. Determinate: dots up to
 the progress point fill, growing from small at the start to full at the head, the head easing in
 so growth is continuous; values ease over `progressMillis`. Running adds a ±10% wave to the fill;
 indeterminate is a travelling wave of swelling dots. Paused: dimmed with a `‖` mark. Error: the
 fill turns red and holds. One Canvas, one path per colour, on the shared work clock.
 */
struct InkProgressBar: View {
    var state: InkBarState
    var height: CGFloat = 3
    var track: Color? = nil
    var ink: Color? = nil
    @Environment(\.scheme) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.inkStill) private var still

    init(fraction: Double, height: CGFloat = 3, running: Bool = false, track: Color? = nil, ink: Color? = nil) {
        self.init(.progress(fraction, running: running), height: height, track: track, ink: ink)
    }

    init(_ state: InkBarState, height: CGFloat = 3, track: Color? = nil, ink: Color? = nil) {
        self.state = state
        self.height = height
        self.track = track
        self.ink = ink
    }

    /// The bar's height (= its dot diameter) for a nominal [height] at [scale]: max(1.5·h, 4) pt,
    /// rounded to whole pixels (dots under 4pt read as a dotted hairline).
    static func diameter(_ height: CGFloat, scale: CGFloat = 3) -> CGFloat {
        CGFloat(InkGrid.bar(widthPt: 0, height: Double(height), scale: Double(scale)).heightPt)
    }

    @Environment(\.displayScale) private var displayScale

    var body: some View {
        let reduced = reduceMotion || still
        let colors = InkBarColors(ink: state.isError ? c.error : ink ?? c.primary, pin: track ?? c.outlineVariant, mark: c.onSurfaceVariant)
        InkBarCanvas(state: state, fraction: state.value, colors: colors, live: state.isLive && !reduced)
            .animation(reduced ? .inkReduced : .inkProgress, value: state.value)
            .frame(height: Self.diameter(height, scale: displayScale))
            .accessibilityElement()
            .accessibilityLabel(state.label)
            .accessibilityValue(state == .indeterminate ? "" : "\(Int(state.value * 100)) percent")
    }
}

struct InkBarColors {
    let ink: Color
    let pin: Color
    let mark: Color
}

/// The bar's leaf: animatable (the eased fraction), on the work clock only while live.
private struct InkBarCanvas: View, Animatable {
    let state: InkBarState
    var fraction: Double
    let colors: InkBarColors
    let live: Bool

    nonisolated var animatableData: Double {
        get { fraction }
        set { fraction = newValue }
    }

    var body: some View {
        let state = state, fraction = fraction, colors = colors, live = live
        InkTimeline(active: live) { date in
            Canvas { context, size in
                InkProbe.hit("ink.draw")
                InkBar.drawFlat(&context, size: size, state: state, fraction: fraction, seconds: live ? date.timeIntervalSinceReferenceDate : 0, colors: colors)
            }
        }
    }
}

/// Bar drawing, shared by the view and the frame tests.
enum InkBar {
    /// The v4 flat bar: a track in the pin colour and a run of ink (sliding when indeterminate).
    static func drawFlat(_ context: inout GraphicsContext, size: CGSize, state: InkBarState, fraction: Double, seconds: Double, colors: InkBarColors) {
        let h = min(size.height, max(2, size.height / 1.5))
        let y = (size.height - h) / 2
        context.fill(Path(CGRect(x: 0, y: y, width: size.width, height: h)), with: .color(colors.pin))
        switch state {
        case .indeterminate:
            let run = size.width * 0.3
            let t = InkMath.cycle(seconds / 1.4)
            let x = -run + (size.width + run) * t
            context.fill(Path(CGRect(x: x, y: y, width: run, height: h)), with: .color(colors.ink))
        default:
            let ink = state.isPaused ? colors.mark : colors.ink
            let w = size.width * InkMath.clamp01(fraction)
            if w > 0 { context.fill(Path(CGRect(x: 0, y: y, width: w, height: h)), with: .color(ink)) }
        }
    }

    /// The dots' radii (pixels) slot by slot for [count] slots: `nil` marks the paused `‖` slot.
    /// Pins are `barEmptyDotScale` of the full radius, the fill ramps from `barFillStartScale`
    /// (eased in at the head, whose position is linear in [fraction]), running adds the wave.
    static func radii(count: Int, fullRadius full: Double, state: InkBarState, fraction: Double, seconds: Double) -> [(radius: Double, filled: Bool)?] {
        let pin = full * InkMath.barEmptyScale
        switch state {
        case .indeterminate:
            return (0 ..< count).map { (full * InkMath.barLoadingScale(slot: Double($0) + 0.5, seconds: seconds), true) }
        default:
            let head = InkMath.barHead(fraction: fraction, count: count)
            let running = state.isLive
            var marked = false
            return (0 ..< count).map { i in
                let c = Double(i) + 0.5
                var scale = InkMath.barFillScale(slot: c, head: head)
                if running && scale > 0 { scale *= InkMath.barRunningScale(slot: c, seconds: seconds) }
                let r = full * scale
                if r > pin { return (r, true) }
                if state.isPaused && !marked {
                    marked = true
                    return nil
                }
                return (pin, false)
            }
        }
    }

    /**
     Draws the bar in [size] (points) at [scale] (the context's display scale by default): the
     geometry is laid out in whole pixels ([InkGrid.bar]), so the pitch is exact, the row is
     centred with no clipped dots and every centre sits on the bar's pixel-snapped mid-line.
     */
    static func draw(_ context: inout GraphicsContext, size: CGSize, state: InkBarState, fraction: Double, seconds: Double, colors: InkBarColors, scale: Double? = nil) {
        let s = scale ?? Double(context.environment.displayScale)
        let d = InkGrid.pixels(Double(size.height), scale: s)
        let pitch = max(d + 1, Int((Double(d) * InkMath.barPitch).rounded()))
        let axis = InkGrid.axis(lengthPx: Double(size.width) * s, pitchPx: pitch, marginPx: Double(d) / 2, oddDiameter: d % 2 == 1)
        guard axis.count > 0 else { return }
        let cy = InkGrid.centred(count: 1, pitchPx: 1, lengthPx: Double(size.height) * s, oddDiameter: d % 2 == 1).firstPx / s
        let full = Double(d) / 2 / s
        var pins = Path(), dots = Path()
        var markSlot: Int? = nil
        for (i, slot) in radii(count: axis.count, fullRadius: full, state: state, fraction: fraction, seconds: seconds).enumerated() {
            guard let slot else {
                markSlot = i
                continue
            }
            let cx = axis.center(i, scale: s), r = slot.radius
            let rect = CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2)
            if slot.filled { dots.addEllipse(in: rect) } else { pins.addEllipse(in: rect) }
        }
        context.fill(pins, with: .color(colors.pin))
        context.fill(dots, with: .color(colors.ink.opacity(state.isPaused ? 0.45 : 1)))
        if let markSlot {
            // `‖` in place of the first empty slot: two short rules.
            let cx = axis.center(markSlot, scale: s), d = Double(d) / s
            let w = max(d * 0.18, 0.75), gap = d * 0.22
            var mark = Path()
            mark.addRect(CGRect(x: cx - gap - w / 2, y: 0, width: w, height: d))
            mark.addRect(CGRect(x: cx + gap - w / 2, y: 0, width: w, height: d))
            context.fill(mark, with: .color(colors.mark))
        }
    }
}

// MARK: - Living cards

/**
 Cached dot geometry for a living surface of one size (motion spec v3 §2): centres on a
 whole-pixel `cardDotPitch` grid (grown, still in whole pixels, so there are at most [maxDots]),
 centred with half a pitch of margin and snapped to the pixel grid ([InkGrid.field]); each dot's
 wave phase kx·x + ky·y (a fixed wave vector, so phases do not depend on the surface's size) and
 its weight (dots thin out around a fade point, so text over the field stays clean). Built once
 per size; a frame only computes radii.
 */
final class InkDotGrid {
    private(set) var size = CGSize.zero
    /// The pitch in points (a whole number of pixels at [scale]).
    private(set) var pitch: Double = 0
    private(set) var scale: Double = 1
    private(set) var columns: InkGrid.Axis?
    private(set) var rows: InkGrid.Axis?
    private(set) var xs: [Double] = []
    private(set) var ys: [Double] = []
    private(set) var phases: [Double] = []
    private(set) var weights: [Double] = []
    /// The wave's own clock: continuous across pauses and size changes.
    let clock = InkWaveClock()
    private var key: Key?

    private struct Key: Equatable {
        let size: CGSize
        let base: Double
        let maxDots: Int
        let fade: UnitPoint?
        let scale: Double
    }

    var count: Int { xs.count }

    func layout(_ size: CGSize, base: Double, maxDots: Int, fade: UnitPoint?, scale: Double = 1) {
        let key = Key(size: size, base: base, maxDots: maxDots, fade: fade, scale: scale)
        guard key != self.key else { return }
        self.key = key
        self.size = size
        self.scale = scale
        let w = Double(size.width), h = Double(size.height)
        let (cols, rows) = InkGrid.field(width: w, height: h, base: base, maxDots: maxDots, scale: scale)
        columns = cols
        self.rows = rows
        pitch = Double(cols.pitchPx) / scale
        let kx = InkGrid.cardKx, ky = InkGrid.cardKy
        xs.removeAll(keepingCapacity: true)
        ys.removeAll(keepingCapacity: true)
        phases.removeAll(keepingCapacity: true)
        weights.removeAll(keepingCapacity: true)
        for r in 0 ..< rows.count {
            let y = rows.center(r, scale: scale)
            for col in 0 ..< cols.count {
                let x = cols.center(col, scale: scale)
                var weight = 1.0
                if let fade {
                    let dx = (x / max(w, 1) - fade.x) * (w / max(h, 1)).squareRoot(), dy = (y / max(h, 1) - fade.y)
                    weight = 0.15 + 0.85 * InkMath.smoothstep(((dx * dx + dy * dy).squareRoot() - 0.15) / 0.6)
                }
                xs.append(x)
                ys.append(y)
                phases.append(kx * x + ky * y)
                weights.append(weight)
            }
        }
    }
}

/// A living field: the cached grid's dots swelling with the slow travelling wave.
enum InkField {
    /// One frame of the field at [seconds] (one wave cycle per [period] s), radii up to [scale]
    /// of the pitch, multiplied by [fill] (print-away swells them).
    static func path(_ grid: InkDotGrid, seconds: Double, period: Double, seed: Double = 0, scale: Double = InkGrid.cardRadiusShare, fill: Double = 1) -> Path {
        path(grid, cycle: InkMath.cycle(seconds / period), seed: seed, scale: scale, fill: fill)
    }

    /// One frame at wave position [cycle] (0…1): r = base·w·(0.62 + 0.38·sin(kx·x + ky·y − 2π·cycle)).
    static func path(_ grid: InkDotGrid, cycle: Double, seed: Double = 0, scale: Double = InkGrid.cardRadiusShare, fill: Double = 1) -> Path {
        var path = Path()
        for (i, r) in radii(grid, cycle: cycle, seed: seed, scale: scale, fill: fill).enumerated() {
            guard r > 0.2 else { continue }
            path.addEllipse(in: CGRect(x: grid.xs[i] - r, y: grid.ys[i] - r, width: r * 2, height: r * 2))
        }
        return path
    }

    /// Every dot's radius (points) at [cycle].
    static func radii(_ grid: InkDotGrid, cycle: Double, seed: Double = 0, scale: Double = InkGrid.cardRadiusShare, fill: Double = 1) -> [Double] {
        let base = grid.pitch * scale * fill
        return (0 ..< grid.count).map { i in
            base * grid.weights[i] * InkMath.cardWave(phase: grid.phases[i] + seed, cycle: cycle)
        }
    }
}

/**
 A living card background (motion spec v3 §2): a halftone dot grid in a low-contrast tone whose
 radii follow a slow 2-D travelling wave (`cardWavePeriodSeconds` per cycle), on the shared
 ambient clock (≤5 fps, asleep after a minute without a touch, off in Low Power Mode, with
 Reduce Motion, off screen and while heavy work runs). ≤ `cardMaxDots` dots. The wave keeps its
 place across pauses and resizes ([InkWaveClock]).
 */
struct InkCardField: View {
    /// Phase offset, so neighbouring cards do not breathe in lockstep.
    var seed = 0.0
    /// Dots thin out around this point (where the card's text sits).
    var fade: UnitPoint? = nil
    var dot: Color? = nil
    @Environment(\.scheme) private var c
    @Environment(\.inkHeavyWork) private var heavy
    @State private var grid = InkDotGrid()

    var body: some View {
        let ink = (dot ?? c.onSurface).opacity(Double(Tokens.Motion.cardDotOpacity))
        let grid = grid, seed = seed, fade = fade
        InkTimeline(active: !heavy, tier: .ambient) { date in
            Canvas { context, size in
                InkProbe.hit("ink.draw")
                grid.layout(size, base: Double(Tokens.Motion.cardDotPitch), maxDots: Int(Tokens.Motion.cardMaxDots), fade: fade,
                            scale: Double(context.environment.displayScale))
                let cycle = grid.clock.advance(to: date, period: Double(Tokens.Motion.cardWavePeriodSeconds))
                context.fill(InkField.path(grid, cycle: cycle, seed: seed), with: .color(ink))
            }
        }
        .background(InkTouchWatcher())
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

extension View {
    /**
     A living card background behind this view's content, clipped to [cornerRadius]. Apply it
     before the card's own fill (`.inkCard().background(fill)`), so the dots sit between the
     fill and the content. Only on deliberate surfaces (hero cards, empty states), never on rows
     of a dense list.
     */
    func inkCard(_ enabled: Bool = true, seed: Double = 0, fade: UnitPoint? = nil, cornerRadius: CGFloat = AutobotShapes.extraSmall) -> some View {
        background {
            if enabled {
                InkCardField(seed: seed, fade: fade).clipShape(RoundedRectangle(cornerRadius: cornerRadius))
            }
        }
    }
}

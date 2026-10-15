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

    /// A bar of [width] × [diameter]: how many dot slots fit (the outer dots touch the ends), their
    /// pitch and the first centre.
    static func barLayout(width: Double, diameter: Double) -> (count: Int, pitch: Double, first: Double) {
        let pitch = diameter * barPitch
        guard width >= diameter, diameter > 0 else { return (0, pitch, 0) }
        let count = Int(((width - diameter) / pitch).rounded(.down)) + 1
        let span = Double(count - 1) * pitch + diameter
        return (count, pitch, (width - span) / 2 + diameter / 2)
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

    /// Living card dots: radius multiplier (0.1…1) of a dot whose wave phase is [phase] (radians)
    /// at [seconds], one cycle per [period] s.
    static func cardWave(phase: Double, seconds: Double, period: Double) -> Double {
        0.55 + 0.45 * sin(phase - 2 * .pi * seconds / period)
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
 Progress bar as a row of dots (motion spec v3 §1). Empty: faint pin dots. Determinate: dots up to
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

    /// Dot diameter for a bar of [height]: dots under 4pt read as a dotted hairline.
    static func diameter(_ height: CGFloat) -> CGFloat { max(height * 1.5, 4) }

    var body: some View {
        let reduced = reduceMotion || still
        let colors = InkBarColors(ink: state.isError ? c.error : ink ?? c.primary, pin: track ?? c.outlineVariant, mark: c.onSurfaceVariant)
        InkBarCanvas(state: state, fraction: state.value, colors: colors, live: state.isLive && !reduced)
            .animation(reduced ? .inkReduced : .inkProgress, value: state.value)
            .frame(height: Self.diameter(height))
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
                InkBar.draw(&context, size: size, state: state, fraction: fraction, seconds: live ? date.timeIntervalSinceReferenceDate : 0, colors: colors)
            }
        }
    }
}

/// Bar drawing, shared by the view and the frame tests.
enum InkBar {
    static func draw(_ context: inout GraphicsContext, size: CGSize, state: InkBarState, fraction: Double, seconds: Double, colors: InkBarColors) {
        let d = Double(size.height)
        let (count, pitch, first) = InkMath.barLayout(width: Double(size.width), diameter: d)
        guard count > 0 else { return }
        let full = d / 2, pin = full * InkMath.barEmptyScale
        var pins = Path(), dots = Path()
        let cy = d / 2
        func add(_ path: inout Path, _ i: Int, _ r: Double) {
            let cx = first + Double(i) * pitch
            path.addEllipse(in: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
        }
        var markSlot: Int? = nil
        switch state {
        case .indeterminate:
            for i in 0 ..< count { add(&dots, i, full * InkMath.barLoadingScale(slot: Double(i) + 0.5, seconds: seconds)) }
        default:
            let head = InkMath.barHead(fraction: fraction, count: count)
            let running = state.isLive
            for i in 0 ..< count {
                let c = Double(i) + 0.5
                var scale = InkMath.barFillScale(slot: c, head: head)
                if running && scale > 0 { scale *= InkMath.barRunningScale(slot: c, seconds: seconds) }
                let r = full * scale
                if r > pin {
                    add(&dots, i, r)
                } else if state.isPaused && markSlot == nil {
                    markSlot = i
                } else {
                    add(&pins, i, pin)
                }
            }
        }
        context.fill(pins, with: .color(colors.pin))
        context.fill(dots, with: .color(colors.ink.opacity(state.isPaused ? 0.45 : 1)))
        if let markSlot {
            // `‖` in place of the first empty slot: two short rules.
            let cx = first + Double(markSlot) * pitch
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
 `cardDotPitch` grid (grown so there are at most [maxDots]), each dot's wave phase (a long
 travelling wave, about one card width per cycle, along a shallow diagonal) and its weight (dots
 thin out around a fade point, so text over the field stays clean). Built once per size; a frame
 only computes radii.
 */
final class InkDotGrid {
    private(set) var size = CGSize.zero
    private(set) var pitch: Double = 0
    private(set) var xs: [Double] = []
    private(set) var ys: [Double] = []
    private(set) var phases: [Double] = []
    private(set) var weights: [Double] = []
    private var key: Key?

    private struct Key: Equatable {
        let size: CGSize
        let base: Double
        let maxDots: Int
        let fade: UnitPoint?
    }

    var count: Int { xs.count }

    func layout(_ size: CGSize, base: Double, maxDots: Int, fade: UnitPoint?) {
        let key = Key(size: size, base: base, maxDots: maxDots, fade: fade)
        guard key != self.key else { return }
        self.key = key
        self.size = size
        let w = Double(size.width), h = Double(size.height)
        pitch = InkMath.dotCell(width: w, height: h, base: base, maxDots: maxDots)
        let cols = max(0, Int(w / pitch)), rows = max(0, Int(h / pitch))
        let ox = (w - Double(cols) * pitch) / 2, oy = (h - Double(rows) * pitch) / 2
        // One cycle across ~the longer side, along a shallow diagonal (down-right).
        let wavelength = max(max(w, h), 1)
        let kx = 2 * .pi / wavelength * cos(0.45), ky = 2 * .pi / wavelength * sin(0.45)
        xs.removeAll(keepingCapacity: true)
        ys.removeAll(keepingCapacity: true)
        phases.removeAll(keepingCapacity: true)
        weights.removeAll(keepingCapacity: true)
        for r in 0 ..< rows {
            let y = oy + (Double(r) + 0.5) * pitch
            for col in 0 ..< cols {
                let x = ox + (Double(col) + 0.5) * pitch
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
    static func path(_ grid: InkDotGrid, seconds: Double, period: Double, seed: Double = 0, scale: Double = 0.36, fill: Double = 1) -> Path {
        var path = Path()
        let base = grid.pitch * scale * fill
        for i in 0 ..< grid.count {
            let r = base * grid.weights[i] * InkMath.cardWave(phase: grid.phases[i] + seed, seconds: seconds, period: period)
            guard r > 0.2 else { continue }
            path.addEllipse(in: CGRect(x: grid.xs[i] - r, y: grid.ys[i] - r, width: r * 2, height: r * 2))
        }
        return path
    }
}

/**
 A living card background (motion spec v3 §2): a halftone dot grid in a low-contrast tone whose
 radii follow a slow 2-D travelling wave (`cardWavePeriodSeconds` per cycle), on the shared
 ambient clock (≤10 fps, asleep after a minute without a touch, off in Low Power Mode, with
 Reduce Motion, off screen and while heavy work runs). ≤ `cardMaxDots` dots.
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
                grid.layout(size, base: Double(Tokens.Motion.cardDotPitch), maxDots: Int(Tokens.Motion.cardMaxDots), fade: fade)
                context.fill(InkField.path(grid, seconds: date.timeIntervalSinceReferenceDate, period: Double(Tokens.Motion.cardWavePeriodSeconds), seed: seed), with: .color(ink))
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

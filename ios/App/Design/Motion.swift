import Combine
import SwiftUI
import UIKit

// "Ink" motion (shared/design motion spec): ink flows, develops and settles into register. Every
// number comes from `Tokens.Motion`; the math lives in [InkMath] so it can be unit tested.

/// Pure motion math: easing, the develop stagger, the flow fill curve and the frame budget.
enum InkMath {
    static let maxDots = 2500
    /// Seconds for the flow fill to follow sampling progress (smooth, never per step).
    static let fillTimeConstant = 0.6
    /// Seconds for the flow speed to ease between flow, loading and shimmer.
    static let speedTimeConstant = 0.4
    /// Flow speed while the model loads, and while latent previews update.
    static let loadingSpeed = 0.5
    static let shimmerSpeed = 0.15
    /// Fill of a waiting (idle) plate.
    static let idleFill = 0.8
    /// Fill while the model loads (lighter than the first step).
    static var loadingFill: Double { Double(Tokens.Motion.flowMinFill) * 0.7 }

    static func clamp01(_ t: Double) -> Double { min(max(t, 0), 1) }

    static func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }

    static func easeOutCubic(_ t: Double) -> Double {
        let u = 1 - clamp01(t)
        return 1 - u * u * u
    }

    /// Ease-out with a slight overshoot past 1 before settling (register).
    static func easeOutBack(_ t: Double, overshoot s: Double = 1.70158) -> Double {
        let u = clamp01(t) - 1
        return 1 + (s + 1) * u * u * u + s * u * u
    }

    static func smoothstep(_ t: Double) -> Double {
        let x = clamp01(t)
        return x * x * (3 - 2 * x)
    }

    /// 30 fps, 15 fps in Low Power Mode.
    static func frameInterval(lowPower: Bool) -> TimeInterval {
        Double(lowPower ? Tokens.Motion.lowPowerFrameIntervalMillis : Tokens.Motion.frameIntervalMillis) / 1000
    }

    /// A loop only runs while its work runs, on screen, in the foreground, with motion allowed.
    static func shouldAnimate(active: Bool, visible: Bool, foreground: Bool, reduceMotion: Bool) -> Bool {
        active && visible && foreground && !reduceMotion
    }

    /**
     Develop progress of the dot at ([x], [y]) (0…1 across the plate) at overall progress [t]: the
     wave washes in along the flow diagonal, `clamp((t − s·(x+y)/2) / (1 − s))`, eased out.
     */
    static func developLocal(t: Double, x: Double, y: Double, stagger s: Double = Double(Tokens.Motion.developStagger)) -> Double {
        guard s < 1 else { return t >= 1 ? 1 : 0 }
        return easeOutCubic((t - s * (x + y) / 2) / (1 - s))
    }

    /// Fill the flow heads for at sampling [progress] (0…1): `flowMinFill` → 1, lighter while loading.
    static func flowFillTarget(progress: Double, loading: Bool) -> Double {
        if loading { return loadingFill }
        let min = Double(Tokens.Motion.flowMinFill)
        return min + (1 - min) * clamp01(progress)
    }

    /// Frame-rate independent exponential approach of [current] to [target].
    static func approach(_ current: Double, _ target: Double, dt: Double, timeConstant: Double) -> Double {
        guard timeConstant > 0 else { return target }
        return current + (target - current) * (1 - exp(-max(dt, 0) / timeConstant))
    }

    /// Wave phase advanced in [dt] seconds (one cycle = 2 phase units, `sin(π·phase)`).
    static func phaseStep(dt: Double, speed: Double) -> Double {
        dt * Double(Tokens.Motion.flowCyclesPerSecond) * 2 * speed
    }

    /// The dot pitch for a plate: [base], grown so the plate never holds more than [maxDots].
    static func dotCell(width: Double, height: Double, base: Double, maxDots: Int = maxDots) -> Double {
        guard width > 0, height > 0, base > 0 else { return base }
        let dots = (width / base) * (height / base)
        return dots <= Double(maxDots) ? base : (width * height / Double(maxDots)).squareRoot()
    }

    /// The procedural field (0…1) at ([x], [y]); the wave moves down-right as [phase] grows.
    static func fieldValue(x: Double, y: Double, phase: Double) -> Double {
        typealias H = Tokens.Halftone
        let wave = 0.5 + 0.5 * sin((x * Double(H.fieldWaveX) + y * Double(H.fieldWaveY) - phase) * .pi)
        let dx = x - Double(H.fieldBlobX), dy = y - Double(H.fieldBlobY)
        let blob = 1 - min(1, (dx * dx * Double(H.fieldBlobWeightX) + dy * dy * Double(H.fieldBlobWeightY)).squareRoot())
        return clamp01(Double(H.fieldWaveMix) * wave + Double(H.fieldBlobMix) * blob - Double(H.fieldFalloffY) * y)
    }

    // MARK: Print

    /// Dot growth while printing (1 → `printMergeScale`), [t] = print progress.
    static func printScale(_ t: Double) -> Double { 1 + (Double(Tokens.Motion.printMergeScale) - 1) * easeOutCubic(t) }

    /// The full-colour image fades in across the second half of the print.
    static func printImageAlpha(_ t: Double) -> Double { smoothstep((t - 0.5) / 0.5) }

    /// Offset (pt) of the register plate [elapsed] seconds into the print (settles with overshoot).
    static func registerShift(elapsed: Double) -> Double {
        Double(Tokens.Motion.registerOffset) * (1 - easeOutBack(elapsed / (Double(Tokens.Motion.registerMillis) / 1000)))
    }

    /// The register plate fades out once it has settled, by the end of the print.
    static func plateAlpha(elapsed: Double) -> Double {
        let settle = Double(Tokens.Motion.registerMillis) / 1000, end = Double(Tokens.Motion.printMillis) / 1000
        return 1 - clamp01((elapsed - settle) / max(end - settle, 0.001))
    }

    // MARK: v2: loaders, bars, ambient grounds, transitions

    static func easeInOutCubic(_ t: Double) -> Double {
        let x = clamp01(t)
        return x < 0.5 ? 4 * x * x * x : 1 - pow(-2 * x + 2, 3) / 2
    }

    /// The fractional part of [t] (0…1), for looping cycles.
    static func cycle(_ t: Double) -> Double { t - t.rounded(.down) }

    /// Loader: the drop's width along the diagonal and the resting dot fill (of the full radius).
    static let loaderDropWidth = 0.34
    static let loaderRestFill = 0.4
    /// Loader peak radius (of half the cell): just past touching, so the drop reads as one blot.
    static let loaderPeak = 1.08
    static var loaderCycleSeconds: Double { Double(Tokens.Motion.loaderCycleMillis) / 1000 }

    /// Loader cycle position (0…1) at [seconds] (a still mid-cycle frame for Reduce Motion).
    static func loaderPhase(_ seconds: Double) -> Double { cycle(seconds / loaderCycleSeconds) }

    /// Where the drop is along the diagonal at cycle position [t]: it enters before the first dot
    /// and leaves after the last, easing in and out.
    static func loaderDropCenter(_ t: Double) -> Double {
        -loaderDropWidth + (1 + 2 * loaderDropWidth) * smoothstep(cycle(t))
    }

    /// How much the loader dot at ([x], [y]) (0…1) swells (0…1) as the drop passes, at [t].
    static func loaderSwell(x: Double, y: Double, t: Double) -> Double {
        let d = ((x + y) / 2 - loaderDropCenter(t)) / loaderDropWidth
        return exp(-2 * d * d)
    }

    /// The loader dot's radius as a share of half its cell.
    static func loaderRadius(x: Double, y: Double, t: Double) -> Double {
        loaderRestFill + (loaderPeak - loaderRestFill) * loaderSwell(x: x, y: y, t: t)
    }

    /// Determinate bar: the lightest dot (share of the full fill) at the tail of the filled part.
    static let barTailDensity = 0.3

    /// Determinate bar density at [u] (0 tail … 1 leading edge of the filled part): a fine
    /// halftone gradient, densest at the head.
    static func barDensity(_ u: Double) -> Double {
        barTailDensity + (1 - barTailDensity) * pow(clamp01(u), 1.6)
    }

    /// Indeterminate bar: the sweeping segment and its trail, as shares of the track.
    static let sweepLength = 0.2
    static let sweepTrail = 0.24
    static var sweepSeconds: Double { Double(Tokens.Motion.indeterminateSweepMillis) / 1000 }

    /// The segment's leading edge (0 … 1 + length + trail) at sweep position [t]: it enters from
    /// the left, eases across and leaves on the right, trail and all.
    static func sweepHead(_ t: Double) -> Double { easeInOutCubic(cycle(t)) * (1 + sweepLength + sweepTrail) }

    /// Ink density (0…1) of the indeterminate bar at [u] (0…1 along the track): solid inside the
    /// segment, fading dots behind it.
    static func sweepDensity(_ u: Double, t: Double) -> Double {
        let head = sweepHead(t), tail = head - sweepLength
        if u > head { return 0 }
        if u >= tail { return 1 }
        let behind = (tail - u) / sweepTrail
        return behind >= 1 ? 0 : (1 - behind) * (1 - behind)
    }

    /// Ambient tier: 10 fps; never in Low Power Mode.
    static var ambientFrameInterval: TimeInterval { Double(Tokens.Motion.ambientFrameIntervalMillis) / 1000 }

    /// Ambient wave phase advanced in [dt] seconds: one cycle (2 units) per `ambientCycleSeconds`.
    static func ambientPhaseStep(dt: Double) -> Double { 2 * dt / Double(Tokens.Motion.ambientCycleSeconds) }

    /// The ambient register plate's breath (−1…1 pt) [elapsed] seconds in.
    static func ambientBreath(_ elapsed: Double) -> Double { sin(2 * .pi * elapsed / Double(Tokens.Motion.ambientBreathSeconds)) }

    /// An ambient ground only drifts on screen, in the foreground, with motion allowed, outside Low
    /// Power Mode, while no heavy job runs and until `ambientIdleStopSeconds` without a touch.
    static func ambientShouldRun(visible: Bool, foreground: Bool, reduceMotion: Bool, lowPower: Bool, heavyWork: Bool, idleSeconds: Double) -> Bool {
        visible && foreground && !reduceMotion && !lowPower && !heavyWork && idleSeconds < Double(Tokens.Motion.ambientIdleStopSeconds)
    }

    static var transitionSeconds: Double { Double(Tokens.Motion.transitionMillis) / 1000 }

    /// Sheets stagger at most this many sections; later ones enter with the last.
    static let maxStaggerSections = 6

    /// Entry delay (s) of sheet section [index].
    static func staggerDelay(_ index: Int) -> Double {
        Double(min(max(index, 0), maxStaggerSections - 1)) * Double(Tokens.Motion.staggerMillis) / 1000
    }

    /// Dissolve: dot radius (share of a cell) that covers the row completely.
    static let dissolveCover = 0.75
    static let dissolveStagger = 0.35

    /// A dissolving row's dot radius (share of its cell) at ([x], [y]) at progress [t]: the dots
    /// shrink to nothing along the flow diagonal.
    static func dissolveRadius(t: Double, x: Double, y: Double) -> Double {
        // Linear per dot: an eased shrink empties the row before the collapse is seen.
        let s = dissolveStagger
        return dissolveCover * (1 - clamp01((t - s * (x + y) / 2) / (1 - s)))
    }

    /// Ink alpha of paper printing away at [p] (0…1) from its resting [rest]: it darkens to a
    /// print for a moment, then lifts off (0 at the end).
    static func printAwayAlpha(_ p: Double, rest: Double) -> Double {
        guard p > 0 else { return rest }
        let peak = max(rest, 0.28)
        return lerp(rest, peak, smoothstep(p / 0.3)) * (1 - smoothstep((p - 0.35) / 0.65))
    }
}

/// Unit curves for keyframes and SwiftUI animations.
extension UnitCurve {
    static let inkOut = UnitCurve.bezier(startControlPoint: UnitPoint(x: 0.33, y: 1), endControlPoint: UnitPoint(x: 0.68, y: 1))
    static let inkBack = UnitCurve.bezier(startControlPoint: UnitPoint(x: 0.34, y: 1.56), endControlPoint: UnitPoint(x: 0.64, y: 1))
}

extension Animation {
    /// The Reduce Motion replacement for any state change.
    static var inkReduced: Animation { .easeInOut(duration: Double(Tokens.Motion.reducedMotionMillis) / 1000) }
    static var inkProgress: Animation { .timingCurve(0.33, 1, 0.68, 1, duration: Double(Tokens.Motion.progressMillis) / 1000) }
}

extension EnvironmentValues {
    /// Renders every ink animation at its end state (snapshot tests).
    @Entry var inkStill = false
}

/// Frame budget of an [InkTimeline]: work (30 / 15 fps) or ambient (10 fps, off in Low Power).
enum InkTier { case work, ambient }

/**
 A frame clock for procedural ink: a TimelineView capped at 30 fps (15 in Low Power Mode) that
 only ticks while [active], on screen, in the foreground and with Reduce Motion off. Paused, it
 still renders once, so content must draw a sensible still for any date.
 */
struct InkTimeline<Content: View>: View {
    let active: Bool
    /// `.ambient`: 10 fps and never in Low Power Mode (living backgrounds).
    var tier = InkTier.work
    @ViewBuilder let content: (Date) -> Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.inkStill) private var still
    @Environment(\.scenePhase) private var scenePhase
    @State private var visible = false
    @State private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled

    var body: some View {
        let ambient = tier == .ambient
        let running = InkMath.shouldAnimate(active: active && !(ambient && lowPower), visible: visible, foreground: scenePhase == .active, reduceMotion: reduceMotion || still)
        let interval = ambient ? InkMath.ambientFrameInterval : InkMath.frameInterval(lowPower: lowPower)
        TimelineView(.animation(minimumInterval: interval, paused: !running)) { context in
            content(context.date)
        }
        .onAppear { visible = true }
        .onDisappear { visible = false }
        .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange).receive(on: RunLoop.main)) { _ in
            lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        }
    }
}

/// Integrates the flow: wave phase and fill follow their targets smoothly, whatever the frame rate.
@MainActor
final class FlowState {
    private(set) var phase: Double
    private(set) var fill: Double
    private var speed: Double = 1
    private var last: Date?

    init(phase: Double = 0, fill: Double = Double(Tokens.Motion.flowMinFill)) {
        self.phase = phase
        self.fill = fill
    }

    /// Steps to [date]; a long gap (paused, off screen) counts as one short frame, so nothing jumps.
    func advance(to date: Date, speed target: Double, fill fillTarget: Double) {
        defer { last = date }
        guard let last else { return }
        let dt = min(max(date.timeIntervalSince(last), 0), 0.1)
        speed = InkMath.approach(speed, target, dt: dt, timeConstant: InkMath.speedTimeConstant)
        phase += InkMath.phaseStep(dt: dt, speed: speed)
        fill = InkMath.approach(fill, fillTarget, dt: dt, timeConstant: InkMath.fillTimeConstant)
    }

    func set(fill: Double) { self.fill = fill }
}

/**
 Animated procedural halftone (HalftoneField that flows): the wave advances at
 `flowCyclesPerSecond`·[speed] while [active]; the dot fill eases toward [fill] (per frame date, so
 a pulse can decay). One Path, one fill per frame. Static with Reduce Motion.
 */
struct FlowingHalftone: View {
    var active = true
    var speed = 1.0
    var dot: Color? = nil
    var cell: CGFloat = Tokens.Halftone.fieldCell
    var phase = 0.0
    var fill: (Date) -> Double = { _ in 1 }
    @Environment(\.scheme) private var scheme
    @State private var flow = FlowState()

    var body: some View {
        InkTimeline(active: active) { date in
            Canvas { context, size in
                flow.advance(to: date, speed: speed, fill: fill(date))
                let pitch = InkMath.dotCell(width: size.width, height: size.height, base: cell)
                context.fill(HalftonePaths.field(size: size, cell: pitch, phase: phase + flow.phase, fill: flow.fill), with: .color(dot ?? scheme.primary))
            }
        }
        .clipped()
        .accessibilityHidden(true)
    }
}

/// Dot paths shared by the static and animated halftones.
enum HalftonePaths {
    /// The procedural field on a centred grid of [cell] pitch, radius scaled by [fill].
    static func field(size: CGSize, cell: CGFloat, phase: Double, fill: Double) -> Path {
        let cols = max(1, Int(size.width / cell)), rows = max(1, Int(size.height / cell))
        let ox = (size.width - CGFloat(cols) * cell) / 2, oy = (size.height - CGFloat(rows) * cell) / 2
        let scale = Double(cell * Tokens.Halftone.fieldRadiusFactor) * fill
        var path = Path()
        for r in 0 ..< rows {
            let y = (Double(r) + 0.5) / Double(rows)
            for c in 0 ..< cols {
                let radius = CGFloat(InkMath.fieldValue(x: (Double(c) + 0.5) / Double(cols), y: y, phase: phase) * scale)
                if radius > Tokens.Halftone.fieldMinRadius {
                    let cx = ox + (CGFloat(c) + 0.5) * cell, cy = oy + (CGFloat(r) + 0.5) * cell
                    path.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
                }
            }
        }
        return path
    }
}

/**
 The thinking / composing indicator: a tiny halftone strip (3 × 8 dots) whose dots ripple with
 the flow. Shown only while a turn streams without visible text; static with Reduce Motion.
 */
struct HalftoneStrip: View {
    var color: Color? = nil
    var rows = 3
    var cols = 8
    var cell: CGFloat = 4
    @Environment(\.scheme) private var scheme
    @State private var flow = FlowState(fill: 1)

    var body: some View {
        InkTimeline(active: true) { date in
            Canvas { context, _ in
                flow.advance(to: date, speed: 2.2, fill: 1)
                var path = Path()
                for r in 0 ..< rows {
                    for c in 0 ..< cols {
                        let wave = 0.5 + 0.5 * sin((Double(c) * 0.45 + Double(r) * 0.3 - flow.phase) * .pi)
                        let radius = cell * 0.46 * CGFloat(0.22 + 0.78 * wave)
                        let cx = (CGFloat(c) + 0.5) * cell, cy = (CGFloat(r) + 0.5) * cell
                        path.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
                    }
                }
                context.fill(path, with: .color(color ?? scheme.primary))
            }
        }
        .frame(width: CGFloat(cols) * cell, height: CGFloat(rows) * cell)
        .accessibilityLabel("Working")
    }
}

/// What an [InkProgressBar] shows.
enum InkBarState: Equatable {
    /// [fraction] done; [running] adds the flowing head.
    case progress(Double, running: Bool)
    /// Working, amount unknown: a short segment sweeps across, leaving a fading trail.
    case indeterminate
    /// Stopped at [fraction]: static, dimmed, with a `‖` mark.
    case paused(Double)
    /// Failed: fills red and holds.
    case error(Double)
}

/**
 Progress bar in ink (motion spec v2 §1). Determinate: the width eases to each value over
 `progressMillis` (no jumps); the filled part is a fine halftone gradient, densest at the leading
 edge, and while running a small flowing dot cluster leads it. Indeterminate: a short ink segment
 sweeps with ease-in-out over `indeterminateSweepMillis`, leaving a fading dot trail. Paused:
 static, dimmed, `‖`. Error: fills red, then holds. One Canvas, one path; static with Reduce Motion.
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

    var body: some View {
        let reduced = reduceMotion || still
        let color = state.isError ? c.error : ink ?? c.primary
        let value = state.value
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Rectangle().fill(track ?? c.outlineVariant)
                if state == .indeterminate {
                    InkSweep(color: color, reduced: reduced)
                } else {
                    InkFill(fraction: value, color: color, solid: state.isError)
                        .opacity(state.isPaused ? 0.45 : 1)
                        .animation(reduced ? .inkReduced : .inkProgress, value: value)
                }
            }
            // Dense dots merge into a solid rule at the bar's height.
            .clipped()
            .overlay(alignment: .leading) {
                let width = geo.size.width * value
                if case .progress(_, true) = state, !reduced, value < 1 {
                    InkHead(color: color)
                        .frame(width: InkHead.length, height: 9)
                        .offset(x: min(width, max(geo.size.width - InkHead.length, 0)))
                        .animation(.inkProgress, value: value)
                        .allowsHitTesting(false)
                } else if state.isPaused {
                    Text("‖")
                        .textStyle(.micro)
                        .foregroundStyle(c.onSurfaceVariant)
                        .fixedSize()
                        .offset(x: min(width + 3, max(geo.size.width - 8, 0)))
                        .allowsHitTesting(false)
                }
            }
        }
        .frame(height: height)
        .accessibilityElement()
        .accessibilityLabel(state.label)
        .accessibilityValue(state == .indeterminate ? "" : "\(Int(value * 100)) percent")
    }
}

private extension InkBarState {
    var value: Double {
        switch self {
        case .progress(let f, _), .paused(let f): InkMath.clamp01(f)
        case .error: 1
        case .indeterminate: 0
        }
    }

    var isError: Bool { if case .error = self { true } else { false } }
    var isPaused: Bool { if case .paused = self { true } else { false } }

    var label: String {
        switch self {
        case .progress: "Progress"
        case .indeterminate: "Working"
        case .paused: "Paused"
        case .error: "Failed"
        }
    }
}

/// The filled part of a determinate bar: a row of dots that grow toward the leading edge until
/// they merge (animatable, so the width eases without per-dot views).
private struct InkFill: View, Animatable {
    var fraction: Double
    let color: Color
    var solid = false

    nonisolated var animatableData: Double {
        get { fraction }
        set { fraction = newValue }
    }

    var body: some View {
        Canvas { context, size in
            let width = size.width * CGFloat(InkMath.clamp01(fraction))
            guard width > 0.5 else { return }
            if solid {
                context.fill(Path(CGRect(x: 0, y: 0, width: width, height: size.height)), with: .color(color))
                return
            }
            context.fill(InkBarDots.fill(size: size, width: width), with: .color(color))
        }
    }
}

/// Indeterminate: one segment sweeping across with its fading trail.
private struct InkSweep: View {
    let color: Color
    let reduced: Bool

    var body: some View {
        InkTimeline(active: !reduced) { date in
            Canvas { context, size in
                let t = reduced ? 0.5 : InkMath.cycle(date.timeIntervalSinceReferenceDate / InkMath.sweepSeconds)
                context.fill(InkBarDots.sweep(size: size, t: t), with: .color(color))
            }
        }
    }
}

/// Bar dots: rows of dots (pitch ≈ the bar's height) across [width], their radius following
/// [density] (0…1) at x; dense dots overlap into solid ink.
enum InkBarDots {
    /// A determinate fill [width] wide: densest at its leading edge.
    static func fill(size: CGSize, width: CGFloat) -> Path {
        path(size: size, width: width) { InkMath.barDensity(Double($0 / width)) }
    }

    /// The indeterminate segment and its trail at sweep position [t].
    static func sweep(size: CGSize, t: Double) -> Path {
        path(size: size, width: size.width) { InkMath.sweepDensity(Double($0 / size.width), t: t) }
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
}

/// Dots spreading ahead of a progress bar's head, thinning with distance, rippling with the flow.
private struct InkHead: View {
    static let length: CGFloat = 18
    let color: Color
    @State private var flow = FlowState(fill: 1)

    var body: some View {
        InkTimeline(active: true) { date in
            Canvas { context, size in
                flow.advance(to: date, speed: 2, fill: 1)
                let cell: CGFloat = 3, cols = Int(size.width / cell), rows = 3
                var path = Path()
                for r in 0 ..< rows {
                    for col in 0 ..< cols {
                        let distance = Double(col) / Double(max(cols - 1, 1))
                        let wave = 0.5 + 0.5 * sin((Double(col) * 0.7 + Double(r) * 0.5 - flow.phase) * .pi)
                        let radius = cell * 0.5 * CGFloat((1 - distance) * (0.35 + 0.65 * wave))
                        guard radius > 0.25 else { continue }
                        let cx = (CGFloat(col) + 0.5) * cell, cy = size.height / 2 + (CGFloat(r) - 1) * cell
                        path.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
                    }
                }
                context.fill(path, with: .color(color))
            }
        }
    }
}

// MARK: - Modifiers

private struct InkStamp<Value: Equatable>: ViewModifier {
    let trigger: Value
    let when: (Value) -> Bool
    @State private var count = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.inkStill) private var still

    func body(content: Content) -> some View {
        let reduced = reduceMotion
        let scale = Double(Tokens.Motion.stampScale), rotation = Double(Tokens.Motion.stampRotationDegrees)
        let duration = Double(reduced ? Tokens.Motion.reducedMotionMillis : Tokens.Motion.stampMillis) / 1000
        content
            .keyframeAnimator(initialValue: 1.0, trigger: count) { view, p in
                view
                    .scaleEffect(reduced ? 1 : InkMath.lerp(scale, 1, p))
                    .rotationEffect(.degrees(reduced ? 0 : rotation * (1 - p)))
                    .opacity(p)
            } keyframes: { _ in
                KeyframeTrack {
                    MoveKeyframe(0.0)
                    LinearKeyframe(1.0, duration: duration, timingCurve: .inkOut)
                }
            }
            .onChange(of: trigger) { _, value in
                if !still && when(value) { count += 1 }
            }
    }
}

private struct InkEnter: ViewModifier {
    let onEntered: () -> Void
    @State private var shown: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(enabled: Bool, onEntered: @escaping () -> Void) {
        self.onEntered = onEntered
        _shown = State(initialValue: !enabled)
    }

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : Tokens.Motion.enterRise)
            .onAppear {
                guard !shown else { return }
                onEntered()
                withAnimation(reduceMotion ? .inkReduced : .timingCurve(0.33, 1, 0.68, 1, duration: Double(Tokens.Motion.enterMillis) / 1000)) { shown = true }
            }
    }
}

private struct GhostFrame {
    var settle = 1.0
    var alpha = 0.0
}

private struct RegisterGhost: ViewModifier {
    let color: Color
    let cornerRadius: CGFloat
    @State private var count = 0
    @State private var enabled: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(enabled: Bool, color: Color, cornerRadius: CGFloat) {
        self.color = color
        self.cornerRadius = cornerRadius
        _enabled = State(initialValue: enabled)
    }

    func body(content: Content) -> some View {
        let offset = Tokens.Motion.registerOffset, duration = Double(Tokens.Motion.registerMillis) / 1000
        let shape = RoundedRectangle(cornerRadius: cornerRadius)
        let color = color
        content
            .background {
                Color.clear.keyframeAnimator(initialValue: GhostFrame(), trigger: count) { view, frame in
                    view.background {
                        shape.fill(color.opacity(0.5 * frame.alpha))
                            .offset(x: offset * (1 - frame.settle), y: offset * (1 - frame.settle))
                    }
                } keyframes: { _ in
                    KeyframeTrack(\.settle) {
                        MoveKeyframe(0.0)
                        LinearKeyframe(1.0, duration: duration, timingCurve: .inkBack)
                    }
                    KeyframeTrack(\.alpha) {
                        MoveKeyframe(1.0)
                        LinearKeyframe(1.0, duration: duration * 0.75)
                        LinearKeyframe(0.0, duration: duration * 0.5)
                    }
                }
            }
            .onAppear {
                guard enabled, !reduceMotion else { return }
                enabled = false
                count += 1
            }
    }
}

extension View {
    /// Pops in like a rubber stamp (scale, rotation, opacity) when [trigger] changes to a value
    /// [when] accepts. Only live changes, never the first render.
    func stamp<V: Equatable>(_ trigger: V, when: @escaping (V) -> Bool = { _ in true }) -> some View {
        modifier(InkStamp(trigger: trigger, when: when))
    }

    /// New content rises `enterRise` pt and fades in over `enterMillis`, when [enabled] (items
    /// added after the screen loaded). [onEntered] fires once it starts.
    func inkEnter(_ enabled: Bool, onEntered: @escaping () -> Void = {}) -> some View {
        modifier(InkEnter(enabled: enabled, onEntered: onEntered))
    }

    /// Enters "out of register": a ghost block offset `registerOffset` pt behind the view settles
    /// into place (ease-out-back) over `registerMillis`, then disappears.
    func registerGhost(_ enabled: Bool, color: Color, cornerRadius: CGFloat = AutobotShapes.extraSmall) -> some View {
        modifier(RegisterGhost(enabled: enabled, color: color, cornerRadius: cornerRadius))
    }
}

/**
 Which transcript entries are new: everything present when the screen first rendered, or that
 arrived in one bulk load (history), is not; small additions afterwards are, until they entered.
 Not observed: it is read and updated while the body is evaluated.
 */
@MainActor
final class InkLedger {
    static let bulk = 3
    private var known: Set<String> = []
    private var live: Set<String> = []
    private var seeded = false

    func observe(_ keys: [String]) {
        let added = keys.filter { !known.contains($0) }
        known.formUnion(added)
        if seeded && !added.isEmpty && added.count <= Self.bulk { live.formUnion(added) }
        seeded = true
    }

    func isNew(_ key: String) -> Bool { live.contains(key) }

    func entered(_ key: String) { live.remove(key) }
}

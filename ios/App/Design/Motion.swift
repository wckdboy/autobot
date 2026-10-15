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

    /// Ambient tier: 10 fps; never in Low Power Mode.
    static var ambientFrameInterval: TimeInterval { Double(Tokens.Motion.ambientFrameIntervalMillis) / 1000 }

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
 Procedural ink on the shared frame clock ([InkClock], one per tier): while [active], on screen,
 in the foreground and with Reduce Motion off, [content] is re-run for every tick of the tier's
 clock (30 / 15 fps, or 10 fps ambient). Only the small leaf that calls [content] reads the clock,
 so a tick never re-evaluates the view that owns the timeline. Paused, it renders once, so
 content must draw a sensible still for any date.
 */
struct InkTimeline<Content: View>: View {
    let active: Bool
    /// `.ambient`: 10 fps, never in Low Power Mode, asleep after a minute without a touch.
    var tier = InkTier.work
    @ViewBuilder let content: (Date) -> Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.inkStill) private var still
    @Environment(\.scenePhase) private var scenePhase
    @State private var visible = false
    @State private var subscription = InkSubscription()

    var body: some View {
        let running = InkMath.shouldAnimate(active: active, visible: visible, foreground: scenePhase == .active, reduceMotion: reduceMotion || still)
        let clock = InkClock.shared(tier)
        InkTick(clock: running ? clock : nil, content: content)
            .onAppear { visible = true }
            .onDisappear { visible = false }
            .onChange(of: running, initial: true) { _, run in subscription.set(run ? clock : nil) }
    }
}

/// The leaf that reads the clock: re-evaluated on every tick, alone.
private struct InkTick<Content: View>: View {
    let clock: InkClock?
    let content: (Date) -> Content

    var body: some View {
        let _ = InkProbe.hit("ink.tick")
        content(clock?.now ?? Date())
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
                InkProbe.hit("ink.draw")
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
                InkProbe.hit("ink.draw")
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

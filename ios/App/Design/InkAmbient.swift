import SwiftUI
import UIKit
import UIKit.UIGestureRecognizerSubclass

// Ink motion v2 (motion spec v2): the loader, living paper grounds, screen and sheet transitions and
// the list dissolve. Same budget as v1: one Canvas pass and one path per colour per frame, frame
// clocks only while there is a reason, static with Reduce Motion.

extension EnvironmentValues {
    /// A heavy job runs (image generation, an agent turn): ambient grounds hold still.
    @Entry var inkHeavyWork = false
    /// Print-away progress (0…1) of the screen being left (the lock screen on unlock).
    @Entry var inkPrintAway = 0.0
    /// The screen inks in on arrival ([View.inkArrive]); its top bar prints its title in register.
    @Entry var inkArrives = false
}

extension Animation {
    /// Row deletion: the dots shrink to nothing over `dissolveMillis`.
    static var inkDissolve: Animation { .linear(duration: Double(Tokens.Motion.dissolveMillis) / 1000) }
    /// Tabs and segmented selections slide with ease-out over `transitionMillis`.
    static var inkSlide: Animation { .timingCurve(0.33, 1, 0.68, 1, duration: InkMath.transitionSeconds) }
}

// MARK: - Loader

/**
 The indeterminate indicator (motion spec v2 §1): a small square of halftone dots where a soft
 ink drop travels the diagonal and the dots swell as it passes, once per `loaderCycleMillis`.
 Sizes: inline (12pt), button (16pt, in place of a label) and block (48pt, optional caption).
 Only on screen while work runs; a still mid-cycle frame with Reduce Motion.
 */
struct InkLoader: View {
    enum Size {
        case inline, button, block

        var side: CGFloat {
            switch self {
            case .inline: 12
            case .button: 16
            case .block: 48
            }
        }

        var dots: Int { self == .block ? 5 : 4 }
    }

    var size = Size.inline
    var caption: String? = nil
    var color: Color? = nil
    @Environment(\.scheme) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.inkStill) private var still

    init(_ size: Size = .inline, caption: String? = nil, color: Color? = nil) {
        self.size = size
        self.caption = caption
        self.color = color
    }

    var body: some View {
        let reduced = reduceMotion || still
        let ink = color ?? c.primary
        let n = size.dots
        VStack(spacing: 8) {
            InkTimeline(active: !reduced) { date in
                Canvas { context, canvas in
                    let t = reduced ? 0.5 : InkMath.loaderPhase(date.timeIntervalSinceReferenceDate)
                    context.fill(Self.dots(side: min(canvas.width, canvas.height), count: n, t: t), with: .color(ink))
                }
            }
            .frame(width: size.side, height: size.side)
            if size == .block, let caption { MicroLabel(caption).fixedSize() }
        }
        .accessibilityElement()
        .accessibilityLabel(caption ?? "Working")
    }

    /// The loader's [count]² dots in a [side] square at cycle position [t].
    static func dots(side: CGFloat, count n: Int, t: Double) -> Path {
        let cell = side / CGFloat(n)
        var path = Path()
        for r in 0 ..< n {
            let y = (Double(r) + 0.5) / Double(n)
            for col in 0 ..< n {
                let x = (Double(col) + 0.5) / Double(n)
                let radius = cell * 0.5 * CGFloat(InkMath.loaderRadius(x: x, y: y, t: t))
                let cx = (CGFloat(col) + 0.5) * cell, cy = (CGFloat(r) + 0.5) * cell
                path.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
            }
        }
        return path
    }
}

/// A label that steps aside for an [InkLoader] while [busy], keeping its size (buttons keep
/// their width).
struct InkBusyLabel<Label: View>: View {
    let busy: Bool
    var size = InkLoader.Size.button
    var color: Color? = nil
    @ViewBuilder let label: () -> Label

    var body: some View {
        label()
            .opacity(busy ? 0 : 1)
            .overlay { if busy { InkLoader(size, color: color) } }
            .accessibilityValue(busy ? "working" : "")
    }
}

// MARK: - Living paper

/// The last touch anywhere in the app (ambient grounds sleep after `ambientIdleStopSeconds`).
@MainActor
@Observable
final class InkIdleClock {
    static let shared = InkIdleClock()
    private(set) var lastTouch = Date()

    func touch(at now: Date = Date()) {
        // At most one update a second: grounds only care about the minute.
        if now.timeIntervalSince(lastTouch) > 1 { lastTouch = now }
    }
}

/// Sees every touch that starts in the window and fails at once, so it never takes part.
private final class InkTouchSensor: UIGestureRecognizer, UIGestureRecognizerDelegate {
    override init(target: Any?, action: Selector?) {
        super.init(target: target, action: action)
        cancelsTouchesInView = false
        delaysTouchesBegan = false
        delaysTouchesEnded = false
        delegate = self
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
        InkIdleClock.shared.touch()
        state = .failed
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }
}

/// Installs one [InkTouchSensor] on the window it lands in.
private struct InkTouchWatcher: UIViewRepresentable {
    final class Host: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let window, !(window.gestureRecognizers ?? []).contains(where: { $0 is InkTouchSensor }) else { return }
            window.addGestureRecognizer(InkTouchSensor(target: nil, action: nil))
        }
    }

    func makeUIView(context: Context) -> Host {
        let host = Host()
        host.isUserInteractionEnabled = false
        return host
    }

    func updateUIView(_ uiView: Host, context: Context) {}
}

/// Integrates the ambient drift and breath; a gap (asleep, off screen) counts as one short frame.
@MainActor
final class AmbientState {
    private(set) var phase: Double
    private(set) var elapsed = 0.0
    private var last: Date?

    init(phase: Double = 0) { self.phase = phase }

    func advance(to date: Date) {
        defer { last = date }
        guard let last else { return }
        let dt = min(max(date.timeIntervalSince(last), 0), 0.2)
        phase += InkMath.ambientPhaseStep(dt: dt)
        elapsed += dt
    }
}

/**
 Living paper (motion spec v2 §2): the hero halftone field drifting extremely slowly (one cycle per
 `ambientCycleSeconds`) in a low-contrast ink at `ambientOpacity`, with a second, primary plate out
 of register that breathes ±1pt over `ambientBreathSeconds`. Drawn behind content at ≤10 fps (the
 ambient tier); holds still after `ambientIdleStopSeconds` without a touch (a touch wakes it),
 while a heavy job runs, in Low Power Mode and with Reduce Motion. [hero] draws the field at full
 strength in [dot] (the welcome hero), without the plate.
 */
struct InkGround: View {
    var dot: Color? = nil
    var plate = true
    var hero = false
    var cell: CGFloat = 12
    var phase = 0.0
    @Environment(\.scheme) private var c
    @Environment(\.inkHeavyWork) private var heavy
    @Environment(\.inkPrintAway) private var printing
    @State private var ambient = AmbientState()
    @State private var asleep = false
    private var idle: InkIdleClock { InkIdleClock.shared }

    var body: some View {
        let ink = dot ?? (hero ? c.primary : c.outline)
        let plateInk = c.primary
        let opacity = hero ? 1 : Double(Tokens.Motion.ambientOpacity)
        let printing = printing
        let lastTouch = idle.lastTouch
        InkTimeline(active: !heavy && !asleep, tier: .ambient) { date in
            Canvas { context, size in
                ambient.advance(to: date)
                Self.draw(&context, size: size, cell: cell, phase: phase + ambient.phase, breath: InkMath.ambientBreath(ambient.elapsed),
                          printing: printing, ink: ink, plate: plate ? plateInk : nil, opacity: opacity)
            }
        }
        .background(InkTouchWatcher())
        .task(id: lastTouch) {
            asleep = false
            try? await Task.sleep(for: .seconds(Double(Tokens.Motion.ambientIdleStopSeconds)))
            if !Task.isCancelled { asleep = true }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// One frame of paper: the field in [ink] and, when given, the [plate] out of register by
    /// `registerOffset` + [breath] pt. [printing] (0…1): the dots swell into solid ink, the paper
    /// darkens for a moment, the plate settles into register, then it all lifts off.
    static func draw(_ context: inout GraphicsContext, size: CGSize, cell: CGFloat, phase: Double, breath: Double, printing: Double,
                     ink: Color, plate: Color?, opacity: Double) {
        let pitch = InkMath.dotCell(width: size.width, height: size.height, base: cell)
        let fill = printing > 0 ? InkMath.printScale(printing) : 1
        let alpha = InkMath.printAwayAlpha(printing, rest: opacity)
        let field = HalftonePaths.field(size: size, cell: pitch, phase: phase, fill: fill)
        if let plate {
            let settle = printing > 0 ? 1 - InkMath.easeOutBack(printing * Double(Tokens.Motion.printMillis / Tokens.Motion.registerMillis)) : 1
            let shift = CGFloat((Double(Tokens.Motion.registerOffset) + breath) * settle)
            var shifted = context
            shifted.translateBy(x: shift, y: shift)
            shifted.fill(field, with: .color(plate.opacity(alpha * 0.6)))
        }
        context.fill(field, with: .color(ink.opacity(alpha)))
    }
}

/// The lock screen leaving: its paper prints away (dots swell, the plate settles, all fades).
private struct InkPrintAway: ViewModifier, Animatable {
    var progress: Double

    nonisolated var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    func body(content: Content) -> some View {
        content
            .environment(\.inkPrintAway, progress)
            .opacity(1 - InkMath.smoothstep(progress))
    }
}

private struct InkPrintAwayTransition: Transition {
    func body(content: Content, phase: TransitionPhase) -> some View {
        content.modifier(InkPrintAway(progress: phase == .didDisappear ? 1 : 0))
    }
}

extension AnyTransition {
    /// Leaves by printing away ([InkGround] inside reads the progress); fades with Reduce Motion
    /// (the animation is then the short crossfade).
    static var inkPrintAway: AnyTransition { AnyTransition(InkPrintAwayTransition()) }
}

// MARK: - Transitions

/// Screens ink in: content enters `registerOffset` pt out of register (down-right) and settles
/// with a slight overshoot over `transitionMillis` while it fades in; the top bar's title prints a
/// primary plate that settles into register. Once per screen; a crossfade with Reduce Motion.
private struct InkArrive: ViewModifier {
    @State private var arrived = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.inkStill) private var still

    func body(content: Content) -> some View {
        let shown = arrived || still
        let shift = shown || reduceMotion ? 0 : Tokens.Motion.registerOffset
        let duration = InkMath.transitionSeconds
        content
            .environment(\.inkArrives, !still)
            .offset(x: shift, y: shift)
            .animation(.timingCurve(0.34, 1.56, 0.64, 1, duration: duration), value: shown)
            .opacity(shown ? 1 : 0)
            .animation(reduceMotion ? .inkReduced : .timingCurve(0.33, 1, 0.68, 1, duration: duration * 0.7), value: shown)
            .onAppear { arrived = true }
    }
}

/// A title's primary plate: a primary silhouette of the title, `registerOffset` pt up-left of the
/// (arriving) title, settling into register over `transitionMillis`, then gone.
private struct TitleGhost: ViewModifier {
    let color: Color
    @State private var count = 0
    @State private var printed = false
    @Environment(\.inkArrives) private var arrives
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let offset = Tokens.Motion.registerOffset, duration = InkMath.transitionSeconds
        let color = color
        content
            .background {
                color.mask { content }
                    .keyframeAnimator(initialValue: GhostKeyframe(), trigger: count) { view, frame in
                        view
                            .opacity(0.8 * frame.alpha)
                            .offset(x: -offset * (1 - frame.settle), y: -offset * (1 - frame.settle))
                    } keyframes: { _ in
                        KeyframeTrack(\.settle) {
                            MoveKeyframe(0.0)
                            LinearKeyframe(1.0, duration: duration, timingCurve: .inkBack)
                        }
                        KeyframeTrack(\.alpha) {
                            MoveKeyframe(1.0)
                            LinearKeyframe(1.0, duration: duration * 0.6)
                            LinearKeyframe(0.0, duration: duration * 0.6)
                        }
                    }
                    .accessibilityHidden(true)
            }
            .onAppear {
                guard arrives, !printed, !reduceMotion else { return }
                printed = true
                count += 1
            }
    }
}

private struct GhostKeyframe {
    var settle = 1.0
    var alpha = 0.0
}

/// A sheet section ink-entering [index] × `staggerMillis` after the sheet opens.
private struct InkSection: ViewModifier {
    let index: Int
    @State private var shown = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.inkStill) private var still

    func body(content: Content) -> some View {
        let visible = shown || still
        content
            .opacity(visible ? 1 : 0)
            .offset(y: visible || reduceMotion ? 0 : Tokens.Motion.enterRise)
            .onAppear {
                guard !shown else { return }
                let enter: Animation = reduceMotion ? .inkReduced : .timingCurve(0.33, 1, 0.68, 1, duration: Double(Tokens.Motion.enterMillis) / 1000)
                withAnimation(enter.delay(InkMath.staggerDelay(index))) { shown = true }
            }
    }
}

/**
 A VStack whose children ink in one after another (sheets: `staggerMillis` apart, at most
 `maxStaggerSections` steps). Children added later ink in on arrival.
 */
struct InkStack<Content: View>: View {
    var alignment = HorizontalAlignment.leading
    var spacing: CGFloat? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: alignment, spacing: spacing) {
            Group(subviews: content) { subviews in
                ForEach(Array(subviews.enumerated()), id: \.element.id) { index, subview in
                    subview.modifier(InkSection(index: index))
                }
            }
        }
    }
}

/// Row deletion: the row breaks into dots that shrink to nothing along the flow diagonal.
private struct InkDissolveMask: ViewModifier, Animatable {
    var amount: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    nonisolated var animatableData: Double {
        get { amount }
        set { amount = newValue }
    }

    func body(content: Content) -> some View {
        let amount = amount, dotted = amount > 0 && !reduceMotion
        content
            .opacity(reduceMotion ? 1 - amount : 1)
            .mask {
                if dotted {
                    Canvas { context, size in
                        context.fill(InkDissolve.dots(size: size, amount: amount), with: .color(.black))
                    }
                } else {
                    Rectangle()
                }
            }
    }
}

/// Deletion collapses the row with a halftone dissolve (`dissolveMillis`, see [Animation.inkDissolve]).
struct InkDissolve: Transition {
    static let cell: CGFloat = 4

    func body(content: Content, phase: TransitionPhase) -> some View {
        content.modifier(InkDissolveMask(amount: phase == .didDisappear ? 1 : 0))
    }

    /// The dissolving dots of a [size] row at [amount] (0 whole … 1 gone); ≤ 2500 dots.
    static func dots(size: CGSize, amount: Double) -> Path {
        let pitch = CGFloat(InkMath.dotCell(width: size.width, height: size.height, base: cell))
        let cols = max(1, Int((size.width / pitch).rounded(.up))), rows = max(1, Int((size.height / pitch).rounded(.up)))
        var path = Path()
        for r in 0 ..< rows {
            let y = (Double(r) + 0.5) / Double(rows)
            for col in 0 ..< cols {
                let radius = pitch * CGFloat(InkMath.dissolveRadius(t: amount, x: (Double(col) + 0.5) / Double(cols), y: y))
                guard radius > 0.1 else { continue }
                let cx = (CGFloat(col) + 0.5) * pitch, cy = (CGFloat(r) + 0.5) * pitch
                path.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
            }
        }
        return path
    }
}

extension AnyTransition {
    /// Rows: appear as they are, leave by dissolving.
    static var inkDissolve: AnyTransition { .asymmetric(insertion: .identity, removal: AnyTransition(InkDissolve())) }
}

extension View {
    /// Inks this screen in when it first appears (pushes, tab switches): see [InkArrive].
    func inkArrive() -> some View { modifier(InkArrive()) }

    /// Prints this title's primary plate into register when the screen inks in ([inkArrive]).
    func titleGhost(_ color: Color) -> some View { modifier(TitleGhost(color: color)) }

    /// A living paper ground behind this view (motion spec v2 §2) while [when].
    func inkGround(_ when: Bool = true, plate: Bool = true) -> some View {
        background { if when { InkGround(plate: plate).ignoresSafeArea() } }
    }
}

/**
 A selection marker's one-frame registration ghost: when the selection moves, the old marker
 shows in primary, `registerOffset` pt out of register, for a single frame.
 */
@MainActor
@Observable
final class InkSelectionGhost {
    private(set) var key: AnyHashable?

    func moved(from old: AnyHashable?, reduceMotion: Bool) {
        guard !reduceMotion, let old else { return }
        key = old
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(Int(Tokens.Motion.frameIntervalMillis)))
            if self.key == old { self.key = nil }
        }
    }
}

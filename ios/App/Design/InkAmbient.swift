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
 The indeterminate indicator (motion spec v3 §1): the same wave of swelling dots as a loading
 bar. Inline and button: a row of 5 dots; block: a 3 × 7 grid where the wave travels
 diagonally, with an optional caption. One cycle per `loaderCycleMillis`, on the shared work
 clock; a still frame with Reduce Motion.
 */
struct InkLoader: View {
    enum Size {
        case inline, button, block

        /// Dot diameter, slot pitch, columns and rows.
        var diameter: CGFloat {
            switch self {
            case .inline: 3
            case .button: 4.5
            case .block: 6
            }
        }

        var pitch: CGFloat { diameter * 1.45 }
        var cols: Int { self == .block ? 7 : 5 }
        var rows: Int { self == .block ? 3 : 1 }
        /// The wave's length in dots (the grid's wave runs along its diagonal).
        var wavelength: Double { self == .block ? 7 : 5 }

        var width: CGFloat { CGFloat(cols - 1) * pitch + diameter }
        /// Inline and button keep the height of the text they stand in for.
        var height: CGFloat {
            switch self {
            case .inline: 12
            case .button: 16
            case .block: CGFloat(rows - 1) * pitch + diameter
            }
        }
    }

    var size = Size.inline
    var caption: String? = nil
    var color: Color? = nil
    @Environment(\.scheme) private var c
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.inkStill) private var still
    @Environment(\.displayScale) private var displayScale

    init(_ size: Size = .inline, caption: String? = nil, color: Color? = nil) {
        self.size = size
        self.caption = caption
        self.color = color
    }

    var body: some View {
        let reduced = reduceMotion || still
        let ink = color ?? c.primary
        let size = size
        VStack(spacing: 8) {
            InkTimeline(active: !reduced) { date in
                Canvas { context, canvas in
                    InkProbe.hit("ink.draw")
                    let t = reduced ? InkMath.loaderCycleSeconds * 0.3 : date.timeIntervalSinceReferenceDate
                    context.fill(Self.dots(size, in: canvas, seconds: t, scale: Double(context.environment.displayScale)), with: .color(ink))
                }
            }
            .frame(width: Self.frame(size, scale: displayScale).width, height: Self.frame(size, scale: displayScale).height)
            if size == .block, let caption { MicroLabel(caption).fixedSize() }
        }
        .accessibilityElement()
        .accessibilityLabel(caption ?? "Working")
    }

    /// The loader's dots, centred in [canvas], at [seconds].
    /// The loader's frame at [scale]: exactly its whole-pixel dots (inline and button keep the
    /// height of the text they stand in for).
    static func frame(_ size: Size, scale: CGFloat) -> CGSize {
        let s = Double(max(scale, 1))
        let d = InkGrid.pixels(Double(size.diameter), scale: s)
        let pitch = max(d + 1, InkGrid.pixels(Double(size.pitch), scale: s))
        let width = CGFloat(Double((size.cols - 1) * pitch + d) / s)
        let height = size == .block ? CGFloat(Double((size.rows - 1) * pitch + d) / s) : size.height
        return CGSize(width: width, height: height)
    }

    /// Whole-pixel geometry at [scale]: diameter, pitch and the centred, snapped axes.
    static func layout(_ size: Size, in canvas: CGSize, scale: Double) -> (diameterPx: Int, cols: InkGrid.Axis, rows: InkGrid.Axis) {
        let d = InkGrid.pixels(Double(size.diameter), scale: scale)
        let pitch = max(d + 1, InkGrid.pixels(Double(size.pitch), scale: scale))
        let odd = d % 2 == 1
        return (
            d,
            InkGrid.centred(count: size.cols, pitchPx: pitch, lengthPx: Double(canvas.width) * scale, oddDiameter: odd),
            InkGrid.centred(count: size.rows, pitchPx: pitch, lengthPx: Double(canvas.height) * scale, oddDiameter: odd),
        )
    }

    static func dots(_ size: Size, in canvas: CGSize, seconds: Double, scale: Double = 3) -> Path {
        let (d, cols, rows) = layout(size, in: canvas, scale: scale)
        let full = Double(d) / 2 / scale
        var path = Path()
        for r in 0 ..< rows.count {
            for col in 0 ..< cols.count {
                let position = Double(col) + Double(r) * 0.75
                let radius = full * InkMath.loaderScale(position: position, wavelength: size.wavelength, seconds: seconds)
                let cx = cols.center(col, scale: scale), cy = rows.center(r, scale: scale)
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
final class InkIdleClock {
    static let shared = InkIdleClock()
    private(set) var lastTouch = Date()

    func touch(at now: Date = Date()) {
        // At most one update a second: grounds only care about the minute.
        guard now.timeIntervalSince(lastTouch) > 1 else { return }
        lastTouch = now
        InkClock.ambient.wake()
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
struct InkTouchWatcher: UIViewRepresentable {
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

/**
 Living paper (motion specs v2 §2, v3 §2): behind large empty areas, the living-card dot field
 at `ambientOpacity` in a low-contrast ink, its radii following a very slow travelling wave (one
 cycle per `ambientCycleSeconds`). ≤ `cardMaxDots` dots on the shared ambient clock (≤10 fps,
 asleep after `ambientIdleStopSeconds` without a touch, off while a heavy job runs, in Low Power
 Mode and with Reduce Motion). [hero] draws the halftone field pattern at full strength in
 [dot] (the welcome hero). [fade] thins the dots where text sits. Unlocking prints the paper away
 (the dots swell into ink, a primary plate settles into register, all lifts off).
 */
struct InkGround: View {
    var dot: Color? = nil
    /// The register plate while printing away.
    var plate = true
    var hero = false
    var cell: CGFloat = 12
    var phase = 0.0
    var fade: UnitPoint? = nil
    @Environment(\.scheme) private var c
    @Environment(\.inkHeavyWork) private var heavy
    @Environment(\.inkPrintAway) private var printing
    @State private var grid = InkDotGrid()

    /// The hero's dot budget (it is the only thing on its screen); grounds use `cardMaxDots`.
    static let heroMaxDots = 1500

    var body: some View {
        let ink = dot ?? (hero ? c.primary : c.outline)
        let plateInk = plate ? c.primary : nil
        let opacity = hero ? 1 : Double(Tokens.Motion.ambientOpacity)
        let printing = printing, grid = grid, cell = cell, phase = phase, hero = hero, fade = fade
        InkTimeline(active: !heavy, tier: .ambient) { date in
            Canvas { context, size in
                InkProbe.hit("ink.draw")
                grid.layout(size, base: Double(cell), maxDots: hero ? Self.heroMaxDots : Int(Tokens.Motion.cardMaxDots), fade: fade,
                            scale: Double(context.environment.displayScale))
                // The paper's own clock: no jump after a pause or a resize.
                let period = Double(Tokens.Motion.ambientCycleSeconds)
                let seconds = grid.clock.advance(to: date, period: period) * period
                Self.draw(&context, grid: grid, seconds: seconds, phase: phase, hero: hero, printing: printing,
                          ink: ink, plate: plateInk, opacity: opacity)
            }
        }
        .background(InkTouchWatcher())
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    /// One frame of paper at [seconds]. [printing] (0…1): the dots swell into solid ink, the
    /// paper darkens for a moment, the [plate] settles into register, then it all lifts off.
    static func draw(_ context: inout GraphicsContext, grid: InkDotGrid, seconds: Double, phase: Double, hero: Bool, printing: Double,
                     ink: Color, plate: Color?, opacity: Double) {
        let fill = printing > 0 ? InkMath.printScale(printing) : 1
        let alpha = InkMath.printAwayAlpha(printing, rest: opacity)
        let field = hero ? heroField(grid, seconds: seconds, phase: phase, fill: fill)
            : InkField.path(grid, seconds: seconds, period: Double(Tokens.Motion.ambientCycleSeconds), seed: phase * .pi, scale: 0.4, fill: fill)
        if let plate, printing > 0 {
            let settle = 1 - InkMath.easeOutBack(printing * Double(Tokens.Motion.printMillis / Tokens.Motion.registerMillis))
            let shift = CGFloat(Double(Tokens.Motion.registerOffset) * settle)
            var shifted = context
            shifted.translateBy(x: shift, y: shift)
            shifted.fill(field, with: .color(plate.opacity(alpha * 0.6)))
        }
        context.fill(field, with: .color(ink.opacity(alpha)))
    }

    /// The hero's halftone field pattern drifting one cycle per `ambientCycleSeconds`.
    private static func heroField(_ grid: InkDotGrid, seconds: Double, phase: Double, fill: Double) -> Path {
        let w = max(Double(grid.size.width), 1), h = max(Double(grid.size.height), 1)
        let drift = phase + InkMath.cycle(seconds / Double(Tokens.Motion.ambientCycleSeconds)) * 2
        let scale = grid.pitch * Double(Tokens.Halftone.fieldRadiusFactor) * fill
        var path = Path()
        for i in 0 ..< grid.count {
            let r = InkMath.fieldValue(x: grid.xs[i] / w, y: grid.ys[i] / h, phase: drift) * scale * grid.weights[i]
            guard r > Double(Tokens.Halftone.fieldMinRadius) else { continue }
            path.addEllipse(in: CGRect(x: grid.xs[i] - r, y: grid.ys[i] - r, width: r * 2, height: r * 2))
        }
        return path
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
/// (arriving) title, settling into register over `transitionMillis`, then gone. The silhouette (a
/// mask, rendered offscreen) only exists while it prints.
private struct TitleGhost: ViewModifier {
    let color: Color
    @State private var printing = false
    @State private var printed = false
    @Environment(\.inkArrives) private var arrives
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let offset = Tokens.Motion.registerOffset, duration = InkMath.transitionSeconds
        let color = color
        content
            .background {
                if printing {
                    color.mask { content }
                        .keyframeAnimator(initialValue: GhostKeyframe(), repeating: false) { view, frame in
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
            }
            .onAppear {
                guard arrives, !printed, !reduceMotion else { return }
                printed = true
                printing = true
            }
            .task(id: printing) {
                guard printing else { return }
                try? await Task.sleep(for: .seconds(duration * 1.3))
                printing = false
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
        let _ = InkProbe.hit("dissolve.body")
        let amount = amount, dotted = amount > 0 && !reduceMotion
        content
            .opacity(reduceMotion ? 1 - amount : 1)
            .mask {
                if dotted {
                    Canvas { context, size in
                        InkProbe.hit("ink.draw")
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
        // Rows at rest carry no mask (a mask renders offscreen); it only exists while leaving.
        if phase.isIdentity {
            content
        } else {
            content.modifier(InkDissolveMask(amount: phase == .didDisappear ? 1 : 0))
        }
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

    /// A living paper ground behind this view (motion specs v2 §2, v3 §2) while [when]; [fade]
    /// thins the dots where text sits.
    func inkGround(_ when: Bool = true, plate: Bool = true, fade: UnitPoint? = nil) -> some View {
        background { if when { InkGround(plate: plate, fade: fade).ignoresSafeArea() } }
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

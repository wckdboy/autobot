import SwiftUI
import UIKit

/// A luma grid to develop into; [id] tells images apart (a new preview, a final result).
struct PlateImage: Equatable {
    let id: String
    let grid: LumaGrid
    /// The full-colour image, for the print (final results only).
    var image: UIImage? = nil

    static func == (a: PlateImage, b: PlateImage) -> Bool { a.id == b.id }
}

/// What a proof plate shows.
enum PlateInk: Equatable {
    /// Waiting: a still, light field.
    case idle
    /// Sampling without a preview: the field flows; [progress] (0…1) raises its fill.
    case flow(progress: Double, loading: Bool)
    /// A latent preview: develops from the field, later previews blend from the previous one.
    case preview(PlateImage)
    /// The result: printed if this plate was working on it, else shown as is.
    case final(PlateImage)
    /// Keep whatever is on the plate (the batch moved on, the result is being loaded).
    case hold

    fileprivate var still: String {
        switch self {
        case .idle, .hold: "idle"
        case .flow: "flow"
        case .preview(let p): "p\(p.id)"
        case .final(let f): "f\(f.id)"
        }
    }
}

/**
 The image-generation proof (motion spec 1–3): a halftone plate that flows while sampling,
 develops into each latent preview (staggered along the flow diagonal), then prints the result:
 dots swell into solid ink while the image fades in underneath and a register plate settles into
 place. Ends as the plain image. One Canvas pass per frame, one Path per colour, ≤ 2500 dots;
 luma is sampled once per image and size. With Reduce Motion it crossfades between stills.
 */
struct InkPlate: View {
    let ink: PlateInk
    /// Working ink (flow, previews, print).
    var dot: Color
    /// Waiting ink (idle field).
    var idleDot: Color
    /// The register plate.
    var plate: Color
    var cell: CGFloat = Tokens.Halftone.imageCell
    var phase: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.inkStill) private var still
    @State private var engine = PlateEngine()
    /// Bumped when a one-shot ends, so the frame clock can stop.
    @State private var revision = 0

    var body: some View {
        Group {
            if reduceMotion || still {
                StillPlate(ink: ink, dot: dot, idleDot: idleDot, cell: cell, phase: phase)
                    .id(ink.still)
                    .transition(.opacity)
            } else {
                live
            }
        }
        .animation(.inkReduced, value: reduceMotion || still ? ink.still : "")
        .clipped()
        .accessibilityHidden(true)
    }

    private var live: some View {
        // Ink is taken while the body is evaluated, so the first frame is already right.
        engine.sync(ink, at: Date())
        let busy = engine.busyUntil
        _ = revision
        return InkTimeline(active: engine.loops(ink) || busy != nil) { date in
            ZStack {
                if let image = engine.image {
                    let alpha = engine.imageAlpha(at: date)
                    if alpha > 0 { Image(uiImage: image).resizable().scaledToFill().opacity(alpha) }
                }
                if !engine.printed {
                    Canvas { context, size in
                        InkProbe.hit("ink.draw")
                        engine.draw(&context, size: size, at: date, cell: cell, phase: phase, dot: dot, idleDot: idleDot, plate: plate)
                    }
                }
            }
        }
        .task(id: busy) {
            guard let busy else { return }
            try? await Task.sleep(for: .seconds(max(busy.timeIntervalSinceNow, 0) + 0.02))
            guard !Task.isCancelled else { return }
            engine.settle(at: Date())
            revision &+= 1
        }
    }
}

/// Reduce Motion / snapshots: the same plate as stills.
private struct StillPlate: View {
    let ink: PlateInk
    let dot: Color
    let idleDot: Color
    let cell: CGFloat
    let phase: Double

    var body: some View {
        switch ink {
        case .idle, .hold:
            field(idleDot, fill: InkMath.idleFill)
        case .flow(let progress, let loading):
            field(dot, fill: InkMath.flowFillTarget(progress: progress, loading: loading))
        case .preview(let p):
            HalftoneImage(grid: p.grid, dot: dot, cell: cell)
        case .final(let f):
            if let image = f.image { Image(uiImage: image).resizable().scaledToFill() } else { HalftoneImage(grid: f.grid, dot: dot, cell: cell) }
        }
    }

    private func field(_ color: Color, fill: Double) -> some View {
        Canvas { context, size in
            InkProbe.hit("ink.draw")
            // The live plate's whole-pixel pitch ([PlateEngine.layout]), so a still lands on the same dots.
            let scale = Double(context.environment.displayScale)
            let pitch = Double(InkGrid.fieldPitchPx(width: size.width, height: size.height, base: Double(cell), maxDots: InkMath.maxDots, scale: scale)) / scale
            context.fill(HalftonePaths.field(size: size, cell: pitch, phase: phase, fill: fill, displayScale: scale), with: .color(color))
        }
    }
}

/**
 The plate's state between frames: stage timings, the flow integrator and the per-dot darkness
 buffers (allocated when an image or the size changes, never per frame).
 */
@MainActor
final class PlateEngine {
    private enum Stage: Equatable {
        case idle
        case flow
        /// Field → [target], staggered along the diagonal.
        case develop(start: Date)
        /// [from] → [target], no stagger.
        case blend(start: Date)
        /// Showing [target] (a preview).
        case shown
        /// [from] → [target] → merged ink, image fading in, register plate settling.
        case print(start: Date)
        /// The plain image.
        case printed
    }

    private var stage = Stage.idle
    private var flow = FlowState(fill: InkMath.idleFill)
    private var speed = 0.0
    private var fillTarget = InkMath.idleFill
    /// Working ink share (0 idle colour … 1 working colour), eased.
    private var mix = 0.0
    private var mixTarget = 0.0
    private var lastFrame: Date?
    /// Develop straight into the print (a result without previews).
    private var printAt: Date?
    private var received = false
    private var lastInk: PlateInk?
    /// The idle fade's end.
    private var fadeUntil: Date?
    /// The plate's phase offset (from the view), for snapshots.
    private var basePhase = 0.0

    private var targetImage: PlateImage?
    private(set) var image: UIImage?

    // Geometry and buffers.
    private var cols = 0, rows = 0
    private var pitch: CGFloat = 0
    private var size = CGSize.zero
    private var scale = 0.0
    /// Dot centres (points) on the whole-pixel grid ([InkGrid.field]), and as fractions of the plate.
    private var xs: [CGFloat] = [], ys: [CGFloat] = []
    private var us: [Double] = [], vs: [Double] = []
    private var target: [Float] = []
    private var waveArg: [Double] = []
    private var fieldStatic: [Double] = []
    private var from: [Float] = []

    private static var developSeconds: Double { Double(Tokens.Motion.developMillis) / 1000 }
    private static var printSeconds: Double { Double(Tokens.Motion.printMillis) / 1000 }

    var printed: Bool { stage == .printed }

    /// Whether this ink keeps the frame clock running (work in progress).
    func loops(_ ink: PlateInk) -> Bool {
        switch ink {
        case .flow, .preview: true
        case .hold: stage == .flow || stage == .shown
        case .idle, .final: false
        }
    }

    /// Takes [ink] if it changed.
    func sync(_ ink: PlateInk, at now: Date) {
        guard ink != lastInk else { return }
        lastInk = ink
        _ = receive(ink, at: now)
    }

    /// Takes new ink; returns until when a one-shot animation runs (nil: none).
    func receive(_ ink: PlateInk, at now: Date) -> Date? {
        defer { received = true }
        let first = !received
        switch ink {
        case .hold:
            return busyUntil
        case .idle:
            stage = .idle
            targetImage = nil
            image = nil
            printAt = nil
            fillTarget = InkMath.idleFill
            speed = 0
            if first { flow.set(fill: fillTarget) }
            mixTarget = 0
            if first { mix = 0 } else { fadeUntil = now.addingTimeInterval(0.5) }
            return busyUntil
        case .flow(let progress, let loading):
            if stage != .flow { stage = .flow; image = nil; printAt = nil }
            fillTarget = InkMath.flowFillTarget(progress: progress, loading: loading)
            speed = loading ? InkMath.loadingSpeed : 1
            mixTarget = 1
            if first { mix = 1; flow.set(fill: fillTarget) }
            return nil
        case .preview(let p):
            guard p != targetImage else { return busyUntil }
            mixTarget = 1
            speed = InkMath.shimmerSpeed
            if first { mix = 1 }
            let developed = targetImage != nil && stage != .idle && stage != .flow
            if developed { snapshot(at: now) }
            targetImage = p
            resample()
            stage = first ? .shown : developed ? .blend(start: now) : .develop(start: now)
            return busyUntil
        case .final(let f):
            guard f != targetImage || image == nil else { return busyUntil }
            let developed = targetImage != nil && stage != .idle && stage != .flow
            let working = stage == .flow || developed
            image = f.image
            mixTarget = 1
            speed = 0
            if first || !working || f.image == nil {
                targetImage = f
                resample()
                stage = f.image == nil ? .shown : .printed
                return nil
            }
            if developed {
                snapshot(at: now)
                targetImage = f
                resample()
                stage = .print(start: now)
            } else {
                // No preview: develop the result out of the field, then print it.
                targetImage = f
                resample()
                stage = .develop(start: now)
                printAt = now.addingTimeInterval(Self.developSeconds)
            }
            return busyUntil
        }
    }

    /// The one-shot animation's end, if one runs.
    var busyUntil: Date? {
        let end: Date? = switch stage {
        case .develop(let start): printAt.map { $0.addingTimeInterval(Self.printSeconds) } ?? start.addingTimeInterval(Self.developSeconds)
        case .blend(let start): start.addingTimeInterval(Self.developSeconds)
        case .print(let start): start.addingTimeInterval(Self.printSeconds)
        default: nil
        }
        guard let fadeUntil else { return end }
        return max(end ?? fadeUntil, fadeUntil)
    }

    /// Ends whatever one-shot ran out (the frame clock may have stopped before its last frame).
    func settle(at now: Date) {
        advanceStage(now)
        if let fade = fadeUntil, now >= fade {
            fadeUntil = nil
            mix = mixTarget
        }
    }

    private func advanceStage(_ now: Date) {
        if let at = printAt, now.timeIntervalSince(at) >= -0.001 {
            printAt = nil
            from = target
            stage = .print(start: at)
        }
        switch stage {
        // A millisecond of slack: the settle task wakes at the end date, not after it.
        case .develop(let start) where now.timeIntervalSince(start) >= Self.developSeconds - 0.001 && printAt == nil: stage = .shown
        case .blend(let start) where now.timeIntervalSince(start) >= Self.developSeconds - 0.001: stage = .shown
        case .print(let start) where now.timeIntervalSince(start) >= Self.printSeconds - 0.001: stage = .printed
        default: break
        }
    }

    func imageAlpha(at now: Date) -> Double {
        switch stage {
        case .printed: 1
        case .print(let start): InkMath.printImageAlpha(now.timeIntervalSince(start) / Self.printSeconds)
        default: 0
        }
    }

    // MARK: Drawing

    func draw(_ context: inout GraphicsContext, size: CGSize, at now: Date, cell: CGFloat, phase: Double, dot: Color, idleDot: Color, plate: Color) {
        basePhase = phase
        layout(size, base: cell, scale: Double(context.environment.displayScale))
        tick(now)
        advanceStage(now)
        guard cols > 0, stage != .printed else { return }

        var printT = 0.0, elapsed = 0.0
        if case .print(let start) = stage {
            elapsed = max(now.timeIntervalSince(start), 0)
            printT = InkMath.clamp01(elapsed / Self.printSeconds)
        }
        let isPrint: Bool = if case .print = stage { true } else { false }
        let scale = isPrint ? InkMath.printScale(printT) : 1
        let maxRadius = Double(pitch * Tokens.Halftone.imageRadiusFactor)

        var dots = Path()
        var register = Path()
        for r in 0 ..< rows {
            let y = vs[r], cy = ys[r]
            for c in 0 ..< cols {
                let i = r * cols + c
                let x = us[c], cx = xs[c]
                let v = value(i, x: x, y: y, at: now, phase: phase)
                let radius = CGFloat(v * maxRadius * scale)
                if radius > Tokens.Halftone.imageMinRadius {
                    dots.addEllipse(in: CGRect(x: cx - radius, y: cy - radius, width: radius * 2, height: radius * 2))
                }
                if isPrint {
                    let pr = CGFloat(Double(target[i]) * maxRadius)
                    if pr > Tokens.Halftone.imageMinRadius {
                        register.addEllipse(in: CGRect(x: cx - pr, y: cy - pr, width: pr * 2, height: pr * 2))
                    }
                }
            }
        }
        if isPrint {
            let shift = CGFloat(InkMath.registerShift(elapsed: elapsed))
            let alpha = 0.5 * InkMath.plateAlpha(elapsed: elapsed)
            if alpha > 0 {
                var shifted = context
                shifted.translateBy(x: shift, y: shift)
                shifted.fill(register, with: .color(plate.opacity(alpha)))
            }
            context.fill(dots, with: .color(dot.opacity(1 - InkMath.printImageAlpha(printT))))
        } else {
            if mix < 0.999 { context.fill(dots, with: .color(idleDot.opacity(1 - mix))) }
            if mix > 0.001 { context.fill(dots, with: .color(dot.opacity(mix))) }
        }
    }

    /// The darkness (0…1) of dot [i] now.
    private func value(_ i: Int, x: Double, y: Double, at now: Date, phase: Double) -> Double {
        let field = { self.fieldAt(i, phase: phase + self.flow.phase) * self.flow.fill }
        switch stage {
        case .idle, .flow:
            return field()
        case .develop(let start):
            let t = now.timeIntervalSince(start) / Self.developSeconds
            return InkMath.lerp(field(), Double(target[i]) * shimmer(x, y), InkMath.developLocal(t: t, x: x, y: y))
        case .blend(let start):
            let t = InkMath.easeOutCubic(now.timeIntervalSince(start) / Self.developSeconds)
            return InkMath.lerp(Double(from[i]), Double(target[i]), t) * shimmer(x, y)
        case .shown:
            return Double(target[i]) * shimmer(x, y)
        case .print(let start):
            // The last preview settles onto the result in the first half, then merges.
            let t = InkMath.easeOutCubic(now.timeIntervalSince(start) / (Self.printSeconds / 2))
            return InkMath.lerp(Double(from[i]), Double(target[i]), t)
        case .printed:
            return Double(target[i])
        }
    }

    /// A ±4% ripple while previews update (the flow keeps moving, very slightly).
    private func shimmer(_ x: Double, _ y: Double) -> Double {
        speed > 0 ? 0.96 + 0.08 * InkMath.fieldValue(x: x, y: y, phase: flow.phase) : 1
    }

    /// [InkMath.fieldValue] for dot [i] from its cached wave argument and static part (no sqrt
    /// per dot per frame).
    private func fieldAt(_ i: Int, phase: Double) -> Double {
        typealias H = Tokens.Halftone
        let wave = 0.5 + 0.5 * sin(waveArg[i] - phase * .pi)
        return InkMath.clamp01(Double(H.fieldWaveMix) * wave + fieldStatic[i])
    }

    private func tick(_ now: Date) {
        let dt = lastFrame.map { min(max(now.timeIntervalSince($0), 0), 0.1) } ?? 0
        lastFrame = now
        flow.advance(to: now, speed: speed, fill: fillTarget)
        mix = InkMath.approach(mix, mixTarget, dt: dt, timeConstant: 0.15)
    }

    /// Copies the dots as they are now into [from] (the start of a blend or print).
    private func snapshot(at now: Date) {
        guard cols > 0, from.count == cols * rows else { return }
        for r in 0 ..< rows {
            for c in 0 ..< cols {
                from[r * cols + c] = Float(value(r * cols + c, x: us[c], y: vs[r], at: now, phase: basePhase))
            }
        }
    }

    /**
     The plate's grid for [newSize] at [newScale]: the same rules as every other dot field
     ([InkGrid.field]: a whole-pixel pitch, centred, half a pitch from the edges), so its dots sit
     where the still plate's and the halftone image's do and antialias alike. Rebuilt only when the
     size or scale changes.
     */
    private func layout(_ newSize: CGSize, base: CGFloat, scale newScale: Double) {
        guard newSize != size || newScale != scale else { return }
        size = newSize
        scale = newScale
        let w = Double(newSize.width), h = Double(newSize.height)
        let (colAxis, rowAxis) = InkGrid.field(width: w, height: h, base: Double(base), maxDots: InkMath.maxDots, scale: newScale)
        pitch = CGFloat(Double(colAxis.pitchPx) / newScale)
        cols = colAxis.count
        rows = rowAxis.count
        xs = (0 ..< cols).map { CGFloat(colAxis.center($0, scale: newScale)) }
        ys = (0 ..< rows).map { CGFloat(rowAxis.center($0, scale: newScale)) }
        us = xs.map { Double($0) / max(w, 1) }
        vs = ys.map { Double($0) / max(h, 1) }
        target = [Float](repeating: 0, count: cols * rows)
        // The field's per-dot constants: the wave's argument and the blob/falloff part.
        typealias H = Tokens.Halftone
        waveArg = []
        fieldStatic = []
        waveArg.reserveCapacity(cols * rows)
        fieldStatic.reserveCapacity(cols * rows)
        for r in 0 ..< rows {
            let y = vs[r]
            for c in 0 ..< cols {
                let x = us[c]
                waveArg.append((x * Double(H.fieldWaveX) + y * Double(H.fieldWaveY)) * .pi)
                let dx = x - Double(H.fieldBlobX), dy = y - Double(H.fieldBlobY)
                let blob = 1 - min(1, (dx * dx * Double(H.fieldBlobWeightX) + dy * dy * Double(H.fieldBlobWeightY)).squareRoot())
                fieldStatic.append(Double(H.fieldBlobMix) * blob - Double(H.fieldFalloffY) * y)
            }
        }
        resample()
        from = target
    }

    /// Samples the target image into [target] (aspect fill, like the image under it).
    private func resample() {
        guard cols > 0, rows > 0, target.count == cols * rows else { return }
        if from.count != target.count { from = [Float](repeating: 0, count: target.count) }
        guard let grid = targetImage?.grid else {
            for i in target.indices { target[i] = 0 }
            return
        }
        let imageAspect = Double(grid.width) / Double(max(grid.height, 1))
        let viewAspect = Double(size.width) / Double(max(size.height, 1))
        let sx = imageAspect > viewAspect ? viewAspect / imageAspect : 1
        let sy = imageAspect > viewAspect ? 1 : imageAspect / viewAspect
        for r in 0 ..< rows {
            let v = 0.5 + (vs[r] - 0.5) * sy
            for c in 0 ..< cols {
                let u = 0.5 + (us[c] - 0.5) * sx
                target[r * cols + c] = 1 - grid.sample(Float(u), Float(v))
            }
        }
    }
}

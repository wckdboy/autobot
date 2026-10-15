import SwiftUI
import Testing
import UIKit
@testable import Autobot

/// Ink motion v2 (motion spec v2): loader, bars, ambient grounds, transitions and dissolve math,
/// plus rendered frames for a visual check.
struct MotionV2Tests {
    @Test func loaderDropTravelsTheDiagonal() {
        // The drop enters before the first dot and leaves after the last.
        #expect(InkMath.loaderDropCenter(0) < 0)
        #expect(InkMath.loaderDropCenter(0.999) > 1)
        #expect(abs(InkMath.loaderDropCenter(0.5) - 0.5) < 1e-9)
        // Mid-cycle the centre dot is swollen, the corners rest.
        let centre = InkMath.loaderRadius(x: 0.5, y: 0.5, t: 0.5)
        let corner = InkMath.loaderRadius(x: 0.1, y: 0.1, t: 0.5)
        #expect(abs(centre - InkMath.loaderPeak) < 1e-9)
        #expect(corner < centre)
        #expect(corner >= InkMath.loaderRestFill)
        // Early in the cycle the top-left corner leads.
        #expect(InkMath.loaderSwell(x: 0.1, y: 0.1, t: 0.3) > InkMath.loaderSwell(x: 0.9, y: 0.9, t: 0.3))
        // It loops: one cycle per loaderCycleMillis.
        let cycle = Double(Tokens.Motion.loaderCycleMillis) / 1000
        #expect(abs(InkMath.loaderPhase(cycle * 3.25) - 0.25) < 1e-9)
        #expect(InkMath.cycle(-0.25) == 0.75)
    }

    @Test func barGradientIsDensestAtTheHead() {
        #expect(InkMath.barDensity(0) == InkMath.barTailDensity)
        #expect(InkMath.barDensity(1) == 1)
        #expect(InkMath.barDensity(2) == 1)
        var last = -1.0
        for i in 0 ... 20 {
            let d = InkMath.barDensity(Double(i) / 20)
            #expect(d >= last)
            last = d
        }
    }

    @Test func indeterminateSweep() {
        // Starts off the track, ends past it with its trail.
        #expect(InkMath.sweepHead(0) == 0)
        #expect(abs(InkMath.sweepHead(0.99999) - (1 + InkMath.sweepLength + InkMath.sweepTrail)) < 1e-3)
        let t = 0.5, head = InkMath.sweepHead(t)
        #expect(InkMath.sweepDensity(head + 0.01, t: t) == 0)
        #expect(InkMath.sweepDensity(head - InkMath.sweepLength / 2, t: t) == 1)
        // The trail fades behind the segment.
        let tail = head - InkMath.sweepLength
        #expect(InkMath.sweepDensity(tail - 0.05, t: t) > InkMath.sweepDensity(tail - 0.2, t: t))
        #expect(InkMath.sweepDensity(tail - InkMath.sweepTrail - 0.01, t: t) == 0)
        // Ease-in-out: slow at the ends.
        #expect(InkMath.easeInOutCubic(0.1) < 0.1)
        #expect(InkMath.easeInOutCubic(0.9) > 0.9)
        #expect(InkMath.easeInOutCubic(0.5) == 0.5)
    }

    @Test func ambientBudget() {
        #expect(abs(InkMath.ambientFrameInterval - 0.1) < 1e-9)
        // One cycle (2 phase units) per ambientCycleSeconds.
        #expect(abs(InkMath.ambientPhaseStep(dt: Double(Tokens.Motion.ambientCycleSeconds)) - 2) < 1e-9)
        // ±1pt over ambientBreathSeconds.
        let breath = Double(Tokens.Motion.ambientBreathSeconds)
        #expect(abs(InkMath.ambientBreath(breath / 4) - 1) < 1e-9)
        #expect(abs(InkMath.ambientBreath(breath * 3 / 4) + 1) < 1e-9)
        #expect(abs(InkMath.ambientBreath(breath)) < 1e-9)
        let idle = Double(Tokens.Motion.ambientIdleStopSeconds)
        #expect(InkMath.ambientShouldRun(visible: true, foreground: true, reduceMotion: false, lowPower: false, heavyWork: false, idleSeconds: 0))
        #expect(!InkMath.ambientShouldRun(visible: true, foreground: true, reduceMotion: false, lowPower: false, heavyWork: false, idleSeconds: idle))
        #expect(!InkMath.ambientShouldRun(visible: true, foreground: true, reduceMotion: false, lowPower: false, heavyWork: true, idleSeconds: 0))
        #expect(!InkMath.ambientShouldRun(visible: true, foreground: true, reduceMotion: false, lowPower: true, heavyWork: false, idleSeconds: 0))
        #expect(!InkMath.ambientShouldRun(visible: true, foreground: true, reduceMotion: true, lowPower: false, heavyWork: false, idleSeconds: 0))
        #expect(!InkMath.ambientShouldRun(visible: false, foreground: true, reduceMotion: false, lowPower: false, heavyWork: false, idleSeconds: 0))
        #expect(!InkMath.ambientShouldRun(visible: true, foreground: false, reduceMotion: false, lowPower: false, heavyWork: false, idleSeconds: 0))
    }

    @MainActor @Test func ambientDriftIsSmoothAfterAPause() {
        let state = AmbientState()
        let t0 = Date()
        state.advance(to: t0)
        state.advance(to: t0.addingTimeInterval(0.1))
        let step = state.phase
        #expect(abs(step - InkMath.ambientPhaseStep(dt: 0.1)) < 1e-9)
        // A minute asleep counts as one short frame: no jump on waking.
        state.advance(to: t0.addingTimeInterval(60))
        #expect(state.phase - step <= InkMath.ambientPhaseStep(dt: 0.2) + 1e-9)
    }

    @Test func staggerAndDissolve() {
        let step = Double(Tokens.Motion.staggerMillis) / 1000
        #expect(InkMath.staggerDelay(0) == 0)
        #expect(abs(InkMath.staggerDelay(2) - 2 * step) < 1e-9)
        // At most six sections stagger.
        #expect(InkMath.staggerDelay(40) == InkMath.staggerDelay(InkMath.maxStaggerSections - 1))
        #expect(InkMath.staggerDelay(-3) == 0)
        // Dissolve: whole rows are covered, finished rows are gone, and it runs along the diagonal.
        #expect(InkMath.dissolveRadius(t: 0, x: 0.5, y: 0.5) == InkMath.dissolveCover)
        #expect(InkMath.dissolveCover >= 0.5.squareRoot() * 1.0)
        #expect(InkMath.dissolveRadius(t: 1, x: 1, y: 1) == 0)
        #expect(InkMath.dissolveRadius(t: 0.4, x: 0, y: 0) < InkMath.dissolveRadius(t: 0.4, x: 1, y: 1))
        #expect(abs(InkMath.transitionSeconds - 0.24) < 1e-9)
    }

    @Test func printAwayLiftsOff() {
        let rest = Double(Tokens.Motion.ambientOpacity)
        #expect(InkMath.printAwayAlpha(0, rest: rest) == rest)
        #expect(InkMath.printAwayAlpha(0.3, rest: rest) > rest)
        #expect(InkMath.printAwayAlpha(1, rest: rest) == 0)
    }

    @MainActor @Test func idleClockThrottles() {
        let clock = InkIdleClock()
        let start = clock.lastTouch
        clock.touch(at: start.addingTimeInterval(0.5))
        #expect(clock.lastTouch == start)
        clock.touch(at: start.addingTimeInterval(2))
        #expect(clock.lastTouch == start.addingTimeInterval(2))
    }

    @MainActor @Test func loaderAndBarPathsStayInBudget() {
        // 4×4 / 5×5 dots, one path.
        #expect(!InkLoader.dots(side: 12, count: 4, t: 0.5).isEmpty)
        let bar = InkBarDots.fill(size: CGSize(width: 360, height: 3), width: 180)
        #expect(bar.boundingRect.maxX <= 182)
        #expect(InkBarDots.sweep(size: CGSize(width: 360, height: 3), t: 0).isEmpty)
        // Dissolve masks cap their dots.
        let rect = InkDissolve.dots(size: CGSize(width: 393, height: 400), amount: 0.01).boundingRect
        #expect(rect.width > 380)
    }

    // MARK: Frames

    private var dir: String? {
        let env = ProcessInfo.processInfo.environment
        return env["AUTOBOT_MOTION_DIR"] ?? env["AUTOBOT_SNAPSHOT_DIR"]
    }

    /**
     Renders the v2 elements to PNGs for a visual check: loader cycles, bar states and sweep, the
     ambient ground (dark, light, paper), the lock screen printing away and a row dissolving.
     Skipped unless `AUTOBOT_MOTION_DIR` or `AUTOBOT_SNAPSHOT_DIR` is set.
     */
    @MainActor @Test func renderInkV2Frames() throws {
        guard let dir else { return }
        let schemes: [(String, AutobotScheme)] = [("dark", .dark), ("light", .light), ("amoled", AutobotScheme.dark.amoled())]

        for (name, c) in schemes {
            // Loader: 8 frames of a cycle, each size, magnified.
            try write(VStack(alignment: .leading, spacing: 18) {
                ForEach([InkLoader.Size.inline, .button, .block], id: \.side) { size in
                    HStack(spacing: 14) {
                        ForEach(0 ..< 8, id: \.self) { i in
                            Canvas { context, s in
                                context.fill(InkLoader.dots(side: s.width, count: size.dots, t: Double(i) / 8), with: .color(c.primary))
                            }
                            .frame(width: size.side, height: size.side)
                        }
                    }
                }
            }
            .padding(20)
            .background(c.background), scale: 4, "\(dir)/v2-loader-\(name).png")

            // Bars: determinate, paused, error (real views), and the indeterminate sweep frames.
            try write(VStack(alignment: .leading, spacing: 14) {
                ForEach([0.12, 0.5, 0.86], id: \.self) { f in InkProgressBar(fraction: f, height: 3) }
                InkProgressBar(.paused(0.42), height: 3)
                InkProgressBar(.error(0.6), height: 3)
                InkProgressBar(fraction: 0.64, height: 2)
                ForEach(0 ..< 6, id: \.self) { i in
                    Canvas { context, s in context.fill(InkBarDots.sweep(size: s, t: 0.1 + Double(i) * 0.15), with: .color(c.primary)) }
                        .frame(height: 3)
                        .background(c.outlineVariant)
                }
            }
            .padding(20)
            .frame(width: 360)
            .background(c.background)
            .environment(\.scheme, c)
            .environment(\.inkStill, true), scale: 3, "\(dir)/v2-bars-\(name).png")

            // Ambient ground, two drift phases and the breath extremes.
            for (i, (phase, breath)) in [(0.0, -1.0), (0.6, 1.0)].enumerated() {
                try write(ZStack {
                    c.background
                    Canvas { context, s in
                        InkGround.draw(&context, size: s, cell: 12, phase: phase, breath: breath, printing: 0, ink: c.outline, plate: c.primary, opacity: Double(Tokens.Motion.ambientOpacity))
                    }
                    lockContent(c)
                }
                .frame(width: 393, height: 852), scale: 1, "\(dir)/v2-ground-\(name)-\(i).png")
            }

            // Print away (lock screen on unlock).
            for p in [0.0, 0.15, 0.3, 0.5, 0.7, 0.9] {
                try write(ZStack {
                    c.background
                    Canvas { context, s in
                        InkGround.draw(&context, size: s, cell: 12, phase: 0, breath: 0, printing: p, ink: c.outline, plate: c.primary, opacity: Double(Tokens.Motion.ambientOpacity))
                    }
                    lockContent(c).opacity(1 - InkMath.smoothstep(p))
                }
                .frame(width: 393, height: 600), scale: 1, "\(dir)/v2-print-\(name)-\(String(format: "%.2f", p)).png")
            }
        }

        // Paper (Imagine) ground and the welcome hero.
        let paper = AutobotScheme.light
        try write(ZStack {
            paper.surfaceContainerLow
            Canvas { context, s in
                InkGround.draw(&context, size: s, cell: 12, phase: 0.3, breath: 0, printing: 0, ink: paper.outline, plate: paper.primary, opacity: Double(Tokens.Motion.ambientOpacity))
            }
        }
        .frame(width: 393, height: 500), scale: 1, "\(dir)/v2-ground-paper.png")

        // A row dissolving.
        try write(VStack(spacing: 6) {
            ForEach([0.0, 0.25, 0.5, 0.75, 0.95], id: \.self) { a in
                HStack {
                    Text("PLAN A TRIP TO LISBON").textStyle(.titleMedium).foregroundStyle(AutobotScheme.dark.onSurface)
                    Spacer()
                    Text("2 MIN AGO").textStyle(.micro).foregroundStyle(AutobotScheme.dark.onSurfaceVariant)
                }
                .padding(14)
                .background(AutobotScheme.dark.surfaceContainerHigh)
                .mask { Canvas { context, s in context.fill(a == 0 ? Path(CGRect(origin: .zero, size: s)) : InkDissolve.dots(size: s, amount: a), with: .color(.black)) } }
            }
        }
        .padding(16)
        .frame(width: 393)
        .background(AutobotScheme.dark.background), scale: 2, "\(dir)/v2-dissolve.png")
    }

    @MainActor private func lockContent(_ c: AutobotScheme) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "lock.fill").font(.system(size: 40)).foregroundStyle(c.primary)
            Text("Autobot is locked").textStyle(.headlineSmall).foregroundStyle(c.onSurface)
            Text("Your conversations are encrypted on this device").textStyle(.bodyMedium).foregroundStyle(c.onSurfaceVariant)
        }
    }

    @MainActor private func write(_ view: some View, scale: CGFloat, _ path: String) throws {
        let renderer = ImageRenderer(content: view)
        renderer.scale = scale
        let image = try #require(renderer.uiImage)
        try #require(image.pngData()).write(to: URL(fileURLWithPath: path))
    }
}

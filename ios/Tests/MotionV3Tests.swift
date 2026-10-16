import SwiftUI
import Testing
import UIKit
@testable import Autobot

/// Ink motion v3 (motion spec v3): the dot-row bar, the wave loader, living cards, the shared
/// tier clocks and draw-only invalidation, plus rendered frames for a visual check.
@MainActor
struct MotionV3Tests {
    // MARK: Math

    @Test func barLayoutFillsTheWidth() {
        let (count, pitch, first) = InkMath.barLayout(width: 360, diameter: 4)
        #expect(abs(pitch - 4 * Double(Tokens.Motion.barDotPitch)) < 1e-9)
        #expect(count == Int((356 / pitch).rounded(.down)) + 1)
        // The outer dots sit inside the bar, centred.
        let last = first + Double(count - 1) * pitch
        #expect(first >= 2 && last <= 358)
        #expect(abs((first - 2) - (358 - last)) < 1e-9)
        #expect(InkMath.barLayout(width: 2, diameter: 4).count == 0)
    }

    @Test func emptyBarIsEmptyAndFullBarIsFull() {
        let n = 60
        let empty = InkMath.barHead(fraction: 0, count: n)
        for i in 0 ..< n { #expect(InkMath.barFillScale(slot: Double(i) + 0.5, head: empty) == 0) }
        let full = InkMath.barHead(fraction: 1, count: n)
        // Every slot full size at 100%, except the start of the ramp.
        #expect(abs(InkMath.barFillScale(slot: Double(n) - 0.5, head: full) - 1) < 0.02)
        #expect(InkMath.barFillScale(slot: 0.5, head: full) >= Double(Tokens.Motion.barFillStartScale))
        for i in 0 ..< n { #expect(InkMath.barFillScale(slot: Double(i) + 0.5, head: full) > 0.4) }
    }

    @Test func fillGrowsTowardTheHeadAndContinuously() {
        let n = 60, head = InkMath.barHead(fraction: 0.5, count: n)
        // Radii grow from the start toward the head (before the head eases in).
        var last = 0.0
        for i in 0 ..< Int(head) - 2 {
            let s = InkMath.barFillScale(slot: Double(i) + 0.5, head: head)
            #expect(s >= last)
            last = s
        }
        // No popping: a slot's radius changes by a small step for a small progress step.
        for i in [10, 29, 30, 31] {
            var previous = 0.0
            for step in 0 ... 400 {
                let s = InkMath.barFillScale(slot: Double(i) + 0.5, head: InkMath.barHead(fraction: Double(step) / 400, count: n))
                #expect(abs(s - previous) < 0.12)
                previous = s
            }
        }
    }

    @Test func loadingWaveTravels() {
        let cycle = Double(Tokens.Motion.loaderCycleMillis) / 1000
        // One wavelength is `barWaveSlots` slots; one cycle per loaderCycleMillis.
        let slots = Double(Tokens.Motion.barWaveSlots)
        #expect(abs(InkMath.barWave(slot: 3, seconds: 1) - InkMath.barWave(slot: 3 + slots, seconds: 1)) < 1e-9)
        #expect(abs(InkMath.barWave(slot: 3, seconds: 1) - InkMath.barWave(slot: 3, seconds: 1 + cycle)) < 1e-9)
        // The crest moves toward the end of the bar.
        let crest = slots / 4
        #expect(abs(InkMath.barWave(slot: crest, seconds: 0) - 1) < 1e-9)
        #expect(abs(InkMath.barWave(slot: crest + slots / 10, seconds: cycle / 10) - 1) < 1e-9)
        // Loading dots range from pin size to full.
        let scales = (0 ..< 70).map { InkMath.barLoadingScale(slot: Double($0) / 10, seconds: 0) }
        #expect(abs(scales.min()! - Double(Tokens.Motion.barEmptyDotScale)) < 0.01)
        #expect(abs(scales.max()! - 1) < 0.01)
        // The running fill's wave never grows a dot past full size, nor below 90%.
        for i in 0 ..< 20 {
            let r = InkMath.barRunningScale(slot: Double(i), seconds: 0.3)
            #expect(r <= 1 && r >= 1 - Double(Tokens.Motion.barRunningWaveAmplitude) - 1e-9)
        }
    }

    @Test func loaderAndCardWaves() {
        let scales = (0 ..< 50).map { InkMath.loaderScale(position: Double($0) / 10, wavelength: 5, seconds: 0) }
        #expect(scales.max()! > 0.99 && scales.min()! < 0.3)
        let period = Double(Tokens.Motion.cardWavePeriodSeconds)
        let a = InkMath.cardWave(phase: 1, seconds: 2, period: period)
        #expect(abs(a - InkMath.cardWave(phase: 1, seconds: 2 + period, period: period)) < 1e-9)
        #expect(a >= 0.1 && a <= 1)
    }

    @Test func cardGridIsCachedAndCapped() {
        let grid = InkDotGrid()
        grid.layout(CGSize(width: 393, height: 852), base: Double(Tokens.Motion.cardDotPitch), maxDots: Int(Tokens.Motion.cardMaxDots), fade: nil)
        #expect(grid.count <= Int(Tokens.Motion.cardMaxDots))
        #expect(grid.count > 400)
        let first = grid.xs
        grid.layout(CGSize(width: 393, height: 852), base: Double(Tokens.Motion.cardDotPitch), maxDots: Int(Tokens.Motion.cardMaxDots), fade: nil)
        #expect(grid.xs == first)
        // A small card keeps the token pitch.
        grid.layout(CGSize(width: 160, height: 120), base: 11, maxDots: 600, fade: UnitPoint(x: 0.2, y: 0.9))
        #expect(grid.pitch == 11)
        // Dots thin out around the fade point.
        let near = zip(grid.xs, grid.ys).enumerated().min { hypot($0.element.0 - 32, $0.element.1 - 108) < hypot($1.element.0 - 32, $1.element.1 - 108) }!.offset
        #expect(grid.weights[near] < 0.4)
        #expect(grid.weights.max()! == 1)
    }

    // MARK: Clock and invalidation

    /// The screen under test: counts its own body evaluations.
    private struct Busy: View {
        @Environment(\.scheme) private var c

        var body: some View {
            let _ = InkProbe.hit("body.test")
            VStack(spacing: 12) {
                InkProgressBar(fraction: 0.4, running: true)
                InkProgressBar(.indeterminate)
                InkLoader()
                InkLoader(.button)
                InkLoader(.block, caption: "working")
                Color.clear.frame(height: 120).inkCard(seed: 1)
                Color.clear.frame(height: 120).inkCard(seed: 2)
            }
            .padding(16)
        }
    }

    @Test(.timeLimit(.minutes(5))) func animatingNeverReevaluatesTheParent() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: Busy().environment(\.scenePhase, .active).autobotTheme(.dark))
        InkIdleClock.shared.touch(at: Date().addingTimeInterval(2))
        let work = InkClock.work.subscribers, ambient = InkClock.ambient.subscribers
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(400))
        InkProbe.reset()
        let work0 = InkClock.work.ticks, ambient0 = InkClock.ambient.ticks
        try await Task.sleep(for: .seconds(1))
        let counts = InkProbe.snapshot()
        let workTicks = InkClock.work.ticks - work0, ambientTicks = InkClock.ambient.ticks - ambient0

        // The parent was not re-evaluated while seven animated elements drew.
        #expect(counts["body.test", default: 0] == 0)
        #expect(counts["ink.draw", default: 0] > 30)
        // One clock per tier: ≤ 30 work ticks and ≤ 10 ambient ticks a second, however many consumers.
        #expect(workTicks >= 15 && workTicks <= 36)
        #expect(ambientTicks >= 5 && ambientTicks <= 12)
        #expect(InkClock.work.subscribers == work + 5)
        #expect(InkClock.ambient.subscribers == ambient + 2)

        // Off screen, every subscription is released and the clocks stop.
        window.isHidden = true
        window.rootViewController = nil
        try await Task.sleep(for: .milliseconds(300))
        #expect(InkClock.work.subscribers == work)
        #expect(InkClock.ambient.subscribers == ambient)
        if work == 0 && ambient == 0 { #expect(!InkClock.work.running && !InkClock.ambient.running) }
    }

    @Test(.timeLimit(.minutes(5))) func stillAndReducedMotionNeverSubscribe() async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: Busy().environment(\.scenePhase, .active).environment(\.inkStill, true).autobotTheme(.dark))
        let work = InkClock.work.subscribers, ambient = InkClock.ambient.subscribers
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(300))
        #expect(InkClock.work.subscribers == work)
        #expect(InkClock.ambient.subscribers == ambient)
        window.isHidden = true
        window.rootViewController = nil
    }

    // MARK: Frames

    private var dir: String? {
        let env = ProcessInfo.processInfo.environment
        return env["AUTOBOT_MOTION_DIR"] ?? env["AUTOBOT_SNAPSHOT_DIR"]
    }

    private static let themes: [(String, AutobotScheme, Color)] = [
        ("dark", .dark, AutobotScheme.dark.background),
        ("light", .light, AutobotScheme.light.background),
        ("paper", .light, AutobotColors.paper),
        ("amoled", AutobotScheme.dark.amoled(), AutobotScheme.dark.amoled().background),
    ]

    /**
     Renders the v3 elements to PNGs for a visual check, per theme (dark, light, paper, AMOLED):
     the bar states (empty, 25, 60, 100 %, running, loading frames, paused, error), the loader
     sizes over a cycle and a living card and ground at three moments. Skipped unless
     `AUTOBOT_MOTION_DIR` or `AUTOBOT_SNAPSHOT_DIR` is set.
     */
    @Test func renderInkV3Frames() throws {
        guard let dir else { return }
        let cycle = InkMath.loaderCycleSeconds
        for (name, c, ground) in Self.themes {
            let colors = InkBarColors(ink: c.primary, pin: c.outlineVariant, mark: c.onSurfaceVariant)
            let error = InkBarColors(ink: c.error, pin: c.outlineVariant, mark: c.onSurfaceVariant)
            func bar(_ label: String, _ state: InkBarState, seconds: Double = 0, height: CGFloat = 3) -> some View {
                VStack(alignment: .leading, spacing: 4) {
                    MicroLabel(label)
                    Canvas { context, s in
                        InkBar.draw(&context, size: s, state: state, fraction: state.value, seconds: seconds, colors: state.isError ? error : colors)
                    }
                    .frame(height: InkProgressBar.diameter(height))
                }
            }
            try write(VStack(alignment: .leading, spacing: 12) {
                bar("empty", .progress(0, running: false))
                bar("25 %", .progress(0.25, running: false))
                bar("60 % · running", .progress(0.6, running: true), seconds: 0.2)
                bar("100 %", .progress(1, running: false))
                bar("loading · t", .indeterminate, seconds: 0)
                bar("loading · t + ⅓ cycle", .indeterminate, seconds: cycle / 3)
                bar("loading · t + ⅔ cycle", .indeterminate, seconds: cycle * 2 / 3)
                bar("paused · 42 %", .paused(0.42))
                bar("error · 60 %", .error(0.6))
                bar("2pt bar · 64 %", .progress(0.64, running: false), height: 2)
            }
            .padding(20)
            .frame(width: 393)
            .background(ground)
            .environment(\.scheme, c), scale: 3, "\(dir)/v3-bars-\(name).png")

            // Loaders: 6 frames of a cycle, each size.
            try write(VStack(alignment: .leading, spacing: 16) {
                ForEach([InkLoader.Size.inline, .button, .block], id: \.width) { size in
                    HStack(spacing: 18) {
                        ForEach(0 ..< 6, id: \.self) { i in
                            Canvas { context, s in
                                context.fill(InkLoader.dots(size, in: s, seconds: cycle * Double(i) / 6), with: .color(c.primary))
                            }
                            .frame(width: size.width, height: size.height)
                        }
                    }
                }
            }
            .padding(20)
            .background(ground), scale: 4, "\(dir)/v3-loader-\(name).png")

            // Living cards (Run's mode cards) and an empty-state ground, three moments.
            for (i, t) in [0.0, 4.0, 8.0].enumerated() {
                try write(VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        card("Chat", "qwen3 4b · on device", c, seconds: t, seed: 0)
                        card("Agent", "deepseek · cloud", c, seconds: t, seed: 2.1)
                    }
                    .frame(height: 130)
                    card("Code", "add a code model", c, seconds: t, seed: 4.2).frame(height: 100)
                    ZStack {
                        Canvas { context, s in
                            let grid = InkDotGrid()
                            grid.layout(s, base: 12, maxDots: Int(Tokens.Motion.cardMaxDots), fade: .center)
                            InkGround.draw(&context, grid: grid, seconds: t * 3, phase: 0, hero: false, printing: 0, ink: c.outline, plate: nil, opacity: Double(Tokens.Motion.ambientOpacity))
                        }
                        VStack(spacing: 6) {
                            MicroLabel("no sessions")
                            MicroLabel("everything stays encrypted on this device", color: c.outline)
                        }
                    }
                    .frame(height: 260)
                }
                .padding(16)
                .frame(width: 393)
                .background(ground)
                .environment(\.scheme, c), scale: 2, "\(dir)/v3-cards-\(name)-\(i).png")
            }
        }
    }

    private func card(_ title: String, _ subtitle: String, _ c: AutobotScheme, seconds: Double, seed: Double) -> some View {
        ZStack(alignment: .bottomLeading) {
            c.surfaceContainerHigh
            Canvas { context, s in
                let grid = InkDotGrid()
                grid.layout(s, base: Double(Tokens.Motion.cardDotPitch), maxDots: Int(Tokens.Motion.cardMaxDots), fade: UnitPoint(x: 0.2, y: 0.95))
                context.fill(InkField.path(grid, seconds: seconds, period: Double(Tokens.Motion.cardWavePeriodSeconds), seed: seed),
                             with: .color(c.onSurface.opacity(Double(Tokens.Motion.cardDotOpacity))))
            }
            VStack(alignment: .leading, spacing: 0) {
                Text(title).textStyle(.headlineMedium).foregroundStyle(c.onSurface)
                Text(subtitle).textStyle(.micro).foregroundStyle(c.onSurfaceVariant)
            }
            .padding(Tokens.Spacing.cardPadding)
        }
        .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall))
    }

    private func write(_ view: some View, scale: CGFloat, _ path: String) throws {
        let renderer = ImageRenderer(content: view)
        renderer.scale = scale
        let image = try #require(renderer.uiImage)
        try #require(image.pngData()).write(to: URL(fileURLWithPath: path))
    }
}

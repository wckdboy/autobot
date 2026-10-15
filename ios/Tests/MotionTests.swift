import SwiftUI
import Testing
import UIKit
@testable import Autobot

/// The ink motion math (motion spec): easing, develop stagger, flow fill, frame budget, print.
struct MotionTests {
    @Test func easingEndpoints() {
        #expect(InkMath.easeOutCubic(0) == 0)
        #expect(InkMath.easeOutCubic(1) == 1)
        #expect(InkMath.easeOutCubic(-1) == 0)
        #expect(InkMath.easeOutCubic(2) == 1)
        // Ease-out: ahead of linear.
        #expect(InkMath.easeOutCubic(0.5) == 0.875)
        #expect(abs(InkMath.easeOutBack(0)) < 1e-12)
        #expect(abs(InkMath.easeOutBack(1) - 1) < 1e-12)
        // Slight overshoot before settling.
        let peak = stride(from: 0.0, through: 1, by: 0.01).map { InkMath.easeOutBack($0) }.max()!
        #expect(peak > 1 && peak < 1.15)
    }

    @Test func developStaggersAlongTheDiagonal() {
        let s = Double(Tokens.Motion.developStagger)
        // Start: nothing developed; end: everything.
        #expect(InkMath.developLocal(t: 0, x: 0, y: 0) == 0)
        #expect(InkMath.developLocal(t: 1, x: 1, y: 1) == 1)
        #expect(InkMath.developLocal(t: 1, x: 0, y: 0) == 1)
        // The top-left corner leads the bottom-right one.
        #expect(InkMath.developLocal(t: 0.4, x: 0, y: 0) > InkMath.developLocal(t: 0.4, x: 1, y: 1))
        // The far corner starts only once t passes the stagger.
        #expect(InkMath.developLocal(t: s, x: 1, y: 1) == 0)
        #expect(InkMath.developLocal(t: s + 0.1, x: 1, y: 1) > 0)
        // Local progress is the eased clamp((t − s·(x+y)/2) / (1 − s)).
        let t = 0.6, x = 0.3, y = 0.5
        #expect(abs(InkMath.developLocal(t: t, x: x, y: y) - InkMath.easeOutCubic((t - s * (x + y) / 2) / (1 - s))) < 1e-12)
        // Monotonic in t.
        var last = -1.0
        for step in 0 ... 20 {
            let v = InkMath.developLocal(t: Double(step) / 20, x: 0.7, y: 0.2)
            #expect(v >= last)
            last = v
        }
    }

    @Test func flowFillRisesWithProgress() {
        let min = Double(Tokens.Motion.flowMinFill)
        #expect(InkMath.flowFillTarget(progress: 0, loading: false) == min)
        #expect(InkMath.flowFillTarget(progress: 1, loading: false) == 1)
        #expect(InkMath.flowFillTarget(progress: 2, loading: false) == 1)
        #expect(abs(InkMath.flowFillTarget(progress: 0.5, loading: false) - (min + (1 - min) / 2)) < 1e-12)
        // Loading is lighter than the first step.
        #expect(InkMath.flowFillTarget(progress: 0.9, loading: true) < min)
    }

    @Test func approachIsFrameRateIndependent() {
        // Two 1/30 s frames land where one 1/15 s frame does.
        let a = InkMath.approach(InkMath.approach(0, 1, dt: 1.0 / 30, timeConstant: 0.6), 1, dt: 1.0 / 30, timeConstant: 0.6)
        let b = InkMath.approach(0, 1, dt: 1.0 / 15, timeConstant: 0.6)
        #expect(abs(a - b) < 1e-12)
        #expect(InkMath.approach(0.2, 1, dt: 0, timeConstant: 0.6) == 0.2)
        #expect(InkMath.approach(0.2, 1, dt: 1, timeConstant: 0) == 1)
    }

    @Test func frameBudget() {
        #expect(abs(InkMath.frameInterval(lowPower: false) - 0.033) < 1e-9)
        #expect(abs(InkMath.frameInterval(lowPower: true) - 0.066) < 1e-9)
        #expect(InkMath.shouldAnimate(active: true, visible: true, foreground: true, reduceMotion: false))
        #expect(!InkMath.shouldAnimate(active: false, visible: true, foreground: true, reduceMotion: false))
        #expect(!InkMath.shouldAnimate(active: true, visible: false, foreground: true, reduceMotion: false))
        #expect(!InkMath.shouldAnimate(active: true, visible: true, foreground: false, reduceMotion: false))
        #expect(!InkMath.shouldAnimate(active: true, visible: true, foreground: true, reduceMotion: true))
    }

    @Test func dotsAreCapped() {
        #expect(InkMath.dotCell(width: 170, height: 170, base: 6) == 6)
        let cell = InkMath.dotCell(width: 360, height: 640, base: 6)
        #expect(cell > 6)
        #expect(Int(360 / cell) * Int(640 / cell) <= InkMath.maxDots)
        #expect(InkMath.phaseStep(dt: 1, speed: 1) == Double(Tokens.Motion.flowCyclesPerSecond) * 2)
    }

    @Test func printCurves() {
        #expect(InkMath.printScale(0) == 1)
        #expect(abs(InkMath.printScale(1) - Double(Tokens.Motion.printMergeScale)) < 1e-12)
        #expect(InkMath.printImageAlpha(0.5) == 0)
        #expect(InkMath.printImageAlpha(1) == 1)
        #expect(abs(InkMath.registerShift(elapsed: 0) - Double(Tokens.Motion.registerOffset)) < 1e-12)
        #expect(abs(InkMath.registerShift(elapsed: Double(Tokens.Motion.registerMillis) / 1000)) < 1e-12)
        #expect(InkMath.plateAlpha(elapsed: 0.1) == 1)
        #expect(InkMath.plateAlpha(elapsed: Double(Tokens.Motion.printMillis) / 1000) == 0)
    }

    @MainActor @Test func ledgerOnlyAnimatesLiveAdditions() {
        let ledger = InkLedger()
        ledger.observe(["a", "b"])
        #expect(!ledger.isNew("a"))
        // History arriving in bulk is not new.
        ledger.observe(["a", "b", "c", "d", "e", "f"])
        #expect(!ledger.isNew("f"))
        ledger.observe(["a", "b", "c", "d", "e", "f", "g"])
        #expect(ledger.isNew("g"))
        ledger.entered("g")
        #expect(!ledger.isNew("g"))
    }

    @MainActor @Test func plateStages() throws {
        let grid = try #require(LumaGrid(image: Self.sample(dark: true).cgImage!))
        let t0 = Date()
        // A result already there when the plate first renders: shown as is.
        let history = PlateEngine()
        #expect(history.receive(.final(PlateImage(id: "r", grid: grid, image: Self.sample(dark: true))), at: t0) == nil)
        #expect(history.printed)

        let engine = PlateEngine()
        #expect(engine.receive(.flow(progress: 0, loading: true), at: t0) == nil)
        #expect(engine.loops(.flow(progress: 0, loading: true)))
        // The first preview develops.
        let develop = try #require(engine.receive(.preview(PlateImage(id: "1", grid: grid)), at: t0))
        #expect(abs(develop.timeIntervalSince(t0) - Double(Tokens.Motion.developMillis) / 1000) < 1e-6)
        // The result prints.
        let t1 = t0.addingTimeInterval(1)
        let print = try #require(engine.receive(.final(PlateImage(id: "r", grid: grid, image: Self.sample(dark: true))), at: t1))
        #expect(abs(print.timeIntervalSince(t1) - Double(Tokens.Motion.printMillis) / 1000) < 1e-6)
        #expect(engine.imageAlpha(at: t1) == 0)
        engine.settle(at: print)
        #expect(engine.printed)
        #expect(engine.busyUntil == nil)

        // No preview: develops out of the field, then prints.
        let direct = PlateEngine()
        _ = direct.receive(.flow(progress: 0.5, loading: false), at: t0)
        let end = try #require(direct.receive(.final(PlateImage(id: "r", grid: grid, image: Self.sample(dark: true))), at: t0))
        #expect(abs(end.timeIntervalSince(t0) - Double(Tokens.Motion.developMillis + Tokens.Motion.printMillis) / 1000) < 1e-6)
    }

    /**
     Renders the Imagine flow → develop → print sequence to PNGs for a visual check. Skipped unless
     `AUTOBOT_MOTION_DIR` is set (`TEST_RUNNER_AUTOBOT_MOTION_DIR=… xcodebuild test`).
     */
    @MainActor @Test func renderPlateFrames() throws {
        guard let dir = ProcessInfo.processInfo.environment["AUTOBOT_MOTION_DIR"] else { return }
        let preview1 = Self.sample(dark: false, blur: true), preview2 = Self.sample(dark: false, blur: false), final = Self.sample(dark: false, blur: false, colour: true)
        let g1 = try #require(LumaGrid(image: preview1.cgImage!)), g2 = try #require(LumaGrid(image: preview2.cgImage!)), gf = try #require(LumaGrid(image: final.cgImage!))
        let engine = PlateEngine()
        let start = Date()
        var t = 0.0
        var frame = 0
        func render(_ label: String) throws {
            let date = start.addingTimeInterval(t)
            let view = ZStack {
                AutobotScheme.light.surfaceContainerLow
                if let image = engine.image {
                    Image(uiImage: image).resizable().scaledToFill().opacity(engine.imageAlpha(at: date))
                }
                if !engine.printed {
                    Canvas { context, size in
                        engine.draw(&context, size: size, at: date, cell: 6, phase: 0, dot: AutobotScheme.light.primary, idleDot: AutobotScheme.light.outlineVariant, plate: AutobotScheme.light.primary)
                    }
                }
            }
            .frame(width: 176, height: 176)
            .clipped()
            let renderer = ImageRenderer(content: view)
            renderer.scale = 2
            let image = try #require(renderer.uiImage)
            try #require(image.pngData()).write(to: URL(fileURLWithPath: "\(dir)/plate-\(String(format: "%03d", frame))-\(label).png"))
            frame += 1
        }
        func run(_ seconds: Double, every: Double, _ label: String, write: Int) throws {
            let frames = Int(seconds * 30)
            for i in 0 ..< frames {
                t += 1.0 / 30
                // Advance the engine every frame; write a few.
                if i % max(frames / write, 1) == 0 { try render(label) } else { tick(engine, start.addingTimeInterval(t)) }
            }
        }
        _ = engine.receive(.idle, at: start)
        try render("idle")
        _ = engine.receive(.flow(progress: 0, loading: true), at: start)
        try run(1, every: 0, "loading", write: 2)
        _ = engine.receive(.flow(progress: 0.1, loading: false), at: start.addingTimeInterval(t))
        try run(1.5, every: 0, "flow", write: 3)
        _ = engine.receive(.preview(PlateImage(id: "1", grid: g1)), at: start.addingTimeInterval(t))
        try run(1.0, every: 0, "develop", write: 6)
        _ = engine.receive(.preview(PlateImage(id: "2", grid: g2)), at: start.addingTimeInterval(t))
        try run(1.0, every: 0, "blend", write: 3)
        _ = engine.receive(.final(PlateImage(id: "f", grid: gf, image: final)), at: start.addingTimeInterval(t))
        try run(1.0, every: 0, "print", write: 8)
        engine.settle(at: start.addingTimeInterval(t))
        try render("printed")
    }

    @MainActor private func tick(_ engine: PlateEngine, _ date: Date) {
        let renderer = ImageRenderer(content: Canvas { context, size in
            engine.draw(&context, size: size, at: date, cell: 6, phase: 0, dot: .red, idleDot: .gray, plate: .red)
        }.frame(width: 176, height: 176))
        _ = renderer.cgImage
    }

    /// A test picture: a dark disc on light paper (or the reverse), optionally blurred / coloured.
    static func sample(dark: Bool, blur: Bool = false, colour: Bool = false) -> UIImage {
        let size = CGSize(width: 256, height: 256)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let g = ctx.cgContext
            (colour ? UIColor(red: 0.95, green: 0.85, blue: 0.6, alpha: 1) : UIColor(white: dark ? 0.1 : 0.92, alpha: 1)).setFill()
            g.fill(CGRect(origin: .zero, size: size))
            let colors = colour
                ? [UIColor(red: 0.15, green: 0.2, blue: 0.5, alpha: 1).cgColor, UIColor(red: 0.9, green: 0.4, blue: 0.3, alpha: 0.2).cgColor]
                : [UIColor(white: dark ? 0.9 : 0.05, alpha: 1).cgColor, UIColor(white: dark ? 0.9 : 0.05, alpha: 0).cgColor]
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 1])!
            g.drawRadialGradient(gradient, startCenter: CGPoint(x: 110, y: 120), startRadius: blur ? 0 : 40, endCenter: CGPoint(x: 110, y: 120), endRadius: blur ? 120 : 90, options: [])
            if !blur {
                UIColor(white: 0.1, alpha: 1).setFill()
                g.fill(CGRect(x: 170, y: 180, width: 60, height: 50))
            }
        }
    }
}

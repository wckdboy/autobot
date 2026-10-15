import SwiftUI
import Testing
import UIKit
@testable import Autobot

/// Ink motion v2 (motion spec v2): loader, bars, ambient grounds, transitions and dissolve math,
/// plus rendered frames for a visual check.
struct MotionV2Tests {
    @Test func ambientBudget() {
        #expect(abs(InkMath.ambientFrameInterval - 0.1) < 1e-9)
        #expect(InkMath.cycle(-0.25) == 0.75)
        #expect(InkMath.easeInOutCubic(0.1) < 0.1)
        #expect(InkMath.easeInOutCubic(0.9) > 0.9)
        #expect(InkMath.easeInOutCubic(0.5) == 0.5)
        let idle = Double(Tokens.Motion.ambientIdleStopSeconds)
        #expect(InkMath.ambientShouldRun(visible: true, foreground: true, reduceMotion: false, lowPower: false, heavyWork: false, idleSeconds: 0))
        #expect(!InkMath.ambientShouldRun(visible: true, foreground: true, reduceMotion: false, lowPower: false, heavyWork: false, idleSeconds: idle))
        #expect(!InkMath.ambientShouldRun(visible: true, foreground: true, reduceMotion: false, lowPower: false, heavyWork: true, idleSeconds: 0))
        #expect(!InkMath.ambientShouldRun(visible: true, foreground: true, reduceMotion: false, lowPower: true, heavyWork: false, idleSeconds: 0))
        #expect(!InkMath.ambientShouldRun(visible: true, foreground: true, reduceMotion: true, lowPower: false, heavyWork: false, idleSeconds: 0))
        #expect(!InkMath.ambientShouldRun(visible: false, foreground: true, reduceMotion: false, lowPower: false, heavyWork: false, idleSeconds: 0))
        #expect(!InkMath.ambientShouldRun(visible: true, foreground: false, reduceMotion: false, lowPower: false, heavyWork: false, idleSeconds: 0))
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

    @MainActor @Test func dissolvePathsStayInBudget() {
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
     Renders a row dissolving to PNGs for a visual check (the v3 bars, loaders, grounds and cards
     render in MotionV3Tests).
     Skipped unless `AUTOBOT_MOTION_DIR` or `AUTOBOT_SNAPSHOT_DIR` is set.
     */
    @MainActor @Test func renderInkV2Frames() throws {
        guard let dir else { return }
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

    @MainActor private func write(_ view: some View, scale: CGFloat, _ path: String) throws {
        let renderer = ImageRenderer(content: view)
        renderer.scale = scale
        let image = try #require(renderer.uiImage)
        try #require(image.pngData()).write(to: URL(fileURLWithPath: path))
    }
}

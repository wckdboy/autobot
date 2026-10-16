import QuartzCore
import SwiftUI
import UIKit

/**
 The one frame clock per tier (motion spec v3 §4): every ink animation on screen draws from the
 same tick, so N loaders, bars and plates cost one display-link callback per frame, all in phase.
 `.work`: 30 fps (15 in Low Power Mode). `.ambient`: 5 fps, off in Low Power Mode and after
 `ambientIdleStopSeconds` without a touch (a touch wakes it).

 Only the tiny leaf view that hosts a Canvas reads [now] (see [InkTimeline]), so a tick
 re-evaluates that leaf and redraws its canvas, never a parent. The display link runs only
 while at least one visible consumer holds a subscription.
 */
@MainActor
@Observable
final class InkClock {
    static let work = InkClock(tier: .work)
    static let ambient = InkClock(tier: .ambient)

    static func shared(_ tier: InkTier) -> InkClock { tier == .work ? work : ambient }

    let tier: InkTier
    /// The current frame's time. Observed: reading it subscribes the reader to every tick.
    private(set) var now = Date()
    @ObservationIgnored private(set) var subscribers = 0
    @ObservationIgnored private(set) var running = false
    @ObservationIgnored private var link: CADisplayLink?
    @ObservationIgnored private var proxy: LinkProxy?
    @ObservationIgnored private var lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
    @ObservationIgnored private var powerObserver: NSObjectProtocol?
    @ObservationIgnored private(set) var ticks = 0

    private init(tier: InkTier) {
        self.tier = tier
        powerObserver = NotificationCenter.default.addObserver(forName: .NSProcessInfoPowerStateDidChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.powerChanged() }
        }
    }

    func subscribe() {
        subscribers += 1
        update()
    }

    func unsubscribe() {
        subscribers = max(subscribers - 1, 0)
        update()
    }

    /// A touch: the ambient tier wakes from its idle sleep.
    func wake() { if tier == .ambient { update() } }

    private func powerChanged() {
        lowPower = ProcessInfo.processInfo.isLowPowerModeEnabled
        link?.invalidate()
        link = nil
        update()
    }

    private var asleep: Bool {
        tier == .ambient && Date().timeIntervalSince(InkIdleClock.shared.lastTouch) >= Double(Tokens.Motion.ambientIdleStopSeconds)
    }

    private func update() {
        let shouldRun = subscribers > 0 && !(tier == .ambient && lowPower) && !asleep
        guard shouldRun != running || (shouldRun && link == nil) else { return }
        running = shouldRun
        if shouldRun {
            let proxy = LinkProxy { [weak self] in self?.tick() }
            let link = CADisplayLink(target: proxy, selector: #selector(LinkProxy.fire))
            let fps = Float(1 / (tier == .ambient ? InkMath.ambientFrameInterval : InkMath.frameInterval(lowPower: lowPower))).rounded()
            link.preferredFrameRateRange = CAFrameRateRange(minimum: max(fps / 2, 1), maximum: fps, preferred: fps)
            link.add(to: .main, forMode: .common)
            self.link = link
            self.proxy = proxy
            now = Date()
        } else {
            link?.invalidate()
            link = nil
            proxy = nil
        }
    }

    private func tick() {
        if asleep {
            update()
            return
        }
        ticks &+= 1
        now = Date()
    }

    /// Breaks the display link's strong reference to the clock.
    @MainActor
    private final class LinkProxy: NSObject {
        let onFire: @MainActor () -> Void
        init(_ onFire: @escaping @MainActor () -> Void) { self.onFire = onFire }
        // Display links added to the main run loop fire on the main thread.
        @objc func fire() { onFire() }
    }
}

/// Holds one subscription to a clock while a consumer runs; released when it stops or goes away.
@MainActor
final class InkSubscription {
    private var clock: InkClock?

    func set(_ clock: InkClock?) {
        guard clock !== self.clock else { return }
        self.clock?.unsubscribe()
        self.clock = clock
        clock?.subscribe()
    }

    isolated deinit { clock?.unsubscribe() }
}

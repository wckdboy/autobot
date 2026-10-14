import Foundation
import Testing
@testable import Autobot

/// The image benchmark's step time comes from step callbacks, never from model load or decode.
struct ComputeBenchTests {
    private let t0 = Date(timeIntervalSince1970: 1_000)

    @Test func loadIsExcludedFromStepTime() {
        // 3 s to load, then one step every 250 ms (steps 1…4), then a slow 2 s VAE decode.
        let ticks = (1 ... 4).map { (step: $0, at: t0.addingTimeInterval(3 + 0.25 * Double($0))) }
        let timing = ImageBenchTiming.measure(started: t0, ticks: ticks)
        #expect(abs((timing.stepMs ?? 0) - 250) < 0.5)
        #expect(timing.loadMs == 3000)
    }

    @Test func callbacksOutOfOrderAreSorted() {
        let ticks = [(step: 2, at: t0.addingTimeInterval(1.4)), (step: 1, at: t0.addingTimeInterval(1.2))]
        let timing = ImageBenchTiming.measure(started: t0, ticks: ticks)
        #expect(abs((timing.stepMs ?? 0) - 200) < 0.5)
        #expect(timing.loadMs == 1000)
    }

    @Test func noProgressMeansNoMeasurement() {
        let timing = ImageBenchTiming.measure(started: t0, ticks: [])
        #expect(timing.stepMs == nil)
        #expect(timing.loadMs == nil)
    }
}

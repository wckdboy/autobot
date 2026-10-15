import Foundation
import QuartzCore

/**
 Debug counters for the animation budget (motion spec v3 §4): screens count their body
 evaluations and ink canvases count their draws, so a test can assert that animating never
 re-evaluates a parent. Compiled out of Release builds.
 */
enum InkProbe {
    #if DEBUG
    private static let lock = NSLock()
    nonisolated(unsafe) private static var counts: [String: Int] = [:]
    /// The last 60 Hz frame an ink canvas drew in: draws in the same frame share one render pass.
    nonisolated(unsafe) private static var lastDrawFrame = -1

    static func snapshot() -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        return counts
    }

    static func reset() {
        lock.lock(); defer { lock.unlock() }
        counts = [:]
        lastDrawFrame = -1
    }
    #endif

    /// Counts one [key] event (a body evaluation, a canvas draw).
    @inline(__always)
    static func hit(_ key: StaticString) {
        #if DEBUG
        lock.lock(); defer { lock.unlock() }
        let name = "\(key)"
        counts[name, default: 0] += 1
        if name == "ink.draw" {
            // Distinct frames with ink drawing: how many render passes the animations cause.
            let frame = Int(CACurrentMediaTime() * 60)
            if frame != lastDrawFrame {
                lastDrawFrame = frame
                counts["ink.frames", default: 0] += 1
            }
        }
        #endif
    }
}

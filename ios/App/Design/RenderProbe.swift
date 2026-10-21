import Foundation

/**
 Debug counters for the render budget: screens and rows count their body evaluations, so a test
 can assert that a change re-evaluates only what it touches. Compiled out of Release builds.
 */
enum RenderProbe {
    #if DEBUG
    private static let lock = NSLock()
    nonisolated(unsafe) private static var counts: [String: Int] = [:]

    static func snapshot() -> [String: Int] {
        lock.lock(); defer { lock.unlock() }
        return counts
    }

    static func reset() {
        lock.lock(); defer { lock.unlock() }
        counts = [:]
    }
    #endif

    /// Counts one [key] event (a body evaluation, a thumbnail load).
    @inline(__always)
    static func hit(_ key: StaticString) {
        #if DEBUG
        lock.lock(); defer { lock.unlock() }
        counts["\(key)", default: 0] += 1
        #endif
    }
}

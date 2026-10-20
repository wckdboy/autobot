import Foundation
import os

/**
 Time to first token shown: from the tap on Send to the first render of reply text, logged once
 per turn as `ttft <ms> ms` (subsystem ai.wckd.autobot, category perf). No content, only the time.
 */
@MainActor
enum ChatTiming {
    private static var sentAt: UInt64 = 0
    private static let log = Logger(subsystem: "ai.wckd.autobot", category: "perf")

    static func sent() { sentAt = DispatchTime.now().uptimeNanoseconds }

    /// A render showing [text].
    static func shown(_ text: String) {
        guard sentAt != 0, !text.isEmpty else { return }
        let ms = (DispatchTime.now().uptimeNanoseconds - sentAt) / 1_000_000
        sentAt = 0
        log.notice("ttft \(ms, privacy: .public) ms")
    }
}

import Foundation
import Testing
import UIKit
@testable import Autobot

/// Going to the background mid-work asks for time to finish, and gives it back when done.
@MainActor
struct BackgroundGraceTests {
    private final class Recorder {
        var begun = 0
        var ended: [UIBackgroundTaskIdentifier] = []
        var expire: (@MainActor () -> Void)?
    }

    private func grace(_ r: Recorder) -> BackgroundGrace {
        BackgroundGrace(begin: { _, expired in
            r.begun += 1
            r.expire = expired
            return UIBackgroundTaskIdentifier(rawValue: r.begun)
        }, end: { r.ended.append($0) })
    }

    @Test func idleAppAsksForNothing() {
        let r = Recorder()
        let g = grace(r)
        g.enteredBackground(busy: false)
        #expect(r.begun == 0)
        #expect(!g.active)
    }

    @Test func graceEndsWhenTheWorkFinishes() {
        let r = Recorder()
        let g = grace(r)
        g.enteredBackground(busy: true)
        g.enteredBackground(busy: true)
        #expect(r.begun == 1)
        g.workChanged(busy: true)
        #expect(r.ended.isEmpty)
        g.workChanged(busy: false)
        #expect(r.ended == [UIBackgroundTaskIdentifier(rawValue: 1)])
        g.finish()
        #expect(r.ended.count == 1)
    }

    @Test func graceEndsWhenItExpires() {
        let r = Recorder()
        let g = grace(r)
        g.enteredBackground(busy: true)
        r.expire?()
        #expect(r.ended == [UIBackgroundTaskIdentifier(rawValue: 1)])
        #expect(!g.active)
    }

    @Test func activityCountsTripsToTheBackground() {
        let activity = AppActivity()
        let epoch = activity.epoch
        #expect(!activity.interrupted(since: epoch))
        activity.enteredBackground()
        activity.enteredBackground()
        #expect(activity.inBackground)
        activity.enteredForeground()
        #expect(activity.interrupted(since: epoch))
        #expect(activity.epoch == epoch + 1)
    }
}

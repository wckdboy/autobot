import Foundation
import Synchronization
import UIKit

/**
 Whether the app is in the background, readable from any thread (the agent bridge's engine host
 runs on Kotlin's threads). [epoch] counts trips to the background, so work that spans one can
 tell it was interrupted (iOS refuses GPU work from the background).
 */
final class AppActivity: Sendable {
    static let shared = AppActivity()

    private let state = Mutex((background: false, epoch: 0))

    var inBackground: Bool { state.withLock { $0.background } }
    var epoch: Int { state.withLock { $0.epoch } }

    func enteredBackground() {
        state.withLock { s in
            if !s.background { s.epoch += 1 }
            s.background = true
        }
    }

    func enteredForeground() { state.withLock { $0.background = false } }

    /// Whether the app went to the background after [epoch] was read.
    func interrupted(since epoch: Int) -> Bool { state.withLock { $0.epoch != epoch } }
}

/**
 Time to finish when the app goes to the background mid-work (an agent turn, an image job): a
 UIKit background task begun on `.background` while work runs, ended when the work finishes, the
 app comes back, or the system's grace (about 30 s) runs out.
 */
@MainActor
final class BackgroundGrace {
    typealias Begin = @MainActor (_ name: String, _ expired: @escaping @MainActor () -> Void) -> UIBackgroundTaskIdentifier
    typealias End = @MainActor (UIBackgroundTaskIdentifier) -> Void

    private let begin: Begin
    private let end: End
    private var task = UIBackgroundTaskIdentifier.invalid

    var active: Bool { task != .invalid }

    init(
        begin: @escaping Begin = { name, expired in
            UIApplication.shared.beginBackgroundTask(withName: name) { MainActor.assumeIsolated { expired() } }
        },
        end: @escaping End = { UIApplication.shared.endBackgroundTask($0) },
    ) {
        self.begin = begin
        self.end = end
    }

    /// The app went to the background; [busy]: work is running that should get to finish.
    func enteredBackground(busy: Bool) {
        guard busy, task == .invalid else { return }
        let id = begin("ai.wckd.autobot.finish-work") { [weak self] in self?.finish() }
        task = id
    }

    /// The running work changed; once nothing runs the grace is given back.
    func workChanged(busy: Bool) {
        if !busy { finish() }
    }

    func finish() {
        guard task != .invalid else { return }
        let id = task
        task = .invalid
        end(id)
    }
}

import Foundation
import UIKit

/**
 When the on-device engines give their memory back. A memory warning releases them at once. Going
 to the background no longer unloads the chat model on every app switch (reloading a few GB on
 return made the app feel slow): they are released after [grace], or when the background time
 iOS grants runs out, whichever comes first; coming back before then cancels it.
 */
@MainActor
final class EngineReleaseScheduler {
    static let defaultGrace: Duration = .seconds(30)

    private let grace: Duration
    private let release: @MainActor () async -> Void
    private let begin: BackgroundGrace.Begin
    private let end: BackgroundGrace.End
    private var timer: Task<Void, Never>?
    private var backgroundTask = UIBackgroundTaskIdentifier.invalid

    /// Whether a release is scheduled (tests).
    var pending: Bool { timer != nil }

    init(
        grace: Duration = EngineReleaseScheduler.defaultGrace,
        release: @escaping @MainActor () async -> Void = { await EngineMemory.shared.releaseAll() },
        begin: @escaping BackgroundGrace.Begin = { name, expired in
            UIApplication.shared.beginBackgroundTask(withName: name) { MainActor.assumeIsolated { expired() } }
        },
        end: @escaping BackgroundGrace.End = { UIApplication.shared.endBackgroundTask($0) },
    ) {
        self.grace = grace
        self.release = release
        self.begin = begin
        self.end = end
    }

    /// The app went to the background: release after the grace (or when its time runs out).
    func enteredBackground() {
        guard timer == nil else { return }
        // Out of background time: release now and hand the time back at once (iOS requires it).
        backgroundTask = begin("ai.wckd.autobot.release-engines") { [weak self] in self?.fire(expired: true) }
        let grace = grace
        timer = Task { [weak self] in
            try? await Task.sleep(for: grace)
            guard !Task.isCancelled else { return }
            self?.fire()
        }
    }

    /// Back in the foreground before the grace ended: the engines stay.
    func becameActive() {
        timer?.cancel()
        timer = nil
        endBackgroundTask()
    }

    /// Memory warning: release now.
    func memoryWarning() { fire() }

    private func fire(expired: Bool = false) {
        timer?.cancel()
        timer = nil
        let release = release
        if expired { endBackgroundTask() }
        Task {
            await release()
            self.endBackgroundTask()
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        let id = backgroundTask
        backgroundTask = .invalid
        end(id)
    }
}

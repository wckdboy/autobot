import Observation
import UIKit

/**
 The UIKit side of the SwiftUI app: only what SwiftUI has no hook for. iOS relaunches (or wakes)
 the app in the background when a model download's background URLSession has events; the
 container (created here at launch, before any scene) resumes the queue and [BackgroundEvents]
 calls the system's completion handler once the finished file is stored and the next one is
 transferring. A UIKit background task covers the verification (hashing a multi-GB file).
 */
@MainActor
final class AppDelegate: NSObject, UIApplicationDelegate {
    private static var container: AppContainer?

    /// The process's one container, shared by the scene and background launches.
    static func makeContainer() -> AppContainer {
        if let container { return container }
        let made = AppContainer()
        container = made
        return made
    }

    /// A panic wipe finished: the next [makeContainer] builds a new, empty app.
    static func dropContainer() { container = nil }

    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void,
    ) {
        guard identifier.hasPrefix(BackgroundTransfer.identifier) else {
            completionHandler()
            return
        }
        let grace = Grace()
        grace.id = application.beginBackgroundTask(withName: "ai.wckd.autobot.models.events") { MainActor.assumeIsolated { grace.end() } }
        BackgroundEvents.shared.handle(identifier) {
            completionHandler()
            grace.end()
        }
        Self.makeContainer().resumeDownloadsInBackground(identifier)
    }

    /// The UIKit background task held while the events are processed.
    @MainActor private final class Grace {
        var id = UIBackgroundTaskIdentifier.invalid

        func end() {
            guard id != .invalid else { return }
            UIApplication.shared.endBackgroundTask(id)
            id = .invalid
        }
    }
}

/**
 The scene's container and navigator. A panic wipe from the lock screen (the duress PIN, the
 failed-unlock limit) empties the app in this process and then [restart]s it: Android restarts
 its process; iOS cannot relaunch itself without the user seeing the app close, so it builds a
 fresh container on the emptied storage and the scene shows it as a new start.
 */
@MainActor
@Observable
final class ContainerHolder {
    static let shared = ContainerHolder()

    private(set) var container: AppContainer
    private(set) var navigator = Navigator()
    private(set) var generation = 0

    private init() { container = AppDelegate.makeContainer() }

    func restart() {
        AppDelegate.dropContainer()
        container = AppDelegate.makeContainer()
        navigator = Navigator()
        generation += 1
    }
}

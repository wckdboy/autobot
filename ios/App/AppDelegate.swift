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

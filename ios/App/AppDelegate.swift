import BackgroundTasks
import UIKit
import UserNotifications

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

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Recipes: the processing task must be registered before launch ends.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: RecipeBackground.taskId, using: .main) { task in
            nonisolated(unsafe) let task = task
            MainActor.assumeIsolated { Self.runRecipes(task) }
        }
        RecipeNotifier.registerCategory()
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    /**
     iOS gave the app time for recipes. While the phone is locked the store cannot be read: the
     task only says "Unlock to run <name>" (from the schedule index) and asks again later.
     Otherwise it loads the data and runs what is due, on the CPU (no GPU in the background).
     */
    private static func runRecipes(_ task: BGTask) {
        let app = makeContainer()
        nonisolated(unsafe) let task = task
        let work = Task { @MainActor in
            if !UIApplication.shared.isProtectedDataAvailable {
                RecipeBackground.submit(RecipeCenter.lockedTask(notifier: RecipeNotifier()))
                task.setTaskCompleted(success: true)
                return
            }
            await app.unlockData()
            app.recipes.runDue()
            await app.recipes.idle()
            app.recipes.reschedule()
            task.setTaskCompleted(success: !Task.isCancelled)
        }
        task.expirationHandler = {
            Task { @MainActor in
                app.recipes.stop()
                work.cancel()
            }
        }
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

/// Recipe notifications: shown while the app is open too; a tap opens the result chat (after the app lock).
extension AppDelegate: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        // Only recipe results show while the app is open (downloads keep iOS's default: none).
        notification.request.content.categoryIdentifier == "ai.wckd.autobot.recipe" ? [.banner, .list, .sound] : []
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard info["recipe"] != nil else { return }
        let session = info["session"] as? String
        await MainActor.run { EntryRouter.shared.receive(.recipe(conversationId: session)) }
    }
}

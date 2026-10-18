import BackgroundTasks
import Foundation
import Synchronization
import UIKit
import UserNotifications

/**
 How recipes get time on iOS. The system decides when a `BGProcessingTask` runs: the request
 only says "not before" the next recipe's time (and whether it needs power or a network), so a
 recipe runs around its time, often later. While the phone is locked the encrypted store cannot
 be read (`NSFileProtectionComplete`): such a task only says "Unlock to run <name>" and the run
 waits for the next unlock or app open. In the background iOS refuses GPU work, so the on-device
 model runs a background recipe on the CPU.
 */
enum RecipeBackground {
    static let taskId = "ai.wckd.autobot.recipes"

    /// What the next processing task request asks for.
    struct Plan: Equatable, Sendable {
        var earliest: Int64
        var requiresPower: Bool
        var requiresNetwork: Bool
    }

    /// Recipes due within this much of the earliest one are served by the same task.
    static let batchWindowMs: Int64 = 3_600_000
    /// A recipe waiting for an unlock asks for another try this much later.
    static let retryMs: Int64 = 3_600_000

    /// The request for [recipes] at [now] (nil: nothing to schedule).
    static func plan(_ recipes: [RecipeDoc], now: Int64) -> Plan? {
        let waiting = recipes.contains { $0.enabled && $0.state.waitingForUnlock }
        let wake = RecipeRules.nextWake(recipes)
        guard wake != nil || waiting else { return nil }
        let earliest = min(wake ?? Int64.max, waiting ? now + retryMs : Int64.max)
        let soon = recipes.filter { r in r.enabled && (r.state.waitingForUnlock || (r.state.nextRunAt.map { $0 <= earliest + batchWindowMs } ?? false)) }
        return Plan(
            earliest: earliest,
            // Only when every recipe it serves wants power: otherwise one would wait for a charger.
            requiresPower: !soon.isEmpty && soon.allSatisfy(\.requiresCharging),
            requiresNetwork: soon.contains { $0.usesNetwork },
        )
    }

    /// Replaces the pending request with [plan] (or cancels it).
    static func submit(_ plan: Plan?) {
        let scheduler = BGTaskScheduler.shared
        scheduler.cancel(taskRequestWithIdentifier: taskId)
        guard let plan else { return }
        let request = BGProcessingTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date(timeIntervalSince1970: TimeInterval(plan.earliest) / 1000)
        request.requiresExternalPower = plan.requiresPower
        request.requiresNetworkConnectivity = plan.requiresNetwork
        do {
            try scheduler.submit(request)
        } catch {
            // The simulator and some states refuse requests; the app runs due recipes when opened.
            EngineErrors.record("Recipes: background request refused (\(error.localizedDescription))")
        }
    }

    // MARK: CPU while in the background

    private static let state = Mutex((running: false, startedInBackground: false))

    /// A recipe run started ([background]: with the app not active).
    static func began(background: Bool) { state.withLock { $0 = (true, background) } }

    static func ended() { state.withLock { $0 = (false, false) } }

    /// Whether the on-device model must use the CPU now: a recipe runs and the app is (or started) in the background.
    static var cpuOnly: Bool {
        let s = state.withLock { $0 }
        return s.running && (s.startedInBackground || AppActivity.shared.inBackground)
    }

    /// The backend a request should use (iOS forbids GPU work from the background).
    static func backend(_ preferred: Backend, cpuOnly: Bool = RecipeBackground.cpuOnly) -> Backend { cpuOnly ? .cpu : preferred }
}

/// Recipe notifications. The title carries only the recipe's name; the body says more only when
/// "Summary in notifications" is on, and the lock screen (previews hidden, the iOS default) shows
/// the title alone.
@MainActor
protocol RecipeNotifying: AnyObject {
    func requestAuthorization() async
    func finished(_ recipe: RecipeDoc, record: String)
    func unlockToRun(id: String, name: String)
    func withdrawUnlock(_ ids: [String])
    func removeAll()
}

@MainActor
final class RecipeNotifier: RecipeNotifying {
    static let category = "ai.wckd.autobot.recipe"
    static let summaryKey = "ai.wckd.autobot.recipes.summaryInNotifications"
    static let finishedPrefix = "ai.wckd.autobot.recipe.done."
    static let unlockPrefix = "ai.wckd.autobot.recipe.unlock."

    /// The "Summary in notifications" switch (off by default).
    static var showSummary: Bool {
        get { UserDefaults.standard.bool(forKey: summaryKey) }
        set { UserDefaults.standard.set(newValue, forKey: summaryKey) }
    }

    /// Registers the category whose hidden-preview placeholder keeps content off the lock screen.
    static func registerCategory() {
        let category = UNNotificationCategory(
            identifier: Self.category, actions: [], intentIdentifiers: [],
            hiddenPreviewsBodyPlaceholder: RecipeRules.copy.OPEN_TO_READ,
            options: [.hiddenPreviewsShowTitle],
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    /// A finished (or failed) run's notification.
    static func content(_ recipe: RecipeDoc, record: String, showSummary: Bool) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = RecipeRules.notificationTitle(recipe.name, record: record)
        content.body = RecipeRules.notificationBody(record: record, showSummary: showSummary)
        content.categoryIdentifier = category
        content.threadIdentifier = category
        var info: [String: String] = ["recipe": recipe.id]
        if let session = RecipeRunDoc(json: record)?.sessionId { info["session"] = session }
        content.userInfo = info
        return content
    }

    /// "Unlock to run <name>", with nothing else.
    static func unlockContent(id: String, name: String) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = RecipeRules.copy.unlockToRun(name: name)
        content.body = RecipeRules.copy.UNLOCK_BODY
        content.categoryIdentifier = category
        content.threadIdentifier = category
        content.userInfo = ["recipe": id]
        return content
    }

    func requestAuthorization() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
    }

    func finished(_ recipe: RecipeDoc, record: String) {
        let content = Self.content(recipe, record: record, showSummary: Self.showSummary)
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: Self.finishedPrefix + recipe.id, content: content, trigger: nil))
    }

    func unlockToRun(id: String, name: String) {
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: Self.unlockPrefix + id, content: Self.unlockContent(id: id, name: name), trigger: nil))
    }

    func withdrawUnlock(_ ids: [String]) {
        let center = UNUserNotificationCenter.current()
        let names = ids.map { Self.unlockPrefix + $0 }
        center.removeDeliveredNotifications(withIdentifiers: names)
        center.removePendingNotificationRequests(withIdentifiers: names)
    }

    func removeAll() {
        let center = UNUserNotificationCenter.current()
        center.getDeliveredNotifications { list in
            let ids = list.map(\.request.identifier).filter { $0.hasPrefix(RecipeNotifier.finishedPrefix) || $0.hasPrefix(RecipeNotifier.unlockPrefix) }
            center.removeDeliveredNotifications(withIdentifiers: ids)
        }
        center.getPendingNotificationRequests { list in
            let ids = list.map(\.identifier).filter { $0.hasPrefix(RecipeNotifier.finishedPrefix) || $0.hasPrefix(RecipeNotifier.unlockPrefix) }
            center.removePendingNotificationRequests(withIdentifiers: ids)
        }
    }
}

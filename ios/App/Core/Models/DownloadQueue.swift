import Foundation
import Network
import Observation
import UserNotifications

/// Why the queue is not moving, in the order the panel reports them.
enum DownloadBlock: Equatable, Sendable {
    /// The kill switch (Offline in Privacy Center). Downloads go through it like every request.
    case killSwitch
    case noNetwork
    case lowStorage

    var label: String {
        switch self {
        case .killSwitch: "network is off · kill switch in privacy center"
        case .noNetwork: "no network · continues when you're back online"
        case .lowStorage: "not enough storage · free some space, then resume"
        }
    }
}

/// The downloads panel: what runs, what waits and why (Android's `DownloadsState`).
struct DownloadsState: Equatable, Sendable {
    var active: DownloadProgress?
    /// Waiting their turn, oldest first (the order they run in).
    var queued: [InstalledModel] = []
    var paused: [InstalledModel] = []
    var block: DownloadBlock?

    var isEmpty: Bool { active == nil && queued.isEmpty && paused.isEmpty }

    /// What the panel's header explains: the queue's block, else why paused rows wait (the kill
    /// switch pauses everything it stops, so nothing is queued then).
    var blocks: [DownloadBlock] {
        if let block { return [block] }
        let reasons = paused.compactMap(DownloadQueue.pausedReason)
        return [DownloadBlock.killSwitch, .lowStorage].filter(reasons.contains)
    }
}

/**
 The rules of the download queue (the port of `DownloadQueue.kt`): the queue is the model registry
 itself (queued rows, oldest first), so it survives a relaunch; a download that was in flight when
 the app went away queues again and continues from its part file / resume data.
 */
enum DownloadQueue {
    /// Error kept on a model the kill switch stopped; it resumes when the network comes back on.
    static let killSwitchError = "Network is off (kill switch). Downloads continue when you allow a network mode in Privacy Center."
    static let lowStoragePrefix = "Not enough storage"

    static func queued(_ models: [InstalledModel]) -> [InstalledModel] {
        models.filter { $0.status == .queued }.sorted { ($0.addedAt, $0.id) < ($1.addedAt, $1.id) }
    }

    static func next(_ models: [InstalledModel]) -> InstalledModel? { queued(models).first }

    /// After a relaunch nothing runs: what was downloading queues again (it resumes, not restarts).
    static func recover(_ models: [InstalledModel]) -> [InstalledModel] { models.filter { $0.status == .downloading } }

    static func blockedByKillSwitch(_ models: [InstalledModel]) -> [InstalledModel] {
        models.filter { $0.status == .paused && $0.error == killSwitchError }
    }

    static func block(networkOff: Bool, connected: Bool) -> DownloadBlock? {
        if networkOff { return .killSwitch }
        if !connected { return .noNetwork }
        return nil
    }

    static func pausedReason(_ model: InstalledModel) -> DownloadBlock? {
        guard model.status == .paused else { return nil }
        if model.error == killSwitchError { return .killSwitch }
        if model.error?.hasPrefix(lowStoragePrefix) == true { return .lowStorage }
        return nil
    }

    /// Seconds to move [remaining] bytes at [bytesPerSecond]; nil when unknown.
    static func eta(remaining: Int64, bytesPerSecond: Int64) -> Int64? {
        guard bytesPerSecond > 0, remaining >= 0 else { return nil }
        return (remaining + bytesPerSecond - 1) / bytesPerSecond
    }

    /// "45 s", "12 min", "1 h 05 min".
    static func formatEta(_ seconds: Int64) -> String {
        if seconds < 60 { return "\(seconds) s" }
        if seconds < 3600 { return "\((seconds + 59) / 60) min" }
        return "\(seconds / 3600) h " + String(format: "%02d", (seconds % 3600) / 60) + " min"
    }

    /// Exponentially smoothed speed, so the ETA does not jump with every packet.
    static func smooth(_ previous: Int64, _ sample: Int64) -> Int64 {
        previous <= 0 ? sample : Int64(Double(previous) * 0.7 + Double(sample) * 0.3)
    }

    static func state(_ models: [InstalledModel], progress: DownloadProgress?, networkOff: Bool, connected: Bool) -> DownloadsState {
        let active = progress.flatMap { p in models.contains { $0.id == p.id && $0.status == .downloading } ? p : nil }
        let waiting = queued(models)
        return DownloadsState(
            active: active,
            queued: waiting.filter { $0.id != active?.id },
            paused: models.filter { $0.status == .paused || $0.status == .failed }.sorted { $0.addedAt < $1.addedAt },
            block: active != nil || !waiting.isEmpty ? block(networkOff: networkOff, connected: connected) : nil,
        )
    }
}

extension DownloadProgress {
    /// Seconds left at the current speed; nil while the speed is unknown.
    var etaSeconds: Int64? { DownloadQueue.eta(remaining: total - downloaded, bytesPerSecond: bytesPerSecond) }
}

/**
 Hands the system back its completion handler for background URLSession events
 (`application(_:handleEventsForBackgroundURLSession:completionHandler:)`) only once the work
 those events started is done: the session delivered everything (`urlSessionDidFinishEvents`)
 and the downloader has verified and stored the finished file and either has the next transfer
 running in the background session or nothing left to do. Calling it earlier lets iOS suspend the
 app before the next file is enqueued, which would stall the queue until the app is opened.
 */
@MainActor
final class BackgroundEvents {
    static let shared = BackgroundEvents()

    private var handlers: [String: () -> Void] = [:]
    private var drained: Set<String> = []
    private var deadline: Task<Void, Never>?
    /// Whether the downloader has settled (see the type's comment).
    var settled: @MainActor () -> Bool = { true }
    /// Upper bound on holding a handler (iOS allows about 30 s).
    var timeout: Duration = .seconds(25)

    var pending: Int { handlers.count }

    /// iOS relaunched (or woke) the app for [identifier]'s events.
    func handle(_ identifier: String, completion: @escaping () -> Void) {
        handlers[identifier] = completion
        deadline?.cancel()
        deadline = Task { [weak self, timeout] in
            try? await Task.sleep(for: timeout)
            guard !Task.isCancelled else { return }
            self?.flush(force: true)
        }
    }

    /// The session delivered every event for [identifier].
    func didFinishEvents(_ identifier: String) {
        drained.insert(identifier)
        check()
    }

    /// Something changed that may settle the downloader.
    func check() { flush(force: false) }

    private func flush(force: Bool) {
        guard force || settled() else { return }
        let ready = force ? Set(handlers.keys) : drained.intersection(handlers.keys)
        for id in ready { handlers.removeValue(forKey: id)?() }
        drained.subtract(ready)
        if handlers.isEmpty {
            deadline?.cancel()
            deadline = nil
        }
    }
}

/// Whether the device has a usable network path (no traffic: path updates only).
@MainActor
@Observable
final class NetworkReachability {
    private(set) var connected = true
    @ObservationIgnored private let monitor = NWPathMonitor()

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let up = path.status == .satisfied
            Task { @MainActor in self?.connected = up }
        }
        monitor.start(queue: DispatchQueue(label: "ai.wckd.autobot.reachability"))
    }

    deinit { monitor.cancel() }
}

/**
 Opt-in local notifications when a model finishes or fails while the app is in the background.
 They carry the model's name and nothing else.
 */
enum DownloadAlerts {
    static let enabledKey = "ai.wckd.autobot.downloads.notify"

    static var enabled: Bool { UserDefaults.standard.bool(forKey: enabledKey) }

    /// Turns the alerts on (asking for permission) or off; returns whether they are on.
    static func setEnabled(_ on: Bool) async -> Bool {
        guard on else {
            UserDefaults.standard.set(false, forKey: enabledKey)
            return false
        }
        let granted = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
        UserDefaults.standard.set(granted, forKey: enabledKey)
        return granted
    }

    static func post(title: String, ok: Bool) {
        guard enabled else { return }
        let content = UNMutableNotificationContent()
        content.title = ok ? "Model ready" : "Download failed"
        content.body = title
        content.sound = .default
        let request = UNNotificationRequest(identifier: "ai.wckd.autobot.download.\(UUID().uuidString)", content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}

/**
 iOS cancels background transfers when the user swipes the app away in the app switcher. The
 next launch sees the cancellation with that reason, resumes the downloads (resume data, or
 `Range` from the part file) and explains once why they stopped.
 */
enum ForceQuitNotice {
    static let key = "ai.wckd.autobot.downloads.forceQuitNotice"

    /// Whether [error] is a background transfer cancelled because the user force-quit the app.
    static func isForceQuit(_ error: Error?) -> Bool {
        guard let error else { return false }
        let reason = (error as NSError).userInfo[NSURLErrorBackgroundTaskCancelledReasonKey] as? Int
        return reason == NSURLErrorCancelledReasonUserForceQuitApplication
    }

    /// Recorded once; shown until the user dismisses it, then never again.
    static func record(_ defaults: UserDefaults = .standard) {
        if defaults.string(forKey: key) == nil { defaults.set("pending", forKey: key) }
    }

    static func shouldShow(_ defaults: UserDefaults = .standard) -> Bool { defaults.string(forKey: key) == "pending" }

    static func dismiss(_ defaults: UserDefaults = .standard) { defaults.set("seen", forKey: key) }
}

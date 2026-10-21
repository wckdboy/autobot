import Foundation
import Observation
import UIKit
import WebKit

/**
 The state bridge between the dsh-ios supervisor (boot coordinator, `dsh-serve` harness, log and
 activity buffers) and the Harness tab. It owns the two rules the ObjC side has no opinion on:

 - **The network gate.** The guest's model calls go straight to DeepSeek's API from the emulator,
   outside Autobot's route resolver and kill switch. So the guest boots and the server runs only
   while the network mode allows traffic out, and the audit log says so.
 - **The key.** When Autobot has a DeepSeek provider with an API key, the guest gets it through the
   server's environment (`DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL`), so the harness answers without
   a second sign-in; otherwise the harness keeps its own key, as upstream.

 It also tracks the web view's page so a dead server or a lost web content process is reloaded
 rather than left blank.
 */
@MainActor
@Observable
final class HarnessModel {
    /// Where the harness's DeepSeek key comes from.
    enum KeySource: Equatable {
        /// No key from Autobot: the harness asks for one in its own settings and keeps it in the guest.
        case guest
        /// Autobot's provider [name] signs the guest's model calls.
        case autobot(String)
    }

    /// A device capability the harness just used (`Contacts`, `Calendar`, …), for the status row.
    struct Activity: Equatable {
        var name: String
        var outcome: String
        var ok: Bool
        var at: Date
    }

    // Boot (image import, kernel, data migration).
    private(set) var bootPhase: DSHBootPhase = .idle
    private(set) var bootMessage = "Preparing the Linux environment…"
    private(set) var bootProgress: Double = -1
    private(set) var bootError: Int32 = 0

    // The supervised server.
    private(set) var state: DSHHarnessState = .idle
    private(set) var port: UInt16 = 0
    private(set) var restartCount: UInt = 0
    private(set) var lastError: String?
    private(set) var logTail: [String] = []

    // The page in the web view.
    /// Bumped whenever the web view must load the server afresh: a new port, an explicit reload,
    /// a failed page, a web content process that died. In-page navigation never reloads.
    private(set) var loadGeneration = 0
    private(set) var pageLoaded = false
    private(set) var pageMessage: String?

    private(set) var keySource: KeySource = .guest
    private(set) var activity: Activity?
    private(set) var networkAllowed = true

    private weak var app: AppContainer?
    private var attached = false
    private var booted = false
    private var observers: [NSObjectProtocol] = []
    private var auditedPort: UInt16 = 0
    /// Page loads retried for the current generation (a server that answers the probe but not the page).
    private var pageRetries = 0
    private static let maxPageRetries = 3
    private static let deepSeekKeys = ["DEEPSEEK_API_KEY", "DEEPSEEK_BASE_URL"]

    // MARK: Derived

    var stateName: String { DSHHarnessStateName(state) }
    var baseURL: URL? { DSHHarness.shared().baseURL }
    var isReady: Bool { state == .ready }
    var isBusy: Bool { state == .starting || state == .restarting || bootPhase == .bootingKernel || bootPhase == .importingImage }
    var failed: Bool { state == .failed || bootPhase == .failed }
    var diagnosticReport: String { DSHHarness.shared().log.diagnosticReport }

    /// One sentence for the startup view.
    var statusMessage: String {
        switch bootPhase {
        case .idle:
            return booted ? bootMessage : "The harness is stopped."
        case .importingImage:
            return bootMessage
        case .bootingKernel:
            return "Booting the Linux guest…"
        case .migratingData:
            return "Moving your sessions to the new image…"
        case .ready:
            switch state {
            case .ready: return "Harness ready"
            case .failed: return lastError ?? "The harness could not start."
            case .stopped: return "The harness is stopped."
            case .restarting: return "The server stopped; starting it again…"
            case .starting, .idle: return "Starting DeepSeek Harness…"
            @unknown default: return stateName
            }
        case .failed:
            return "The Linux guest failed to boot (error \(bootError))."
        @unknown default:
            return bootMessage
        }
    }

    /// `127.0.0.1:3080 · on device` once serving, else the state.
    var statusDetail: String {
        if state == .ready, port > 0 { return "127.0.0.1:\(port) · on device" }
        return stateName
    }

    var keyLabel: String {
        switch keySource {
        case .guest: "key kept by the harness"
        case .autobot(let name): "key from \(name)"
        }
    }

    // MARK: Lifecycle

    /// Called once when the tab first shows. Observes the supervisor and boots the guest when the
    /// network mode allows it.
    func attach(app: AppContainer, networkAllowed: Bool) {
        guard !attached else { return }
        attached = true
        self.app = app
        self.networkAllowed = networkAllowed
        // A supervisor started by an earlier screen keeps running; the observers may already be in place.
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .DSHBootStateDidChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshBoot() }
        })
        observers.append(center.addObserver(forName: .DSHHarnessStateDidChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshHarness() }
        })
        observers.append(center.addObserver(forName: .DSHLogBufferDidChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshLog() }
        })
        observers.append(center.addObserver(forName: .DSHActivityLogDidChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshActivity() }
        })
        refreshBoot()
        refreshHarness()
        refreshLog()
        refreshActivity()
        applyEnvironment()
        if networkAllowed { boot() }
    }

    /// The network mode changed: Offline stops the server, any route out (re)starts it.
    func setNetworkAllowed(_ allowed: Bool) {
        guard allowed != networkAllowed else { return }
        networkAllowed = allowed
        if allowed {
            if !booted {
                boot()
            } else if bootPhase == .ready, state == .stopped || state == .failed || state == .idle {
                DSHHarness.shared().start()
            }
        } else {
            stopForOffline()
        }
    }

    /// Offline: the server stops (if it runs or is starting) and the audit log says so. The boot
    /// coordinator may still start it when the kernel comes up; [refreshHarness] catches that.
    private func stopForOffline() {
        guard state == .ready || state == .starting || state == .restarting else { return }
        DSHHarness.shared().stop()
        app?.auditLog.record(method: "GUEST", host: "api.deepseek.com", path: "/", tag: "Harness", route: "offline",
                             blocked: true, error: "Offline mode: the harness is stopped")
    }

    /// Reads Autobot's DeepSeek provider into the server's environment. A changed key restarts a
    /// running server so the next turn uses it.
    func applyEnvironment() {
        let harness = DSHHarness.shared()
        let current = harness.extraEnvironment
        var env = current
        var source = KeySource.guest
        if let app, let provider = app.providers.providers.first(where: { $0.kind == .deepseek && app.providers.hasApiKey($0) }),
           let secret = app.providers.apiKey(provider)?.reveal(), !secret.isEmpty {
            source = .autobot(provider.displayName)
            env["DEEPSEEK_API_KEY"] = secret
            let base = provider.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
            if !base.isEmpty, base != ProviderPresets.deepSeekBaseUrl {
                env["DEEPSEEK_BASE_URL"] = base
            } else {
                env.removeValue(forKey: "DEEPSEEK_BASE_URL")
            }
        } else {
            env.removeValue(forKey: "DEEPSEEK_API_KEY")
            env.removeValue(forKey: "DEEPSEEK_BASE_URL")
        }
        keySource = source
        // Only the two DeepSeek entries are ours; the rest (the host bridge) stays as it is. A
        // server that already has these values is left alone, so a fresh screen never restarts it.
        let changed = Self.deepSeekKeys.contains { env[$0] != current[$0] }
        guard changed else { return }
        harness.extraEnvironment = env
        if state == .ready || state == .starting {
            harness.restart()
        }
    }

    /// Loads the page again when the server is up, or starts the server when it is not.
    func reload() {
        if state == .ready {
            pageLoaded = false
            pageMessage = "Loading the interface…"
            pageRetries = 0
            loadGeneration += 1
        } else if bootPhase == .ready, networkAllowed {
            DSHHarness.shared().start()
        } else if !booted, networkAllowed {
            boot()
        }
    }

    func restart() {
        guard networkAllowed else { return }
        if !booted { boot(); return }
        DSHHarness.shared().restart()
    }

    func stop() {
        DSHHarness.shared().stop()
    }

    func start() {
        guard networkAllowed else { return }
        if !booted { boot(); return }
        DSHHarness.shared().start()
    }

    /// Back in the foreground: the page's socket may be gone (the client reconnects on its own),
    /// but a server that died in the background needs a restart and a reload.
    func verifyAlive() {
        guard state == .ready, pageLoaded else { return }
        DSHHarness.shared().verifyAlive { [weak self] alive in
            MainActor.assumeIsolated {
                guard let self, !alive else { return }
                self.pageLoaded = false
                self.pageMessage = "Waiting for the harness…"
            }
        }
    }

    // MARK: The page

    func pageDidLoad() {
        pageLoaded = true
        pageMessage = nil
        DSHHarness.shared().log.append("[autobot] web interface loaded (harness \(String(format: "%.1f", DSHHarness.shared().lastStartupDuration)) s)")
    }

    func pageFailed(_ error: Error) {
        let nsError = error as NSError
        // -999: a load we replaced. WebKit 102 (frame load interrupted by policy change): a navigation our own policy cancelled.
        if nsError.code == NSURLErrorCancelled || (nsError.domain == WKError.errorDomain && nsError.code == 102) { return }
        pageLoaded = false
        DSHHarness.shared().log.append("[autobot] page load failed: \(error.localizedDescription)")
        guard pageRetries < Self.maxPageRetries else {
            pageMessage = "The page did not load. Reload from the menu."
            return
        }
        pageRetries += 1
        pageMessage = "Waiting for the harness…"
        let generation = loadGeneration
        // The server was answering a moment ago; give it a beat and retry, and let the harness
        // restart it if it is really gone.
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1500))
            guard let self, !self.pageLoaded, generation == self.loadGeneration else { return }
            DSHHarness.shared().verifyAlive { [weak self] alive in
                MainActor.assumeIsolated {
                    guard let self, alive, self.state == .ready else { return }
                    self.loadGeneration += 1
                }
            }
        }
    }

    func webProcessTerminated() {
        DSHHarness.shared().log.append("[autobot] web content process terminated; reloading")
        pageLoaded = false
        if state == .ready { loadGeneration += 1 }
    }

    isolated deinit {
        for token in observers { NotificationCenter.default.removeObserver(token) }
    }

    // MARK: Private

    private func boot() {
        guard !booted else { return }
        booted = true
        DSHBootCoordinator.shared().start()
    }

    private func refreshBoot() {
        let boot = DSHBootCoordinator.shared()
        bootPhase = boot.phase
        bootMessage = boot.statusMessage
        bootProgress = boot.progress
        bootError = boot.bootError
    }

    private func refreshHarness() {
        let harness = DSHHarness.shared()
        let wasReady = state == .ready
        let oldPort = port
        state = harness.state
        port = harness.port
        restartCount = harness.restartCount
        lastError = harness.lastError
        if !networkAllowed, state == .starting || state == .ready {
            // The mode went Offline during the boot; the coordinator started the server anyway.
            stopForOffline()
            return
        }
        if state == .ready {
            if !wasReady || port != oldPort {
                pageLoaded = false
                pageMessage = "Loading the interface…"
                pageRetries = 0
                loadGeneration += 1
            }
            if port != auditedPort {
                auditedPort = port
                // The guest's model calls are not seen one by one; this entry says they happen.
                app?.auditLog.record(method: "GUEST", host: "api.deepseek.com", path: "/", tag: "Harness",
                                     route: "direct", blocked: false,
                                     error: "DeepSeek Harness on 127.0.0.1:\(port): model calls leave from the Linux guest")
            }
        } else if wasReady {
            pageLoaded = false
        }
    }

    private func refreshLog() {
        logTail = DSHHarness.shared().log.lines.suffix(24).map { $0 }
    }

    private func refreshActivity() {
        guard let latest = DSHActivityLog.shared().entries.first, latest.source == .capability else { return }
        activity = Activity(name: latest.name, outcome: DSHActivityOutcomeName(latest.outcome),
                            ok: latest.outcome == .OK || latest.outcome == .started, at: latest.date)
    }
}

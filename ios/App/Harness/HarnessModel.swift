import Foundation
import Observation
import UIKit

/// State bridge for the dsh-ios harness supervisor: boot phases, the server's
/// state/port, and the guest log tail, published as SwiftUI-observable values.
@MainActor
@Observable
final class HarnessModel {
    var bootPhase: DSHBootPhase = .idle
    var bootMessage = "Preparing the Linux environment…"
    var bootProgress: Double = -1
    var bootError: Int32 = 0

    var state: DSHHarnessState = .idle
    var port: UInt16 = 0
    var restartCount: UInt = 0
    var lastError: String?
    var logTail: [String] = []

    private var started = false
    private var observers: [NSObjectProtocol] = []

    var stateName: String {
        DSHHarnessStateName(state)
    }

    var baseURL: URL? {
        DSHHarness.shared().baseURL
    }

    var isReady: Bool {
        state == .ready
    }

    var failed: Bool {
        state == .failed || bootPhase == .failed
    }

    /// A human sentence for the startup overlay.
    var statusMessage: String {
        switch bootPhase {
        case .idle, .importingImage:
            return bootMessage
        case .bootingKernel:
            return "Booting the Linux guest…"
        case .migratingData:
            return "Moving your sessions to the new image…"
        case .ready:
            if state == .ready { return "Harness ready" }
            if state == .failed { return lastError ?? "The harness could not start." }
            return "Starting DeepSeek Harness…"
        case .failed:
            return "The Linux guest failed to boot (error \(bootError))."
        @unknown default:
            return bootMessage
        }
    }

    var statusDetail: String {
        if state == .ready, port > 0 { "Serving on 127.0.0.1:\(port)" }
        return DSHHarnessStateName(state)
    }

    func start() {
        guard !started else { return }
        started = true
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: NSNotification.Name.DSHBootStateDidChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshBoot() }
        })
        observers.append(center.addObserver(forName: NSNotification.Name.DSHHarnessStateDidChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshHarness() }
        })
        observers.append(center.addObserver(forName: NSNotification.Name.DSHLogBufferDidChange, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refreshLog() }
        })
        refreshAll()
        DSHBootCoordinator.shared().start()
    }

    func restart() {
        DSHHarness.shared().restart()
    }

    func stop() {
        DSHHarness.shared().stop()
    }

    var diagnosticReport: String {
        DSHHarness.shared().log.diagnosticReport
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
        state = harness.state
        port = harness.port
        restartCount = harness.restartCount
        lastError = harness.lastError
    }

    private func refreshLog() {
        logTail = DSHHarness.shared().log.lines.suffix(24).map { $0 }
    }

    private func refreshAll() {
        refreshBoot()
        refreshHarness()
        refreshLog()
    }
}

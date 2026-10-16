import Foundation
import Observation

/// Live progress of the running download.
struct DownloadProgress: Equatable, Sendable {
    let id: String
    let title: String
    let downloaded: Int64
    let total: Int64
    let bytesPerSecond: Int64

    var fraction: Double { total <= 0 ? 0 : min(max(Double(downloaded) / Double(total), 0), 1) }
}

/**
 Downloads queued models one at a time (port of `ModelDownloader.kt`).

 Per file: resolve the URL hop by hop (each hop passes the kill switch and is audited;
 credentials are only sent to their own hub), append to `*.part` with `Range` resume, verify
 SHA-256 (from the catalog/API, or the Hub's `X-Linked-ETag`), then atomically rename. Bodies run
 in a background URLSession so a download keeps going while the app is in the background; a
 pause keeps what that session received (resume data), and after a relaunch a transfer the
 earlier process left running is adopted rather than started again.

 When a file finishes while the app is suspended, iOS relaunches it in the background
 ([BackgroundEvents]); the downloader verifies and stores the file and starts the next one in the
 background session before the system's completion handler is called, so a multi-file model and
 the rest of the queue keep going without the app being opened. Swiping the app away cancels the
 transfers (an iOS rule): the next launch resumes them and explains it once ([ForceQuitNotice]).
 */
@MainActor
@Observable
final class ModelDownloader {
    /// Space kept free on top of the model itself.
    nonisolated static let reserveBytes: Int64 = 512 * 1024 * 1024

    private(set) var progress: DownloadProgress?
    private(set) var busy = false
    /// The launch's recovery (reconnecting to the background session) has not finished yet.
    private(set) var preparing = false
    /// Show the one-time "swiping Autobot away stops downloads" explanation.
    private(set) var forceQuitNotice = ForceQuitNotice.shouldShow()
    @ObservationIgnored private var recovered = false
    @ObservationIgnored private let library: ModelLibrary
    @ObservationIgnored private let clients: HttpClientFactory
    @ObservationIgnored private let accounts: ModelAccounts
    @ObservationIgnored private let background = BackgroundTransfer()
    @ObservationIgnored private var worker: Task<Void, Never>?
    @ObservationIgnored private var currentId: String?
    @ObservationIgnored private var lastReport = Date.distantPast
    @ObservationIgnored private var lastBytes: Int64 = -1
    @ObservationIgnored private var speed: Int64 = 0
    /// Tests stream bodies through the policy client instead of the background session.
    @ObservationIgnored var useBackgroundSession = true
    /// Free bytes on the models volume (tests substitute a full disk).
    @ObservationIgnored var freeSpace: () -> Int64 = { DeviceProfile.current().freeStorageBytes }

    init(library: ModelLibrary, clients: HttpClientFactory, accounts: ModelAccounts) {
        self.library = library
        self.clients = clients
        self.accounts = accounts
    }

    /// Queues [plan] and starts the worker.
    func install(_ plan: ModelPlan) {
        library.add(plan)
        kick()
    }

    func resume(_ id: String) {
        guard let model = library.get(id) else { return }
        library.progress(id, downloaded: model.downloadedBytes, status: .queued)
        kick()
    }

    /// Whether the background session has a transfer the queue is waiting for.
    var hasActiveTransfer: Bool { useBackgroundSession && background.hasActiveTransfer }

    /// iOS relaunched the app for [identifier]'s background events: recreate that session.
    func attachBackgroundSession(_ identifier: String) {
        guard useBackgroundSession else { return }
        background.attach(identifier: identifier)
    }

    /// The kill switch changed: its pauses continue once a network mode is allowed again.
    func networkChanged(offline: Bool) {
        guard !offline else { return }
        let blocked = DownloadQueue.blockedByKillSwitch(library.models)
        guard !blocked.isEmpty else { return }
        for m in blocked { library.progress(m.id, downloaded: m.downloadedBytes, status: .queued) }
        kick()
    }

    func dismissForceQuitNotice() {
        ForceQuitNotice.dismiss()
        forceQuitNotice = false
    }

    func pause(_ id: String) {
        if currentId == id { worker?.cancel() }
        // In-flight bytes live in [progress] only; the registry gets them now.
        let live = progress?.id == id ? progress?.downloaded : nil
        if let model = library.get(id) { library.progress(id, downloaded: live ?? model.downloadedBytes, status: .paused) }
    }

    func remove(_ id: String) {
        if currentId == id { worker?.cancel() }
        library.remove(id)
        TransferResume.discard(under: ModelLibrary.dir(of: id))
        kick()
    }

    /**
     Once per launch, after the registry is loaded: nothing runs yet, so interrupted downloads
     queue again and resume (a transfer the earlier process left running is adopted by the fetch
     of the same part file; one that finished is taken from disk; one iOS cancelled because the app
     was swiped away continues from its resume data). Transfers no queued model adopts are stopped,
     keeping their resume data, so the same file never downloads twice in parallel.
     */
    func recoverAfterRestart() {
        guard !recovered else { return }
        recovered = true
        for m in DownloadQueue.recover(library.models) {
            library.progress(m.id, downloaded: Self.keptBytes(m.id), status: .queued)
        }
        guard useBackgroundSession else {
            if library.models.contains(where: { $0.status == .queued }) { kick() }
            return
        }
        preparing = true
        Task { [weak self] in
            guard let self else { return }
            _ = await background.reconnect()
            // Recreating the sessions delivers what happened while the app was gone (finished
            // bodies, a force quit's cancellations with their resume data): let it arrive first.
            try? await Task.sleep(for: .milliseconds(400))
            forceQuitNotice = ForceQuitNotice.shouldShow()
            if library.models.contains(where: { $0.status == .queued }) { kick() } else { background.dropOrphans() }
            preparing = false
            BackgroundEvents.shared.check()
        }
    }

    /// Stops every transfer (panic wipe).
    func cancelAll() {
        worker?.cancel()
        background.cancelAll()
    }

    /// Bytes of [id] kept for a resume: part and finished files, and what paused transfers received.
    nonisolated static func keptBytes(_ id: String) -> Int64 {
        ModelLibrary.bytesOnDisk(id) + TransferResume.bytes(under: ModelLibrary.dir(of: id))
    }

    private func kick() {
        guard worker == nil else { return }
        // Busy from now (not from when the task starts), so a background relaunch is not settled early.
        busy = true
        worker = Task { [weak self] in
            await self?.drain()
        }
    }

    private func drain() async {
        busy = true
        defer {
            busy = false
            progress = nil
            currentId = nil
            worker = nil
            BackgroundEvents.shared.check()
        }
        while !Task.isCancelled, let next = library.models.filter({ $0.status == .queued }).min(by: { $0.addedAt < $1.addedAt }) {
            currentId = next.id
            await runOne(next)
            currentId = nil
        }
        // A pause cancels the worker; anything still queued starts a fresh one.
        if library.models.contains(where: { $0.status == .queued }) {
            Task { [weak self] in self?.kick() }
        } else {
            // Transfers from an earlier process that no queued model adopted.
            background.dropOrphans()
        }
    }

    private func runOne(_ model: InstalledModel) async {
        let id = model.id
        lastBytes = -1
        speed = 0
        do {
            try requireSpace(model)
            library.progress(id, downloaded: model.downloadedBytes, status: .downloading)
            var manifest = model.manifest
            var done: Int64 = 0
            for (index, file) in manifest.files.enumerated() {
                let before = done
                let planned = manifest.files.reduce(0) { $0 + $1.sizeBytes }
                let result = try await downloadFile(model, file) { [weak self] written, fileTotal in
                    Task { @MainActor in self?.report(model, before + written, max(planned, before + fileTotal)) }
                }
                // Adopt the authoritative size/hash learned from the server, kept at once so a
                // later file failing does not make a resume fetch this one again.
                if result != manifest.files[index] {
                    manifest.files[index] = result
                    library.update(id) { $0.manifest.files[index] = result }
                }
                done += result.sizeBytes
            }
            let total = manifest.files.reduce(0) { $0 + $1.sizeBytes }
            library.update(id) {
                $0.manifest = manifest
                $0.totalBytes = total
            }
            library.progress(id, downloaded: total, status: .ready)
            if AppActivity.shared.inBackground { DownloadAlerts.post(title: model.title, ok: true) }
        } catch is CancellationError {
            settle(id, status: .paused)
        } catch let e as DownloadError {
            settle(id, status: e.retryable ? .paused : .failed, error: e.message)
            if !e.retryable, AppActivity.shared.inBackground { DownloadAlerts.post(title: model.title, ok: false) }
        } catch let e as NetworkBlockedError {
            settle(id, status: .paused, error: clients.policy.mode == .offline ? DownloadQueue.killSwitchError : "Blocked by the network policy (\(e.message))")
        } catch {
            if (error as? URLError)?.code == .cancelled || Task.isCancelled {
                settle(id, status: .paused)
            } else if Self.isOutOfSpace(error) {
                settle(id, status: .paused, error: "\(DownloadQueue.lowStoragePrefix): the disk filled up while downloading. Free some space, then resume.")
            } else {
                settle(id, status: .paused, error: error.localizedDescription)
            }
        }
    }

    private func settle(_ id: String, status: ModelStatus, error: String? = nil) {
        guard let current = library.get(id) else { return }
        // A user pause wins over a failure surfacing from the cancelled transfer.
        let final = current.status == .paused ? ModelStatus.paused : status
        library.progress(id, downloaded: Self.keptBytes(id), status: final, error: final == .paused && current.status == .paused ? nil : error)
    }

    private func report(_ model: InstalledModel, _ downloaded: Int64, _ total: Int64) {
        guard currentId == model.id else { return }
        let now = Date()
        let dt = now.timeIntervalSince(lastReport)
        if dt < 0.25 && downloaded < total { return }
        // The first sample after a (re)start only sets the baseline: resumed bytes are not speed.
        if lastBytes >= 0, dt > 0, dt < 10, downloaded >= lastBytes {
            speed = DownloadQueue.smooth(speed, Int64(Double(downloaded - lastBytes) / dt))
        }
        lastReport = now
        lastBytes = downloaded
        // In-flight bytes stay here (≤ 4 updates a second, read by the downloads panel); the
        // registry is written on pause, failure and completion, and a relaunch measures the bytes
        // on disk, so nothing is sealed, persisted or fanned out to the app per tick.
        progress = DownloadProgress(id: model.id, title: model.title, downloaded: downloaded, total: total, bytesPerSecond: speed)
    }

    #if DEBUG
    /// One once-a-second progress report of [id] (perf benchmarks).
    func benchmarkTick(_ id: String, downloaded: Int64, total: Int64) {
        progress = DownloadProgress(id: id, title: "", downloaded: downloaded, total: total, bytesPerSecond: 1)
    }
    #endif

    /// A write that failed because the volume is full.
    nonisolated static func isOutOfSpace(_ error: Error) -> Bool {
        let e = error as NSError
        if e.domain == NSCocoaErrorDomain && e.code == NSFileWriteOutOfSpaceError { return true }
        if e.domain == NSPOSIXErrorDomain && e.code == Int(ENOSPC) { return true }
        return (e.userInfo[NSUnderlyingErrorKey] as? Error).map(isOutOfSpace) ?? false
    }

    private func requireSpace(_ model: InstalledModel) throws {
        let free = freeSpace()
        let needed = max(model.totalBytes - Self.keptBytes(model.id), 0) + Self.reserveBytes
        if free > 0 && free < needed {
            throw DownloadError(message: "\(DownloadQueue.lowStoragePrefix): needs \(formatBytes(needed)), \(formatBytes(free)) free")
        }
    }

    /// Downloads one file (resuming) and returns it with its verified size and SHA-256.
    private func downloadFile(_ model: InstalledModel, _ file: ModelFile, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> ModelFile {
        let target = ModelLibrary.file(of: model.id, file)
        let existing = FileFetcher.size(target)
        if existing > 0, file.sha256 != nil, file.sizeBytes == 0 || existing == file.sizeBytes {
            onProgress(existing, existing)
            var done = file
            done.sizeBytes = existing
            return done
        }
        let credential: Secret? = switch file.auth {
        case .huggingFace: accounts.huggingFaceToken()
        case .civitai: accounts.civitaiKey()
        case nil: nil
        }
        let fetcher = FileFetcher(clients: clients, bulk: useBackgroundSession ? background : nil)
        return try await fetcher.fetch(file, to: target, credential: credential, onProgress: onProgress)
    }
}

extension InstalledModel {
    /// "Paused at 1.2 GB of 4 GB" while a paused download keeps bytes; nil otherwise.
    var pausedLabel: String? {
        guard status == .paused, downloadedBytes > 0 else { return nil }
        return "Paused at \(formatBytes(downloadedBytes))" + (totalBytes > 0 ? " of \(formatBytes(totalBytes))" : "")
    }
}

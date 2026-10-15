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
 */
@MainActor
@Observable
final class ModelDownloader {
    /// Space kept free on top of the model itself.
    nonisolated static let reserveBytes: Int64 = 512 * 1024 * 1024

    private(set) var progress: DownloadProgress?
    private(set) var busy = false
    @ObservationIgnored private let library: ModelLibrary
    @ObservationIgnored private let clients: HttpClientFactory
    @ObservationIgnored private let accounts: ModelAccounts
    @ObservationIgnored private let background = BackgroundTransfer()
    @ObservationIgnored private var worker: Task<Void, Never>?
    @ObservationIgnored private var currentId: String?
    @ObservationIgnored private var lastReport = Date.distantPast
    @ObservationIgnored private var lastBytes: Int64 = 0
    @ObservationIgnored private var lastStore = Date.distantPast
    /// Tests stream bodies through the policy client instead of the background session.
    @ObservationIgnored var useBackgroundSession = true

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

    func pause(_ id: String) {
        if currentId == id { worker?.cancel() }
        if let model = library.get(id) { library.progress(id, downloaded: model.downloadedBytes, status: .paused) }
    }

    func remove(_ id: String) {
        if currentId == id { worker?.cancel() }
        library.remove(id)
        TransferResume.discard(under: ModelLibrary.dir(of: id))
        kick()
    }

    /**
     After a relaunch no worker is running: interrupted downloads become paused. A background
     transfer the earlier process left running for one of them is adopted (the model is queued
     again and its fetch picks the transfer up); any other is stopped, keeping its resume data,
     so the same file never downloads twice in parallel.
     */
    func recoverAfterRestart() {
        let interrupted = library.models.filter { $0.status == .downloading }.map(\.id)
        for id in interrupted {
            library.progress(id, downloaded: Self.keptBytes(id), status: .paused)
        }
        guard useBackgroundSession else { return }
        Task { [weak self] in
            guard let self else { return }
            let running = await background.reconnect()
            for id in interrupted where library.get(id)?.status == .paused {
                let dir = TransferResume.tag(ModelLibrary.dir(of: id)) + "/"
                if running.contains(where: { $0.hasPrefix(dir) }) { library.progress(id, downloaded: Self.keptBytes(id), status: .queued) }
            }
            if library.models.contains(where: { $0.status == .queued }) { kick() } else { background.dropOrphans() }
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
                // Adopt the authoritative size/hash learned from the server.
                manifest.files[index] = result
                done += result.sizeBytes
            }
            let total = manifest.files.reduce(0) { $0 + $1.sizeBytes }
            library.update(id) {
                $0.manifest = manifest
                $0.totalBytes = total
            }
            library.progress(id, downloaded: total, status: .ready)
        } catch is CancellationError {
            settle(id, status: .paused)
        } catch let e as DownloadError {
            settle(id, status: e.retryable ? .paused : .failed, error: e.message)
        } catch let e as NetworkBlockedError {
            settle(id, status: .paused, error: "Network is off (\(e.message)). Allow a network mode in Privacy Center, then resume.")
        } catch {
            if (error as? URLError)?.code == .cancelled || Task.isCancelled {
                settle(id, status: .paused)
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
        let speed = dt > 0 && dt < 10 && downloaded >= lastBytes ? Int64(Double(downloaded - lastBytes) / dt) : 0
        lastReport = now
        lastBytes = downloaded
        progress = DownloadProgress(id: model.id, title: model.title, downloaded: downloaded, total: total, bytesPerSecond: speed)
        if now.timeIntervalSince(lastStore) > 1 {
            lastStore = now
            library.progress(model.id, downloaded: downloaded, status: .downloading)
        }
    }

    private func requireSpace(_ model: InstalledModel) throws {
        let free = DeviceProfile.current().freeStorageBytes
        let needed = max(model.totalBytes - Self.keptBytes(model.id), 0) + Self.reserveBytes
        if free > 0 && free < needed {
            throw DownloadError(message: "Not enough storage: needs \(formatBytes(needed)), \(formatBytes(free)) free")
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

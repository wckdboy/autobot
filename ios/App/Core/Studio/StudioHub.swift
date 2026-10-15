import AutobotShared
import Foundation
import Observation
import UIKit

/// A ComfyUI server the user added (Studio › Workflows). The optional auth header's value is a Keychain secret.
struct ComfyServer: Codable, Identifiable, Equatable, Hashable, Sendable {
    var id: String
    var name: String
    var baseUrl: String
    var routing: String = RouteOverride.inherit.rawValue
    /// e.g. `Authorization` (a reverse proxy in front of ComfyUI); nil = none.
    var headerName: String? = nil
    var headerRef: String? = nil

    var isLoopback: Bool { Loopback.isLoopbackURL(baseUrl) }
    var routeOverride: RouteOverride { RouteOverride(rawValue: routing) ?? .inherit }
    var route: Route { isLoopback ? .localCpu : isPrivateHost(baseUrl) ? .pcLan : .cloudApi }
}

/// One X/Y grid run, kept while the Studio is open so its result grid can be shown.
struct GridRun: Identifiable, Equatable {
    let id: String
    let title: String
    let columns: [String]
    let rows: [String]
    let xLabel: String
    let yLabel: String?
    /// Job ids row by row.
    let jobIds: [[String]]
    let truncated: Bool
}

/**
 The Creative Studio on iOS: the shared Studio core ([StudioBridge]: queue, ComfyUI, pipelines,
 `run_workflow`) wired to the encrypted store and files, the app's engines registered as they
 become available, and the state the Studio screens render (records, jobs, previews, grids).
 Jobs live in the app scope, so they keep running while the Studio is closed.
 */
@MainActor
@Observable
final class StudioHub {
    static let agentProjectName = "Agent"
    private static let comfyKey = "studio_comfy_backends"
    private static let projectKey = "studio_project"

    let library = StudioLibrary()
    let files: StudioFilesHost
    /// The queue, oldest first.
    private(set) var jobs: [StudioJob] = []
    /// Each running job's latest preview frame.
    private(set) var previews: [String: Data] = [:]
    private(set) var comfyServers: [ComfyServer]
    private(set) var projectId: String?
    private(set) var grids: [GridRun] = []
    /// Engine kinds available now, by kind → labels ("This iPhone", a backend name).
    private(set) var engineLabels: [String: [String]] = [:]

    @ObservationIgnored let host: StudioStoreHost
    @ObservationIgnored let bridge: StudioBridge
    @ObservationIgnored private let preferences: Preferences
    @ObservationIgnored private let secrets: SecretStore
    @ObservationIgnored private var jobsHandle: (any AutobotShared.Cancellable)?
    @ObservationIgnored private var registered: [String: StudioHostEngine] = [:]
    @ObservationIgnored private var restored = false
    @ObservationIgnored private var lastPreview: [String: KotlinByteArray] = [:]
    @ObservationIgnored private var followUps: [String: (StudioAsset) -> Void] = [:]
    /// Notified when `run_workflow` should come or go (saved workflows exist or not).
    @ObservationIgnored var onWorkflowsChanged: ((Bool) -> Void)?

    init(store: EncryptedStore, keys: KeyManager, clients: HttpClientFactory, preferences: Preferences, secrets: SecretStore) {
        self.preferences = preferences
        self.secrets = secrets
        files = StudioFilesHost(keys: keys)
        comfyServers = preferences.get(Self.comfyKey, as: [ComfyServer].self) ?? []
        projectId = preferences.get(Self.projectKey, as: String.self)
        let library = library
        host = StudioStoreHost(store: store) { kind, id, json in
            Task { @MainActor in library.apply(kind: kind, id: id, json: json) }
        }
        let transport = URLSessionTransport(clients: clients)
        bridge = StudioBridge(store: host, files: files, http: transport, binary: transport, sockets: transport)
        applyComfy()
    }

    // MARK: Lifecycle

    /// Reads the records (off main), makes sure there is a project, restores the queue.
    func load() async {
        let host = host
        let rows = await Task.detached(priority: .userInitiated) { StudioLibrary.read(host) }.value
        library.load(rows)
        if library.project(projectId) == nil { projectId = library.projects.first(where: { $0.name != Self.agentProjectName })?.id ?? library.projects.first?.id }
        if projectId == nil { _ = createProject("Studio") }
        onWorkflowsChanged?(!library.workflows.isEmpty)
        guard !restored else { return }
        restored = true
        jobsHandle = bridge.observeJobs { [weak self] list in
            Task { @MainActor in self?.jobsChanged(list) }
        }
        bridge.restore {}
    }

    func close() {
        jobsHandle?.cancel()
        bridge.close()
    }

    private func jobsChanged(_ list: [StudioJob]) {
        let before = Dictionary(jobs.map { ($0.id, $0.state) }, uniquingKeysWith: { a, _ in a })
        jobs = list
        var next: [String: Data] = [:]
        var raw: [String: KotlinByteArray] = [:]
        for job in list where job.state == .running {
            guard let p = job.preview else { continue }
            raw[job.id] = p.bytes
            next[job.id] = lastPreview[job.id] === p.bytes ? previews[job.id] : BackupBytes.shared.toData(bytes: p.bytes) as Data
        }
        lastPreview = raw
        if next != previews { previews = next }
        for job in list where job.state == .done && before[job.id] != nil && before[job.id] != .done {
            guard let follow = followUps.removeValue(forKey: job.id) else { continue }
            for id in job.outputs { if let a = library.asset(id) { follow(a) } }
        }
    }

    // MARK: Engines

    /**
     Registers what can run now: on-device diffusion / upscale / audio / voice when their models are
     installed, every A1111 / Local SSE backend, every ComfyUI server. Called when models or
     backends change.
     */
    func syncEngines(app: AppContainer) {
        var wanted: [String: () -> StudioHostEngine] = [:]
        let generator = app.generator, files = files
        for backend in app.backends.backends {
            let key = "diffusion|\(backend.kind == .onDevice ? "" : backend.id)|\(backend.baseUrl)|\(backend.routing)|\(backend.name)"
            wanted[key] = { DiffusionStudioEngine(backend: backend, generator: generator, files: files) }
        }
        let models = app.library.models.filter { $0.isReady && $0.engine == .coreML }
        if let up = models.first(where: { $0.kind == .upscaler }) {
            wanted["upscale|\(up.id)"] = { UpscaleStudioEngine(folder: ModelLibrary.dir(of: up.id), generator: generator, files: files) }
        }
        if let audio = models.first(where: { $0.kind == .audio }) {
            wanted["audio|\(audio.id)"] = { AudioStudioEngine(folder: ModelLibrary.dir(of: audio.id), generator: generator) }
        }
        if let voice = models.first(where: { $0.kind == .voice }) {
            wanted["speech|\(voice.id)"] = { SpeechStudioEngine(folder: ModelLibrary.dir(of: voice.id)) }
        }
        for (key, engine) in registered where wanted[key] == nil {
            bridge.unregister(kind: engine.capabilities.kind, backendId: engine.capabilities.backendId)
            registered[key] = nil
        }
        for (key, make) in wanted where registered[key] == nil {
            let engine = make()
            // A changed backend replaces its old registration (same kind and backend id).
            if let old = registered.first(where: { $0.value.capabilities.kind == engine.capabilities.kind && $0.value.capabilities.backendId == engine.capabilities.backendId }) {
                registered[old.key] = nil
            }
            bridge.register(engine: engine)
            registered[key] = engine
        }
        var labels: [String: [String]] = [:]
        for e in registered.values { labels[e.capabilities.kind, default: []].append(e.capabilities.label) }
        for s in comfyServers { labels[EngineKinds.shared.COMFYUI, default: []].append(s.name) }
        engineLabels = labels.mapValues { $0.sorted() }
    }

    func has(_ kind: String) -> Bool { !(engineLabels[kind] ?? []).isEmpty }

    // MARK: ComfyUI servers

    func comfyServer(_ id: String?) -> ComfyServer? { id.flatMap { id in comfyServers.first { $0.id == id } } }

    func saveComfy(_ server: ComfyServer, headerValue: String?) {
        var s = server
        if let value = headerValue {
            if value.isEmpty {
                s.headerRef.map(secrets.delete)
                s.headerRef = nil
            } else {
                let ref = s.headerRef ?? secrets.newRef()
                try? secrets.put(ref, Secret(value))
                s.headerRef = ref
            }
        }
        comfyServers = comfyServers.filter { $0.id != s.id } + [s]
        preferences.set(Self.comfyKey, comfyServers)
        applyComfy()
    }

    func deleteComfy(_ id: String) {
        comfyServer(id)?.headerRef.map(secrets.delete)
        comfyServers.removeAll { $0.id == id }
        preferences.set(Self.comfyKey, comfyServers)
        applyComfy()
    }

    func comfyBackend(_ s: ComfyServer) -> ComfyBackend {
        let header = s.headerName.flatMap { name -> KotlinPair<NSString, NSString>? in
            guard !name.isEmpty, let value = s.headerRef.flatMap(secrets.get)?.reveal() else { return nil }
            return KotlinPair(first: name as NSString, second: value as NSString)
        }
        return ComfyBackend(id: s.id, label: s.name, baseUrl: s.baseUrl, route: s.routing, authHeader: header)
    }

    private func applyComfy() {
        bridge.setComfyBackends(backends: comfyServers.map(comfyBackend))
        var labels = engineLabels
        labels[EngineKinds.shared.COMFYUI] = comfyServers.map(\.name).sorted()
        if labels[EngineKinds.shared.COMFYUI]?.isEmpty == true { labels[EngineKinds.shared.COMFYUI] = nil }
        engineLabels = labels
    }

    func testComfy(_ server: ComfyServer) async -> (ok: Bool, text: String) {
        await withCheckedContinuation { c in
            bridge.testComfy(backend: comfyBackend(server)) { ok, text in c.resume(returning: (ok.boolValue, text)) }
        }
    }

    // MARK: Projects

    var project: StudioProject? { library.project(projectId) }

    func selectProject(_ id: String) {
        projectId = id
        preferences.set(Self.projectKey, id)
    }

    @discardableResult
    func createProject(_ name: String) -> StudioProject {
        let now = nowMillis()
        let p = StudioProject(id: StudioIds.shared.doNew(prefix: "p", random: KotlinRandom.companion), name: name.trimmingCharacters(in: .whitespaces).isEmpty ? "Untitled" : name,
                              coverAssetId: nil, createdAt: now, updatedAt: now)
        put(p)
        selectProject(p.id)
        return p
    }

    func renameProject(_ id: String, _ name: String) {
        guard let p = library.project(id) else { return }
        put(p.doCopy(id: p.id, name: name, coverAssetId: p.coverAssetId, createdAt: p.createdAt, updatedAt: nowMillis()))
    }

    /// Deletes the project, its assets and their files.
    func deleteProject(_ id: String) {
        for a in library.assets where a.projectId == id { deleteAsset(a.id) }
        host.remove(kind: StudioStoreBridgeCompanion.shared.PROJECTS, id: id)
        library.apply(kind: StudioStoreBridgeCompanion.shared.PROJECTS, id: id, json: nil)
        if projectId == id {
            projectId = library.projects.first?.id
            if projectId == nil { _ = createProject("Studio") }
        }
    }

    private func put(_ p: StudioProject) {
        let json = StudioCodec.shared.encodeProject(value: p)
        host.put(kind: StudioStoreBridgeCompanion.shared.PROJECTS, id: p.id, json: json)
        library.apply(kind: StudioStoreBridgeCompanion.shared.PROJECTS, id: p.id, json: json)
    }

    // MARK: Assets

    func setFavorite(_ id: String, _ favorite: Bool) {
        guard let a = library.asset(id) else { return }
        putAsset(a.doCopy(id: a.id, projectId: a.projectId, type: a.type, recipe: a.recipe, createdAt: a.createdAt, mimeType: a.mimeType, sizeBytes: a.sizeBytes,
                          durationMs: a.durationMs, width: a.width, height: a.height, favorite: favorite, parentId: a.parentId, label: a.label))
    }

    func putAsset(_ a: StudioAsset) {
        let json = StudioCodec.shared.encodeAsset(value: a)
        host.put(kind: StudioStoreBridgeCompanion.shared.ASSETS, id: a.id, json: json)
        library.apply(kind: StudioStoreBridgeCompanion.shared.ASSETS, id: a.id, json: json)
    }

    func deleteAsset(_ id: String) {
        host.remove(kind: StudioStoreBridgeCompanion.shared.ASSETS, id: id)
        library.apply(kind: StudioStoreBridgeCompanion.shared.ASSETS, id: id, json: nil)
        let files = files
        Task.detached { files.delete(id) }
    }

    /// Adds bytes made outside the queue (a trim, a picked photo) as an asset of the current project.
    func addAsset(_ data: Data, type: AssetType, mime: String, recipe: Recipe, width: Int = 0, height: Int = 0, durationMs: Int64 = 0, parent: String? = nil, label: String? = nil) async throws -> StudioAsset {
        guard let projectId else { throw StudioFailure(message: "No project") }
        let id = StudioIds.shared.doNew(prefix: "a", random: KotlinRandom.companion)
        let files = files
        try await Task.detached { try files.save(id, data, mimeType: mime) }.value
        let a = StudioAsset(id: id, projectId: projectId, type: type, recipe: recipe, createdAt: nowMillis(), mimeType: mime, sizeBytes: Int64(data.count),
                            durationMs: durationMs, width: Int32(width), height: Int32(height), favorite: false, parentId: parent, label: label)
        putAsset(a)
        return a
    }

    func bytes(_ id: String) async -> Data? {
        let files = files
        return await Task.detached { files.bytes(id) }.value
    }

    /// A small image of an image or video asset (decoded, cached by the caller).
    func thumbnail(_ asset: StudioAsset) async -> UIImage? {
        let files = files, id = asset.id, isVideo = asset.type == .video
        let data: Data? = await Task.detached {
            if let t = files.thumbnail(id) { return t }
            guard isVideo, let url = files.openPlain(id, ext: StudioCodec.shared.extension(mimeType: "video/mp4")) else { return nil }
            defer { try? FileManager.default.removeItem(at: url) }
            guard let poster = StudioMedia.poster(url), let jpeg = ImageCodec.jpeg(poster, quality: 0.8) else { return nil }
            files.putThumbnail(id, jpeg)
            return jpeg
        }.value
        guard let data else { return nil }
        return await decodeImage(data, maxSide: 600)
    }

    /// An audio asset's waveform (computed once, cached encrypted).
    func waveform(_ asset: StudioAsset) async -> [Float] {
        let files = files, id = asset.id, ext = StudioCodec.shared.extension(mimeType: asset.mimeType)
        return await Task.detached {
            if let w = files.waveform(id) { return w }
            guard let url = files.openPlain(id, ext: ext) else { return [] }
            defer { try? FileManager.default.removeItem(at: url) }
            let peaks = StudioAudio.peaks(file: url) ?? []
            if !peaks.isEmpty { files.putWaveform(id, peaks) }
            return peaks
        }.value
    }

    // MARK: Queue

    var activeJobs: [StudioJob] { jobs.filter { !$0.state.isFinished } }
    var hasRunningOnDevice: Bool { jobs.contains { $0.state == .running && $0.recipe.backendId == nil && $0.recipe.engine != EngineKinds.shared.COMFYUI } }

    @discardableResult
    func submit(_ recipe: Recipe, label: String? = nil, parent: String? = nil, then: ((StudioAsset) -> Void)? = nil) -> StudioJob? {
        guard let projectId else { return nil }
        let job = bridge.queue.submit(projectId: projectId, recipe: recipe, label: label, parentAssetId: parent)
        if let then { followUps[job.id] = then }
        return job
    }

    /// A batch: the prompt's wildcards expanded × [count], seeds by [mode].
    @discardableResult
    func submitBatch(_ recipe: Recipe, count: Int, mode: SeedMode, parent: String? = nil, then: ((StudioAsset) -> Void)? = nil) -> [StudioJob] {
        guard let projectId else { return [] }
        let planned = BatchPlanner.shared.plan(base: recipe, batchCount: Int32(count), seedMode: mode, cap: 64, random: KotlinRandom.companion, sampled: false)
        let jobs = bridge.queue.submitAll(projectId: projectId, planned: planned, parentAssetId: parent)
        if let then { for j in jobs { followUps[j.id] = then } }
        return jobs
    }

    /// An X/Y grid: one job per cell; the run is kept for the result grid.
    @discardableResult
    func submitGrid(_ recipe: Recipe, x: GridAxis, y: GridAxis?, title: String) throws -> GridRun {
        guard let projectId else { throw StudioFailure(message: "No project") }
        let plan = GridPlanner.shared.plan(base: recipe, x: x, y: y, cap: 64)
        let jobs = bridge.queue.submitAll(projectId: projectId, planned: plan.jobs, parentAssetId: nil)
        let rowCount = max(1, plan.rows.count)
        var grid = Array(repeating: [String](), count: rowCount)
        for (planned, job) in zip(plan.jobs, jobs) { grid[Int(planned.row)].append(job.id) }
        let run = GridRun(id: UUID().uuidString, title: title, columns: plan.columns, rows: plan.rows, xLabel: x.label, yLabel: y?.label,
                          jobIds: grid, truncated: plan.truncated)
        grids.insert(run, at: 0)
        return run
    }

    func job(_ id: String) -> StudioJob? { jobs.first { $0.id == id } }

    func cancel(_ id: String) { _ = bridge.queue.cancel(id: id) }
    func retry(_ id: String) { _ = bridge.queue.retry(id: id) }
    func clearFinished() { bridge.queue.clearFinished() }

    func runPipeline(_ pipeline: Pipeline, vars: [String: String], input: [String]) async -> (ok: Bool, message: String) {
        guard let projectId else { return (false, "No project") }
        return await withCheckedContinuation { c in
            _ = bridge.runPipeline(pipeline: pipeline, projectId: projectId, vars: vars, input: input, onStep: { _ in }) { result, error in
                if let error { c.resume(returning: (false, error)) } else if let result {
                    c.resume(returning: (result.ok, result.error ?? "\(result.assetIds.count) asset\(result.assetIds.count == 1 ? "" : "s") made"))
                } else {
                    c.resume(returning: (false, "Failed"))
                }
            }
        }
    }

    // MARK: Workflows & prompt library

    func saveWorkflow(_ w: StudioWorkflow) {
        let json = StudioCodec.shared.encodeWorkflow(value: w)
        host.put(kind: StudioStoreBridgeCompanion.shared.WORKFLOWS, id: w.id, json: json)
        library.apply(kind: StudioStoreBridgeCompanion.shared.WORKFLOWS, id: w.id, json: json)
        onWorkflowsChanged?(true)
    }

    func deleteWorkflow(_ id: String) {
        host.remove(kind: StudioStoreBridgeCompanion.shared.WORKFLOWS, id: id)
        library.apply(kind: StudioStoreBridgeCompanion.shared.WORKFLOWS, id: id, json: nil)
        onWorkflowsChanged?(!library.workflows.isEmpty)
    }

    struct Imported {
        var workflow: StudioWorkflow?
        var validation: String?
        var checked = false
        var error: String?
    }

    func importWorkflow(name: String, json: String, backendId: String?) async -> Imported {
        await withCheckedContinuation { c in
            bridge.importWorkflow(name: name, json: json, backendId: backendId) { w, validation, checked, error in
                c.resume(returning: Imported(workflow: w, validation: validation, checked: checked.boolValue, error: error))
            }
        }
    }

    /// The validation message (nil = ready) or the error reaching the server.
    func validate(_ w: StudioWorkflow, on backendId: String) async -> (message: String?, error: String?) {
        await withCheckedContinuation { c in bridge.validate(workflow: w, backendId: backendId) { m, e in c.resume(returning: (m, e)) } }
    }

    func refreshOptions(_ w: StudioWorkflow, on backendId: String) async -> StudioWorkflow {
        await withCheckedContinuation { c in bridge.refreshOptions(workflow: w, backendId: backendId) { c.resume(returning: $0) } }
    }

    func saveSnippet(_ s: PromptSnippet) {
        let json = StudioCodec.shared.encodeSnippet(value: s)
        host.put(kind: StudioStoreBridgeCompanion.shared.SNIPPETS, id: s.id, json: json)
        library.apply(kind: StudioStoreBridgeCompanion.shared.SNIPPETS, id: s.id, json: json)
    }

    func deleteSnippet(_ id: String) {
        host.remove(kind: StudioStoreBridgeCompanion.shared.SNIPPETS, id: id)
        library.apply(kind: StudioStoreBridgeCompanion.shared.SNIPPETS, id: id, json: nil)
    }
}

extension JobState {
    var label: String {
        switch self {
        case .queued: "queued"
        case .running: "running"
        case .done: "done"
        case .failed: "failed"
        default: "cancelled"
        }
    }
}

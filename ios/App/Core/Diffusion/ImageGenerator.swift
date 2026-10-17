import AutobotShared
import Foundation
import Observation

/// Who asked for an image (shown in the progress UI).
enum GenerationSource: Sendable { case user, agent }

/// Live state of the single generation slot.
enum GenerationState: Equatable, Sendable {
    case idle
    case running(source: GenerationSource, backend: String, request: DiffusionRequest, image: Int, step: Int, totalSteps: Int, preview: Data?, startedAt: Date)

    var isRunning: Bool { if case .running = self { true } else { false } }
}

extension DiffusionRequest {
    /// The engine calls of one user job: the request as is, or one per wildcard-expanded prompt,
    /// each with the batch and the seed, at most `PromptRuns.MAX_IMAGES` images (see `PromptRuns`).
    func calls() -> [DiffusionRequest] {
        PromptRuns.shared.plan(template: prompt, batch: Int32(batchCount), seed: seed, maxImages: PromptRuns.shared.MAX_IMAGES).map { run in
            var call = self
            call.prompt = run.prompt
            call.batchCount = Int(run.count)
            call.seed = run.seed
            return call
        }
    }
}

/// One stored result.
struct GeneratedImage: Equatable, Sendable {
    let galleryId: String
    let seed: Int64
    let width: Int
    let height: Int
    let durationMs: Int64
}

/// Builds engines bound to the network policy (kill switch, route, audit tag per backend).
final class DiffusionEngineFactory: Sendable {
    private let clients: HttpClientFactory
    private let llm: LocalLlm
    private let diffusion = LocalDiffusion()
    /// The backend an installed image model runs on: the user's choice, else the benchmark's fastest.
    private let backendFor: @MainActor @Sendable (InstalledModel) -> Backend

    init(clients: HttpClientFactory, llm: LocalLlm, backendFor: @escaping @MainActor @Sendable (InstalledModel) -> Backend = { $0.backend }) {
        self.clients = clients
        self.llm = llm
        self.backendFor = backendFor
    }

    @MainActor
    func create(_ backend: DiffusionBackend, library: ModelLibrary) -> any DiffusionEngine {
        let clients = clients
        let tag = "image:\(backend.name)"
        let override = backend.routeOverride
        let factory: @Sendable () -> HttpClient = { clients.create(tag: tag, override: override) }
        return switch backend.kind {
        case .sdApi: SdApiEngine(baseUrl: backend.baseUrl, clientFactory: factory)
        case .localSse: LocalSseEngine(baseUrl: backend.baseUrl, clientFactory: factory)
        case .onDevice: OnDeviceEngine(library: library, llm: llm, diffusion: diffusion, backendFor: backendFor)
        }
    }
}

/**
 The app's single image-generation slot (port of `ImageGenerator.kt`). Jobs are serialized (one
 Neural Engine / GPU, one backend at a time), progress is published in [state], and every result
 is stored in the encrypted gallery as it arrives — the gallery *is* the history. User jobs run
 on their own task so they keep going while the user switches tabs.
 */
@MainActor
@Observable
final class ImageGenerator {
    private(set) var state: GenerationState = .idle
    /// Results of the latest user-started job (kept so the screen can be left and reopened).
    private(set) var lastResults: [GeneratedImage] = []
    private(set) var lastError: String?
    /// What the running job is doing before its first step (model loading), or nil.
    private(set) var phase: String?
    /// A job runs. Stored apart from [state] (which changes every step) so views that only need
    /// "busy" (the root, Run, the studio's layout) are not re-rendered per step.
    private(set) var isBusy = false

    @ObservationIgnored private let engines: DiffusionEngineFactory
    @ObservationIgnored private let gallery: GalleryRepository
    @ObservationIgnored private let backends: DiffusionBackendRepository
    @ObservationIgnored private let library: ModelLibrary
    @ObservationIgnored private var userJob: Task<Void, Never>?

    init(engines: DiffusionEngineFactory, gallery: GalleryRepository, backends: DiffusionBackendRepository, library: ModelLibrary) {
        self.engines = engines
        self.gallery = gallery
        self.backends = backends
        self.library = library
    }

    func engine(for backend: DiffusionBackend) -> any DiffusionEngine { engines.create(backend, library: library) }

    /// Starts a user job. Results stream into [lastResults]; failures land in [lastError].
    func start(_ backend: DiffusionBackend, _ request: DiffusionRequest, _ inputs: DiffusionInputs) {
        guard userJob == nil, !isBusy else { return }
        lastError = nil
        lastResults = []
        let epoch = AppActivity.shared.epoch
        userJob = Task {
            do {
                _ = try await generate(backend, request.calls(), inputs, source: .user) { [weak self] image in self?.lastResults.append(image) }
            } catch is CancellationError {
            } catch {
                lastError = backend.kind == .onDevice && AppActivity.shared.interrupted(since: epoch)
                    ? "Stopped: iOS doesn't allow Neural Engine or GPU work while Autobot is in the background. Keep it open and try again."
                    : error.localizedDescription
            }
            userJob = nil
        }
    }

    /// Cancels the user job (the backend is interrupted / the stream dropped).
    func cancel() { userJob?.cancel() }

    func dismissError() { lastError = nil }

    /// Runs one job in the caller's task and stores every result.
    func generate(
        _ backend: DiffusionBackend,
        _ request: DiffusionRequest,
        _ inputs: DiffusionInputs = DiffusionInputs(),
        source: GenerationSource = .user,
        onResult: @escaping (GeneratedImage) -> Void = { _ in },
    ) async throws -> [GeneratedImage] {
        try await generate(backend, [request], inputs, source: source, onResult: onResult)
    }

    /**
     Runs [calls] one after another as one job: one slot, one progress state whose request carries
     the job's image count as its batch and whose image index counts across the calls.
     */
    func generate(
        _ backend: DiffusionBackend,
        _ calls: [DiffusionRequest],
        _ inputs: DiffusionInputs,
        source: GenerationSource,
        onResult: @escaping (GeneratedImage) -> Void,
    ) async throws -> [GeneratedImage] {
        guard !isBusy else { throw DiffusionError(message: "Another image is being generated", code: .backend) }
        guard var job = calls.first else { return [] }
        job.batchCount = calls.reduce(0) { $0 + max($1.batchCount, 1) }
        let engine = engines.create(backend, library: library)
        let started = Date()
        var saved: [GeneratedImage] = []
        isBusy = true
        state = .running(source: source, backend: backend.name, request: job, image: 0, step: 0, totalSteps: job.steps, preview: nil, startedAt: started)
        defer {
            state = .idle
            phase = nil
            isBusy = false
        }
        for request in calls {
            let offset = saved.count
            for try await event in engine.generate(request, inputs: inputs) {
                switch event {
                case .phase(let text):
                    phase = text
                case .progress(let image, let step, let total, let preview):
                    phase = nil
                    if case .running(let s, let b, let r, _, _, _, let old, let t) = state {
                        state = .running(source: s, backend: b, request: r, image: offset + image, step: step, totalSteps: total, preview: preview ?? old, startedAt: t)
                    }
                case .result(_, let image, let seed, let width, let height, let durationMs, _):
                    let stored = try await store(backend, request, image: image, seed: seed, width: width, height: height, durationMs: durationMs)
                    saved.append(stored)
                    onResult(stored)
                    if case .running(let s, let b, let r, let i, let st, let tt, _, let t) = state {
                        state = .running(source: s, backend: b, request: r, image: i, step: st, totalSteps: tt, preview: image, startedAt: t)
                    }
                }
            }
            try Task.checkCancellation()
        }
        if saved.isEmpty { throw DiffusionError(message: "The backend finished without returning an image", code: .protocol) }
        return saved
    }

    private func store(_ backend: DiffusionBackend, _ request: DiffusionRequest, image: Data, seed: Int64, width: Int, height: Int, durationMs: Int64) async throws -> GeneratedImage {
        let id = gallery.newId()
        let thumb = await Task.detached { ImageCodec.thumbnail(image) }.value ?? image
        var effective = request
        effective.seed = seed
        effective.width = width
        effective.height = height
        effective.batchCount = 1
        let params = (try? JSONEncoder.store.encode(effective)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
        try await gallery.save(
            GalleryItem(
                id: id,
                createdAt: nowMillis(),
                mode: request.mode.rawValue,
                prompt: request.prompt,
                negativePrompt: request.negativePrompt,
                width: width,
                height: height,
                seed: seed,
                steps: request.steps,
                cfgScale: request.cfgScale,
                sampler: [request.sampler, request.scheduler].compactMap { $0 }.joined(separator: " · "),
                engine: backend.name,
                model: request.model,
                durationMs: durationMs,
                paramsJson: params,
            ),
            image: image,
            thumbnail: thumb,
        )
        return GeneratedImage(galleryId: id, seed: seed, width: width, height: height, durationMs: durationMs)
    }

    /// The full request stored with a gallery item ("reuse parameters").
    nonisolated static func decodeParams(_ item: GalleryItem) -> DiffusionRequest? {
        try? JSONDecoder().decode(DiffusionRequest.self, from: Data(item.paramsJson.utf8))
    }
}

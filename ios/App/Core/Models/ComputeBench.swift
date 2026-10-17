import AutobotShared
import Foundation
import Synchronization

/// What the phone can compute on: CPU always, plus the Metal GPU and the Apple Neural Engine.
struct ComputeDevices: Equatable, Sendable {
    var gpu: String? = nil
    /// "Apple Neural Engine" when Core ML can use it (not in the Simulator).
    var npu: String? = nil
    var npuError: String? = nil
    /// Backends llama.cpp can run chat models on (it has no Neural Engine backend).
    var llmBackends: Set<Backend> = [.cpu]
    /// Every device llama.cpp registered (for the diagnostics report).
    var engineDevices: [LocalDevice] = []
}

/**
 Measures models on each backend so [ComputeProfile] can pick the fastest automatically (port of
 `ComputeBench.kt`). Text models: prompt processing and generation speed, and, when a draft model
 for it is installed, the same reply with and without speculative decoding (speedup, acceptance).
 Image models (Core ML): time per denoising step on the Neural Engine and on the GPU.
 */
@MainActor
final class ComputeBench {
    /// ~120 prompt tokens: enough to measure prompt processing without taking long.
    private static let prompt = "You are benchmarking a phone. Summarise in two sentences why on-device inference helps privacy, " +
        "battery life and latency compared with cloud inference, and mention one trade-off such as model size, memory " +
        "bandwidth or thermal throttling that limits sustained performance on a mobile system on a chip."

    /// Speculation pays over longer replies: a few hundred tokens of a structured answer.
    private static let speculativePrompt = "List ten practical ways to make a phone battery last longer, one numbered line each, " +
        "then explain the two most effective ones in a short paragraph."
    private static let speculativeTokens = 256
    private static let speculativeContext = 2048

    private let library: ModelLibrary
    private let profile: ComputeProfile
    private let llm: LocalLlm
    /// The speculative decoding setting (`SpeculativeMode` id); "off" skips that benchmark.
    private let speculativeMode: String
    private let diffusion = LocalDiffusion()

    init(library: ModelLibrary, profile: ComputeProfile, llm: LocalLlm, speculativeMode: String = "auto") {
        self.library = library
        self.profile = profile
        self.llm = llm
        self.speculativeMode = speculativeMode
    }

    /// What the system reports without loading an engine (shown while [devices] runs).
    static func quickDevices() -> ComputeDevices {
        guard Engines.linked else { return ComputeDevices(npuError: "on-device engines are not part of this build") }
        let info = LocalDeviceInfo.current()
        var result = ComputeDevices(gpu: info.gpu)
        if info.neuralEngine {
            result.npu = "Apple Neural Engine"
        } else {
            result.npuError = "the Neural Engine is not available in the Simulator"
        }
        return result
    }

    /// Adds what llama.cpp can actually use (Metal) to [quickDevices].
    func devices() async -> ComputeDevices {
        var result = Self.quickDevices()
        guard Engines.linked else { return result }
        result.engineDevices = await llm.devices()
        for d in result.engineDevices where d.kind == "gpu" {
            result.gpu = d.description.isEmpty ? d.name : d.description
            result.llmBackends.insert(.gpu)
        }
        return result
    }

    /// Benchmarks [model] on each backend in [backends]; results are recorded and returned.
    func run(_ model: InstalledModel, backends: Set<Backend>, onStatus: @escaping (String) -> Void) async -> [Benchmark] {
        var results: [Benchmark] = []
        switch model.engine {
        case .llama:
            for backend in [Backend.cpu, .gpu, .npu] where backends.contains(backend) {
                onStatus("\(model.title) on \(backend.label)…")
                results.append(await text(model, backend))
            }
            results.forEach(profile.record)
            if let spec = await speculative(model, onStatus: onStatus) { profile.recordSpeculative(spec) }
            return results
        case .coreML:
            for backend in [Backend.npu, .gpu] {
                onStatus("\(model.title) on \(backend == .npu ? "neural engine" : "gpu")…")
                results.append(await image(model, backend))
            }
        default:
            break
        }
        results.forEach(profile.record)
        return results
    }

    private func text(_ model: InstalledModel, _ backend: Backend) async -> Benchmark {
        guard let path = library.paths(model)[.model] else { return Benchmark(modelId: model.id, backend: backend, error: "not installed") }
        let request: [String: Any] = [
            "messages": [["role": "user", "content": Self.prompt]],
            "max_tokens": 96,
            "temperature": 0.0,
            "enable_thinking": false,
        ]
        let started = Date()
        do {
            let json = try JSONSerialization.data(withJSONObject: request)
            let r = try await llm.chat(modelPath: path.path, mmprojPath: nil, contextLength: 2048, backend: backend, requestJSON: json) { _ in }
            let promptTokens = r.promptTokens - r.cachedTokens
            return Benchmark(
                modelId: model.id,
                backend: Backend.of(r.backend),
                promptTps: r.promptMs > 0 ? Double(promptTokens) / r.promptMs * 1000 : nil,
                generationTps: r.generationMs > 0 ? Double(r.completionTokens) / r.generationMs * 1000 : nil,
                loadMs: Int64(Date().timeIntervalSince(started) * 1000 - r.promptMs - r.generationMs),
            )
        } catch {
            return Benchmark(modelId: model.id, backend: backend, error: error.localizedDescription)
        }
    }

    /**
     Speculative decoding on the backend "auto" picks: the same greedy reply without and with the
     installed draft model (whatever the memory rule of the setting, unless it is off). Nil when the
     model has no installed draft or the setting is off.
     */
    private func speculative(_ model: InstalledModel, onStatus: @escaping (String) -> Void) async -> SpeculativeResult? {
        guard speculativeMode != "off", let path = library.paths(model)[.model] else { return nil }
        let backend = profile.resolve(model)
        guard let draft = SpeculativeDraftPicker.pick(for: model, mode: "on", library: library, device: DeviceProfile.current(),
                                                      context: Self.speculativeContext, backend: backend) else { return nil }
        onStatus("\(model.title) with \(draft.title) (speculative)…")
        func run(_ mode: String) async -> String? {
            let request: [String: Any] = [
                "messages": [["role": "user", "content": Self.speculativePrompt]],
                "max_tokens": Self.speculativeTokens,
                "temperature": 0.0,
                "enable_thinking": false,
                "draft": ["path": draft.path, "backend": draft.backend],
                "speculative": mode,
            ]
            guard let json = try? JSONSerialization.data(withJSONObject: request) else { return nil }
            return try? await llm.chatJSON(modelPath: path.path, mmprojPath: nil, contextLength: Self.speculativeContext, backend: backend,
                                           requestJSON: json) { _ in }
        }
        // "off" keeps the draft loaded and does not use it: both runs see the same memory.
        let plain = await run("off")
        let spec = await run(speculativeMode)
        let stats = spec.flatMap { LocalLlmCodec.shared.speculativeStats(json: $0) }
        var error: String? = nil
        if plain == nil || spec == nil {
            error = "the engine did not answer"
        } else if let stats, stats.status != "active" {
            error = stats.reason ?? stats.status
        }
        return SpeculativeResult(modelId: model.id, draftTitle: draft.title, backend: backend, plainTps: Self.tps(plain), speculativeTps: Self.tps(spec),
                                 acceptance: stats?.acceptance ?? 0, error: error)
    }

    /// Generation speed in a result JSON (tokens per second), 0 when absent.
    nonisolated static func tps(_ json: String?) -> Double {
        guard let json, let object = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              let usage = object["usage"] as? [String: Any],
              let ms = (usage["generation_ms"] as? NSNumber)?.doubleValue, ms > 0,
              let tokens = (usage["completion_tokens"] as? NSNumber)?.doubleValue
        else { return 0 }
        return tokens / ms * 1000
    }

    private func image(_ model: InstalledModel, _ backend: Backend) async -> Benchmark {
        guard let folder = LocalDiffusion.resourceFolder(in: ModelLibrary.dir(of: model.id)) else {
            return Benchmark(modelId: model.id, backend: backend, error: "not installed")
        }
        // The chat model and the pipeline should not compete for memory.
        await llm.unload()
        let steps = 4
        do {
            let info = try diffusion.inspect(folder)
            var request = DiffusionRequest()
            request.prompt = "a lighthouse on a cliff at dusk"
            request.steps = steps
            request.cfgScale = 1
            request.seed = 1
            request.width = info.width
            request.height = info.height
            let ticks = Mutex<[(step: Int, at: Date)]>([])
            let started = Date()
            try await diffusion.generate(info, request: request, inputs: DiffusionInputs(), backend: backend) { event in
                if case .progress(_, let step, _, _) = event { ticks.withLock { $0.append((step, Date())) } }
            }
            await diffusion.unload()
            let timing = ImageBenchTiming.measure(started: started, ticks: ticks.withLock { $0 })
            return Benchmark(modelId: model.id, backend: backend, stepMs: timing.stepMs, loadMs: timing.loadMs,
                             error: timing.stepMs == nil ? "no step progress reported" : nil)
        } catch {
            await diffusion.unload()
            return Benchmark(modelId: model.id, backend: backend, error: error.localizedDescription)
        }
    }
}

/**
 Image benchmark timing from the sampler's step callbacks: the step time is the mean interval
 between consecutive steps (model load and the VAE decode never count), and the load time is
 everything before the first step started — like Android's `Benchmark.stepMs` / `loadMs`.
 */
enum ImageBenchTiming {
    static func measure(started: Date, ticks: [(step: Int, at: Date)]) -> (stepMs: Double?, loadMs: Int64?) {
        let sorted = ticks.sorted { $0.at < $1.at }
        guard let first = sorted.first else { return (nil, nil) }
        let stepMs: Double
        if let last = sorted.last, last.step > first.step {
            stepMs = last.at.timeIntervalSince(first.at) * 1000 / Double(last.step - first.step)
        } else {
            // A single callback: the whole first step is all there is, load cannot be separated.
            return (first.at.timeIntervalSince(started) * 1000, nil)
        }
        let beforeFirst = first.at.timeIntervalSince(started) * 1000 - stepMs * Double(max(first.step, 1))
        return (stepMs, Int64(max(beforeFirst, 0)))
    }
}

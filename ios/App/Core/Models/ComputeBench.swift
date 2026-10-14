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
 `ComputeBench.kt`). Text models: prompt processing and generation speed. Image models (Core ML):
 time per denoising step on the Neural Engine and on the GPU.
 */
@MainActor
final class ComputeBench {
    /// ~120 prompt tokens: enough to measure prompt processing without taking long.
    private static let prompt = "You are benchmarking a phone. Summarise in two sentences why on-device inference helps privacy, " +
        "battery life and latency compared with cloud inference, and mention one trade-off such as model size, memory " +
        "bandwidth or thermal throttling that limits sustained performance on a mobile system on a chip."

    private let library: ModelLibrary
    private let profile: ComputeProfile
    private let llm: LocalLlm
    private let diffusion = LocalDiffusion()

    init(library: ModelLibrary, profile: ComputeProfile, llm: LocalLlm) {
        self.library = library
        self.profile = profile
        self.llm = llm
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

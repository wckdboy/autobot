import Foundation
#if canImport(AutobotEngines)
import AutobotEngines
#endif

/// The on-device engines that keep a large model in memory.
enum ResidentEngine: String, Sendable, CaseIterable {
    /// llama.cpp chat (a few GB).
    case llm
    /// Core ML Stable Diffusion (~1.2 GB resident, more while it runs).
    case diffusion
    /// Kokoro voice (predictor and decoders, ~250 MB each).
    case tts
    /// whisper.cpp dictation.
    case whisper
    /// Core ML 4× upscaler (the model plus the image buffers).
    case upscaler
}

/**
 Keeps the on-device engines from running the app into the system memory limit (jetsam). Every
 engine use goes through [using]: before an engine loads, the ones that must not share memory
 with it are unloaded ([evictions]). On a memory warning and when the app goes to the background
 every engine is unloaded ([releaseAll]); voice mode ending or a chat closing releases the speech
 engines. An engine in use is released when its work ends, never under it.
 */
actor EngineMemory {
    static let shared = EngineMemory(unloaders: EngineMemory.engineUnloaders)

    private let unloaders: [ResidentEngine: @Sendable () async -> Void]
    /// Engines that may hold their model (used since they were last unloaded).
    private(set) var resident: Set<ResidentEngine> = []
    private var busy: [ResidentEngine: Int] = [:]
    /// Released while in use: unloaded when the work ends.
    private var pendingRelease: Set<ResidentEngine> = []

    init(unloaders: [ResidentEngine: @Sendable () async -> Void]) {
        self.unloaders = unloaders
    }

    /**
     Engines to unload before [kind] loads, of those [resident]. Image generation and upscaling
     take most of the memory a phone grants an app, so they run alone. Chat, dictation and the
     voice run together (voice mode needs all three) but never next to those two.
     */
    nonisolated static func evictions(before kind: ResidentEngine, resident: Set<ResidentEngine>) -> Set<ResidentEngine> {
        let alone: Set<ResidentEngine> = [.diffusion, .upscaler]
        let evict = alone.contains(kind) ? Set(ResidentEngine.allCases).subtracting([kind]) : alone
        return evict.intersection(resident)
    }

    /// Runs [body], which loads and uses [kind], after [memory] unloaded what must not share
    /// memory with it. [body] runs in the caller's isolation.
    static func using<T>(
        _ kind: ResidentEngine,
        on memory: EngineMemory = .shared,
        isolation: isolated (any Actor)? = #isolation,
        _ body: () async throws -> T,
    ) async throws -> T {
        await memory.begin(kind)
        do {
            let value = try await body()
            await memory.end(kind)
            return value
        } catch {
            await memory.end(kind)
            throw error
        }
    }

    /// Unloads [kinds] now, or when the work using them ends.
    func release(_ kinds: Set<ResidentEngine>) async {
        for kind in kinds.intersection(resident) {
            if busy[kind, default: 0] > 0 {
                pendingRelease.insert(kind)
            } else {
                await unload(kind)
            }
        }
    }

    /// Memory warning or background: every engine goes (busy ones when they are done).
    func releaseAll() async { await release(Set(ResidentEngine.allCases)) }

    /// Engines whose unload waits on their own thread for the request in flight, so it is safe
    /// (and, before a big load, needed) while they are busy. The others are actors whose models
    /// would vanish under a running request: they go when their work ends.
    nonisolated static let unloadWaitsForWork: Set<ResidentEngine> = [.llm, .diffusion, .whisper]

    func begin(_ kind: ResidentEngine) async {
        busy[kind, default: 0] += 1
        pendingRelease.remove(kind)
        for other in Self.evictions(before: kind, resident: resident) {
            if busy[other, default: 0] > 0 && !Self.unloadWaitsForWork.contains(other) {
                pendingRelease.insert(other)
            } else {
                await unload(other)
            }
        }
        resident.insert(kind)
    }

    func end(_ kind: ResidentEngine) async {
        busy[kind, default: 1] -= 1
        if busy[kind] == 0, pendingRelease.remove(kind) != nil {
            await unload(kind)
        }
    }

    private func unload(_ kind: ResidentEngine) async {
        resident.remove(kind)
        pendingRelease.remove(kind)
        await unloaders[kind]?()
    }

    /// The real engines. Each unload runs on the engine's own thread after the request in flight.
    private static var engineUnloaders: [ResidentEngine: @Sendable () async -> Void] {
        #if canImport(AutobotEngines)
        [
            .llm: { await LlamaEngine.shared.unload() },
            .diffusion: { await AutobotEngines.DiffusionEngine.shared.unload() },
            .tts: { await LocalVoice.shared.unload() },
            .whisper: { await SpeechEngine.shared.unload() },
            .upscaler: { await UpscaleEngine.shared.unload() },
        ]
        #else
        [:]
        #endif
    }
}

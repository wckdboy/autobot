import Foundation
import Synchronization
#if canImport(AutobotEngines)
import AutobotEngines
#endif

// Adapters between the app and the `AutobotEngines` package (llama.cpp on Metal, whisper.cpp,
// Core ML Stable Diffusion). These are the only files that import the package; its type names
// (ToolCall, DiffusionRequest, …) overlap the app's. When the package is not linked (see
// `ios/README.md`) every call fails with [EngineUnavailable] and the UI says so.

struct EngineUnavailable: LocalizedError {
    var errorDescription: String? { "On-device engines are not part of this build" }
}

/// Engine failure with the engine core's code (`CANCELLED`, `CONTEXT_WINDOW_EXCEEDED`, …).
struct LocalEngineError: LocalizedError {
    let code: String
    let message: String
    var errorDescription: String? { message }
}

enum Engines {
    /// Whether the on-device engines are linked into this build.
    static var linked: Bool {
        #if canImport(AutobotEngines)
        true
        #else
        false
        #endif
    }

    static func map(_ error: Error) -> Error {
        #if canImport(AutobotEngines)
        if let e = error as? EngineError {
            if e.code != "CANCELLED" { EngineErrors.record(e.message) }
            return LocalEngineError(code: e.code, message: e.message)
        }
        #endif
        EngineErrors.record(error.localizedDescription)
        return error
    }
}

// MARK: - Chat (llama.cpp)

enum LocalDelta: Sendable {
    case content(String)
    case reasoning(String)
}

/// Final result of one on-device completion (mirrors the engine core's JSON).
struct LocalLlmResult: Sendable {
    var content: String
    var reasoning: String
    var toolCalls: [ToolCall]
    /// "stop", "length", "tool_calls", "cancelled" or "error".
    var finishReason: String
    var promptTokens: Int
    var cachedTokens: Int
    var completionTokens: Int
    /// Where the model actually ran: "cpu", "gpu" or "npu".
    var backend: String
    var promptMs: Double
    var generationMs: Double
}

/// A compute device llama.cpp can use.
struct LocalDevice: Sendable, Hashable {
    let name: String
    let description: String
    /// "cpu", "gpu" or "npu".
    let kind: String
    var memoryTotalBytes: Int64 = 0
}

/// Recent engine failures for the diagnostics report: messages only, in memory, newest first.
enum EngineErrors {
    private static let log = Mutex<[String]>([])

    static func record(_ message: String) {
        log.withLock { list in
            list.insert(message, at: 0)
            if list.count > 10 { list.removeLast(list.count - 10) }
        }
    }

    static var recent: [String] { log.withLock { $0 } }
}

/// llama.cpp on this device (Metal GPU or CPU); one resident model, requests run serially.
final class LocalLlm: Sendable {
    func chat(
        modelPath: String,
        mmprojPath: String?,
        contextLength: Int,
        backend: Backend,
        requestJSON: Data,
        onDelta: @escaping @Sendable (LocalDelta) -> Void,
    ) async throws -> LocalLlmResult {
        #if canImport(AutobotEngines)
        do {
            let r = try await LlamaEngine.shared.chat(
                modelPath: modelPath,
                mmprojPath: mmprojPath,
                contextLength: contextLength,
                backend: ComputeBackend(rawValue: backend.rawValue) ?? .auto,
                requestJSON: requestJSON,
                onDelta: { kind, text in onDelta(kind == .reasoning ? .reasoning(text) : .content(text)) },
            )
            return LocalLlmResult(
                content: r.content,
                reasoning: r.reasoning,
                toolCalls: r.toolCalls.map { ToolCall(id: $0.id, name: $0.name, arguments: $0.arguments) },
                finishReason: r.finishReason,
                promptTokens: r.usage.promptTokens,
                cachedTokens: r.usage.cachedTokens,
                completionTokens: r.usage.completionTokens,
                backend: r.usage.backend,
                promptMs: r.usage.promptMs,
                generationMs: r.usage.generationMs,
            )
        } catch {
            throw Engines.map(error)
        }
        #else
        throw EngineUnavailable()
        #endif
    }

    /// Same as [chat] but returns the engine core's raw result JSON (what the shared Kotlin
    /// codec decodes). Engine failures are thrown as [LocalEngineError].
    func chatJSON(
        modelPath: String,
        mmprojPath: String?,
        contextLength: Int,
        backend: Backend,
        requestJSON: Data,
        onDelta: @escaping @Sendable (LocalDelta) -> Void,
    ) async throws -> String {
        #if canImport(AutobotEngines)
        do {
            return try await LlamaEngine.shared.chat(
                modelPath: modelPath,
                mmprojPath: mmprojPath,
                contextLength: contextLength,
                backend: ComputeBackend(rawValue: backend.rawValue) ?? .auto,
                requestJSON: requestJSON,
                onDelta: { kind, text in onDelta(kind == .reasoning ? .reasoning(text) : .content(text)) },
            ).json
        } catch {
            throw Engines.map(error)
        }
        #else
        throw EngineUnavailable()
        #endif
    }

    func cancel() {
        #if canImport(AutobotEngines)
        LlamaEngine.shared.cancel()
        #endif
    }

    /// Frees the resident model (before image generation needs the memory).
    func unload() async {
        #if canImport(AutobotEngines)
        await LlamaEngine.shared.unload()
        #endif
    }

    func devices() async -> [LocalDevice] {
        #if canImport(AutobotEngines)
        let list = (try? await LlamaEngine.shared.devices()) ?? []
        return list.map { LocalDevice(name: $0.name, description: $0.description, kind: $0.kind, memoryTotalBytes: Int64($0.memoryTotal)) }
        #else
        return []
        #endif
    }

    func systemInfo() async throws -> String {
        #if canImport(AutobotEngines)
        try await LlamaEngine.shared.systemInfo()
        #else
        throw EngineUnavailable()
        #endif
    }
}

// MARK: - Speech (whisper.cpp)

/// On-device dictation: microphone capture (16 kHz mono, memory only) and whisper.cpp.
final class LocalSpeech: Sendable {
    #if canImport(AutobotEngines)
    private let recorder = AudioRecorder()
    #endif

    func requestPermission() async -> Bool {
        #if canImport(AutobotEngines)
        await AudioRecorder.requestPermission()
        #else
        false
        #endif
    }

    func start() throws {
        #if canImport(AutobotEngines)
        do { try recorder.start() } catch { throw Engines.map(error) }
        #else
        throw EngineUnavailable()
        #endif
    }

    /// Stops recording and returns the samples.
    func stop() -> [Float] {
        #if canImport(AutobotEngines)
        recorder.stop()
        #else
        []
        #endif
    }

    func transcribe(modelPath: String, samples: [Float]) async throws -> String {
        #if canImport(AutobotEngines)
        do { return try await SpeechEngine.shared.transcribe(modelPath: modelPath, pcm16kMono: samples) } catch { throw Engines.map(error) }
        #else
        throw EngineUnavailable()
        #endif
    }
}

// MARK: - Device

enum LocalDeviceInfo {
    /// Hardware facts from the engines package when linked (marketing name, chip, ANE).
    static func current() -> (machine: String, name: String?, chip: String?, neuralEngine: Bool, gpu: String?, availableRam: Int64?) {
        #if canImport(AutobotEngines)
        let d = DeviceCompute.current()
        return (d.machine, d.modelName, d.chip, d.hasNeuralEngine, d.gpuName, Int64(d.availableRamBytes))
        #else
        let p = DeviceProfile.current()
        return (p.machine, nil, p.chip, false, p.gpu, nil)
        #endif
    }
}

import CoreML
import Foundation

/// Where an engine should run. Mirrors the Android backend strings ("auto", "cpu", "gpu", "npu").
public enum ComputeBackend: String, Sendable, Codable, CaseIterable {
    /// npu → gpu → cpu, whatever loads first.
    case auto
    case cpu
    /// Metal.
    case gpu
    /// Apple Neural Engine. Only Core ML engines (images, the whisper encoder) can use it; llama.cpp
    /// has no ANE backend, so a chat model asked for "npu" falls back to the GPU or CPU.
    case npu

    /// Core ML compute units for this backend: `.npu` = CPU + Neural Engine, `.gpu` = CPU + GPU.
    public var computeUnits: MLComputeUnits {
        switch self {
        case .auto: .all
        case .cpu: .cpuOnly
        case .gpu: .cpuAndGPU
        case .npu: .cpuAndNeuralEngine
        }
    }
}

/// ggml's Metal backend does not work in the iOS Simulator (garbage output, crashes in the
/// simulated driver), so engines stay on the CPU there.
let metalUsable: Bool = {
    #if targetEnvironment(simulator)
    false
    #else
    true
    #endif
}()

/// A compute device as reported by llama.cpp (`autobot_llama_devices_json`).
public struct ComputeDevice: Sendable, Codable, Hashable {
    public let name: String
    public let description: String
    /// "cpu", "gpu" or "npu".
    public let kind: String
    public let memoryFree: Double
    public let memoryTotal: Double

    enum CodingKeys: String, CodingKey {
        case name, description, kind
        case memoryFree = "memory_free"
        case memoryTotal = "memory_total"
    }

    public var backend: ComputeBackend { ComputeBackend(rawValue: kind) ?? .cpu }
}

/// An engine failure. [code] matches the Android engine's error codes (LOAD_FAILED,
/// CONTEXT_WINDOW_EXCEEDED, NO_VISION, BAD_IMAGE, DECODE_FAILED, CANCELLED, BAD_REQUEST, …).
public struct EngineError: Error, Sendable, Equatable, LocalizedError {
    public let code: String
    public let message: String

    public init(_ code: String, _ message: String) {
        self.code = code
        self.message = message
    }

    public var errorDescription: String? { message }

    public static let cancelled = EngineError("CANCELLED", "cancelled")
}

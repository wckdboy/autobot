import AutobotShared
import Foundation
import Metal
import UIKit

/// How well a model fits this phone.
enum Fit: String, Sendable {
    case good, tight, tooBig, unsupported

    var label: String {
        switch self {
        case .good: "fits"
        case .tight: "tight"
        case .tooBig: "too big"
        case .unsupported: "unsupported"
        }
    }
}

/// What this phone can run, read from the system (no network, no identifiers leave the device).
struct DeviceProfile: Equatable, Sendable {
    /// Hardware identifier, e.g. `iPhone18,1`.
    let machine: String
    /// Marketing chip name, e.g. `A19 Pro`, when known.
    let chip: String?
    let totalRamBytes: Int64
    let freeStorageBytes: Int64
    let cores: Int
    let gpu: String?
    let thermal: ProcessInfo.ThermalState
    /// Apple Neural Engine present (Core ML falls back to the GPU without it).
    var neuralEngine = false
    /// Metal's recommended working set, 0 when unknown.
    var gpuWorkingSetBytes: Int64 = 0

    /// e.g. `iPhone18,1 · A19 Pro`.
    var label: String { [machine, chip].compactMap { $0 }.joined(separator: " · ") }

    var thermalLabel: String {
        switch thermal {
        case .nominal: "cool"
        case .fair: "warm"
        case .serious: "hot"
        case .critical: "throttled"
        @unknown default: "—"
        }
    }

    /// What the shared fit rules see.
    var capabilities: DeviceCapabilities {
        DeviceCapabilities(platform: .ios, totalRamBytes: totalRamBytes, freeStorageBytes: freeStorageBytes, cpuFeatures: [], socModel: nil,
                           neuralEngine: neuralEngine, gpuWorkingSetBytes: gpuWorkingSetBytes)
    }

    /// `fits` / `tight` / `too big` by the same rules as Android (weights + KV cache / buffers
    /// within about half of physical RAM; engines this build lacks are unsupported).
    func fit(_ kind: ModelKind, bytes: Int64, engine: EngineKind) -> Fit {
        Fit(capabilities.fit(kind: kind.kotlin, bytes: bytes, engine: engine.kotlin, contextTokens: 0))
    }

    /// What never changes while the app runs (identifier, chip, RAM, cores, GPU, ANE), read once:
    /// creating the Metal device and asking the engines package is not free.
    private struct Hardware: Sendable {
        let machine: String
        let chip: String?
        let totalRamBytes: Int64
        let cores: Int
        let gpu: String?
        let neuralEngine: Bool
        let gpuWorkingSetBytes: Int64
    }

    private static let hardware: Hardware = {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
        let identifier = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? machine
        let metal = MTLCreateSystemDefaultDevice()
        return Hardware(
            machine: identifier, chip: chipName(identifier), totalRamBytes: Int64(ProcessInfo.processInfo.physicalMemory),
            cores: ProcessInfo.processInfo.activeProcessorCount, gpu: metal?.name, neuralEngine: LocalDeviceInfo.hasNeuralEngine,
            gpuWorkingSetBytes: Int64(metal?.recommendedMaxWorkingSetSize ?? 0),
        )
    }()

    /// Free space on the app's volume (a file system query; the only part that changes with thermal state).
    static func freeStorage() -> Int64 {
        Int64((try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?
            .volumeAvailableCapacityForImportantUsage ?? 0)
    }

    /// The device now: cached hardware facts, current free storage and thermal state.
    static func current() -> DeviceProfile {
        let h = hardware
        return DeviceProfile(
            machine: h.machine, chip: h.chip, totalRamBytes: h.totalRamBytes, freeStorageBytes: freeStorage(), cores: h.cores, gpu: h.gpu,
            thermal: ProcessInfo.processInfo.thermalState, neuralEngine: h.neuralEngine, gpuWorkingSetBytes: h.gpuWorkingSetBytes,
        )
    }

    /// This profile with fresh free storage and thermal state (hardware facts kept).
    func refreshed() -> DeviceProfile {
        DeviceProfile(
            machine: machine, chip: chip, totalRamBytes: totalRamBytes, freeStorageBytes: Self.freeStorage(), cores: cores, gpu: gpu,
            thermal: ProcessInfo.processInfo.thermalState, neuralEngine: neuralEngine, gpuWorkingSetBytes: gpuWorkingSetBytes,
        )
    }

    /// Public hardware identifiers → chips (recent iPhones).
    static func chipName(_ machine: String) -> String? {
        let table: [String: String] = [
            "iPhone14,2": "A15 Bionic", "iPhone14,3": "A15 Bionic", "iPhone14,4": "A15 Bionic", "iPhone14,5": "A15 Bionic",
            "iPhone14,7": "A15 Bionic", "iPhone14,8": "A15 Bionic", "iPhone14,6": "A15 Bionic",
            "iPhone15,2": "A16 Bionic", "iPhone15,3": "A16 Bionic", "iPhone15,4": "A16 Bionic", "iPhone15,5": "A16 Bionic",
            "iPhone16,1": "A17 Pro", "iPhone16,2": "A17 Pro",
            "iPhone17,1": "A18 Pro", "iPhone17,2": "A18 Pro", "iPhone17,3": "A18", "iPhone17,4": "A18", "iPhone17,5": "A18",
            "iPhone18,1": "A19 Pro", "iPhone18,2": "A19 Pro", "iPhone18,3": "A19", "iPhone18,4": "A19 Pro", "iPhone18,5": "A19",
        ]
        if let chip = table[machine] { return chip }
        if machine.hasPrefix("arm64") || machine.hasPrefix("x86") { return "simulator" }
        return nil
    }
}

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

    /// Weights + KV cache / buffers should stay under about half of physical RAM (same budget
    /// as Android). iOS also caps a single app's memory, which this budget keeps clear of.
    func fit(_ kind: ModelKind, bytes: Int64, engine: EngineKind) -> Fit {
        if engine == .none || !engine.runsOnIOS { return .unsupported }
        let working: Int64 = switch kind {
        case .chat, .code: bytes * 13 / 10
        case .image: bytes * 14 / 10
        default: bytes
        }
        let budget = totalRamBytes / 2
        if working <= budget * 7 / 10 { return .good }
        if working <= budget { return .tight }
        return .tooBig
    }

    static func current() -> DeviceProfile {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix(while: { $0 != 0 }), as: UTF8.self)
        }
        let identifier = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? machine
        let free = (try? URL(fileURLWithPath: NSHomeDirectory()).resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?
            .volumeAvailableCapacityForImportantUsage ?? 0
        return DeviceProfile(
            machine: identifier,
            chip: chipName(identifier),
            totalRamBytes: Int64(ProcessInfo.processInfo.physicalMemory),
            freeStorageBytes: Int64(free),
            cores: ProcessInfo.processInfo.activeProcessorCount,
            gpu: MTLCreateSystemDefaultDevice()?.name,
            thermal: ProcessInfo.processInfo.thermalState,
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
            "iPhone18,1": "A19 Pro", "iPhone18,2": "A19 Pro", "iPhone18,3": "A19", "iPhone18,4": "A19 Pro",
        ]
        if let chip = table[machine] { return chip }
        if machine.hasPrefix("arm64") || machine.hasPrefix("x86") { return "simulator" }
        return nil
    }
}

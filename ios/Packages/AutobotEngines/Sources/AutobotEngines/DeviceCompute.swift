import Foundation
import Metal

/// What this device can run, read from the system (no network, no identifiers leave the device).
/// The iOS counterpart of Android's `DeviceProfile`.
public struct DeviceCompute: Sendable, Codable, Hashable {
    /// Hardware model identifier, e.g. `iPhone17,1`.
    public let machine: String
    /// Marketing name, e.g. `iPhone 16 Pro`, when the identifier is known.
    public let modelName: String?
    /// Apple silicon chip, e.g. `A18 Pro` or `M4`, when known.
    public let chip: String?
    public let totalRamBytes: UInt64
    /// Memory this app may still allocate before the system terminates it (`os_proc_available_memory`).
    public let availableRamBytes: UInt64
    public let freeStorageBytes: Int64
    public let cores: Int
    public let performanceCores: Int
    /// Core ML can schedule work on the Apple Neural Engine (every iOS 18 device; not the Simulator).
    public let hasNeuralEngine: Bool
    public let gpuName: String?
    /// Metal's recommended working set: roughly how much a GPU model may use without paging.
    public let gpuRecommendedMaxWorkingSetBytes: UInt64
    public let isSimulator: Bool

    /// e.g. `iPhone 16 Pro · A18 Pro`.
    public var label: String {
        [modelName ?? machine, chip].compactMap { $0 }.joined(separator: " · ")
    }

    public static func current(storagePath: String = NSHomeDirectory()) -> DeviceCompute {
        #if targetEnvironment(simulator)
        let simulator = true
        let machine = ProcessInfo.processInfo.environment["SIMULATOR_MODEL_IDENTIFIER"] ?? sysctlString("hw.machine") ?? "Simulator"
        #else
        let simulator = false
        let machine = sysctlString("hw.machine") ?? "unknown"
        #endif
        let known = lookup(machine)
        let gpu = MTLCreateSystemDefaultDevice()
        let free = (try? URL(fileURLWithPath: storagePath)
            .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage) ?? 0
        return DeviceCompute(
            machine: machine,
            modelName: known?.name,
            chip: known?.chip,
            totalRamBytes: ProcessInfo.processInfo.physicalMemory,
            availableRamBytes: availableMemory(),
            freeStorageBytes: free,
            cores: ProcessInfo.processInfo.processorCount,
            performanceCores: sysctlInt("hw.perflevel0.physicalcpu") ?? ProcessInfo.processInfo.activeProcessorCount,
            hasNeuralEngine: !simulator,
            gpuName: gpu?.name,
            gpuRecommendedMaxWorkingSetBytes: gpu?.recommendedMaxWorkingSetSize ?? 0,
            isSimulator: simulator,
        )
    }

    /// Marketing name and chip for a hardware identifier (recent iPhones and iPads).
    public static func lookup(_ machine: String) -> (name: String, chip: String)? {
        MODELS[machine]
    }

    private static func availableMemory() -> UInt64 {
        #if os(iOS) && !targetEnvironment(simulator)
        return UInt64(os_proc_available_memory())
        #else
        return ProcessInfo.processInfo.physicalMemory
        #endif
    }

    private static func sysctlString(_ name: String) -> String? {
        var size = 0
        guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else { return nil }
        var buffer = [CChar](repeating: 0, count: size)
        guard sysctlbyname(name, &buffer, &size, nil, 0) == 0 else { return nil }
        return String(decoding: buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }, as: UTF8.self)
    }

    private static func sysctlInt(_ name: String) -> Int? {
        var value: Int32 = 0
        var size = MemoryLayout<Int32>.size
        guard sysctlbyname(name, &value, &size, nil, 0) == 0, value > 0 else { return nil }
        return Int(value)
    }

    /// Public hardware identifiers → marketing names and chips.
    private static let MODELS: [String: (name: String, chip: String)] = [
        "iPhone11,2": ("iPhone XS", "A12 Bionic"),
        "iPhone11,4": ("iPhone XS Max", "A12 Bionic"),
        "iPhone11,6": ("iPhone XS Max", "A12 Bionic"),
        "iPhone11,8": ("iPhone XR", "A12 Bionic"),
        "iPhone12,1": ("iPhone 11", "A13 Bionic"),
        "iPhone12,3": ("iPhone 11 Pro", "A13 Bionic"),
        "iPhone12,5": ("iPhone 11 Pro Max", "A13 Bionic"),
        "iPhone12,8": ("iPhone SE (2nd generation)", "A13 Bionic"),
        "iPhone13,1": ("iPhone 12 mini", "A14 Bionic"),
        "iPhone13,2": ("iPhone 12", "A14 Bionic"),
        "iPhone13,3": ("iPhone 12 Pro", "A14 Bionic"),
        "iPhone13,4": ("iPhone 12 Pro Max", "A14 Bionic"),
        "iPhone14,2": ("iPhone 13 Pro", "A15 Bionic"),
        "iPhone14,3": ("iPhone 13 Pro Max", "A15 Bionic"),
        "iPhone14,4": ("iPhone 13 mini", "A15 Bionic"),
        "iPhone14,5": ("iPhone 13", "A15 Bionic"),
        "iPhone14,6": ("iPhone SE (3rd generation)", "A15 Bionic"),
        "iPhone14,7": ("iPhone 14", "A15 Bionic"),
        "iPhone14,8": ("iPhone 14 Plus", "A15 Bionic"),
        "iPhone15,2": ("iPhone 14 Pro", "A16 Bionic"),
        "iPhone15,3": ("iPhone 14 Pro Max", "A16 Bionic"),
        "iPhone15,4": ("iPhone 15", "A16 Bionic"),
        "iPhone15,5": ("iPhone 15 Plus", "A16 Bionic"),
        "iPhone16,1": ("iPhone 15 Pro", "A17 Pro"),
        "iPhone16,2": ("iPhone 15 Pro Max", "A17 Pro"),
        "iPhone17,1": ("iPhone 16 Pro", "A18 Pro"),
        "iPhone17,2": ("iPhone 16 Pro Max", "A18 Pro"),
        "iPhone17,3": ("iPhone 16", "A18"),
        "iPhone17,4": ("iPhone 16 Plus", "A18"),
        "iPhone17,5": ("iPhone 16e", "A18"),
        "iPhone18,1": ("iPhone 17 Pro", "A19 Pro"),
        "iPhone18,2": ("iPhone 17 Pro Max", "A19 Pro"),
        "iPhone18,3": ("iPhone 17", "A19"),
        "iPhone18,4": ("iPhone Air", "A19 Pro"),
        "iPad13,4": ("iPad Pro 11-inch (3rd generation)", "M1"),
        "iPad13,5": ("iPad Pro 11-inch (3rd generation)", "M1"),
        "iPad13,6": ("iPad Pro 11-inch (3rd generation)", "M1"),
        "iPad13,7": ("iPad Pro 11-inch (3rd generation)", "M1"),
        "iPad13,8": ("iPad Pro 12.9-inch (5th generation)", "M1"),
        "iPad13,9": ("iPad Pro 12.9-inch (5th generation)", "M1"),
        "iPad13,10": ("iPad Pro 12.9-inch (5th generation)", "M1"),
        "iPad13,11": ("iPad Pro 12.9-inch (5th generation)", "M1"),
        "iPad13,16": ("iPad Air (5th generation)", "M1"),
        "iPad13,17": ("iPad Air (5th generation)", "M1"),
        "iPad14,3": ("iPad Pro 11-inch (4th generation)", "M2"),
        "iPad14,4": ("iPad Pro 11-inch (4th generation)", "M2"),
        "iPad14,5": ("iPad Pro 12.9-inch (6th generation)", "M2"),
        "iPad14,6": ("iPad Pro 12.9-inch (6th generation)", "M2"),
        "iPad14,8": ("iPad Air 11-inch (M2)", "M2"),
        "iPad14,9": ("iPad Air 11-inch (M2)", "M2"),
        "iPad14,10": ("iPad Air 13-inch (M2)", "M2"),
        "iPad14,11": ("iPad Air 13-inch (M2)", "M2"),
        "iPad15,3": ("iPad Air 11-inch (M3)", "M3"),
        "iPad15,4": ("iPad Air 11-inch (M3)", "M3"),
        "iPad15,5": ("iPad Air 13-inch (M3)", "M3"),
        "iPad15,6": ("iPad Air 13-inch (M3)", "M3"),
        "iPad15,7": ("iPad (A16)", "A16"),
        "iPad15,8": ("iPad (A16)", "A16"),
        "iPad16,1": ("iPad mini (A17 Pro)", "A17 Pro"),
        "iPad16,2": ("iPad mini (A17 Pro)", "A17 Pro"),
        "iPad16,3": ("iPad Pro 11-inch (M4)", "M4"),
        "iPad16,4": ("iPad Pro 11-inch (M4)", "M4"),
        "iPad16,5": ("iPad Pro 13-inch (M4)", "M4"),
        "iPad16,6": ("iPad Pro 13-inch (M4)", "M4"),
    ]
}

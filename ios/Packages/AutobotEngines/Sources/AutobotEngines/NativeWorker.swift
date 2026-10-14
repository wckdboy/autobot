import Foundation
import Synchronization

/// A dedicated serial thread for blocking native work (model loads, decoding), so it never ties
/// up an actor's executor or the cooperative pool. The counterpart of Android's single-thread
/// worker executor in the engine services.
final class NativeWorker: Sendable {
    private let queue: DispatchQueue

    init(_ label: String) {
        queue = DispatchQueue(label: label, qos: .userInitiated)
    }

    /// Runs [work] on the worker; [onCancel] is invoked (from any thread) if the calling task is cancelled.
    func run<T: Sendable>(
        _ work: @escaping @Sendable () throws -> T,
        onCancel: @escaping @Sendable () -> Void = {}
    ) async throws -> T {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    continuation.resume(with: Result { try work() })
                }
            }
        } onCancel: {
            onCancel()
        }
    }
}

/// A raw native handle owned by a worker thread. Other threads may only use it through
/// [withPointer] (e.g. to cancel), which cannot overlap with [take] freeing it.
final class NativeHandle: Sendable {
    private let bits = Mutex<UInt>(0)

    var pointer: OpaquePointer? {
        bits.withLock { OpaquePointer(bitPattern: $0) }
    }

    func set(_ pointer: OpaquePointer?) {
        bits.withLock { $0 = pointer.map { UInt(bitPattern: Int(bitPattern: UnsafeRawPointer($0))) } ?? 0 }
    }

    /// Clears the handle and returns the previous pointer for the owner to free.
    func take() -> OpaquePointer? {
        bits.withLock { value in
            defer { value = 0 }
            return OpaquePointer(bitPattern: value)
        }
    }

    func withPointer(_ body: (OpaquePointer) -> Void) {
        bits.withLock { value in
            if let pointer = OpaquePointer(bitPattern: value) { body(pointer) }
        }
    }
}

/// Takes ownership of a `malloc`ed C string from an engine core.
func takeString(_ s: UnsafeMutablePointer<CChar>?, free: (UnsafeMutablePointer<CChar>?) -> Void) -> String {
    guard let s else { return "" }
    defer { free(s) }
    return String(cString: s)
}

/// Performance-core count minus headroom, like Android's `(cores - 2).coerceIn(2, 6)`.
func inferenceThreads() -> Int32 {
    var perf: Int32 = 0
    var size = MemoryLayout<Int32>.size
    if sysctlbyname("hw.perflevel0.physicalcpu", &perf, &size, nil, 0) != 0 || perf <= 0 {
        perf = Int32(ProcessInfo.processInfo.activeProcessorCount) - 2
    }
    return min(max(perf, 2), 6)
}

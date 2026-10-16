import Foundation
import Synchronization

/// Resumes a continuation once: whichever of its answers comes first; later ones are dropped.
final class ResumeOnce<T: Sendable>: Sendable {
    private let continuation = Mutex<CheckedContinuation<T, Never>?>(nil)

    func set(_ c: CheckedContinuation<T, Never>) { continuation.withLock { $0 = c } }

    func resume(_ value: T) {
        continuation.withLock { c -> CheckedContinuation<T, Never>? in
            defer { c = nil }
            return c
        }?.resume(returning: value)
    }
}

/**
 Waits for a callback API ([start] calls `done` once) for at most [limit]; then returns [late].
 A "Test" button must not spin for the transport's three-minute idle timeout when a host drops
 packets instead of refusing them. The request itself still ends on its own.
 */
func awaitCallback<T: Sendable>(
    within limit: Duration,
    late: @autoclosure () -> T,
    isolation: isolated (any Actor)? = #isolation,
    _ start: (_ done: @escaping @Sendable (T) -> Void) -> Void,
) async -> T {
    let once = ResumeOnce<T>()
    let fallback = late()
    let timer = Mutex<Task<Void, Never>?>(nil)
    let value = await withCheckedContinuation { (continuation: CheckedContinuation<T, Never>) in
        once.set(continuation)
        timer.withLock { $0 = Task { try? await Task.sleep(for: limit); if !Task.isCancelled { once.resume(fallback) } } }
        start { once.resume($0) }
    }
    timer.withLock { $0?.cancel() }
    return value
}

/// [message] with where to turn the network back on when the kill switch refused the request.
func withNetworkHint(_ message: String) -> String {
    guard message.contains("Offline mode"), !message.contains("Privacy Center") else { return message }
    return message + ". Allow a network mode in Privacy Center to reach it."
}

/// "No answer from host in 20 s": what a timed-out test says.
func noAnswerMessage(_ url: String, after limit: Duration) -> String {
    let host = URL(string: url)?.host() ?? url
    return "No answer from \(host) in \(limit.components.seconds) s. Check the address, that the server is running, and that this phone can reach it."
}

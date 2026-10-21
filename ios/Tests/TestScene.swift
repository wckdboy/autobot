import UIKit

/**
 The test host's window scene. A run of a few tests can start before the host app has connected
 its scene: wait for it (up to 10 s) instead of failing the lookup.
 */
@MainActor
enum TestScene {
    struct Missing: Error {}

    static func first() async throws -> UIWindowScene {
        for _ in 0 ..< 200 {
            if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first { return scene }
            try await Task.sleep(for: .milliseconds(50))
        }
        throw Missing()
    }
}

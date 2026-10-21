import AutobotShared
import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Renders the screens in front of the app (the biometric lock, the PIN pad with a warning and the
 first-run welcome) in dark and light. Each must lay out on a phone without crashing; with
 `AUTOBOT_SNAPSHOT_DIR` set (`TEST_RUNNER_AUTOBOT_SNAPSHOT_DIR=… xcodebuild test`) the PNGs are kept
 for review.
 */
@MainActor
struct LockSnapshotTests {
    private var dir: String? { ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] }

    @Test(.timeLimit(.minutes(2))) func renderLockAndWelcome() async throws {
        for mode in [ThemeMode.dark, .light] {
            let suffix = mode == .dark ? "dark" : "light"
            try await snapshot(LockScreen(message: "Face ID was cancelled") {}, mode, "lock-\(suffix)")
            try await snapshot(
                PinLockScreen(
                    status: LockStatus(failures: 3, remaining: KotlinInt(int: 2), warn: true, waitMillis: 0),
                    message: PanicCopy.shared.WRONG_PIN, busy: false, biometrics: true, biometryLabel: "Face ID",
                    onSubmit: { _ in }, onBiometrics: {},
                ),
                mode, "pin-\(suffix)",
            )
            try await snapshot(WelcomeScreen(onSetUp: {}, onPairPc: {}, onAddApiKey: {}), mode, "welcome-\(suffix)")
        }
    }

    private func snapshot(_ view: some View, _ mode: ThemeMode, _ name: String) async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = mode == .dark ? .dark : .light
        window.rootViewController = UIHostingController(rootView: view.autobotTheme(mode))
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(800))
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        window.isHidden = true
        let png = try #require(image.pngData())
        #expect(png.count > 1000)
        if let dir { try png.write(to: URL(fileURLWithPath: "\(dir)/\(name).png")) }
    }
}

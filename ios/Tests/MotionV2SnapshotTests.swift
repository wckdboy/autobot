import SwiftUI
import Testing
import UIKit
@testable import Autobot

/// The v2 elements in context (window snapshots, ink at rest): busy buttons and tags, bar states,
/// the block loader, the lock and welcome screens on living paper. Skipped unless
/// `AUTOBOT_SNAPSHOT_DIR` is set.
@MainActor
struct MotionV2SnapshotTests {
    @Test(.timeLimit(.minutes(5))) func renderInkV2Screens() async throws {
        guard let dir = ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] else { return }
        for mode in [ThemeMode.dark, .light] {
            let suffix = mode == .dark ? "dark" : "light"
            try await snapshot(Kit(), mode, "\(dir)/v2-kit-\(suffix).png")
            try await snapshot(LockScreen(message: nil) {}, mode, "\(dir)/v2-lock-\(suffix).png")
            try await snapshot(WelcomeScreen(onSetUp: {}, onPairPc: {}, onAddApiKey: {}), mode, "\(dir)/v2-welcome-\(suffix).png")
        }
        try await snapshot(Kit().paperTheme(), .light, "\(dir)/v2-kit-paper.png")
    }

    private struct Kit: View {
        @Environment(\.scheme) private var c

        var body: some View {
            VStack(alignment: .leading, spacing: 16) {
                AutobotTopBar(title: "Ink v2")
                HStack(spacing: 8) {
                    GhostButton("Test connection", busy: true) {}
                    PrimaryButton("Save") {}
                }
                HStack(spacing: 8) {
                    GhostButton("Import") {}
                    PrimaryButton("Export", enabled: false, busy: true) {}
                }
                HStack(spacing: 10) {
                    Tag("upscale 4×", accent: c.primary, busy: true) {}
                    Tag("loading", accent: c.primary, filled: true, busy: true)
                    Tag("benchmark", accent: c.primary, filled: true) {}
                    TextAction("Refresh", busy: true) {}
                    InkLoader()
                }
                Segmented(options: ["chat", "code", "image"], selected: "code", label: { $0 }) { _ in }
                VStack(alignment: .leading, spacing: 10) {
                    MicroLabel("downloading · 41%")
                    InkProgressBar(fraction: 0.41, running: true)
                    MicroLabel("loading the model")
                    InkProgressBar(.indeterminate, height: 2)
                    MicroLabel("paused")
                    InkProgressBar(.paused(0.63))
                    MicroLabel("failed")
                    InkProgressBar(.error(0.3))
                }
                InkLoader(.block, caption: "decrypting").frame(maxWidth: .infinity)
                Spacer()
            }
            .padding(16)
            .background(c.background.ignoresSafeArea())
        }
    }

    private func snapshot(_ view: some View, _ mode: ThemeMode, _ path: String) async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = mode == .dark ? .dark : .light
        window.rootViewController = UIHostingController(rootView: view.environment(\.inkStill, true).autobotTheme(mode))
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(1200))
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        try #require(image.pngData()).write(to: URL(fileURLWithPath: path))
        window.isHidden = true
    }
}

import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 The navigation host at the window sizes an iPad gives it: portrait and landscape, and the split
 view and Slide Over widths (compact below ~500 pt), with a chat open in the Run tab. Renders PNGs
 for review when `AUTOBOT_SNAPSHOT_DIR` is set; always checks that the host lays out within each
 width (nothing pushes the window wider).
 */
@MainActor
@Suite(.serialized)
struct AdaptiveSnapshotTests {
    private var dir: String? { ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] }

    static let sizes: [(String, CGSize, UIUserInterfaceSizeClass)] = [
        ("portrait", CGSize(width: 744, height: 1133), .regular),
        ("landscape", CGSize(width: 1133, height: 744), .regular),
        ("split-half", CGSize(width: 566, height: 744), .compact),
        ("split-third", CGSize(width: 320, height: 744), .compact),
        ("split-two-thirds", CGSize(width: 694, height: 744), .regular),
    ]

    @Test(.timeLimit(.minutes(5))) func navHostAtIPadWidths() async throws {
        let app = AppContainer()
        let nav = Navigator()
        var chat = app.conversations.create(incognito: true, providerId: nil, model: "qwen3")
        chat.title = "Reverse a list in Swift"
        app.conversations.update(chat)
        app.agent.preview(chat.id, state: ChatSnapshotTests.chatState(chat.id))
        for (name, size, sizeClass) in Self.sizes {
            for chatOpen in [false, true] {
                nav.reset(.run, chatOpen ? [.chat(conversationId: chat.id)] : [])
                let (window, fit) = try await render(AutobotNavHost(), app, nav, size, sizeClass)
                #expect(fit.width <= size.width + 0.5, "\(name): the host wants \(fit.width) pt in a \(size.width) pt window")
                if let dir {
                    let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
                        window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
                    }
                    try #require(image.pngData()).write(to: URL(fileURLWithPath: "\(dir)/ipad-\(name)\(chatOpen ? "-chat" : "").png"))
                }
                window.isHidden = true
                window.rootViewController = nil
            }
        }
        nav.reset(.run, [])
    }

    private func render(_ view: some View, _ app: AppContainer, _ nav: Navigator, _ size: CGSize, _ sizeClass: UIUserInterfaceSizeClass) async throws -> (UIWindow, CGSize) {
        let scene = try await TestScene.first()
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(origin: .zero, size: size)
        let root = view.environment(\.horizontalSizeClass, sizeClass == .compact ? .compact : .regular)
            .environment(\.scenePhase, .active).environment(app).environment(nav).autobotTheme(.light)
        let host = UIHostingController(rootView: root)
        host.traitOverrides.horizontalSizeClass = sizeClass
        window.rootViewController = host
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(1500))
        return (window, host.sizeThatFits(in: size))
    }
}

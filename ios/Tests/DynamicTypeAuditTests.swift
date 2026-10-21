import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Dynamic Type: the screens use the system text styles, which follow the iOS text-size curve by
 themselves; what is checked here is that the app's own pieces (the native kit's rows and chips,
 the title with its dot, the wordmark) hold a phone's width at the largest accessibility size,
 growing taller instead of running off the side, and that the brand marks grow with the text.
 With `AUTOBOT_SNAPSHOT_DIR` set the main screens are also rendered at AX5 into PNGs for review.
 */
@Suite(.serialized, .timeLimit(.minutes(5)))
@MainActor
struct DynamicTypeAuditTests {
    private var dir: String? { ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] }
    /// The narrowest phone the app supports (iPhone SE / mini width).
    private let width: CGFloat = 375

    // MARK: Controls hold the width

    @Test func nativeKitFitsAPhoneAtAX5() {
        let checks: [(String, AnyView)] = [
            ("labeled slider", AnyView(LabeledSlider("Denoising strength", value: .constant(0.55), in: 0 ... 1))),
            ("menu row", AnyView(Form { MenuRow("Sampler", value: "DPM++ 2M Karras", options: ["DPM++ 2M Karras", "Euler a"]) { _ in } })),
            ("form field", AnyView(Form { FormField("Base URL", text: .constant(""), prompt: "https://pc.local:7860", kind: .url) })),
            ("notice", AnyView(NoticeBanner(message: "Forge · desktop unreachable · Offline mode: network access is disabled") {})),
            ("chips", AnyView(ImagineTagFlow(spacing: 6) {
                ForEach(["1:1 · 1024×1024", "16:9 · 1368×768", "9:16 · 768×1368", "4:3 · 1176×880"], id: \.self) { Text($0).chipLook(active: false) }
            })),
            ("tags", AnyView(HStack { RouteBadge(route: .pcLan); CapsuleTag("3 live") })),
        ]
        for (name, view) in checks {
            let large = fit(view, .large)
            let ax5 = fit(view, .accessibility5)
            #expect(ax5.width <= width + 0.5, "\(name) is \(ax5.width) pt wide at AX5, over \(width)")
            #expect(ax5.height >= large.height, "\(name) shrinks at AX5: \(large.height) → \(ax5.height)")
        }
    }

    @Test func chipsWrapTallerInsteadOfClipping() {
        let view = ImagineTagFlow(spacing: 6) {
            ForEach(["1:1 · 1024×1024", "16:9 · 1368×768", "9:16 · 768×1368", "4:3 · 1176×880"], id: \.self) { Text($0).chipLook(active: false) }
        }
        #expect(fit(view, .accessibility5).height > fit(view, .large).height)
    }

    @Test func titleDotAndWordmarkGrowWithTheText() {
        let title = TitleWithDot("Gallery")
        #expect(fit(title, .accessibility5).height > fit(title, .large).height)
        #expect(fit(Wordmark(), .accessibility3).width > fit(Wordmark(), .large).width)
        #expect(fit(Wordmark(), .accessibility5).width <= width)
    }

    private func fit(_ view: some View, _ size: DynamicTypeSize) -> CGSize {
        let host = UIHostingController(rootView: view.autobotTheme(.dark).dynamicTypeSize(size))
        return host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
    }

    // MARK: Screens by eye

    @Test func renderScreensAtAX5() async throws {
        guard let dir else { return }
        let app = AppContainer()
        let nav = Navigator()
        var chat = app.conversations.create(incognito: true, providerId: nil, model: "qwen3")
        chat.title = "Reverse a list in Swift"
        app.conversations.update(chat)
        app.agent.preview(chat.id, state: ChatSnapshotTests.chatState(chat.id))
        let screens: [(String, AnyView)] = [
            ("settings", AnyView(SettingsScreen())),
            ("chat-list", AnyView(ChatListScreen())),
            ("chat", AnyView(ChatScreen(conversationId: chat.id))),
            ("lock", AnyView(LockScreen(message: nil) {})),
            ("welcome", AnyView(WelcomeScreen(onSetUp: {}, onPairPc: {}, onAddApiKey: {}))),
            ("backends", AnyView(BackendsScreen())),
            ("gallery", AnyView(GalleryScreen())),
            ("imagine", AnyView(ImagineScreen())),
            ("models", AnyView(ModelsScreen())),
            ("studio", AnyView(StudioScreen())),
        ]
        for (name, view) in screens {
            try await snapshot(view, app, nav, "\(dir)/ax5-\(name).png")
        }
    }

    private func snapshot(_ view: some View, _ app: AppContainer, _ nav: Navigator, _ path: String) async throws {
        let scene = try await TestScene.first()
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = .dark
        let root = view.background(Color.black.ignoresSafeArea()).environment(app).environment(nav)
            .autobotTheme(.dark).dynamicTypeSize(.accessibility5)
        window.rootViewController = UIHostingController(rootView: root)
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(1200))
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        try #require(image.pngData()).write(to: URL(fileURLWithPath: path))
        window.isHidden = true
    }
}

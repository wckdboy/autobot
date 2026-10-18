import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Dynamic Type: the type roles follow the iOS text-size curve (exactly the token size at the
 default `.large`, capped for display type), icons and tap targets grow with them, and the shared
 controls hold a phone's width at the largest accessibility size, growing taller instead of
 running off the side. With `AUTOBOT_SNAPSHOT_DIR` set the main screens are also rendered at
 AX5 into PNGs for review by eye.
 */
@Suite(.serialized, .timeLimit(.minutes(5)))
@MainActor
struct DynamicTypeAuditTests {
    private var dir: String? { ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] }
    /// The narrowest phone the app supports (iPhone SE / mini width).
    private let width: CGFloat = 375

    // MARK: Type scale

    @Test func defaultSizeIsTheTokenSize() {
        for style in AutobotType.allCases {
            #expect(AutobotFonts.scale(for: style.token.size, .large) == 1)
            #expect(AutobotFonts.uiFont(style.token, dynamicType: .large).pointSize == AutobotFonts.uiFont(style.token).pointSize)
        }
        #expect(AutobotFonts.iconScale(.large) == 1)
    }

    @Test func typeGrowsWithEachStepAndNeverGrowsBelowDefault() {
        let sizes: [DynamicTypeSize] = [.xSmall, .small, .medium, .large, .xLarge, .xxLarge, .xxxLarge,
                                        .accessibility1, .accessibility2, .accessibility3, .accessibility4, .accessibility5]
        for style in AutobotType.allCases {
            let points = sizes.map { AutobotFonts.uiFont(style.token, dynamicType: $0).pointSize }
            for (a, b) in zip(points, points.dropFirst()) { #expect(a <= b, "\(style) shrinks between steps: \(points)") }
            #expect(points.first! <= style.token.size, "\(style) grows at xSmall")
            #expect(points.last! > style.token.size, "\(style) does not grow at AX5")
        }
    }

    @Test func readingTypeReachesAccessibilitySizesAndDisplayTypeIsCapped() {
        // Body text at AX5 follows iOS (about 3×); hero lines stop at 1.2× so a screen keeps its content.
        let body = AutobotFonts.uiFont(AutobotType.bodyLarge.token, dynamicType: .accessibility5).pointSize
        #expect(body >= AutobotType.bodyLarge.token.size * 2.5)
        for style in AutobotType.allCases {
            let s = AutobotFonts.scale(for: style.token.size, .accessibility5)
            #expect(s <= AutobotFonts.maxScale(for: style.token.size) + 0.001, "\(style) passes its cap: \(s)")
        }
        #expect(AutobotFonts.iconScale(.accessibility5) <= 1.6)
        #expect(AutobotFonts.iconScale(.accessibility5) > 1.2)
    }

    // MARK: Controls hold the width

    @Test func sharedControlsFitAPhoneAtAX5() {
        let checks: [(String, AnyView)] = [
            ("segmented", AnyView(Segmented(options: ["Immediately", "30 seconds", "1 minute", "5 minutes"], selected: "1 minute", label: { $0 }, onSelect: { _ in }))),
            ("nav bar", AnyView(AutobotNavBar(items: Tab.allCases.map { NavItem(key: $0, label: $0.label) }, selected: Tab.run) { _ in })),
            ("navigation row", AnyView(NavigationRow(title: "Privacy Center and on-device data", value: "Offline") {})),
            ("text field", AnyView(ConsoleTextField(text: .constant(""), placeholder: "message · / for commands"))),
            ("icon actions", AnyView(HStack(spacing: 0) {
                IconAction(.mic, label: "Dictate") {}
                IconAction(.image, label: "Attach image") {}
                IconAction(.layers, label: "Layers") {}
                IconAction(.mic, label: "Voice") {}
            })),
        ]
        for (name, view) in checks {
            let large = fit(view, .large)
            let ax5 = fit(view, .accessibility5)
            #expect(ax5.width <= width + 0.5, "\(name) is \(ax5.width) pt wide at AX5, over \(width)")
            #expect(ax5.height >= large.height, "\(name) shrinks at AX5: \(large.height) → \(ax5.height)")
        }
    }

    @Test func segmentedGrowsTallerInsteadOfClipping() {
        let view = Segmented(options: ["Immediately", "30 seconds", "1 minute", "5 minutes"], selected: "1 minute", label: { $0 }, onSelect: { _ in })
        #expect(fit(view, .accessibility5).height > fit(view, .large).height)
    }

    @Test func iconActionsKeepA44PointTarget() {
        for size in [DynamicTypeSize.xSmall, .large, .accessibility5] {
            let s = fit(IconAction(.mic, label: "Dictate") {}, size)
            #expect(s.width >= 44 && s.height >= 44, "IconAction is \(s) at \(size)")
        }
    }

    private func fit(_ view: some View, _ size: DynamicTypeSize) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.inkStill, true).autobotTheme(.dark).dynamicTypeSize(size))
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
            ("models", AnyView(ModelsScreen())),
            ("studio", AnyView(StudioScreen())),
            ("nav-bar", AnyView(VStack { Spacer(); AutobotNavBar(items: Tab.allCases.map { NavItem(key: $0, label: $0.label) }, selected: Tab.run) { _ in } })),
        ]
        for (name, view) in screens {
            try await snapshot(view, app, nav, "\(dir)/ax5-\(name).png")
        }
    }

    private func snapshot(_ view: some View, _ app: AppContainer, _ nav: Navigator, _ path: String) async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = .dark
        let root = view.background(Color.black.ignoresSafeArea()).environment(app).environment(nav).environment(\.inkStill, true)
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

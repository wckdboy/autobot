import AutobotShared
import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Renders the 1.7.0 surfaces that need a cloud chat or counted usage to show (a demo install has
 neither): the policy line with the Think and REDACT chips, the receipt with its redaction and
 token rows, and a filled usage board, in both themes. Every run lays them out; with
 `AUTOBOT_SNAPSHOT_DIR` set the PNGs are kept for review. Only incognito chats are used.
 */
@Suite(.serialized, .timeLimit(.minutes(3)))
@MainActor
struct ReleaseSurfacesSnapshotTests {
    private var dir: String? { ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] }

    @Test func renderRedactionReceiptAndUsage() async throws {
        let app = AppContainer()
        let nav = Navigator()

        var chat = app.conversations.create(incognito: true, providerId: nil, model: "claude-opus-5-5")
        chat.title = "Write to Hannah"
        app.conversations.update(chat)
        app.agent.preview(chat.id, state: Self.cloudState(chat.id))

        let receipt = try Self.receipt()
        let board = try UsageBoardPreview(report: Self.report())

        for mode in [Autobot.ThemeMode.dark, .light] {
            let suffix = mode == .dark ? "dark" : "light"
            try await snapshot(ChatScreen(conversationId: chat.id), app, nav, mode, "chat-redact-\(suffix)")
            try await snapshot(ReceiptSheet(receipt: receipt) {}, app, nav, mode, "receipt-redacted-\(suffix)")
            try await snapshot(ScrollView { board.padding(16) }, app, nav, mode, "usage-board-\(suffix)")
        }
    }

    private struct UsageBoardPreview: View {
        let report: UsageReportModel
        @State private var period = "day"
        @State private var series = "day"
        @State private var byModel = false
        var body: some View {
            UsageBoardView(report: report, providers: [], periodKey: $period, seriesKey: $series, byModel: $byModel)
        }
    }

    static func cloudState(_ id: String) -> ChatSessionState {
        var s = ChatSessionState(sessionId: id)
        s.toolsEnabled = true
        s.provider = "anth"
        s.model = "claude-opus-5-5"
        s.thinking = "AUTO"
        s.thinkingSupport = "BUDGET"
        s.redaction = "ON"
        s.projection = SessionProjection(items: [
            .user(key: "e1", text: "Email Hannah Novak at hannah@example.org that the draft is ready", images: []),
            .assistant(key: "e2", text: "Done: I wrote to Hannah Novak.", reasoning: nil, interrupted: false, outputTokens: 12, model: "claude-opus-5-5"),
        ])
        return s
    }

    static func receipt() throws -> Autobot.Receipt {
        let json = #"""
        {"turn":1,"line":"1 host · 2 KB out · 3 redacted","tracked":true,
         "hosts":[{"host":"api.anthropic.com","route":"direct","requests":2,"bytesSent":2048,"bytesReceived":4096}],
         "models":[{"provider":"anth","model":"claude-opus-5-5","name":"Anthropic","onDevice":false,"policy":"no training"}],
         "redacted":true,"redactions":[{"kind":"PERSON","count":2,"label":"2 names"},{"kind":"EMAIL","count":1,"label":"1 email"}],
         "usage":"10k in · 1.0k out · 8.0k cached · $0.0296",
         "usageRows":[{"model":"claude-opus-5-5","line":"10k in · 1.0k out · 8.0k cached · $0.0296"}]}
        """#
        return try JSONDecoder().decode(Autobot.Receipt.self, from: Data(json.utf8))
    }

    static func report() throws -> UsageReportModel {
        let now: Int64 = 1_792_000_000_000
        let start = UsageLedger.companion.bucketStart(at: now - 60_000)
        func record(_ provider: String, _ model: String, _ input: Int64, _ output: Int64, onDevice: Bool = false, preset: String? = nil) -> UsageRecord {
            UsageRecord(
                start: start, span: UsageLedger.companion.FINE_SPAN, provider: provider, model: model, providerName: provider.uppercased(), presetId: preset,
                onDevice: onDevice, input: input, output: output, cacheRead: 0, cacheWrite: 0, reasoning: 0, requests: 1, unreported: 0,
            )
        }
        let records = [
            record("anth", "claude-opus-5-5", 1_000_000, 100_000, preset: AutobotShared.ProviderPresets.shared.ANTHROPIC),
            record("local", "qwen3", 20_000, 2_000, onDevice: true),
            record("go", "glm-5", 40_000, 4_000, preset: AutobotShared.ProviderPresets.shared.OPENCODE_GO),
        ]
        let kotlin = UsageBoard.shared.report(records: records, plan: UsagePlan(prices: [], windows: []), now: now, zone: ZoneRulesCompanion.shared.UTC, names: ["go": "OpenCode Go"])
        return try #require(UsageReportModel.decode(UsageJson.shared.encode(report: kotlin)))
    }

    private func snapshot(_ view: some View, _ app: AppContainer, _ nav: Navigator, _ mode: Autobot.ThemeMode, _ name: String) async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = mode == .dark ? .dark : .light
        let root = Background(content: view).environment(app).environment(nav).environment(\.inkStill, true).autobotTheme(mode)
        window.rootViewController = UIHostingController(rootView: root)
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(dir == nil ? 300 : 1500))
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        window.isHidden = true
        let png = try #require(image.pngData())
        #expect(image.size.width > 0 && png.count > 1000, "\(name)")
        if let dir { try png.write(to: URL(fileURLWithPath: "\(dir)/\(name).png")) }
    }

    private struct Background<Content: View>: View {
        let content: Content
        @Environment(\.scheme) private var c
        var body: some View { content.background(c.background.ignoresSafeArea()) }
    }
}

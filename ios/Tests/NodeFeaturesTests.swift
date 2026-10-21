import AutobotShared
import Foundation
import ImageIO
import SwiftUI
import Testing
import UIKit
@testable import Autobot

/// Invincible 0.0.14 on iOS: a bot's personality, profile and usage; photos, retry and kept notes in node chats.
@MainActor
@Suite
struct NodeFeaturesTests {
    private let now: Int64 = 1_800_000_000

    @Test func theDemoBotHasAPersonalityAProfileAndAWeek() throws {
        let d = NodeBotDetails.shared.sample(bot: "ops")
        let p = try #require(d.personalities)
        #expect(NodeBotDetails.shared.personality(p: p) == "concise")
        #expect(NodeBotDetails.shared.personalityLine(p: p) == "Short answers, no preamble.")
        #expect(d.profile?.entries.first == "Wants restarts confirmed in one line.")
        let i = try #require(d.insights)
        #expect(NodeBotDetails.shared.summary(i: i) == "46 turns · 131 model calls · 440.4k tokens · 31m 52s")
        #expect(NodeBotDetails.shared.share(i: i) == "all local")
        #expect(NodeBotDetails.shared.toolBars(i: i, max: 5).map(\.label) == ["terminal", "read_file", "web_fetch", "remember"])
        #expect(NodeBotDetails.shared.costLine(i: NodeBotDetails.shared.sample(bot: "support").insights!) == "$1.24 for cloud calls")
        #expect(NodeBotDetails.shared.profileEditCommand(bot: "ops") == "$EDITOR ~/.autobot/bots/ops/USER.md")
    }

    @Test func choosingAPersonalityShowsAtOnce() async throws {
        let model = NodeBotModel(providerId: BotsDemo.nodeId, name: "ops")
        // As the node answered (no runtime here: the choice shows and stays).
        model.show(detail: NodeBotDetails.shared.sample(bot: "ops"))
        await model.setPersonality("reviewer")
        #expect(model.detail?.personalities?.active == "reviewer")
        await model.setPersonality(NodeBotDetails.shared.NONE)
        #expect(model.detail?.personalities?.active == nil)
        #expect(model.settingPersonality == nil)
    }

    @Test func aSwitchSaysWhatTheNodeDid() {
        let words = NodeBotDetails.shared
        #expect(words.switched(name: "ops", s: BotSwitch(enabled: true, restart: false, status: "running", error: nil)) == "ops is running.")
        #expect(words.switched(name: "ops", s: BotSwitch(enabled: true, restart: false, status: "error", error: "model not found")) == "ops could not start: model not found")
        #expect(words.switched(name: "ops", s: BotSwitch(enabled: false, restart: true, status: nil, error: nil)) == "Saved. The agent on the node applies it when it restarts.")
    }

    @Test func retryIsOnTheLastAnswerOfAFinishedTurn() {
        let rows: [NodeAgentRow] = [
            .user(id: 1, text: "first"), .reply(id: 2, text: "one", route: nil),
            .user(id: 3, text: "second"), .note(id: 4, text: "run: ls", tone: .tool), .reply(id: 5, text: "two", route: nil),
            .kept(id: 6, text: "kept · memory: x"),
        ]
        #expect(NodeAgentModel.retryTarget(rows, running: false) == 5)
        #expect(NodeAgentModel.retryTarget(rows, running: true) == nil)
        let failed = Array(rows.prefix(3)) + [.note(id: 7, text: "The node did not answer.", tone: .error)]
        #expect(NodeAgentModel.retryTarget(failed, running: false) == 7)
        let asking = Array(rows.prefix(3)) + [.question(id: 8, approval: "a", title: "Run a command?", detail: "rm", answer: nil)]
        #expect(NodeAgentModel.retryTarget(asking, running: false) == nil)
        #expect(NodeAgentModel.retrying(rows) == Array(rows.prefix(3)))
    }

    @Test func theDemoChatHasAPhotoAndWhatWasKept() {
        var n = 0
        let rows = NodeChatDemo.rows(photo: NodeChatDemo.photo(width: 320, height: 240)) { n += 1; return n }
        guard case .user(_, _, let photos, let count) = rows[0] else { Issue.record("no photo row"); return }
        #expect(photos.count == 1 && count == 1)
        #expect(rows.contains(.kept(id: 4, text: "kept · " + NodeChatSample.shared.KEPT)))
    }

    @Test func photosAreDownscaledToTheNodesSide() throws {
        let big = UIGraphicsImageRenderer(size: CGSize(width: 4032, height: 3024), format: {
            let f = UIGraphicsImageRendererFormat()
            f.scale = 1
            return f
        }()).image { ctx in
            UIColor.gray.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 4032, height: 3024))
        }
        let original = try #require(big.jpegData(compressionQuality: 0.9))
        let jpeg = try #require(NodePhotos.encode(original))
        let source = try #require(CGImageSourceCreateWithData(jpeg as CFData, nil))
        let props = try #require(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        #expect(props[kCGImagePropertyPixelWidth] as? Int == 1568)
        #expect(props[kCGImagePropertyPixelHeight] as? Int == 1176)
        #expect(NodePhotos.encode(Data("not an image".utf8)) == nil)
    }

    /// Bot detail's new sections as PNGs for review, when `AUTOBOT_SNAPSHOT_DIR` is set (as ChatSnapshotTests).
    @Test(.timeLimit(.minutes(2))) func renderBotDetailSections() async throws {
        guard let dir = ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] else { return }
        for bot in ["ops", "support"] {
            for mode in [Autobot.ThemeMode.dark, .light] {
                // The sections are List sections, as on the bot screen.
                let view = List {
                    BotDetailSections(detail: NodeBotDetails.shared.sample(bot: bot), bot: bot, node: "studio-mac", setting: nil) { _ in }
                }
                .listStyle(.insetGrouped)
                try await snapshot(view, mode, "\(dir)/bot-sections-\(bot)-\(mode == .dark ? "dark" : "light").png")
            }
        }
    }

    private func snapshot(_ view: some View, _ mode: Autobot.ThemeMode, _ path: String) async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = mode == .dark ? .dark : .light
        window.rootViewController = UIHostingController(rootView: Ground(content: view).autobotTheme(mode))
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(800))
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in window.drawHierarchy(in: window.bounds, afterScreenUpdates: true) }
        try #require(image.pngData()).write(to: URL(fileURLWithPath: path))
        window.isHidden = true
    }

    private struct Ground<Content: View>: View {
        let content: Content
        var body: some View { content.background(Color(.systemBackground).ignoresSafeArea()) }
    }

    @Test func theNoKeyBannerSaysWhatAndroidSays() {
        #expect(ChatBannerWords.shared.missingKey(provider: "DeepSeek") == "DeepSeek has no API key, so messages will fail. Add it in Model providers.")
    }
}

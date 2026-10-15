import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Renders the chat screens with sample state into PNGs for visual review. Skipped unless
 `AUTOBOT_SNAPSHOT_DIR` is set (pass it as `TEST_RUNNER_AUTOBOT_SNAPSHOT_DIR=… xcodebuild test`).
 Uses incognito conversations only, so nothing is written to the encrypted store.
 */
@MainActor
struct ChatSnapshotTests {
    private var dir: String? { ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] }

    @Test func renderChatScreens() async throws {
        guard let dir else { return }
        let app = AppContainer()
        let nav = Navigator()
        nav.path = [.chatList, .chat(conversationId: "x")]

        var agent = app.conversations.create(incognito: true, providerId: nil, model: "deepseek-chat")
        agent.title = "Plan a trip to Lisbon"
        app.conversations.update(agent)
        app.agent.preview(agent.id, state: Self.agentState(agent.id))

        var chat = app.conversations.create(incognito: true, providerId: nil, model: "qwen3")
        chat.title = "Reverse a list in Swift"
        app.conversations.update(chat)
        app.agent.preview(chat.id, state: Self.chatState(chat.id))

        let empty = app.conversations.create(incognito: true, providerId: nil, model: "deepseek-chat")

        for mode in [ThemeMode.dark, .light] {
            let suffix = mode == .dark ? "dark" : "light"
            try await snapshot(ChatScreen(conversationId: agent.id), app, nav, mode, "\(dir)/chat-agent-\(suffix).png")
            try await snapshot(ChatScreen(conversationId: chat.id), app, nav, mode, "\(dir)/chat-plain-\(suffix).png")
            try await snapshot(ChatScreen(conversationId: empty.id), app, nav, mode, "\(dir)/chat-empty-\(suffix).png")
            try await snapshot(ChatListScreen(), app, nav, mode, "\(dir)/chat-list-\(suffix).png")
        }
    }

    private func snapshot(_ view: some View, _ app: AppContainer, _ nav: Navigator, _ mode: ThemeMode, _ path: String) async throws {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.overrideUserInterfaceStyle = mode == .dark ? .dark : .light
        // Ink animations render their end states, so snapshots are deterministic.
        let root = RootBackground(content: view).environment(app).environment(nav).environment(\.inkStill, true).autobotTheme(mode)
        window.rootViewController = UIHostingController(rootView: root)
        window.makeKeyAndVisible()
        try await Task.sleep(for: .milliseconds(1500))
        let image = UIGraphicsImageRenderer(bounds: window.bounds).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        try #require(image.pngData()).write(to: URL(fileURLWithPath: path))
        window.isHidden = true
    }

    private struct RootBackground<Content: View>: View {
        let content: Content
        @Environment(\.scheme) private var c
        var body: some View { content.background(c.background.ignoresSafeArea()) }
    }

    static func agentState(_ id: String) -> ChatSessionState {
        var s = ChatSessionState(sessionId: id)
        s.toolsEnabled = true
        s.running = true
        s.projection = SessionProjection(
            items: [
                .user(key: "e1", text: "Plan a 3-day trip to Lisbon and save it as notes", images: []),
                .assistant(key: "e2", text: "I'll check your notes folder first, then draft the plan.", reasoning: "The user wants a plan saved to the workspace. Look for existing notes, then write lisbon.md.", interrupted: false, outputTokens: 42, model: "deepseek-chat"),
                .tool(.init(key: "ta", callId: "a", name: "glob", summary: "notes/*.md", state: .ok, output: "notes/packing.md\nnotes/todo.md", code: nil, galleryIds: [], durationMs: 120)),
                .tool(.init(key: "tb", callId: "b", name: "web_fetch", summary: "visitlisboa.com/events", state: .denied, output: "Error: denied", code: "USER_REJECTED", galleryIds: [], durationMs: 2300)),
                .tool(.init(key: "tc", callId: "c", name: "write", summary: "notes/lisbon.md", state: .running, output: nil, code: nil, galleryIds: [], durationMs: nil)),
                .tool(.init(key: "td", callId: "d", name: "delete", summary: "notes/old-trip.md", state: .waitingApproval, output: nil, code: nil, galleryIds: [], durationMs: nil)),
                .notice(key: "e9", text: "retry 1 · rate_limit · 800 ms", kind: .info),
            ],
            todos: [TodoItem(content: "Find existing notes", status: .completed), TodoItem(content: "Draft day-by-day plan", status: .inProgress), TodoItem(content: "Save lisbon.md", status: .pending)],
            lastUsage: TokenUsage(inputTokens: 11_800, outputTokens: 420),
            contextWindow: 128_000,
        )
        s.approvals = [ApprovalState(callId: "d", toolName: "delete", summary: "notes/old-trip.md", reason: "Deletes workspace files")]
        s.live = LiveState(text: "", reasoning: "Day 1: Alfama and the castle…", toolNames: [], tokensPerSecond: 18.4)
        s.provider = "p"
        s.model = "deepseek-chat"
        return s
    }

    static func chatState(_ id: String) -> ChatSessionState {
        var s = ChatSessionState(sessionId: id)
        s.toolsEnabled = false
        s.projection = SessionProjection(
            items: [
                .user(key: "e1", text: "How do I reverse a list in Swift?", images: []),
                .assistant(key: "e2", text: "Use `reversed()` for a lazy view, or `reverse()` on a `var` array.\n\n```swift\nlet xs = [1, 2, 3]\nprint(Array(xs.reversed()))\n```\n\nThat's it.", reasoning: nil, interrupted: false, outputTokens: 48, model: "qwen3"),
                .user(key: "e3", text: "And in place?", images: []),
                .assistant(key: "e4", text: "Call `xs.reverse()` on a mutable array.", reasoning: "Short answer.", interrupted: true, outputTokens: 12, model: "qwen3"),
                .notice(key: "e5", text: "stopped", kind: .warn),
            ],
            lastUsage: TokenUsage(inputTokens: 300, outputTokens: 60),
            contextWindow: 128_000,
        )
        s.question = QuestionState(callId: "q", question: "Which Swift version do you target?", options: ["Swift 5.10", "Swift 6"], multiSelect: false)
        return s
    }
}

import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Renders the empty and error states (a refused key, a stopped answer, an approval waiting, the
 search notices, an empty chat list) in both themes. Every run renders them (a state that fails
 to lay out shows up here); with `AUTOBOT_SNAPSHOT_DIR` set the PNGs are kept for review. Only
 incognito conversations are used, so nothing reaches the encrypted store.
 */
@Suite(.serialized, .timeLimit(.minutes(3)))
@MainActor
struct StateSnapshotTests {
    private var dir: String? { ProcessInfo.processInfo.environment["AUTOBOT_SNAPSHOT_DIR"] }

    @Test func renderEmptyAndErrorStates() async throws {
        let app = AppContainer()
        let nav = Navigator()

        var failed = app.conversations.create(incognito: true, providerId: nil, model: "deepseek-chat")
        failed.title = "Summarise my notes"
        app.conversations.update(failed)
        app.agent.preview(failed.id, state: Self.errorState(failed.id))

        var waiting = app.conversations.create(incognito: true, providerId: nil, model: "deepseek-chat")
        waiting.title = "Clean up the workspace"
        app.conversations.update(waiting)
        app.agent.preview(waiting.id, state: Self.approvalState(waiting.id))

        let notices = VStack(alignment: .leading, spacing: 0) {
            ForEach([
                ModelsModel.searchNotice(hub: "Hugging Face", loading: false, hasResults: false, searched: false, error: nil, offline: true),
                ModelsModel.searchNotice(hub: "Civitai", loading: false, hasResults: false, searched: true, error: HubError(message: "The request timed out."), offline: false),
                ModelsModel.searchNotice(hub: "Hugging Face", loading: false, hasResults: false, searched: true, error: nil, offline: false),
            ].compactMap { $0 }, id: \.title) { SearchNoticeView(notice: $0) {} }
        }
        .padding(16)
        .frame(maxHeight: .infinity, alignment: .top)

        for mode in [ThemeMode.dark, .light] {
            let suffix = mode == .dark ? "dark" : "light"
            try await snapshot(ChatScreen(conversationId: failed.id), app, nav, mode, "chat-error-\(suffix)")
            try await snapshot(ChatScreen(conversationId: waiting.id), app, nav, mode, "chat-approval-\(suffix)")
            try await snapshot(notices, app, nav, mode, "models-search-notices-\(suffix)")
            try await snapshot(ChatListScreen(query: "nothing matches this"), app, nav, mode, "chat-list-no-matches-\(suffix)")
        }
    }

    private func snapshot(_ view: some View, _ app: AppContainer, _ nav: Navigator, _ mode: ThemeMode, _ name: String) async throws {
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

    static func errorState(_ id: String) -> ChatSessionState {
        var s = ChatSessionState(sessionId: id)
        s.toolsEnabled = false
        s.projection = SessionProjection(
            items: [
                .user(key: "e1", text: "Summarise my notes from this week", images: []),
                .notice(key: "e2", text: "The provider rejected the API key. Check it under Settings › Model providers", kind: .error),
                .user(key: "e3", text: "Try again", images: []),
                .assistant(key: "e4", text: "Here is what you wrote on Monday", reasoning: nil, interrupted: true, outputTokens: 9, model: "deepseek-chat"),
                .notice(key: "e5", text: "stopped", kind: .warn),
            ],
        )
        return s
    }

    static func approvalState(_ id: String) -> ChatSessionState {
        var s = ChatSessionState(sessionId: id)
        s.toolsEnabled = true
        s.running = true
        s.projection = SessionProjection(
            items: [
                .user(key: "e1", text: "Delete the old drafts", images: []),
                .tool(.init(key: "t1", callId: "c1", name: "delete", summary: "drafts/old.md", state: .waitingApproval, output: nil, code: nil, galleryIds: [], durationMs: nil)),
            ],
        )
        s.approvals = [ApprovalState(callId: "c1", toolName: "delete", summary: "drafts/old.md", reason: "Deletes workspace files")]
        return s
    }
}

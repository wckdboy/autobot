import Foundation
import Testing
@testable import Autobot

/// Port of `shared/.../session/SessionProjectionTest.kt`.
struct SessionProjectionTests {
    private func entries(_ events: SessionEvent...) -> [LogEntry] {
        events.enumerated().map { LogEntry(seq: Int64($0.offset + 1), time: 0, event: $0.element) }
    }

    @Test func projectsMessagesToolsTodosAndNotices() {
        let log = entries(
            .userMessage(text: "ctx", kind: .context, contextId: "cwd"),
            .userMessage(text: "draw a cat"),
            .requestContext(provider: "p", model: "m", contextWindow: 128_000),
            .assistantMessage(.init(
                turn: 1, step: 1, text: "", reasoning: "plan",
                toolCalls: [.init(id: "a", name: "generate_image", arguments: "{}"), .init(id: "b", name: "web_fetch", arguments: "{}"), .init(id: "c", name: "read", arguments: "{}")],
                usage: TokenUsage(inputTokens: 900, outputTokens: 100),
            )),
            .toolResult(.init(callId: "a", name: "generate_image", content: "ok", meta: .object(["gallery": .array([.string("g1")])]))),
            .toolResult(.init(callId: "b", name: "web_fetch", content: "Error: denied", isError: true, code: "USER_REJECTED")),
            .todoWrite(todos: [TodoItem(content: "draw", status: .inProgress)]),
            .turnEnd(turn: 1, reason: .error, detail: "AUTH: bad key"),
        )

        let p = projectSession(log, pendingApprovals: [], running: ["c"]) { name, _ in "args of \(name)" }

        let tools = p.items.compactMap { item -> SessionItem.Tool? in if case .tool(let t) = item { t } else { nil } }
        #expect(tools.map(\.state) == [.ok, .denied, .running])
        #expect(tools[0].galleryIds == ["g1"])
        #expect(tools[2].summary == "args of read")
        guard case .user(_, let text, _) = p.items.first else { Issue.record("first item is not a user message"); return }
        #expect(text == "draw a cat")
        let reasoning = p.items.compactMap { item -> String?? in if case .assistant(_, _, let r, _, _, _, _) = item { r } else { nil } }
        #expect(reasoning == ["plan"])
        guard case .notice = p.items.last else { Issue.record("last item is not a notice"); return }
        #expect(p.todos.count == 1)
        #expect(p.contextWindow == 128_000)
        #expect(p.lastUsage?.inputTokens == 900)
    }

    @Test func pendingApprovalWinsOverStreaming() {
        let log = entries(.assistantMessage(.init(turn: 1, step: 1, text: "", toolCalls: [.init(id: "x", name: "delete", arguments: "{}")])))
        let p = projectSession(log, pendingApprovals: ["x"], running: []) { _, _ in "" }
        guard p.items.count == 1, case .tool(let t) = p.items[0] else { Issue.record("expected one tool row"); return }
        #expect(t.state == .waitingApproval)
    }
}

import Foundation
import Testing
@testable import Autobot

/// Undo for agents on iOS: the bridge's state JSON with file changes decodes (old states too).
struct UndoTests {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(type, from: Data(json.utf8))
    }

    @Test func aFileStepWithItsDiffAndUndoStateDecodes() throws {
        let json = #"""
        {"type":"tool","key":"tw","callId":"w","name":"write","summary":"notes.md  (3 chars)","state":"OK","output":"Created /workspace/notes.md (3 bytes).",
         "code":null,"galleryIds":[],"durationMs":12,"steps":[],
         "change":{"path":"notes.md","op":"created","added":1,"removed":0,"diff":["@@ -0,0 +1,1 @@","+hi"],"truncated":false,"undoable":true,"note":null},
         "turn":3,"undone":true,"revertId":"revert-7"}
        """#
        guard case .tool(let tool) = try decode(SessionItem.self, json) else { Issue.record("expected a tool step"); return }
        #expect(tool.change == FileChange(path: "notes.md", op: .created, added: 1, diff: ["@@ -0,0 +1,1 @@", "+hi"]))
        #expect(tool.turn == 3)
        #expect(tool.isUndone)
        #expect(tool.revertId == "revert-7")
    }

    @Test func oldStepsAndAnswersWithoutUndoStateStillDecode() throws {
        let step = #"{"type":"tool","key":"t1","callId":"c","name":"read","summary":"a.md","state":"OK","output":"x","code":null,"galleryIds":[]}"#
        guard case .tool(let tool) = try decode(SessionItem.self, step) else { Issue.record("expected a tool step"); return }
        #expect(tool.change == nil)
        #expect(!tool.isUndone)
        let answer = #"{"type":"assistant","key":"e3","text":"hi","receipt":null,"turnChanges":null}"#
        guard case .assistant(_, _, _, _, _, _, _, nil, _) = try decode(SessionItem.self, answer) else { Issue.record("expected no turn changes"); return }
        // A projection from before undo has no `changes`.
        let projection = try decode(SessionProjection.self, #"{"items":[],"todos":[],"lastUsage":null,"contextWindow":null}"#)
        #expect(projection.changes.isEmpty)
    }

    @Test func turnChangesAndTheChangesSheetDecode() throws {
        let answer = #"{"type":"assistant","key":"e9","text":"done","turnChanges":{"turn":2,"files":2,"undone":true,"revertId":"revert-4","undoable":false}}"#
        guard case .assistant(_, _, _, _, _, _, _, let changes?, _) = try decode(SessionItem.self, answer) else { Issue.record("expected turn changes"); return }
        #expect(changes == TurnChanges(turn: 2, files: 2, undone: true, revertId: "revert-4", undoable: false))

        let projection = try decode(SessionProjection.self, #"""
        {"items":[],"todos":[],"lastUsage":null,"contextWindow":null,
         "changes":[{"turn":2,"files":[{"path":"a.md","op":"modified","added":2,"removed":1,"undone":false,"callId":"e1"},
                                       {"path":"b.md","op":"deleted","added":0,"removed":4,"undone":true,"callId":"d1"}]}]}
        """#)
        #expect(projection.changes.count == 1)
        #expect(projection.changes[0].files.map(\.path) == ["a.md", "b.md"])
        #expect(projection.changes[0].files[1].op == .deleted && projection.changes[0].files[1].undone)
    }

    @Test func unknownChangeOpsFallBackInsteadOfFailing() throws {
        let change = try decode(FileChange.self, #"{"path":"x","op":"renamed"}"#)
        #expect(change.op == .modified)
        #expect(change.undoable)
    }
}

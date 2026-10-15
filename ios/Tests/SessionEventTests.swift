import Foundation
import Testing
@testable import Autobot

/// The Swift mirrors read and write the same JSON lines as Kotlin's `SessionJson`.
struct SessionEventTests {
    private func decode(_ json: String) throws -> LogEntry { try SessionJSON.decode(Data(json.utf8)) }

    private func object(_ data: Data) -> [String: Any] { (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:] }

    @Test func decodesKotlinUserMessage() throws {
        let entry = try decode(#"{"seq":0,"time":1,"event":{"type":"user/message","text":"hi"}}"#)
        #expect(entry.seq == 0)
        #expect(entry.time == 1)
        #expect(entry.event == .userMessage(text: "hi"))
        #expect(entry.surface == .append)
    }

    @Test func decodesAssistantWithToolCallsAndUsage() throws {
        let entry = try decode(#"{"seq":3,"time":5,"event":{"type":"assistant/message","turn":1,"step":2,"text":"ok","reasoning":"r","toolCalls":[{"id":"c1","name":"read","arguments":"{\"path\":\"a\"}"}],"usage":{"inputTokens":10,"outputTokens":4},"model":"m"}}"#)
        guard case .assistantMessage(let m) = entry.event else { Issue.record("not an assistant message"); return }
        #expect(m.turn == 1 && m.step == 2)
        #expect(m.toolCalls == [ToolCallRecord(id: "c1", name: "read", arguments: #"{"path":"a"}"#)])
        #expect(m.usage?.inputTokens == 10)
        #expect(m.interrupted == false)
    }

    @Test func decodesToolResultMeta() throws {
        let entry = try decode(#"{"seq":4,"time":6,"event":{"type":"tool/result","callId":"c1","name":"generate_image","content":"done","meta":{"gallery":["g1","g2"]}}}"#)
        guard case .toolResult(let r) = entry.event else { Issue.record("not a tool result"); return }
        #expect(r.meta?["gallery"]?.arrayValue?.compactMap(\.stringValue) == ["g1", "g2"])
        #expect(r.isError == false)
    }

    @Test func decodesReplaceSurfaceAndUnknownTypes() throws {
        let summary = try decode(#"{"seq":9,"time":0,"event":{"type":"user/message","text":"sum","kind":"SUMMARY"},"surface":{"type":"replace","startSeq":1,"endSeq":8}}"#)
        #expect(summary.surface == .replace(startSeq: 1, endSeq: 8))
        #expect(summary.event == .userMessage(text: "sum", kind: .summary))
        let future = try decode(#"{"seq":10,"time":0,"event":{"type":"subagent/spawn","x":1}}"#)
        #expect(future.event == .unknown(type: "subagent/spawn"))
    }

    @Test func encodesWithoutDefaults() throws {
        let data = try SessionJSON.encode(LogEntry(seq: 2, time: 3, event: .userMessage(text: "x")))
        let o = object(data)
        let event = o["event"] as? [String: Any] ?? [:]
        #expect(event["type"] as? String == "user/message")
        #expect(event["kind"] == nil)
        #expect(event["images"] == nil)
        #expect(o["surface"] == nil)
    }

    @Test func roundTripsEveryCase() throws {
        let events: [SessionEvent] = [
            .turnStart(turn: 1),
            .turnEnd(turn: 1, reason: .maxTokens, detail: "d"),
            .stepStart(turn: 1, step: 0),
            .stepEnd(turn: 1, step: 0),
            .systemMessage(text: "s"),
            .userMessage(text: "u", kind: .prompt, contextId: nil, images: ["img"]),
            .assistantMessage(.init(turn: 1, step: 0, text: "a", interrupted: true)),
            .assistantAttempt(turn: 1, step: 0, code: "AUTH", message: "m"),
            .toolCall(callId: "c", name: "read", arguments: "{}"),
            .toolResult(.init(callId: "c", name: "read", content: "x", isError: true, code: "E")),
            .approvalAsked(callId: "c", toolName: "delete", reason: "r"),
            .approvalDecided(callId: "c", outcome: .allowedForSession),
            .permissionPresetChanged(preset: "read-only"),
            .agentSettingsChanged(toolsEnabled: false),
            .requestContext(provider: "p", model: "m", contextWindow: 8192),
            .llmRetry(attempt: 2, delayMs: 500, code: "RATE_LIMIT", message: "m"),
            .todoWrite(todos: [TodoItem(content: "t", status: .completed)]),
            .compactionStart(reason: "auto", estimatedTokens: 100),
            .compactionEnd(replacedEntries: 3, summaryChars: 40, error: nil),
            .sessionTitle(title: "t"),
            .commandRun(name: "compact", input: nil),
        ]
        for (i, event) in events.enumerated() {
            let entry = LogEntry(seq: Int64(i), time: 7, event: event)
            #expect(try SessionJSON.decode(SessionJSON.encode(entry)) == entry)
        }
    }

    @Test func decodesBridgeSessionState() throws {
        let json = #"""
        {"sessionId":"s1","projection":{"items":[
          {"type":"user","key":"e1","text":"hi","images":[]},
          {"type":"assistant","key":"e2","text":"hello","reasoning":null,"interrupted":false,"outputTokens":5,"model":"m"},
          {"type":"tool","key":"tc","callId":"c","name":"read","summary":"a.md","state":"WAITING_APPROVAL","output":null,"code":null,"galleryIds":[],"durationMs":null},
          {"type":"notice","key":"e3","text":"stopped","kind":"WARN"},
          {"type":"tool","key":"tt","callId":"t","name":"task","summary":"read notes","state":"OK","output":"report","code":null,"galleryIds":[],"durationMs":900,"steps":["✓ read · notes.md"]}],
         "todos":[{"content":"x","status":"in_progress"}],"lastUsage":{"inputTokens":1,"outputTokens":2,"totalTokens":null,"cacheReadTokens":null,"reasoningTokens":null},"contextWindow":8192},
         "live":{"text":"t","reasoning":"","toolNames":[],"tokensPerSecond":12.5},"running":true,
         "approvals":[{"callId":"c","toolName":"read","summary":"a.md","reason":"Network access"}],
         "question":{"callId":"q","question":"Which?","options":["a","b"],"multiSelect":false},
         "toolsEnabled":true,"permission":"workspace-write","provider":"p","model":"m"}
        """#
        let state = try JSONDecoder().decode(ChatSessionState.self, from: Data(json.utf8))
        #expect(state.projection.items.count == 5)
        #expect(state.projection.todos.first?.status == .inProgress)
        #expect(state.live?.tokensPerSecond == 12.5)
        #expect(state.approvals.first?.reason == "Network access")
        #expect(state.question?.options == ["a", "b"])
        #expect(state.permissionPreset == .workspace)
        guard case .tool(let t) = state.projection.items[2] else { Issue.record("not a tool"); return }
        #expect(t.state == .waitingApproval)
        #expect(t.steps == nil)
        guard case .tool(let task) = state.projection.items[4] else { Issue.record("not a tool"); return }
        #expect(task.steps == ["✓ read · notes.md"])
    }
}

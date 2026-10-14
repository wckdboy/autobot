import Foundation

// Swift mirror of `shared/.../session/SessionProjection.kt` and the bridge's `SessionState`
// JSON. The KMP runtime sends this state ready-made; the interim runtime computes it with
// [projectSession], a port of the Kotlin function.

enum ToolState: String, Codable, Sendable {
    case streaming = "STREAMING", waitingApproval = "WAITING_APPROVAL", running = "RUNNING", ok = "OK", error = "ERROR", denied = "DENIED"
}

enum NoticeKind: String, Codable, Sendable { case info = "INFO", warn = "WARN", error = "ERROR" }

/// One row of the session transcript.
enum SessionItem: Codable, Equatable, Identifiable, Sendable {
    case user(key: String, text: String, images: [String])
    case assistant(key: String, text: String, reasoning: String?, interrupted: Bool, outputTokens: Int?, model: String?)
    case tool(Tool)
    case notice(key: String, text: String, kind: NoticeKind)

    struct Tool: Codable, Equatable, Sendable {
        var key: String
        var callId: String
        var name: String
        var summary: String
        var state: ToolState
        var output: String?
        var code: String?
        var galleryIds: [String]
        /// Wall time from dispatch to result.
        var durationMs: Int64?
    }

    var id: String { key }

    var key: String {
        switch self {
        case .user(let k, _, _), .assistant(let k, _, _, _, _, _), .notice(let k, _, _): k
        case .tool(let t): t.key
        }
    }

    private enum K: String, CodingKey { case type, key, text, images, reasoning, interrupted, outputTokens, model, kind }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        let key = try c.decode(String.self, forKey: .key)
        switch try c.decode(String.self, forKey: .type) {
        case "user":
            self = .user(key: key, text: try c.decode(String.self, forKey: .text), images: try c.decodeIfPresent([String].self, forKey: .images) ?? [])
        case "assistant":
            self = .assistant(
                key: key,
                text: try c.decodeIfPresent(String.self, forKey: .text) ?? "",
                reasoning: try c.decodeIfPresent(String.self, forKey: .reasoning),
                interrupted: try c.decodeIfPresent(Bool.self, forKey: .interrupted) ?? false,
                outputTokens: try c.decodeIfPresent(Int.self, forKey: .outputTokens),
                model: try c.decodeIfPresent(String.self, forKey: .model),
            )
        case "tool":
            self = .tool(try Tool(from: decoder))
        default:
            self = .notice(key: key, text: try c.decodeIfPresent(String.self, forKey: .text) ?? "", kind: try c.decodeIfPresent(NoticeKind.self, forKey: .kind) ?? .info)
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        switch self {
        case .user(let key, let text, let images):
            try c.encode("user", forKey: .type)
            try c.encode(key, forKey: .key)
            try c.encode(text, forKey: .text)
            try c.encode(images, forKey: .images)
        case .assistant(let key, let text, let reasoning, let interrupted, let outputTokens, let model):
            try c.encode("assistant", forKey: .type)
            try c.encode(key, forKey: .key)
            try c.encode(text, forKey: .text)
            try c.encodeIfPresent(reasoning, forKey: .reasoning)
            try c.encode(interrupted, forKey: .interrupted)
            try c.encodeIfPresent(outputTokens, forKey: .outputTokens)
            try c.encodeIfPresent(model, forKey: .model)
        case .tool(let t):
            try c.encode("tool", forKey: .type)
            try t.encode(to: encoder)
        case .notice(let key, let text, let kind):
            try c.encode("notice", forKey: .type)
            try c.encode(key, forKey: .key)
            try c.encode(text, forKey: .text)
            try c.encode(kind, forKey: .kind)
        }
    }
}

struct SessionProjection: Codable, Equatable, Sendable {
    var items: [SessionItem] = []
    var todos: [TodoItem] = []
    var lastUsage: TokenUsage? = nil
    var contextWindow: Int? = nil
}

/// The in-flight model output of the current step.
struct LiveState: Codable, Equatable, Sendable {
    var text: String
    var reasoning: String
    var toolNames: [String]
    var tokensPerSecond: Double?
}

struct ApprovalState: Codable, Equatable, Identifiable, Sendable {
    var callId: String
    var toolName: String
    var summary: String
    var reason: String?
    var id: String { callId }
}

struct QuestionState: Codable, Equatable, Sendable {
    var callId: String
    var question: String
    var options: [String]
    var multiSelect: Bool
}

/// Everything a chat screen renders (the bridge's `SessionState`).
struct ChatSessionState: Codable, Equatable, Sendable {
    var sessionId: String
    var projection = SessionProjection()
    var live: LiveState? = nil
    var running = false
    var approvals: [ApprovalState] = []
    var question: QuestionState? = nil
    var toolsEnabled = true
    /// `read-only`, `workspace-write` or `full-access`.
    var permission = PermissionPreset.workspace.label
    var provider = ""
    var model = ""

    init(sessionId: String) { self.sessionId = sessionId }

    var permissionPreset: PermissionPreset { PermissionPreset.fromLabel(permission) ?? .workspace }
}

/**
 Projects the append-only session log into transcript rows (port of Kotlin's `projectSession`).
 - pendingApprovals: call ids currently waiting for the user.
 - running: call ids currently executing.
 - describe: one-line argument summary for a tool call.
 */
func projectSession(
    _ entries: [LogEntry],
    pendingApprovals: Set<String>,
    running: Set<String>,
    describe: (String, String) -> String,
) -> SessionProjection {
    var items: [SessionItem] = []
    var toolIndex: [String: Int] = [:]
    var callStarted: [String: Int64] = [:]
    var todos: [TodoItem] = []
    var usage: TokenUsage?
    var window: Int?
    let deniedCodes: Set<String> = ["PERMISSION_DENIED", "USER_REJECTED", "APPROVAL_UNAVAILABLE", "APPROVAL_CANCELLED"]

    for entry in entries {
        let key = "e\(entry.seq)"
        switch entry.event {
        case .userMessage(let text, let kind, _, let images):
            switch kind {
            case .prompt: items.append(.user(key: key, text: text, images: images))
            case .summary: items.append(.notice(key: key, text: "context compacted · \(text.count) chars of summary", kind: .info))
            case .context: break
            }
        case .assistantMessage(let e):
            let blank = { (s: String?) in (s ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            if !blank(e.text) || !blank(e.reasoning) {
                items.append(.assistant(key: key, text: e.text, reasoning: e.reasoning, interrupted: e.interrupted, outputTokens: e.usage?.outputTokens, model: e.model))
            }
            if let u = e.usage { usage = u }
            for call in e.toolCalls {
                toolIndex[call.id] = items.count
                let state: ToolState = pendingApprovals.contains(call.id) ? .waitingApproval : running.contains(call.id) ? .running : .streaming
                items.append(.tool(.init(key: "t\(call.id)", callId: call.id, name: call.name, summary: describe(call.name, call.arguments),
                                         state: state, output: nil, code: nil, galleryIds: [], durationMs: nil)))
            }
        case .toolCall(let callId, _, _):
            callStarted[callId] = entry.time
        case .toolResult(let r):
            guard let index = toolIndex[r.callId], case .tool(var tool) = items[index] else { continue }
            tool.state = r.code.map(deniedCodes.contains) == true ? .denied : r.isError ? .error : .ok
            tool.output = r.content
            tool.code = r.code
            tool.galleryIds = r.meta?["gallery"]?.arrayValue?.compactMap(\.stringValue) ?? []
            tool.durationMs = callStarted[r.callId].map { entry.time - $0 }.flatMap { $0 >= 0 ? $0 : nil }
            items[index] = .tool(tool)
        case .todoWrite(let t):
            todos = t
        case .turnEnd(_, let reason, let detail):
            switch reason {
            case .completed: break
            case .aborted: items.append(.notice(key: key, text: "stopped", kind: .warn))
            case .maxTokens: items.append(.notice(key: key, text: "output limit reached — say \"continue\"", kind: .warn))
            case .maxSteps: items.append(.notice(key: key, text: "step limit reached for this turn", kind: .warn))
            case .blocked: items.append(.notice(key: key, text: "blocked: \(detail ?? "")", kind: .error))
            case .error, .interrupted: items.append(.notice(key: key, text: detail ?? "turn failed", kind: .error))
            }
        case .llmRetry(let attempt, let delayMs, let code, _):
            items.append(.notice(key: key, text: "retry \(attempt) · \(code.lowercased()) · \(delayMs) ms", kind: .info))
        case .permissionPresetChanged(let preset):
            items.append(.notice(key: key, text: "permissions → \(preset)", kind: .info))
        case .agentSettingsChanged(let tools):
            items.append(.notice(key: key, text: tools ? "tools on" : "tools off · plain chat", kind: .info))
        case .compactionEnd(_, _, let error):
            if let error { items.append(.notice(key: key, text: "compaction failed: \(error)", kind: .warn)) }
        case .requestContext(_, _, let w):
            window = w
        default:
            break
        }
    }
    return SessionProjection(items: items, todos: todos, lastUsage: usage, contextWindow: window)
}

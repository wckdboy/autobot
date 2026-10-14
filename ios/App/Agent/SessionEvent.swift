import Foundation

// Codable mirrors of `agent/core/.../session/SessionEvent.kt`. The JSON is kotlinx.serialization's
// `SessionJson`: polymorphic on `"type"` with DSH-style serial names, enums by name, default
// values and nulls omitted. Unknown event types decode as `.unknown` so newer logs still load.

enum UserMessageKind: String, Codable, Sendable {
    /// Typed by the user.
    case prompt = "PROMPT"
    /// Runtime context (instructions, policies, skills catalog), wrapped in `<system-reminder>`.
    case context = "CONTEXT"
    /// A compaction summary that replaces earlier history.
    case summary = "SUMMARY"
}

enum TurnEndReason: String, Codable, Sendable {
    case completed = "COMPLETED", aborted = "ABORTED", blocked = "BLOCKED", error = "ERROR"
    case maxTokens = "MAX_TOKENS", maxSteps = "MAX_STEPS", interrupted = "INTERRUPTED"
}

enum ApprovalOutcome: String, Codable, Sendable {
    case allowedOnce = "ALLOWED_ONCE", allowedForSession = "ALLOWED_FOR_SESSION", rejected = "REJECTED", cancelled = "CANCELLED", unavailable = "UNAVAILABLE"
}

struct ToolCallRecord: Codable, Equatable, Sendable {
    var id: String
    var name: String
    var arguments: String
}

struct TokenUsage: Codable, Equatable, Sendable {
    var inputTokens: Int? = nil
    var outputTokens: Int? = nil
    var totalTokens: Int? = nil
    var cacheReadTokens: Int? = nil
    var reasoningTokens: Int? = nil
}

enum TodoStatus: String, Codable, Sendable {
    case pending, inProgress = "in_progress", completed
}

struct TodoItem: Codable, Equatable, Sendable {
    var content: String
    var status: TodoStatus
}

/// A question the agent asks the user (`ask_user_question`).
struct UserQuestion: Codable, Equatable, Sendable {
    var question: String
    var options: [String]
    var multiSelect = false
}

/// Permission presets (labels as Android's `PermissionPreset`).
enum PermissionPreset: String, Codable, CaseIterable, Sendable {
    case readOnly = "READ_ONLY", workspace = "WORKSPACE", fullAccess = "FULL_ACCESS"

    var label: String {
        switch self {
        case .readOnly: "read-only"
        case .workspace: "workspace-write"
        case .fullAccess: "full-access"
        }
    }

    static func fromLabel(_ label: String) -> PermissionPreset? {
        allCases.first { $0.label == label.lowercased() } ?? allCases.first { $0.rawValue.caseInsensitiveCompare(label) == .orderedSame }
    }
}

/**
 Durable session events. The log is the single source of truth: model-visible means logged, and
 the request history is derived from it.
 */
enum SessionEvent: Equatable, Sendable {
    case turnStart(turn: Int)
    case turnEnd(turn: Int, reason: TurnEndReason, detail: String?)
    case stepStart(turn: Int, step: Int)
    case stepEnd(turn: Int, step: Int)
    case systemMessage(text: String)
    case userMessage(text: String, kind: UserMessageKind = .prompt, contextId: String? = nil, images: [String] = [])
    case assistantMessage(AssistantMessage)
    case assistantAttempt(turn: Int, step: Int, code: String, message: String)
    case toolCall(callId: String, name: String, arguments: String)
    case toolResult(ToolResult)
    case approvalAsked(callId: String, toolName: String, reason: String?)
    case approvalDecided(callId: String, outcome: ApprovalOutcome)
    case permissionPresetChanged(preset: String)
    case agentSettingsChanged(toolsEnabled: Bool)
    case requestContext(provider: String, model: String, contextWindow: Int?)
    case llmRetry(attempt: Int, delayMs: Int64, code: String, message: String)
    case todoWrite(todos: [TodoItem])
    case compactionStart(reason: String, estimatedTokens: Int)
    case compactionEnd(replacedEntries: Int, summaryChars: Int, error: String?)
    case sessionTitle(title: String)
    case commandRun(name: String, input: String?)
    /// An event type this build does not know (kept so the log still loads).
    case unknown(type: String)

    struct AssistantMessage: Codable, Equatable, Sendable {
        var turn: Int
        var step: Int
        var text: String
        var reasoning: String? = nil
        var toolCalls: [ToolCallRecord] = []
        var usage: TokenUsage? = nil
        var model: String? = nil
        /// Cut short by cancellation; kept so the next request contains what the user saw.
        var interrupted = false

        enum CodingKeys: String, CodingKey { case turn, step, text, reasoning, toolCalls, usage, model, interrupted }

        init(turn: Int, step: Int, text: String, reasoning: String? = nil, toolCalls: [ToolCallRecord] = [], usage: TokenUsage? = nil, model: String? = nil, interrupted: Bool = false) {
            self.turn = turn
            self.step = step
            self.text = text
            self.reasoning = reasoning
            self.toolCalls = toolCalls
            self.usage = usage
            self.model = model
            self.interrupted = interrupted
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            turn = try c.decodeIfPresent(Int.self, forKey: .turn) ?? 0
            step = try c.decodeIfPresent(Int.self, forKey: .step) ?? 0
            text = try c.decodeIfPresent(String.self, forKey: .text) ?? ""
            reasoning = try c.decodeIfPresent(String.self, forKey: .reasoning)
            toolCalls = try c.decodeIfPresent([ToolCallRecord].self, forKey: .toolCalls) ?? []
            usage = try c.decodeIfPresent(TokenUsage.self, forKey: .usage)
            model = try c.decodeIfPresent(String.self, forKey: .model)
            interrupted = try c.decodeIfPresent(Bool.self, forKey: .interrupted) ?? false
        }

        func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(turn, forKey: .turn)
            try c.encode(step, forKey: .step)
            try c.encode(text, forKey: .text)
            try c.encodeIfPresent(reasoning, forKey: .reasoning)
            if !toolCalls.isEmpty { try c.encode(toolCalls, forKey: .toolCalls) }
            try c.encodeIfPresent(usage, forKey: .usage)
            try c.encodeIfPresent(model, forKey: .model)
            if interrupted { try c.encode(true, forKey: .interrupted) }
        }
    }

    /// Tool result. `meta` is free-form JSON (e.g. `{"gallery": ["<id>"]}`); kept as JSON text.
    struct ToolResult: Equatable, Sendable {
        var callId: String
        var name: String
        var content: String
        var isError = false
        var code: String? = nil
        var meta: JSONValue? = nil
    }

    /// The serial name (`area/verb`).
    var type: String {
        switch self {
        case .turnStart: "turn/start"
        case .turnEnd: "turn/end"
        case .stepStart: "step/start"
        case .stepEnd: "step/end"
        case .systemMessage: "system/message"
        case .userMessage: "user/message"
        case .assistantMessage: "assistant/message"
        case .assistantAttempt: "assistant/attempt"
        case .toolCall: "tool/call"
        case .toolResult: "tool/result"
        case .approvalAsked: "approval/asked"
        case .approvalDecided: "approval/decided"
        case .permissionPresetChanged: "permission/preset"
        case .agentSettingsChanged: "agent/config"
        case .requestContext: "request/context"
        case .llmRetry: "llm/retry"
        case .todoWrite: "todo/write"
        case .compactionStart: "compaction/start"
        case .compactionEnd: "compaction/end"
        case .sessionTitle: "session/title"
        case .commandRun: "command/run"
        case .unknown(let type): type
        }
    }
}

extension SessionEvent: Codable {
    private enum K: String, CodingKey {
        case type, turn, reason, detail, step, text, kind, contextId, images, code, message, callId, name, arguments, content
        case isError, meta, toolName, outcome, preset, toolsEnabled, provider, model, contextWindow, attempt, delayMs, todos
        case estimatedTokens, replacedEntries, summaryChars, error, title, input
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        let type = try c.decode(String.self, forKey: .type)
        func int(_ k: K) throws -> Int { try c.decodeIfPresent(Int.self, forKey: k) ?? 0 }
        func str(_ k: K) throws -> String { try c.decodeIfPresent(String.self, forKey: k) ?? "" }
        switch type {
        case "turn/start": self = .turnStart(turn: try int(.turn))
        case "turn/end":
            self = .turnEnd(turn: try int(.turn), reason: try c.decode(TurnEndReason.self, forKey: .reason), detail: try c.decodeIfPresent(String.self, forKey: .detail))
        case "step/start": self = .stepStart(turn: try int(.turn), step: try int(.step))
        case "step/end": self = .stepEnd(turn: try int(.turn), step: try int(.step))
        case "system/message": self = .systemMessage(text: try str(.text))
        case "user/message":
            self = .userMessage(
                text: try str(.text),
                kind: try c.decodeIfPresent(UserMessageKind.self, forKey: .kind) ?? .prompt,
                contextId: try c.decodeIfPresent(String.self, forKey: .contextId),
                images: try c.decodeIfPresent([String].self, forKey: .images) ?? [],
            )
        case "assistant/message": self = .assistantMessage(try AssistantMessage(from: decoder))
        case "assistant/attempt": self = .assistantAttempt(turn: try int(.turn), step: try int(.step), code: try str(.code), message: try str(.message))
        case "tool/call": self = .toolCall(callId: try str(.callId), name: try str(.name), arguments: try str(.arguments))
        case "tool/result":
            self = .toolResult(ToolResult(
                callId: try str(.callId),
                name: try str(.name),
                content: try str(.content),
                isError: try c.decodeIfPresent(Bool.self, forKey: .isError) ?? false,
                code: try c.decodeIfPresent(String.self, forKey: .code),
                meta: try c.decodeIfPresent(JSONValue.self, forKey: .meta),
            ))
        case "approval/asked": self = .approvalAsked(callId: try str(.callId), toolName: try str(.toolName), reason: try c.decodeIfPresent(String.self, forKey: .reason))
        case "approval/decided": self = .approvalDecided(callId: try str(.callId), outcome: try c.decode(ApprovalOutcome.self, forKey: .outcome))
        case "permission/preset": self = .permissionPresetChanged(preset: try str(.preset))
        case "agent/config": self = .agentSettingsChanged(toolsEnabled: try c.decodeIfPresent(Bool.self, forKey: .toolsEnabled) ?? true)
        case "request/context":
            self = .requestContext(provider: try str(.provider), model: try str(.model), contextWindow: try c.decodeIfPresent(Int.self, forKey: .contextWindow))
        case "llm/retry":
            self = .llmRetry(attempt: try int(.attempt), delayMs: try c.decodeIfPresent(Int64.self, forKey: .delayMs) ?? 0, code: try str(.code), message: try str(.message))
        case "todo/write": self = .todoWrite(todos: try c.decodeIfPresent([TodoItem].self, forKey: .todos) ?? [])
        case "compaction/start": self = .compactionStart(reason: try str(.reason), estimatedTokens: try int(.estimatedTokens))
        case "compaction/end":
            self = .compactionEnd(replacedEntries: try int(.replacedEntries), summaryChars: try int(.summaryChars), error: try c.decodeIfPresent(String.self, forKey: .error))
        case "session/title": self = .sessionTitle(title: try str(.title))
        case "command/run": self = .commandRun(name: try str(.name), input: try c.decodeIfPresent(String.self, forKey: .input))
        default: self = .unknown(type: type)
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(type, forKey: .type)
        switch self {
        case .turnStart(let turn):
            try c.encode(turn, forKey: .turn)
        case .turnEnd(let turn, let reason, let detail):
            try c.encode(turn, forKey: .turn)
            try c.encode(reason, forKey: .reason)
            try c.encodeIfPresent(detail, forKey: .detail)
        case .stepStart(let turn, let step), .stepEnd(let turn, let step):
            try c.encode(turn, forKey: .turn)
            try c.encode(step, forKey: .step)
        case .systemMessage(let text):
            try c.encode(text, forKey: .text)
        case .userMessage(let text, let kind, let contextId, let images):
            try c.encode(text, forKey: .text)
            if kind != .prompt { try c.encode(kind, forKey: .kind) }
            try c.encodeIfPresent(contextId, forKey: .contextId)
            if !images.isEmpty { try c.encode(images, forKey: .images) }
        case .assistantMessage(let m):
            try m.encode(to: encoder)
        case .assistantAttempt(let turn, let step, let code, let message):
            try c.encode(turn, forKey: .turn)
            try c.encode(step, forKey: .step)
            try c.encode(code, forKey: .code)
            try c.encode(message, forKey: .message)
        case .toolCall(let callId, let name, let arguments):
            try c.encode(callId, forKey: .callId)
            try c.encode(name, forKey: .name)
            try c.encode(arguments, forKey: .arguments)
        case .toolResult(let r):
            try c.encode(r.callId, forKey: .callId)
            try c.encode(r.name, forKey: .name)
            try c.encode(r.content, forKey: .content)
            if r.isError { try c.encode(true, forKey: .isError) }
            try c.encodeIfPresent(r.code, forKey: .code)
            try c.encodeIfPresent(r.meta, forKey: .meta)
        case .approvalAsked(let callId, let toolName, let reason):
            try c.encode(callId, forKey: .callId)
            try c.encode(toolName, forKey: .toolName)
            try c.encodeIfPresent(reason, forKey: .reason)
        case .approvalDecided(let callId, let outcome):
            try c.encode(callId, forKey: .callId)
            try c.encode(outcome, forKey: .outcome)
        case .permissionPresetChanged(let preset):
            try c.encode(preset, forKey: .preset)
        case .agentSettingsChanged(let toolsEnabled):
            try c.encode(toolsEnabled, forKey: .toolsEnabled)
        case .requestContext(let provider, let model, let window):
            try c.encode(provider, forKey: .provider)
            try c.encode(model, forKey: .model)
            try c.encodeIfPresent(window, forKey: .contextWindow)
        case .llmRetry(let attempt, let delayMs, let code, let message):
            try c.encode(attempt, forKey: .attempt)
            try c.encode(delayMs, forKey: .delayMs)
            try c.encode(code, forKey: .code)
            try c.encode(message, forKey: .message)
        case .todoWrite(let todos):
            try c.encode(todos, forKey: .todos)
        case .compactionStart(let reason, let tokens):
            try c.encode(reason, forKey: .reason)
            try c.encode(tokens, forKey: .estimatedTokens)
        case .compactionEnd(let replaced, let chars, let error):
            try c.encode(replaced, forKey: .replacedEntries)
            try c.encode(chars, forKey: .summaryChars)
            try c.encodeIfPresent(error, forKey: .error)
        case .sessionTitle(let title):
            try c.encode(title, forKey: .title)
        case .commandRun(let name, let input):
            try c.encode(name, forKey: .name)
            try c.encodeIfPresent(input, forKey: .input)
        case .unknown:
            break
        }
    }
}

/// How an entry changes the model-visible surface. Compaction uses `.replace`.
enum SurfaceOp: Equatable, Sendable, Codable {
    case append
    /// This entry stands in for entries `startSeq...endSeq`.
    case replace(startSeq: Int64, endSeq: Int64)

    private enum K: String, CodingKey { case type, startSeq, endSeq }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        if try c.decode(String.self, forKey: .type) == "replace" {
            self = .replace(startSeq: try c.decode(Int64.self, forKey: .startSeq), endSeq: try c.decode(Int64.self, forKey: .endSeq))
        } else {
            self = .append
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        switch self {
        case .append: try c.encode("append", forKey: .type)
        case .replace(let s, let e):
            try c.encode("replace", forKey: .type)
            try c.encode(s, forKey: .startSeq)
            try c.encode(e, forKey: .endSeq)
        }
    }
}

/// One persisted log row. [seq] is dense and strictly increasing within a session.
struct LogEntry: Codable, Equatable, Sendable {
    var seq: Int64
    var time: Int64
    var event: SessionEvent
    var surface: SurfaceOp = .append

    init(seq: Int64, time: Int64, event: SessionEvent, surface: SurfaceOp = .append) {
        self.seq = seq
        self.time = time
        self.event = event
        self.surface = surface
    }

    private enum K: String, CodingKey { case seq, time, event, surface }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: K.self)
        seq = try c.decode(Int64.self, forKey: .seq)
        time = try c.decodeIfPresent(Int64.self, forKey: .time) ?? 0
        event = try c.decode(SessionEvent.self, forKey: .event)
        surface = try c.decodeIfPresent(SurfaceOp.self, forKey: .surface) ?? .append
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: K.self)
        try c.encode(seq, forKey: .seq)
        try c.encode(time, forKey: .time)
        try c.encode(event, forKey: .event)
        if surface != .append { try c.encode(surface, forKey: .surface) }
    }
}

/// Untyped JSON (tool result `meta`).
indirect enum JSONValue: Codable, Equatable, Sendable {
    case null, bool(Bool), number(Double), string(String), array([JSONValue]), object([String: JSONValue])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSONValue].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSONValue].self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n): try c.encode(n)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    subscript(key: String) -> JSONValue? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }

    var arrayValue: [JSONValue]? {
        if case .array(let a) = self { return a }
        return nil
    }
}

/// Session log codec (same JSON as Kotlin's `SessionJson`).
enum SessionJSON {
    static func encode(_ entry: LogEntry) throws -> Data { try JSONEncoder.store.encode(entry) }
    static func decode(_ data: Data) throws -> LogEntry { try JSONDecoder().decode(LogEntry.self, from: data) }
}

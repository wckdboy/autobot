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
    /// [receipt]: the turn's privacy receipt, on the turn's last answer once the turn ended;
    /// [turnChanges]: the files the turn changed ("Undo turn"), likewise.
    case assistant(key: String, text: String, reasoning: String?, interrupted: Bool, outputTokens: Int?, model: String?, receipt: Receipt? = nil, turnChanges: TurnChanges? = nil)
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
        /// A subagent's own tool steps (`task`), one line each: `✓ read · notes.md`.
        var steps: [String]? = nil
        /// What a file tool did to its file (line diff), from the result's `meta.change`.
        var change: FileChange? = nil
        /// The turn the call belongs to.
        var turn: Int? = nil
        /// The user undid this step's change (directly, by undoing its turn, or an earlier step).
        var undone: Bool? = nil
        /// The revert that undid it, for "Redo".
        var revertId: String? = nil

        var isUndone: Bool { undone ?? false }
    }

    var id: String { key }

    var key: String {
        switch self {
        case .user(let k, _, _), .assistant(let k, _, _, _, _, _, _, _), .notice(let k, _, _): k
        case .tool(let t): t.key
        }
    }

    private enum K: String, CodingKey { case type, key, text, images, reasoning, interrupted, outputTokens, model, receipt, turnChanges, kind }

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
                receipt: try c.decodeIfPresent(Receipt.self, forKey: .receipt),
                turnChanges: try c.decodeIfPresent(TurnChanges.self, forKey: .turnChanges),
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
        case .assistant(let key, let text, let reasoning, let interrupted, let outputTokens, let model, let receipt, let turnChanges):
            try c.encode("assistant", forKey: .type)
            try c.encode(key, forKey: .key)
            try c.encode(text, forKey: .text)
            try c.encodeIfPresent(reasoning, forKey: .reasoning)
            try c.encode(interrupted, forKey: .interrupted)
            try c.encodeIfPresent(outputTokens, forKey: .outputTokens)
            try c.encodeIfPresent(model, forKey: .model)
            try c.encodeIfPresent(receipt, forKey: .receipt)
            try c.encodeIfPresent(turnChanges, forKey: .turnChanges)
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

/**
 A turn's privacy receipt (Kotlin `Receipt`, built by the shared runtime from the session and
 audit logs; never sent anywhere): [line] under the answer, the rest in the receipt sheet. Every
 field decodes leniently so receipts from newer or older builds still load.
 */
struct Receipt: Codable, Equatable, Sendable {
    /// Requests of the turn to one host over one route (`direct`, `tor`, `socks5`, `loopback`).
    struct Host: Codable, Equatable, Sendable {
        var host: String
        var route: String
        var requests = 0
        var bytesSent: Int64 = 0
        var bytesReceived: Int64 = 0
        var blocked = 0
        var tags: [String] = []

        init(host: String, route: String, requests: Int, bytesSent: Int64 = 0, bytesReceived: Int64 = 0, blocked: Int = 0, tags: [String] = []) {
            (self.host, self.route, self.requests, self.bytesSent, self.bytesReceived, self.blocked, self.tags) = (host, route, requests, bytesSent, bytesReceived, blocked, tags)
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            host = try c.decode(String.self, forKey: .host)
            route = try c.decodeIfPresent(String.self, forKey: .route) ?? "direct"
            requests = try c.decodeIfPresent(Int.self, forKey: .requests) ?? 0
            bytesSent = try c.decodeIfPresent(Int64.self, forKey: .bytesSent) ?? 0
            bytesReceived = try c.decodeIfPresent(Int64.self, forKey: .bytesReceived) ?? 0
            blocked = try c.decodeIfPresent(Int.self, forKey: .blocked) ?? 0
            tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
        }
    }

    /// A model that answered, with its provider and data policy.
    struct Model: Codable, Equatable, Sendable {
        var provider: String
        var model: String
        var name: String? = nil
        var onDevice = false
        var policy: String? = nil
        var policyNote: String? = nil

        init(provider: String, model: String, name: String? = nil, onDevice: Bool = false, policy: String? = nil, policyNote: String? = nil) {
            (self.provider, self.model, self.name, self.onDevice, self.policy, self.policyNote) = (provider, model, name, onDevice, policy, policyNote)
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            provider = try c.decodeIfPresent(String.self, forKey: .provider) ?? ""
            model = try c.decodeIfPresent(String.self, forKey: .model) ?? ""
            name = try c.decodeIfPresent(String.self, forKey: .name)
            onDevice = try c.decodeIfPresent(Bool.self, forKey: .onDevice) ?? false
            policy = try c.decodeIfPresent(String.self, forKey: .policy)
            policyNote = try c.decodeIfPresent(String.self, forKey: .policyNote)
        }
    }

    /// A tool that ran; [network] tools (`web_fetch`, `web_search`, MCP) reach other hosts.
    struct Tool: Codable, Equatable, Sendable {
        var name: String
        var network = false

        init(name: String, network: Bool = false) {
            self.name = name
            self.network = network
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            name = try c.decode(String.self, forKey: .name)
            network = try c.decodeIfPresent(Bool.self, forKey: .network) ?? false
        }
    }

    var turn: Int
    /// `on device`, or `2 hosts · 14 KB out · no retention`.
    var line: String
    /// Nothing left the phone (traffic was tracked and stayed on it).
    var onDevice = false
    var tracked = false
    var hosts: [Host] = []
    var bytesSent: Int64 = 0
    var bytesReceived: Int64 = 0
    var routes: [String] = []
    var models: [Model] = []
    var tools: [Tool] = []
    var fallbacks: [String] = []

    init(turn: Int, line: String, onDevice: Bool = false, tracked: Bool = false, hosts: [Host] = [], bytesSent: Int64 = 0, bytesReceived: Int64 = 0,
         routes: [String] = [], models: [Model] = [], tools: [Tool] = [], fallbacks: [String] = []) {
        (self.turn, self.line, self.onDevice, self.tracked, self.hosts, self.bytesSent, self.bytesReceived) = (turn, line, onDevice, tracked, hosts, bytesSent, bytesReceived)
        (self.routes, self.models, self.tools, self.fallbacks) = (routes, models, tools, fallbacks)
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        turn = try c.decodeIfPresent(Int.self, forKey: .turn) ?? 0
        line = try c.decodeIfPresent(String.self, forKey: .line) ?? "receipt"
        onDevice = try c.decodeIfPresent(Bool.self, forKey: .onDevice) ?? false
        tracked = try c.decodeIfPresent(Bool.self, forKey: .tracked) ?? false
        hosts = try c.decodeIfPresent([Host].self, forKey: .hosts) ?? []
        bytesSent = try c.decodeIfPresent(Int64.self, forKey: .bytesSent) ?? 0
        bytesReceived = try c.decodeIfPresent(Int64.self, forKey: .bytesReceived) ?? 0
        routes = try c.decodeIfPresent([String].self, forKey: .routes) ?? []
        models = try c.decodeIfPresent([Model].self, forKey: .models) ?? []
        tools = try c.decodeIfPresent([Tool].self, forKey: .tools) ?? []
        fallbacks = try c.decodeIfPresent([String].self, forKey: .fallbacks) ?? []
    }

    /// `0 B`, `512 B`, `14 KB`, `1.2 MB` (Kotlin `Receipts.formatBytes`).
    static func formatBytes(_ bytes: Int64) -> String {
        if bytes < 1024 { return "\(bytes) B" }
        if bytes < 1024 * 1024 { return "\(Int64((Double(bytes) / 1024).rounded())) KB" }
        let tenths = Int64((Double(bytes) * 10 / (1024 * 1024)).rounded())
        return "\(tenths / 10).\(tenths % 10) MB"
    }
}

extension [SessionItem] {
    /// The items with each key once (the first wins): `ForEach` needs unique ids, and a model
    /// that repeats a tool-call id would otherwise give two rows the same one.
    var uniqueKeys: [SessionItem] {
        var seen = Set<String>()
        seen.reserveCapacity(count)
        guard contains(where: { !seen.insert($0.key).inserted }) else { return self }
        seen.removeAll(keepingCapacity: true)
        return filter { seen.insert($0.key).inserted }
    }
}

enum ChangeOp: String, Codable, Sendable { case created, modified, deleted }

/**
 What one file tool call did to one workspace file (Kotlin `FileChange`): unified-diff [diff]
 lines (`@@ …`, ` ctx`, `-old`, `+new`), counts, and whether the undo journal kept a copy.
 Decodes leniently so results from older or newer builds still load.
 */
struct FileChange: Codable, Equatable, Sendable {
    var path: String
    var op: ChangeOp
    var added = 0
    var removed = 0
    var diff: [String] = []
    var truncated = false
    var undoable = true
    var note: String? = nil

    init(path: String, op: ChangeOp, added: Int = 0, removed: Int = 0, diff: [String] = [], truncated: Bool = false, undoable: Bool = true, note: String? = nil) {
        (self.path, self.op, self.added, self.removed, self.diff, self.truncated, self.undoable, self.note) = (path, op, added, removed, diff, truncated, undoable, note)
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        path = try c.decode(String.self, forKey: .path)
        op = (try? c.decode(ChangeOp.self, forKey: .op)) ?? .modified
        added = try c.decodeIfPresent(Int.self, forKey: .added) ?? 0
        removed = try c.decodeIfPresent(Int.self, forKey: .removed) ?? 0
        diff = try c.decodeIfPresent([String].self, forKey: .diff) ?? []
        truncated = try c.decodeIfPresent(Bool.self, forKey: .truncated) ?? false
        undoable = try c.decodeIfPresent(Bool.self, forKey: .undoable) ?? true
        note = try c.decodeIfPresent(String.self, forKey: .note)
    }
}

/// Undo state of a turn, on its last answer: "Undo turn (2 files)" or "Redo turn".
struct TurnChanges: Codable, Equatable, Sendable {
    var turn: Int
    var files: Int
    var undone = false
    var revertId: String? = nil
    var undoable = true

    init(turn: Int, files: Int, undone: Bool = false, revertId: String? = nil, undoable: Bool = true) {
        (self.turn, self.files, self.undone, self.revertId, self.undoable) = (turn, files, undone, revertId, undoable)
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        turn = try c.decodeIfPresent(Int.self, forKey: .turn) ?? 0
        files = try c.decodeIfPresent(Int.self, forKey: .files) ?? 0
        undone = try c.decodeIfPresent(Bool.self, forKey: .undone) ?? false
        revertId = try c.decodeIfPresent(String.self, forKey: .revertId)
        undoable = try c.decodeIfPresent(Bool.self, forKey: .undoable) ?? true
    }
}

/// One file in the "changes" sheet, and the files one turn changed.
struct ChangedFile: Codable, Equatable, Sendable, Identifiable {
    var path: String
    var op: ChangeOp
    var added: Int
    var removed: Int
    var undone: Bool
    var callId: String
    var id: String { callId + path }
}

struct ChangeGroup: Codable, Equatable, Sendable, Identifiable {
    var turn: Int
    var files: [ChangedFile]
    var id: Int { turn }
}

struct SessionProjection: Codable, Equatable, Sendable {
    var items: [SessionItem] = []
    var todos: [TodoItem] = []
    var lastUsage: TokenUsage? = nil
    var contextWindow: Int? = nil
    /// Files the agent changed in this session, per turn (absent from older states).
    var changes: [ChangeGroup] = []

    init(items: [SessionItem] = [], todos: [TodoItem] = [], lastUsage: TokenUsage? = nil, contextWindow: Int? = nil, changes: [ChangeGroup] = []) {
        (self.items, self.todos, self.lastUsage, self.contextWindow, self.changes) = (items, todos, lastUsage, contextWindow, changes)
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        items = try c.decodeIfPresent([SessionItem].self, forKey: .items) ?? []
        todos = try c.decodeIfPresent([TodoItem].self, forKey: .todos) ?? []
        lastUsage = try c.decodeIfPresent(TokenUsage.self, forKey: .lastUsage)
        contextWindow = try c.decodeIfPresent(Int.self, forKey: .contextWindow)
        changes = try c.decodeIfPresent([ChangeGroup].self, forKey: .changes) ?? []
    }
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
    /// Private fragments the call would send (taint tracking); the card highlights them.
    var privateData: [String]? = nil
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

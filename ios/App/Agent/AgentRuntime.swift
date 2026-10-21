import Foundation
import Observation

/// Session settings the chat screen shows (tools, permission preset, route).
struct SessionSettings: Equatable, Sendable {
    var toolsEnabled = true
    /// `read-only`, `workspace-write` or `full-access`.
    var permission = PermissionPreset.workspace.label
    var provider = ""
    var model = ""
    /// The chat's Think switch, and whether its model reasons ("NONE", "SWITCH", "ALWAYS": the chip shows unless NONE).
    var thinking = ThinkingChoice()
    var thinkingSupport = "NONE"
    /// The redaction gateway for the chat's route: ON, OFF, or NONE (on-device or loopback: nothing to hide).
    var redaction = "NONE"
    /// The local-first cascade policy of a plain cloud chat (OFF, ASK, AUTO, NEVER), NONE where it cannot apply.
    var cascade = "NONE"

    var permissionPreset: PermissionPreset { PermissionPreset.fromLabel(permission) ?? .workspace }
}

/**
 A projected transcript with what the chat screen derives from it, computed once per projection
 change (off the main thread for the shared runtime) instead of on every render.
 */
struct SessionTranscript: Equatable, Sendable {
    let projection: SessionProjection
    /// [projection]'s items with each key once (`ForEach` ids).
    let items: [SessionItem]
    /// Item keys in order.
    let keys: [String]
    /// Tool step numbers by item key (1-based; a repeated key keeps its first number).
    let stepNumbers: [String: Int]
    /// Key of the first prompt (it renders as the run header).
    let firstPrompt: String?
    /// Tokens in the context after the last call.
    let contextUsed: Int?

    init(_ projection: SessionProjection) {
        self.projection = projection
        items = projection.items.uniqueKeys
        keys = items.map(\.key)
        var numbers: [String: Int] = [:]
        var n = 0
        for item in projection.items {
            guard case .tool(let t) = item else { continue }
            n += 1
            if numbers[t.key] == nil { numbers[t.key] = n }
        }
        stepNumbers = numbers
        firstPrompt = projection.items.first { if case .user = $0 { true } else { false } }?.key
        contextUsed = projection.lastUsage.map { ($0.inputTokens ?? 0) + ($0.cacheReadTokens ?? 0) + ($0.outputTokens ?? 0) }
    }

    static func == (a: Self, b: Self) -> Bool { a.projection == b.projection }
}

/**
 One state change of a session, as the runtime delivers it: [transcript] is nil when the
 projection did not change (a streamed chunk), so the transcript and everything rendered from it
 stay untouched.
 */
struct SessionUpdate: Sendable {
    var transcript: SessionTranscript?
    var live: LiveState?
    var running: Bool
    var approvals: [ApprovalState]
    var question: QuestionState?
    var settings: SessionSettings

    init(transcript: SessionTranscript?, live: LiveState?, running: Bool, approvals: [ApprovalState], question: QuestionState?, settings: SessionSettings) {
        (self.transcript, self.live, self.running, self.approvals, self.question, self.settings) = (transcript, live, running, approvals, question, settings)
    }

    /// Only a new transcript: everything else stays as it is.
    init(transcriptOnly transcript: SessionTranscript) {
        self.init(transcript: transcript, live: nil, running: false, approvals: [], question: nil, settings: SessionSettings())
        onlyTranscript = true
    }

    /// Set by [init(transcriptOnly:)].
    private(set) var onlyTranscript = false

    init(_ state: ChatSessionState) {
        self.init(
            transcript: SessionTranscript(state.projection), live: state.live, running: state.running, approvals: state.approvals, question: state.question,
            settings: SessionSettings(
                toolsEnabled: state.toolsEnabled, permission: state.permission, provider: state.provider, model: state.model,
                thinking: ThinkingChoice(mode: state.thinking ?? "AUTO", budget: state.thinkingBudget), thinkingSupport: state.thinkingSupport ?? "NONE",
                redaction: state.redaction ?? "NONE", cascade: state.cascade ?? "NONE",
            ),
        )
    }
}

/**
 Observable state of one agent session, as the chat screen renders it (the bridge's
 `SessionState`). Each part is observed on its own and only assigned when it changed, so a
 streamed chunk invalidates only what reads [live], not the transcript or the screen around it.
 */
@MainActor
@Observable
final class AgentSession {
    let id: String
    private(set) var transcript = SessionTranscript(SessionProjection())
    /// The in-flight model output (read only by the live message).
    private(set) var live: LiveState?
    /// Whether [live] is set (what the screen around the live message needs).
    private(set) var isLive = false
    private(set) var running = false
    private(set) var approvals: [ApprovalState] = []
    private(set) var question: QuestionState?
    private(set) var settings = SessionSettings()
    /// Called after every applied update (voice mode follows its turn with it). Not observed.
    @ObservationIgnored var onUpdate: (@MainActor (AgentSession) -> Void)?

    init(id: String) { self.id = id }

    /// The whole state (composed; prefer the parts in views). Setting it applies the changes.
    var state: ChatSessionState {
        get {
            var s = ChatSessionState(sessionId: id)
            s.projection = transcript.projection
            s.live = live
            s.running = running
            s.approvals = approvals
            s.question = question
            s.toolsEnabled = settings.toolsEnabled
            s.permission = settings.permission
            s.provider = settings.provider
            s.model = settings.model
            return s
        }
        set {
            let projectionChanged = newValue.projection != transcript.projection
            var update = SessionUpdate(
                transcript: nil, live: newValue.live, running: newValue.running, approvals: newValue.approvals, question: newValue.question,
                settings: SessionSettings(
                    toolsEnabled: newValue.toolsEnabled, permission: newValue.permission, provider: newValue.provider, model: newValue.model,
                    thinking: ThinkingChoice(mode: newValue.thinking ?? "AUTO", budget: newValue.thinkingBudget), thinkingSupport: newValue.thinkingSupport ?? "NONE",
                    redaction: newValue.redaction ?? "NONE", cascade: newValue.cascade ?? "NONE",
                ),
            )
            if projectionChanged { update.transcript = SessionTranscript(newValue.projection) }
            apply(update)
        }
    }

    /// Applies [update], assigning only the parts that changed.
    func apply(_ update: SessionUpdate) {
        if let t = update.transcript, t != transcript { transcript = t }
        if update.onlyTranscript {
            onUpdate?(self)
            return
        }
        if update.live != live { live = update.live }
        if (update.live != nil) != isLive { isLive = update.live != nil }
        if update.running != running { running = update.running }
        if update.approvals != approvals { approvals = update.approvals }
        if update.question != question { question = update.question }
        if update.settings != settings { settings = update.settings }
        onUpdate?(self)
    }
}

/**
 Everything the UI needs from the agent runtime. Android runs every chat / agent / code session
 through `:agent:runtime`'s `AgentHost` (the Kotlin port of DeepSeek Harness). On iOS the same
 Kotlin runtime runs from `AutobotShared.xcframework` ([SharedAgentRuntime], JSON over callbacks);
 [InterimAgentRuntime] (plain streaming chat) is the fallback for builds without the framework.
 */
@MainActor
protocol AgentRuntime: AnyObject {
    /// Sessions with a turn in progress (for list indicators).
    var running: Set<String> { get }
    /// Whether this runtime executes tools (the interim one does not).
    var supportsTools: Bool { get }

    /// The session for [conversationId], opening it on first use with the conversation's
    /// provider and model.
    func session(_ conversationId: String) -> AgentSession
    /// The session if it is open or previewed, without opening it (safe while a view renders).
    func existingSession(_ conversationId: String) -> AgentSession?
    /// No screen shows [conversationId] any more: stop streaming its state (a running turn goes
    /// on). The next [session] call resumes it.
    func close(_ conversationId: String)
    /// Shows [state] for [conversationId] without opening it on the runtime, so nothing replaces
    /// it (snapshots and demo data).
    func preview(_ conversationId: String, state: ChatSessionState)
    /// Sends a prompt (with attached image ids): a new turn, or queued behind the running one.
    func send(_ conversationId: String, text: String, images: [String]) async
    func cancel(_ conversationId: String)
    /// Answers [conversationId]'s approval [callId] (call ids repeat across chats).
    func answerApproval(_ conversationId: String, callId: String, outcome: ApprovalOutcome)
    func answerQuestion(_ conversationId: String, callId: String, answers: [String]?)
    func setToolsEnabled(_ conversationId: String, _ enabled: Bool)
    func setPermission(_ conversationId: String, _ preset: PermissionPreset)
    /// Switches the session's provider / model.
    func setRoute(_ conversationId: String, providerId: String, model: String)
    /// The provider list changed: conversations whose provider is gone (or that had none) move to
    /// the first provider, open sessions included.
    func providersChanged(_ providers: [Provider])
    /// Manual `/compact`.
    func compact(_ conversationId: String)
    /// Stops and forgets the agent (before deleting its conversation; [ChatLibrary] deletes the log).
    func discard(_ conversationId: String)
    /// Panic wipe: stop everything and drop in-memory logs.
    func shutdown()
    /// Undoes one file step of the agent (and later changes to the same files); idle sessions only.
    func undoStep(_ conversationId: String, callId: String)
    /// Undoes every file change of [turn].
    func undoTurn(_ conversationId: String, turn: Int)
    /// Re-applies what an undo ([revertId]) put back.
    func redo(_ conversationId: String, revertId: String)
    /// Sets the chat's Think switch (remembered with the chat).
    func setThinking(_ conversationId: String, _ choice: ThinkingChoice)
    /// The user's Think default for [model] on [provider] (Settings), if any.
    func thinkingDefault(provider: String, model: String) -> ThinkingChoice?
    /// Sets (nil: clears) [model]'s Think default; chats that never chose follow it.
    func setThinkingDefault(provider: String, model: String, _ choice: ThinkingChoice?)
    /// This chat's redaction switch (what Settings says makes the chat follow Settings again).
    func setRedaction(_ conversationId: String, _ enabled: Bool)
    /// This chat's "phone first" policy (OFF, ASK, AUTO, NEVER; what Settings says makes it follow Settings again).
    func setCascade(_ conversationId: String, _ policy: String)
    /// "Ask <cloud model>": sends the prompt of the phone's offered answer to the chat's cloud model.
    func escalate(_ conversationId: String)
    /// A tap on a generated UI (`show_ui`, A2UI): a `.send` action becomes the chat's next user
    /// turn (queued behind a running one), shown in the transcript as its label. False when it
    /// could not be handed to the chat's agent (the form unlocks again).
    func sendA2uiAction(_ conversationId: String, _ action: A2uiAction) async -> Bool
}

/// The chat's Think switch, as the shared `ThinkingSetting`: `mode` is OFF, AUTO or ON; `budget`
/// (reasoning tokens) only for ON, nil there meaning the model's default.
struct ThinkingChoice: Equatable, Hashable, Sendable {
    var mode = "AUTO"
    var budget: Int? = nil

    static let auto = ThinkingChoice()
    static let off = ThinkingChoice(mode: "OFF")
    static let on = ThinkingChoice(mode: "ON")
    /// Budgets offered for ON (nil: the model's default), as `ThinkingSetting.BUDGETS`.
    static let budgets: [Int?] = [nil, 256, 512, 1024, 2048, 4096]

    /// Tapping the chip: auto → on → off → auto.
    var next: ThinkingChoice {
        switch mode {
        case "AUTO": .on
        case "ON": .off
        default: .auto
        }
    }

    /// "think · auto", "think · off", "think · on", "think · 1k".
    var chipLabel: String {
        switch mode {
        case "OFF": "think · off"
        case "ON": budget.map { "think · \(Self.tokens($0))" } ?? "think · on"
        default: "think · auto"
        }
    }

    static func tokens(_ n: Int) -> String {
        n >= 1000 ? (n % 1024 == 0 ? "\(n / 1024)k" : "\(n / 1000)k") : "\(n)"
    }
}

/// Runtimes without a Think switch (the interim one).
extension AgentRuntime {
    func setThinking(_ conversationId: String, _ choice: ThinkingChoice) {}
    func thinkingDefault(provider: String, model: String) -> ThinkingChoice? { nil }
    func setThinkingDefault(provider: String, model: String, _ choice: ThinkingChoice?) {}
    func setRedaction(_ conversationId: String, _ enabled: Bool) {}
    func setCascade(_ conversationId: String, _ policy: String) {}
    func escalate(_ conversationId: String) {}
    /// No tools, no generated UI.
    func sendA2uiAction(_ conversationId: String, _ action: A2uiAction) async -> Bool { false }
}

/// Runtimes without file tools have nothing to undo.
extension AgentRuntime {
    func undoStep(_ conversationId: String, callId: String) {}
    func undoTurn(_ conversationId: String, turn: Int) {}
    func redo(_ conversationId: String, revertId: String) {}
}

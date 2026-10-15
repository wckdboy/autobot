import Foundation
import Observation

/**
 Observable state of one agent session, as the chat screen renders it (the bridge's
 `SessionState`: projected transcript, live frame, approvals, question, settings).
 */
@MainActor
@Observable
final class AgentSession {
    let id: String
    var state: ChatSessionState

    init(id: String) {
        self.id = id
        state = ChatSessionState(sessionId: id)
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
    func answerApproval(callId: String, outcome: ApprovalOutcome)
    func answerQuestion(callId: String, answers: [String]?)
    func setToolsEnabled(_ conversationId: String, _ enabled: Bool)
    func setPermission(_ conversationId: String, _ preset: PermissionPreset)
    /// Switches the session's provider / model.
    func setRoute(_ conversationId: String, providerId: String, model: String)
    /// The provider list changed: conversations whose provider is gone (or that had none) move to
    /// the first provider, open sessions included.
    func providersChanged(_ providers: [Provider])
    /// Manual `/compact`.
    func compact(_ conversationId: String)
    /// Stops and forgets the agent (before deleting its conversation).
    func discard(_ conversationId: String)
    /// Panic wipe: stop everything and drop in-memory logs.
    func shutdown()
    /// Undoes one file step of the agent (and later changes to the same files); idle sessions only.
    func undoStep(_ conversationId: String, callId: String)
    /// Undoes every file change of [turn].
    func undoTurn(_ conversationId: String, turn: Int)
    /// Re-applies what an undo ([revertId]) put back.
    func redo(_ conversationId: String, revertId: String)
}

/// Runtimes without file tools have nothing to undo.
extension AgentRuntime {
    func undoStep(_ conversationId: String, callId: String) {}
    func undoTurn(_ conversationId: String, turn: Int) {}
    func redo(_ conversationId: String, revertId: String) {}
}

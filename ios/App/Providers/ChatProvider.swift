import Foundation

enum ChatRole: String, Sendable { case system, user, assistant, tool }

/// A function call requested by the model. [arguments] is the raw JSON object text.
struct ToolCall: Equatable, Sendable {
    let id: String
    let name: String
    let arguments: String
}

/**
 One conversation message.

 - toolCalls: calls requested by an assistant message.
 - toolCallId: the call a tool message answers.
 - reasoning: an assistant's reasoning trace; only sent back when the request sets
   [ChatRequest.echoReasoning] (DeepSeek thinking mode inside a tool-call turn).
 - images: plain image files to show the model (user messages only; vision models).
 */
struct ChatMessage: Equatable, Sendable {
    var role: ChatRole
    var content: String
    var toolCalls: [ToolCall] = []
    var toolCallId: String? = nil
    var reasoning: String? = nil
    var images: [URL] = []
}

/// A function the model may call. [parametersJson] is a JSON Schema object, as text.
struct ToolSpec: Equatable, Sendable {
    let name: String
    let description: String
    let parametersJson: String
}

/**
 A provider-agnostic chat completion request. Contains only conversation content: no device,
 user or installation identifiers are ever attached.
 */
struct ChatRequest: Equatable, Sendable {
    var model: String
    var messages: [ChatMessage]
    var temperature: Double? = nil
    var maxTokens: Int? = nil
    var tools: [ToolSpec] = []
    /// Send reasoning back as `reasoning_content` on assistant messages (DeepSeek tool turns).
    var echoReasoning = false
}

enum ErrorKind: String, Sendable {
    /// Refused locally by the kill switch / network policy.
    case blocked, unauthorized, rateLimited, quota, badRequest, server, network, `protocol`, http
}

/// Streaming output of [ChatProvider.streamChat].
enum ChatEvent: Equatable, Sendable {
    case contentDelta(String)
    case reasoningDelta(String)
    /// A fragment of a streamed tool call; fragments sharing an index belong to one call.
    case toolCallDelta(index: Int, id: String?, name: String?, argumentsDelta: String)
    case usage(promptTokens: Int?, completionTokens: Int?, totalTokens: Int?)
    /// The choice finished: `stop`, `length`, `tool_calls`, `content_filter`, …
    case finish(String)
    case done
    case error(ErrorKind, String, httpCode: Int? = nil)
}

/// Outcome of [ChatProvider.testConnection].
enum ConnectionTestResult: Equatable, Sendable {
    case success(modelCount: Int?)
    case failure(ErrorKind, String)
}

/// A remote (or loopback, or on-device) chat model endpoint.
protocol ChatProvider: Sendable {
    /**
     Streams a completion. Iterating starts the call and cancelling the consuming task cancels
     it. Failures are emitted as `.error` events rather than thrown.
     */
    func streamChat(_ request: ChatRequest) -> AsyncStream<ChatEvent>

    /// Lightweight authenticated request (`GET /models`) to verify reachability and credentials.
    func testConnection() async -> ConnectionTestResult
}

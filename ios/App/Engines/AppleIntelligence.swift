import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// The shared `SystemPrompt` (JSON from the Kotlin `LocalChatRouter`): the chat reshaped for a
/// system model's ~4k context, with tools dropped and old turns trimmed.
struct SystemPromptPlan: Codable, Sendable, Equatable {
    struct Turn: Codable, Sendable, Equatable {
        var role: String
        var text: String
    }

    var instructions: String?
    var history: [Turn]
    var prompt: String
    var temperature: Double?
    var maxTokens: Int
}

/**
 Apple Intelligence's on-device model (Foundation Models, iOS 26+): the "This phone" system model
 and the fallback when no downloaded model can run. On-device only — Private Cloud Compute is
 never used. Plain chat: the shared router does not offer it tools.
 */
enum AppleIntelligence {
    static let id = "system:apple"
    static let title = "Apple Intelligence (on-device)"

    /// Why the model cannot run.
    enum Unavailability: Equatable, Sendable {
        case needsNewerOS
        case deviceNotEligible
        case turnedOff
        case notReady
        case other(String)

        var detail: String {
            switch self {
            case .needsNewerOS: "needs iOS 26 or newer"
            case .deviceNotEligible: "this device does not support Apple Intelligence"
            case .turnedOff: "Apple Intelligence is turned off in Settings"
            case .notReady: "Apple Intelligence is still downloading its model"
            case .other(let reason): reason
            }
        }
    }

    /// nil when the model can answer now.
    static var unavailability: Unavailability? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) { return map(SystemLanguageModel.default.availability) }
        #endif
        return .needsNewerOS
    }

    #if canImport(FoundationModels)
    @available(iOS 26.0, *)
    static func map(_ availability: SystemLanguageModel.Availability) -> Unavailability? {
        switch availability {
        case .available: nil
        case .unavailable(.deviceNotEligible): .deviceNotEligible
        case .unavailable(.appleIntelligenceNotEnabled): .turnedOff
        case .unavailable(.modelNotReady): .notReady
        case .unavailable(let other): .other("unavailable (\(other))")
        }
    }
    #endif

    // MARK: - Request mapping

    /// Transcript entries before they become Foundation Models types (pure, tested on any OS).
    enum Entry: Equatable {
        case instructions(String)
        case prompt(String)
        case response(String)
    }

    /// Instructions and history; the plan's `prompt` is what the session then responds to.
    static func entries(_ plan: SystemPromptPlan) -> [Entry] {
        var out: [Entry] = []
        if let i = plan.instructions, !i.isEmpty { out.append(.instructions(i)) }
        for turn in plan.history where !turn.text.isEmpty {
            out.append(turn.role == "assistant" ? .response(turn.text) : .prompt(turn.text))
        }
        return out
    }

    #if canImport(FoundationModels)
    @available(iOS 26.0, *)
    static func transcript(_ plan: SystemPromptPlan) -> Transcript {
        Transcript(entries: entries(plan).map { entry -> Transcript.Entry in
            switch entry {
            case .instructions(let text):
                .instructions(Transcript.Instructions(segments: [.text(.init(content: text))], toolDefinitions: []))
            case .prompt(let text):
                .prompt(Transcript.Prompt(segments: [.text(.init(content: text))]))
            case .response(let text):
                .response(Transcript.Response(assetIDs: [], segments: [.text(.init(content: text))]))
            }
        })
    }

    /// Streams one answer; returns the engine result JSON the shared codec decodes.
    @available(iOS 26.0, *)
    static func respond(_ plan: SystemPromptPlan, onDelta: @escaping @Sendable (String) -> Void) async -> String {
        if let reason = map(SystemLanguageModel.default.availability) {
            return errorJSON(code: "LOAD_FAILED", message: "\(title) is not available: \(reason.detail)")
        }
        let session = LanguageModelSession(model: .default, tools: [], transcript: transcript(plan))
        let options = GenerationOptions(temperature: plan.temperature, maximumResponseTokens: plan.maxTokens)
        var sent = ""
        var promptTokens: Int?
        var completionTokens: Int?
        do {
            for try await snapshot in session.streamResponse(to: plan.prompt, options: options) {
                let text = snapshot.content
                // Snapshots are cumulative; send what is new.
                if text.hasPrefix(sent) {
                    let delta = String(text.dropFirst(sent.count))
                    if !delta.isEmpty { onDelta(delta) }
                    sent = text
                }
                // Token usage and the newer error types are in the iOS 27 SDK (Xcode 27, Swift 6.4; Xcode 26.x ships at most 6.3);
                // built with an older Xcode, they are left out and usage stays unknown.
                #if compiler(>=6.4)
                if #available(iOS 27.0, *) {
                    promptTokens = snapshot.usage.input.totalTokenCount
                    completionTokens = snapshot.usage.output.totalTokenCount
                }
                #endif
            }
            if Task.isCancelled { return errorJSON(code: "CANCELLED", message: "cancelled") }
            return resultJSON(finish: completionTokens.map { $0 >= plan.maxTokens ? "length" : "stop" } ?? "stop", promptTokens: promptTokens, completionTokens: completionTokens)
        } catch {
            let (code, message) = describe(error)
            return errorJSON(code: code, message: message)
        }
    }

    /// Framework errors as the engine core's codes (context overflow is what compaction reacts to).
    @available(iOS 26.0, *)
    static func describe(_ error: Error) -> (code: String, message: String) {
        if error is CancellationError { return ("CANCELLED", "cancelled") }
        #if compiler(>=6.4)
        if #available(iOS 27.0, *), let e = error as? LanguageModelError {
            switch e {
            case .contextSizeExceeded: return ("CONTEXT_WINDOW_EXCEEDED", contextMessage)
            case .guardrailViolation: return ("GUARDRAIL", guardrailMessage)
            case .refusal: return ("REFUSAL", "\(title) declined to answer this request")
            case .rateLimited: return ("RATE_LIMITED", "\(title) is busy; try again in a moment")
            case .unsupportedLanguageOrLocale: return ("UNSUPPORTED_LANGUAGE", languageMessage)
            default: return ("ERROR", e.localizedDescription)
            }
        }
        if #available(iOS 27.0, *), error is SystemLanguageModel.Error {
            return ("LOAD_FAILED", "\(title) is not ready: \(Unavailability.notReady.detail)")
        }
        #endif
        if let e = error as? LanguageModelSession.GenerationError {
            switch e {
            case .exceededContextWindowSize: return ("CONTEXT_WINDOW_EXCEEDED", contextMessage)
            case .guardrailViolation: return ("GUARDRAIL", guardrailMessage)
            case .refusal: return ("REFUSAL", "\(title) declined to answer this request")
            case .rateLimited, .concurrentRequests: return ("RATE_LIMITED", "\(title) is busy; try again in a moment")
            case .unsupportedLanguageOrLocale: return ("UNSUPPORTED_LANGUAGE", languageMessage)
            case .assetsUnavailable: return ("LOAD_FAILED", "\(title) is not ready: \(Unavailability.notReady.detail)")
            default: return ("ERROR", e.localizedDescription)
            }
        }
        return ("ERROR", error.localizedDescription)
    }
    #endif

    static let contextMessage = "context length exceeded: the conversation is too long for Apple Intelligence (about 4k tokens) — start a new chat or /compact"
    static let guardrailMessage = "Apple Intelligence's safety guardrails blocked this request"
    static let languageMessage = "Apple Intelligence does not support this language yet"

    // MARK: - Result JSON (the engine core's shape, decoded by the shared codec)

    static func resultJSON(finish: String, promptTokens: Int?, completionTokens: Int?) -> String {
        var o: [String: Any] = ["finish_reason": finish]
        if let promptTokens, let completionTokens { o["usage"] = ["prompt_tokens": promptTokens, "completion_tokens": completionTokens] }
        return json(o)
    }

    static func errorJSON(code: String, message: String) -> String {
        json(["error": ["code": code, "message": message]])
    }

    private static func json(_ object: [String: Any]) -> String {
        (try? JSONSerialization.data(withJSONObject: object)).map { String(decoding: $0, as: UTF8.self) }
            ?? #"{"error":{"code":"ERROR","message":"engine error"}}"#
    }
}

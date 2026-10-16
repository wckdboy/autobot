import Foundation
import Testing
@testable import Autobot
#if canImport(AutobotShared)
import AutobotShared
#endif
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Apple Intelligence as the system model: request mapping, availability and error handling. The
/// Simulator usually has no model, so answering itself is only exercised on a device.
struct AppleIntelligenceTests {
    private let plan = SystemPromptPlan(
        instructions: "You are Autobot.",
        history: [.init(role: "user", text: "hi"), .init(role: "assistant", text: "hello"), .init(role: "user", text: "[tool result]\nok")],
        prompt: "and now?",
        temperature: 0.2,
        maxTokens: 512,
    )

    @Test func mapsThePlanToTranscriptEntries() {
        #expect(AppleIntelligence.entries(plan) == [.instructions("You are Autobot."), .prompt("hi"), .response("hello"), .prompt("[tool result]\nok")])
        let bare = SystemPromptPlan(instructions: nil, history: [.init(role: "user", text: "")], prompt: "x", temperature: nil, maxTokens: 1)
        #expect(AppleIntelligence.entries(bare).isEmpty)
    }

    @Test func decodesTheSharedPromptJSON() throws {
        let json = #"{"instructions":null,"history":[{"role":"user","text":"a"}],"prompt":"b","temperature":null,"maxTokens":1024}"#
        let decoded = try JSONDecoder().decode(SystemPromptPlan.self, from: Data(json.utf8))
        #expect(decoded.history == [.init(role: "user", text: "a")])
        #expect(decoded.prompt == "b" && decoded.maxTokens == 1024 && decoded.instructions == nil)
    }

    #if canImport(AutobotShared)
    @Test func sharedPromptRoundTripsThroughTheKotlinCodec() throws {
        let request = AutobotShared.ChatRequest(
            model: AppleIntelligence.id,
            messages: [AutobotShared.ChatMessage(role: AutobotShared.ChatRole.system, content: "sys", toolCalls: [], toolCallId: nil, reasoning: nil, images: []),
                       AutobotShared.ChatMessage(role: AutobotShared.ChatRole.user, content: "hello", toolCalls: [], toolCallId: nil, reasoning: nil, images: [])],
            temperature: nil, maxTokens: nil, tools: [], echoReasoning: false, sessionKey: nil, thinking: nil,
        )
        let kotlin = SystemPrompt.companion.from(request: request, contextTokens: 4096)
        // Same field names as the Swift mirror.
        let mirror = SystemPromptPlan(instructions: kotlin.instructions, history: [], prompt: kotlin.prompt, temperature: nil, maxTokens: Int(kotlin.maxTokens))
        #expect(mirror.instructions == "sys" && mirror.prompt == "hello" && mirror.maxTokens == 1024)
    }
    #endif

    @Test func resultAndErrorJSONMatchTheEngineShape() throws {
        let ok = try JSONSerialization.jsonObject(with: Data(AppleIntelligence.resultJSON(finish: "stop", promptTokens: 10, completionTokens: 3).utf8)) as? [String: Any]
        #expect(ok?["finish_reason"] as? String == "stop")
        #expect((ok?["usage"] as? [String: Int])?["completion_tokens"] == 3)
        let noUsage = try JSONSerialization.jsonObject(with: Data(AppleIntelligence.resultJSON(finish: "stop", promptTokens: nil, completionTokens: nil).utf8)) as? [String: Any]
        #expect(noUsage?["usage"] == nil)
        let error = try JSONSerialization.jsonObject(with: Data(AppleIntelligence.errorJSON(code: "CONTEXT_WINDOW_EXCEEDED", message: "too long").utf8)) as? [String: [String: String]]
        #expect(error?["error"]?["code"] == "CONTEXT_WINDOW_EXCEEDED")
    }

    @Test func unavailabilityReadsForTheUser() {
        #expect(AppleIntelligence.Unavailability.turnedOff.detail == "Apple Intelligence is turned off in Settings")
        #expect(AppleIntelligence.Unavailability.needsNewerOS.detail == "needs iOS 26 or newer")
        #expect(AppleIntelligence.Unavailability.deviceNotEligible.detail.contains("does not support"))
    }

    #if canImport(FoundationModels)
    @Test func mapsAvailabilityAndErrors() throws {
        guard #available(iOS 26.0, *) else { return }
        #expect(AppleIntelligence.map(.available) == nil)
        #expect(AppleIntelligence.map(.unavailable(.deviceNotEligible)) == .deviceNotEligible)
        #expect(AppleIntelligence.map(.unavailable(.appleIntelligenceNotEnabled)) == .turnedOff)
        #expect(AppleIntelligence.map(.unavailable(.modelNotReady)) == .notReady)
        let context = LanguageModelSession.GenerationError.Context(debugDescription: "x")
        #expect(AppleIntelligence.describe(LanguageModelSession.GenerationError.exceededContextWindowSize(context)).code == "CONTEXT_WINDOW_EXCEEDED")
        #expect(AppleIntelligence.describe(LanguageModelSession.GenerationError.guardrailViolation(context)).code == "GUARDRAIL")
        #expect(AppleIntelligence.describe(LanguageModelSession.GenerationError.assetsUnavailable(context)).code == "LOAD_FAILED")
        #expect(AppleIntelligence.describe(CancellationError()).code == "CANCELLED")
        let transcript = AppleIntelligence.transcript(plan)
        #expect(transcript.count == 4)
        guard case .instructions = transcript[0], case .response = transcript[2] else { Issue.record("unexpected entry kinds"); return }
    }

    @Test(.timeLimit(.minutes(3))) func respondsWithAClearErrorWhenTheModelIsMissing() async throws {
        guard #available(iOS 26.0, *), let reason = AppleIntelligence.unavailability else { return }
        let json = await AppleIntelligence.respond(plan) { _ in }
        let o = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: [String: String]]
        #expect(o?["error"]?["code"] == "LOAD_FAILED")
        #expect(o?["error"]?["message"]?.contains(reason.detail) == true)
    }
    #endif

    #if canImport(AutobotShared)
    @Test func sharedStatusMatchesAvailability() {
        let status = AppleIntelligence.sharedStatus()
        #expect(status.id == "system:apple")
        #expect(status.usable == (AppleIntelligence.unavailability == nil))
    }
    #endif
}

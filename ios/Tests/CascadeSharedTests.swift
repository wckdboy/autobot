import AutobotShared
import Foundation
import Testing

/// The local-first cascade's shared rules and words through the Kotlin framework, and what the
/// judge's rules cost on this device.
struct CascadeSharedTests {
    @Test func theChipAndTheOfferUseTheSharedWords() {
        #expect(CascadePolicy.ask.chip == "phone first · ask")
        #expect(CascadePolicy.never.chip == "phone only")
        #expect(CascadePolicy.entries.map(\.name) == ["OFF", "ASK", "AUTO", "NEVER"])
        #expect(CascadeLabels.shared.ask(model: "deepseek-chat") == "Ask deepseek-chat")
        #expect(CascadeLabels.shared.offer(verdict: "WEAK", reasons: ["refusal"]) == "The phone's answer looks weak: it declined.")
    }

    /**
     What the judge adds besides the self-check pass, measured on this device: the rules over a
     prompt and a 1 KB answer, the verdict, and the risk classifier. The self-check itself is one
     on-device request of at most 8 output tokens; there is no chat model in the test fixtures,
     so that part is not measured here.
     */
    @Test func theJudgesRulesAreCheap() {
        let judge = CascadeJudge(threshold: CascadeJudge.companion.DEFAULT_THRESHOLD, unsureBand: CascadeJudge.companion.DEFAULT_UNSURE_BAND)
        let prompts = [
            "Explain the Monty Hall problem in two paragraphs.",
            "What's the weather in Zurich right now?",
            "Write a reply to lena.vogel@posteo.de: I'm running late, see you at 7.",
            "What dosage of ibuprofen is safe with my medication?",
        ]
        let answer = String(repeating: "The car is behind one of three doors, and the host always opens a goat door. ", count: 13)
        // Warm up (first calls pay for regex compilation).
        for p in prompts {
            _ = judge.judge(signals: judge.signals(prompt: p, answer: answer, truncated: false, failed: false), selfCheck: nil)
            _ = CascadeRiskClassifier.shared.classify(prompt: p, images: false, privateContext: false, privateTerms: [])
        }
        let rounds = 50
        let start = DispatchTime.now().uptimeNanoseconds
        for _ in 0..<rounds {
            for p in prompts {
                let result = judge.judge(signals: judge.signals(prompt: p, answer: answer, truncated: false, failed: false), selfCheck: nil)
                _ = CascadeRiskClassifier.shared.classify(prompt: p, images: false, privateContext: false, privateTerms: [])
                _ = result.verdict
            }
        }
        let perTurnMs = Double(DispatchTime.now().uptimeNanoseconds - start) / 1e6 / Double(rounds * prompts.count)
        print("cascade judge rules + risk: \(String(format: "%.3f", perTurnMs)) ms per turn (simulator)")
        #expect(perTurnMs < 50)
        #expect(CascadeJudge.companion.parseSelfCheck(reply: "<think>hm</think> 4")?.intValue == 4)
    }
}

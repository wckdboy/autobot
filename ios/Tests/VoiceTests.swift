import AutobotShared
import Foundation
import Testing
@testable import Autobot

// Both modules have these names: the app's Swift ones are under test here.
private typealias SessionItem = Autobot.SessionItem
private typealias VoiceTurn = Autobot.VoiceTurn

/// Port of `shared/.../voice/VoiceTest.kt` (`VoiceTurn`), the iOS voice pick, and the shared
/// text rules as Swift sees them.
struct VoiceTests {
    private func user(_ k: String) -> SessionItem { .user(key: k, text: "hi", images: []) }
    private func answer(_ k: String, _ text: String, interrupted: Bool = false) -> SessionItem {
        .assistant(key: k, text: text, reasoning: nil, interrupted: interrupted, outputTokens: nil, model: nil)
    }

    @Test func readsTheLastAnswerOnceTheTurnEnds() {
        let before = [user("e1"), answer("e2", "old")]
        let turn = VoiceTurn(baseline: Set(before.map(\.key)), startedAtMs: 0)
        // Prompt queued: nothing new yet, agent still idle.
        #expect(turn.update(items: before, running: false, nowMs: 100) == nil)
        let during = before + [user("e3"), answer("e4", "Let me check.")]
        #expect(turn.update(items: during, running: true, nowMs: 200) == nil)
        let done = during + [answer("e6", "It is **sunny**.")]
        #expect(turn.update(items: done, running: false, nowMs: 300) == .init(reply: "It is **sunny**."))
    }

    @Test func aTurnThatEndedBeforeRunningWasSeenStillFinishes() {
        let turn = VoiceTurn(baseline: [], startedAtMs: 0)
        #expect(turn.update(items: [user("e1"), answer("e2", "ok")], running: false, nowMs: 10) == .init(reply: "ok"))
    }

    @Test func theOldTurnEndingIsNotTheVoiceTurn() {
        let turn = VoiceTurn(baseline: ["e1"], startedAtMs: 0)
        #expect(turn.update(items: [user("e1"), answer("e2", "previous")], running: false, nowMs: 10) == nil)
    }

    @Test func stoppedOrFailedAnswersAreNotRead() {
        let stopped = VoiceTurn(baseline: [], startedAtMs: 0)
        _ = stopped.update(items: [user("e1")], running: true, nowMs: 1)
        #expect(stopped.update(items: [user("e1"), answer("e2", "half", interrupted: true)], running: false, nowMs: 2) == .init(reply: nil))

        let failed = VoiceTurn(baseline: [], startedAtMs: 0)
        _ = failed.update(items: [user("e1")], running: true, nowMs: 1)
        let items = [user("e1"), answer("e2", "Let me look."), .notice(key: "e3", text: "AUTH: bad key", kind: .error)]
        #expect(failed.update(items: items, running: false, nowMs: 2) == .init(reply: nil))
    }

    @Test func givesUpWhenThePromptNeverArrives() {
        let turn = VoiceTurn(baseline: [], startedAtMs: 0)
        #expect(turn.update(items: [], running: false, nowMs: VoiceTurn.timeoutMs - 1) == nil)
        #expect(turn.update(items: [], running: true, nowMs: VoiceTurn.timeoutMs * 2) == nil)
        #expect(turn.update(items: [], running: false, nowMs: VoiceTurn.timeoutMs) == .init(reply: nil))
    }

    @Test func announcesEachApprovalOnce() {
        let turn = VoiceTurn(baseline: [], startedAtMs: 0)
        #expect(turn.needsAnnouncement(["c1"]))
        #expect(!turn.needsAnnouncement(["c1"]))
        #expect(turn.needsAnnouncement(["c1", "c2"]))
        #expect(!turn.needsAnnouncement([]))
    }

    @Test func picksTheBestVoiceForTheLanguage() {
        let voices = [
            VoiceCandidate(identifier: "en-us", language: "en-US", quality: 1),
            VoiceCandidate(identifier: "de-de", language: "de-DE", quality: 1),
            VoiceCandidate(identifier: "de-at-premium", language: "de-AT", quality: 3),
            VoiceCandidate(identifier: "de-de-enhanced", language: "de-DE", quality: 2),
            VoiceCandidate(identifier: "bells", language: "de-DE", quality: 3, special: true),
        ]
        #expect(SystemSpeaker.pick(voices, preferred: "de-DE") == "de-de-enhanced")
        #expect(SystemSpeaker.pick(voices, preferred: "de-CH") == "de-at-premium")
        #expect(SystemSpeaker.pick(voices, preferred: "ja-JP") == "en-us")
        #expect(SystemSpeaker.pick([voices[4]], preferred: "de-DE") == nil)
    }

    @Test func sharedTextRulesBridge() {
        let text = AutobotShared.VoiceText.shared
        let plain = text.speakable(markdown: "# Hi\nIt is **sunny**. Take `sunglasses`!")
        #expect(plain == "Hi.\nIt is sunny. Take sunglasses!")
        #expect(text.chunks(text: plain, maxChars: text.MAX_CHUNK) == ["Hi.", "It is sunny. Take sunglasses!"])
        #expect(text.transcript(raw: "[BLANK_AUDIO]") == "")
    }

    @Test func endOfSpeechBridges() {
        let detector = EndOfSpeech(threshold: 0.05, silenceMs: 1_200, minSpeechMs: 200, noSpeechMs: 8_000)
        var verdict = SpeechVerdict.listening
        var at: Int64 = 0
        for level in Array(repeating: Float(0.01), count: 3) + Array(repeating: 0.3, count: 5) + Array(repeating: 0.01, count: 20) {
            verdict = detector.onLevel(peak: level, atMs: at)
            if verdict != .listening { break }
            at += 100
        }
        #expect(verdict == .ended)
        #expect(at == 1_900)
    }
}

import AutobotShared
import Foundation
import Testing
@testable import Autobot

// Both modules have these names: the app's Swift ones are under test here.
private typealias SessionItem = Autobot.SessionItem
private typealias VoiceTurn = Autobot.VoiceTurn
private typealias SpeechQueue = Autobot.SpeechQueue

/// Streaming dictation and read-aloud as the iOS app drives them: the shared Kotlin logic through
/// the Swift bridge, the speaking queue, and the dictation driver with a fake engine.
@MainActor
struct LiveVoiceTests {
    private final class FakeSpeaker: Speaker {
        let id = "fake"
        var isAvailable: Bool { true }
        var spoken: [String] = []
        var prepared: [String] = []

        func speak(_ text: String) async throws {
            spoken.append(text)
            try await Task.sleep(for: .milliseconds(5))
        }

        func prepare(_ text: String) { prepared.append(text) }
        func stop() {}
    }

    /// One word per half second of audio; the microphone is fed by the test.
    private final class FakeEngine: DictationEngine {
        var calls = 0
        var partial: [Bool] = []
        func startLive(onLevel: @escaping @Sendable (Float) -> Void, onSamples: @escaping @Sendable (UnsafeBufferPointer<Float>) -> Void, onInterruption: @escaping @Sendable () -> Void) throws {}
        func stopLive() {}
        func transcribe(_ samples: [Float], language: String?, partial: Bool) async throws -> (text: String, language: String) {
            calls += 1
            self.partial.append(partial)
            try await Task.sleep(for: .milliseconds(10))
            return ((0 ..< samples.count / 8_000).map { "word\($0 + 1)" }.joined(separator: " "), "en")
        }
    }

    private func tone(ms: Int, loud: Bool) -> [Float] {
        (0 ..< 16 * ms).map { n in loud ? Float(0.4 * sin(2 * Double.pi * 220 * Double(n) / 16_000)) : 0.002 }
    }

    @Test func speechQueueSpeaksPiecesAsTheyArriveUntilClosed() async throws {
        let speaker = FakeSpeaker()
        var started: [Int] = []
        let queue = SpeechQueue(onPiece: { started.append($0) })
        queue.add(["One.", "Two."])
        let run = Task { try await queue.run(speaker) }
        try await Task.sleep(for: .milliseconds(60))
        #expect(speaker.spoken == ["One.", "Two."])
        queue.add(["Three."])
        queue.close()
        try await run.value
        #expect(speaker.spoken == ["One.", "Two.", "Three."])
        #expect(speaker.prepared == ["Two."])
        #expect(started == [0, 1, 2])
    }

    @Test func aPieceAfterAWaitForTheAnswerIsAResume() async throws {
        let speaker = FakeSpeaker()
        var events: [String] = []
        let queue = SpeechQueue(onResume: { events.append("resume") }) { events.append("piece\($0)") }
        queue.add(["One.", "Two."])
        let run = Task { try await queue.run(speaker) }
        try await Task.sleep(for: .milliseconds(60))
        queue.add(["Three."])
        queue.close()
        try await run.value
        #expect(events == ["piece0", "piece1", "resume", "piece2"])
    }

    @Test func committedStepsAreReadToTheirEnd() {
        let turn = VoiceTurn(baseline: [], startedAtMs: 0)
        let stream = ReadAloudStream(speakable: { LiteMarkdown.speakable($0) }, maxChars: AutobotShared.VoiceText.shared.MAX_CHUNK)
        var pieces = stream.update(text: "Sure. Let me look")
        let items: [SessionItem] = [
            .user(key: "e1", text: "weather?", images: []),
            .assistant(key: "e2", text: "Sure. Let me look the forecast up.", reasoning: nil, interrupted: false, outputTokens: nil, model: nil),
        ]
        for step in turn.committedSteps(items: items) { pieces += stream.stepDone(text: step) }
        #expect(turn.committedSteps(items: items).isEmpty)
        #expect(pieces == ["Sure.", "Let me look the forecast up."])
    }

    @Test func liveDictationKeepsAudioQueuedBeforeTheStop() async throws {
        let engine = FakeEngine()
        let live = LiveDictation(engine: engine)
        try live.start(onLevel: { _ in }, onInterruption: {})
        live.received(tone(ms: 400, loud: false) + tone(ms: 1_000, loud: true) + tone(ms: 700, loud: false))
        while engine.calls == 0 { try await Task.sleep(for: .milliseconds(5)) }
        try await Task.sleep(for: .milliseconds(50))
        // More speech captured just before the stop, still on its way to the main actor.
        let late = tone(ms: 1_000, loud: true) + tone(ms: 100, loud: false)
        Task { @MainActor in live.received(late) }
        let before = engine.calls
        _ = await live.finish()
        // The late words are speech after the last partial: a full last pass runs over them
        // (dropped, the last partial would have looked like it covered everything).
        #expect(engine.calls == before + 1)
        #expect(engine.partial.last == false)
    }

    @Test func aCancelledQueueStopsWaiting() async throws {
        let queue = SpeechQueue()
        let run = Task { try await queue.run(FakeSpeaker()) }
        try await Task.sleep(for: .milliseconds(20))
        run.cancel()
        await #expect(throws: CancellationError.self) { try await run.value }
    }

    @Test func liveDictationShowsWordsAndEndsWithAFinalTranscript() async throws {
        let engine = FakeEngine()
        let live = LiveDictation(engine: engine)
        var partials: [DictationText] = []
        live.onText = { partials.append($0) }
        try live.start(onLevel: { _ in }, onInterruption: {})
        let audio = tone(ms: 400, loud: false) + tone(ms: 2_500, loud: true) + tone(ms: 1_000, loud: false)
        for start in stride(from: 0, to: audio.count, by: 1_600) {
            live.received(Array(audio[start ..< min(start + 1_600, audio.count)]))
            try await Task.sleep(for: .milliseconds(15))
        }
        let final = await live.finish()
        #expect(partials.contains { !$0.isEmpty })
        #expect(final.hasPrefix("word1"))
        #expect(engine.calls >= 2)
        #expect(live.firstPartialMs != nil)
        // Live passes run as partial passes (shorter encoder context).
        #expect(engine.partial.first == true)
    }

    @Test func silenceGivesAnEmptyTranscriptWithoutAnyPass() async throws {
        let engine = FakeEngine()
        let live = LiveDictation(engine: engine)
        try live.start(onLevel: { _ in }, onInterruption: {})
        live.received(tone(ms: 3_000, loud: false))
        #expect(await live.finish() == "")
        #expect(engine.calls == 0)
    }

    @Test func readAloudStreamUsesTheChatRendererAndSkipsCode() {
        let stream = ReadAloudStream(speakable: { LiteMarkdown.speakable($0) }, maxChars: AutobotShared.VoiceText.shared.MAX_CHUNK)
        let answer = "**Sure.** Here is how:\n\n```swift\nlet x = 1. Then more.\n```\n\nRun it, and you are done."
        var pieces: [String] = []
        var i = answer.startIndex
        while i < answer.endIndex {
            i = answer.index(i, offsetBy: 3, limitedBy: answer.endIndex) ?? answer.endIndex
            pieces += stream.update(text: String(answer[..<i]))
        }
        pieces += stream.finish(reply: answer)
        #expect(pieces.first == "Sure.")
        #expect(!pieces.contains { $0.contains("let x") || $0.contains("Then more") })
        #expect(pieces.last?.hasSuffix("you are done.") == true)
    }

    @Test func bargeInFromSwift() {
        let gate = BargeIn()
        gate.start(atMs: 0)
        var fired = false
        for i in 0 ..< 5 { fired = fired || gate.onLevel(peak: 0.15, atMs: Int64(i * 100)) }
        for i in 5 ..< 12 { fired = fired || gate.onLevel(peak: 0.8, atMs: Int64(i * 100)) }
        #expect(fired)
    }

    @Test func voiceTurnKnowsWhenItsPromptIsIn() {
        let turn = VoiceTurn(baseline: ["e1"], startedAtMs: 0)
        #expect(!turn.promptSeen(items: [.user(key: "e1", text: "hi", images: [])]))
        #expect(turn.promptSeen(items: [.user(key: "e1", text: "hi", images: []), .user(key: "e3", text: "again", images: [])]))
    }
}

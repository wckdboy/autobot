import AutobotShared
import Foundation
import Observation

// Streaming dictation and sentence-by-sentence read-aloud on iOS (port of Android's
// `feature/chat/.../voice/LiveDictation.kt` and `SpeechQueue.kt`). The windowing, agreement and
// segmentation are the shared Kotlin ones in `shared/.../voice`; only capture and engine calls
// are here. Audio stays in memory (one window) and is never stored or sent.

/// The words heard so far while dictating or listening, observed on its own: only the live
/// words line reads it, so each new transcription re-renders that line, not the chat screen.
@MainActor
@Observable
final class HeardWords {
    private(set) var committed = ""
    private(set) var tentative = ""

    var isEmpty: Bool { committed.isEmpty && tentative.isEmpty }

    func set(_ text: DictationText) {
        if committed != text.committed { committed = text.committed }
        if tentative != text.tentative { tentative = text.tentative }
    }

    func clear() { set(DictationText(committed: "", tentative: "")) }
}

/// What [LiveDictation] needs from the engines (a fake in tests).
@MainActor
protocol DictationEngine: AnyObject {
    func startLive(onLevel: @escaping @Sendable (Float) -> Void, onSamples: @escaping @Sendable (UnsafeBufferPointer<Float>) -> Void, onInterruption: @escaping @Sendable () -> Void) throws
    func stopLive()
    func transcribe(_ samples: [Float], language: String?) async throws -> (text: String, language: String)
}

/// [LocalSpeech] with the chat's speech model.
@MainActor
final class LocalDictationEngine: DictationEngine {
    private let speech: LocalSpeech
    private let modelPath: String

    init(speech: LocalSpeech, modelPath: String) {
        self.speech = speech
        self.modelPath = modelPath
    }

    func startLive(onLevel: @escaping @Sendable (Float) -> Void, onSamples: @escaping @Sendable (UnsafeBufferPointer<Float>) -> Void, onInterruption: @escaping @Sendable () -> Void) throws {
        try speech.startLive(onLevel: onLevel, onSamples: onSamples, onInterruption: onInterruption)
    }

    func stopLive() { _ = speech.stop() }

    func transcribe(_ samples: [Float], language: String?) async throws -> (text: String, language: String) {
        try await speech.transcribeDetailed(modelPath: modelPath, samples: samples, language: language)
    }
}

/**
 Streaming dictation: captures the microphone and keeps one whisper pass running over the shared
 `StreamingDictation` window, so [onText] sees the words while the user speaks. [finish] stops
 capture, cancels the pass under way and runs one last pass over what is left.
 */
@MainActor
final class LiveDictation {
    private let engine: DictationEngine
    private let dictation = StreamingDictation()
    private var inFlight: Task<Void, Never>?
    private var stopped = false
    private var speechAt: Int64 = -1
    /// Live text, on the main actor.
    var onText: (DictationText) -> Void = { _ in }
    /// Milliseconds from the first speech to the first words shown (nil: none yet).
    private(set) var firstPartialMs: Int64?

    init(engine: DictationEngine) {
        self.engine = engine
    }

    /// Starts listening. [preroll]: audio heard just before (a barge-in's first words).
    /// [onLevel] and [onInterruption] are called on the main actor.
    func start(preroll: [Float]? = nil, onLevel: @escaping @MainActor (Float) -> Void, onInterruption: @escaping @MainActor () -> Void) throws {
        if let preroll, !preroll.isEmpty { append(preroll) }
        try engine.startLive(
            onLevel: { peak in Task { @MainActor in onLevel(peak) } },
            onSamples: { [weak self] buffer in
                let chunk = Array(buffer)
                Task { @MainActor in self?.received(chunk) }
            },
            onInterruption: { Task { @MainActor in onInterruption() } },
        )
    }

    /// Feeds audio directly (tests and the simulator benchmark).
    func received(_ samples: [Float]) {
        guard !stopped else { return }
        append(samples)
        if speechAt < 0, dictation.firstSpeechAtMs >= 0 { speechAt = monotonicMs() }
        pump()
    }

    private func append(_ samples: [Float]) {
        let data = samples.withUnsafeBufferPointer { Data(buffer: $0) }
        VoiceAudio.shared.append(dictation: dictation, data: data)
    }

    /// Starts the next pass when one is due and none runs.
    private func pump() {
        guard inFlight == nil, !stopped, let pass = dictation.nextPass() else { return }
        let samples = Self.floats(VoiceAudio.shared.samples(pass: pass))
        let language = pass.language
        let engine = engine
        inFlight = Task { [weak self] in
            let heard = try? await engine.transcribe(samples, language: language)
            guard let self else { return }
            self.inFlight = nil
            guard !Task.isCancelled, !self.stopped else { return }
            if let heard {
                let text = self.dictation.onResult(pass: pass, raw: heard.text, detectedLanguage: heard.language)
                if self.firstPartialMs == nil, !text.isEmpty, self.speechAt >= 0 { self.firstPartialMs = monotonicMs() - self.speechAt }
                self.onText(text)
            } else {
                self.dictation.onFailed(pass: pass)
            }
            self.pump()
        }
    }

    /// Stops capture; the final transcript ("" when nothing was said).
    func finish() async -> String {
        guard !stopped else { return dictation.result }
        stopped = true
        engine.stopLive()
        if let pass = inFlight {
            // Only the last pass matters now: stop this one (whisper aborts) and let it return.
            pass.cancel()
            await pass.value
        }
        guard let last = dictation.finish() else { return dictation.result }
        let raw = (try? await engine.transcribe(Self.floats(VoiceAudio.shared.samples(pass: last)), language: last.language).text) ?? ""
        return dictation.onFinal(pass: last, raw: raw)
    }

    /// Stops without a transcript.
    func cancel() {
        guard !stopped else { return }
        stopped = true
        engine.stopLive()
        inFlight?.cancel()
    }

    static func floats(_ data: Data) -> [Float] {
        data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    }
}

/**
 Pieces of an answer spoken in order as they arrive while it still streams: [add] them, [close]
 when the answer is complete, and [run] speaks until both. The next piece goes to the speaker's
 `prepare` while one plays, so the neural voice synthesizes ahead.
 */
@MainActor
final class SpeechQueue {
    private var pending: [String] = []
    private var closed = false
    private var waiter: CheckedContinuation<Void, Never>?
    private let onPiece: (Int) -> Void

    init(onPiece: @escaping (Int) -> Void = { _ in }) {
        self.onPiece = onPiece
    }

    func add(_ pieces: [String]) {
        guard !pieces.isEmpty else { return }
        pending += pieces
        wake()
    }

    /// No more pieces will come.
    func close() {
        closed = true
        wake()
    }

    private func wake() {
        let w = waiter
        waiter = nil
        w?.resume()
    }

    /// Speaks every piece until closed and empty. Cancel the task to stop.
    func run(_ speaker: any Speaker) async throws {
        var index = 0
        while true {
            try Task.checkCancellation()
            if pending.isEmpty {
                if closed { return }
                await withTaskCancellationHandler {
                    await withCheckedContinuation { waiter = $0 }
                } onCancel: {
                    Task { @MainActor in self.wake() }
                }
                continue
            }
            let next = pending.removeFirst()
            if let after = pending.first { speaker.prepare(after) }
            onPiece(index)
            index += 1
            try await speaker.speak(next)
        }
    }
}

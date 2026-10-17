import AutobotShared
import AVFoundation
import Foundation
import Testing
@testable import Autobot

/**
 Voice latency on this machine (Simulator: whisper.cpp and speech on the Mac's CPU, no Neural
 Engine). Opt-in: the dictation run needs a whisper model and a recording, passed from the host
 as `TEST_RUNNER_AUTOBOT_WHISPER_MODEL` / `TEST_RUNNER_AUTOBOT_SPEECH_AUDIO` (xcodebuild hands
 them to the test process without the prefix). Prints `[voice-latency]` lines.
 */
@MainActor
struct VoiceLatencyTests {
    private nonisolated static var env: [String: String] { ProcessInfo.processInfo.environment }
    private nonisolated static var dictationInputs: (model: String, audio: String)? {
        guard let model = env["AUTOBOT_WHISPER_MODEL"], let audio = env["AUTOBOT_SPEECH_AUDIO"],
              FileManager.default.fileExists(atPath: model), FileManager.default.fileExists(atPath: audio) else { return nil }
        return (model, audio)
    }

    /// A recording as 16 kHz mono floats.
    private static func load(_ path: String) throws -> [Float] {
        let file = try AVAudioFile(forReading: URL(fileURLWithPath: path))
        let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
        let input = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length))!
        try file.read(into: input)
        let converter = AVAudioConverter(from: file.processingFormat, to: target)!
        let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: AVAudioFrameCount(Double(file.length) * 16_000 / file.processingFormat.sampleRate) + 1_024)!
        var fed = false
        var error: NSError?
        converter.convert(to: out, error: &error) { _, status in
            if fed {
                status.pointee = .endOfStream
                return nil
            }
            fed = true
            status.pointee = .haveData
            return input
        }
        return Array(UnsafeBufferPointer(start: out.floatChannelData![0], count: Int(out.frameLength)))
    }

    /// Logs each pass's audio length, engine time and text.
    private final class TimedEngine: DictationEngine {
        let inner: DictationEngine
        var log: [String] = []
        init(_ inner: DictationEngine) { self.inner = inner }
        func startLive(onLevel: @escaping @Sendable (Float) -> Void, onSamples: @escaping @Sendable (UnsafeBufferPointer<Float>) -> Void, onInterruption: @escaping @Sendable () -> Void) throws {}
        func stopLive() {}
        func transcribe(_ samples: [Float], language: String?) async throws -> (text: String, language: String) {
            let start = monotonicMs()
            do {
                let result = try await inner.transcribe(samples, language: language)
                log.append("\(samples.count / 16)→\(monotonicMs() - start) '\(result.text)'")
                return result
            } catch {
                log.append("\(samples.count / 16)→\(monotonicMs() - start) \(error is CancellationError ? "cancelled" : "error")")
                throw error
            }
        }
    }

    @Test(.enabled(if: dictationInputs != nil, "set TEST_RUNNER_AUTOBOT_WHISPER_MODEL and TEST_RUNNER_AUTOBOT_SPEECH_AUDIO"))
    func streamingDictationWithWhisper() async throws {
        let (model, path) = Self.dictationInputs!
        let speech = try Self.load(path)
        let audio = [Float](repeating: 0.001, count: 8_000) + speech + [Float](repeating: 0.001, count: 16_000)
        // Warm the model once (the first load is not dictation latency).
        _ = try await LocalSpeech().transcribeDetailed(modelPath: model, samples: Array(speech.prefix(16_000)), language: nil)

        let timed = TimedEngine(LocalDictationEngine(speech: LocalSpeech(), modelPath: model))
        let live = LiveDictation(engine: timed)
        defer { print("[voice-latency] passes (audio ms → engine ms, text): " + timed.log.joined(separator: " | ")) }
        var partials: [(Int64, String)] = []
        let started = monotonicMs()
        live.onText = { partials.append((monotonicMs() - started, $0.full)) }
        // Real time: 100 ms of audio every 100 ms, like the microphone.
        for start in stride(from: 0, to: audio.count, by: 1_600) {
            let tick = monotonicMs()
            live.received(Array(audio[start ..< min(start + 1_600, audio.count)]))
            let spent = monotonicMs() - tick
            try await Task.sleep(for: .milliseconds(max(0, 100 - spent)))
        }
        let stopAt = monotonicMs()
        let final = await live.finish()
        let finalMs = monotonicMs() - stopAt
        print("[voice-latency] audio \(audio.count / 16) ms, partials \(partials.count), time to first partial \(live.firstPartialMs.map(String.init) ?? "none") ms after speech onset, final \(finalMs) ms after stop")
        for (at, text) in partials.prefix(12) { print("[voice-latency]   +\(at) ms: \(text)") }
        print("[voice-latency] final: \(final)")
        #expect(!final.isEmpty)
        #expect(live.firstPartialMs != nil)
    }

    /// Time from the first streamed token to the first audio of the system voice, at 20 tokens/s.
    @Test(.enabled(if: !AVSpeechSynthesisVoice.speechVoices().isEmpty, "no system voice in this simulator"))
    func readAloudFirstAudio() async throws {
        let answer = "The forecast for Paris tomorrow is mostly sunny, with a high of 21 degrees. Winds stay light from the west, " +
            "and there is no rain expected until Thursday evening. Enjoy your day!"
        let stream = ReadAloudStream(speakable: { LiteMarkdown.speakable($0) }, maxChars: AutobotShared.VoiceText.shared.MAX_CHUNK)
        let speaker = SystemSpeaker()
        SpeechAudio.activate(.readAloud)
        defer {
            speaker.stop()
            SpeechAudio.deactivate()
        }
        var audioAt: Int64?
        speaker.onAudioStart = { if audioAt == nil { audioAt = monotonicMs() } }
        let start = monotonicMs()
        var firstPieceAt: Int64?
        var speaking: Task<Void, Error>?
        var sent = answer.startIndex
        // ~4 characters a token, 20 tokens a second.
        while sent < answer.endIndex, audioAt == nil {
            sent = answer.index(sent, offsetBy: 4, limitedBy: answer.endIndex) ?? answer.endIndex
            let pieces = stream.update(text: String(answer[..<sent]))
            if let first = pieces.first, speaking == nil {
                firstPieceAt = monotonicMs()
                speaking = Task { try await speaker.speak(first) }
            }
            try await Task.sleep(for: .milliseconds(50))
        }
        for _ in 0 ..< 100 where audioAt == nil { try await Task.sleep(for: .milliseconds(20)) }
        speaking?.cancel()
        let text = firstPieceAt.map { "\($0 - start)" } ?? "none"
        let audio = audioAt.map { "\($0 - start)" } ?? "none"
        let whole = answer.count / 4 * 50
        print("[voice-latency] read-aloud at 20 tok/s: first piece \(text) ms, first audio \(audio) ms after the answer started (before: after the whole answer, \(whole) ms, plus synthesis)")
        #expect(firstPieceAt != nil)
    }
}

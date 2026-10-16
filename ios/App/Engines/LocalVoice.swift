import AutobotShared
import AVFoundation
import Foundation
import Synchronization
#if canImport(AutobotEngines)
import AutobotEngines
#endif

/**
 Kokoro, the on-device neural voice: text in, 24 kHz mono float PCM out, sentence by sentence.
 Text → phonemes → token ids is the shared Kotlin frontend (`KokoroFrontend`: misaki's lexicons,
 no espeak); the model runs on Core ML (`AutobotEngines.TtsEngine`). [folder] is an installed
 Kokoro voice model (`ModelLibrary.dir(of:)`). Loads on first use and stays loaded until
 [unload]. Nothing is fetched or sent.
 */
actor LocalVoice {
    static let shared = LocalVoice()

    /// Output sample rate: 24 kHz mono.
    static let sampleRate = Int(KokoroFrontend.companion.SAMPLE_RATE)
    static let defaultVoice = KokoroFrontend.companion.DEFAULT_VOICE

    private var frontend: (folder: URL, value: KokoroFrontend)?

    /// One piece of speech: its text and Kokoro token ids.
    struct Piece: Sendable {
        let text: String
        let tokens: [Int32]
        let styleRow: Int
    }

    /**
     Speech for [text] as one PCM block per sentence (or part of a long one), each yielded as soon
     as it is synthesized, so playback can start after the first. [voice] is a voice id
     (`af_heart`; see [voices]); [speed] 1 is natural. Ending the iteration stops synthesis.
     */
    nonisolated func synthesize(folder: URL, text: String, voice: String = LocalVoice.defaultVoice, speed: Float = 1) -> AsyncThrowingStream<[Float], Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for piece in try await self.pieces(folder: folder, text: text, voice: voice) {
                        try Task.checkCancellation()
                        continuation.yield(try await Self.speak(folder: folder, piece: piece, voice: voice, speed: speed))
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error is CancellationError ? nil : Engines.map(error))
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// All of [text] as one PCM block (a short reply, or the next piece prepared while one plays).
    func synthesizeAll(folder: URL, text: String, voice: String = LocalVoice.defaultVoice, speed: Float = 1) async throws -> [Float] {
        var out: [Float] = []
        for try await block in synthesize(folder: folder, text: text, voice: voice, speed: speed) { out += block }
        return out
    }

    /// [text] as pieces for the engine (the shared frontend; the Core ML predictor takes 254 ids).
    func pieces(folder: URL, text: String, voice: String) throws -> [Piece] {
        let f = try load(folder)
        #if canImport(AutobotEngines)
        let max = Int32(TtsEngine.maxTokens)
        #else
        let max: Int32 = 254
        #endif
        return f.chunks(text: text, voice: voice, maxTokens: max).map { chunk in
            let ids = chunk.ids
            return Piece(text: chunk.text, tokens: (0 ..< ids.size).map { ids.get(index: $0) }, styleRow: Int(chunk.styleIndex))
        }
    }

    /// Loads the lexicons and the models now (first synthesis otherwise pays for it).
    func prepare(folder: URL) async throws {
        _ = try load(folder)
        #if canImport(AutobotEngines)
        do {
            try await EngineMemory.using(.tts) { try await TtsEngine.shared.prepare(folder: folder) }
        } catch {
            throw Engines.map(error)
        }
        #endif
    }

    /// Frees the models and lexicons.
    func unload() async {
        frontend = nil
        #if canImport(AutobotEngines)
        await TtsEngine.shared.unload()
        #endif
    }

    /// Voice ids (`af_heart`, …) installed in [folder], sorted.
    nonisolated static func voices(in folder: URL) -> [String] {
        let items = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        return items.filter { $0.hasSuffix(".bin") }.map { String($0.dropLast(4)) }.sorted()
    }

    private func load(_ folder: URL) throws -> KokoroFrontend {
        if let frontend, frontend.folder == folder { return frontend.value }
        guard FileManager.default.fileExists(atPath: folder.appending(path: KokoroFrontend.companion.CONFIG).path) else {
            throw LocalEngineError(code: "NO_MODEL", message: "The Kokoro voice is not installed. Get it in Models › voice · read aloud.")
        }
        do {
            let value = try KokoroFrontend.companion.load(dir: folder.path)
            frontend = (folder, value)
            return value
        } catch {
            throw LocalEngineError(code: "BAD_MODEL", message: "Cannot read the voice's lexicons (\(error.localizedDescription)). Remove the voice in Models and download it again.")
        }
    }

    private static func speak(folder: URL, piece: Piece, voice: String, speed: Float) async throws -> [Float] {
        #if canImport(AutobotEngines)
        do {
            return try await EngineMemory.using(.tts) {
                try await TtsEngine.shared.synthesize(folder: folder, tokens: piece.tokens, styleRow: piece.styleRow, voice: voice, speed: speed)
            }
        } catch {
            throw Engines.map(error)
        }
        #else
        throw EngineUnavailable()
        #endif
    }
}

/// Playback stopped because the audio system took the output away; [message] is user-facing.
struct VoiceInterrupted: LocalizedError, Equatable {
    let message: String
    var errorDescription: String? { message }
}

/**
 Plays mono float PCM (Kokoro's 24 kHz) as it arrives: each block is scheduled on an
 `AVAudioPlayerNode` right away, so the first sentence plays while the next is synthesized.
 [play] returns when everything has been heard, or right after [stop] / cancellation. It throws
 [VoiceInterrupted] when an audio interruption (a call, Siri), a route or configuration change
 that stops the engine, or a media services reset cuts playback, and gives up waiting once the
 scheduled audio should long have finished. The caller sets up the audio session.
 */
@MainActor
final class VoicePlayer {
    private let output: any VoiceOutput
    private let format: AVAudioFormat
    private var stopped = false
    private var interruption: VoiceInterrupted?

    /// Grace after the scheduled audio's length before [play] stops waiting for it.
    static let drainGrace: Duration = .seconds(3)

    private final class Pending: Sendable {
        let count = Atomic<Int>(0)
    }

    init(sampleRate: Int = LocalVoice.sampleRate, output: (any VoiceOutput)? = nil) {
        format = AVAudioFormat(standardFormatWithSampleRate: Double(sampleRate), channels: 1)!
        self.output = output ?? EngineVoiceOutput(format: format)
    }

    func play(_ audio: AsyncThrowingStream<[Float], Error>) async throws {
        stopped = false
        interruption = nil
        let observers = observe()
        defer { observers.forEach { NotificationCenter.default.removeObserver($0) } }
        try output.start()
        defer { output.stop() }
        let pending = Pending()
        var scheduled: Double = 0
        for try await block in audio {
            if let interruption { throw interruption }
            if stopped || Task.isCancelled { return }
            guard !block.isEmpty, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(block.count)) else { continue }
            buffer.frameLength = AVAudioFrameCount(block.count)
            block.withUnsafeBufferPointer { buffer.floatChannelData![0].update(from: $0.baseAddress!, count: block.count) }
            pending.count.add(1, ordering: .relaxed)
            scheduled += Double(block.count) / format.sampleRate
            output.schedule(buffer, completion: Self.completion(pending))
        }
        let deadline = ContinuousClock.now + .seconds(scheduled) + Self.drainGrace
        while !stopped, !Task.isCancelled, pending.count.load(ordering: .relaxed) > 0 {
            if let interruption { throw interruption }
            if ContinuousClock.now >= deadline { return }
            try? await Task.sleep(for: .milliseconds(20))
        }
        if let interruption { throw interruption }
    }

    /// Stops playback now; a suspended [play] returns.
    func stop() {
        stopped = true
        output.halt()
    }

    /**
     The completion of one buffer, built outside the main actor: Core Audio calls it on its render
     thread, and a closure formed in a main-actor method would be main-actor isolated (Swift 6
     traps when it runs anywhere else).
     */
    private nonisolated static func completion(_ pending: Pending) -> @Sendable (AVAudioPlayerNodeCompletionCallbackType) -> Void {
        { @Sendable _ in pending.count.subtract(1, ordering: .relaxed) }
    }

    private func interrupted(_ message: String) {
        guard interruption == nil, !stopped else { return }
        interruption = VoiceInterrupted(message: message)
        output.halt()
    }

    private func observe() -> [any NSObjectProtocol] {
        let center = NotificationCenter.default
        return [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
                let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init(rawValue:))
                guard type == .began else { return }
                MainActor.assumeIsolated { self?.interrupted("Playback was interrupted") }
            },
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.interrupted("The audio system restarted") }
            },
        ] + [output.engine].compactMap { engine in
            center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.interrupted("The audio output changed") }
            }
        }
    }
}

/// Where [VoicePlayer] plays its buffers: the audio engine (tests substitute a silent output).
@MainActor
protocol VoiceOutput: AnyObject {
    /// The engine whose configuration change means the output was lost (nil: none).
    var engine: AVAudioEngine? { get }
    func start() throws
    /// Stops playing now (stop, interruption); [stop] also stops the engine at the end of [VoicePlayer.play].
    func halt()
    func stop()
    func schedule(_ buffer: AVAudioPCMBuffer, completion: @escaping @Sendable (AVAudioPlayerNodeCompletionCallbackType) -> Void)
}

/// An `AVAudioPlayerNode` on its own `AVAudioEngine`.
@MainActor
final class EngineVoiceOutput: VoiceOutput {
    private let audio = AVAudioEngine()
    private let node = AVAudioPlayerNode()

    init(format: AVAudioFormat) {
        audio.attach(node)
        audio.connect(node, to: audio.mainMixerNode, format: format)
    }

    var engine: AVAudioEngine? { audio }

    func start() throws {
        try audio.start()
        node.play()
    }

    func halt() { node.stop() }

    func stop() {
        node.stop()
        audio.stop()
    }

    func schedule(_ buffer: AVAudioPCMBuffer, completion: @escaping @Sendable (AVAudioPlayerNodeCompletionCallbackType) -> Void) {
        node.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack, completionHandler: completion)
    }
}

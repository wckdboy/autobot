@preconcurrency import AVFoundation
import Foundation
import Synchronization

/**
 * Records the microphone as 16 kHz mono float PCM for [SpeechEngine] (the iOS side of Android's
 * `LocalSpeech.record`). Samples stay in memory only. Needs NSMicrophoneUsageDescription.
 */
public final class AudioRecorder: Sendable {
    public static let sampleRate = 16_000.0

    private struct State: ~Copyable {
        var engine: AVAudioEngine?
        var samples: [Float] = []
        var managesSession = true
    }

    private let state = Mutex(State())
    private let maxSamples: Int

    public init(maxSeconds: Int = 120) {
        maxSamples = Int(Self.sampleRate) * maxSeconds
    }

    /// Asks for microphone access (only when first needed, like Android's RECORD_AUDIO).
    public static func requestPermission() async -> Bool {
        await AVAudioApplication.requestRecordPermission()
    }

    public var isRecording: Bool { state.withLock { $0.engine != nil } }

    /**
     * Starts recording. [onLevel] gets the peak level (0…1) of each buffer, on an audio thread.
     * [managesSession]: switch the audio session to recording and release it on stop; false when
     * the app has set up a session that can record (play-and-record for a voice conversation) and
     * keeps it, so speech can play right after.
     */
    public func start(managesSession: Bool = true, onLevel: @escaping @Sendable (Float) -> Void = { _ in }) throws {
        guard AVAudioApplication.shared.recordPermission == .granted else {
            throw EngineError("NO_PERMISSION", "microphone access is off")
        }
        _ = stop()
        if managesSession {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        }

        let engine = AVAudioEngine()
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0,
              let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Self.sampleRate, channels: 1, interleaved: false),
              let converter = AVAudioConverter(from: inputFormat, to: target)
        else { throw EngineError("NO_MICROPHONE", "microphone unavailable") }

        let ratio = Self.sampleRate / inputFormat.sampleRate
        input.installTap(onBus: 0, bufferSize: 4096, format: inputFormat) { [weak self] buffer, _ in
            guard let self else { return }
            let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 32
            guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
            var consumed = false
            var error: NSError?
            converter.convert(to: out, error: &error) { _, status in
                if consumed {
                    status.pointee = .noDataNow
                    return nil
                }
                consumed = true
                status.pointee = .haveData
                return buffer
            }
            guard error == nil, let channel = out.floatChannelData?[0] else { return }
            let chunk = UnsafeBufferPointer(start: channel, count: Int(out.frameLength))
            var peak: Float = 0
            for s in chunk { peak = max(peak, abs(s)) }
            let full = self.state.withLock { state in
                let room = self.maxSamples - state.samples.count
                state.samples.append(contentsOf: chunk.prefix(max(room, 0)))
                return state.samples.count >= self.maxSamples
            }
            onLevel(min(peak, 1))
            // Hit the limit: stop capturing (off the tap thread) but keep the samples for stop().
            if full { DispatchQueue.global().async { _ = self.stop(keep: true) } }
        }
        engine.prepare()
        try engine.start()
        // The engine is only touched under the lock from here on; older compilers cannot prove the
        // hand-off of this non-Sendable reference into the mutex is safe.
        nonisolated(unsafe) let running = engine
        state.withLock {
            $0.samples = []
            $0.engine = running
            $0.managesSession = managesSession
        }
    }

    /// Stops recording and returns everything recorded since [start].
    @discardableResult
    public func stop() -> [Float] {
        stop(keep: false)
    }

    private func stop(keep: Bool) -> [Float] {
        let (engine, samples, release) = state.withLock { state -> (AVAudioEngine?, [Float], Bool) in
            let engine = state.engine
            state.engine = nil
            if keep { return (engine, [], state.managesSession) }
            defer { state.samples = [] }
            return (engine, state.samples, state.managesSession)
        }
        if let engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
            if release { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        }
        return samples
    }
}

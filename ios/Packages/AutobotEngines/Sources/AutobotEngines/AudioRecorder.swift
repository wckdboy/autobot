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
        var keepSamples = true
        var observers: [any NSObjectProtocol] = []
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
     * keeps it, so speech can play right after. A route change (headset, Bluetooth A2DP ↔ HFP)
     * restarts capture in the new format; an audio interruption, a media services reset or a
     * restart that fails stops it (the samples so far are kept for [stop]) and calls
     * [onInterruption] on an arbitrary thread.
     *
     * [onSamples] gets each converted 16 kHz buffer on the audio thread (live dictation); with
     * [keepSamples] false nothing accumulates and [stop] returns no samples, so memory stays at
     * what the consumer keeps. [voiceProcessing]: Apple's voice-processing I/O (echo cancellation,
     * noise suppression) with other audio ducked as little as possible, for listening while an
     * answer plays; ignored where the route cannot do it.
     */
    public func start(
        managesSession: Bool = true,
        keepSamples: Bool = true,
        voiceProcessing: Bool = false,
        onLevel: @escaping @Sendable (Float) -> Void = { _ in },
        onSamples: (@Sendable (UnsafeBufferPointer<Float>) -> Void)? = nil,
        onInterruption: @escaping @Sendable () -> Void = {},
    ) throws {
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
        if voiceProcessing {
            // Before the engine starts; the input format changes with it. Best effort: some routes
            // (and the simulator) refuse, and the energy gate behind it still holds.
            if (try? input.setVoiceProcessingEnabled(true)) != nil {
                input.voiceProcessingOtherAudioDuckingConfiguration = .init(enableAdvancedDucking: false, duckingLevel: .min)
            }
        }
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let target = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Self.sampleRate, channels: 1, interleaved: false)
        else { throw EngineError("NO_MICROPHONE", "microphone unavailable") }

        // format nil: the tap follows the input's current format, which changes with the route; a
        // fixed format that no longer matches raises an Objective-C exception. The converter is
        // rebuilt whenever a buffer arrives in another format.
        let converter = ConverterCache(target: target)
        input.installTap(onBus: 0, bufferSize: 4096, format: nil) { [weak self] buffer, _ in
            guard let self, let out = converter.convert(buffer) else { return }
            guard let channel = out.floatChannelData?[0] else { return }
            let chunk = UnsafeBufferPointer(start: channel, count: Int(out.frameLength))
            var peak: Float = 0
            for s in chunk where s.isFinite { peak = max(peak, abs(s)) }
            let full = self.state.withLock { state in
                guard state.keepSamples else { return false }
                let room = self.maxSamples - state.samples.count
                state.samples.append(contentsOf: chunk.prefix(max(room, 0)))
                return state.samples.count >= self.maxSamples
            }
            onSamples?(chunk)
            onLevel(min(peak, 1))
            // Hit the limit: stop capturing (off the tap thread) but keep the samples for stop().
            if full { DispatchQueue.global().async { _ = self.stop(keep: true) } }
        }
        engine.prepare()
        try engine.start()
        // The engine is only touched under the lock from here on; older compilers cannot prove the
        // hand-off of this non-Sendable reference into the mutex is safe.
        nonisolated(unsafe) let running = engine
        let center = NotificationCenter.default
        let interrupted: @Sendable () -> Void = { [weak self] in
            guard let self, self.stop(keep: true, ifRunning: running) else { return }
            onInterruption()
        }
        nonisolated(unsafe) let observers: [any NSObjectProtocol] = [
            center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
                // The engine stopped itself for the new route: run it again in the new format.
                guard let self else { return }
                let restarted = self.state.withLock { state -> Bool in
                    guard state.engine === running else { return true }
                    return (try? running.start()) != nil
                }
                if !restarted { interrupted() }
            },
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { note in
                let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init(rawValue:))
                if type == .began { interrupted() }
            },
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil) { _ in interrupted() },
        ]
        state.withLock {
            $0.samples = []
            $0.engine = running
            $0.managesSession = managesSession
            $0.keepSamples = keepSamples
            $0.observers = observers
        }
    }

    /// Stops recording and returns everything recorded since [start].
    @discardableResult
    public func stop() -> [Float] {
        stop(keep: false)
    }

    private func stop(keep: Bool) -> [Float] {
        stop(keep: keep, only: nil).samples
    }

    /// Stops [engine] if it is still the one recording (keeping the samples); false if it was not.
    @discardableResult
    private func stop(keep: Bool, ifRunning engine: AVAudioEngine) -> Bool {
        stop(keep: keep, only: engine).stopped
    }

    private func stop(keep: Bool, only: AVAudioEngine?) -> (samples: [Float], stopped: Bool) {
        let (engine, samples, release, observers) = state.withLock { state -> (AVAudioEngine?, [Float], Bool, [any NSObjectProtocol]) in
            guard only == nil || state.engine === only else { return (nil, [], false, []) }
            let engine = state.engine
            let observers = state.observers
            state.engine = nil
            state.observers = []
            if keep { return (engine, [], state.managesSession, observers) }
            defer { state.samples = [] }
            return (engine, state.samples, state.managesSession, observers)
        }
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        if let engine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
            if release { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
        }
        return (samples, engine != nil)
    }
}

/// The tap's converter to 16 kHz mono, rebuilt when the input format changes (route changes).
/// Only the tap thread uses it.
private final class ConverterCache: @unchecked Sendable {
    private let target: AVAudioFormat
    private var converter: AVAudioConverter?

    init(target: AVAudioFormat) {
        self.target = target
    }

    func convert(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        let format = buffer.format
        guard format.sampleRate > 0, format.channelCount > 0, buffer.frameLength > 0 else { return nil }
        if converter?.inputFormat != format { converter = AVAudioConverter(from: format, to: target) }
        guard let converter else { return nil }
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * target.sampleRate / format.sampleRate) + 32
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return nil }
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
        return error == nil ? out : nil
    }
}

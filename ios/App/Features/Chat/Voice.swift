import AutobotShared
import AVFoundation
import Foundation

// Read-aloud and voice mode (port of `feature/chat/.../voice/`). The text rules (what to read, in
// which pieces, when the speaker stopped talking) are the shared Kotlin ones in `shared/.../voice`.

/// Why a [Speaker] cannot talk; [message] is shown to the user as is.
struct SpeakerUnavailable: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/**
 Reads text aloud on the device. Read-aloud and voice mode only talk to this, so another engine (a
 neural voice) plugs in as a second implementation picked by [Speakers.pick]. Implementations
 must never synthesize over the network. The caller sets up the audio session ([SpeechAudio]).
 */
@MainActor
protocol Speaker: AnyObject {
    /// Stable id for the setting that picks the engine (`system`, …).
    var id: String { get }
    /// Whether it can speak right now (an on-device voice or model is installed).
    var isAvailable: Bool { get }
    /// Speaks one plain-text piece (see [read]) and returns once it has been spoken or [stop]ped.
    /// Cancelling the task stops it too. Throws [SpeakerUnavailable] with a user-facing reason.
    func speak(_ text: String) async throws
    /// Hint: [text] is the next piece; an engine with synthesis latency can start on it now.
    func prepare(_ text: String)
    /// Stops speaking now; a suspended [speak] returns.
    func stop()
}

extension Speaker {
    func prepare(_ text: String) {}

    /// Reads [markdown] sentence by sentence: speech starts after the first one and stops between any two.
    /// [onChunk] hears each piece's index as it starts (voice mode's speaking strip pulses with it).
    func read(_ markdown: String, onChunk: (Int) -> Void = { _ in }) async throws {
        let text = AutobotShared.VoiceText.shared
        let chunks = text.chunks(text: text.speakable(markdown: markdown), maxChars: text.MAX_CHUNK)
        for (i, chunk) in chunks.enumerated() {
            // stop() ends the piece being spoken; a cancelled task must not start the next one.
            try Task.checkCancellation()
            if i + 1 < chunks.count { prepare(chunks[i + 1]) }
            onChunk(i)
            try await speak(chunk)
        }
    }
}

/// The installed speech engines.
@MainActor
enum Speakers {
    private static var all: [any Speaker] = [SystemSpeaker()]

    /// The engine with [id] (a setting, later), else the neural voice when one is downloaded,
    /// else the system one.
    static func pick(_ id: String? = nil) -> any Speaker {
        all.first { $0.id == id }
            ?? all.first { $0.id == KokoroSpeaker.kokoroId && $0.isAvailable }
            ?? all.first { $0.id == SystemSpeaker.systemId }!
    }

    /// Adds an engine (a neural voice) next to the system one.
    static func register(_ speaker: any Speaker) {
        all.removeAll { $0.id == speaker.id }
        all.append(speaker)
    }
}

/**
 The audio session for speech. Dictation leaves it to `AudioRecorder` (record, released on stop);
 read-aloud plays (`.playback`, spoken audio); voice mode keeps one play-and-record session for
 the whole conversation so the answer is audible right after the microphone stops.
 */
enum SpeechAudio {
    enum Use { case readAloud, voice }

    static func activate(_ use: Use) {
        let session = AVAudioSession.sharedInstance()
        switch use {
        case .readAloud:
            try? session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        case .voice:
            try? session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetoothHFP, .allowBluetoothA2DP, .duckOthers])
        }
        try? session.setActive(true)
    }

    static func deactivate() {
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }
}

/// A system voice as [SystemSpeaker.pick] sees it.
struct VoiceCandidate: Equatable {
    let identifier: String
    /// BCP 47, `en-US`.
    let language: String
    /// 1 default, 2 enhanced, 3 premium.
    let quality: Int
    /// Novelty (Bells, Bubbles…) and Personal Voice are never picked.
    var special = false
}

/// The system speech synthesizer: its voices are on the device, the best installed one is used.
@MainActor
final class SystemSpeaker: Speaker {
    nonisolated static let systemId = "system"
    var id: String { Self.systemId }

    private let synthesizer = AVSpeechSynthesizer()
    private let delegate = SpeechDelegate()
    private var waiting: [ObjectIdentifier: CheckedContinuation<Void, Never>] = [:]
    /// The voice chosen for the current language (voices change rarely; [stop] forgets it).
    private var chosen: (language: String, voice: AVSpeechSynthesisVoice)?

    init() {
        synthesizer.delegate = delegate
        delegate.onEnd = { [weak self] id in self?.waiting.removeValue(forKey: id)?.resume() }
    }

    var isAvailable: Bool { !AVSpeechSynthesisVoice.speechVoices().isEmpty }

    func speak(_ text: String) async throws {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        try Task.checkCancellation()
        guard let voice = voice() else { throw SpeakerUnavailable(message: "No voice installed — add one in Settings › Accessibility › Spoken Content › Voices") }
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = voice
        let key = ObjectIdentifier(utterance)
        await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                waiting[key] = continuation
                synthesizer.speak(utterance)
            }
        } onCancel: {
            Task { @MainActor in self.stop() }
        }
    }

    func stop() {
        synthesizer.stopSpeaking(at: .immediate)
        let pending = waiting.values
        waiting.removeAll()
        pending.forEach { $0.resume() }
        chosen = nil
    }

    private func voice() -> AVSpeechSynthesisVoice? {
        let language = AVSpeechSynthesisVoice.currentLanguageCode()
        if let chosen, chosen.language == language { return chosen.voice }
        let voices = AVSpeechSynthesisVoice.speechVoices()
        let candidates = voices.map { v in
            VoiceCandidate(
                identifier: v.identifier,
                language: v.language,
                quality: v.quality.rawValue,
                special: v.voiceTraits.contains(.isNoveltyVoice) || v.voiceTraits.contains(.isPersonalVoice),
            )
        }
        guard let id = Self.pick(candidates, preferred: language), let voice = voices.first(where: { $0.identifier == id }) else { return nil }
        chosen = (language, voice)
        return voice
    }

    /**
     The voice to use: the [preferred] language and region, else the language, else English, else
     any; then premium over enhanced over default. Novelty and personal voices are skipped.
     */
    nonisolated static func pick(_ voices: [VoiceCandidate], preferred: String) -> String? {
        func base(_ tag: String) -> String { String(tag.split(separator: "-").first ?? "").lowercased() }
        func rank(_ v: VoiceCandidate) -> Int {
            if v.language.lowercased() == preferred.lowercased() { return 3 }
            if base(v.language) == base(preferred) { return 2 }
            return base(v.language) == "en" ? 1 : 0
        }
        return voices.filter { !$0.special }.max { a, b in
            (rank(a), a.quality, b.identifier) < (rank(b), b.quality, a.identifier)
        }?.identifier
    }
}

private final class SpeechDelegate: NSObject, AVSpeechSynthesizerDelegate, @unchecked Sendable {
    /// Called on the main actor when an utterance finished or was cancelled.
    @MainActor var onEnd: ((ObjectIdentifier) -> Void)?

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { ended(utterance) }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) { ended(utterance) }

    private func ended(_ utterance: AVSpeechUtterance) {
        let id = ObjectIdentifier(utterance)
        DispatchQueue.main.async { MainActor.assumeIsolated { self.onEnd?(id) } }
    }
}

/**
 Follows one voice-mode turn (port of the shared Kotlin `VoiceTurn`): from the moment the prompt
 is sent ([baseline] = the transcript keys before it) until the agent is idle again. The prompt is
 queued until a turn picks it up, so the turn counts only once that prompt shows in the transcript.
 */
final class VoiceTurn {
    /// Idle this long without the prompt appearing: give up (the send failed).
    static let timeoutMs: Int64 = 15_000

    private let baseline: Set<String>
    private let startedAtMs: Int64
    private var sawRunning = false
    private var announced: Set<String> = []

    init(baseline: Set<String>, startedAtMs: Int64) {
        self.baseline = baseline
        self.startedAtMs = startedAtMs
    }

    /// How the turn ended: [reply] is the answer to read aloud (nil: nothing to read).
    struct Finished: Equatable { let reply: String? }

    /// Call on every state change. Returns [Finished] once the agent has run the prompt and
    /// stopped (or never picked it up within [timeoutMs] while idle); nil while the turn is under way.
    func update(items: [SessionItem], running: Bool, nowMs: Int64) -> Finished? {
        let fresh = items.filter { !baseline.contains($0.key) }
        guard let prompt = fresh.lastIndex(where: { if case .user = $0 { true } else { false } }) else {
            return !running && nowMs - startedAtMs >= Self.timeoutMs ? Finished(reply: nil) : nil
        }
        if running {
            sawRunning = true
            return nil
        }
        let after = Array(fresh[(prompt + 1)...])
        if !sawRunning && after.isEmpty { return nil }
        // The last answer, unless it was stopped (the user stopped it) or an error followed it.
        guard let reply = after.lastIndex(where: { if case .assistant(_, let text, _, _, _, _, _) = $0 { !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty } else { false } }),
              case .assistant(_, let text, _, let interrupted, _, _, _) = after[reply], !interrupted,
              !after[(reply + 1)...].contains(where: { if case .notice(_, _, .error) = $0 { true } else { false } })
        else { return Finished(reply: nil) }
        return Finished(reply: text)
    }

    /// True when [waiting] (approval / question call ids) holds one not announced yet.
    func needsAnnouncement(_ waiting: [String]) -> Bool {
        let new = Set(waiting).subtracting(announced)
        announced.formUnion(new)
        return !new.isEmpty
    }
}

/// Milliseconds on a monotonic clock (for [VoiceTurn] and `EndOfSpeech`).
func monotonicMs() -> Int64 { Int64(ProcessInfo.processInfo.systemUptime * 1000) }

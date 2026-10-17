import AutobotShared
import Foundation
import ImageIO
import Observation
import UIKit

enum Dictation { case idle, recording, transcribing }

/// Voice mode's loop: ready → listening → transcribing → thinking (agent turn) → speaking → ready.
enum VoicePhase { case ready, listening, transcribing, thinking, speaking }

/// Voice mode ([handsFree]: listen again after each answer, stop at the end of speech).
struct VoiceState: Equatable {
    var phase = VoicePhase.ready
    var handsFree = false
    /// Spoken pieces started (the speaking strip pulses on each).
    var spoken = 0
}

/// Recent microphone peaks (0…1, oldest first), observed on their own: only the level meter
/// reads them, so ~10 updates a second do not re-render the chat screen.
@MainActor
@Observable
final class VoiceLevels {
    private(set) var peaks: [Float] = []

    func append(_ peak: Float, keep: Int) {
        var next = peaks
        if next.count >= keep { next.removeFirst(next.count - keep + 1) }
        next.append(peak)
        peaks = next
    }

    func clear() { if !peaks.isEmpty { peaks = [] } }
}

/// Prepares picked images for vision models (port of `ImageInput.kt`).
enum ImageInput {
    /// Longest side sent to a model; vision encoders resample to a few hundred pixels anyway.
    static let maxSide: CGFloat = 1344

    /**
     Decodes [data] (any format iOS reads, including HEIC) straight to at most [maxSide], upright,
     and re-encodes it as JPEG. ImageIO's thumbnail path never decodes the full image (a 48 MP
     photo would take ~200 MB), and the new JPEG carries no EXIF, so location and camera data
     never reach a model or the attachment store.
     */
    static func encode(_ data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary), image.width > 0, image.height > 0 else { return nil }
        return ImageCodec.jpeg(image, quality: 0.88)
    }
}

/**
 Screen-local state of a session: attachments being composed, dictation, read-aloud, notices and
 decoded thumbnails (port of the UI half of `ChatViewModel.kt`; the agent state comes from
 [AgentSession]).
 */
@MainActor
@Observable
final class ChatModel {
    static let maxAttachments = 4

    var notice: String?
    /// Images attached to the message being written (attachment ids).
    var pendingImages: [String] = []
    var dictation: Dictation = .idle
    /// Text dictated since the composer last took it.
    var dictated: String?
    /// Key of the assistant message being read aloud.
    var speakingKey: String?
    /// Voice mode; nil when off.
    var voice: VoiceState?
    /// Microphone levels while voice mode listens.
    let levels = VoiceLevels()

    @ObservationIgnored private var thumbs: [String: UIImage] = [:]
    @ObservationIgnored private var readTask: Task<Void, Never>?
    @ObservationIgnored private var voiceTask: Task<Void, Never>?
    @ObservationIgnored private var voiceApp: AppContainer?
    @ObservationIgnored private var voiceConversation: String?
    @ObservationIgnored private var turn: VoiceTurn?
    @ObservationIgnored private var endOfSpeech: EndOfSpeech?
    /// Words heard so far while dictating or listening (only the live words line observes it).
    let heardWords = HeardWords()
    /// The dictation listening now (composer or voice mode).
    @ObservationIgnored private var live: LiveDictation?
    /// A composer dictation asking for the microphone (a second tap must not start another).
    @ObservationIgnored private var dictationStart: UUID?
    /// The voice answer being read while it streams, its speaking queue and task.
    @ObservationIgnored private var reading: ReadAloudStream?
    @ObservationIgnored private var queue: SpeechQueue?
    @ObservationIgnored private var queueTask: Task<Void, Never>?
    /// Hands-free barge-in while the answer plays, and the last audio it heard (the first words).
    @ObservationIgnored private var barge: BargeIn?
    @ObservationIgnored private var bargeRing: [Float] = []
    /// When this voice turn's answer started streaming, and how long until its first piece played.
    @ObservationIgnored private var answerStartedAt: Int64?
    @ObservationIgnored private(set) var lastFirstAudioMs: Int64?

    private static let levelHistory = 32
    /// Audio kept from the barge-in microphone for the dictation it starts (0.8 s).
    private static let bargePreroll = 12_800

    /// The speaker reading now (kept so stop() reaches the one that speaks even if the pick
    /// changed meanwhile, e.g. the neural voice finished downloading).
    @ObservationIgnored private var activeSpeaker: (any Speaker)?

    /// The speaker for a new reading (picked once per reading, not on every access).
    private func startSpeaker() -> any Speaker {
        let s = Speakers.pick()
        if let active = activeSpeaker, active !== s { active.stop() }
        activeSpeaker = s
        return s
    }

    private func stopSpeaker() { activeSpeaker?.stop() }

    // MARK: Attachments

    /// Attaches a picked image: downscaled and re-encoded (which also drops EXIF/GPS metadata).
    func attach(_ data: Data, app: AppContainer) async {
        guard pendingImages.count < Self.maxAttachments else {
            notice = "Up to \(Self.maxAttachments) images per message"
            return
        }
        let encoded = await Task.detached { ImageInput.encode(data) }.value
        guard let encoded, let id = try? app.attachments.put(encoded) else {
            notice = "Could not read that image"
            return
        }
        pendingImages.append(id)
    }

    func removeAttachment(_ id: String, app: AppContainer) {
        pendingImages.removeAll { $0 == id }
        app.attachments.delete(id)
    }

    /// Small preview of an attachment (composer and transcript).
    func attachmentThumbnail(_ id: String, app: AppContainer) async -> UIImage? {
        if let cached = thumbs["a-\(id)"] { return cached }
        // Read and decrypted off the main actor, like the decode.
        let attachments = app.attachments
        guard let bytes = await Task.detached(priority: .userInitiated, operation: { attachments.bytes(id) }).value,
              let image = await decodeImage(bytes, maxSide: 256) else { return nil }
        thumbs["a-\(id)"] = image
        return image
    }

    /// Decrypted gallery thumbnail for tool cards.
    func galleryThumbnail(_ id: String, app: AppContainer) async -> UIImage? {
        if let cached = thumbs["g-\(id)"] { return cached }
        // `run_workflow` results are Studio assets (`a-…`), not gallery items.
        if app.gallery.get(id) == nil, let asset = app.studio.library.asset(id) {
            let image = await app.studio.thumbnail(asset)
            thumbs["g-\(id)"] = image
            return image
        }
        guard let bytes = await app.gallery.thumbnail(id), let image = await decodeImage(bytes) else { return nil }
        thumbs["g-\(id)"] = image
        return image
    }


    // MARK: Dictation

    /// Starts dictation (live words while recording), or stops it and gives the final transcript.
    func toggleDictation(app: AppContainer) {
        switch dictation {
        case .transcribing:
            return
        case .recording:
            guard let live else {
                dictation = .idle
                return
            }
            dictation = .transcribing
            Task {
                let text = AutobotShared.VoiceText.shared.transcript(raw: await live.finish())
                if self.live === live { self.live = nil }
                heardWords.clear()
                if !text.isEmpty { dictated = text }
                dictation = .idle
                if voice == nil { SpeechAudio.deactivate() }
            }
        case .idle:
            guard dictationStart == nil else { return }
            guard let path = speechModelPath(app) else {
                notice = "Get a speech model in Models → speech to dictate"
                return
            }
            stopSpeaking()
            let start = UUID()
            dictationStart = start
            Task {
                defer { if dictationStart == start { dictationStart = nil } }
                // Asked only when the microphone is first needed.
                guard await app.speech.requestPermission() else {
                    notice = "Microphone access is off — allow it in Settings to dictate"
                    return
                }
                // Stopped (screen left, voice mode) while the permission was asked.
                guard dictationStart == start else { return }
                SpeechAudio.activate(.dictation)
                let live = makeLive(app: app, path: path)
                do {
                    try live.start(onLevel: { _ in }, onInterruption: { [weak self] in self?.recordingInterrupted(app: app) })
                    self.live = live
                    dictation = .recording
                } catch {
                    SpeechAudio.deactivate()
                    notice = "Dictation failed: \(error.localizedDescription)"
                }
            }
        }
    }

    func consumeDictation() { dictated = nil }

    private func speechModelPath(_ app: AppContainer) -> String? {
        guard let model = app.library.models.first(where: { $0.isReady && $0.engine == .whisper }) else { return nil }
        return app.library.paths(model)[.model]?.path
    }

    private func makeLive(app: AppContainer, path: String) -> LiveDictation {
        let live = LiveDictation(engine: LocalDictationEngine(speech: app.speech, modelPath: path))
        live.onText = { [weak self] text in self?.heardWords.set(text) }
        return live
    }

    /// The microphone was taken away (a call, Siri, media services reset): end the recording.
    private func recordingInterrupted(app: AppContainer) {
        if dictation == .recording {
            live?.cancel()
            live = nil
            heardWords.clear()
            dictation = .idle
            notice = "Dictation stopped: the microphone was interrupted"
            if voice == nil { SpeechAudio.deactivate() }
        }
        if voice?.phase == .listening {
            live?.cancel()
            live = nil
            heardWords.clear()
            endOfSpeech = nil
            voiceFailed("Voice stopped: the microphone was interrupted")
        }
    }

    func stopRecording(app: AppContainer) {
        dictationStart = nil
        if dictation == .recording {
            live?.cancel()
            live = nil
            heardWords.clear()
            dictation = .idle
            // The recording session would keep other apps' audio silenced.
            if voice == nil { SpeechAudio.deactivate() }
        }
    }

    // MARK: Read aloud

    /// Reads an answer aloud, on the device only (see [Speakers]); tapping again stops.
    func speak(key: String, text: String) {
        readTask?.cancel()
        if speakingKey == key {
            stopSpeaking()
            return
        }
        if voice != nil {
            stopVoiceWork()
        } else {
            SpeechAudio.activate(.readAloud)
        }
        stopSpeaker()
        speakingKey = key
        let speaker = startSpeaker()
        readTask = Task {
            do {
                try await speaker.read(text)
            } catch let e as SpeakerUnavailable {
                notice = e.message
            } catch {}
            guard speakingKey == key else { return }
            speakingKey = nil
            if voice == nil { SpeechAudio.deactivate() }
        }
    }

    func stopSpeaking() {
        readTask?.cancel()
        stopSpeaker()
        if speakingKey != nil, voice == nil { SpeechAudio.deactivate() }
        speakingKey = nil
    }

    // MARK: Voice mode: talk (live words) → send → read the answer while it streams, on the phone

    func setVoiceMode(_ on: Bool, app: AppContainer, conversationId: String) {
        guard on != (voice != nil) else { return }
        stopVoiceWork()
        guard on else {
            voice = nil
            app.agent.existingSession(conversationId)?.onUpdate = nil
            SpeechAudio.deactivate()
            Task { await EngineMemory.shared.release([.tts, .whisper]) }
            return
        }
        stopSpeaking()
        stopRecording(app: app)
        voiceApp = app
        voiceConversation = conversationId
        SpeechAudio.activate(.voice)
        voice = VoiceState()
        app.agent.session(conversationId).onUpdate = { [weak self] session in self?.sessionChanged(session.state) }
    }

    func setHandsFree(_ on: Bool) { voice?.handsFree = on }

    /// The voice button: talk, send what was said, stop the agent, or interrupt the answer and talk.
    func voiceTap() {
        switch voice?.phase {
        case .ready: listen()
        case .listening: finishListening()
        case .transcribing, nil: break
        case .thinking: if let voiceApp, let voiceConversation { voiceApp.agent.cancel(voiceConversation) }
        case .speaking: interrupt()
        }
    }

    /// Listens with live words on screen. [preroll]: audio heard just before (a barge-in's first words).
    private func listen(preroll: [Float]? = nil) {
        guard let app = voiceApp else { return }
        guard let path = speechModelPath(app) else { return voiceFailed("Get a speech model in Models › speech") }
        voiceTask?.cancel()
        stopSpeaker()
        voiceTask = Task {
            // Asked only when the microphone is first needed.
            guard await app.speech.requestPermission() else { return voiceFailed("Microphone access is off — allow it in Settings to talk") }
            guard !Task.isCancelled, voice != nil else { return }
            endOfSpeech = voice?.handsFree == true ? EndOfSpeech(threshold: 0.05, silenceMs: 1_200, minSpeechMs: 200, noSpeechMs: 8_000) : nil
            // After a barge-in the user is mid-sentence: the detector must not wait for them to start.
            if preroll != nil { endOfSpeech?.primeHeard() }
            let live = makeLive(app: app, path: path)
            do {
                try live.start(preroll: preroll, onLevel: { [weak self] peak in self?.heard(peak) }, onInterruption: { [weak self] in self?.recordingInterrupted(app: app) })
                self.live = live
                setPhase(.listening)
            } catch {
                voiceFailed("Voice failed: \(error.localizedDescription)")
            }
        }
    }

    /// One microphone level: the meter, and hands-free end-of-speech detection.
    private func heard(_ peak: Float) {
        guard voice?.phase == .listening else { return }
        levels.append(peak, keep: Self.levelHistory)
        guard let endOfSpeech else { return }
        let verdict = endOfSpeech.onLevel(peak: peak, atMs: monotonicMs())
        if verdict == .ended {
            finishListening()
        } else if verdict == .noSpeech {
            live?.cancel()
            live = nil
            heardWords.clear()
            voiceFailed("Didn't catch that")
        }
    }

    private func finishListening() {
        guard let app = voiceApp, let conversationId = voiceConversation, let live else { return }
        endOfSpeech = nil
        setPhase(.transcribing)
        voiceTask?.cancel()
        voiceTask = Task {
            let raw = await live.finish()
            if self.live === live { self.live = nil }
            heardWords.clear()
            guard !Task.isCancelled else { return }
            let text = AutobotShared.VoiceText.shared.transcript(raw: raw)
            guard !text.isEmpty else { return voiceFailed("Didn't catch that") }
            turn = VoiceTurn(baseline: Set(app.agent.session(conversationId).state.projection.items.map(\.key)), startedAtMs: monotonicMs())
            // The answer is read while it streams, from the chat renderer's own text.
            reading = ReadAloudStream(speakable: { LiteMarkdown.speakable($0) }, maxChars: AutobotShared.VoiceText.shared.MAX_CHUNK)
            answerStartedAt = nil
            setPhase(.thinking)
            let images = pendingImages
            pendingImages = []
            await app.agent.send(conversationId, text: text, images: images)
            // Re-check once the prompt could have been dropped without any state change.
            do { try await Task.sleep(for: .milliseconds(VoiceTurn.timeoutMs)) } catch { return }
            sessionChanged(app.agent.session(conversationId).state)
        }
    }

    /**
     Follows the agent during a voice turn: reads the answer sentence by sentence while it streams,
     announces approvals, and ends the reading when the turn is over.
     */
    func sessionChanged(_ state: ChatSessionState) {
        guard let turn else { return }
        if let text = state.live?.text, !text.isEmpty, let reading, turn.promptSeen(items: state.projection.items) {
            if answerStartedAt == nil { answerStartedAt = monotonicMs() }
            speakPieces(reading.update(text: text))
        }
        let waiting = state.approvals.map(\.callId) + (state.question.map { [$0.callId] } ?? [])
        if turn.needsAnnouncement(waiting) {
            let line = state.question != nil ? "The agent has a question." : "Needs your approval."
            if let queue {
                queue.add([line])
            } else {
                let speaker = startSpeaker()
                Task { try? await speaker.speak(line) }
            }
        }
        guard let finished = turn.update(items: state.projection.items, running: state.running, nowMs: monotonicMs()) else { return }
        self.turn = nil
        // The rest of the answer (or all of it, when it did not stream), then the queue ends.
        if let reading { speakPieces(reading.finish(reply: finished.reply)) }
        if let queue {
            queue.close()
        } else {
            reading = nil
            setPhase(.ready)
        }
    }

    /// Queues [pieces]; the first one starts the speaking phase (and hands-free barge-in).
    private func speakPieces(_ pieces: [String]) {
        guard !pieces.isEmpty else { return }
        if queue == nil {
            let q = SpeechQueue { [weak self] index in
                guard let self else { return }
                if index == 0 { self.startedSpeaking() }
                self.voice?.spoken += 1
            }
            queue = q
            let speaker = startSpeaker()
            queueTask = Task { [weak self] in
                do {
                    try await q.run(speaker)
                } catch let e as SpeakerUnavailable {
                    self?.stopVoiceReading()
                    self?.voiceFailed(e.message)
                    return
                } catch {
                    return
                }
                guard let self, self.queue === q else { return }
                self.stopVoiceReading()
                if self.voice?.handsFree == true { self.listen() } else { self.setPhase(.ready) }
            }
        }
        queue?.add(pieces)
    }

    private func startedSpeaking() {
        if let answerStartedAt { lastFirstAudioMs = monotonicMs() - answerStartedAt }
        setPhase(.speaking)
        if voice?.handsFree == true { startBargeIn() }
    }

    /**
     Hands-free: listens while the answer plays, through voice-processing I/O (echo cancellation)
     and the shared `BargeIn` gate, so talking over the answer stops it and starts listening with
     the first words kept. If capture cannot start during playback, tapping still interrupts.
     */
    private func startBargeIn() {
        guard let app = voiceApp, barge == nil else { return }
        let gate = BargeIn()
        gate.start(atMs: monotonicMs())
        barge = gate
        bargeRing = []
        do {
            try app.speech.startLive(
                voiceProcessing: true,
                onLevel: { [weak self] peak in Task { @MainActor in self?.bargeHeard(peak) } },
                onSamples: { [weak self] buffer in
                    let chunk = Array(buffer)
                    Task { @MainActor in self?.keepForBarge(chunk) }
                },
            )
        } catch {
            barge = nil
        }
    }

    private func keepForBarge(_ chunk: [Float]) {
        guard barge != nil else { return }
        bargeRing.append(contentsOf: chunk)
        if bargeRing.count > Self.bargePreroll { bargeRing.removeFirst(bargeRing.count - Self.bargePreroll) }
    }

    private func bargeHeard(_ peak: Float) {
        guard let gate = barge, voice?.phase == .speaking else { return }
        if gate.onLevel(peak: peak, atMs: monotonicMs()) {
            let preroll = bargeRing
            interrupt(preroll: preroll)
        }
    }

    private func stopBarge() {
        guard barge != nil else { return }
        barge = nil
        bargeRing = []
        _ = voiceApp?.speech.stop()
    }

    /// The user talks over the answer (a tap, or barge-in): stop reading it and the agent, then listen.
    private func interrupt(preroll: [Float]? = nil) {
        stopVoiceReading()
        if turn != nil, let app = voiceApp, let id = voiceConversation, app.agent.session(id).running { app.agent.cancel(id) }
        turn = nil
        listen(preroll: preroll)
    }

    /// Stops reading the voice answer (and the barge-in microphone).
    private func stopVoiceReading() {
        queueTask?.cancel()
        queueTask = nil
        queue = nil
        reading = nil
        stopBarge()
        stopSpeaker()
    }

    private func setPhase(_ phase: VoicePhase) {
        if voice?.phase != phase { voice?.phase = phase }
        levels.clear()
    }

    private func voiceFailed(_ message: String) {
        notice = message
        // A failed turn is no longer followed: its end must not reset a new recording to ready.
        turn = nil
        setPhase(.ready)
    }

    private func stopVoiceWork() {
        voiceTask?.cancel()
        if dictation == .idle {
            live?.cancel()
            live = nil
            heardWords.clear()
        }
        endOfSpeech = nil
        stopVoiceReading()
        turn = nil
        setPhase(.ready)
    }
}

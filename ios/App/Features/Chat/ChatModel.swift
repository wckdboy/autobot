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
    /// Recent microphone peaks (0…1), oldest first.
    var levels: [Float] = []
    /// Spoken pieces started (the speaking strip pulses on each).
    var spoken = 0
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

    @ObservationIgnored private var thumbs: [String: UIImage] = [:]
    @ObservationIgnored private var readTask: Task<Void, Never>?
    @ObservationIgnored private var voiceTask: Task<Void, Never>?
    @ObservationIgnored private var voiceApp: AppContainer?
    @ObservationIgnored private var voiceConversation: String?
    @ObservationIgnored private var turn: VoiceTurn?
    @ObservationIgnored private var endOfSpeech: EndOfSpeech?

    private static let levelHistory = 32

    private var speaker: any Speaker { Speakers.pick() }

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
        guard let bytes = app.attachments.bytes(id), let image = await decodeImage(bytes, maxSide: 256) else { return nil }
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

    /// Starts dictation, or stops it and transcribes on the phone (whisper.cpp).
    func toggleDictation(app: AppContainer) {
        switch dictation {
        case .transcribing:
            return
        case .recording:
            let samples = app.speech.stop()
            guard let path = speechModelPath(app) else {
                dictation = .idle
                notice = "The speech model is missing. Get one in Models › speech"
                return
            }
            dictation = .transcribing
            Task {
                do {
                    let text = try await app.speech.transcribe(modelPath: path, samples: samples).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !text.isEmpty { dictated = text }
                } catch {
                    notice = "Dictation failed: \(error.localizedDescription)"
                }
                dictation = .idle
            }
        case .idle:
            guard speechModelPath(app) != nil else {
                notice = "Get a speech model in Models → speech to dictate"
                return
            }
            stopSpeaking()
            Task {
                // Asked only when the microphone is first needed.
                guard await app.speech.requestPermission() else {
                    notice = "Microphone access is off — allow it in Settings to dictate"
                    return
                }
                do {
                    try app.speech.start(onInterruption: { [weak self] in
                        Task { @MainActor in self?.recordingInterrupted(app: app) }
                    })
                    dictation = .recording
                } catch {
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

    /// The microphone was taken away (a call, Siri, media services reset): end the recording.
    private func recordingInterrupted(app: AppContainer) {
        if dictation == .recording {
            _ = app.speech.stop()
            dictation = .idle
            notice = "Dictation stopped: the microphone was interrupted"
        }
        if voice?.phase == .listening {
            _ = app.speech.stop()
            endOfSpeech = nil
            voiceFailed("Voice stopped: the microphone was interrupted")
        }
    }

    func stopRecording(app: AppContainer) {
        if dictation == .recording {
            _ = app.speech.stop()
            dictation = .idle
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
        speaker.stop()
        speakingKey = key
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
        speaker.stop()
        if speakingKey != nil, voice == nil { SpeechAudio.deactivate() }
        speakingKey = nil
    }

    // MARK: Voice mode: push to talk → transcribe → send → read the answer aloud, all on the phone

    func setVoiceMode(_ on: Bool, app: AppContainer, conversationId: String) {
        guard on != (voice != nil) else { return }
        stopVoiceWork()
        guard on else {
            voice = nil
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
    }

    func setHandsFree(_ on: Bool) { voice?.handsFree = on }

    /// The voice button: talk, send what was said, stop the agent, or interrupt the answer and talk.
    func voiceTap() {
        switch voice?.phase {
        case .ready: listen()
        case .listening: finishListening()
        case .transcribing, nil: break
        case .thinking: if let voiceApp, let voiceConversation { voiceApp.agent.cancel(voiceConversation) }
        case .speaking:
            speaker.stop()
            listen()
        }
    }

    private func listen() {
        guard let app = voiceApp else { return }
        guard speechModelPath(app) != nil else { return voiceFailed("Get a speech model in Models › speech") }
        voiceTask?.cancel()
        speaker.stop()
        voiceTask = Task {
            // Asked only when the microphone is first needed.
            guard await app.speech.requestPermission() else { return voiceFailed("Microphone access is off — allow it in Settings to talk") }
            guard !Task.isCancelled, voice != nil else { return }
            endOfSpeech = voice?.handsFree == true ? EndOfSpeech(threshold: 0.05, silenceMs: 1_200, minSpeechMs: 200, noSpeechMs: 8_000) : nil
            do {
                try app.speech.start(managesSession: false, onLevel: { [weak self] peak in
                    Task { @MainActor in self?.heard(peak) }
                }, onInterruption: { [weak self] in
                    Task { @MainActor in self?.recordingInterrupted(app: app) }
                })
                setPhase(.listening)
            } catch {
                voiceFailed("Voice failed: \(error.localizedDescription)")
            }
        }
    }

    /// One microphone level: the meter, and hands-free end-of-speech detection.
    private func heard(_ peak: Float) {
        guard voice?.phase == .listening, let app = voiceApp else { return }
        voice?.levels = Array(((voice?.levels ?? []) + [peak]).suffix(Self.levelHistory))
        guard let endOfSpeech else { return }
        let verdict = endOfSpeech.onLevel(peak: peak, atMs: monotonicMs())
        if verdict == .ended {
            finishListening()
        } else if verdict == .noSpeech {
            _ = app.speech.stop()
            voiceFailed("Didn't catch that")
        }
    }

    private func finishListening() {
        guard let app = voiceApp, let conversationId = voiceConversation else { return }
        let samples = app.speech.stop()
        endOfSpeech = nil
        guard let path = speechModelPath(app) else { return voiceFailed("Get a speech model in Models › speech") }
        setPhase(.transcribing)
        voiceTask?.cancel()
        voiceTask = Task {
            do {
                let raw = try await app.speech.transcribe(modelPath: path, samples: samples)
                guard !Task.isCancelled else { return }
                let text = AutobotShared.VoiceText.shared.transcript(raw: raw)
                guard !text.isEmpty else { return voiceFailed("Didn't catch that") }
                turn = VoiceTurn(baseline: Set(app.agent.session(conversationId).state.projection.items.map(\.key)), startedAtMs: monotonicMs())
                setPhase(.thinking)
                let images = pendingImages
                pendingImages = []
                await app.agent.send(conversationId, text: text, images: images)
                // Re-check once the prompt could have been dropped without any state change.
                try await Task.sleep(for: .milliseconds(VoiceTurn.timeoutMs))
                sessionChanged(app.agent.session(conversationId).state)
            } catch is CancellationError {
            } catch {
                voiceFailed("Voice failed: \(error.localizedDescription)")
            }
        }
    }

    /// Follows the agent during a voice turn: announces approvals, reads the answer when it is done.
    func sessionChanged(_ state: ChatSessionState) {
        guard let turn, voice?.phase == .thinking else { return }
        let waiting = state.approvals.map(\.callId) + (state.question.map { [$0.callId] } ?? [])
        if turn.needsAnnouncement(waiting) {
            let line = state.question != nil ? "The agent has a question." : "Needs your approval."
            Task { try? await speaker.speak(line) }
        }
        guard let finished = turn.update(items: state.projection.items, running: state.running, nowMs: monotonicMs()) else { return }
        self.turn = nil
        guard let reply = finished.reply else { return setPhase(.ready) }
        voiceTask?.cancel()
        voiceTask = Task {
            setPhase(.speaking)
            do {
                try await speaker.read(reply) { [weak self] _ in self?.voice?.spoken += 1 }
            } catch let e as SpeakerUnavailable {
                return voiceFailed(e.message)
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            if voice?.handsFree == true { listen() } else { setPhase(.ready) }
        }
    }

    private func setPhase(_ phase: VoicePhase) {
        voice?.phase = phase
        voice?.levels = []
    }

    private func voiceFailed(_ message: String) {
        notice = message
        setPhase(.ready)
    }

    private func stopVoiceWork() {
        voiceTask?.cancel()
        if voice?.phase == .listening, let voiceApp { _ = voiceApp.speech.stop() }
        endOfSpeech = nil
        speaker.stop()
        turn = nil
        setPhase(.ready)
    }
}

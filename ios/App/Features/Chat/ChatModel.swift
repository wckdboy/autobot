import AVFoundation
import Foundation
import Observation
import UIKit

enum Dictation { case idle, recording, transcribing }

/// Prepares picked images for vision models (port of `ImageInput.kt`).
enum ImageInput {
    /// Longest side sent to a model; vision encoders resample to a few hundred pixels anyway.
    static let maxSide: CGFloat = 1344

    /**
     Decodes [data] (any format iOS reads, including HEIC), scales it to at most [maxSide], redraws
     it upright and re-encodes it as JPEG. Redrawing drops EXIF, so location and camera data never
     reach a model or the attachment store.
     */
    static func encode(_ data: Data) -> Data? {
        guard let image = UIImage(data: data), image.size.width > 0, image.size.height > 0 else { return nil }
        let longest = max(image.size.width, image.size.height)
        let scale = min(1, maxSide / longest)
        let size = CGSize(width: max(1, (image.size.width * scale).rounded()), height: max(1, (image.size.height * scale).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let redrawn = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        return redrawn.jpegData(compressionQuality: 0.88)
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

    @ObservationIgnored private var thumbs: [String: UIImage] = [:]
    @ObservationIgnored private let synthesizer = AVSpeechSynthesizer()
    @ObservationIgnored private var speechDelegate: SpeechDelegate?

    init() {
        let delegate = SpeechDelegate { [weak self] in self?.speakingKey = nil }
        speechDelegate = delegate
        synthesizer.delegate = delegate
    }

    // MARK: Attachments

    /// Attaches a picked image: downscaled and re-encoded (which also drops EXIF/GPS metadata).
    func attach(_ data: Data, app: AppContainer) async {
        guard pendingImages.count < Self.maxAttachments else { return }
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
            Task {
                // Asked only when the microphone is first needed.
                guard await app.speech.requestPermission() else {
                    notice = "Microphone access is off — allow it in Settings to dictate"
                    return
                }
                do {
                    try app.speech.start()
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

    func stopRecording(app: AppContainer) {
        if dictation == .recording {
            _ = app.speech.stop()
            dictation = .idle
        }
    }

    // MARK: Read aloud

    /// Reads an answer aloud with the system speech synthesizer; tapping again stops.
    func speak(key: String, text: String) {
        if speakingKey == key {
            synthesizer.stopSpeaking(at: .immediate)
            speakingKey = nil
            return
        }
        synthesizer.stopSpeaking(at: .immediate)
        let plain = text
            .replacingOccurrences(of: "```[\\s\\S]*?```", with: " code block. ", options: .regularExpression)
            .replacingOccurrences(of: "[*_#`>|]", with: "", options: .regularExpression)
        speakingKey = key
        synthesizer.speak(AVSpeechUtterance(string: plain))
    }

    func stopSpeaking() {
        synthesizer.stopSpeaking(at: .immediate)
        speakingKey = nil
    }
}

private final class SpeechDelegate: NSObject, AVSpeechSynthesizerDelegate, @unchecked Sendable {
    private let onDone: @MainActor () -> Void

    init(onDone: @escaping @MainActor () -> Void) { self.onDone = onDone }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) { finished() }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {}

    private func finished() {
        let onDone = onDone
        DispatchQueue.main.async { MainActor.assumeIsolated { onDone() } }
    }
}

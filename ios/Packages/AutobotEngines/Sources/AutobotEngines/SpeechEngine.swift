import AutobotWhisper
import Foundation

public struct Transcript: Sendable, Codable, Hashable {
    public let text: String
    /// Detected or requested language code ("en", …); empty when unknown.
    public let language: String

    private struct Failure: Decodable { let error: String }

    /// Decodes the whisper core's `{text, language}` or throws for `{error}`.
    public static func decode(_ json: String) throws -> Transcript {
        let data = Data(json.utf8)
        if let failure = try? JSONDecoder().decode(Failure.self, from: data) {
            throw failure.error == "cancelled" ? EngineError.cancelled : EngineError("TRANSCRIBE_FAILED", failure.error)
        }
        struct Body: Decodable {
            let text: String
            let language: String?
        }
        do {
            let body = try JSONDecoder().decode(Body.self, from: data)
            return Transcript(text: body.text.trimmingCharacters(in: .whitespacesAndNewlines), language: body.language ?? "")
        } catch {
            throw EngineError("BAD_RESULT", "unreadable transcription result")
        }
    }
}

/**
 * Transcribes speech on the device with whisper.cpp (the core shared with Android). The decoder
 * runs on Metal; the encoder runs on the Apple Neural Engine when a Core ML encoder
 * (`ggml-<name>-encoder.mlmodelc`) sits next to the ggml model, otherwise on Metal. The model
 * stays loaded between calls. Audio is never stored or sent anywhere.
 */
public actor SpeechEngine {
    public static let shared = SpeechEngine()

    private let worker = NativeWorker("autobot.whisper")

    public init() {}

    /// Transcribes 16 kHz mono float PCM (see [AudioRecorder]). [language] is a code like "en", or nil to detect.
    public func transcribe(modelPath: String, pcm16kMono: [Float], language: String? = nil) async throws -> String {
        try await transcribeDetailed(modelPath: modelPath, pcm16kMono: pcm16kMono, language: language).text
    }

    /// [partial]: a live partial pass of streaming dictation, transcribed with an encoder context
    /// sized to the audio instead of a padded 30 s (faster; its text may differ slightly from a
    /// full pass). Not with a Core ML encoder, which always takes 30 s.
    public func transcribeDetailed(modelPath: String, pcm16kMono: [Float], language: String? = nil, useGPU: Bool = true, partial: Bool = false) async throws -> Transcript {
        try Task.checkCancellation()
        let useGPU = useGPU && metalUsable
        let flags = partial ? AUTOBOT_WHISPER_PARTIAL : 0
        let json = try await worker.run({
            pcm16kMono.withUnsafeBufferPointer { samples in
                takeString(
                    autobot_whisper_transcribe_ex(modelPath, samples.baseAddress, samples.count, language ?? "auto", useGPU ? 1 : 0, flags),
                    free: autobot_whisper_string_free,
                )
            }
        }, onCancel: {
            autobot_whisper_cancel()
        })
        return try Transcript.decode(json)
    }

    /// Frees the speech model.
    public func unload() async {
        _ = try? await worker.run { autobot_whisper_free() }
    }
}

import Foundation

/**
 Kokoro-82M, the downloadable neural voice (Models › voice), on Core ML entirely on the phone.
 While one piece plays, [prepare] synthesizes the next, so long answers have no gaps. The caller
 owns the audio session (`SpeechAudio`).
 */
@MainActor
final class KokoroSpeaker: Speaker {
    static let kokoroId = "kokoro"
    static let voice = "af_heart"

    let id = KokoroSpeaker.kokoroId
    private let library: ModelLibrary
    /// Created on first use: building the audio graph starts the output unit, which must not
    /// happen at launch.
    private lazy var player = VoicePlayer()
    private var next: (text: String, task: Task<[Float], Error>)?

    init(library: ModelLibrary) {
        self.library = library
    }

    private var folder: URL? {
        library.models.first { $0.isReady && $0.kind == .voice && $0.engine == .coreML }.map { ModelLibrary.dir(of: $0.id) }
    }

    var isAvailable: Bool { folder != nil }

    func prepare(_ text: String) {
        next?.task.cancel()
        guard let folder else { return }
        next = (text, Task { try await LocalVoice.shared.synthesizeAll(folder: folder, text: text, voice: Self.voice) })
    }

    func speak(_ text: String) async throws {
        guard let folder else { throw SpeakerUnavailable(message: "The voice model is missing. Get one in Models › voice") }
        let ready = next?.text == text ? next?.task : nil
        next = nil
        let audio: AsyncThrowingStream<[Float], Error>
        if let ready {
            audio = AsyncThrowingStream { continuation in
                let task = Task {
                    do {
                        continuation.yield(try await ready.value)
                        continuation.finish()
                    } catch {
                        continuation.finish(throwing: error)
                    }
                }
                continuation.onTermination = { _ in task.cancel() }
            }
        } else {
            audio = LocalVoice.shared.synthesize(folder: folder, text: text, voice: Self.voice)
        }
        playerReady = true
        do {
            try await withTaskCancellationHandler {
                try await player.play(audio)
            } onCancel: {
                Task { @MainActor in self.player.stop() }
            }
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw SpeakerUnavailable(message: "The voice failed: \(error.localizedDescription)")
        }
    }

    func stop() {
        next?.task.cancel()
        next = nil
        if playerReady { player.stop() }
    }

    private var playerReady = false
}

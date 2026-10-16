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
    /// Pieces synthesized ahead, oldest first. [Speaker.read] prepares piece i+1 before it speaks
    /// piece i, so a prepared piece must survive the speak of the one before it (dropping it there
    /// synthesized every piece twice and the prefetch never hit).
    private var prepared: [(text: String, task: Task<[Float], Error>)] = []

    init(library: ModelLibrary) {
        self.library = library
    }

    private var folder: URL? {
        library.models.first { $0.isReady && $0.kind == .voice && $0.engine == .coreML }.map { ModelLibrary.dir(of: $0.id) }
    }

    var isAvailable: Bool { folder != nil }

    func prepare(_ text: String) {
        guard let folder, !prepared.contains(where: { $0.text == text }) else { return }
        prepared.append((text, Task { try await LocalVoice.shared.synthesizeAll(folder: folder, text: text, voice: Self.voice) }))
        // At most the next two pieces wait; an older one will not be spoken.
        while prepared.count > 2 { prepared.removeFirst().task.cancel() }
    }

    /// The prepared synthesis of [text], taken out of the queue (nil: not prepared).
    func takePrepared(_ text: String) -> Task<[Float], Error>? {
        guard let i = prepared.firstIndex(where: { $0.text == text }) else { return nil }
        return prepared.remove(at: i).task
    }

    /// Texts prepared and not spoken yet (tests).
    var preparedTexts: [String] { prepared.map(\.text) }

    func speak(_ text: String) async throws {
        guard let folder else { throw SpeakerUnavailable(message: "The voice model is missing. Get one in Models › voice") }
        let ready = takePrepared(text)
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
        prepared.forEach { $0.task.cancel() }
        prepared.removeAll()
        if playerReady { player.stop() }
    }

    private var playerReady = false
}

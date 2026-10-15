import AutobotShared
import Foundation
#if canImport(AutobotEngines)
import AutobotEngines
#endif

/**
 On-device text to audio (Stable Audio Open Small, "Powered by Stability AI"): a prompt in, up to
 11.9 s of 44.1 kHz stereo float PCM out. Prompt → T5 token ids is the shared Kotlin
 `T5Tokenizer` (`tokenizer.json` in the model folder); the models run on Core ML
 (`AutobotEngines.AudioGenEngine`). [folder] is an installed bundle (`ModelLibrary.dir(of:)` of
 `StableAudioCatalog.coreML`). Cancelling the calling task stops between model calls. Nothing is
 fetched or sent. The Studio's audio room is meant to drive it through a `StudioEngine` adapter.
 */
actor LocalAudioGen {
    static let shared = LocalAudioGen()

    static let sampleRate = Int(StableAudio.shared.SAMPLE_RATE)
    static let maxSeconds = StableAudio.shared.MAX_SECONDS
    static let defaultSteps = Int(StableAudio.shared.DEFAULT_STEPS)

    private var tokenizer: (folder: URL, value: T5Tokenizer)?

    #if canImport(AutobotEngines)
    /// Audio for [prompt]. [cfg] 1 (no guidance) is what the distilled model was made for; [seed] fixes the result.
    func generate(
        folder: URL, prompt: String, seconds: Double = 10, steps: Int = LocalAudioGen.defaultSteps, cfg: Float = 1, seed: UInt64 = 0,
        onProgress: @Sendable (AudioGenEngine.Progress) -> Void = { _ in }
    ) async throws -> AudioGenEngine.Audio {
        let tokens = try ids(folder: folder, prompt: prompt)
        do {
            return try await AudioGenEngine.shared.generate(
                folder: folder, request: .init(tokens: tokens, seconds: seconds, steps: steps, cfg: cfg, seed: seed), onProgress: onProgress,
            )
        } catch {
            throw error is CancellationError ? error : Engines.map(error)
        }
    }
    #endif

    /// T5 ids for [prompt], closed with `</s>` (the engine pads them).
    func ids(folder: URL, prompt: String) throws -> [Int32] {
        let t = try load(folder)
        let ids = t.ids(text: prompt)
        return (0 ..< ids.size).map { ids.get(index: $0) }
    }

    /// Reads the tokenizer and compiles the Core ML packages now (the first generation otherwise pays for it).
    func prepare(folder: URL) async throws {
        _ = try load(folder)
        #if canImport(AutobotEngines)
        do { try await AudioGenEngine.shared.prepare(folder: folder) } catch { throw Engines.map(error) }
        #endif
    }

    /// Frees the models and the tokenizer.
    func unload() async {
        tokenizer = nil
        #if canImport(AutobotEngines)
        await AudioGenEngine.shared.unload()
        #endif
    }

    private func load(_ folder: URL) throws -> T5Tokenizer {
        if let tokenizer, tokenizer.folder == folder { return tokenizer.value }
        let file = folder.appending(path: StableAudioCatalog.shared.TOKENIZER)
        guard FileManager.default.fileExists(atPath: file.path) else {
            throw LocalEngineError(code: "NO_MODEL", message: "Stable Audio Open Small is not installed")
        }
        do {
            let value = try T5Tokenizer.companion.load(path: file.path)
            tokenizer = (folder, value)
            return value
        } catch {
            throw LocalEngineError(code: "BAD_MODEL", message: "Cannot read the audio model's tokenizer: \(error.localizedDescription)")
        }
    }
}

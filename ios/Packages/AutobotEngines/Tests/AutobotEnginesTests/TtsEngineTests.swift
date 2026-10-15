import CoreML
import Foundation
import Testing

@testable import AutobotEngines

@Suite struct TtsEngineTests {
    @Test func framesFollowKokoroRounding() {
        #expect(TtsEngine.frames([0.2, 1.5, 2.5, 3.49], speed: 1) == [1, 2, 2, 3])
        // Faster speech: fewer frames, never none.
        #expect(TtsEngine.frames([4, 4, 0.4], speed: 2) == [2, 2, 1])
        #expect(TtsEngine.frames([3], speed: 0) == [3])
    }

    @Test func splitsLongPiecesAtTheMiddleWord() {
        let s = TtsEngine.spaceId
        #expect(TtsEngine.splitPoint([50, 51, s, 52, 53, s, 54, 55, 56, 57]) == 6)
        #expect(TtsEngine.splitPoint([50, 51, 52, 53]) == 2)
    }

    @Test func alignmentRepeatsEachPhonemeForItsFrames() throws {
        // [1, C=2, T=3] features, 1 + 2 + 1 frames, padded to 6.
        let f = try MLMultiArray(shape: [1, 2, 3], dataType: .float32)
        for i in 0 ..< 6 { f[i] = NSNumber(value: Float(i + 1)) }
        let a = try TtsEngine.aligned(f, frames: [1, 2, 1], length: 6)
        #expect(a.shape == [1, 2, 6])
        #expect((0 ..< 12).map { a[$0].floatValue } == [1, 2, 2, 3, 0, 0, 4, 5, 5, 6, 0, 0])
    }

    @Test func voicePackRows() throws {
        let values = (0 ..< KokoroVoicePack.rows * KokoroVoicePack.dim).map { Float($0 / KokoroVoicePack.dim) }
        let pack = try KokoroVoicePack(values: values)
        #expect(pack.style(row: 3).allSatisfy { $0 == 3 })
        #expect(pack.style(row: 9999).first == Float(KokoroVoicePack.rows - 1))
        #expect(throws: EngineError.self) { try KokoroVoicePack(values: [1, 2, 3]) }
    }

    /// The real models, when `TEST_RUNNER_KOKORO_DIR` names an installed Kokoro Core ML folder (with `af_heart.bin`).
    @Test func synthesizesWithTheRealModel() async throws {
        guard let path = ProcessInfo.processInfo.environment["KOKORO_DIR"], FileManager.default.fileExists(atPath: path + "/Kokoro_Predictor.mlpackage") else { return }
        let folder = URL(fileURLWithPath: path)
        // "həlˈO wˈɜɹld." (misaki) as Kokoro ids.
        let ids: [Int32] = [50, 83, 54, 156, 31, 16, 65, 156, 87, 123, 54, 46, 4]
        let engine = TtsEngine()
        _ = try await engine.synthesize(folder: folder, tokens: ids, styleRow: ids.count - 1, voice: "af_heart")
        let start = Date()
        let audio = try await engine.synthesize(folder: folder, tokens: ids, styleRow: ids.count - 1, voice: "af_heart")
        let seconds = Date().timeIntervalSince(start)
        let duration = Double(audio.count) / Double(TtsEngine.sampleRate)
        print("kokoro core ml: \(duration) s audio in \(seconds) s, RTF \(seconds / duration)")
        #expect(duration > 0.5 && duration < 3)
        let peak = audio.map { abs($0) }.max() ?? 0
        print("kokoro core ml: peak \(peak), nan \(audio.contains { $0.isNaN })")
        #expect(peak > 0.05 && peak < 1.5)
    }
}

import CoreML
import Foundation
import Testing

@testable import AutobotEngines

/// The Swift sampler against the same golden values as the shared Kotlin `StableAudioTest`.
@Suite struct AudioGenEngineTests {
    @Test func scheduleMatchesStableAudioTools() {
        let expected: [Float] = [1, 0.9943756, 0.9844802, 0.9579123, 0.8909032, 0.7455466, 0.5124974, 0.273885, 0]
        let s = AudioGenEngine.schedule(8)
        #expect(s.count == 9)
        for (a, b) in zip(s, expected) { #expect(abs(a - b) < 1e-6) }
        #expect(AudioGenEngine.schedule(1) == [1, 0])
    }

    @Test func noiseMatchesKotlin() {
        var g = GaussianNoise(seed: 1_234_567)
        #expect(g.nextUInt64() == 6_457_827_717_110_365_317)
        #expect(g.nextUInt64() == 3_203_168_211_198_807_973)
        #expect(g.nextUInt64() == 9_817_491_932_198_370_423)
        var n = GaussianNoise(seed: 42)
        let expected: [Float] = [0.41471976, 0.65268123, -0.89188623, 1.3268336, 1.7295930, -1.8834168]
        for (a, b) in zip(n.fill(6), expected) { #expect(abs(a - b) < 1e-6) }
    }

    @Test func padsAndTruncatesTokens() {
        let (ids, mask) = AudioGenEngine.padded([350, 4250, 1])
        #expect(ids.count == 64 && mask.count == 64)
        #expect(Array(ids.prefix(4)) == [350, 4250, 1, 0])
        #expect(mask.reduce(0, +) == 3)
        let long = AudioGenEngine.padded([Int32](repeating: 9, count: 80) + [1])
        #expect(long.ids.last == 1 && long.ids[62] == 9 && long.mask.allSatisfy { $0 == 1 })
        #expect(AudioGenEngine.padded([]).ids.first == 1)
    }

    @Test func conditioningZeroesPadding() {
        var text = [Float](repeating: 1, count: 64 * 768)
        text[5] = .nan
        let c = AudioGenEngine.crossConditioning(text, mask: (0 ..< 64).map { $0 < 3 ? 1 : 0 }, seconds: [Float](repeating: 2, count: 768))
        #expect(c.count == 65 * 768)
        #expect(c[0] == 1 && c[5] == 0 && c[3 * 768] == 0 && c[64 * 768] == 2)
        #expect(AudioGenEngine.normalizedSeconds(10) == Float(0.0390625))
        #expect(AudioGenEngine.frames(10) == 441_000)
        #expect(AudioGenEngine.frames(99) == AudioGenEngine.samples)
    }

    @Test func pingPongAndGuidance() {
        let d = AudioGenEngine.denoised([1, -2, 0.5], t: 0.5, velocity: [0.5, 0.5, -1], uncond: nil, cfg: 1)
        #expect(d == [0.75, -2.25, 1])
        var noise = GaussianNoise(seed: 1)
        #expect(AudioGenEngine.pingPong(d, tNext: 0, noise: &noise) == d)
        let g = AudioGenEngine.denoised([1, 1, 0], t: 1, velocity: [0, 1, 0], uncond: [1, 0, -1], cfg: 3)
        for (a, b) in zip(g, [Float(1), -2, -2]) { #expect(abs(a - b) < 1e-6) }
    }

    @Test func clipAndWav() {
        var decoded = [Float](repeating: 0.25, count: 2 * AudioGenEngine.samples)
        for i in AudioGenEngine.samples ..< decoded.count { decoded[i] = -0.5 }
        let clip = AudioGenEngine.clip(decoded, seconds: 1)
        #expect(clip.frames == 44_100)
        #expect(abs(clip.left[0] - 0.5) < 1e-6 && abs(clip.right[100] + 1) < 1e-6 && clip.right[44_099] == 0)
        let wav = AudioGenEngine.Audio(left: [0, 1, -1], right: [0.5, -0.5, .nan]).wav()
        #expect(wav.count == 44 + 12)
        #expect(String(decoding: wav.prefix(4), as: UTF8.self) == "RIFF")
        let samples = (0 ..< 6).map { i in Int16(littleEndian: wav.subdata(in: 44 + 2 * i ..< 46 + 2 * i).withUnsafeBytes { $0.loadUnaligned(as: Int16.self) }) }
        #expect(samples == [0, 16384, 32767, -16383, -32767, 0])
    }

    @Test func readsStridedArrays() throws {
        let a = try MLMultiArray(shape: [1, 2, 3], dataType: .float32)
        for i in 0 ..< 6 { a[i] = NSNumber(value: Float(i)) }
        #expect(AudioGenEngine.values(a) == [0, 1, 2, 3, 4, 5])
    }

    /// The real models, when `AUDIOGEN_DIR` (`TEST_RUNNER_AUDIOGEN_DIR` under xcodebuild) names an installed Core ML folder.
    @Test func generatesWithTheRealModel() async throws {
        guard let path = ProcessInfo.processInfo.environment["AUDIOGEN_DIR"], AudioGenEngine.installed(in: URL(fileURLWithPath: path)) else { return }
        let folder = URL(fileURLWithPath: path)
        let engine = AudioGenEngine()
        let compileStart = Date()
        try await engine.prepare(folder: folder)
        print("audiogen core ml: compiled in \(Date().timeIntervalSince(compileStart)) s")
        // "128 BPM tech house drum loop" (T5Tokenizer).
        let tokens: [Int32] = [209, 2577, 272, 6218, 5256, 629, 5253, 6494, 1]
        let marks = Marks()
        let start = Date()
        let audio = try await engine.generate(folder: folder, request: .init(tokens: tokens, seconds: 10, seed: 42)) { p in marks.add("\(p.stage.rawValue) \(p.step)") }
        let seconds = Date().timeIntervalSince(start)
        for (a, b) in zip(marks.all, marks.all.dropFirst()) { print("audiogen core ml: \(a.0) → \(b.0) \(b.1.timeIntervalSince(a.1)) s") }
        print("audiogen core ml: \(audio.seconds) s audio in \(seconds) s, RTF \(seconds / audio.seconds)")
        #expect(audio.frames == 441_000)
        for channel in [audio.left, audio.right] {
            let peak = channel.map { abs($0) }.max() ?? 0
            let rms = (channel.reduce(0) { $0 + Double($1 * $1) } / Double(channel.count)).squareRoot()
            print("audiogen core ml: peak \(peak) rms \(rms)")
            #expect(channel.allSatisfy { $0.isFinite })
            #expect(peak > 0.5 && rms > 0.02)
        }
        try audio.wav().write(to: folder.appending(path: "audiogen-test.wav"))
    }
}

private final class Marks: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [(String, Date)] = []
    func add(_ s: String) { lock.withLock { items.append((s, Date())) } }
    var all: [(String, Date)] { lock.withLock { items } }
}

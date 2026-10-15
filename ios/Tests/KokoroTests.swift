import AutobotShared
import Foundation
import Testing
@testable import Autobot

/// Kokoro on iOS: the Core ML catalog entry and the shared Kotlin frontend as Swift sees it.
struct KokoroTests {
    @Test func voiceIsInTheCoreMLCatalog() throws {
        let plan = try #require(CoreMLCatalog.voices.first)
        #expect(plan.kind == .voice && plan.format == .coreML && plan.engine == .coreML)
        #expect(engineFor(.voice, .coreML) == .coreML)
        #expect(engineFor(.voice, .onnx) == .none)
        let names = Set(plan.manifest.files.map(\.name))
        #expect(names.contains("Kokoro_Predictor.mlpackage/Manifest.json"))
        #expect(names.contains("config.json") && names.contains("us_gold.json") && names.contains("af_heart.bin"))
        #expect(plan.manifest.files.allSatisfy { $0.keepName && $0.sha256?.count == 64 && $0.sizeBytes > 0 })
        // Voices are used by read-aloud, never opened from the Models tab.
        #expect(!Catalog.all.contains { $0.kind == .voice })
    }

    @MainActor @Test func installedVoiceOpensNothing() {
        let plan = CoreMLCatalog.voices[0]
        let m = InstalledModel(
            id: plan.id, kind: plan.kind, source: plan.source, title: plan.title, subtitle: plan.subtitle, format: plan.format, engine: plan.engine,
            baseModel: plan.baseModel, license: plan.license, nsfw: plan.nsfw, totalBytes: plan.totalBytes, downloadedBytes: plan.totalBytes,
            status: .ready, error: nil, manifest: plan.manifest, addedAt: 0, updatedAt: 0,
        )
        #expect(ModelsModel.launch(m).task == ModelTask.none)
    }

    /// The Kotlin/Native frontend (regexes, lexicon reader) gives the ids the JVM tests check.
    @Test func sharedFrontendReadsText() {
        let config = #"{"vocab": {".": 4, " ": 16, "d": 46, "h": 50, "l": 54, "w": 65, "ə": 83, "ɜ": 87, "ɹ": 123, "ˈ": 156, "ˌ": 157, "O": 31, "A": 24, "i": 51, "k": 53, "o": 57, "ɑ": 69, "ɔ": 76, "n": 56, "t": 62, "s": 61, "æ": 72, "b": 44, "m": 55, "a": 43, "ɪ": 102, "ʊ": 135, "ʌ": 138, "u": 63, "z": 68, "p": 58, "f": 48, "v": 64, "j": 52, "ɡ": 92, "ŋ": 112, "ð": 81, "θ": 119, "ʃ": 131, "ʒ": 147, "ʤ": 82, "ʧ": 133, "I": 25, "W": 39, "Y": 41, "ᵊ": 42, "ᵻ": 177, "T": 36, "ɛ": 86, ",": 3, "!": 5, "?": 6, "e": 47, "c": 45, "r": 60, "x": 66, "y": 67, "q": 59}}"#
        let gold = #"{"hello": "həlˈO", "world": "wˈɜɹld"}"#
        let frontend = KokoroFrontend.companion.parse(config: config, usGold: gold, usSilver: "{}", gbGold: nil, gbSilver: nil)
        let chunks = frontend.chunks(text: "Hello world.", voice: "af_heart", maxTokens: 254)
        #expect(chunks.count == 1)
        let ids = chunks[0].ids
        #expect((0 ..< ids.size).map { ids.get(index: $0) } == [50, 83, 54, 156, 31, 16, 65, 156, 87, 123, 54, 46, 4])
        #expect(chunks[0].styleIndex == 12)
        // Words outside the lexicon are still spoken (letter-to-sound rules).
        #expect(!frontend.phonemes(text: "Kokoro", voice: "af_heart").isEmpty)
    }
}

import CoreML
import Foundation
import Testing

@testable import AutobotEngines

/// A model folder for a test, removed with the test's temporary directory.
private func folder(_ name: String, files: [String: Data] = [:]) throws -> URL {
    let root = FileManager.default.temporaryDirectory.appending(path: "engines-\(UUID().uuidString)/\(name)")
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    for (path, data) in files {
        let url = root.appending(path: path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url)
    }
    return root
}

private let junk = Data((0 ..< 512).map { UInt8($0 % 13) })

/**
 Stable Diffusion's device choices (SD 2.1 was reported slow): where each build runs, and when the
 pipeline keeps its stages resident; a cancelled or broken request fails instead of hanging.
 */
@Suite(.timeLimit(.minutes(2)))
struct DiffusionChoiceTests {
    private func model(_ arch: CoreMLDiffusionModel.Arch, _ attention: CoreMLDiffusionModel.Attention?) -> CoreMLDiffusionModel {
        CoreMLDiffusionModel(folder: URL(fileURLWithPath: "/m"), arch: arch, width: 512, height: 512, supportsImageToImage: true, attention: attention)
    }

    @Test func computeUnitsFollowTheBuildUnlessChosen() {
        #expect(DiffusionEngine.computeUnits(model(.sd, .splitEinsum), backend: .auto) == .cpuAndNeuralEngine)
        #expect(DiffusionEngine.computeUnits(model(.sd, .original), backend: .auto) == .cpuAndGPU)
        // Apple's catalog builds keep only the compiled files: split-einsum, the Neural Engine.
        #expect(DiffusionEngine.computeUnits(model(.sd, nil), backend: .auto) == .cpuAndNeuralEngine)
        #expect(DiffusionEngine.computeUnits(model(.sd, .splitEinsum), backend: .gpu) == .cpuAndGPU)
        #expect(DiffusionEngine.computeUnits(model(.sdxl, .original), backend: .npu) == .cpuAndNeuralEngine)
        #expect(DiffusionEngine.computeUnits(model(.sd, nil), backend: .cpu) == .cpuOnly)
    }

    @Test func stagesStayResidentWhereMemoryAllows() {
        let gib: UInt64 = 1 << 30
        // 6 GB phones (about 5.6 GiB reported) and smaller reload each stage per image.
        #expect(DiffusionEngine.reduceMemory(arch: .sd, physicalMemory: 4 * gib))
        #expect(DiffusionEngine.reduceMemory(arch: .sd, physicalMemory: 5_700_000_000))
        // 8 GB and up keep SD 1.5 / 2.1 resident: no UNet reload per image.
        #expect(!DiffusionEngine.reduceMemory(arch: .sd, physicalMemory: 7_990_000_000))
        #expect(!DiffusionEngine.reduceMemory(arch: .sd, physicalMemory: 12 * gib))
        // SDXL never fits whole next to the app.
        #expect(DiffusionEngine.reduceMemory(arch: .sdxl, physicalMemory: 16 * gib))
    }

    @Test func theSD21CatalogFolderIsAnSDModelForTheNeuralEngine() throws {
        // As the catalog installs it: the compiled resources flat in the model's own directory.
        let metadata = Data(#"[{"inputSchema":[{"name":"z","shape":"[1, 4, 64, 64]"}]}]"#.utf8)
        let dir = try folder("catalog_coreml-sd21-base-palettized_1a2b", files: [
            "TextEncoder.mlmodelc/model.mil": junk, "Unet.mlmodelc/model.mil": junk, "VAEDecoder.mlmodelc/metadata.json": metadata,
            "VAEEncoder.mlmodelc/model.mil": junk, "vocab.json": Data("{}".utf8), "merges.txt": Data(),
        ])
        let m = try CoreMLDiffusionModel.inspect(dir)
        #expect(m.arch == .sd)
        #expect(m.width == 512 && m.height == 512)
        #expect(m.supportsImageToImage)
        #expect(m.preferredBackend == .npu)
        #expect(DiffusionEngine.computeUnits(m, backend: .auto) == .cpuAndNeuralEngine)
    }

    @Test func aCancelledRequestNeverStartsLoading() async throws {
        let dir = try folder("never")
        let model = CoreMLDiffusionModel(folder: dir, arch: .sd, width: 512, height: 512, supportsImageToImage: false, attention: nil)
        let task = Task { try await DiffusionEngine().generate(model: model, request: DiffusionRequest(prompt: "x")) }
        task.cancel()
        await #expect(throws: (any Error).self) { try await task.value }
        #expect(!FileManager.default.fileExists(atPath: dir.appending(path: ".autobot-loaded-\(MLComputeUnits.cpuAndNeuralEngine.rawValue)").path))
    }

    @Test func aBrokenModelFailsToLoadInsteadOfHanging() async throws {
        let dir = try folder("broken", files: [
            "TextEncoder.mlmodelc/coremldata.bin": junk, "Unet.mlmodelc/coremldata.bin": junk, "VAEDecoder.mlmodelc/coremldata.bin": junk,
            "vocab.json": Data("{}".utf8), "merges.txt": Data(),
        ])
        let model = try CoreMLDiffusionModel.inspect(dir)
        let engine = DiffusionEngine()
        await #expect(throws: (any Error).self) {
            _ = try await engine.generate(model: model, request: DiffusionRequest(prompt: "x", steps: 1), backend: .cpu)
        }
        // The engine is not wedged: the next request is answered too.
        await #expect(throws: (any Error).self) {
            _ = try await engine.generate(model: model, request: DiffusionRequest(prompt: "y", steps: 1), backend: .cpu)
        }
        await engine.unload()
    }
}

/// Kokoro and Stable Audio report a missing or broken model as an error, and recover from it.
@Suite(.timeLimit(.minutes(2)))
struct VoiceAndAudioFailureTests {
    @Test func kokoroWithoutItsModelsSaysWhatIsMissing() async throws {
        let engine = TtsEngine()
        let empty = try folder("kokoro-empty")
        await #expect(throws: EngineError("UNSUPPORTED", "Kokoro_Predictor is missing")) {
            _ = try await engine.synthesize(folder: empty, tokens: [50, 83], styleRow: 1, voice: "af_heart")
        }
    }

    @Test func aBrokenKokoroModelFailsEachTimeWithoutSticking() async throws {
        let engine = TtsEngine()
        let broken = try folder("kokoro-broken", files: ["Kokoro_Predictor.mlmodelc/coremldata.bin": junk, "Kokoro_Decoder_64.mlmodelc/coremldata.bin": junk])
        for _ in 0 ..< 2 {
            await #expect(throws: (any Error).self) {
                _ = try await engine.synthesize(folder: broken, tokens: [50, 83], styleRow: 1, voice: "af_heart")
            }
        }
        // A good folder afterwards is not blocked by the failed load (here: still missing, so it says so).
        let empty = try folder("kokoro-after")
        await #expect(throws: EngineError("UNSUPPORTED", "Kokoro_Predictor is missing")) {
            _ = try await engine.synthesize(folder: empty, tokens: [50], styleRow: 0, voice: "af_heart")
        }
    }

    @Test func stableAudioRejectsBadRequestsAndMissingOrBrokenModels() async throws {
        let engine = AudioGenEngine()
        let empty = try folder("audio-empty")
        #expect(!AudioGenEngine.installed(in: empty))
        await #expect(throws: EngineError("UNSUPPORTED", "Stable Audio Open Small is not installed")) {
            _ = try await engine.generate(folder: empty, request: .init(tokens: [1]))
        }
        await #expect(throws: EngineError.self) {
            _ = try await engine.generate(folder: empty, request: .init(tokens: [1], steps: 0))
        }
        await #expect(throws: EngineError.self) {
            _ = try await engine.generate(folder: empty, request: .init(tokens: [1], cfg: 40))
        }
        let names = [AudioGenEngine.textEncoder, AudioGenEngine.secondsEmbedder, AudioGenEngine.dit, AudioGenEngine.decoder]
        let broken = try folder("audio-broken", files: Dictionary(uniqueKeysWithValues: names.map { ("\($0).mlmodelc/coremldata.bin", junk) }))
        #expect(AudioGenEngine.installed(in: broken))
        do {
            _ = try await engine.generate(folder: broken, request: .init(tokens: [209, 1], seconds: 1, steps: 1))
            Issue.record("a broken model must not generate")
        } catch let e as EngineError {
            #expect(e.code == "LOAD_FAILED")
        }
    }
}

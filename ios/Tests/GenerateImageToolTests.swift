import Foundation
import Testing
@testable import Autobot

/// iOS `generate_image` matches Android's tool and reports failures the same way.
@MainActor
struct GenerateImageToolTests {
    private func parts() -> (ImageGenerator, DiffusionBackendRepository) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("tool-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let keys = KeyManager()
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: keys)
        let backends = DiffusionBackendRepository(preferences: Preferences(url: dir.appendingPathComponent("prefs.json")))
        let clients = HttpClientFactory(policy: NetworkPolicy(), auditLog: AuditLog())
        let generator = ImageGenerator(engines: DiffusionEngineFactory(clients: clients, llm: LocalLlm()), gallery: GalleryRepository(store: store, keys: keys),
                                       backends: backends, library: ModelLibrary(store: store))
        return (generator, backends)
    }

    @Test func schemaMatchesAndroid() throws {
        let (generator, backends) = parts()
        let tool = GenerateImageTool(generator: generator, backends: backends)
        #expect(tool.name == "generate_image")
        #expect(tool.kind == "GENERATE")
        #expect(tool.timeoutMs_ == 900_000)
        let schema = try #require(try JSONSerialization.jsonObject(with: Data(tool.parametersJson.utf8)) as? [String: Any])
        #expect(schema["required"] as? [String] == ["prompt"])
        #expect(schema["additionalProperties"] as? Bool == false)
        let props = try #require(schema["properties"] as? [String: [String: Any]])
        #expect(Set(props.keys) == ["prompt", "negative_prompt", "width", "height", "steps", "seed", "count"])
        #expect(props["count"]?["maximum"] as? Int == 4)
        #expect(props["seed"]?["minimum"] as? Int == -1)
        #expect(tool.describe(argsJson: #"{"prompt":"a lighthouse"}"#) == "a lighthouse")
    }

    @Test func argumentsOverrideTheImagineSettings() {
        var base = DiffusionRequest()
        base.sampler = "PNDM"
        base.negativePrompt = "blurry"
        base.mode = .img2img
        base.seed = 42
        let r = GenerateImageTool.request(["prompt": "tram", "width": 777, "count": 2], base: base)
        #expect(r.mode == .txt2img)
        #expect(r.prompt == "tram")
        #expect(r.width == 776)
        #expect(r.height == base.height)
        #expect(r.sampler == "PNDM")
        #expect(r.negativePrompt == "blurry")
        #expect(r.seed == -1)
        #expect(r.batchCount == 2)
    }

    @Test(.timeLimit(.minutes(3))) func withoutABackendTheToolFailsLikeAndroid() async {
        let (generator, backends) = parts()
        let out = await GenerateImageTool.run(["prompt": "x"], generator: generator, backends: backends)
        #expect(out == .error("no image backend is configured (Imagine → Backends)", "NO_BACKEND"))
        #expect(out.content.hasPrefix("Error: "))
    }
}

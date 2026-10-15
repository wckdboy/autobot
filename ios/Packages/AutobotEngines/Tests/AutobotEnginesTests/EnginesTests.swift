import CoreML
import Foundation
import Testing

@testable import AutobotEngines

@Suite struct LlamaResultTests {
    @Test func decodesResultWithToolCalls() throws {
        let json = """
        {"content":"Hi","reasoning":"think","tool_calls":[{"id":"local_3_0","name":"read","arguments":"{\\"path\\":\\"a.txt\\"}"}],
         "finish_reason":"tool_calls","usage":{"prompt_tokens":12,"cached_tokens":8,"completion_tokens":3,"backend":"gpu","prompt_ms":1.5,"generation_ms":20.25}}
        """
        let result = try LlamaResult.decode(json)
        #expect(result.content == "Hi")
        #expect(result.reasoning == "think")
        #expect(result.toolCalls == [ToolCall(id: "local_3_0", name: "read", arguments: "{\"path\":\"a.txt\"}")])
        #expect(result.finishReason == "tool_calls")
        #expect(result.usage.cachedTokens == 8)
        #expect(result.backend == .gpu)
        #expect(result.json == json)
    }

    @Test func errorResultThrowsEngineError() {
        #expect(throws: EngineError("CONTEXT_WINDOW_EXCEEDED", "prompt has 9000 tokens; context is 4096")) {
            try LlamaResult.decode(#"{"error":{"code":"CONTEXT_WINDOW_EXCEEDED","message":"prompt has 9000 tokens; context is 4096"}}"#)
        }
    }

    @Test func garbageIsBadResult() {
        #expect(throws: EngineError.self) { try LlamaResult.decode("not json") }
    }

    @Test func devicesDecode() throws {
        let json = #"[{"name":"MTL0","description":"Apple A18 Pro GPU","kind":"gpu","memory_free":5.0e9,"memory_total":6.0e9},{"name":"CPU","description":"Apple","kind":"cpu","memory_free":0,"memory_total":0}]"#
        let devices = try JSONDecoder().decode([ComputeDevice].self, from: Data(json.utf8))
        #expect(devices.map(\.backend) == [.gpu, .cpu])
        #expect(devices[0].memoryTotal == 6.0e9)
    }

    @Test func mmprojIsAddedToRequest() throws {
        let request = Data(#"{"messages":[]}"#.utf8)
        let out = try LlamaEngine.withMmproj(request, "/m/mmproj.gguf")
        let object = try #require(try JSONSerialization.jsonObject(with: out) as? [String: Any])
        #expect(object["mmproj"] as? String == "/m/mmproj.gguf")
        #expect(try LlamaEngine.withMmproj(request, nil) == request)
    }
}

@Suite struct SpeechTests {
    @Test func decodesTranscript() throws {
        let t = try Transcript.decode(#"{"text":"hello there ","language":"en"}"#)
        #expect(t == Transcript(text: "hello there", language: "en"))
    }

    @Test func errorsAndCancel() {
        #expect(throws: EngineError("TRANSCRIBE_FAILED", "cannot load speech model")) {
            try Transcript.decode(#"{"error":"cannot load speech model"}"#)
        }
        #expect(throws: EngineError.cancelled) { try Transcript.decode(#"{"error":"cancelled"}"#) }
    }
}

@Suite struct DeviceComputeTests {
    @Test func knownModels() {
        #expect(DeviceCompute.lookup("iPhone17,1")?.name == "iPhone 16 Pro")
        #expect(DeviceCompute.lookup("iPhone17,1")?.chip == "A18 Pro")
        #expect(DeviceCompute.lookup("iPad16,3")?.chip == "M4")
        #expect(DeviceCompute.lookup("iPhone99,9") == nil)
    }

    @Test func currentDeviceIsSane() {
        let d = DeviceCompute.current()
        #expect(d.totalRamBytes > 0)
        #expect(d.cores > 0)
        #expect(!d.label.isEmpty)
    }

    @Test func backendsMapToComputeUnits() {
        #expect(ComputeBackend.npu.computeUnits == .cpuAndNeuralEngine)
        #expect(ComputeBackend.gpu.computeUnits == .cpuAndGPU)
        #expect(ComputeBackend.auto.computeUnits == .all)
    }
}

@Suite struct DiffusionTests {
    @Test func schedulerMapping() {
        #expect(DiffusionEngine.schedulerFor(sampler: "DPM++ 2M", scheduler: "Karras").label == "DPM++ 2M Karras")
        #expect(DiffusionEngine.schedulerFor(sampler: "Euler a", scheduler: nil).label == "DPM++ 2M")
        #expect(DiffusionEngine.schedulerFor(sampler: "PNDM", scheduler: "Karras").label == "PNDM")
    }

    @Test func latentSizeFromMetadata() {
        let metadata = Data(#"[{"inputSchema":[{"name":"z","shape":"[1, 4, 96, 64]","dataType":"Float16"}]}]"#.utf8)
        let size = CoreMLDiffusionModel.latentSize(metadata: metadata)
        #expect(size?.width == 64)
        #expect(size?.height == 96)
        #expect(CoreMLDiffusionModel.latentSize(metadata: Data("{}".utf8)) == nil)
    }

    @Test func latentPreviewMapsChannelsToRGB() throws {
        // 3 wide, 2 high; channel 0 = 1 everywhere, the rest 0 → SD factors row 0 → (0.35, 0.23, 0.32).
        var scalars = [Float32](repeating: 0, count: 4 * 6)
        for i in 0..<6 { scalars[i] = 1 }
        let image = try #require(LatentPreview.image(MLShapedArray(scalars: scalars, shape: [1, 4, 2, 3]), sdxl: false))
        #expect(image.width == 3)
        #expect(image.height == 2)
        let pixel = try #require(image.dataProvider?.data as Data?)
        #expect(Array(pixel.prefix(3)) == [172, 156, 168])
        #expect(LatentPreview.image(MLShapedArray(scalars: [Float32](repeating: 0, count: 3 * 4), shape: [1, 3, 2, 2]), sdxl: false) == nil)
    }

    @Test func inspectsModelFolder() throws {
        let dir = FileManager.default.temporaryDirectory.appending(path: "sdxl_split_einsum_\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        for name in ["TextEncoder.mlmodelc", "TextEncoder2.mlmodelc", "Unet.mlmodelc", "VAEDecoder.mlmodelc"] {
            try FileManager.default.createDirectory(at: dir.appending(path: name), withIntermediateDirectories: true)
        }
        try Data().write(to: dir.appending(path: "vocab.json"))
        try Data().write(to: dir.appending(path: "merges.txt"))
        let model = try CoreMLDiffusionModel.inspect(dir)
        #expect(model.arch == .sdxl)
        #expect(model.width == 1024)
        #expect(!model.supportsImageToImage)
        #expect(model.preferredBackend == .npu)
    }

    @Test func rejectsNonModelFolder() {
        #expect(throws: EngineError.self) { try CoreMLDiffusionModel.inspect(FileManager.default.temporaryDirectory) }
    }
}

/// Smoke tests through the real native frameworks (no models needed).
@Suite struct NativeSmokeTests {
    @Test func llamaReportsDevices() async throws {
        let engine = LlamaEngine()
        let devices = try await engine.devices()
        #expect(devices.contains { $0.kind == "cpu" })
        #expect(!devices.contains { $0.kind == "npu" })
        #expect(try await !engine.systemInfo().isEmpty)
        #expect(try await engine.modelInfo() == nil)
    }

    @Test func llamaMissingModelFailsToLoad() async {
        await #expect(throws: EngineError.self) {
            try await LlamaEngine().chat(modelPath: "/nonexistent.gguf", contextLength: 2048, requestJSON: Data(#"{"messages":[]}"#.utf8)) { _, _ in }
        }
    }

    @Test func whisperMissingModelFails() async {
        await #expect(throws: EngineError("TRANSCRIBE_FAILED", "cannot load speech model")) {
            try await SpeechEngine().transcribe(modelPath: "/nonexistent.bin", pcm16kMono: [Float](repeating: 0, count: 16_000))
        }
    }
}

/// End-to-end runs with real models, skipped unless the paths are given, e.g.
/// `TEST_RUNNER_AUTOBOT_LLM_MODEL=/path/model.gguf TEST_RUNNER_AUTOBOT_WHISPER_MODEL=/path/ggml-tiny.en.bin
///  TEST_RUNNER_AUTOBOT_SPEECH_WAV=/path/16k-float.wav xcodebuild test …`.
@Suite(.serialized) struct ModelTests {
    static let env = ProcessInfo.processInfo.environment
    static let llm = env["AUTOBOT_LLM_MODEL"]
    static let whisper = env["AUTOBOT_WHISPER_MODEL"]
    static let wav = env["AUTOBOT_SPEECH_WAV"]

    @Test(.enabled(if: llm != nil)) func chatStreamsAndCallsTools() async throws {
        let engine = LlamaEngine()
        let request = #"""
        {"messages":[{"role":"user","content":"What is the weather in Paris? Use the tool."}],
         "tools":[{"type":"function","function":{"name":"get_weather","description":"Current weather for a city",
           "parameters":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}}}],
         "temperature":0,"max_tokens":96,"seed":1}
        """#
        let deltas = Deltas()
        let result = try await engine.chat(modelPath: Self.llm!, contextLength: 2048, backend: .auto, requestJSON: Data(request.utf8)) { kind, text in
            deltas.append(kind, text)
        }
        print("result:", result.json)
        #expect(result.backend == .gpu || result.backend == .cpu)
        #expect(!result.toolCalls.isEmpty || !result.content.isEmpty)
        if let call = result.toolCalls.first {
            #expect(call.name == "get_weather")
            #expect(call.arguments.contains("Paris"))
        } else {
            #expect(deltas.text(.content) == result.content)
        }

        // Second turn reuses the KV prefix.
        let followUp = #"{"messages":[{"role":"user","content":"What is the weather in Paris? Use the tool."},{"role":"assistant","content":"I'll check."},{"role":"user","content":"Say hi in one word."}],"temperature":0,"max_tokens":8}"#
        let second = try await engine.chat(modelPath: Self.llm!, contextLength: 2048, requestJSON: Data(followUp.utf8)) { _, _ in }
        print("second:", second.json)
        #expect(second.usage.cachedTokens > 0)
        await engine.unload()
    }

    @Test(.enabled(if: llm != nil)) func cancellingStopsGeneration() async throws {
        let engine = LlamaEngine()
        let request = #"{"messages":[{"role":"user","content":"Count from 1 to 500, one number per line."}],"max_tokens":2000}"#
        let task = Task {
            try await engine.chat(modelPath: Self.llm!, contextLength: 2048, backend: .cpu, requestJSON: Data(request.utf8)) { _, _ in }
        }
        try await Task.sleep(for: .seconds(3))
        task.cancel()
        let result = try await task.value
        #expect(result.finishReason == "cancelled")
        #expect(result.backend == .cpu)
        await engine.unload()
    }

    @Test(.enabled(if: whisper != nil && wav != nil)) func transcribesSpeech() async throws {
        let pcm = try Self.floatWav(URL(fileURLWithPath: Self.wav!))
        let text = try await SpeechEngine().transcribe(modelPath: Self.whisper!, pcm16kMono: pcm, language: "en")
        print("transcript:", text)
        #expect(text.lowercased().contains("hello"))
    }

    /// Samples of a 32-bit float WAV's `data` chunk.
    static func floatWav(_ url: URL) throws -> [Float] {
        let data = try Data(contentsOf: url)
        var offset = 12
        while offset + 8 <= data.count {
            let id = String(decoding: data[offset..<offset + 4], as: UTF8.self)
            let size = Int(data[offset + 4..<offset + 8].withUnsafeBytes { $0.loadUnaligned(as: UInt32.self) })
            if id == "data" {
                return data[offset + 8..<min(data.count, offset + 8 + size)].withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
            }
            offset += 8 + size + (size & 1)
        }
        return []
    }
}

private final class Deltas: @unchecked Sendable {
    private let lock = NSLock()
    private var parts: [(DeltaKind, String)] = []

    func append(_ kind: DeltaKind, _ text: String) {
        lock.withLock { parts.append((kind, text)) }
    }

    func text(_ kind: DeltaKind) -> String {
        lock.withLock { parts.filter { $0.0 == kind }.map(\.1).joined() }
    }
}

/// Upscale memory caps and the file-backed output.
@Suite struct UpscaleMemoryTests {
    @Test func capsByOutputSideAndAvailableMemory() {
        let plenty = 4 << 30
        #expect(UpscaleEngine.fits(width: 1024, height: 1024, scale: 4, availableBytes: plenty))
        // 8192 → 32768 px a side: never.
        #expect(!UpscaleEngine.fits(width: 8192, height: 8192, scale: 4, availableBytes: Int.max / 2))
        #expect(!UpscaleEngine.fits(width: 2049, height: 16, scale: 4, availableBytes: plenty))
        // 2048² → 8192²: 256 MB of result plus the source, too much with 300 MB left.
        #expect(!UpscaleEngine.fits(width: 2048, height: 2048, scale: 4, availableBytes: 300 << 20))
        #expect(!UpscaleEngine.fits(width: 0, height: 10, scale: 4, availableBytes: plenty))
        #expect(!UpscaleEngine.fits(width: Int.max / 2, height: 1, scale: 4, availableBytes: plenty))
    }

    @Test func mappedPixelsRoundTrip() throws {
        let pixels = try MappedPixels(count: 16)
        for i in 0 ..< 16 { pixels.pixels[i] = UInt8(i * 10) }
        let data = try pixels.finish()
        #expect(Array(data) == (0 ..< 16).map { UInt8($0 * 10) })
        let image = try UpscaleEngine.image(data, width: 2, height: 2)
        #expect(image.width == 2 && image.height == 2)
    }
}

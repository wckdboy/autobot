import Foundation
import Testing
@testable import Autobot

struct CatalogTests {
    private static let repoRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()

    /// kotlinx.serialization defaults: enums by name, default values omitted.
    private let fixture = """
    [
      {"id":"catalog:qwen","kind":"CHAT","source":"CATALOG","title":"Qwen","subtitle":"Q4_0","format":"GGUF","engine":"LLAMA","license":"apache-2.0",
       "manifest":{"files":[{"role":"MODEL","name":"q.gguf","url":"https://huggingface.co/u/q/resolve/main/q.gguf","sizeBytes":1000,"auth":"HUGGING_FACE"},
                            {"role":"MMPROJ","name":"mmproj-F16.gguf","url":"https://huggingface.co/u/q/resolve/main/mmproj-F16.gguf","sizeBytes":10,"auth":"HUGGING_FACE"}],
                   "recommended":{"contextLength":16384}}},
      {"id":"catalog:sd","kind":"IMAGE","source":"CATALOG","title":"SD","subtitle":"","format":"GGUF","engine":"DIFFUSION",
       "manifest":{"files":[{"role":"MODEL","name":"sd.gguf","url":"https://x/sd.gguf"}],"recommended":{"steps":20,"cfg":7.0,"sampler":"euler_a"}}},
      {"id":"catalog:npu","kind":"IMAGE","source":"CATALOG","title":"NPU","subtitle":"","format":"QNN","engine":"NPU",
       "manifest":{"files":[{"role":"UNET","name":"unet.bin","url":"https://x/p.zip","zipEntry":{"entry":"unet.bin"},"keepName":true}],"npu":{"arch":"sd15","runtime":"qnn"}}},
      {"id":"catalog:whisper","kind":"SPEECH","source":"CATALOG","title":"Whisper","subtitle":"","format":"GGML","engine":"WHISPER","license":"mit",
       "manifest":{"files":[{"role":"MODEL","name":"ggml-base-q5_1.bin","url":"https://x/w.bin","sizeBytes":57}]}}
    ]
    """

    @Test func decodesKotlinShapedCatalog() throws {
        let plans = try Catalog.decode(Data(fixture.utf8))
        #expect(plans.count == 4)
        let qwen = plans[0]
        #expect(qwen.kind == .chat && qwen.engine == .llama && qwen.format == .gguf && qwen.source == .catalog)
        #expect(qwen.manifest.files[1].role == .mmproj)
        #expect(qwen.manifest.files[0].auth == .huggingFace)
        #expect(qwen.manifest.files[0].sha256 == nil)
        #expect(qwen.manifest.files[0].keepName == false)
        #expect(qwen.manifest.recommended?.contextLength == 16384)
        #expect(qwen.manifest.trainedWords.isEmpty && !qwen.manifest.gated && !qwen.nsfw)
        #expect(qwen.totalBytes == 1010)
        #expect(plans[2].manifest.files[0].zipEntry?.dataOffset == -1)
        #expect(plans[2].manifest.npu?.runtime == "qnn")
    }

    @Test func decodesGroupedCatalog() throws {
        let grouped = "{\"chatModels\": \(fixture), \"broken\": [{\"id\": 1}]}"
        #expect(try Catalog.decode(Data(grouped.utf8)).count == 4)
    }

    @Test func sharedCatalogFileDecodesAndKeepsRunnableEntries() throws {
        let url = Self.repoRoot.appendingPathComponent("shared/catalog/catalog.json")
        try #require(FileManager.default.fileExists(atPath: url.path), "shared/catalog/catalog.json not exported yet")
        let plans = try Catalog.decode(Data(contentsOf: url))
        #expect(plans.contains { $0.engine == .llama })
        #expect(plans.contains { $0.engine == .whisper })
        let runnable = plans.filter { $0.engine == .llama || $0.engine == .whisper }
        #expect(runnable.allSatisfy { !$0.manifest.files.isEmpty && $0.manifest.files.allSatisfy { $0.url.hasPrefix("https://") } })
        #expect(runnable.count < plans.count, "the export also carries Android-only image entries")
    }

    @Test func bundledCatalogOnlyHasIOSEngines() {
        #expect(!Catalog.shared.isEmpty)
        #expect(Catalog.shared.allSatisfy { $0.engine == .llama || $0.engine == .whisper })
        #expect(!Catalog.chat.isEmpty && !Catalog.speech.isEmpty)
    }

    @Test func coreMLCatalogIsComplete() {
        #expect(CoreMLCatalog.all.count == 3)
        for plan in CoreMLCatalog.all {
            let names = Set(plan.manifest.files.map(\.name))
            #expect(plan.engine == .coreML && plan.format == .coreML && plan.kind == .image)
            #expect(names.contains("vocab.json") && names.contains("merges.txt"))
            #expect(names.contains("VAEDecoder.mlmodelc/weights/weight.bin"))
            #expect(names.contains("TextEncoder.mlmodelc/model.mil"))
            #expect(!names.contains { $0.hasPrefix("SafetyChecker") })
            #expect(plan.manifest.files.allSatisfy { $0.keepName && $0.sizeBytes > 0 && $0.url.contains("/resolve/") })
            // Weights are LFS objects with a known SHA-256.
            #expect(plan.manifest.files.filter { $0.name.hasSuffix("weight.bin") }.allSatisfy { $0.sha256?.count == 64 })
        }
        let sd15 = CoreMLCatalog.all[0]
        #expect(sd15.manifest.files.contains { $0.name.hasPrefix("VAEEncoder.mlmodelc") })
        #expect(sd15.totalBytes == 957_337_899)
    }

    @Test func coreMLFilesKeepTheirRelativePath() {
        let file = CoreMLCatalog.all[0].manifest.files.first { $0.name == "Unet.mlmodelc/weights/weight.bin" }!
        let url = ModelLibrary.file(of: "catalog:x", file)
        #expect(url.path.hasSuffix("/Unet.mlmodelc/weights/weight.bin"))
        #expect(ModelLibrary.safeRelativePath("../../etc/passwd") == "file/file/etc/passwd")
    }
}

import Foundation
import Testing
@testable import Autobot

struct CatalogTests {
    @Test func sharedCatalogBridgesEveryField() {
        // Read through the framework (no JSON): the Android catalog's chat, code and speech entries.
        let qwen = Catalog.shared.first { $0.id == "catalog:qwen3-vl-2b-q4_0" }
        #expect(qwen?.kind == .chat && qwen?.engine == .llama && qwen?.format == .gguf && qwen?.source == .catalog)
        #expect(qwen?.manifest.files.map(\.role) == [.model, .mmproj])
        #expect(qwen?.manifest.files.first?.auth == .huggingFace)
        #expect(qwen?.manifest.files.first?.url == "https://huggingface.co/unsloth/Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen3-VL-2B-Instruct-Q4_0.gguf")
        #expect(qwen?.manifest.recommended?.contextLength == 8192)
        #expect(qwen?.totalBytes == Int64(1008 + 781) * 1_048_576)
        #expect(Catalog.shared.first { $0.id == "catalog:qwen3-4b-2507-q4_0" }?.manifest.notes?.hasPrefix("Recommended for agent sessions.") == true)
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
            #expect(plan.manifest.recommended?.sampler == "DPM++ 2M" && plan.manifest.recommended?.cfg != nil)
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

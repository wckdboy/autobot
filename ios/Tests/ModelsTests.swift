import Foundation
import Testing
@testable import Autobot

/// Port of `ModelsTest.kt` (iOS engines: llama.cpp, whisper.cpp, Core ML).
struct ModelsTests {
    @Test func catalogIsConsistent() {
        let ids = Catalog.all.map(\.id)
        #expect(ids.count == Set(ids).count)
        for plan in Catalog.all {
            #expect(!plan.manifest.files.isEmpty, "\(plan.id)")
            for f in plan.manifest.files {
                #expect(f.url.hasPrefix("https://huggingface.co/") && f.url.contains("/resolve/"), "\(f.url)")
                #expect(f.auth == .huggingFace)
                #expect(f.sizeBytes > 0)
            }
            let format = plan.format == .coreML ? ModelFormat.coreML : plan.format
            #expect(engineFor(plan.kind, format) == plan.engine, "\(plan.id)")
        }
    }

    @Test func formatsAndEngines() {
        #expect(formatOf("Qwen3-4B-Q4_0.gguf") == .gguf)
        #expect(formatOf("dreamshaper_8.safetensors") == .safetensors)
        #expect(formatOf("AnythingV5_qnn2.28_8gen2.zip", owner: "xororz") == .qnn)
        #expect(formatOf("ggml-base-q5_1.bin") == .ggml)
        #expect(formatOf("Unet.mlmodelc/weights/weight.bin") == .coreML)
        #expect(engineFor(.speech, .ggml) == .whisper)
        #expect(engineFor(.chat, .gguf) == .llama)
        #expect(engineFor(.image, .coreML) == .coreML)
        // Android-only formats have no engine on iOS.
        #expect(engineFor(.image, .qnn) == .none)
        #expect(engineFor(.image, .gguf) == .none)
        #expect(engineFor(.image, .safetensors) == .none)
        #expect(engineFor(.chat, .safetensors) == .none)
    }

    @Test func formatBytesRules() {
        #expect(formatBytes(1_500_000_000) == "1.5 GB")
        #expect(formatBytes(512_000_000) == "512 MB")
        #expect(formatBytes(2_000) == "2 KB")
        #expect(formatBytes(12) == "12 B")
    }

    private func phone(ram: Int64) -> DeviceProfile {
        DeviceProfile(machine: "iPhone18,1", chip: "A19 Pro", totalRamBytes: ram, freeStorageBytes: 200 << 30, cores: 6, gpu: "Apple A19 Pro GPU", thermal: .nominal)
    }

    @Test func deviceProfileFits() {
        let p = phone(ram: 12 << 30)
        #expect(p.label == "iPhone18,1 · A19 Pro")
        #expect(p.fit(.chat, bytes: 2_500_000_000, engine: .llama) == .good)
        #expect(p.fit(.chat, bytes: 9_000_000_000, engine: .llama) == .tooBig)
        #expect(p.fit(.image, bytes: 1, engine: .none) == .unsupported)
        #expect(p.fit(.image, bytes: 1_000_000_000, engine: .diffusion) == .unsupported)
        #expect(p.fit(.image, bytes: 957_337_899, engine: .coreML) == .good)
        // SDXL (3.05 GB): fits 12 GiB, tight on 8 GiB, too big on 6 GiB.
        #expect(p.fit(.image, bytes: 3_052_951_670, engine: .coreML) == .good)
        #expect(phone(ram: 8 << 30).fit(.image, bytes: 3_052_951_670, engine: .coreML) == .tight)
        #expect(phone(ram: 6 << 30).fit(.image, bytes: 3_052_951_670, engine: .coreML) == .tooBig)
        #expect(DeviceProfile.chipName("iPhone17,1") == "A18 Pro")
        #expect(p.capabilities.totalRamBytes == 12 << 30)
    }

    @Test func fastestBackendUsesNewestSuccessfulRun() {
        let runs = [
            Benchmark(modelId: "m", backend: .cpu, generationTps: 9, createdAt: 1),
            Benchmark(modelId: "m", backend: .gpu, generationTps: 4, createdAt: 1),
            Benchmark(modelId: "m", backend: .gpu, generationTps: 20, createdAt: 2),
            Benchmark(modelId: "m", backend: .npu, error: "no backend", createdAt: 3),
        ]
        #expect(ComputeProfile.fastest(runs) == .gpu)
        #expect(ComputeProfile.fastest([Benchmark(modelId: "m", backend: .gpu, generationTps: 50, createdAt: 1),
                                        Benchmark(modelId: "m", backend: .gpu, generationTps: 1, createdAt: 2),
                                        Benchmark(modelId: "m", backend: .cpu, generationTps: 3, createdAt: 1)]) == .cpu)
        #expect(ComputeProfile.fastest([Benchmark(modelId: "i", backend: .npu, stepMs: 300), Benchmark(modelId: "i", backend: .gpu, stepMs: 900)]) == .npu)
        #expect(ComputeProfile.fastest([]) == nil)
    }

    @Test func civitaiPlansHaveNoEngineOnIOS() throws {
        let file = CivitaiFile(name: "m.safetensors", sizeBytes: 1536, sha256: "abcdef", format: nil, primary: true, downloadUrl: "https://civitai.com/api/download/models/9")
        let version = CivitaiVersion(id: 9, name: "v1", baseModel: "SD 1.5", trainedWords: ["w"], files: [file], previewUrl: nil, previewMature: false, earlyAccess: false)
        let model = CivitaiModel(id: 1, name: "M", type: "LORA", nsfw: false, creator: nil, downloads: 5, allowCommercialUse: nil, versions: [version])
        let plan = try #require(civitaiPlan(model, version))
        #expect(plan.kind == .lora)
        #expect(plan.engine == .none)
        #expect(plan.id == "civitai:9")
        #expect(plan.subtitle == "v1 · SD 1.5")
        #expect(plan.manifest.files.first?.auth == .civitai)
        #expect(plan.manifest.trainedWords == ["w"])
    }

    @Test func coreMLBundlesAreFoundInRepoListings() {
        let files = CoreMLCatalog.all[0].manifest.files.map { HfFile(path: "split_einsum_v2/compiled/" + $0.name, sizeBytes: $0.sizeBytes, sha256: $0.sha256) }
            + [HfFile(path: "README.md", sizeBytes: 1, sha256: nil), HfFile(path: "packages/x.mlpackage/Data/m", sizeBytes: 1, sha256: nil)]
        let repo = HfRepo(id: "apple/x", commit: "abc", gated: false, license: nil, files: files)
        #expect(repo.coreMLBundles == ["split_einsum_v2/compiled"])
        #expect(repo.coreMLSize("split_einsum_v2/compiled") == 957_337_899)
    }
}

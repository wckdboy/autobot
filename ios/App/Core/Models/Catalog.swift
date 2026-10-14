import Foundation

/**
 Curated starter models (port of `Catalog.kt`). Chat, code and speech entries are shared with
 Android: they come from `catalog.json` (exported from the Android catalog into
 `shared/catalog/catalog.json` and bundled at build time) and fall back to the copy below. Images
 run on the Neural Engine through Core ML here, so [CoreMLCatalog] replaces Android's sd.cpp /
 QNN image entries. Sizes are approximate; the real size and SHA-256 come from the Hub at
 download time (`X-Linked-Size` / `X-Linked-ETag`), and every file is verified against them.
 */
enum Catalog {
    private static let hf = "https://huggingface.co"

    /// Shared entries this build can run (llama.cpp chat/code, whisper.cpp speech).
    static let shared: [ModelPlan] = {
        let bundled = Bundle.main.url(forResource: "catalog", withExtension: "json").flatMap { try? Data(contentsOf: $0) }
        let plans = bundled.flatMap { try? decode($0) } ?? fallback
        return plans.filter { $0.engine == .llama || $0.engine == .whisper }
    }()

    static var chat: [ModelPlan] { shared.filter { $0.kind == .chat } }
    static var code: [ModelPlan] { shared.filter { $0.kind == .code } }
    static var speech: [ModelPlan] { shared.filter { $0.kind == .speech } }
    static var all: [ModelPlan] { shared + CoreMLCatalog.all }

    /**
     Decodes the exported catalog: kotlinx.serialization defaults (enums by name, default values
     omitted). Accepts a top-level array of plans or an object whose values are arrays of plans
     (e.g. `{"chatModels": [...], "imageModels": [...]}`).
     */
    static func decode(_ data: Data) throws -> [ModelPlan] {
        let decoder = JSONDecoder()
        if let list = try? decoder.decode([LenientPlan].self, from: data) { return list.compactMap(\.plan) }
        let groups = try decoder.decode([String: [LenientPlan]].self, from: data)
        return groups.keys.sorted().flatMap { key in groups[key]!.compactMap(\.plan) }
    }

    /// Skips entries that fail to decode instead of failing the whole catalog.
    private struct LenientPlan: Decodable {
        let plan: ModelPlan?
        init(from decoder: Decoder) throws { plan = try? ModelPlan(from: decoder) }
    }

    static func hfFile(_ repo: String, _ path: String, mib: Int64, role: FileRole = .model) -> ModelFile {
        ModelFile(role: role, name: String(path.split(separator: "/").last!), url: "\(hf)/\(repo)/resolve/main/\(path)", sizeBytes: mib * 1_048_576, auth: .huggingFace)
    }

    private static func chat(
        _ id: String, _ title: String, _ subtitle: String, _ repo: String, _ file: String, _ mib: Int64, _ license: String,
        kind: ModelKind = .chat, context: Int = 8192, notes: String? = nil, mmproj: (String, Int64)? = nil,
    ) -> ModelPlan {
        ModelPlan(
            id: "catalog:\(id)", kind: kind, source: .catalog, title: title, subtitle: subtitle, format: .gguf, engine: .llama, license: license,
            manifest: ModelManifest(
                files: [hfFile(repo, file, mib: mib)] + (mmproj.map { [hfFile(repo, $0.0, mib: $0.1, role: .mmproj)] } ?? []),
                pageUrl: "\(hf)/\(repo)",
                recommended: Recommended(contextLength: context),
                notes: notes,
            ),
        )
    }

    private static func speech(_ id: String, _ title: String, _ subtitle: String, _ file: String, _ mib: Int64) -> ModelPlan {
        ModelPlan(
            id: "catalog:\(id)", kind: .speech, source: .catalog, title: title, subtitle: subtitle, format: .ggml, engine: .whisper, license: "mit",
            manifest: ModelManifest(files: [hfFile("ggerganov/whisper.cpp", file, mib: mib)], pageUrl: "\(hf)/ggerganov/whisper.cpp"),
        )
    }

    /// Copy of the Android catalog's chat, code and speech entries (used until catalog.json ships).
    static let fallback: [ModelPlan] = [
        chat("qwen3.5-0.8b-q4_0", "Qwen3.5 0.8B", "Q4_0 · fast, low RAM", "unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_0.gguf", 484, "apache-2.0"),
        chat("qwen3.5-2b-q4_0", "Qwen3.5 2B", "Q4_0 · balanced", "unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_0.gguf", 1159, "apache-2.0"),
        chat("qwen3.5-4b-q4_0", "Qwen3.5 4B", "Q4_0 · best small all-rounder", "unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_0.gguf", 2464, "apache-2.0"),
        chat("qwen3-4b-2507-q4_0", "Qwen3 4B Instruct 2507", "Q4_0 · strong tool calling", "unsloth/Qwen3-4B-Instruct-2507-GGUF",
             "Qwen3-4B-Instruct-2507-Q4_0.gguf", 2266, "apache-2.0", context: 16384, notes: "Recommended for agent sessions."),
        chat("gemma-4-e2b-q4_0", "Gemma 4 E2B", "Q4_0 · Google", "unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_0.gguf", 2900, "apache-2.0"),
        chat("phi-4-mini-q4_0", "Phi-4 mini", "Q4_0 · Microsoft", "bartowski/microsoft_Phi-4-mini-instruct-GGUF", "microsoft_Phi-4-mini-instruct-Q4_0.gguf", 2223, "mit"),
        chat("smollm3-3b-q4_0", "SmolLM3 3B", "Q4_0 · Hugging Face", "unsloth/SmolLM3-3B-GGUF", "SmolLM3-3B-Q4_0.gguf", 1728, "apache-2.0"),
        chat("llama-3.2-3b-q4_0", "Llama 3.2 3B", "Q4_0 · Meta", "bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_0.gguf", 1833,
             "llama3.2", notes: "Llama 3.2 Community License and Acceptable Use Policy apply."),
        chat("qwen2.5-coder-1.5b-q4_0", "Qwen2.5 Coder 1.5B", "Q4_0 · code", "Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF",
             "qwen2.5-coder-1.5b-instruct-q4_0.gguf", 1017, "apache-2.0", kind: .code),
        chat("qwen2.5-coder-7b-q4_k_m", "Qwen2.5 Coder 7B", "Q4_K_M · code · 16 GB+ RAM", "unsloth/Qwen2.5-Coder-7B-Instruct-GGUF",
             "Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf", 4466, "apache-2.0", kind: .code),
        chat("qwen3-vl-2b-q4_0", "Qwen3-VL 2B", "Q4_0 · vision · reads photos and screenshots", "unsloth/Qwen3-VL-2B-Instruct-GGUF",
             "Qwen3-VL-2B-Instruct-Q4_0.gguf", 1008, "apache-2.0", mmproj: ("mmproj-F16.gguf", 781)),
        chat("qwen3-vl-4b-q4_0", "Qwen3-VL 4B", "Q4_0 · vision · stronger OCR and reasoning", "unsloth/Qwen3-VL-4B-Instruct-GGUF",
             "Qwen3-VL-4B-Instruct-Q4_0.gguf", 2266, "apache-2.0", mmproj: ("mmproj-F16.gguf", 797)),
        chat("smolvlm2-2.2b-q4_k_m", "SmolVLM2 2.2B", "Q4_K_M · vision · Hugging Face", "ggml-org/SmolVLM2-2.2B-Instruct-GGUF",
             "SmolVLM2-2.2B-Instruct-Q4_K_M.gguf", 1061, "apache-2.0", mmproj: ("mmproj-SmolVLM2-2.2B-Instruct-Q8_0.gguf", 565)),
        speech("whisper-base-q5_1", "Whisper base", "57 MB · fast dictation · 99 languages", "ggml-base-q5_1.bin", 57),
        speech("whisper-small-q5_1", "Whisper small", "181 MB · better accuracy", "ggml-small-q5_1.bin", 181),
        speech("whisper-large-v3-turbo-q8_0", "Whisper large-v3 turbo", "834 MB · best accuracy · slower", "ggml-large-v3-turbo-q8_0.bin", 834),
    ]
}

/**
 Core ML Stable Diffusion for the Neural Engine (iOS only): Apple's `coreml-stable-diffusion-*`
 repositories, compiled `split_einsum` resources downloaded file by file into one model folder
 (no zips). Paths, sizes and LFS SHA-256s verified against the Hub API (2026-10), pinned to a
 commit. The safety checker is left out (Autobot never filters output on device, as on Android).
 */
enum CoreMLCatalog {
    private static func plan(
        id: String, repo: String, revision: String, folder: String, title: String, subtitle: String, baseModel: String, license: String,
        recommended: Recommended, files: [(String, Int64, String?)],
    ) -> ModelPlan {
        ModelPlan(
            id: "catalog:\(id)", kind: .image, source: .catalog, title: title, subtitle: subtitle, format: .coreML, engine: .coreML,
            baseModel: baseModel, license: license,
            manifest: ModelManifest(
                files: files.map { path, size, sha in
                    ModelFile(
                        role: role(path), name: path, url: "https://huggingface.co/\(repo)/resolve/\(revision)/\(folder)/\(path)",
                        sizeBytes: size, sha256: sha, auth: .huggingFace, keepName: true,
                    )
                },
                pageUrl: "https://huggingface.co/\(repo)",
                recommended: recommended,
            ),
        )
    }

    private static func f(_ path: String, _ size: Int64, sha: String? = nil) -> (String, Int64, String?) { (path, size, sha) }

    private static func role(_ path: String) -> FileRole {
        if path.hasPrefix("Unet") { return .unet }
        if path.hasPrefix("TextEncoder2") { return .textEncoder2 }
        if path.hasPrefix("TextEncoder") { return .textEncoder }
        if path.hasPrefix("VAEDecoder") { return .vaeDecoder }
        if path.hasPrefix("VAEEncoder") { return .vaeEncoder }
        if path == "vocab.json" || path == "merges.txt" { return .tokenizer }
        return .other
    }

    static let all: [ModelPlan] = [
        plan(
            id: "coreml-sd15-palettized", repo: "apple/coreml-stable-diffusion-v1-5-palettized", revision: "04a6a0bdd66fb8da470c14e56d762343ef579d88", folder: "split_einsum_v2/compiled",
            title: "SD 1.5", subtitle: "6-bit palettized · neural engine · 512²", baseModel: "SD 1.5", license: "creativeml-openrail-m",
            recommended: Recommended(steps: 25, cfg: 7.5, sampler: "DPM++ 2M", scheduler: "Karras", width: 512, height: 512),
            files: [
            f("TextEncoder.mlmodelc/analytics/coremldata.bin", 207, sha: "988cd33580bb5c09498f19a846dba6cd34ac01f41683271dadef857ef74f3a67"),
            f("TextEncoder.mlmodelc/coremldata.bin", 825, sha: "3ed65fe33a7fdc432b88d6ec82934011db501c93ba7b4277a801f2e36e054d8b"),
            f("TextEncoder.mlmodelc/metadata.json", 2771),
            f("TextEncoder.mlmodelc/model.mil", 208229),
            f("TextEncoder.mlmodelc/weights/weight.bin", 139866304, sha: "24f28d2ddfcd5bf5e5484e13e4ea7a2fcf10d3979c65c1c319aa06efbaa209bb"),
            f("Unet.mlmodelc/analytics/coremldata.bin", 207, sha: "492ef32357f349b7e3683e6ae823721dbf3f0461024c8cb9e467de1838395ea3"),
            f("Unet.mlmodelc/coremldata.bin", 1207, sha: "2932318a18ec5ea96e21fdaa24b97a48eb0114230bb41f7f1df973418a1acb42"),
            f("Unet.mlmodelc/metadata.json", 3705),
            f("Unet.mlmodelc/model.mil", 3040467),
            f("Unet.mlmodelc/weights/weight.bin", 645167616, sha: "a278b99498e4f87e32e5e4faf05d576ad768a071bdce16741debf507439cd6dc"),
            f("VAEDecoder.mlmodelc/analytics/coremldata.bin", 207, sha: "1a9d135ee5dbd3b459b0f29834916205c6b4209e48e865837d0d99c221b29702"),
            f("VAEDecoder.mlmodelc/coremldata.bin", 755, sha: "a270b3c9dc565d860744d442e8927cf9c65bc5b17667a0489a7b857620ddbe65"),
            f("VAEDecoder.mlmodelc/metadata.json", 2472),
            f("VAEDecoder.mlmodelc/model.mil", 181386),
            f("VAEDecoder.mlmodelc/weights/weight.bin", 98993280, sha: "bde01e1323a5e756198ef2ae52331f04f9c3043df796b0ccc7ca541321c0fde3"),
            f("VAEEncoder.mlmodelc/analytics/coremldata.bin", 207, sha: "0911ab2348b7f2c228b1ffd421fbf834751773e5d3174a3dffa8584255b31ec7"),
            f("VAEEncoder.mlmodelc/coremldata.bin", 761, sha: "d1038a67b5829a8ab067ccabee5fb542117ba733bd516216225f3dd7e80143d7"),
            f("VAEEncoder.mlmodelc/metadata.json", 2460),
            f("VAEEncoder.mlmodelc/model.mil", 139736),
            f("VAEEncoder.mlmodelc/weights/weight.bin", 68338112, sha: "a1cf43e550e49a14d795b3402f07ece9a7cfbf405734dd73ce30881a8df6d771"),
            f("merges.txt", 524657),
            f("vocab.json", 862328),
            ],
        ),
        plan(
            id: "coreml-sd21-base-palettized", repo: "apple/coreml-stable-diffusion-2-1-base-palettized", revision: "dca5f2fda9c5143c71aded72bbb4ce39f3c2cc9c", folder: "split_einsum_v2/compiled",
            title: "SD 2.1 base", subtitle: "6-bit palettized · neural engine · 512²", baseModel: "SD 2.1", license: "openrail++",
            recommended: Recommended(steps: 25, cfg: 7.5, sampler: "DPM++ 2M", scheduler: "Karras", width: 512, height: 512),
            files: [
            f("TextEncoder.mlmodelc/analytics/coremldata.bin", 207, sha: "a26bdd792e919ca3412c0685e74ea507a6de835532f0ec58d780c99b0a7a0193"),
            f("TextEncoder.mlmodelc/coremldata.bin", 839, sha: "2f0484a1a04e023da06787de4140cf215f9ccd67f93d916dc388b846d8607899"),
            f("TextEncoder.mlmodelc/metadata.json", 2792),
            f("TextEncoder.mlmodelc/model.mil", 391100),
            f("TextEncoder.mlmodelc/weights/weight.bin", 318860352, sha: "3be3ff19e2ed53774d8c4c22f58bb4f9d37a44c2d6d34955e72decef86aa8615"),
            f("Unet.mlmodelc/analytics/coremldata.bin", 207, sha: "7432befba40c728344d55ededaedcbeebf04eca023437afb4418e655515c066c"),
            f("Unet.mlmodelc/coremldata.bin", 1221, sha: "5bc742d03b80b46feaa6f9e2159a5c89acda3ed2c1562f2b4f781bb7bfbce110"),
            f("Unet.mlmodelc/metadata.json", 3728),
            f("Unet.mlmodelc/model.mil", 3101782),
            f("Unet.mlmodelc/weights/weight.bin", 649959936, sha: "a4bfeb078744274ac2000593ae80522c8e3363bec8a4cf5e35ef93e3f7b14056"),
            f("VAEDecoder.mlmodelc/analytics/coremldata.bin", 207, sha: "682f30dbd27b8c96fae9dd5d7cec36e85812e370cc2a920d9a6cef7a908e29d3"),
            f("VAEDecoder.mlmodelc/coremldata.bin", 769, sha: "57555bee9ff5d60c5b6fd48564eac43523d954a2d34101273b8e3fa8f1aee3d0"),
            f("VAEDecoder.mlmodelc/metadata.json", 2493),
            f("VAEDecoder.mlmodelc/model.mil", 181387),
            f("VAEDecoder.mlmodelc/weights/weight.bin", 98993280, sha: "2caef4ff7a13ebe71592e39aff32a93ad62f54fb9fd8ea68ce9239550c5ed63b"),
            f("VAEEncoder.mlmodelc/analytics/coremldata.bin", 207, sha: "15f5816e1040cbb9124d5d3d9b17ea7cee1e32adda5322b45d992b072bfa352a"),
            f("VAEEncoder.mlmodelc/coremldata.bin", 775, sha: "a5fc14b860f094c7935a9df0edbd5f792eca65facc9f76fc1d16537ffe2e6405"),
            f("VAEEncoder.mlmodelc/metadata.json", 2481),
            f("VAEEncoder.mlmodelc/model.mil", 139736),
            f("VAEEncoder.mlmodelc/weights/weight.bin", 68338112, sha: "be8ea3259f6c51d5f5159b025fd907d5c0d5f244dbf8f9e40f34b22ab90b83bd"),
            f("merges.txt", 524657),
            f("vocab.json", 862328),
            ],
        ),
        plan(
            id: "coreml-sdxl-base-ios", repo: "apple/coreml-stable-diffusion-xl-base-ios", revision: "ef92c54fe816c826f5328709b545338518d08db7", folder: "coreml-stable-diffusion-xl-base-ios_split_einsum_compiled",
            title: "SDXL base", subtitle: "mixed-bit · neural engine · 768² · txt2img only · 8 GB+ RAM", baseModel: "SDXL 1.0", license: "openrail++",
            recommended: Recommended(steps: 25, cfg: 7.0, sampler: "DPM++ 2M", scheduler: "Karras", width: 768, height: 768),
            files: [
            f("TextEncoder.mlmodelc/analytics/coremldata.bin", 243, sha: "ba0cd4cdb851a15a59e0610f198aeb8dbe24cc758d99922e65955a44f79dfc9f"),
            f("TextEncoder.mlmodelc/coremldata.bin", 860, sha: "dce008301b82a1598022040d02303a414be207b0b12cd4510432281b6678a5f5"),
            f("TextEncoder.mlmodelc/metadata.json", 2804),
            f("TextEncoder.mlmodelc/model.mil", 187532),
            f("TextEncoder.mlmodelc/weights/weight.bin", 246145536, sha: "72a0d48befc2a65462b0c320927d31b4b0c8bdcae383d357f4209de9bbc4d6d5"),
            f("TextEncoder2.mlmodelc/analytics/coremldata.bin", 243, sha: "a6c5f66dbbbb69b15ab440424185a2775026f6661723158140531d0a8caf11a2"),
            f("TextEncoder2.mlmodelc/coremldata.bin", 860, sha: "70b1249456151731abd5a48006cd0d08a424172fdeb104e59099e182882d13d0"),
            f("TextEncoder2.mlmodelc/metadata.json", 2809),
            f("TextEncoder2.mlmodelc/model.mil", 490294),
            f("TextEncoder2.mlmodelc/weights/weight.bin", 1389367424, sha: "73ade7c979ddd33a1af71ecc581bdf4c5a5695f4c6adf6450ad0a914930d1459"),
            f("UnetChunk1.mlmodelc/analytics/coremldata.bin", 243, sha: "b6911510036e3a9f3df8544d6f451625e8faa8dc9f9f4c024ae5e88cf9b170c5"),
            f("UnetChunk1.mlmodelc/coremldata.bin", 743, sha: "4544dad1a05973f5c9298509782061ae5a6ccedf1cfce1dbf18e46735906863e"),
            f("UnetChunk1.mlmodelc/metadata.json", 6996),
            f("UnetChunk1.mlmodelc/model.mil", 6353892),
            f("UnetChunk1.mlmodelc/weights/weight.bin", 653930048, sha: "c3157612374be1fdf8e5e61dbf6b8c2138fdc81dd70c8ed08e785e62657d85fa"),
            f("UnetChunk2.mlmodelc/analytics/coremldata.bin", 243, sha: "338113769e38ebc471eb63a83b2a3a2497b8f0cc059a08c10cb3e6852896c75f"),
            f("UnetChunk2.mlmodelc/coremldata.bin", 672, sha: "1301537bd8ad0f89575f8c2b1398664df2d05032d38113bc3e85288b645ab466"),
            f("UnetChunk2.mlmodelc/metadata.json", 6092),
            f("UnetChunk2.mlmodelc/model.mil", 4107584),
            f("UnetChunk2.mlmodelc/weights/weight.bin", 651783424, sha: "3187aa81e7258dae2d80e0cb1060416f25265ea920e9be72642c24970caf9c52"),
            f("VAEDecoder.mlmodelc/analytics/coremldata.bin", 243, sha: "055c412933973d54894c58716cd08be44f50057a7f37e17be97b2e93b64b6321"),
            f("VAEDecoder.mlmodelc/coremldata.bin", 809, sha: "4492ee202d19fc3ed370a137f408e887fcf7ccf1fe957da9ed97bb7c9f9cab22"),
            f("VAEDecoder.mlmodelc/metadata.json", 2591),
            f("VAEDecoder.mlmodelc/model.mil", 179220),
            f("VAEDecoder.mlmodelc/weights/weight.bin", 98993280, sha: "9de777d8a5be28417a49a2a284749105d97d2a1eb56d2bdb448d511c82061b3e"),
            f("merges.txt", 524657),
            f("vocab.json", 862328),
            ],
        ),
    ]
}

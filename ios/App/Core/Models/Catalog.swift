import AutobotShared
import Foundation

/**
 Curated starter models, read from the shared catalog (`shared/.../core/models/Catalog.kt`).
 Chat, code and speech entries are the same as Android's; images run on the Neural Engine through
 Core ML here, so [CoreMLCatalog] replaces Android's sd.cpp / QNN image entries. Sizes are
 approximate; the real size and SHA-256 come from the Hub at download time (`X-Linked-Size` /
 `X-Linked-ETag`), and every file is verified against them.
 */
enum Catalog {
    /// Shared entries this build can run (llama.cpp chat/code, whisper.cpp speech).
    static let shared: [ModelPlan] = AutobotShared.Catalog.shared.all.map(ModelPlan.init).filter { $0.engine.runsOnIOS }

    static var chat: [ModelPlan] { shared.filter { $0.kind == .chat } }
    static var code: [ModelPlan] { shared.filter { $0.kind == .code } }
    static var speech: [ModelPlan] { shared.filter { $0.kind == .speech } }
    static var all: [ModelPlan] { shared + CoreMLCatalog.all }
}

/// Core ML Stable Diffusion and the Real-ESRGAN upscaler for the Neural Engine (the shared `CoreMLCatalog`).
enum CoreMLCatalog {
    static let all: [ModelPlan] = AutobotShared.CoreMLCatalog.shared.all.map(ModelPlan.init)

    /// 4× super-resolution on the Neural Engine for gallery images (Android's NPU upscaler).
    static let upscalers: [ModelPlan] = AutobotShared.CoreMLCatalog.shared.upscalers.map(ModelPlan.init)
}

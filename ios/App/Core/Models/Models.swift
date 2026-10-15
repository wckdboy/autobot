import Foundation

// Codable mirrors of `core/models/.../Models.kt`. Enum raw values are the Kotlin names, so the
// shared catalog (`shared/catalog/catalog.json`, kotlinx.serialization defaults: enums by name,
// default values omitted) decodes as is. Every optional or defaulted field tolerates absence.

/// What a model is for. Drives which engine and screen use it.
enum ModelKind: String, Codable, CaseIterable, Sendable {
    case chat = "CHAT", code = "CODE", image = "IMAGE", lora = "LORA", vae = "VAE", embedding = "EMBEDDING"
    case upscaler = "UPSCALER", speech = "SPEECH", other = "OTHER"

    var label: String { rawValue.lowercased() }
}

enum ModelFormat: String, Codable, CaseIterable, Sendable {
    case gguf = "GGUF"
    /// whisper.cpp model (`ggml-*.bin`).
    case ggml = "GGML"
    case safetensors = "SAFETENSORS"
    case ckpt = "CKPT"
    /// Precompiled Hexagon NPU package (Android only).
    case qnn = "QNN"
    /// MNN graphs (Android only).
    case mnn = "MNN"
    case localDream = "LOCAL_DREAM"
    /// Compiled Core ML Stable Diffusion resources (iOS only, Apple's `split_einsum` builds).
    case coreML = "COREML"
    case other = "OTHER"

    var label: String {
        switch self {
        case .gguf: "GGUF"
        case .ggml: "ggml"
        case .safetensors: "safetensors"
        case .ckpt: "ckpt"
        case .qnn: "QNN"
        case .mnn: "MNN"
        case .localDream: "Local Dream"
        case .coreML: "Core ML"
        case .other: "file"
        }
    }
}

/// Which built-in engine runs the model, if any.
enum EngineKind: String, Codable, CaseIterable, Sendable {
    case llama = "LLAMA", diffusion = "DIFFUSION", npu = "NPU", whisper = "WHISPER", coreML = "COREML", none = "NONE"

    var label: String {
        switch self {
        case .llama: "llama.cpp"
        case .diffusion: "sd.cpp"
        case .npu: "qnn · mnn"
        case .whisper: "whisper.cpp"
        case .coreML: "core ml"
        case .none: "none"
        }
    }

    /// Engines this iOS build can run.
    var runsOnIOS: Bool { self == .llama || self == .whisper || self == .coreML }
}

enum ModelSource: String, Codable, CaseIterable, Sendable {
    case catalog = "CATALOG", huggingFace = "HUGGING_FACE", civitai = "CIVITAI"

    var label: String {
        switch self {
        case .catalog: "catalog"
        case .huggingFace: "hugging face"
        case .civitai: "civitai"
        }
    }
}

enum ModelStatus: String, Codable, Sendable {
    case queued = "QUEUED", downloading = "DOWNLOADING", paused = "PAUSED", ready = "READY", failed = "FAILED"
}

/// Where a model runs. `.auto` lets the benchmark (or the engine) decide.
enum Backend: String, Codable, CaseIterable, Sendable {
    case auto, cpu, gpu, npu

    var id: String { rawValue }
    var label: String { rawValue }

    static func of(_ id: String?) -> Backend { Backend(rawValue: id ?? "") ?? .auto }
}

/// Role of a file within a model bundle.
enum FileRole: String, Codable, CaseIterable, Sendable {
    case model = "MODEL", diffusionModel = "DIFFUSION_MODEL", llm = "LLM", clipL = "CLIP_L", clipG = "CLIP_G", t5xxl = "T5XXL"
    case vae = "VAE", taesd = "TAESD", mmproj = "MMPROJ", other = "OTHER"
    case tokenizer = "TOKENIZER", tokenEmb = "TOKEN_EMB", posEmb = "POS_EMB", textEncoder = "TEXT_ENCODER"
    case tokenEmb2 = "TOKEN_EMB_2", posEmb2 = "POS_EMB_2", textEncoder2 = "TEXT_ENCODER_2", unet = "UNET"
    case vaeDecoder = "VAE_DECODER", vaeEncoder = "VAE_ENCODER"
    /// Companion data of another file, kept next to it under its own name.
    case weights = "WEIGHTS"
}

/// Which account's credentials a download URL may receive.
enum AuthHost: String, Codable, Sendable {
    case huggingFace = "HUGGING_FACE", civitai = "CIVITAI"
}

/// Where an entry lives inside a remote zip (Android's NPU packages; unused on iOS).
struct ZipEntryRef: Codable, Equatable, Sendable {
    var entry: String
    var dataOffset: Int64 = -1
    var compressedSize: Int64 = 0
    var method: Int = 0
    var crc32: Int64 = 0

    init(entry: String, dataOffset: Int64 = -1, compressedSize: Int64 = 0, method: Int = 0, crc32: Int64 = 0) {
        self.entry = entry
        self.dataOffset = dataOffset
        self.compressedSize = compressedSize
        self.method = method
        self.crc32 = crc32
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        entry = try c.decode(String.self, forKey: .entry)
        dataOffset = try c.decodeIfPresent(Int64.self, forKey: .dataOffset) ?? -1
        compressedSize = try c.decodeIfPresent(Int64.self, forKey: .compressedSize) ?? 0
        method = try c.decodeIfPresent(Int.self, forKey: .method) ?? 0
        crc32 = try c.decodeIfPresent(Int64.self, forKey: .crc32) ?? 0
    }
}

struct ModelFile: Codable, Equatable, Sendable {
    var role: FileRole
    /// File name. With [keepName] on iOS it may be a relative path inside the model directory
    /// (Core ML bundles: `Unet.mlmodelc/weights/weight.bin`).
    var name: String
    var url: String
    /// Expected size, or 0 when unknown until the server reports it.
    var sizeBytes: Int64 = 0
    /// Lower-case hex SHA-256 when known up front; otherwise adopted from the server.
    var sha256: String? = nil
    var auth: AuthHost? = nil
    var zipEntry: ZipEntryRef? = nil
    /// Store under [name] itself instead of a role-prefixed name.
    var keepName = false

    init(role: FileRole, name: String, url: String, sizeBytes: Int64 = 0, sha256: String? = nil, auth: AuthHost? = nil, zipEntry: ZipEntryRef? = nil, keepName: Bool = false) {
        self.role = role
        self.name = name
        self.url = url
        self.sizeBytes = sizeBytes
        self.sha256 = sha256
        self.auth = auth
        self.zipEntry = zipEntry
        self.keepName = keepName
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        role = try c.decode(FileRole.self, forKey: .role)
        name = try c.decode(String.self, forKey: .name)
        url = try c.decode(String.self, forKey: .url)
        sizeBytes = try c.decodeIfPresent(Int64.self, forKey: .sizeBytes) ?? 0
        sha256 = try c.decodeIfPresent(String.self, forKey: .sha256)
        auth = try c.decodeIfPresent(AuthHost.self, forKey: .auth)
        zipEntry = try c.decodeIfPresent(ZipEntryRef.self, forKey: .zipEntry)
        keepName = try c.decodeIfPresent(Bool.self, forKey: .keepName) ?? false
    }
}

/// How the NPU engine runs a package (Android only; decoded for completeness).
struct NpuPackage: Codable, Equatable, Sendable {
    var arch: String
    var runtime: String
    var vpred: Bool?
    var htpArch: String?
}

/// Engine defaults the model was made for (applied when it is selected).
struct Recommended: Codable, Equatable, Sendable {
    var steps: Int? = nil
    var cfg: Double? = nil
    var sampler: String? = nil
    var scheduler: String? = nil
    var width: Int? = nil
    var height: Int? = nil
    var contextLength: Int? = nil
    var negativePrompt: String? = nil
}

struct ModelManifest: Codable, Equatable, Sendable {
    var files: [ModelFile]
    var pageUrl: String? = nil
    var recommended: Recommended? = nil
    var trainedWords: [String] = []
    var gated = false
    var notes: String? = nil
    var npu: NpuPackage? = nil

    init(files: [ModelFile], pageUrl: String? = nil, recommended: Recommended? = nil, trainedWords: [String] = [], gated: Bool = false, notes: String? = nil, npu: NpuPackage? = nil) {
        self.files = files
        self.pageUrl = pageUrl
        self.recommended = recommended
        self.trainedWords = trainedWords
        self.gated = gated
        self.notes = notes
        self.npu = npu
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        files = try c.decode([ModelFile].self, forKey: .files)
        pageUrl = try c.decodeIfPresent(String.self, forKey: .pageUrl)
        recommended = try c.decodeIfPresent(Recommended.self, forKey: .recommended)
        trainedWords = try c.decodeIfPresent([String].self, forKey: .trainedWords) ?? []
        gated = try c.decodeIfPresent(Bool.self, forKey: .gated) ?? false
        notes = try c.decodeIfPresent(String.self, forKey: .notes)
        npu = try c.decodeIfPresent(NpuPackage.self, forKey: .npu)
    }
}

/// Everything needed to install a model.
struct ModelPlan: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var kind: ModelKind
    var source: ModelSource
    var title: String
    var subtitle: String
    var format: ModelFormat
    var engine: EngineKind
    var baseModel: String? = nil
    var license: String? = nil
    var nsfw = false
    var manifest: ModelManifest

    var totalBytes: Int64 { manifest.files.reduce(0) { $0 + $1.sizeBytes } }

    init(id: String, kind: ModelKind, source: ModelSource, title: String, subtitle: String, format: ModelFormat, engine: EngineKind,
         baseModel: String? = nil, license: String? = nil, nsfw: Bool = false, manifest: ModelManifest) {
        self.id = id
        self.kind = kind
        self.source = source
        self.title = title
        self.subtitle = subtitle
        self.format = format
        self.engine = engine
        self.baseModel = baseModel
        self.license = license
        self.nsfw = nsfw
        self.manifest = manifest
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = (try? c.decode(ModelKind.self, forKey: .kind)) ?? .other
        source = (try? c.decode(ModelSource.self, forKey: .source)) ?? .catalog
        title = try c.decode(String.self, forKey: .title)
        subtitle = try c.decodeIfPresent(String.self, forKey: .subtitle) ?? ""
        format = (try? c.decode(ModelFormat.self, forKey: .format)) ?? .other
        engine = (try? c.decode(EngineKind.self, forKey: .engine)) ?? .none
        baseModel = try c.decodeIfPresent(String.self, forKey: .baseModel)
        license = try c.decodeIfPresent(String.self, forKey: .license)
        nsfw = try c.decodeIfPresent(Bool.self, forKey: .nsfw) ?? false
        manifest = try c.decode(ModelManifest.self, forKey: .manifest)
    }
}

/// A model in the registry (installed or in progress).
struct InstalledModel: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var kind: ModelKind
    var source: ModelSource
    var title: String
    var subtitle: String
    var format: ModelFormat
    var engine: EngineKind
    var baseModel: String?
    var license: String?
    var nsfw: Bool
    var totalBytes: Int64
    var downloadedBytes: Int64
    var status: ModelStatus
    var error: String?
    var manifest: ModelManifest
    var addedAt: Int64
    var updatedAt: Int64
    /// The user's choice for this model (`.auto` unless they picked one).
    var backend: Backend = .auto

    var isReady: Bool { status == .ready }
    var fraction: Double { totalBytes <= 0 ? 0 : min(max(Double(downloadedBytes) / Double(totalBytes), 0), 1) }
    var runnable: Bool { isReady && engine != .none && engine.runsOnIOS }
}

/// Formats bytes as `2.4 GB` / `512 MB` (same rules as Android's `formatBytes`).
func formatBytes(_ bytes: Int64) -> String {
    if bytes >= 1_000_000_000 { return String(format: "%.1f GB", Double(bytes) / 1e9) }
    if bytes >= 1_000_000 { return "\(bytes / 1_000_000) MB" }
    if bytes >= 1_000 { return "\(bytes / 1_000) KB" }
    return "\(bytes) B"
}

/// Guesses the file format from its name (and the repo owner, for Local Dream packages).
func formatOf(_ name: String, owner: String? = nil) -> ModelFormat {
    let n = name.lowercased()
    let xororz = owner?.caseInsensitiveCompare("xororz") == .orderedSame
    if n.contains("_qnn") || (n.hasSuffix(".bin") && xororz) { return .qnn }
    if n.hasSuffix(".mnn") || (xororz && n.hasSuffix(".zip")) { return .mnn }
    if xororz { return .localDream }
    if n.hasSuffix(".gguf") { return .gguf }
    if n.hasPrefix("ggml-") && n.hasSuffix(".bin") { return .ggml }
    if n.contains(".mlmodelc") || n.contains(".mlpackage") { return .coreML }
    if n.hasSuffix(".safetensors") { return .safetensors }
    if n.hasSuffix(".ckpt") || n.hasSuffix(".pt") || n.hasSuffix(".pth") { return .ckpt }
    return .other
}

/// The built-in engine that can run [kind] in [format] on iOS (Android's sd.cpp/QNN formats
/// have no engine here; Core ML replaces them for images).
func engineFor(_ kind: ModelKind, _ format: ModelFormat) -> EngineKind {
    if kind == .speech { return format == .ggml ? .whisper : .none }
    if format == .coreML { return kind == .image || kind == .upscaler ? .coreML : .none }
    if kind == .chat || kind == .code { return format == .gguf ? .llama : .none }
    return .none
}

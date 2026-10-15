import Foundation
import Observation

// Port of `core/diffusion/.../Diffusion.kt` and the data half of `Backends.kt`.

enum DiffusionMode: String, Codable, CaseIterable, Sendable {
    case txt2img = "TXT2IMG", img2img = "IMG2IMG", inpaint = "INPAINT"

    var label: String { rawValue.lowercased() }
}

/// A LoRA applied at generation time (`<lora:name:weight>` on A1111-style backends).
struct LoraRef: Codable, Equatable, Hashable, Sendable {
    var name: String
    var weight: Double = 1
}

/**
 Engine-independent generation parameters (same JSON as Kotlin's `DiffusionRequest`, so gallery
 "reuse parameters" round-trips). Pixel inputs travel separately in [DiffusionInputs].
 `seed` -1 = random.
 */
struct DiffusionRequest: Codable, Equatable, Sendable {
    var mode: DiffusionMode = .txt2img
    var prompt = ""
    var negativePrompt = ""
    var width = 512
    var height = 512
    var steps = 20
    var cfgScale: Double = 7
    var seed: Int64 = -1
    var sampler = "DPM++ 2M"
    var scheduler: String? = "Karras"
    var batchCount = 1
    var denoiseStrength: Double = 0.6
    var maskBlur = 4
    var inpaintOnlyMasked = true
    var loras: [LoraRef] = []
    var clipSkip: Int? = nil
    var model: String? = nil

    init() {}

    enum CodingKeys: String, CodingKey {
        case mode, prompt, negativePrompt, width, height, steps, cfgScale, seed, sampler, scheduler, batchCount
        case denoiseStrength, maskBlur, inpaintOnlyMasked, loras, clipSkip, model
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = DiffusionRequest()
        mode = (try? c.decode(DiffusionMode.self, forKey: .mode)) ?? d.mode
        prompt = (try? c.decode(String.self, forKey: .prompt)) ?? d.prompt
        negativePrompt = (try? c.decode(String.self, forKey: .negativePrompt)) ?? d.negativePrompt
        width = (try? c.decode(Int.self, forKey: .width)) ?? d.width
        height = (try? c.decode(Int.self, forKey: .height)) ?? d.height
        steps = (try? c.decode(Int.self, forKey: .steps)) ?? d.steps
        cfgScale = (try? c.decode(Double.self, forKey: .cfgScale)) ?? d.cfgScale
        seed = (try? c.decode(Int64.self, forKey: .seed)) ?? d.seed
        sampler = (try? c.decode(String.self, forKey: .sampler)) ?? d.sampler
        scheduler = c.contains(.scheduler) ? try c.decodeIfPresent(String.self, forKey: .scheduler) : d.scheduler
        batchCount = (try? c.decode(Int.self, forKey: .batchCount)) ?? d.batchCount
        denoiseStrength = (try? c.decode(Double.self, forKey: .denoiseStrength)) ?? d.denoiseStrength
        maskBlur = (try? c.decode(Int.self, forKey: .maskBlur)) ?? d.maskBlur
        inpaintOnlyMasked = (try? c.decode(Bool.self, forKey: .inpaintOnlyMasked)) ?? d.inpaintOnlyMasked
        loras = (try? c.decode([LoraRef].self, forKey: .loras)) ?? d.loras
        clipSkip = try? c.decodeIfPresent(Int.self, forKey: .clipSkip)
        model = try? c.decodeIfPresent(String.self, forKey: .model)
    }

    /// Writes every field, nulls included (kotlinx `encodeDefaults = true`): a missing
    /// `scheduler` would decode back as the "Karras" default.
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(mode, forKey: .mode)
        try c.encode(prompt, forKey: .prompt)
        try c.encode(negativePrompt, forKey: .negativePrompt)
        try c.encode(width, forKey: .width)
        try c.encode(height, forKey: .height)
        try c.encode(steps, forKey: .steps)
        try c.encode(cfgScale, forKey: .cfgScale)
        try c.encode(seed, forKey: .seed)
        try c.encode(sampler, forKey: .sampler)
        try c.encode(scheduler, forKey: .scheduler)
        try c.encode(batchCount, forKey: .batchCount)
        try c.encode(denoiseStrength, forKey: .denoiseStrength)
        try c.encode(maskBlur, forKey: .maskBlur)
        try c.encode(inpaintOnlyMasked, forKey: .inpaintOnlyMasked)
        try c.encode(loras, forKey: .loras)
        try c.encode(clipSkip, forKey: .clipSkip)
        try c.encode(model, forKey: .model)
    }
}

/// How a backend wants the inpaint mask encoded.
enum MaskEncoding: Sendable {
    /// Opaque PNG: white = repaint, black = keep (A1111).
    case opaqueBlackWhite
    /// PNG with alpha: opaque white strokes = repaint, transparent = keep.
    case alphaWhite
}

/// PNG bytes for img2img / inpaint.
struct DiffusionInputs: Sendable, CustomStringConvertible {
    var initImage: Data? = nil
    var mask: Data? = nil
    var description: String { "DiffusionInputs(init=\(initImage?.count ?? 0)B, mask=\(mask?.count ?? 0)B)" }
}

enum DiffusionEvent: Sendable {
    /// What the backend is doing before sampling starts (e.g. loading the model), for the UI.
    case phase(String)
    /// Sampling progress. [preview] is an encoded image when the backend sends one.
    case progress(image: Int, step: Int, totalSteps: Int, preview: Data?)
    /// One finished image (PNG or JPEG bytes).
    case result(index: Int, image: Data, seed: Int64, width: Int, height: Int, durationMs: Int64, info: String?)
}

struct DiffusionError: LocalizedError, Sendable {
    enum Code: Sendable { case blocked, unreachable, backend, `protocol`, unsupported }
    let message: String
    var code: Code = .backend
    var errorDescription: String? { message }
}

/// What the UI should offer for this backend.
struct EngineCapabilities: Sendable {
    var modes: Set<DiffusionMode>
    var runtimeLora: Bool
    var modelSwitching: Bool
    var livePreview: Bool
    var maskEncoding: MaskEncoding
    /// Fixed output sizes, or empty when any multiple of [sizeMultiple] works.
    var fixedSizes: [(Int, Int)] = []
    var sizeMultiple = 8
    var maxSteps = 150
}

/// Discoverable resources of a backend.
struct EngineCatalog: Equatable, Sendable {
    var models: [String] = []
    var currentModel: String? = nil
    var loras: [String] = []
    var samplers: [String] = []
    var schedulers: [String] = []
}

/**
 An image backend. [generate] is cold: iterating starts the job, cancelling the consuming task
 cancels it (and interrupts the backend where the API allows). Failures are thrown as
 [DiffusionError].
 */
protocol DiffusionEngine: Sendable {
    var capabilities: EngineCapabilities { get }
    func catalog() async throws -> EngineCatalog
    func generate(_ request: DiffusionRequest, inputs: DiffusionInputs) -> AsyncThrowingStream<DiffusionEvent, Error>
}

/// Prompt helpers shared by engines and UI.
enum PromptSyntax {
    private static let loraTag = try! NSRegularExpression(pattern: #"<lora:([^:>]+)(?::([-0-9.]+))?>"#)

    /// Appends `<lora:…>` tags for [loras] not already present in [prompt].
    static func withLoraTags(_ prompt: String, _ loras: [LoraRef]) -> String {
        let ns = prompt as NSString
        let present = Set(loraTag.matches(in: prompt, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range(at: 1)) })
        let tags = loras.filter { !present.contains($0.name) && $0.weight != 0 }.map { "<lora:\($0.name):\(String(format: "%.2f", $0.weight))>" }.joined(separator: " ")
        return tags.isEmpty ? prompt : "\(prompt.replacingOccurrences(of: #"\s+$"#, with: "", options: .regularExpression)) \(tags)"
    }

    /// Removes LoRA tags (for engines that cannot apply them).
    static func stripLoraTags(_ prompt: String) -> String {
        let ns = prompt as NSString
        let stripped = loraTag.stringByReplacingMatches(in: prompt, range: NSRange(location: 0, length: ns.length), withTemplate: "")
        return stripped.replacingOccurrences(of: #"\s{2,}"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    }

    /// Rough CLIP token estimate, good enough for an "approaching 75" warning.
    static func estimateTokens(_ prompt: String) -> Int {
        let clean = stripLoraTags(prompt)
        let regex = try! NSRegularExpression(pattern: #"[A-Za-z]+|\d+|[^\sA-Za-z\d]"#)
        let ns = clean as NSString
        return regex.matches(in: clean, range: NSRange(location: 0, length: ns.length)).reduce(0) { n, m in n + (m.range.length > 7 ? 2 : 1) }
    }

    /// Snaps to the backend's multiple, clamped to [min]...[max].
    static func snap(_ value: Int, multiple: Int = 8, min: Int = 64, max: Int = 2048) -> Int {
        Swift.min(Swift.max((value + multiple / 2) / multiple * multiple, min), max)
    }
}

enum BackendKind: String, Codable, CaseIterable, Sendable {
    /// Core ML Stable Diffusion on this phone (models from the Models tab).
    case onDevice = "ON_DEVICE"
    /// AUTOMATIC1111 / Forge / SD.Next REST API.
    case sdApi = "SD_API"
    /// Local HTTP+SSE diffusion server (Local Dream host mode and compatible).
    case localSse = "LOCAL_SSE"

    var label: String {
        switch self {
        case .onDevice: "This phone"
        case .sdApi: "A1111 / Forge API"
        case .localSse: "Local SSE engine"
        }
    }
}

/// A configured image backend. Holds no secrets. [routing] is a [RouteOverride] name.
struct DiffusionBackend: Codable, Identifiable, Equatable, Hashable, Sendable {
    var id: String
    var kind: BackendKind
    var name: String
    var baseUrl: String
    var routing: String = RouteOverride.inherit.rawValue

    var isLoopback: Bool { kind == .onDevice || Loopback.isLoopbackURL(baseUrl) }
    var routeOverride: RouteOverride { RouteOverride(rawValue: routing) ?? .inherit }
}

/**
 Backends, the default selection and the last request, in [Preferences]. Also keeps the built-in
 "This phone" backend in step with installed Core ML image models (Android's
 `OnDeviceBackendSync`).
 */
@MainActor
@Observable
final class DiffusionBackendRepository {
    static let onDeviceId = "on-device"
    /// Starting points; loopback, so they work in Offline mode.
    static let presets: [(BackendKind, String)] = [(.localSse, "http://127.0.0.1:8081"), (.sdApi, "http://127.0.0.1:7860")]

    private(set) var backends: [DiffusionBackend]
    private(set) var defaultBackendId: String?
    private(set) var lastRequest: DiffusionRequest
    @ObservationIgnored private let preferences: Preferences

    init(preferences: Preferences) {
        self.preferences = preferences
        backends = preferences.get("diffusion_backends", as: [DiffusionBackend].self) ?? []
        defaultBackendId = preferences.get("diffusion_default_backend", as: String.self)
        lastRequest = preferences.get("diffusion_last_request", as: DiffusionRequest.self) ?? DiffusionRequest()
    }

    var defaultBackend: DiffusionBackend? { backends.first { $0.id == defaultBackendId } ?? backends.first }

    func save(_ backend: DiffusionBackend) {
        backends = backends.filter { $0.id != backend.id } + [backend]
        preferences.set("diffusion_backends", backends)
        if defaultBackendId == nil { setDefault(backend.id) }
    }

    func delete(_ id: String) {
        backends.removeAll { $0.id == id }
        preferences.set("diffusion_backends", backends)
        if defaultBackendId == id {
            defaultBackendId = backends.first?.id
            preferences.set("diffusion_default_backend", defaultBackendId)
        }
    }

    func setDefault(_ id: String) {
        defaultBackendId = id
        preferences.set("diffusion_default_backend", id)
    }

    func saveLastRequest(_ request: DiffusionRequest) {
        lastRequest = request
        preferences.set("diffusion_last_request", request)
    }

    func newId() -> String { UUID().uuidString.lowercased() }

    /// Adds "This phone" when the first on-device image model is ready; removes it when none are left.
    func syncOnDevice(available: Bool) {
        let existing = backends.contains { $0.id == Self.onDeviceId }
        if available && !existing {
            save(DiffusionBackend(id: Self.onDeviceId, kind: .onDevice, name: "This phone", baseUrl: "local://this-phone"))
        } else if !available && existing {
            delete(Self.onDeviceId)
        }
    }
}

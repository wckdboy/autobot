#if canImport(AutobotShared)
import AutobotShared
import Foundation

/**
 `generate_image` for the shared agent loop (port of Android's `GenerateImageTool.kt`: same name,
 description, schema, kind, summary, output text and `meta.gallery`). Renders on the user's
 default image backend (Core ML on this phone, or a PC backend). Unspecified parameters come from
 the last Imagine settings, so the user's sampler and model carry over. Results go to the
 encrypted gallery; the model gets their ids and seeds. In an incognito chat they stay in memory
 only, unsaved (`IncognitoImages`), until the user saves them.
 */
final class GenerateImageTool: NSObject, HostTool, @unchecked Sendable {
    let name = "generate_image"
    let kind = "GENERATE"
    let description_ =
        "Generate images with Stable Diffusion on the user's configured image backend. Write prompts as comma-separated " +
        "visual tags or a short scene description (subject, style, lighting, composition). Omitted parameters reuse " +
        "the user's current Imagine settings. Images are saved to the user's private gallery (in an incognito chat they are only shown, unsaved)."
    /// The JSON the Kotlin `schema { … }` DSL builds for Android's tool.
    let parametersJson = """
    {"type":"object","properties":{\
    "prompt":{"type":"string","description":"Positive prompt; {a|b} makes one image per option (same seed)"},\
    "negative_prompt":{"type":"string","description":"What to avoid"},\
    "width":{"type":"integer","minimum":256,"maximum":2048,"description":"Width in px (multiple of 8)"},\
    "height":{"type":"integer","minimum":256,"maximum":2048,"description":"Height in px (multiple of 8)"},\
    "steps":{"type":"integer","minimum":1,"maximum":100,"description":"Sampling steps"},\
    "seed":{"type":"integer","minimum":-1,"description":"Seed, or -1 for random"},\
    "count":{"type":"integer","minimum":1,"maximum":4,"description":"Images per prompt (1–4)"}},\
    "required":["prompt"],"additionalProperties":false}
    """
    let timeoutMs_: Int64 = 15 * 60_000

    private let generator: ImageGenerator
    private let backends: DiffusionBackendRepository

    @MainActor
    init(generator: ImageGenerator, backends: DiffusionBackendRepository) {
        self.generator = generator
        self.backends = backends
    }

    func describe(argsJson: String) -> String { Self.args(argsJson)["prompt"] as? String ?? "" }

    func execute(callId: String, sessionId: String, argsJson: String, sink: HostToolSink) -> any AutobotShared.Cancellable {
        nonisolated(unsafe) let sink = sink
        let args = Self.args(argsJson)
        let task = Task { @MainActor [generator, backends] in
            let result = await Self.run(args, generator: generator, backends: backends, sessionId: sessionId)
            sink.onResult(content: result.content, isError: result.isError, code: result.code, metaJson: result.meta)
        }
        return TaskHandle(task)
    }

    struct Output: Equatable {
        var content: String
        var isError = false
        var code: String? = nil
        var meta: String? = nil

        static func error(_ message: String, _ code: String) -> Output { Output(content: "Error: \(message)", isError: true, code: code) }
    }

    @MainActor
    static func run(_ args: [String: Any], generator: ImageGenerator, backends: DiffusionBackendRepository, sessionId: String = "") async -> Output {
        guard let backend = backends.defaultBackend else {
            return .error("no image backend is configured (Imagine → Backends)", "NO_BACKEND")
        }
        if generator.isBusy { return .error("the image backend is busy with another job; try again shortly", "BUSY") }
        let request = request(args, base: backends.lastRequest)
        do {
            let incognito = IncognitoImages.shared.isIncognitoSession(sessionId: sessionId)
            let images = try await generator.generate(backend, request.calls(), DiffusionInputs(), source: .agent, incognitoOwner: incognito ? sessionId : nil, onResult: { _ in })
            let list = images.map { "\(incognito ? "image" : "gallery"):\($0.galleryId) (seed \($0.seed), \($0.width)×\($0.height), \(Double($0.durationMs) / 1000)s)" }
            let meta = try? JSONSerialization.data(withJSONObject: ["gallery": images.map(\.galleryId)])
            let note = incognito ? IncognitoImages.shared.toolNote(count: Int32(images.count)) : "They are shown to the user in the chat and saved to the gallery."
            return Output(
                content: "Generated \(images.count) image(s) on \(backend.name): \(list.joined(separator: ", ")). \(note)",
                meta: meta.map { String(decoding: $0, as: UTF8.self) },
            )
        } catch is CancellationError {
            return .error("cancelled", "CANCELLED")
        } catch let e as DiffusionError {
            return .error(e.message, "IMAGE_\(code(e.code))")
        } catch {
            return .error(error.localizedDescription, "IMAGE_BACKEND")
        }
    }

    /// The Imagine settings with the model's arguments applied (txt2img, random seed by default).
    static func request(_ args: [String: Any], base: DiffusionRequest) -> DiffusionRequest {
        func int(_ key: String) -> Int? { (args[key] as? NSNumber)?.intValue }
        var r = base
        r.mode = .txt2img
        r.prompt = args["prompt"] as? String ?? ""
        r.negativePrompt = args["negative_prompt"] as? String ?? base.negativePrompt
        r.width = int("width").map { PromptSyntax.snap($0) } ?? base.width
        r.height = int("height").map { PromptSyntax.snap($0) } ?? base.height
        r.steps = int("steps") ?? base.steps
        r.seed = (args["seed"] as? NSNumber)?.int64Value ?? -1
        r.batchCount = int("count") ?? 1
        return r
    }

    private static func code(_ c: DiffusionError.Code) -> String {
        switch c {
        case .blocked: "BLOCKED"
        case .unreachable: "UNREACHABLE"
        case .backend: "BACKEND"
        case .protocol: "PROTOCOL"
        case .unsupported: "UNSUPPORTED"
        }
    }

    static func args(_ json: String) -> [String: Any] {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]) ?? [:]
    }
}
#endif

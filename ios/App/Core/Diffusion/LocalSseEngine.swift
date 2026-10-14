import Foundation

/**
 Client for a local diffusion server speaking the "generate over SSE" protocol (port of
 `LocalSseEngine.kt`):

 - `GET /health` → 200 when ready.
 - `POST /generate` (JSON) → `text/event-stream` with `progress`, `complete` and `error` events;
   dropping the connection cancels the job.

 Interoperable with Local Dream's backend host mode (written from the protocol description). The
 model is fixed by whatever the server loaded; LoRA tags are stripped from the prompt.
 */
struct LocalSseEngine: DiffusionEngine {
    let baseUrl: String
    let clientFactory: @Sendable () -> HttpClient

    /// Scheduler ids understood by the protocol.
    static let schedulers = ["dpm", "dpm_karras", "dpm_sde", "dpm_sde_karras", "euler", "euler_karras", "euler_a", "euler_a_karras", "lcm"]

    let capabilities = EngineCapabilities(
        modes: Set(DiffusionMode.allCases),
        runtimeLora: false,
        modelSwitching: false,
        livePreview: true,
        maskEncoding: .alphaWhite,
        maxSteps: 50,
    )

    func catalog() async throws -> EngineCatalog {
        guard let url = endpoint("health") else { throw DiffusionError(message: "Invalid backend URL", code: .unreachable) }
        do {
            let response = try await clientFactory().send(URLRequest(url: url))
            if !response.isSuccessful { throw DiffusionError(message: "Backend not ready (HTTP \(response.status))", code: .backend) }
        } catch let blocked as NetworkBlockedError {
            throw DiffusionError(message: blocked.message, code: .blocked)
        } catch let e as DiffusionError {
            throw e
        } catch {
            throw DiffusionError(message: error.localizedDescription, code: .unreachable)
        }
        return EngineCatalog(samplers: Self.schedulers)
    }

    func generate(_ request: DiffusionRequest, inputs: DiffusionInputs) -> AsyncThrowingStream<DiffusionEvent, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try await run(request, inputs) { continuation.yield($0) }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func run(_ request: DiffusionRequest, _ inputs: DiffusionInputs, emit: @Sendable (DiffusionEvent) -> Void) async throws {
        if request.mode != .txt2img && inputs.initImage == nil { throw DiffusionError(message: "\(request.mode.label) needs an input image", code: .unsupported) }
        if request.mode == .inpaint && inputs.mask == nil { throw DiffusionError(message: "Paint a mask first", code: .unsupported) }
        guard let url = endpoint("generate") else { throw DiffusionError(message: "Invalid backend URL", code: .unreachable) }
        let client = clientFactory()
        for index in 0 ..< max(request.batchCount, 1) {
            // A fixed seed advances per image so a batch is not N copies of one picture.
            let seed = request.seed >= 0 ? request.seed + Int64(index) : -1
            var http = URLRequest(url: url)
            http.httpMethod = "POST"
            http.setValue("text/event-stream", forHTTPHeaderField: "Accept")
            http.setValue("application/json", forHTTPHeaderField: "Content-Type")
            http.httpBody = try JSONSerialization.data(withJSONObject: Self.requestBody(request, inputs, seed: seed))
            http.timeoutInterval = 600
            let started = Date()
            do {
                let (bytes, response) = try await client.stream(http)
                guard (200 ..< 300).contains(response.statusCode) else {
                    let body = await bytes.prefixData(16_384)
                    throw DiffusionError(message: "HTTP \(response.statusCode): \(SdApiEngine.errorDetail(String(decoding: body, as: UTF8.self)))")
                }
                var event: String?
                var data = ""
                var done = false
                var line = Data()
                func dispatch() throws {
                    if !data.isEmpty { done = try handle(event, data, index, request, started, emit) }
                    event = nil
                    data = ""
                }
                func process(_ text: String) throws {
                    if text.isEmpty { try dispatch() }
                    else if text.hasPrefix(":") {}
                    else if text.hasPrefix("event:") { event = text.dropFirst(6).trimmingCharacters(in: .whitespaces) }
                    else if text.hasPrefix("data:") { data += text.dropFirst(5).trimmingCharacters(in: .whitespaces) }
                }
                for try await byte in bytes {
                    if byte == 0x0A {
                        if line.last == 0x0D { line.removeLast() }
                        try process(String(decoding: line, as: UTF8.self))
                        line.removeAll(keepingCapacity: true)
                        if done { break }
                    } else {
                        line.append(byte)
                    }
                }
                if !done && !line.isEmpty { try process(String(decoding: line, as: UTF8.self)) }
                if !done && !data.isEmpty { try dispatch() }
                if !done {
                    try Task.checkCancellation()
                    throw DiffusionError(message: "Stream ended before the image was complete", code: .protocol)
                }
            } catch let blocked as NetworkBlockedError {
                throw DiffusionError(message: blocked.message, code: .blocked)
            } catch let e as DiffusionError {
                throw e
            } catch {
                if Task.isCancelled || (error as? URLError)?.code == .cancelled { throw CancellationError() }
                throw DiffusionError(message: error.localizedDescription, code: .unreachable)
            }
        }
    }

    /// Returns `true` when the image is complete.
    private func handle(_ event: String?, _ data: String, _ index: Int, _ request: DiffusionRequest, _ started: Date, _ emit: @Sendable (DiffusionEvent) -> Void) throws -> Bool {
        guard let obj = try? JSONSerialization.jsonObject(with: Data(data.utf8)) as? [String: Any] else { return false }
        switch event ?? obj["type"] as? String {
        case "progress":
            emit(.progress(
                image: index,
                step: obj["step"] as? Int ?? 0,
                totalSteps: obj["total_steps"] as? Int ?? request.steps,
                preview: (obj["image"] as? String).flatMap(SdApiEngine.decodeBase64),
            ))
        case "complete":
            guard let image = (obj["image"] as? String).flatMap(SdApiEngine.decodeBase64) else {
                throw DiffusionError(message: "Completed without an image", code: .protocol)
            }
            emit(.result(
                index: index,
                image: image,
                seed: (obj["seed"] as? NSNumber)?.int64Value ?? request.seed,
                width: obj["width"] as? Int ?? request.width,
                height: obj["height"] as? Int ?? request.height,
                durationMs: (obj["generation_time_ms"] as? NSNumber)?.int64Value ?? Int64(Date().timeIntervalSince(started) * 1000),
                info: nil,
            ))
            return true
        case "error":
            throw DiffusionError(message: obj["message"] as? String ?? "Backend error")
        default:
            break
        }
        return false
    }

    static func requestBody(_ request: DiffusionRequest, _ inputs: DiffusionInputs, seed: Int64) -> [String: Any] {
        var body: [String: Any] = [
            "prompt": PromptSyntax.stripLoraTags(request.prompt),
            "negative_prompt": request.negativePrompt,
            "steps": request.steps,
            "cfg": request.cfgScale,
            "width": request.width,
            "height": request.height,
            "scheduler": schedulerId(request.sampler, request.scheduler),
            "show_diffusion_process": true,
            "show_diffusion_stride": 2,
            "preview_format": "jpeg",
            "output_format": "png",
        ]
        if seed >= 0 { body["seed"] = seed }
        if request.mode != .txt2img, let initImage = inputs.initImage {
            body["image"] = initImage.base64EncodedString()
            body["denoise_strength"] = request.denoiseStrength
        }
        if request.mode == .inpaint, let mask = inputs.mask { body["mask"] = mask.base64EncodedString() }
        return body
    }

    /// Maps A1111-style sampler/scheduler names onto protocol ids (ids pass through).
    static func schedulerId(_ sampler: String, _ scheduler: String?) -> String {
        if schedulers.contains(sampler) { return sampler }
        let s = sampler.lowercased()
        let karras = scheduler?.caseInsensitiveCompare("karras") == .orderedSame || s.contains("karras")
        let base: String
        if s.contains("lcm") { return "lcm" }
        else if s.contains("sde") { base = "dpm_sde" }
        else if s.contains("dpm") { base = "dpm" }
        else if s.contains("euler a") || s.contains("euler_a") || s.contains("ancestral") { base = "euler_a" }
        else if s.contains("euler") { base = "euler" }
        else { base = "dpm" }
        return karras ? "\(base)_karras" : base
    }

    private func endpoint(_ path: String) -> URL? {
        let base = baseUrl.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return URL(string: base + "/" + path)
    }
}

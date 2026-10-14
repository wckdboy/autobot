import Foundation

/**
 AUTOMATIC1111-compatible REST backend (`/sdapi/v1`): stable-diffusion-webui, Forge, SD.Next and
 others that implement the same API (port of `SdApiEngine.kt`).

 - LoRAs are applied at runtime via `<lora:name:weight>` prompt tags.
 - Inpainting uses `img2img` with an opaque black/white mask (`inpainting_fill = original`).
 - Progress (with the backend's live preview) is polled from `/sdapi/v1/progress`.
 - Cancelling posts `/sdapi/v1/interrupt`.

 [clientFactory] creates a policy-bound client per operation (kill switch, route, audit tag).
 */
struct SdApiEngine: DiffusionEngine {
    let baseUrl: String
    let clientFactory: @Sendable () -> HttpClient
    var progressInterval: Duration = .milliseconds(800)

    let capabilities = EngineCapabilities(
        modes: Set(DiffusionMode.allCases),
        runtimeLora: true,
        modelSwitching: true,
        livePreview: true,
        maskEncoding: .opaqueBlackWhite,
    )

    func catalog() async throws -> EngineCatalog {
        let client = clientFactory()
        func names(_ path: String, _ key: String) async -> [String] {
            ((try? await get(client, path)) as? [[String: Any]])?.compactMap { $0[key] as? String } ?? []
        }
        // Fail loudly if the backend is unreachable at all; tolerate missing optional endpoints.
        guard let models = try await get(client, "sdapi/v1/sd-models") as? [[String: Any]] else {
            throw DiffusionError(message: "Unexpected response from backend", code: .protocol)
        }
        let current = ((try? await get(client, "sdapi/v1/options")) as? [String: Any])?["sd_model_checkpoint"] as? String
        return EngineCatalog(
            models: models.compactMap { $0["title"] as? String },
            currentModel: current,
            loras: await names("sdapi/v1/loras", "name"),
            samplers: await names("sdapi/v1/samplers", "name"),
            schedulers: await names("sdapi/v1/schedulers", "label"),
        )
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

    private func run(_ request: DiffusionRequest, _ inputs: DiffusionInputs, emit: @escaping @Sendable (DiffusionEvent) -> Void) async throws {
        let img2img = request.mode != .txt2img
        if img2img && inputs.initImage == nil { throw DiffusionError(message: "\(request.mode.label) needs an input image", code: .unsupported) }
        if request.mode == .inpaint && inputs.mask == nil { throw DiffusionError(message: "Paint a mask first", code: .unsupported) }

        let client = clientFactory()
        let body = try JSONSerialization.data(withJSONObject: Self.requestBody(request, inputs))
        let started = Date()
        var finished = false
        let interval = progressInterval
        let poller = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                guard !Task.isCancelled,
                      let progress = (try? await get(client, "sdapi/v1/progress?skip_current_image=false")) as? [String: Any],
                      let state = progress["state"] as? [String: Any],
                      let step = state["sampling_step"] as? Int else { continue }
                let total = state["sampling_steps"] as? Int ?? request.steps
                let job = state["job_no"] as? Int ?? 0
                let preview = (progress["current_image"] as? String).flatMap(Self.decodeBase64)
                if step > 0 && !Task.isCancelled { emit(.progress(image: job, step: step, totalSteps: total, preview: preview)) }
            }
        }
        defer {
            poller.cancel()
            if !finished {
                // Interrupt the backend; the job keeps running there otherwise.
                let interrupt = clientFactory()
                if let url = endpoint("sdapi/v1/interrupt") {
                    Task.detached {
                        var r = URLRequest(url: url)
                        r.httpMethod = "POST"
                        r.timeoutInterval = 3
                        _ = try? await interrupt.send(r)
                    }
                }
            }
        }
        guard let response = try await post(client, img2img ? "sdapi/v1/img2img" : "sdapi/v1/txt2img", body) as? [String: Any] else {
            throw DiffusionError(message: "Unexpected response from backend", code: .protocol)
        }
        poller.cancel()
        let images = (response["images"] as? [String] ?? []).compactMap(Self.decodeBase64)
        let info = response["info"] as? String
        let infoJson = info.flatMap { try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any] }
        let seeds = (infoJson?["all_seeds"] as? [NSNumber])?.map(\.int64Value) ?? []
        // Some setups prepend a grid image when several images are returned.
        let results = !seeds.isEmpty && images.count == seeds.count + 1 ? Array(images.dropFirst()) : images
        if results.isEmpty { throw DiffusionError(message: "Backend returned no images", code: .protocol) }
        let elapsed = Int64(Date().timeIntervalSince(started) * 1000)
        for (i, png) in results.enumerated() {
            emit(.result(
                index: i,
                image: png,
                seed: i < seeds.count ? seeds[i] : (infoJson?["seed"] as? NSNumber)?.int64Value ?? request.seed,
                width: infoJson?["width"] as? Int ?? request.width,
                height: infoJson?["height"] as? Int ?? request.height,
                durationMs: elapsed,
                info: info,
            ))
        }
        finished = true
    }

    /// The `txt2img` / `img2img` JSON body.
    static func requestBody(_ request: DiffusionRequest, _ inputs: DiffusionInputs) -> [String: Any] {
        var body: [String: Any] = [
            "prompt": PromptSyntax.withLoraTags(request.prompt, request.loras),
            "negative_prompt": request.negativePrompt,
            "steps": request.steps,
            "cfg_scale": request.cfgScale,
            "width": request.width,
            "height": request.height,
            "seed": request.seed,
            "sampler_name": request.sampler,
            "batch_size": 1,
            "n_iter": max(request.batchCount, 1),
            "send_images": true,
            "save_images": false,
            "override_settings_restore_afterwards": false,
        ]
        if let scheduler = request.scheduler { body["scheduler"] = scheduler }
        var overrides: [String: Any] = ["return_grid": false]
        if let model = request.model { overrides["sd_model_checkpoint"] = model }
        if let clip = request.clipSkip { overrides["CLIP_stop_at_last_layers"] = clip }
        body["override_settings"] = overrides
        if request.mode != .txt2img, let initImage = inputs.initImage {
            body["init_images"] = [initImage.base64EncodedString()]
            body["denoising_strength"] = request.denoiseStrength
        }
        if request.mode == .inpaint, let mask = inputs.mask {
            body["mask"] = mask.base64EncodedString()
            body["mask_blur"] = request.maskBlur
            body["inpainting_fill"] = 1
            body["inpaint_full_res"] = request.inpaintOnlyMasked
            body["inpaint_full_res_padding"] = 32
            body["inpainting_mask_invert"] = 0
        }
        return body
    }

    private func endpoint(_ path: String) -> URL? {
        let base = baseUrl.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return URL(string: base + "/" + path)
    }

    private func get(_ client: HttpClient, _ path: String) async throws -> Any {
        guard let url = endpoint(path) else { throw DiffusionError(message: "Invalid backend URL", code: .unreachable) }
        return try await execute(client, URLRequest(url: url))
    }

    private func post(_ client: HttpClient, _ path: String, _ body: Data) async throws -> Any {
        guard let url = endpoint(path) else { throw DiffusionError(message: "Invalid backend URL", code: .unreachable) }
        var r = URLRequest(url: url)
        r.httpMethod = "POST"
        r.httpBody = body
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.timeoutInterval = 3600
        return try await execute(client, r)
    }

    private func execute(_ client: HttpClient, _ request: URLRequest) async throws -> Any {
        let response: HttpResponse
        do {
            response = try await client.send(request)
        } catch let blocked as NetworkBlockedError {
            throw DiffusionError(message: blocked.message, code: .blocked)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            if (error as? URLError)?.code == .cancelled { throw CancellationError() }
            throw DiffusionError(message: error.localizedDescription, code: .unreachable)
        }
        let text = String(decoding: response.body, as: UTF8.self)
        guard response.isSuccessful else { throw DiffusionError(message: "HTTP \(response.status): \(Self.errorDetail(text))", code: .backend) }
        guard let json = try? JSONSerialization.jsonObject(with: response.body, options: [.fragmentsAllowed]) else {
            throw DiffusionError(message: "Malformed JSON from backend", code: .protocol)
        }
        return json
    }

    /// The most useful line of an error body (`errors` / `error` / `detail`).
    static func errorDetail(_ body: String) -> String {
        guard let obj = try? JSONSerialization.jsonObject(with: Data(body.utf8)) as? [String: Any] else {
            let t = String(body.prefix(200))
            return t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "error" : t
        }
        for key in ["errors", "error", "detail"] {
            if let s = obj[key] as? String { return String(s.prefix(200)) }
            if let a = obj[key] as? [Any], let first = a.first {
                if let msg = (first as? [String: Any])?["msg"] as? String { return String(msg.prefix(200)) }
                return String("\(first)".prefix(200))
            }
        }
        return String(body.prefix(200))
    }

    static func decodeBase64(_ text: String) -> Data? {
        let payload = text.range(of: "base64,").map { String(text[$0.upperBound...]) } ?? text
        guard let data = Data(base64Encoded: payload, options: .ignoreUnknownCharacters), !data.isEmpty else { return nil }
        return data
    }
}

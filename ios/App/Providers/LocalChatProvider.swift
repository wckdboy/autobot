import Foundation

/**
 Chat on this phone through llama.cpp (the `AutobotEngines` package, Metal or CPU). Nothing
 touches the network. [ChatRequest.model] is an installed model id; tool calling uses the
 model's own chat template, so agent sessions work offline. Port of `LocalChatProvider.kt`.
 */
struct LocalChatProvider: ChatProvider {
    static let defaultContext = 8192
    static let maxContext = 32768

    let library: ModelLibrary
    let compute: ComputeProfile
    let engine: LocalLlm

    func streamChat(_ request: ChatRequest) -> AsyncStream<ChatEvent> {
        AsyncStream { continuation in
            let task = Task {
                await run(request) { continuation.yield($0) }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func run(_ request: ChatRequest, emit: @escaping @Sendable (ChatEvent) -> Void) async {
        let resolved: (path: URL, mmproj: URL?, context: Int, backend: Backend)? = await MainActor.run {
            guard let model = library.get(request.model), model.isReady, model.engine == .llama,
                  let path = library.paths(model)[.model] else { return nil }
            let context = min(model.manifest.recommended?.contextLength ?? Self.defaultContext, Self.maxContext)
            return (path, library.paths(model)[.mmproj], context, compute.resolve(model))
        }
        guard let resolved else {
            emit(.error(.badRequest, "Model '\(request.model)' is not installed on this phone (Models tab)"))
            return
        }
        do {
            let json = try Self.encode(request, mmproj: resolved.mmproj)
            let result = try await withTaskCancellationHandler {
                try await engine.chat(
                    modelPath: resolved.path.path,
                    mmprojPath: resolved.mmproj?.path,
                    contextLength: resolved.context,
                    backend: resolved.backend,
                    requestJSON: json,
                    onDelta: { delta in
                        switch delta {
                        case .content(let t): emit(.contentDelta(t))
                        case .reasoning(let t): emit(.reasoningDelta(t))
                        }
                    },
                )
            } onCancel: {
                engine.cancel()
            }
            for (i, call) in result.toolCalls.enumerated() {
                emit(.toolCallDelta(index: i, id: call.id, name: call.name, argumentsDelta: call.arguments))
            }
            emit(.usage(promptTokens: result.promptTokens, completionTokens: result.completionTokens, totalTokens: result.promptTokens + result.completionTokens))
            if result.finishReason != "cancelled" { emit(.finish(result.finishReason)) }
            emit(.done)
        } catch let e as LocalEngineError {
            switch e.code {
            case "CANCELLED": return
            case "CONTEXT_WINDOW_EXCEEDED": emit(.error(.badRequest, "context length exceeded: \(e.message)"))
            default: emit(.error(.badRequest, e.message))
            }
        } catch {
            emit(.error(.badRequest, error.localizedDescription))
        }
    }

    func testConnection() async -> ConnectionTestResult {
        do {
            _ = try await engine.systemInfo()
            let count = await MainActor.run { library.models.filter { $0.isReady && $0.engine == .llama }.count }
            return .success(modelCount: count)
        } catch {
            return .failure(.server, error.localizedDescription)
        }
    }

    /// The engine core's OpenAI-shaped request; images are `{type:"image", path}` parts.
    static func encode(_ request: ChatRequest, mmproj: URL?) throws -> Data {
        var root: [String: Any] = [
            "messages": request.messages.map { m -> [String: Any] in
                var o: [String: Any] = ["role": m.role.rawValue]
                if m.images.isEmpty {
                    o["content"] = m.content
                } else {
                    o["content"] = [["type": "text", "text": m.content]] + m.images.map { ["type": "image", "path": $0.path] }
                }
                if !m.toolCalls.isEmpty {
                    o["tool_calls"] = m.toolCalls.map { ["id": $0.id, "type": "function", "function": ["name": $0.name, "arguments": $0.arguments]] }
                }
                if let id = m.toolCallId { o["tool_call_id"] = id }
                return o
            },
            "max_tokens": request.maxTokens ?? 2048,
        ]
        if !request.tools.isEmpty {
            root["tools"] = request.tools.map { t -> [String: Any] in
                let schema = (try? JSONSerialization.jsonObject(with: Data(t.parametersJson.utf8))) ?? [String: Any]()
                return ["type": "function", "function": ["name": t.name, "description": t.description, "parameters": schema]]
            }
        }
        if let mmproj { root["mmproj"] = mmproj.path }
        if let t = request.temperature { root["temperature"] = t }
        return try JSONSerialization.data(withJSONObject: root)
    }
}

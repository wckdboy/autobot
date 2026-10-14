import Foundation

/**
 Streaming client for any OpenAI-compatible `/chat/completions` endpoint using Server-Sent
 Events (`stream=true`) — port of `OpenAICompatibleProvider.kt`.

 - `delta.content` → `.contentDelta`
 - `delta.reasoning_content` (DeepSeek) or `delta.reasoning` (OpenRouter, Ollama) → `.reasoningDelta`
 - `delta.tool_calls[]` → `.toolCallDelta` (fragments, keyed by `index`)
 - `finish_reason` → `.finish`
 - `usage` (requested with `stream_options.include_usage`) → `.usage`
 - `data: [DONE]` or end of stream → `.done`

 HTTP and transport failures are mapped to `.error`; kill-switch refusals become `.blocked`. Only
 `Authorization`, `Accept` and `Content-Type` headers are sent; the client replaces the
 User-Agent and strips identifying headers. DeepSeek, OpenRouter and Ollama use this class with
 their default base URLs (OpenRouter attribution headers are never sent).
 */
struct OpenAICompatibleProvider: ChatProvider {
    let baseUrl: String
    let apiKey: Secret?
    let client: HttpClient
    var includeUsage = true

    private var base: String { baseUrl.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")) }

    private func endpoint(_ path: String) -> URL? {
        guard let url = URL(string: base + "/" + path), let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https", url.host() != nil else { return nil }
        return url
    }

    private func authorized(_ request: inout URLRequest) {
        if let key = apiKey, !key.isBlank { request.setValue("Bearer \(key.reveal())", forHTTPHeaderField: "Authorization") }
    }

    func streamChat(_ request: ChatRequest) -> AsyncStream<ChatEvent> {
        AsyncStream { continuation in
            let task = Task {
                await run(request) { continuation.yield($0) }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    private func run(_ chat: ChatRequest, emit: @Sendable (ChatEvent) -> Void) async {
        guard let url = endpoint("chat/completions") else {
            emit(.error(.badRequest, "Invalid base URL"))
            return
        }
        let body: Data
        do {
            body = try Self.encodeRequest(chat, includeUsage: includeUsage)
        } catch {
            emit(.error(.badRequest, "Invalid tool schema: \(error.localizedDescription)"))
            return
        }
        var http = URLRequest(url: url)
        http.httpMethod = "POST"
        http.httpBody = body
        http.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        http.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        authorized(&http)
        do {
            let (bytes, response) = try await client.stream(http)
            guard (200 ..< 300).contains(response.statusCode) else {
                emit(Self.httpError(status: response.statusCode, body: await bytes.prefixData(16_384)))
                return
            }
            for try await line in bytes.lines {
                if Task.isCancelled { return }
                if line.isEmpty || line.hasPrefix(":") || !line.hasPrefix("data:") { continue }
                let data = line.dropFirst(5).trimmingCharacters(in: .whitespaces)
                if data == "[DONE]" { break }
                guard let chunk = try? JSONDecoder().decode(WireChunk.self, from: Data(data.utf8)) else {
                    emit(.error(.protocol, "Malformed stream chunk"))
                    return
                }
                if let error = chunk.error {
                    emit(.error(.server, error.message ?? "Provider reported an error"))
                    return
                }
                for choice in chunk.choices ?? [] {
                    if let delta = choice.delta {
                        if let reasoning = delta.reasoning_content ?? delta.reasoning, !reasoning.isEmpty { emit(.reasoningDelta(reasoning)) }
                        if let content = delta.content, !content.isEmpty { emit(.contentDelta(content)) }
                        for call in delta.tool_calls ?? [] {
                            emit(.toolCallDelta(index: call.index ?? 0, id: call.id, name: call.function?.name, argumentsDelta: call.function?.arguments ?? ""))
                        }
                    }
                    if let reason = choice.finish_reason { emit(.finish(reason)) }
                }
                if let usage = chunk.usage { emit(.usage(promptTokens: usage.prompt_tokens, completionTokens: usage.completion_tokens, totalTokens: usage.total_tokens)) }
            }
            emit(.done)
        } catch let blocked as NetworkBlockedError {
            emit(.error(.blocked, blocked.message))
        } catch {
            if !Task.isCancelled { emit(.error(.network, (error as? URLError)?.localizedDescription ?? error.localizedDescription)) }
        }
    }

    func testConnection() async -> ConnectionTestResult {
        guard let url = endpoint("models") else { return .failure(.badRequest, "Invalid base URL") }
        var request = URLRequest(url: url)
        authorized(&request)
        do {
            let response = try await client.send(request)
            guard response.isSuccessful else {
                if case .error(let kind, let message, _) = Self.httpError(status: response.status, body: response.body) { return .failure(kind, message) }
                return .failure(.http, "HTTP \(response.status)")
            }
            let count = (try? JSONSerialization.jsonObject(with: response.body) as? [String: Any])?["data"].flatMap { $0 as? [Any] }?.count
            return .success(modelCount: count)
        } catch let blocked as NetworkBlockedError {
            return .failure(.blocked, blocked.message)
        } catch {
            return .failure(.network, error.localizedDescription)
        }
    }

    // MARK: Wire format

    /// Serializes [request] to the OpenAI wire format. Throws on a tool schema that is not a JSON object.
    static func encodeRequest(_ request: ChatRequest, includeUsage: Bool) throws -> Data {
        var root: [String: Any] = [
            "model": request.model,
            "messages": request.messages.map { wireMessage($0, echoReasoning: request.echoReasoning) },
            "stream": true,
        ]
        if includeUsage { root["stream_options"] = ["include_usage": true] }
        if let t = request.temperature { root["temperature"] = t }
        if let m = request.maxTokens { root["max_tokens"] = m }
        if !request.tools.isEmpty {
            root["tools"] = try request.tools.map { spec -> [String: Any] in
                guard let schema = try? JSONSerialization.jsonObject(with: Data(spec.parametersJson.utf8)) as? [String: Any] else {
                    throw StoreError(message: "\(spec.name): parameters must be a JSON object")
                }
                return ["type": "function", "function": ["name": spec.name, "description": spec.description, "parameters": schema]]
            }
        }
        return try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
    }

    private static func wireMessage(_ m: ChatMessage, echoReasoning: Bool) -> [String: Any] {
        var out: [String: Any] = ["role": m.role.rawValue]
        if m.role == .assistant && !m.toolCalls.isEmpty && m.content.isEmpty {
            // No content key: tool-call-only assistant message.
        } else if !m.images.isEmpty {
            var parts: [[String: Any]] = []
            if !m.content.isEmpty { parts.append(["type": "text", "text": m.content]) }
            for url in m.images {
                if let data = dataUrl(url) { parts.append(["type": "image_url", "image_url": ["url": data]]) }
            }
            out["content"] = parts
        } else {
            out["content"] = m.content
        }
        if !m.toolCalls.isEmpty {
            out["tool_calls"] = m.toolCalls.map { ["id": $0.id, "type": "function", "function": ["name": $0.name, "arguments": $0.arguments]] }
        }
        if let id = m.toolCallId { out["tool_call_id"] = id }
        if echoReasoning && m.role == .assistant, let reasoning = m.reasoning { out["reasoning_content"] = reasoning }
        return out
    }

    /// An attached image as a `data:` URL (cloud vision models take images inline).
    private static func dataUrl(_ url: URL) -> String? {
        guard let bytes = try? Data(contentsOf: url) else { return nil }
        let b = [UInt8](bytes.prefix(4))
        let mime = if b.count > 3 && b[0] == 0x89 && b[1] == 0x50 {
            "image/png"
        } else if b.count > 3 && b[0] == 0x52 && b[1] == 0x49 {
            "image/webp"
        } else {
            "image/jpeg"
        }
        return "data:\(mime);base64,\(bytes.base64EncodedString())"
    }

    static func httpError(status: Int, body: Data) -> ChatEvent {
        let envelope = try? JSONDecoder().decode(WireErrorEnvelope.self, from: body)
        let detail = (envelope?.error?.message ?? envelope?.message).map { String($0.prefix(300)) }.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        let kind: ErrorKind = switch status {
        case 400, 404, 422: .badRequest
        case 401, 403: .unauthorized
        case 402: .quota
        case 429: .rateLimited
        case 500 ..< 600: .server
        default: .http
        }
        let fallback = switch kind {
        case .unauthorized: "Authentication failed — check the API key"
        case .quota: "Insufficient balance or quota"
        case .rateLimited: "Rate limited — try again later"
        case .server: "Provider server error"
        case .badRequest: "Request rejected by provider"
        default: "HTTP error"
        }
        return .error(kind, "HTTP \(status): \(detail ?? fallback)", httpCode: status)
    }
}

private struct WireFunctionDelta: Decodable { let name: String?; let arguments: String? }
private struct WireToolCallDelta: Decodable { let index: Int?; let id: String?; let function: WireFunctionDelta? }
private struct WireDelta: Decodable {
    let content: String?
    let reasoning_content: String?
    let reasoning: String?
    let tool_calls: [WireToolCallDelta]?
}
private struct WireChoice: Decodable { let delta: WireDelta?; let finish_reason: String? }
private struct WireUsage: Decodable { let prompt_tokens: Int?; let completion_tokens: Int?; let total_tokens: Int? }
private struct WireError: Decodable { let message: String? }
private struct WireChunk: Decodable { let choices: [WireChoice]?; let usage: WireUsage?; let error: WireError? }
private struct WireErrorEnvelope: Decodable { let error: WireError?; let message: String? }

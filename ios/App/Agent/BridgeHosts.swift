#if canImport(AutobotShared)
import AutobotShared
import Foundation
import Synchronization

// Host sides of the KMP bridge interfaces (`shared/.../{AgentBridge,Transport,LocalEngine}.kt`).
// Kotlin calls these on its own background threads, so everything here is thread-safe and never
// touches main-actor state directly: providers and models are read from snapshots the app keeps
// current ([HostSnapshots]).

/// Thread-safe copies of what the bridge reads from background threads.
final class HostSnapshots: Sendable {
    struct LocalModel: Sendable {
        let path: String
        let mmproj: String?
        let contextLength: Int
        let backend: Backend
    }

    private let providers = Mutex<[String: Provider]>([:])
    private let modelLists = Mutex<[String: String]>([:])
    private let models = Mutex<[String: LocalModel]>([:])

    func setProviders(_ list: [Provider]) { providers.withLock { $0 = Dictionary(list.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a }) } }
    func provider(_ id: String) -> Provider? { providers.withLock { $0[id] } }

    /// Cached model lists (`ModelCache` JSON) by provider id; their context windows feed the agent.
    func setModelLists(_ map: [String: String]) { modelLists.withLock { $0 = map } }
    func modelList(_ id: String) -> String? { modelLists.withLock { $0[id] } }

    func setModels(_ map: [String: LocalModel]) { models.withLock { $0 = map } }
    func model(_ id: String) -> LocalModel? { models.withLock { $0[id] } }
    var readyModelCount: Int { models.withLock { $0.count } }
}

/// A Kotlin-facing handle that cancels a Swift task.
final class TaskHandle: NSObject, AutobotShared.Cancellable, @unchecked Sendable {
    private let task: Mutex<Task<Void, Never>?>

    init(_ task: Task<Void, Never>?) { self.task = Mutex(task) }

    func cancel() { task.withLock { $0?.cancel() } }
}

/// The host's provider list; API keys are read from the Keychain on demand.
final class ProviderDirectoryHost: NSObject, ProviderDirectory, @unchecked Sendable {
    private let snapshots: HostSnapshots
    private let secrets: SecretStore

    init(snapshots: HostSnapshots, secrets: SecretStore) {
        self.snapshots = snapshots
        self.secrets = secrets
    }

    func provider(id: String) -> ProviderSpec? {
        guard let p = snapshots.provider(id) else { return nil }
        return ProviderSpec(
            id: p.id,
            kind: SharedMapping.kind(p.kind),
            displayName: p.displayName,
            baseUrl: p.baseUrl,
            apiKey: p.apiKeyRef.flatMap(secrets.get)?.reveal(),
            defaultModel: p.defaultModel,
            route: p.routing.rawValue,
            presetId: p.presetId,
            modelsJson: snapshots.modelList(p.id),
        )
    }
}

/**
 `HttpTransport` over the app's policy-bound URLSession client: kill switch, route resolution
 (the request's provider override), header scrub, audit log (with the request's tag), no
 redirects. Bytes only — the OpenAI encoding and SSE decoding happen in Kotlin.
 */
final class URLSessionTransport: NSObject, HttpTransport, @unchecked Sendable {
    private static let errorBodyLimit = 16 * 1024
    private let clients: HttpClientFactory

    init(clients: HttpClientFactory) { self.clients = clients }

    func send(request: HttpRequestSpec, sink: HttpSink) -> any AutobotShared.Cancellable {
        let override = RouteOverride(rawValue: request.route) ?? .inherit
        let client = clients.create(tag: request.tag, override: override)
        let spec = (method: request.method, url: request.url, headers: request.headers, body: request.body, tag: request.tag, maxBytes: request.maxBytes)
        nonisolated(unsafe) let sink = sink
        let task = Task.detached {
            guard let url = URL(string: spec.url) else {
                sink.onFailure(blocked: false, message: "Invalid URL")
                return
            }
            var http = URLRequest(url: url)
            http.httpMethod = spec.method
            spec.headers.forEach { http.setValue($0.value, forHTTPHeaderField: $0.key) }
            http.httpBody = spec.body.map { Data($0.utf8) }
            do {
                let (bytes, response) = try await client.stream(http, tag: spec.tag)
                var headers: [String: String] = [:]
                response.allHeaderFields.forEach { headers["\($0.key)".lowercased()] = "\($0.value)" }
                let status = response.statusCode
                guard (200 ..< 300).contains(status) else {
                    let body = await bytes.prefixData(Self.errorBodyLimit)
                    sink.onResponse(status: Int32(status), headers: headers, errorBody: String(decoding: body, as: UTF8.self))
                    return
                }
                sink.onResponse(status: Int32(status), headers: headers, errorBody: nil)
                var line = Data()
                var read: Int64 = 0
                for try await byte in bytes {
                    read += 1
                    if read > spec.maxBytes { break }
                    if byte == 0x0A {
                        if line.last == 0x0D { line.removeLast() }
                        sink.onLine(line: String(decoding: line, as: UTF8.self))
                        line.removeAll(keepingCapacity: true)
                    } else {
                        line.append(byte)
                    }
                }
                if Task.isCancelled { return }
                if !line.isEmpty { sink.onLine(line: String(decoding: line, as: UTF8.self)) }
                sink.onComplete()
            } catch let blocked as NetworkBlockedError {
                sink.onFailure(blocked: true, message: blocked.message)
            } catch {
                if !Task.isCancelled { sink.onFailure(blocked: false, message: error.localizedDescription) }
            }
        }
        return TaskHandle(task)
    }
}

/// `LocalEngine` over `AutobotEngines.LlamaEngine` (Metal / CPU), via [LocalLlm].
final class LocalEngineHost: NSObject, LocalEngine, @unchecked Sendable {
    private let snapshots: HostSnapshots
    private let llm: LocalLlm

    init(snapshots: HostSnapshots, llm: LocalLlm) {
        self.snapshots = snapshots
        self.llm = llm
    }

    func chat(modelId: String, requestJson: String, sink: LocalSink) -> (any AutobotShared.Cancellable)? {
        guard let model = snapshots.model(modelId) else { return nil }
        let llm = llm
        nonisolated(unsafe) let sink = sink
        let task = Task.detached {
            do {
                let result = try await withTaskCancellationHandler {
                    try await llm.chatJSON(
                        modelPath: model.path,
                        mmprojPath: model.mmproj,
                        contextLength: model.contextLength,
                        backend: model.backend,
                        requestJSON: Data(requestJson.utf8),
                        onDelta: { delta in
                            switch delta {
                            case .content(let t): sink.onContent(text: t)
                            case .reasoning(let t): sink.onReasoning(text: t)
                            }
                        },
                    )
                } onCancel: {
                    llm.cancel()
                }
                sink.onResult(json: result)
            } catch let e as LocalEngineError {
                // The shared codec turns `{error:{code,message}}` into events (CANCELLED → none).
                let body = ["error": ["code": e.code, "message": e.message]]
                let json = (try? JSONSerialization.data(withJSONObject: body)).map { String(decoding: $0, as: UTF8.self) }
                sink.onResult(json: json ?? #"{"error":{"code":"ERROR","message":"engine error"}}"#)
            } catch {
                sink.onFailure(message: error.localizedDescription)
            }
        }
        return TaskHandle(task)
    }

    func mmproj(modelId: String) -> String? { snapshots.model(modelId)?.mmproj }

    func readyModelCount() -> Int32 { Int32(snapshots.readyModelCount) }
}

/// Session logs as opaque JSON lines, sealed in the encrypted store (incognito: memory only).
final class SessionStoreHost: NSObject, SessionStoreBridge, @unchecked Sendable {
    private let store: SessionStore

    init(store: SessionStore) { self.store = store }

    func load(sessionId: String) -> [String] {
        store.loadJSON(sessionId).map { String(decoding: $0, as: UTF8.self) }
    }

    func append(sessionId: String, entries: [String]) {
        let lines = entries.compactMap { line -> (Int64, Data)? in
            let data = Data(line.utf8)
            guard let seq = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["seq"] as? NSNumber else { return nil }
            return (seq.int64Value, data)
        }
        try? store.appendJSON(sessionId, lines.map { (seq: $0.0, json: $0.1) })
    }
}

/// Decrypted attachments for one model call; the plain copies are deleted afterwards.
final class ImageStoreHost: NSObject, ImageStoreBridge, @unchecked Sendable {
    private let attachments: AttachmentRepository

    init(attachments: AttachmentRepository) { self.attachments = attachments }

    func openPlain(id: String) -> String? { attachments.openPlain(id)?.path }

    func discard(path: String) {
        guard path.hasPrefix(AppPaths.plainCache.path) else { return }
        try? FileManager.default.removeItem(atPath: path)
    }
}

/// Swift ↔ Kotlin enum mapping.
enum SharedMapping {
    static func kind(_ kind: ProviderKind) -> AutobotShared.ProviderKind {
        switch kind {
        case .deepseek: .deepseek
        case .openAICompatible: .openaiCompatible
        case .openRouter: .openrouter
        case .ollama: .ollama
        case .local: .local
        case .anthropic: .anthropic
        }
    }

    static func outcome(_ outcome: ApprovalOutcome) -> AutobotShared.ApprovalOutcome {
        switch outcome {
        case .allowedOnce: .allowedOnce
        case .allowedForSession: .allowedForSession
        case .rejected: .rejected
        case .cancelled: .cancelled
        case .unavailable: .unavailable
        }
    }

    static func preset(_ preset: PermissionPreset) -> AutobotShared.PermissionPreset {
        switch preset {
        case .readOnly: .readOnly
        case .workspace: .workspace
        case .fullAccess: .fullAccess
        }
    }
}
#endif

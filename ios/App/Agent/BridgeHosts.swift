#if canImport(AutobotShared)
import AutobotShared
import Foundation
import Synchronization
import UIKit

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
        /// Too big for this device by the shared fit rules (the system model may stand in).
        var tooBig = false
    }

    private let providers = Mutex<[String: Provider]>([:])
    private let modelLists = Mutex<[String: String]>([:])
    private let models = Mutex<[String: LocalModel]>([:])
    private let mcpTokenRefs = Mutex<[String: String]>([:])

    func setProviders(_ list: [Provider]) { providers.withLock { $0 = Dictionary(list.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a }) } }
    func provider(_ id: String) -> Provider? { providers.withLock { $0[id] } }

    /// Cached model lists (`ModelCache` JSON) by provider id; their context windows feed the agent.
    func setModelLists(_ map: [String: String]) { modelLists.withLock { $0 = map } }
    func modelList(_ id: String) -> String? { modelLists.withLock { $0[id] } }

    func setModels(_ map: [String: LocalModel]) { models.withLock { $0 = map } }
    func model(_ id: String) -> LocalModel? { models.withLock { $0[id] } }
    var readyModelCount: Int { models.withLock { $0.count } }

    func setMcpServers(_ list: [McpServer]) {
        mcpTokenRefs.withLock { $0 = Dictionary(list.compactMap { s in s.tokenRef.map { (s.id, $0) } }, uniquingKeysWith: { a, _ in a }) }
    }
    func mcpTokenRef(_ serverId: String) -> String? { mcpTokenRefs.withLock { $0[serverId] } }
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
            helperModel: p.helperModel,
        )
    }
}

/// MCP bearer tokens, read from the Keychain only when the shared hub connects to that server.
final class McpTokenSourceHost: NSObject, McpTokenSource, @unchecked Sendable {
    private let snapshots: HostSnapshots
    private let secrets: SecretStore

    init(snapshots: HostSnapshots, secrets: SecretStore) {
        self.snapshots = snapshots
        self.secrets = secrets
    }

    func token(serverId: String) -> String? { snapshots.mcpTokenRef(serverId).flatMap(secrets.get)?.reveal() }
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
        let epoch = AppActivity.shared.epoch
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
                // A reply the GPU stopped because the app went to the background ends with a notice,
                // not as if the model had finished.
                let stopped = AppActivity.shared.interrupted(since: epoch) && Self.finishReason(result) == "error"
                sink.onResult(json: stopped ? Self.errorJSON(Self.backgrounded, Self.backgroundedMessage) : result)
            } catch let e as LocalEngineError {
                // The shared codec turns `{error:{code,message}}` into events (CANCELLED → none).
                let stopped = e.code != "CANCELLED" && AppActivity.shared.interrupted(since: epoch)
                sink.onResult(json: stopped ? Self.errorJSON(Self.backgrounded, Self.backgroundedMessage) : Self.errorJSON(e.code, e.message))
            } catch {
                if AppActivity.shared.interrupted(since: epoch) {
                    sink.onResult(json: Self.errorJSON(Self.backgrounded, Self.backgroundedMessage))
                } else {
                    sink.onFailure(message: error.localizedDescription)
                }
            }
        }
        return TaskHandle(task)
    }

    /// Error code of an on-device reply cut short because the app left the foreground.
    static let backgrounded = "BACKGROUNDED"
    static let backgroundedMessage = "Stopped: iOS does not let the on-device model use the GPU while Autobot is in the background, "
        + "so this reply is incomplete. Keep Autobot open while it answers, then send again."

    /// `{error:{code,message}}` as the shared codec reads it.
    static func errorJSON(_ code: String, _ message: String) -> String {
        let body = ["error": ["code": code, "message": message]]
        let json = (try? JSONSerialization.data(withJSONObject: body)).map { String(decoding: $0, as: UTF8.self) }
        return json ?? #"{"error":{"code":"ERROR","message":"engine error"}}"#
    }

    /// `finish_reason` of an engine result.
    static func finishReason(_ json: String) -> String? {
        (try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])?["finish_reason"] as? String
    }

    func mmproj(modelId: String) -> String? { snapshots.model(modelId)?.mmproj }

    func readyModelCount() -> Int32 { Int32(snapshots.readyModelCount) }

    func modelState(modelId: String) -> LocalModelState {
        guard let model = snapshots.model(modelId) else { return .notInstalled }
        return model.tooBig ? .tooBig : .ready
    }

    func systemModel() -> SystemModelStatus? { AppleIntelligence.sharedStatus() }

    func systemChat(promptJson: String, sink: LocalSink) -> (any AutobotShared.Cancellable)? {
        guard let plan = try? JSONDecoder().decode(SystemPromptPlan.self, from: Data(promptJson.utf8)) else { return nil }
        #if canImport(FoundationModels)
        guard #available(iOS 26.0, *) else { return nil }
        nonisolated(unsafe) let sink = sink
        let task = Task.detached {
            let result = await AppleIntelligence.respond(plan) { sink.onContent(text: $0) }
            if result.contains("\"LOAD_FAILED\"") || result.contains("\"GUARDRAIL\"") { EngineErrors.record("system model: \(result)") }
            sink.onResult(json: result)
        }
        return TaskHandle(task)
        #else
        return nil
        #endif
    }
}

extension AppleIntelligence {
    /// The shared status the router and the Compute screen use.
    static func sharedStatus() -> SystemModelStatus {
        let reason = unavailability
        return SystemModelStatus(
            id: id,
            title: title,
            availability: reason == nil ? .available : reason == .notReady ? .downloading : .unavailable,
            detail: reason?.detail,
        )
    }
}

/**
 Session logs as opaque JSON lines, sealed in the encrypted store (incognito: memory only).
 While the device is locked the store cannot be written (`NSFileProtectionComplete`): appends
 wait in memory, in order, and are written before any newer one, at the latest when protected
 data becomes available again; until then [load] still returns them.
 */
final class SessionStoreHost: NSObject, SessionStoreBridge, @unchecked Sendable {
    struct Batch: Sendable {
        let sessionId: String
        let lines: [(seq: Int64, json: Data)]
    }

    private let store: SessionStore
    let unsaved: PendingWrites<Batch>
    private var observer: NSObjectProtocol?

    init(store: SessionStore) {
        self.store = store
        unsaved = PendingWrites { [store] batch in try store.appendJSON(batch.sessionId, batch.lines) }
        super.init()
        observer = NotificationCenter.default.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: nil) { [weak self] _ in
            guard let host = self else { return }
            DispatchQueue.global(qos: .utility).async { host.retry() }
        }
    }

    deinit { observer.map(NotificationCenter.default.removeObserver) }

    func load(sessionId: String) -> [String] {
        let waiting = unsaved.pending.filter { $0.sessionId == sessionId }.flatMap { $0.lines.map(\.json) }
        return (store.loadJSON(sessionId) + waiting).map { String(decoding: $0, as: UTF8.self) }
    }

    func append(sessionId: String, entries: [String]) {
        let lines = entries.compactMap { line -> (seq: Int64, json: Data)? in
            let data = Data(line.utf8)
            guard let seq = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["seq"] as? NSNumber else { return nil }
            return (seq.int64Value, data)
        }
        guard !lines.isEmpty else { return }
        unsaved.submit(Batch(sessionId: sessionId, lines: lines))
    }

    /// Writes what waited for the device to unlock; a failure now (disk full) is reported.
    func retry() {
        guard let error = unsaved.flush() else { return }
        let count = unsaved.pending.reduce(0) { $0 + $1.lines.count }
        EngineErrors.record("Session log not saved (\(error.localizedDescription)); \(count) entries kept in memory")
    }

    /// Drops what waits for a deleted session.
    func forget(sessionId: String) { unsaved.discard { $0.sessionId == sessionId } }
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

/**
 The current web search settings for `web_search`, read by Kotlin per call on a background thread.
 The Brave key is read from the Keychain only then, never cached here.
 */
final class WebSearchSourceHost: NSObject, WebSearchSource, @unchecked Sendable {
    private struct Snapshot: Sendable {
        var provider = "NONE"
        var baseUrl = ""
        var route = "INHERIT"
    }

    private let secrets: SecretStore
    private let snapshot = Mutex(Snapshot())

    init(secrets: SecretStore) { self.secrets = secrets }

    func update(_ settings: AppSettings) {
        snapshot.withLock { $0 = Snapshot(provider: settings.searchProvider, baseUrl: settings.searchBaseUrl, route: settings.searchRoute.rawValue) }
    }

    func config() -> WebSearchConfig? {
        let s = snapshot.withLock { $0 }
        let provider = SettingsSchema.shared.searchProvider(value: s.provider)
        guard provider.name != "NONE" else { return nil }
        let key = provider.name == "BRAVE" ? secrets.get(AutobotShared.AppSettings.companion.SEARCH_API_KEY_REF)?.reveal() : nil
        return WebSearchConfig(provider: provider, baseUrl: s.baseUrl, apiKey: key, route: s.route)
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

    static func mcpSpec(_ server: McpServer) -> McpServerSpec {
        McpServerSpec(
            id: server.id, name: server.name, url: server.url, route: server.routing.rawValue,
            enabled: server.enabled, trusted: server.trusted, revision: server.updatedAt,
        )
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

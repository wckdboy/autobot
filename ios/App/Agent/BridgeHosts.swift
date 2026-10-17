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
        /// The draft model for speculative decoding (installed, fits next to it, the setting allows it).
        var draft: Draft? = nil
    }

    /// A draft model as the engine request carries it (`DraftChoice`).
    struct Draft: Sendable, Equatable {
        let modelId: String
        let title: String
        let path: String
        /// "cpu" or "gpu" (`SpeculativeDrafts.draftBackend`).
        let backend: String
        /// `SpeculativeMode` id.
        let mode: String
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
    fileprivate let clients: HttpClientFactory

    init(clients: HttpClientFactory) { self.clients = clients }

    func send(request: HttpRequestSpec, sink: HttpSink) -> any AutobotShared.Cancellable {
        let override = RouteOverride(rawValue: request.route) ?? .inherit
        let client = clients.create(tag: request.tag, override: override)
        let trace = request.trace.map { AuditTrace(session: $0.sessionId, turn: Int($0.turn)) }
        let spec = (method: request.method, url: request.url, headers: request.headers, body: request.body, tag: request.tag, maxBytes: request.maxBytes, trace: trace)
        let audit = clients.auditLog
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
                let (chunks, response, entry) = try await client.streamChunksAudited(http, tag: spec.tag, trace: spec.trace)
                // The body bytes read go into the audit entry (privacy receipts) before the
                // caller hears the end of the stream, and on every early exit.
                var read: Int64 = 0
                defer { audit.received(id: entry, bytes: read) }
                var headers: [String: String] = [:]
                response.allHeaderFields.forEach { headers["\($0.key)".lowercased()] = "\($0.value)" }
                let status = response.statusCode
                guard (200 ..< 300).contains(status) else {
                    var body = Data()
                    for try await chunk in chunks {
                        body.append(chunk.prefix(Self.errorBodyLimit - body.count))
                        if body.count >= Self.errorBodyLimit { break }
                    }
                    read = Int64(body.count)
                    audit.received(id: entry, bytes: read)
                    sink.onResponse(status: Int32(status), headers: headers, errorBody: String(decoding: body, as: UTF8.self))
                    return
                }
                sink.onResponse(status: Int32(status), headers: headers, errorBody: nil)
                // Whole chunks, split at newlines (memchr), empty lines kept (SSE event ends).
                var line = Data()
                for try await chunk in chunks {
                    let room = Int(max(spec.maxBytes - read, 0))
                    let data = chunk.count > room ? chunk.prefix(room) : chunk
                    read += Int64(data.count)
                    var start = data.startIndex
                    while let newline = data[start...].firstIndex(of: 0x0A) {
                        line.append(data[start ..< newline])
                        if line.last == 0x0D { line.removeLast() }
                        sink.onLine(line: String(decoding: line, as: UTF8.self))
                        line.removeAll(keepingCapacity: true)
                        start = data.index(after: newline)
                    }
                    line.append(data[start...])
                    if chunk.count > room { break }
                }
                if Task.isCancelled { return }
                if !line.isEmpty { sink.onLine(line: String(decoding: line, as: UTF8.self)) }
                audit.received(id: entry, bytes: read)
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

/**
 The binary and WebSocket siblings of [URLSessionTransport] (Studio media backends such as
 ComfyUI), through the same policy-bound client: kill switch (`ws` is cleartext, `wss` TLS),
 route, header scrub, audit log with the bytes received, no redirects.
 */
extension URLSessionTransport: BinaryHttpTransport, WebSocketTransport {
    private static let binaryErrorLimit = 16 * 1024

    func exchange(request: HttpRequestSpec, body: KotlinByteArray?, sink: BinarySink) -> any AutobotShared.Cancellable {
        let override = RouteOverride(rawValue: request.route) ?? .inherit
        let client = clients.create(tag: request.tag, override: override)
        let trace = request.trace.map { AuditTrace(session: $0.sessionId, turn: Int($0.turn)) }
        let payload = body.map { BackupBytes.shared.toData(bytes: $0) as Data } ?? request.body.map { Data($0.utf8) }
        let spec = (method: request.method, url: request.url, headers: request.headers, tag: request.tag, maxBytes: request.maxBytes)
        nonisolated(unsafe) let sink = sink
        let task = Task.detached {
            guard let url = URL(string: spec.url) else {
                sink.onFailure(blocked: false, message: "Invalid URL")
                return
            }
            var http = URLRequest(url: url)
            http.httpMethod = spec.method
            spec.headers.forEach { http.setValue($0.value, forHTTPHeaderField: $0.key) }
            http.httpBody = payload
            do {
                let response = try await client.fetch(http, tag: spec.tag, trace: trace)
                if Task.isCancelled { return }
                var headers: [String: String] = [:]
                response.headers.forEach { headers[$0.key.lowercased()] = $0.value }
                if response.isSuccessful && Int64(response.body.count) > spec.maxBytes {
                    sink.onFailure(blocked: false, message: "Response larger than \(spec.maxBytes) bytes")
                    return
                }
                let data = response.isSuccessful ? response.body : response.body.prefix(Self.binaryErrorLimit)
                sink.onResponse(status: Int32(response.status), headers: headers, body: BackupBytes.shared.fromData(data: data))
            } catch let blocked as NetworkBlockedError {
                sink.onFailure(blocked: true, message: blocked.message)
            } catch {
                if !Task.isCancelled { sink.onFailure(blocked: false, message: error.localizedDescription) }
            }
        }
        return TaskHandle(task)
    }

    func connect(spec: WebSocketSpec, listener: WebSocketListener) -> any WebSocketConnection {
        let override = RouteOverride(rawValue: spec.route) ?? .inherit
        let client = clients.create(tag: spec.tag, override: override)
        let trace = spec.trace.map { AuditTrace(session: $0.sessionId, turn: Int($0.turn)) }
        guard let url = URL(string: spec.url) else {
            listener.onFailure(blocked: false, message: "Invalid URL")
            return SocketConnection.closed
        }
        var request = URLRequest(url: url)
        spec.headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
        do {
            let (task, entry) = try client.webSocket(request, tag: spec.tag, trace: trace)
            task.maximumMessageSize = Int(min(spec.maxMessageBytes, Int64(Int.max)))
            let connection = SocketConnection(task: task, listener: listener, audit: clients.auditLog, entry: entry)
            connection.start()
            return connection
        } catch let blocked as NetworkBlockedError {
            listener.onFailure(blocked: true, message: blocked.message)
        } catch {
            listener.onFailure(blocked: false, message: error.localizedDescription)
        }
        return SocketConnection.closed
    }
}

/// One URLSession WebSocket for the shared code: callbacks serialised under a lock, the open
/// event delivered once (before the first message), and the bytes received audited at the end.
final class SocketConnection: NSObject, WebSocketConnection, URLSessionWebSocketDelegate, @unchecked Sendable {
    static let closed = SocketConnection(task: nil, listener: nil, audit: nil, entry: 0)

    private struct State {
        var opened = false
        var finished = false
        var received: Int64 = 0
    }

    private let task: URLSessionWebSocketTask?
    nonisolated(unsafe) private let listener: WebSocketListener?
    private let audit: AuditLog?
    private let entry: Int64
    private let state = Mutex(State())
    private let reader = Mutex<Task<Void, Never>?>(nil)

    init(task: URLSessionWebSocketTask?, listener: WebSocketListener?, audit: AuditLog?, entry: Int64) {
        self.task = task
        self.listener = listener
        self.audit = audit
        self.entry = entry
    }

    func start() {
        guard let task else { return }
        task.delegate = self
        task.resume()
        // The loop holds the connection until the socket ends, so the listener always hears how it
        // ended even when nobody else keeps a reference (a failed connect must never hang its caller).
        let loop = Task.detached {
            while !Task.isCancelled {
                do {
                    let message = try await task.receive()
                    self.deliver(message)
                } catch {
                    self.ended(error)
                    return
                }
            }
        }
        reader.withLock { $0 = loop }
    }

    private func emit(_ block: (WebSocketListener) -> Void) {
        state.withLock { s in
            guard !s.finished, let listener else { return }
            if !s.opened {
                s.opened = true
                listener.onOpen()
            }
            block(listener)
        }
    }

    private func deliver(_ message: URLSessionWebSocketTask.Message) {
        switch message {
        case .string(let text):
            state.withLock { $0.received += Int64(text.utf8.count) }
            emit { $0.onText(text: text) }
        case .data(let data):
            state.withLock { $0.received += Int64(data.count) }
            emit { $0.onBinary(bytes: BackupBytes.shared.fromData(data: data)) }
        @unknown default:
            break
        }
    }

    private func ended(_ error: Error) {
        let code = task?.closeCode ?? .invalid
        let reason = task?.closeReason.map { String(decoding: $0, as: UTF8.self) } ?? ""
        finish { listener in
            if code != .invalid {
                listener.onClosed(code: Int32(code.rawValue), reason: reason)
            } else {
                listener.onFailure(blocked: false, message: error.localizedDescription)
            }
        }
    }

    /// Ends the socket once: reports the bytes and tells the listener (unless cancelled).
    private func finish(_ tell: ((WebSocketListener) -> Void)?) {
        let received: Int64? = state.withLock { s in
            guard !s.finished else { return nil }
            s.finished = true
            if let tell, let listener { tell(listener) }
            return s.received
        }
        if let received, task != nil { audit?.received(id: entry, bytes: received) }
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        state.withLock { s in
            guard !s.finished, !s.opened, let listener else { return }
            s.opened = true
            listener.onOpen()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        ended(error ?? URLError(.networkConnectionLost))
    }

    func sendText(text: String) -> Bool {
        guard let task, state.withLock({ !$0.finished }) else { return false }
        task.send(.string(text)) { _ in }
        return true
    }

    func close(code: Int32, reason: String) {
        task?.cancel(with: URLSessionWebSocketTask.CloseCode(rawValue: Int(code)) ?? .normalClosure, reason: Data(reason.utf8))
    }

    func cancel() {
        finish(nil)
        reader.withLock { $0?.cancel() }
        task?.cancel()
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
                // Cancelling the task stops this request only (the engine's per-request token),
                // even while it waits behind another session's or for the model to load.
                let result = try await llm.chatJSON(
                    modelPath: model.path,
                    mmprojPath: model.mmproj,
                    contextLength: model.contextLength,
                    // A recipe running in the background: iOS refuses GPU work there.
                    backend: RecipeBackground.backend(model.backend),
                    requestJSON: Data(requestJson.utf8),
                    onDelta: { delta in
                        switch delta {
                        case .content(let t): sink.onContent(text: t)
                        case .reasoning(let t): sink.onReasoning(text: t)
                        case .reasoningStatus(let json): sink.onReasoningStatus(json: json)
                        }
                    },
                )
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

    func draft(modelId: String) -> DraftChoice? {
        guard let d = snapshots.model(modelId)?.draft else { return nil }
        return DraftChoice(modelId: d.modelId, title: d.title, path: d.path, backend: d.backend, mode: SpeculativeMode.companion.of(value: d.mode))
    }

    func readyModelCount() -> Int32 { Int32(snapshots.readyModelCount) }

    /// The model file's chat template (header only, no load): the bridge reads it once per model to
    /// know whether the Think chip shows.
    func chatTemplate(modelId: String) -> String? {
        guard let model = snapshots.model(modelId) else { return nil }
        return LocalLlm.chatTemplate(path: model.path)
    }

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
            guard let seq = Self.seq(data) else { return nil }
            return (seq, data)
        }
        guard !lines.isEmpty else { return }
        unsaved.submit(Batch(sessionId: sessionId, lines: lines))
    }

    /**
     The entry's `seq`. Kotlin writes it first (`{"seq":12,"time":…`), so it is read from the
     line's head instead of parsing a line that can hold a megabyte of tool output; any other
     layout falls back to a full parse.
     */
    static func seq(_ line: Data) -> Int64? {
        let head = Array(#"{"seq":"#.utf8)
        if line.count > head.count, line.prefix(head.count).elementsEqual(head) {
            var value: Int64 = 0
            var digits = 0
            var negative = false
            for byte in line.dropFirst(head.count) {
                if byte == UInt8(ascii: "-") && digits == 0 && !negative {
                    negative = true
                } else if byte >= 0x30 && byte <= 0x39, digits < 18 {
                    value = value * 10 + Int64(byte - 0x30)
                    digits += 1
                } else {
                    break
                }
            }
            if digits > 0 { return negative ? -value : value }
        }
        return ((try? JSONSerialization.jsonObject(with: line)) as? [String: Any])?["seq"].flatMap { ($0 as? NSNumber)?.int64Value }
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

#if canImport(AutobotShared)
import AutobotShared
import Foundation
import Observation
import Synchronization

/**
 The agent runtime Android runs (`AgentHost`, the Kotlin port of DeepSeek Harness: tools,
 permission gate, approvals, plans, questions, retries, compaction, skills, `/workspace`), from
 `AutobotShared.xcframework`. Kotlin owns the loop and the transcript projection; Swift supplies
 HTTP ([URLSessionTransport], behind the kill switch), the on-device engine ([LocalEngineHost]),
 encrypted session logs ([SessionStoreHost]), attachments and the provider list, and renders the
 `SessionState` JSON the bridge streams back.
 */
@MainActor
@Observable
final class SharedAgentRuntime: AgentRuntime {
    private(set) var running: Set<String> = []
    /// Connection state of the configured MCP servers, by server id.
    private(set) var mcpStatus: [String: McpServerStatus] = [:]
    let supportsTools = true

    @ObservationIgnored private let bridge: AgentBridge
    @ObservationIgnored private let sessionStore: SessionStoreHost
    @ObservationIgnored private var sessions: [String: AgentSession] = [:]
    @ObservationIgnored private var handles: [String: any AutobotShared.Cancellable] = [:]
    @ObservationIgnored private var sessionListeners: [String: SessionListenerBox] = [:]
    /// Sessions whose state stream was stopped by [close]; [session] reopens it.
    @ObservationIgnored private var closed: Set<String> = []
    /// Bridge calls waiting for a callback, failed by [shutdown] (the bridge never answers then).
    @ObservationIgnored private var waiting: [UUID: @MainActor () -> Void] = [:]
    @ObservationIgnored private var isShutDown = false
    @ObservationIgnored private var runningHandle: (any AutobotShared.Cancellable)?
    @ObservationIgnored private var mcpHandle: (any AutobotShared.Cancellable)?
    @ObservationIgnored private let conversations: ConversationRepository
    @ObservationIgnored private let providers: ProviderRepository
    @ObservationIgnored private let store: SessionStore
    @ObservationIgnored private let listeners = NSMutableArray()

    init(
        conversations: ConversationRepository,
        providers: ProviderRepository,
        store: SessionStore,
        clients: HttpClientFactory,
        llm: LocalLlm,
        attachments: AttachmentRepository,
        secrets: SecretStore,
        snapshots: HostSnapshots,
    ) {
        self.conversations = conversations
        self.providers = providers
        self.store = store
        let sessionStore = SessionStoreHost(store: store)
        self.sessionStore = sessionStore
        bridge = AgentBridge(
            workspaceDir: AppPaths.workspace.path,
            providers: ProviderDirectoryHost(snapshots: snapshots, secrets: secrets),
            transport: URLSessionTransport(clients: clients),
            local: LocalEngineHost(snapshots: snapshots, llm: llm),
            store: sessionStore,
            images: ImageStoreHost(attachments: attachments),
            mcpTokens: McpTokenSourceHost(snapshots: snapshots, secrets: secrets),
            audit: clients.auditLog.shared,
            journalDir: AppPaths.workspaceUndo.path,
        )
        let listener = RunningListenerBox { [weak self] ids in self?.running = Set(ids) }
        listeners.add(listener)
        runningHandle = bridge.observeRunning(listener: listener)
        let mcpListener = McpStatusListenerBox { [weak self] list in
            self?.mcpStatus = Dictionary(list.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        }
        listeners.add(mcpListener)
        mcpHandle = bridge.observeMcpStatus(listener: mcpListener)
    }

    func preview(_ id: String, state: ChatSessionState) {
        let s = sessions[id] ?? AgentSession(id: id)
        s.state = state
        sessions[id] = s
    }

    func existingSession(_ id: String) -> AgentSession? { sessions[id] }

    func session(_ id: String) -> AgentSession {
        if let s = sessions[id] {
            if closed.remove(id) != nil { open(s) }
            return s
        }
        let s = AgentSession(id: id)
        sessions[id] = s
        open(s)
        return s
    }

    /// Streams the bridge's state for [s] (opening its agent on first use).
    private func open(_ s: AgentSession) {
        let id = s.id
        conversations.repoint(providers: providers.providers, only: id)
        let conversation = conversations.get(id)
        let provider = conversation?.providerId.flatMap(providers.get) ?? providers.providers.first
        let model = conversation?.model.flatMap { $0.isEmpty ? nil : $0 } ?? provider?.defaultModel ?? ""
        let listener = SessionListenerBox { [weak s] state in s?.state = state }
        sessionListeners[id] = listener
        handles[id] = bridge.open(sessionId: id, providerId: provider?.id ?? "", model: model, listener: listener)
    }

    func close(_ id: String) {
        guard let handle = handles.removeValue(forKey: id) else { return }
        handle.cancel()
        sessionListeners[id] = nil
        closed.insert(id)
    }

    func send(_ id: String, text: String, images: [String]) async {
        let prompt = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !prompt.isEmpty || !images.isEmpty else { return }
        _ = session(id)
        conversations.titleFromPrompt(id, prompt)
        bridge.send(sessionId: id, text: prompt, images: images)
    }

    func cancel(_ id: String) { bridge.cancel(sessionId: id) }

    func answerApproval(callId: String, outcome: ApprovalOutcome) {
        bridge.answerApproval(callId: callId, outcome: SharedMapping.outcome(outcome))
    }

    func answerQuestion(callId: String, answers: [String]?) { bridge.answerQuestion(callId: callId, answers: answers) }

    func setToolsEnabled(_ id: String, _ enabled: Bool) {
        _ = session(id)
        bridge.setToolsEnabled(sessionId: id, enabled: enabled)
    }

    func setPermission(_ id: String, _ preset: PermissionPreset) {
        _ = session(id)
        bridge.setPermission(sessionId: id, preset: SharedMapping.preset(preset))
    }

    func setRoute(_ id: String, providerId: String, model: String) {
        if var c = conversations.get(id) {
            c.providerId = providerId
            c.model = model
            conversations.update(c)
        }
        _ = session(id)
        bridge.setRoute(sessionId: id, providerId: providerId, model: model)
    }

    func providersChanged(_ list: [Provider]) {
        for c in conversations.repoint(providers: list) where sessions[c.id] != nil {
            bridge.setRoute(sessionId: c.id, providerId: c.providerId ?? "", model: c.model ?? "")
        }
    }

    func compact(_ id: String) {
        _ = session(id)
        bridge.compact(sessionId: id)
    }

    func undoStep(_ id: String, callId: String) {
        _ = session(id)
        bridge.undoStep(sessionId: id, callId: callId)
    }

    func undoTurn(_ id: String, turn: Int) {
        _ = session(id)
        bridge.undoTurn(sessionId: id, turn: Int32(turn))
    }

    func redo(_ id: String, revertId: String) {
        _ = session(id)
        bridge.redo(sessionId: id, revertId: revertId)
    }

    func discard(_ id: String) {
        handles.removeValue(forKey: id)?.cancel()
        sessionListeners[id] = nil
        closed.remove(id)
        sessions[id] = nil
        bridge.discard(sessionId: id)
        sessionStore.forget(sessionId: id)
        store.delete(id)
    }

    func shutdown() {
        isShutDown = true
        handles.values.forEach { $0.cancel() }
        handles.removeAll()
        sessionListeners.removeAll()
        closed.removeAll()
        runningHandle?.cancel()
        mcpHandle?.cancel()
        sessions.removeAll()
        bridge.shutdown {}
        store.clearIncognito()
        // The bridge's scope is cancelled: callbacks still outstanding never come.
        let pending = waiting.values
        waiting.removeAll()
        pending.forEach { $0() }
    }

    /**
     Calls the bridge with a callback and waits for it, or returns [stopped] at once once the
     bridge is shut down (now or while waiting). Exactly one of the two resumes the caller.
     */
    private func call<T: Sendable>(stopped: T, _ start: (_ done: @escaping @Sendable (T) -> Void) -> Void) async -> T {
        if isShutDown { return stopped }
        let id = UUID()
        let once = ResumeOnce<T>()
        let value = await withCheckedContinuation { (continuation: CheckedContinuation<T, Never>) in
            once.set(continuation)
            waiting[id] = { once.resume(stopped) }
            start { once.resume($0) }
        }
        waiting[id] = nil
        return value
    }

    /// Provider "test connection" through the shared codec and the app's transport: success, a
    /// summary (model count and first ids, or the error) and the model list to cache.
    func testConnection(_ spec: ProviderSpec) async -> (ok: Bool, message: String, modelsJson: String?) {
        let result = await call(stopped: Reply3(ok: false, message: Self.stoppedMessage, json: nil)) { done in
            bridge.testConnection(spec: spec) { ok, message, json in done(Reply3(ok: ok.boolValue, message: message, json: json)) }
        }
        return (result.ok, result.message, result.json)
    }

    /// Fetches a provider's chat models: the `ModelCache` JSON, or an error message.
    func listModels(_ spec: ProviderSpec) async -> (json: String?, error: String?) {
        let result = await call(stopped: Reply2(json: nil, error: Self.stoppedMessage)) { done in
            bridge.listModels(spec: spec) { json, error in done(Reply2(json: json, error: error)) }
        }
        return (result.json, result.error)
    }

    private static let stoppedMessage = "The agent runtime was stopped"
    private struct Reply3: Sendable { let ok: Bool, message: String, json: String? }
    private struct Reply2: Sendable { let json: String?, error: String? }

    /// The configured MCP servers; sessions pick up enabled servers' tools at their next turn.
    func setMcpServers(_ servers: [McpServer]) { bridge.setMcpServers(servers: servers.map(SharedMapping.mcpSpec)) }

    /// MCP "test connection" (initialize + tools/list) through the app's transport.
    func testMcpServer(_ server: McpServer, token: Secret?) async -> (Bool, String) {
        let result = await call(stopped: Reply3(ok: false, message: Self.stoppedMessage, json: nil)) { done in
            bridge.testMcpServer(spec: SharedMapping.mcpSpec(server), token: token?.reveal()) { ok, message in
                done(Reply3(ok: ok.boolValue, message: message, json: nil))
            }
        }
        return (result.ok, result.message)
    }

    /// Adds a host-implemented tool (e.g. `generate_image`) to every session.
    func registerTool(_ tool: any HostTool) { bridge.registerTool(tool: tool) }

    /// Local memory on (the encrypted notes) or off: tools and recall come and go in every session.
    func setMemory(_ store: MemoryStoreHost?) { bridge.setMemory(store: store) }

    /// The private vault on (`vault_search` in every session) or off.
    func setVault(_ host: VaultHost?) { bridge.setVault(host: host) }

    /// `web_search` on (settings read per call) or off.
    func setWebSearch(_ source: WebSearchSourceHost?) { bridge.setWebSearch(source: source) }

    /// "On limit, fall back" (off by default): a turn whose provider's usage limit is used up is
    /// retried once on the provider's helper model, else on [onDevice] (the "This phone" provider).
    func setQuotaFallback(_ enabled: Bool, onDevice: Provider?) {
        let model = onDevice?.defaultModel ?? ""
        bridge.setQuotaFallback(enabled: enabled, onDeviceProviderId: model.isEmpty ? nil : onDevice?.id, onDeviceModel: model.isEmpty ? nil : model)
    }

    /// Skills found in the workspace (`name: description`).
    func skills() -> [String] { bridge.skills() }
}

/// Resumes a continuation once: with the bridge's answer, or the fallback after a shutdown.
private final class ResumeOnce<T: Sendable>: Sendable {
    private let continuation = Mutex<CheckedContinuation<T, Never>?>(nil)

    func set(_ c: CheckedContinuation<T, Never>) { continuation.withLock { $0 = c } }

    func resume(_ value: T) {
        continuation.withLock { c -> CheckedContinuation<T, Never>? in
            defer { c = nil }
            return c
        }?.resume(returning: value)
    }
}

/**
 Decodes `SessionState` JSON off the main thread and delivers it on the main actor, latest wins:
 while one state is on its way to the main actor, newer ones replace each other and only the
 last is decoded and delivered after it. A streaming answer (~30 states a second) never queues
 work faster than the screen takes it.
 */
final class SessionListenerBox: NSObject, SessionListener, @unchecked Sendable {
    private let deliver: @MainActor (ChatSessionState) -> Void
    /// The newest JSON not decoded yet, and whether a decode → deliver pass is under way.
    private let slot = Mutex<(json: String?, busy: Bool)>((nil, false))
    private let queue = DispatchQueue(label: "autobot.session-state", qos: .userInitiated)

    init(_ deliver: @escaping @MainActor (ChatSessionState) -> Void) { self.deliver = deliver }

    func onState(json: String) {
        let start = slot.withLock { s -> Bool in
            s.json = json
            defer { s.busy = true }
            return !s.busy
        }
        if start { queue.async { self.drain() } }
    }

    /// Decodes the newest JSON and hands it to the main actor; runs again if more arrived.
    private func drain() {
        guard let json = slot.withLock({ s -> String? in
            defer { s.json = nil }
            if s.json == nil { s.busy = false }
            return s.json
        }) else { return }
        let state: ChatSessionState
        do {
            state = try JSONDecoder().decode(ChatSessionState.self, from: Data(json.utf8))
        } catch {
            // A shared/Swift mismatch would otherwise freeze the chat with no trace.
            EngineErrors.record("session state decode: \(error)")
            queue.async { self.drain() }
            return
        }
        let deliver = deliver
        DispatchQueue.main.async {
            MainActor.assumeIsolated { deliver(state) }
            // Delivered: the next pass picks up whatever arrived meanwhile (or ends).
            self.queue.async { self.drain() }
        }
    }
}

private final class McpStatusListenerBox: NSObject, McpStatusListener, @unchecked Sendable {
    private let deliver: @MainActor ([McpServerStatus]) -> Void

    init(_ deliver: @escaping @MainActor ([McpServerStatus]) -> Void) { self.deliver = deliver }

    func onStatus(servers: [McpServerStatus]) {
        let deliver = deliver
        nonisolated(unsafe) let servers = servers
        DispatchQueue.main.async { MainActor.assumeIsolated { deliver(servers) } }
    }
}

private final class RunningListenerBox: NSObject, RunningListener, @unchecked Sendable {
    private let deliver: @MainActor ([String]) -> Void

    init(_ deliver: @escaping @MainActor ([String]) -> Void) { self.deliver = deliver }

    func onRunning(sessionIds: [String]) {
        let deliver = deliver
        DispatchQueue.main.async { MainActor.assumeIsolated { deliver(sessionIds) } }
    }
}
#endif

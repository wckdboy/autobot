#if canImport(AutobotShared)
import AutobotShared
import Foundation
import Observation

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
    let supportsTools = true

    @ObservationIgnored private let bridge: AgentBridge
    @ObservationIgnored private var sessions: [String: AgentSession] = [:]
    @ObservationIgnored private var handles: [String: any AutobotShared.Cancellable] = [:]
    @ObservationIgnored private var runningHandle: (any AutobotShared.Cancellable)?
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
        bridge = AgentBridge(
            workspaceDir: AppPaths.workspace.path,
            providers: ProviderDirectoryHost(snapshots: snapshots, secrets: secrets),
            transport: URLSessionTransport(clients: clients),
            local: LocalEngineHost(snapshots: snapshots, llm: llm),
            store: SessionStoreHost(store: store),
            images: ImageStoreHost(attachments: attachments),
        )
        let listener = RunningListenerBox { [weak self] ids in self?.running = Set(ids) }
        listeners.add(listener)
        runningHandle = bridge.observeRunning(listener: listener)
    }

    func session(_ id: String) -> AgentSession {
        if let s = sessions[id] { return s }
        let s = AgentSession(id: id)
        sessions[id] = s
        let conversation = conversations.get(id)
        let provider = conversation?.providerId.flatMap(providers.get) ?? providers.providers.first
        let model = conversation?.model.flatMap { $0.isEmpty ? nil : $0 } ?? provider?.defaultModel ?? ""
        let listener = SessionListenerBox { [weak s] state in s?.state = state }
        listeners.add(listener)
        handles[id] = bridge.open(sessionId: id, providerId: provider?.id ?? "", model: model, listener: listener)
        return s
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

    func compact(_ id: String) {
        _ = session(id)
        bridge.compact(sessionId: id)
    }

    func discard(_ id: String) {
        handles.removeValue(forKey: id)?.cancel()
        sessions[id] = nil
        bridge.discard(sessionId: id)
        store.delete(id)
    }

    func shutdown() {
        handles.values.forEach { $0.cancel() }
        handles.removeAll()
        runningHandle?.cancel()
        sessions.removeAll()
        bridge.shutdown {}
        store.clearIncognito()
    }

    /// Provider "test connection" through the shared codec and the app's transport.
    func testConnection(_ spec: ProviderSpec) async -> (Bool, String) {
        await withCheckedContinuation { continuation in
            bridge.testConnection(spec: spec) { ok, message in continuation.resume(returning: (ok.boolValue, message)) }
        }
    }

    /// Adds a host-implemented tool (e.g. `generate_image`) to every session.
    func registerTool(_ tool: any HostTool) { bridge.registerTool(tool: tool) }

    /// Local memory on (the encrypted notes) or off: tools and recall come and go in every session.
    func setMemory(_ store: MemoryStoreHost?) { bridge.setMemory(store: store) }

    /// `web_search` on (settings read per call) or off.
    func setWebSearch(_ source: WebSearchSourceHost?) { bridge.setWebSearch(source: source) }

    /// Skills found in the workspace (`name: description`).
    func skills() -> [String] { bridge.skills() }
}

/// Decodes `SessionState` JSON off the main thread and delivers it in order on the main actor.
private final class SessionListenerBox: NSObject, SessionListener, @unchecked Sendable {
    private let deliver: @MainActor (ChatSessionState) -> Void

    init(_ deliver: @escaping @MainActor (ChatSessionState) -> Void) { self.deliver = deliver }

    func onState(json: String) {
        guard let state = try? JSONDecoder().decode(ChatSessionState.self, from: Data(json.utf8)) else { return }
        let deliver = deliver
        DispatchQueue.main.async { MainActor.assumeIsolated { deliver(state) } }
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

#if canImport(AutobotShared)
import AutobotShared
import Foundation
import Synchronization
import Testing
@testable import Autobot

/**
 A scripted OpenAI-compatible server: each host gets a script that answers a `/chat/completions`
 call with SSE events, pauses (delivered later on the loading thread) or a stream that never ends.
 Hosts are unique per test, so suites running in parallel never see each other's calls.
 */
final class LlmStub: URLProtocol, @unchecked Sendable {
    enum Step: Sendable {
        case event(String)
        case pause(milliseconds: Int)
        /// Keeps the stream open without finishing (a model still thinking).
        case hang
    }

    struct Call: Sendable {
        let host: String
        let path: String
        let headers: [String: String]
        let body: String

        var json: [String: Any]? { (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any] }
        var messages: [[String: Any]] { json?["messages"] as? [[String: Any]] ?? [] }
        /// The last message's role (`tool` after a tool result, `user` for a new prompt).
        var lastRole: String? { messages.last?["role"] as? String }
        func mentions(_ marker: String) -> Bool { body.contains(marker) }
    }

    typealias Script = @Sendable (Call) -> (status: Int, steps: [Step])

    private struct State {
        var scripts: [String: Script] = [:]
        var calls: [Call] = []
        var stopped: [String: Int] = [:]
    }

    nonisolated(unsafe) private static let state = Mutex(State())

    static func serve(_ host: String, _ script: @escaping Script) { state.withLock { $0.scripts[host] = script } }
    static func calls(_ host: String) -> [Call] { state.withLock { $0.calls.filter { $0.host == host } } }
    /// Streams of [host] the client dropped before they finished (a cancel).
    static func dropped(_ host: String) -> Int { state.withLock { $0.stopped[host] ?? 0 } }

    static func text(_ s: String) -> Step { .event(chunk(["content": s])) }
    static func finish(_ reason: String = "stop") -> Step {
        .event(json(["id": "c", "object": "chat.completion.chunk", "choices": [["index": 0, "delta": [String: Any](), "finish_reason": reason]]]))
    }
    static func toolCall(id: String, name: String, arguments: [String: Any]) -> [Step] {
        let args = json(arguments)
        return [.event(chunk(["tool_calls": [["index": 0, "id": id, "type": "function", "function": ["name": name, "arguments": args]]]])), finish("tool_calls")]
    }

    private static func chunk(_ delta: [String: Any]) -> String {
        json(["id": "c", "object": "chat.completion.chunk", "choices": [["index": 0, "delta": delta]]])
    }

    private static func json(_ value: Any) -> String {
        String(decoding: try! JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]), as: UTF8.self)
    }

    private var steps: [Step] = []
    private var stopped = false
    private var done = false
    private var thread: Thread?
    private var mode = RunLoop.Mode.default

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let host = request.url?.host() ?? ""
        let call = Call(host: host, path: request.url?.path() ?? "", headers: request.allHTTPHeaderFields ?? [:], body: Self.bodyOf(request))
        let script = Self.state.withLock { s -> Script? in
            s.calls.append(call)
            return s.scripts[host]
        }
        guard let url = request.url, let script else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        let (status, steps) = script(call)
        thread = Thread.current
        mode = RunLoop.current.currentMode ?? .default
        let type = status == 200 ? "text/event-stream" : "application/json"
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": type])!, cacheStoragePolicy: .notAllowed)
        if status != 200 {
            client?.urlProtocol(self, didLoad: Data(#"{"error":{"message":"refused","type":"invalid_request_error"}}"#.utf8))
            finish()
            return
        }
        self.steps = steps
        next()
    }

    @objc private func next() {
        guard !stopped else { return }
        while !steps.isEmpty {
            switch steps.removeFirst() {
            case .event(let json):
                client?.urlProtocol(self, didLoad: Data("data: \(json)\n\n".utf8))
            case .pause(let ms):
                nonisolated(unsafe) let thread = thread
                let mode = mode
                DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(ms)) { [self] in
                    guard let thread else { return }
                    perform(#selector(next), on: thread, with: nil, waitUntilDone: false, modes: [mode.rawValue])
                }
                return
            case .hang:
                return
            }
        }
        client?.urlProtocol(self, didLoad: Data("data: [DONE]\n\n".utf8))
        finish()
    }

    private func finish() {
        done = true
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {
        stopped = true
        if !done, let host = request.url?.host() { Self.state.withLock { $0.stopped[host, default: 0] += 1 } }
    }

    private static func bodyOf(_ request: URLRequest) -> String {
        if let data = request.httpBody { return String(decoding: data, as: UTF8.self) }
        guard let stream = request.httpBodyStream else { return "" }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let n = stream.read(&buffer, maxLength: buffer.count)
            if n <= 0 { break }
            data.append(buffer, count: n)
        }
        return String(decoding: data, as: UTF8.self)
    }
}

/// Polls [condition] until it holds or [timeout] passes (any thread).
func waitFor(_ timeout: Duration = .seconds(15), _ condition: @Sendable () -> Bool) async -> Bool {
    let clock = ContinuousClock()
    let end = clock.now + timeout
    while clock.now < end {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(20))
    }
    return condition()
}

/// Records one session's stream (`openStream`) as the bridge sends it.
final class StreamRecorder: NSObject, SessionStreamListener, @unchecked Sendable {
    private let state = Mutex<(transcripts: [String], frames: [String])>(([], []))

    func onTranscript(json: String) { state.withLock { $0.transcripts.append(json) } }
    func onFrame(json: String) { state.withLock { $0.frames.append(json) } }

    var transcripts: [String] { state.withLock { $0.transcripts } }
    var frames: [String] { state.withLock { $0.frames } }

    private static func object(_ json: String) -> [String: Any] { ((try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any]) ?? [:] }

    var revisions: [Int] { transcripts.map { Self.object($0)["revision"] as? Int ?? -1 } }
    var latest: SessionTranscript? { transcripts.last.flatMap { try? SessionStreamDecoder().transcript($0) } }
    var lastFrame: [String: Any]? { frames.last.map(Self.object) }
    var running: Bool { lastFrame?["running"] as? Bool ?? false }
    var approvals: [String] { (lastFrame?["approvals"] as? [[String: Any]] ?? []).compactMap { $0["callId"] as? String } }
    /// Live parts of the frames: (textFrom, text).
    var liveParts: [(from: Int, text: String)] {
        frames.compactMap { Self.object($0)["live"] as? [String: Any] }.map { ($0["textFrom"] as? Int ?? -1, $0["text"] as? String ?? "") }
    }

    var answers: [String] {
        (latest?.items ?? []).compactMap { if case .assistant(_, let text, _, _, _, _, _, _) = $0 { text } else { nil } }
    }

    var prompts: [String] {
        (latest?.items ?? []).compactMap { if case .user(_, let text, _) = $0 { text } else { nil } }
    }

    var interrupted: Bool {
        (latest?.items ?? []).contains { if case .assistant(_, _, _, let interrupted, _, _, _, _) = $0 { interrupted } else { false } }
    }

    var notices: [String] { (latest?.items ?? []).compactMap { if case .notice(_, let text, _) = $0 { text } else { nil } } }
}

/**
 The shared agent runtime through its Swift bridge (`AgentBridge`) with the app's real hosts
 (transport behind the kill switch, encrypted session store): the stream protocol (revisions,
 text deltas, resync), approvals answered by (session, call id), cancel mid-answer, idle-agent
 eviction (MAX_OPEN_AGENTS) with reopening, retain / release, provider invalidation (the same
 path as the adapter cache's TTL), and the foreground switch.
 */
@Suite(.serialized, .timeLimit(.minutes(2)))
struct AgentBridgeTests {
    private let dir: URL
    private let host = "llm-\(UUID().uuidString.prefix(8).lowercased()).example.com"
    private let snapshots = HostSnapshots()
    private let secrets = SecretStore()
    private let keyRef = "test-key-\(UUID().uuidString)"
    private let audit = AuditLog()
    private let bridge: AgentBridge

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("bridge-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("workspace"), withIntermediateDirectories: true)
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        let factory = HttpClientFactory(policy: policy, auditLog: audit, protocolClasses: [LlmStub.self])
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        try secrets.put(keyRef, Secret("key-one"))
        snapshots.setProviders([Provider(id: "p", kind: .openAICompatible, displayName: "Stub", baseUrl: "https://\(host)/v1", defaultModel: "stub-model", apiKeyRef: keyRef)])
        bridge = AgentBridge(
            workspaceDir: dir.appendingPathComponent("workspace").path,
            providers: ProviderDirectoryHost(snapshots: snapshots, secrets: secrets),
            transport: URLSessionTransport(clients: factory),
            local: LocalEngineHost(snapshots: snapshots, llm: LocalLlm()),
            store: SessionStoreHost(store: Autobot.SessionStore(store: store)),
            images: nil,
            mcpTokens: McpTokenSourceHost(snapshots: snapshots, secrets: secrets),
            audit: audit.shared,
            journalDir: nil,
        )
    }

    private func open(_ id: String, _ recorder: StreamRecorder) -> any AutobotShared.Cancellable {
        bridge.openStream(sessionId: id, providerId: "p", model: "stub-model", listener: recorder, textDeltas: true)
    }

    private func finish() {
        bridge.shutdown {}
        secrets.delete(keyRef)
    }

    // MARK: Stream protocol

    @Test func revisionsRiseAndTextDeltasRebuildTheAnswer() async throws {
        LlmStub.serve(host) { _ in
            (200, [LlmStub.text("Hel"), .pause(milliseconds: 150), LlmStub.text("lo, "), .pause(milliseconds: 150), LlmStub.text("world"), .pause(milliseconds: 100), LlmStub.finish()])
        }
        let recorder = StreamRecorder()
        let handle = open("s-stream", recorder)
        bridge.send(sessionId: "s-stream", text: "say hello", images: [])
        #expect(await waitFor { recorder.answers == ["Hello, world"] && !recorder.running })

        let revisions = recorder.revisions
        #expect(revisions == revisions.sorted() && Set(revisions).count == revisions.count)
        #expect(revisions.count >= 2)
        // Frames carry deltas: replayed in order, they rebuild what was streamed with no gap.
        let decoder = SessionStreamDecoder()
        var shown = ""
        for frame in recorder.frames {
            let (update, resync) = try decoder.frame(frame)
            #expect(!resync)
            if let live = update.live { shown = live.text }
        }
        #expect("Hello, world".hasPrefix(shown))
        #expect(recorder.liveParts.contains { $0.from > 0 })
        #expect(LlmStub.calls(host).first?.headers["Authorization"] == "Bearer key-one")
        handle.cancel()
        finish()
    }

    @Test func aLostFrameIsRepairedByAResync() async throws {
        LlmStub.serve(host) { _ in (200, [LlmStub.text("partial answer"), .hang]) }
        let recorder = StreamRecorder()
        let handle = open("s-resync", recorder)
        bridge.send(sessionId: "s-resync", text: "think", images: [])
        #expect(await waitFor { recorder.liveParts.contains { $0.text.contains("partial") } })
        let before = recorder.revisions.last ?? 0
        let framesBefore = recorder.frames.count

        // The client missed a frame (a delta that does not continue its text): it asks again.
        let decoder = SessionStreamDecoder()
        let gap = #"{"live":{"turn":0,"step":0,"textFrom":99,"text":"zz","reasoningFrom":0,"reasoning":"","toolNames":[]},"running":true}"#
        #expect(try decoder.frame(gap).resync)
        bridge.resync(sessionId: "s-resync")

        #expect(await waitFor { (recorder.revisions.last ?? 0) > before })
        #expect(await waitFor { recorder.frames.count > framesBefore && recorder.liveParts.last?.from == 0 })
        #expect(recorder.liveParts.last?.text == "partial answer")
        bridge.cancel(sessionId: "s-resync")
        #expect(await waitFor { !recorder.running })
        handle.cancel()
        finish()
    }

    @Test func cancellingMidAnswerStopsTheStreamAndKeepsWhatArrived() async throws {
        LlmStub.serve(host) { _ in (200, [LlmStub.text("I will count: 1 2 3"), .hang]) }
        let recorder = StreamRecorder()
        let handle = open("s-cancel", recorder)
        bridge.send(sessionId: "s-cancel", text: "count to a million", images: [])
        #expect(await waitFor { recorder.running && recorder.liveParts.contains { $0.text.contains("count") } })

        bridge.cancel(sessionId: "s-cancel")
        #expect(await waitFor { !recorder.running })
        let host = host
        #expect(await waitFor { LlmStub.dropped(host) == 1 })
        #expect(recorder.interrupted || !recorder.notices.isEmpty)
        handle.cancel()
        finish()
    }

    // MARK: Approvals

    @Test func approvalsAreAnsweredBySessionAndCallId() async throws {
        let workspace = dir.appendingPathComponent("workspace")
        try Data("1".utf8).write(to: workspace.appendingPathComponent("one.txt"))
        try Data("2".utf8).write(to: workspace.appendingPathComponent("two.txt"))
        LlmStub.serve(host) { call in
            if call.lastRole == "tool" { return (200, [LlmStub.text("done"), LlmStub.finish()]) }
            // Both chats' models pick the same call id: answers must not cross over.
            let file = call.mentions("[one]") ? "one.txt" : "two.txt"
            return (200, LlmStub.toolCall(id: "call_1", name: "delete", arguments: ["file_path": file]))
        }
        let one = StreamRecorder(), two = StreamRecorder()
        let h1 = open("s-one", one), h2 = open("s-two", two)
        bridge.send(sessionId: "s-one", text: "remove it [one]", images: [])
        bridge.send(sessionId: "s-two", text: "remove it [two]", images: [])
        #expect(await waitFor { one.approvals == ["call_1"] && two.approvals == ["call_1"] })

        bridge.answerApproval(sessionId: "s-two", callId: "call_1", outcome: .rejected)
        #expect(await waitFor { two.answers.last == "done" && !two.running })
        try await Task.sleep(for: .milliseconds(200))
        #expect(one.approvals == ["call_1"])
        #expect(one.running)
        #expect(FileManager.default.fileExists(atPath: workspace.appendingPathComponent("one.txt").path))

        bridge.answerApproval(sessionId: "s-one", callId: "call_1", outcome: .allowedOnce)
        #expect(await waitFor { one.answers.last == "done" && !one.running })
        #expect(!FileManager.default.fileExists(atPath: workspace.appendingPathComponent("one.txt").path))
        #expect(FileManager.default.fileExists(atPath: workspace.appendingPathComponent("two.txt").path))
        h1.cancel()
        h2.cancel()
        finish()
    }

    // MARK: Eviction

    @Test func idleAgentsBeyondTheLimitAreClosedAndReopenWithTheirHistory() async throws {
        LlmStub.serve(host) { call in
            let marker = call.messages.compactMap { $0["content"] as? String }.last ?? ""
            return (200, [LlmStub.text("answer to \(marker)"), LlmStub.finish()])
        }
        let limit = Int(AgentBridge.companion.MAX_OPEN_AGENTS)
        nonisolated(unsafe) let bridge = bridge
        bridge.retain(sessionId: "s-0")
        for i in 0 ..< limit + 3 {
            let recorder = StreamRecorder()
            let handle = open("s-\(i)", recorder)
            bridge.send(sessionId: "s-\(i)", text: "q\(i)", images: [])
            #expect(await waitFor { recorder.answers == ["answer to q\(i)"] && !recorder.running })
            handle.cancel()
        }
        // The retained one stays; the rest are trimmed to the limit (released streams evict lazily).
        #expect(await waitFor { bridge.openSessions().count <= limit + 1 })
        let open = Set(bridge.openSessions())
        #expect(open.contains("s-0"))
        #expect(!open.contains("s-1"))

        // An evicted session comes back from the store with its transcript, and still answers.
        let again = StreamRecorder()
        let handle = self.open("s-1", again)
        #expect(await waitFor { again.prompts == ["q1"] && again.answers == ["answer to q1"] })
        bridge.send(sessionId: "s-1", text: "q1b", images: [])
        #expect(await waitFor { again.answers == ["answer to q1", "answer to q1b"] })
        handle.cancel()

        bridge.release(sessionId: "s-0")
        #expect(await waitFor { bridge.openSessions().count <= limit })
        finish()
    }

    // MARK: Providers

    @Test func invalidatingProvidersMidTurnRebuildsTheAdapterWithTheNewKey() async throws {
        let secrets = secrets, keyRef = keyRef
        nonisolated(unsafe) let bridge = bridge
        LlmStub.serve(host) { call in
            if call.lastRole == "tool" { return (200, [LlmStub.text("listed"), LlmStub.finish()]) }
            if call.mentions("rotate") {
                // The key changes between two steps of one turn (an edit, or the cache's TTL running out).
                try? secrets.put(keyRef, Secret("key-two"))
                bridge.invalidateProviders()
                return (200, LlmStub.toolCall(id: "g1", name: "glob", arguments: ["pattern": "*.txt"]))
            }
            return (200, [LlmStub.text("hi"), LlmStub.finish()])
        }
        let recorder = StreamRecorder()
        let handle = open("s-keys", recorder)
        bridge.send(sessionId: "s-keys", text: "hello", images: [])
        #expect(await waitFor { recorder.answers == ["hi"] && !recorder.running })

        // A new key without an invalidation: the cached adapter keeps the old one.
        try secrets.put(keyRef, Secret("key-stale"))
        bridge.send(sessionId: "s-keys", text: "again", images: [])
        #expect(await waitFor { recorder.answers.count == 2 && !recorder.running })
        try secrets.put(keyRef, Secret("key-one"))

        bridge.send(sessionId: "s-keys", text: "rotate now", images: [])
        #expect(await waitFor { recorder.answers.last == "listed" && !recorder.running })
        let keys = LlmStub.calls(host).map { $0.headers["Authorization"] ?? "" }
        #expect(keys == ["Bearer key-one", "Bearer key-one", "Bearer key-one", "Bearer key-two"])
        handle.cancel()
        finish()
    }

    @Test func aRejectedKeySaysWhereToFixIt() async throws {
        LlmStub.serve(host) { _ in (401, []) }
        let recorder = StreamRecorder()
        let handle = open("s-401", recorder)
        bridge.send(sessionId: "s-401", text: "hello", images: [])
        #expect(await waitFor { !recorder.notices.isEmpty && !recorder.running })
        #expect(recorder.notices.contains { $0.contains("API key") && $0.contains("Model providers") })
        handle.cancel()
        finish()
    }

    @Test func turnsRunAcrossTheForegroundSwitch() async throws {
        LlmStub.serve(host) { _ in (200, [LlmStub.text("still here"), LlmStub.finish()]) }
        let recorder = StreamRecorder()
        let handle = open("s-bg", recorder)
        bridge.setForeground(foreground: false)
        bridge.send(sessionId: "s-bg", text: "hello", images: [])
        #expect(await waitFor { recorder.answers == ["still here"] && !recorder.running })
        bridge.setForeground(foreground: true)
        bridge.send(sessionId: "s-bg", text: "again", images: [])
        #expect(await waitFor { recorder.answers.count == 2 })
        handle.cancel()
        finish()
    }
}

/// The app's runtime over the bridge: closed chats beyond [SharedAgentRuntime.maxIdle] are
/// forgotten, and one opened again streams its history back and keeps answering.
@Suite(.serialized, .timeLimit(.minutes(2)))
@MainActor
struct SharedRuntimeTests {
    @Test func closedChatsAreTrimmedAndReopenWithTheirHistory() async throws {
        let host = "rt-\(UUID().uuidString.prefix(8).lowercased()).example.com"
        LlmStub.serve(host) { call in
            let last = call.messages.compactMap { $0["content"] as? String }.last ?? ""
            return (200, [LlmStub.text("re: \(last)"), LlmStub.finish()])
        }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rt-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let keys = KeyManager()
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: keys)
        let secrets = SecretStore()
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        let snapshots = HostSnapshots()
        let conversations = ConversationRepository(store: store)
        let providers = ProviderRepository(store: store, secrets: secrets)
        let provider = try providers.save(Provider(id: "p-\(host)", kind: .openAICompatible, displayName: "Stub", baseUrl: "https://\(host)/v1", defaultModel: "m"),
                                          apiKey: .set(Secret("k")))
        snapshots.setProviders(providers.providers)
        let runtime = SharedAgentRuntime(
            conversations: conversations, providers: providers, store: Autobot.SessionStore(store: store),
            clients: HttpClientFactory(policy: policy, auditLog: AuditLog(), protocolClasses: [LlmStub.self]), llm: LocalLlm(),
            attachments: AttachmentRepository(keys: keys), secrets: secrets, snapshots: snapshots,
        )
        var ids: [String] = []
        for i in 0 ..< SharedAgentRuntime.maxIdle + 2 {
            let c = conversations.create(incognito: false, providerId: provider.id, model: "m")
            ids.append(c.id)
            let session = runtime.session(c.id)
            await runtime.send(c.id, text: "q\(i)", images: [])
            #expect(await eventually { session.transcript.items.count == 2 && !session.running })
            runtime.close(c.id)
        }
        #expect(await eventually { runtime.heldSessionIds.count <= SharedAgentRuntime.maxIdle })
        #expect(runtime.existingSession(ids[0]) == nil)
        #expect(runtime.existingSession(ids[ids.count - 1]) != nil)

        let reopened = runtime.session(ids[0])
        #expect(await eventually { reopened.transcript.items.count == 2 })
        await runtime.send(ids[0], text: "q0b", images: [])
        #expect(await eventually {
            reopened.transcript.items.contains { if case .assistant(_, let text, _, _, _, _, _, _) = $0 { text == "re: q0b" } else { false } }
        })
        #expect(conversations.get(ids[0])?.title == "q0")
        runtime.shutdown()
        secrets.delete(provider.apiKeyRef ?? "")
    }
}
#endif

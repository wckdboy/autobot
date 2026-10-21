#if canImport(AutobotShared)
import AutobotShared
import Foundation
import Testing
@testable import Autobot

/// A small `show_ui` form, as the shared prompt's example: a name field and a button whose
/// action carries the name back.
private let rsvp = #"[{"version":"v1.0","createSurface":{"surfaceId":"rsvp","catalogId":"https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json","components":[{"id":"root","component":"Column","children":["q","name","go"]},{"id":"q","component":"Text","text":"**Coming on Friday?**"},{"id":"name","component":"TextField","label":"Your name","value":{"@path":"/name"}},{"id":"go","component":"Button","variant":"primary","child":"go_t","action":{"event":{"name":"rsvpYes","context":{"name":{"@path":"/name"}}}}},{"id":"go_t","component":"Text","text":"I'm in"}],"dataModel":{"name":""}}}]"#

private func showUiItem(_ key: String, _ stream: String?) -> Autobot.SessionItem {
    .tool(.init(key: key, callId: "c-\(key)", name: "show_ui", summary: "", state: .ok, output: "Shown to the user.", code: nil, galleryIds: [], durationMs: nil, a2ui: stream))
}

/// A chat's generative UI on its own: the transcript's `show_ui` items replayed into one host.
@MainActor
struct A2uiChatSurfacesTests {
    @Test func aToolItemWithA2uiDrawsItsSurface() {
        let chat = A2uiChatSurfaces()
        #expect(chat.sync([.user(key: "e0", text: "rsvp?", images: []), showUiItem("t1_0", rsvp)]))
        let models = chat.surfaces("t1_0")
        #expect(models.map(\.surfaceId) == ["rsvp"])
        #expect(models.first?.node(id: "go") != nil)
        // Another item owns nothing; syncing the same transcript again changes nothing.
        #expect(chat.surfaces("e0").isEmpty)
        #expect(!chat.sync([.user(key: "e0", text: "rsvp?", images: []), showUiItem("t1_0", rsvp)]))
        #expect(chat.surfaces("t1_0").first === models.first)
    }

    @Test func inputSurvivesNewTurnsAndAnUnrelatedHistoryStartsOver() throws {
        let chat = A2uiChatSurfaces()
        chat.sync([showUiItem("t1_0", rsvp)])
        let model = try #require(chat.surfaces("t1_0").first)
        #expect(model.set(try #require(model.node(id: "name")), string: "Mara"))
        // A later turn (a tool step without UI, an answer) keeps the typed name.
        chat.sync([showUiItem("t1_0", rsvp), showUiItem("t2_0", nil), .assistant(key: "e9", text: "ok", reasoning: nil, interrupted: false, outputTokens: nil, model: nil)])
        #expect(model.dataModelJson?.contains("Mara") == true)
        // A transcript that no longer extends the applied one (undo, another chat) replays from scratch.
        chat.sync([showUiItem("x1_0", rsvp)])
        #expect(chat.surfaces("t1_0").isEmpty)
        #expect(chat.surfaces("x1_0").map(\.surfaceId) == ["rsvp"])
        #expect(model.dataModelJson?.contains("Mara") == false)
    }

    @Test func theButtonSendsTheSharedActionMessageWithItsLabel() throws {
        let chat = A2uiChatSurfaces()
        chat.sync([showUiItem("t1_0", rsvp)])
        let model = try #require(chat.surfaces("t1_0").first)
        model.set(try #require(model.node(id: "name")), string: "Mara")
        guard case let .send(surfaceId, name, messageJson, _, userMessage) = model.dispatch(try #require(model.node(id: "go"))) else {
            Issue.record("the button did not send")
            return
        }
        #expect(surfaceId == "rsvp" && name == "rsvpYes" && userMessage == nil)
        #expect(messageJson.contains("Mara"))
        #expect(A2uiChat.shared.humanize(name: name) == "Rsvp yes")
    }
}

/**
 Generative UI through the real shared runtime (`AgentBridge`, the app's hosts, a scripted
 model): `show_ui` is offered only while Settings' `generative_ui` is on, its result reaches the
 Swift transcript with the A2UI stream, a tap goes back as the next user turn shown by its label,
 and a reopened chat replays its surfaces from the encrypted log.
 */
@Suite(.serialized, .timeLimit(.minutes(2)))
@MainActor
struct A2uiChatBridgeTests {
    private let dir: URL
    private let host = "llm-\(UUID().uuidString.prefix(8).lowercased()).example.com"
    private let snapshots = HostSnapshots()
    private let secrets = SecretStore()
    private let keyRef = "test-key-\(UUID().uuidString)"
    private let audit = AuditLog()
    private let factory: HttpClientFactory
    private let store: EncryptedStore

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("a2ui-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir.appendingPathComponent("workspace"), withIntermediateDirectories: true)
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        factory = HttpClientFactory(policy: policy, auditLog: audit, protocolClasses: [LlmStub.self])
        store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        try secrets.put(keyRef, Secret("key-one"))
        snapshots.setProviders([Provider(id: "p", kind: .openAICompatible, displayName: "Stub", baseUrl: "https://\(host)/v1", defaultModel: "stub-model", apiKeyRef: keyRef)])
    }

    private func makeBridge() -> AgentBridge {
        AgentBridge(
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

    private static func offersShowUi(_ call: LlmStub.Call) -> Bool {
        ((call.json?["tools"] as? [[String: Any]]) ?? []).contains { ($0["function"] as? [String: Any])?["name"] as? String == "show_ui" }
    }

    @Test func showUiRendersAnActionBecomesATurnAndAReloadReplaysIt() async throws {
        LlmStub.serve(host) { call in
            // The system prompt explains <a2ui_action>; the user turn starts with "[UI action]".
            if (call.messages.last?["content"] as? String)?.hasPrefix("[UI action]") == true { return (200, [LlmStub.text("See you Friday, Mara."), LlmStub.finish()]) }
            if call.lastRole == "tool" { return (200, [LlmStub.finish()]) }
            return (200, LlmStub.toolCall(id: "ui1", name: "show_ui", arguments: ["messages": rsvp]))
        }
        let bridge = makeBridge()
        bridge.setGenerativeUi(enabled: true)
        let recorder = StreamRecorder()
        let handle = bridge.openStream(sessionId: "s-ui", providerId: "p", model: "stub-model", listener: recorder, textDeltas: true)
        bridge.send(sessionId: "s-ui", text: "Ask me about Friday", images: [])

        // The tool step arrives in the Swift transcript with its stream, and draws a surface.
        #expect(await waitFor { recorder.latest?.items.contains { if case .tool(let t) = $0 { t.a2ui != nil } else { false } } == true && !recorder.running })
        #expect(LlmStub.calls(host).first.map(Self.offersShowUi) == true)
        let items = try #require(recorder.latest?.items)
        let chat = A2uiChatSurfaces()
        chat.sync(items)
        let key = try #require(items.compactMap { if case .tool(let t) = $0, t.name == "show_ui" { t.key } else { nil } }.first)
        let model = try #require(chat.surfaces(key).first)
        #expect(model.surfaceId == "rsvp" && model.root != nil)

        // A tap: the shared action message as the next turn, the label in the transcript.
        model.set(try #require(model.node(id: "name")), string: "Mara")
        guard case let .send(surfaceId, name, messageJson, dataModelJson, userMessage) = model.dispatch(try #require(model.node(id: "go"))) else {
            Issue.record("the button did not send")
            return
        }
        bridge.sendA2uiAction(sessionId: "s-ui", surfaceId: surfaceId, name: name, messageJson: messageJson, dataModelJson: dataModelJson, userMessage: userMessage)
        #expect(await waitFor { recorder.answers.contains("See you Friday, Mara.") && !recorder.running })
        #expect(recorder.prompts == ["Ask me about Friday", "Rsvp yes"])
        let sent = try #require(LlmStub.calls(host).last { ($0.messages.last?["content"] as? String)?.hasPrefix("[UI action]") == true })
        #expect(sent.mentions("Mara") && sent.mentions("rsvpYes"))
        handle.cancel()
        bridge.shutdown {}

        // Reload: another runtime reads the chat back from the encrypted log and the surface returns.
        let again = makeBridge()
        let reloaded = StreamRecorder()
        let reopened = again.openStream(sessionId: "s-ui", providerId: "p", model: "stub-model", listener: reloaded, textDeltas: true)
        #expect(await waitFor { reloaded.prompts.count == 2 })
        let fresh = A2uiChatSurfaces()
        fresh.sync(try #require(reloaded.latest?.items))
        #expect(fresh.surfaces(key).first?.node(id: "go") != nil)
        reopened.cancel()
        again.shutdown {}
        secrets.delete(keyRef)
    }

    @Test func showUiIsOfferedOnlyWhileGenerativeUiIsOn() async throws {
        LlmStub.serve(host) { _ in (200, [LlmStub.text("plain"), LlmStub.finish()]) }
        let bridge = makeBridge()
        let recorder = StreamRecorder()
        let handle = bridge.openStream(sessionId: "s-off", providerId: "p", model: "stub-model", listener: recorder, textDeltas: true)
        bridge.send(sessionId: "s-off", text: "hi", images: [])
        #expect(await waitFor { recorder.answers == ["plain"] && !recorder.running })
        #expect(LlmStub.calls(host).last.map(Self.offersShowUi) == false)

        bridge.setGenerativeUi(enabled: true)
        bridge.send(sessionId: "s-off", text: "again", images: [])
        #expect(await waitFor { recorder.answers.count == 2 && !recorder.running })
        #expect(LlmStub.calls(host).last.map(Self.offersShowUi) == true)
        #expect(LlmStub.calls(host).last?.mentions("Generative UI") == true)

        bridge.setGenerativeUi(enabled: false)
        bridge.send(sessionId: "s-off", text: "once more", images: [])
        #expect(await waitFor { recorder.answers.count == 3 && !recorder.running })
        #expect(LlmStub.calls(host).last.map(Self.offersShowUi) == false)
        handle.cancel()
        bridge.shutdown {}
        secrets.delete(keyRef)
    }
}
#endif

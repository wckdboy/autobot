import SwiftUI
import Testing
import UIKit
@testable import Autobot

/**
 Main-thread cost of the hot paths the perf audit measured: a streaming 60-message agent chat
 with tool outputs, the chat list with 300 chats, and a download's progress ticks. Prints
 `PERF …` lines; skipped unless `AUTOBOT_PERF` is set (`TEST_RUNNER_AUTOBOT_PERF=1 xcodebuild test`).
 */
@MainActor
struct PerfBenchmarkTests {
    private var enabled: Bool { ProcessInfo.processInfo.environment["AUTOBOT_PERF"] != nil }

    /// A 60-row transcript: prompts, markdown answers with reasoning, and tools with ~16 KB outputs.
    static func longState(_ id: String) -> ChatSessionState {
        var s = ChatSessionState(sessionId: id)
        s.toolsEnabled = true
        s.running = true
        var items: [SessionItem] = []
        let output = String(repeating: "line of tool output with some text 0123456789\n", count: 350)
        let answer = String(repeating: "Here is a paragraph with `inline code` and words.\n\n", count: 12) + "```swift\nlet x = 1\nprint(x)\n```\n"
        for i in 0 ..< 15 {
            items.append(.user(key: "u\(i)", text: "Prompt number \(i): do the thing", images: []))
            items.append(.assistant(key: "a\(i)", text: answer, reasoning: String(repeating: "thinking about it\n", count: 40), interrupted: false, outputTokens: 300, model: "m"))
            items.append(.tool(.init(key: "t\(i)a", callId: "c\(i)a", name: "read", summary: "notes/\(i).md", state: .ok, output: output, code: nil, galleryIds: [], durationMs: 120)))
            items.append(.tool(.init(key: "t\(i)b", callId: "c\(i)b", name: "grep", summary: "TODO", state: .ok, output: output, code: nil, galleryIds: [], durationMs: 80)))
        }
        s.projection = SessionProjection(items: items, lastUsage: TokenUsage(inputTokens: 20_000, outputTokens: 4_000), contextWindow: 128_000)
        s.provider = "p"
        s.model = "m"
        return s
    }

    /// The bridge's stream JSON (`AgentBridge.openStream`): a transcript, and frames with text deltas.
    static func transcriptJSON(_ s: ChatSessionState, revision: Int = 1) throws -> String {
        "{\"sessionId\":\"\(s.sessionId)\",\"revision\":\(revision),\"projection\":" + String(decoding: try JSONEncoder().encode(s.projection), as: UTF8.self) + "}"
    }

    static func frameJSON(_ s: ChatSessionState, frame: Int, turn: Int = 1, textFrom: Int, delta: String, textLength: Int) -> String {
        """
        {"sessionId":"\(s.sessionId)","revision":1,"frame":\(frame),"live":{"turn":\(turn),"step":0,"textFrom":\(textFrom),"text":"\(delta)",\
        "textLength":\(textLength),"reasoningFrom":0,"reasoning":"","reasoningLength":0,"toolCalls":[],"toolNames":[],"tokensPerSecond":20.0},\
        "running":true,"approvals":[],"question":null,"toolsEnabled":true,"permission":"workspace-write","provider":"p","model":"m"}
        """
    }

    @Test func streamingChat() async throws {
        guard enabled else { return }
        let app = AppContainer()
        let nav = Navigator()
        let conversation = app.conversations.create(incognito: true, providerId: nil, model: "m")
        var state = Self.longState(conversation.id)
        state.live = LiveState(text: "", reasoning: "", toolNames: [], tokensPerSecond: 20)
        app.agent.preview(conversation.id, state: state)

        let window = try Self.window(ChatScreen(conversationId: conversation.id), app, nav)
        defer { window.isHidden = true }
        try await Task.sleep(for: .milliseconds(800))

        // 1. Decoding. Before: every frame carried the whole state (projection spliced in) and was
        // decoded whole. Now: one transcript, then small frames with the streamed delta.
        let frames = 120
        let word = "streamed words "
        var full: [String] = []
        for i in 0 ..< frames {
            state.live?.text = String(repeating: word, count: i + 1)
            full.append(String(decoding: try JSONEncoder().encode(state), as: UTF8.self))
        }
        let clock = ContinuousClock()
        let whole = clock.measure {
            for json in full { _ = try? JSONDecoder().decode(ChatSessionState.self, from: Data(json.utf8)) }
        }
        print("PERF chat.decodeWhole frames=\(frames) bytes/frame=\(full[0].utf8.count) perFrame=\(whole / frames)")
        let transcript = try Self.transcriptJSON(state)
        let deltas = (0 ..< frames).map { i in Self.frameJSON(state, frame: i + 1, textFrom: i * word.utf16.count, delta: word, textLength: (i + 1) * word.utf16.count) }
        let decoder = SessionStreamDecoder()
        var last: SessionUpdate?
        let decode = try clock.measure {
            _ = try decoder.transcript(transcript)
            for json in deltas { last = try decoder.frame(json).update }
        }
        #expect(last?.live?.text == String(repeating: word, count: frames))
        print("PERF chat.decodeStream transcript=1 frames=\(frames) bytes/frame=\(deltas[0].utf8.count) total=\(decode) perFrame=\(decode / frames)")

        // 2. Rendering: each live frame applied on the main actor, then laid out.
        let host = try #require(window.rootViewController)
        let session = try #require(app.agent.existingSession(conversation.id))
        let replay = SessionStreamDecoder()
        _ = try replay.transcript(transcript)
        let updates = try deltas.map { try replay.frame($0).update }
        InkProbe.reset()
        let render = clock.measure {
            for update in updates {
                session.apply(update)
                host.view.setNeedsLayout()
                host.view.layoutIfNeeded()
            }
        }
        print("PERF chat.render frames=\(frames) total=\(render) perFrame=\(render / frames) bodies=\(InkProbe.snapshot())")
    }

    @Test func chatList300() async throws {
        guard enabled else { return }
        let app = AppContainer()
        let nav = Navigator()
        var ids: [String] = []
        for i in 0 ..< 300 {
            var c = app.conversations.create(incognito: true, providerId: nil, model: "m")
            c.title = "Chat number \(i) about something"
            app.conversations.update(c)
            ids.append(c.id)
        }
        let clock = ContinuousClock()
        var window: UIWindow?
        let first = try clock.measure {
            window = try Self.window(ChatListScreen(), app, nav)
            window?.rootViewController?.view.layoutIfNeeded()
        }
        defer { window?.isHidden = true }
        try await Task.sleep(for: .milliseconds(500))
        let host = try #require(window?.rootViewController)
        InkProbe.reset()
        let updates = 60
        let render = clock.measure {
            for i in 0 ..< updates {
                app.conversations.touch(ids[i % ids.count])
                host.view.setNeedsLayout()
                host.view.layoutIfNeeded()
            }
        }
        // The model rebuild alone (what every chat change costs before SwiftUI diffs the list).
        let lists = ChatListModel()
        var n = 0
        let rebuild = clock.measure {
            for i in 0 ..< 20 {
                n += lists.model(.init(revision: i, count: 300, folders: [], providers: [], hidden: [], filterKey: "all", sortKey: "RECENT", hits: nil, startOfToday: 0)) {
                    (app.conversations.conversations, [], [])
                }.total
            }
        }
        print("PERF list.rebuild perBuild=\(rebuild / 20) n=\(n)")
        print("PERF list.first=\(first) list.update n=\(updates) total=\(render) perUpdate=\(render / updates) bodies=\(InkProbe.snapshot())")
    }

    @Test func downloadProgress() async throws {
        guard enabled else { return }
        let app = AppContainer()
        await app.unlockData()
        let plan = ModelPlan(
            id: "perf-model", kind: .chat, source: .catalog, title: "Perf", subtitle: "", format: .gguf, engine: .llama,
            manifest: ModelManifest(files: [ModelFile(role: .model, name: "m.gguf", url: "https://example.invalid/m.gguf", sizeBytes: 4_000_000_000)]),
        )
        app.library.add(plan)
        defer { app.library.remove(plan.id) }
        let clock = ContinuousClock()
        let ticks = 40
        let total = clock.measure {
            for i in 0 ..< ticks { app.downloader.benchmarkTick(plan.id, downloaded: Int64(i) * 50_000_000, total: 4_000_000_000) }
        }
        print("PERF download.tick n=\(ticks) total=\(total) perTick=\(total / ticks)")
    }

    @Test func launch() async throws {
        guard enabled else { return }
        let clock = ContinuousClock()
        var app: AppContainer?
        let construct = clock.measure { app = AppContainer() }
        let unlock = await clock.measure { await app?.unlockData() }
        print("PERF launch.appContainerInit=\(construct) unlockData=\(unlock)")
    }

    private static func window(_ view: some View, _ app: AppContainer, _ nav: Navigator) throws -> UIWindow {
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let root = view.environment(app).environment(nav).environment(\.inkStill, true).autobotTheme(.dark)
        window.rootViewController = UIHostingController(rootView: root)
        window.makeKeyAndVisible()
        return window
    }
}

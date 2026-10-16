import Foundation
import Observation
import SwiftUI
import Synchronization
import Testing
import UIKit
@testable import Autobot

/// The encrypted store's WAL mode and its two connections under concurrent use.
struct EncryptedStoreConcurrencyTests {
    private func store() -> (EncryptedStore, URL) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("wal-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return (EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager()), dir)
    }

    @Test func runsInWalMode() throws {
        let (store, dir) = store()
        defer { try? FileManager.default.removeItem(at: dir) }
        try store.put("c", id: "a", "x")
        #expect(FileManager.default.fileExists(atPath: dir.appendingPathComponent("db.sqlite-wal").path))
        #expect(try store.get("c", id: "a", as: String.self) == "x")
    }

    @Test func readersAndWritersRunConcurrently() async throws {
        let (store, dir) = store()
        defer { try? FileManager.default.removeItem(at: dir) }
        let failures = Atomic<Int>(0)
        await withTaskGroup(of: Void.self) { group in
            for writer in 0 ..< 4 {
                group.addTask {
                    for i in 0 ..< 50 {
                        do {
                            try store.put("c", id: "w\(writer)-\(i)", i)
                            try store.appendEvents(sessionId: "s\(writer)", [(seq: Int64(i), json: Data("{\"seq\":\(i)}".utf8))])
                        } catch { failures.add(1, ordering: .relaxed) }
                    }
                }
            }
            for _ in 0 ..< 4 {
                group.addTask {
                    for _ in 0 ..< 50 {
                        do {
                            _ = try store.all("c", as: Int.self)
                            _ = try store.loadEvents(sessionId: "s0")
                        } catch { failures.add(1, ordering: .relaxed) }
                    }
                }
            }
        }
        #expect(failures.load(ordering: .relaxed) == 0)
        #expect(try store.all("c", as: Int.self).count == 200)
        for writer in 0 ..< 4 { #expect(try store.loadEvents(sessionId: "s\(writer)").count == 50) }
        #expect(try store.hasEvents(sessionId: "s1", before: 1))
        #expect(try !store.hasEvents(sessionId: "s1", before: 0))
    }

    @Test func prefixQueriesAndDeletes() throws {
        let (store, dir) = store()
        defer { try? FileManager.default.removeItem(at: dir) }
        try store.put("q", id: "a:1", "one")
        try store.put("q", id: "a:2", "two")
        try store.put("q", id: "b:1", "other")
        #expect(try store.entries("q", idPrefix: "a:", as: String.self).map(\.value) == ["one", "two"])
        try store.delete("q", idPrefix: "a:")
        #expect(try store.ids("q") == ["b:1"])
    }
}

/// Writes queued on a background queue keep their order, also across a locked period.
struct PendingWritesBackgroundTests {
    private final class Disk: Sendable {
        let locked = Atomic<Bool>(false)
        let written = Mutex<[Int]>([])

        func write(_ n: Int) throws {
            if locked.load(ordering: .sequentiallyConsistent) { throw StoreError(message: "locked") }
            written.withLock { $0.append(n) }
        }
    }

    @Test func backgroundWritesKeepSubmissionOrder() {
        let disk = Disk()
        let writes = PendingWrites<Int>(queue: DispatchQueue(label: "test.writes")) { try disk.write($0) }
        for n in 0 ..< 500 { #expect(writes.submit(n) == nil) }
        writes.waitUntilWritten()
        #expect(disk.written.withLock { $0 } == Array(0 ..< 500))
        #expect(writes.pending.isEmpty)
    }

    @Test func lockedBackgroundWritesWaitAndLandInOrder() {
        let disk = Disk()
        disk.locked.store(true, ordering: .sequentiallyConsistent)
        let writes = PendingWrites<Int>(queue: DispatchQueue(label: "test.writes")) { try disk.write($0) }
        (1 ... 3).forEach { writes.submit($0) }
        writes.waitUntilWritten()
        #expect(writes.pending == [1, 2, 3])
        disk.locked.store(false, ordering: .sequentiallyConsistent)
        writes.submit(4)
        writes.retry()
        writes.waitUntilWritten()
        #expect(disk.written.withLock { $0 } == [1, 2, 3, 4])
    }

    @MainActor @Test func conversationWritesLeaveTheMainThreadAndPersist() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("conv-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        let repo = ConversationRepository(store: store)
        var ids: [String] = []
        for i in 0 ..< 20 {
            var c = repo.create(incognito: false, providerId: nil, model: nil)
            c.title = "chat \(i)"
            repo.update(c)
            ids.append(c.id)
        }
        repo.delete(ids[0])
        repo.waitForWrites()
        let reread = ConversationRepository(store: store)
        reread.load()
        #expect(Set(reread.conversations.map(\.id)) == Set(ids.dropFirst()))
        #expect(reread.conversations.first { $0.id == ids[5] }?.title == "chat 5")
    }

    @MainActor @Test func sortedInsertKeepsNewestFirst() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("sort-\(UUID().uuidString)")
        let repo = ConversationRepository(store: EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager()))
        let a = repo.create(incognito: true, providerId: nil, model: nil)
        let b = repo.create(incognito: true, providerId: nil, model: nil)
        var old = repo.create(incognito: true, providerId: nil, model: nil)
        old.updatedAt = 1
        repo.update(old)
        #expect(repo.conversations.last?.id == old.id)
        var top = repo.get(a.id)!
        top.updatedAt = nowMillis() + 10_000
        repo.update(top)
        #expect(repo.conversations.map(\.id) == [a.id, b.id, old.id])
        let sorted = repo.conversations.map(\.updatedAt)
        #expect(sorted == sorted.sorted(by: >))
    }
}

/// The chat list's model is rebuilt only when an input changes.
@MainActor
struct ChatListModelTests {
    private func key(revision: Int, filter: String = "all", hidden: Set<String> = []) -> ChatListModel.Key {
        .init(revision: revision, count: 2, folders: [], providers: [], hidden: hidden, filterKey: filter, sortKey: "RECENT", hits: nil, startOfToday: 0)
    }

    @Test func memoizedOnItsInputs() {
        let conversations = [
            Conversation(id: "a", title: "A", providerId: nil, model: nil, createdAt: 1, updatedAt: 2),
            Conversation(id: "b", title: "B", providerId: nil, model: nil, createdAt: 1, updatedAt: 1),
        ]
        let lists = ChatListModel()
        var reads = 0
        let inputs = { () -> ([Conversation], [ChatFolder], [Provider]) in
            reads += 1
            return (conversations, [], [])
        }
        let first = lists.model(key(revision: 1), inputs: inputs)
        _ = lists.model(key(revision: 1), inputs: inputs)
        _ = lists.model(key(revision: 1), inputs: inputs)
        #expect(lists.builds == 1)
        #expect(reads == 1)
        #expect(first.total == 2)
        #expect(first.activeIds == ["a", "b"])

        let hidden = lists.model(key(revision: 1, hidden: ["a"]), inputs: inputs)
        #expect(lists.builds == 2)
        #expect(hidden.items.map(\.id) == ["b"])
        // The visible order changed: the list animates.
        #expect(hidden.order != first.order)
        _ = lists.model(key(revision: 2, hidden: ["a"]), inputs: inputs)
        #expect(lists.builds == 3)
    }
}

/// Parsed markdown is cached, and a streamed message re-parses only its tail.
@MainActor
struct MarkdownCacheTests {
    @Test func streamedPrefixesParseLikeTheWholeText() {
        let text = "Intro paragraph with `code`.\n\nSecond one\nstill second.\n\n```swift\nlet a = 1\n\nlet b = 2\n```\n\nAfter the fence.\n\n  \nLast `x` line"
        let cache = MarkdownCache()
        var end = text.startIndex
        while end < text.endIndex {
            end = text.index(end, offsetBy: 3, limitedBy: text.endIndex) ?? text.endIndex
            let prefix = String(text[..<end])
            #expect(cache.blocks(prefix) == LiteMarkdown.parse(prefix), "prefix \(prefix.count)")
        }
        // One entry per growing message, not one per frame.
        #expect(cache.count == 1)
    }

    @Test func finishedMessagesHitTheCache() {
        let cache = MarkdownCache(limit: 2)
        let a = cache.blocks("first")
        _ = cache.blocks("second message")
        #expect(cache.blocks("first") == a)
        _ = cache.blocks("third")
        #expect(cache.count == 2)
        cache.removeAll()
        #expect(cache.count == 0)
    }

    @Test func safeSplitSkipsBlankLinesInsideFences() {
        let text: Substring = "a\n\n```\nx\n\ny\n"
        let split = MarkdownCache.safeSplit(text)
        #expect(split.map { String(text[..<$0]) } == "a\n\n")
    }

    @Test func thinkingTailIsTheLastNonBlankLine() {
        #expect(ThinkingDisclosure.lastLine("one\n  two  \n\n  ") == "two")
        #expect(ThinkingDisclosure.lastLine("   ") == "")
        #expect(ThinkingDisclosure.lastLine("only") == "only")
    }
}

/// A session's parts are observed apart: a streamed chunk does not invalidate the transcript.
@MainActor
struct SessionSplitTests {
    final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var n = 0
        func add() { lock.withLock { n += 1 } }
        func withLock<T>(_ body: (Int) -> T) -> T { lock.withLock { body(n) } }
    }

    private func observe(_ read: @escaping () -> Void) -> Counter {
        let fired = Counter()
        withObservationTracking(read) { fired.add() }
        return fired
    }

    @Test func liveFramesLeaveTheTranscriptAlone() {
        let session = AgentSession(id: "s")
        var state = ChatSessionState(sessionId: "s")
        state.projection = SessionProjection(items: [.user(key: "e1", text: "hi", images: [])])
        session.state = state
        let transcriptReads = observe { _ = session.transcript }
        let runningReads = observe { _ = session.running }
        let liveReads = observe { _ = session.live }

        state.live = LiveState(text: "a", reasoning: "", toolNames: [], tokensPerSecond: nil)
        session.state = state
        #expect(transcriptReads.withLock { $0 } == 0)
        #expect(runningReads.withLock { $0 } == 0)
        #expect(liveReads.withLock { $0 } == 1)

        // An identical update assigns nothing.
        let isLive = observe { _ = session.isLive }
        session.state = state
        #expect(isLive.withLock { $0 } == 0)

        let again = observe { _ = session.transcript }
        state.projection.items.append(.notice(key: "e2", text: "x", kind: .info))
        session.state = state
        #expect(again.withLock { $0 } == 1)
        #expect(session.transcript.keys == ["e1", "e2"])
    }

    @Test func transcriptDerivationsAreComputedOnce() {
        let t = SessionTranscript(SessionProjection(
            items: [
                .user(key: "u", text: "go", images: []),
                .tool(.init(key: "t1", callId: "c", name: "read", summary: "", state: .ok, output: nil, code: nil, galleryIds: [], durationMs: nil)),
                .tool(.init(key: "t1", callId: "c", name: "read", summary: "", state: .ok, output: nil, code: nil, galleryIds: [], durationMs: nil)),
                .tool(.init(key: "t2", callId: "d", name: "grep", summary: "", state: .ok, output: nil, code: nil, galleryIds: [], durationMs: nil)),
            ],
            lastUsage: TokenUsage(inputTokens: 10, outputTokens: 5),
        ))
        #expect(t.items.count == 3)
        #expect(t.stepNumbers == ["t1": 1, "t2": 3])
        #expect(t.firstPrompt == "u")
        #expect(t.contextUsed == 15)
    }

    @Test func transcriptOnlyUpdateKeepsTheRest() {
        let session = AgentSession(id: "s")
        var state = ChatSessionState(sessionId: "s")
        state.running = true
        state.live = LiveState(text: "x", reasoning: "", toolNames: [], tokensPerSecond: nil)
        session.state = state
        session.apply(SessionUpdate(transcriptOnly: SessionTranscript(SessionProjection(items: [.notice(key: "n", text: "t", kind: .info)]))))
        #expect(session.running)
        #expect(session.live?.text == "x")
        #expect(session.transcript.keys == ["n"])
    }
}

/// The bridge's stream protocol: transcripts when they change, frames with text deltas.
struct SessionStreamDecoderTests {
    private func frame(turn: Int = 1, step: Int = 0, textFrom: Int, text: String, reasoningFrom: Int = 0, reasoning: String = "") -> String {
        """
        {"sessionId":"s","revision":1,"frame":1,"live":{"turn":\(turn),"step":\(step),"textFrom":\(textFrom),"text":"\(text)","textLength":0,\
        "reasoningFrom":\(reasoningFrom),"reasoning":"\(reasoning)","reasoningLength":0,"toolCalls":[{"index":0,"id":"c","name":"read","argumentsLength":12}],\
        "toolNames":["read"],"tokensPerSecond":12.5},"running":true,"approvals":[],"question":null,"toolsEnabled":false,"permission":"read-only",\
        "provider":"p","model":"m"}
        """
    }

    @Test func deltasAppendReplaceAndResync() throws {
        let d = SessionStreamDecoder()
        var r = try d.frame(frame(textFrom: 0, text: "Hé"))
        #expect(r.update.live?.text == "Hé")
        #expect(!r.resync)
        // Offsets are UTF-16 (Kotlin) lengths: "Hé" is 2.
        r = try d.frame(frame(textFrom: 2, text: " 👋", reasoningFrom: 0, reasoning: "why"))
        #expect(r.update.live?.text == "Hé 👋")
        #expect(r.update.live?.reasoning == "why")
        #expect(r.update.live?.toolNames == ["read"])
        #expect(r.update.settings == SessionSettings(toolsEnabled: false, permission: "read-only", provider: "p", model: "m"))
        #expect(r.update.transcript == nil)
        // A gap: keep the text, ask for a resync.
        r = try d.frame(frame(textFrom: 99, text: "lost", reasoningFrom: 3))
        #expect(r.resync)
        #expect(r.update.live?.text == "Hé 👋")
        // A new step starts from scratch.
        r = try d.frame(frame(step: 1, textFrom: 0, text: "next"))
        #expect(r.update.live?.text == "next")
        #expect(!r.resync)
        let idle = try d.frame(#"{"sessionId":"s","revision":1,"frame":2,"live":null,"running":false,"approvals":[],"question":null,"toolsEnabled":true,"permission":"workspace-write","provider":"p","model":"m"}"#)
        #expect(idle.update.live == nil)
        #expect(!idle.update.running)
    }

    @Test func transcriptsCapToolOutputs() throws {
        let output = String(repeating: "x", count: SessionStreamDecoder.outputCap + 500)
        let projection = SessionProjection(items: [.tool(.init(key: "t", callId: "c", name: "read", summary: "", state: .ok, output: output, code: nil, galleryIds: [], durationMs: nil))])
        let json = "{\"sessionId\":\"s\",\"revision\":3,\"projection\":" + String(decoding: try JSONEncoder().encode(projection), as: UTF8.self) + "}"
        let t = try SessionStreamDecoder().transcript(json)
        guard case .tool(let tool) = t.items.first else { Issue.record("no tool"); return }
        #expect(tool.output?.count == SessionStreamDecoder.outputCap)
    }

    @Test func boxCoalescesFramesWithoutLosingDeltas() async throws {
        let resyncs = Atomic<Int>(0)
        let received = Mutex<[SessionUpdate]>([])
        let box = SessionStreamBox(resync: { resyncs.add(1, ordering: .relaxed) }) { u in received.withLock { $0.append(u) } }
        box.onTranscript(json: #"{"sessionId":"s","revision":1,"projection":{"items":[{"type":"user","key":"e1","text":"hi","images":[]}]}}"#)
        var length = 0
        for i in 0 ..< 200 {
            box.onFrame(json: frame(textFrom: length, text: "w\(i) "))
            length += "w\(i) ".utf16.count
        }
        let expected = (0 ..< 200).map { "w\($0) " }.joined()
        for _ in 0 ..< 200 where received.withLock({ $0.last?.live?.text }) != expected {
            try await Task.sleep(for: .milliseconds(10))
        }
        let updates = received.withLock { $0 }
        #expect(updates.last?.live?.text == expected)
        #expect(updates.first?.transcript?.keys == ["e1"])
        #expect(updates.count <= 200)
        #expect(resyncs.load(ordering: .relaxed) == 0)
    }

    @Test func seqIsReadFromTheLineHead() {
        #expect(SessionStoreHost.seq(Data(#"{"seq":42,"time":1,"event":{"type":"x"}}"#.utf8)) == 42)
        #expect(SessionStoreHost.seq(Data(#"{"time":1,"seq":7}"#.utf8)) == 7)
        #expect(SessionStoreHost.seq(Data(#"{"time":1}"#.utf8)) == nil)
    }
}

/// Chat search text is kept as sealed rows next to the log.
struct SearchTextStoreTests {
    @Test func appendedTextIsSearchableWithoutTheLog() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("search-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager())
        let sessions = SessionStore(store: store)
        try sessions.append("chat", [LogEntry(seq: 0, time: 1, event: .userMessage(text: "lisbon trip", images: []))])
        try sessions.append("chat", [LogEntry(seq: 1, time: 2, event: .userMessage(text: "porto too", images: []))])
        // A fresh chat is indexed from its first append: rows, no log decryption at search time.
        #expect(try store.ids(SessionStore.searchCollection).contains("chat:base"))
        let text = SessionStore(store: store).searchText("chat")
        #expect(text.contains("lisbon") && text.contains("porto"))

        // A chat with an older log and no rows is indexed from the log once.
        try store.appendEvents(sessionId: "old", [(seq: 0, json: try SessionJSON.encode(LogEntry(seq: 0, time: 1, event: .userMessage(text: "faro", images: []))))])
        let fresh = SessionStore(store: store)
        #expect(fresh.searchTexts(["chat", "old"])["old"]?.contains("faro") == true)
        #expect(try store.ids(SessionStore.searchCollection).contains("old:base"))

        fresh.delete("chat")
        #expect(try !store.ids(SessionStore.searchCollection).contains { $0.hasPrefix("chat:") })
    }
}

/// Engines are released a while after the app went to the background, not on every switch.
@MainActor
struct EngineReleaseSchedulerTests {
    @Test func returningInTimeKeepsTheEngines() async throws {
        let released = Mutex(0)
        var ended: [UIBackgroundTaskIdentifier] = []
        let s = EngineReleaseScheduler(
            grace: .milliseconds(200), release: { released.withLock { $0 += 1 } },
            begin: { _, _ in UIBackgroundTaskIdentifier(rawValue: 7) }, end: { ended.append($0) },
        )
        s.enteredBackground()
        #expect(s.pending)
        s.becameActive()
        try await Task.sleep(for: .milliseconds(350))
        #expect(released.withLock { $0 } == 0)
        #expect(ended == [UIBackgroundTaskIdentifier(rawValue: 7)])

        s.enteredBackground()
        try await Task.sleep(for: .milliseconds(450))
        #expect(released.withLock { $0 } == 1)
        #expect(!s.pending)

        s.memoryWarning()
        try await Task.sleep(for: .milliseconds(50))
        #expect(released.withLock { $0 } == 2)
    }
}

/// Download progress stays in memory; the registry fans out only real changes.
struct ModelProgressTests {
    @Test func byteCountsAloneAreNotAChange() {
        let plan = ModelPlan(id: "m", kind: .chat, source: .catalog, title: "M", subtitle: "", format: .gguf, engine: .llama, manifest: ModelManifest(files: []))
        let a = InstalledModel(id: "m", kind: .chat, source: .catalog, title: "M", subtitle: "", format: .gguf, engine: .llama, baseModel: nil, license: nil, nsfw: false,
                               totalBytes: 10, downloadedBytes: 1, status: .downloading, error: nil, manifest: plan.manifest, addedAt: 1, updatedAt: 1, backend: .auto)
        var b = a
        b.downloadedBytes = 5
        b.updatedAt = 9
        #expect(ModelLibrary.sameExceptProgress(a, b))
        b.status = .ready
        #expect(!ModelLibrary.sameExceptProgress(a, b))
        #expect(!ModelLibrary.sameExceptProgress(nil, a))
    }
}

/// Decrypted render caches go with a panic wipe.
@MainActor
struct DecryptedCacheWipeTests {
    @Test func wipeClearsThumbnailsAndParsedText() {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).image { _ in }
        GalleryThumbs.put("wipe-test", image)
        _ = MarkdownCache.shared.blocks("secret chat text")
        #expect(GalleryThumbs.get("wipe-test") != nil)
        AppContainer.clearDecryptedCaches()
        #expect(GalleryThumbs.get("wipe-test") == nil)
        #expect(MarkdownCache.shared.count == 0)
    }
}

/// A chat opened from the list (no session yet) shows its transcript once the session opens.
@MainActor
struct BlankChatRegressionTests {
    @Test func chatRendersTheSessionOpenedInItsTask() async throws {
        let app = AppContainer()
        let nav = Navigator()
        let conversation = app.conversations.create(incognito: true, providerId: nil, model: "m")
        #expect(app.agent.existingSession(conversation.id) == nil)
        let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: ChatScreen(conversationId: conversation.id).environment(app).environment(nav).environment(\.inkStill, true).autobotTheme(.dark))
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            app.agent.close(conversation.id)
        }
        // The screen's task opens the session.
        var session: AgentSession?
        for _ in 0 ..< 100 where session == nil {
            try await Task.sleep(for: .milliseconds(20))
            session = app.agent.existingSession(conversation.id)
        }
        let opened = try #require(session)
        InkProbe.reset()
        opened.apply(SessionUpdate(transcriptOnly: SessionTranscript(SessionProjection(items: [
            .tool(.init(key: "t1", callId: "c", name: "read", summary: "notes.md", state: .ok, output: "x", code: nil, galleryIds: [], durationMs: 3)),
        ]))))
        for _ in 0 ..< 50 where (InkProbe.snapshot()["body.stepRow"] ?? 0) == 0 {
            window.rootViewController?.view.setNeedsLayout()
            window.rootViewController?.view.layoutIfNeeded()
            try await Task.sleep(for: .milliseconds(20))
        }
        // Before the fix the body never observed the session the task opened: no row rendered.
        #expect((InkProbe.snapshot()["body.stepRow"] ?? 0) > 0)
    }
}

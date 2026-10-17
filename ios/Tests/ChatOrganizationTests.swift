import AutobotShared
import Foundation
import Testing
@testable import Autobot

/// Chat organization on iOS: stored fields (old rows decode), rename / pin / archive / folders,
/// search over titles and logs, and deletion with undo.
@Suite(.serialized)
@MainActor
struct ChatOrganizationTests {
    /// Records what the library asks of the agent; nothing else is used.
    private final class FakeAgent: AgentRuntime {
        var cancelled: [String] = []
        var discarded: [String] = []
        var running: Set<String> = []
        var supportsTools: Bool { false }
        func session(_ conversationId: String) -> Autobot.AgentSession { fatalError("unused") }
        func existingSession(_ conversationId: String) -> Autobot.AgentSession? { nil }
        func close(_ conversationId: String) {}
        func preview(_ conversationId: String, state: Autobot.ChatSessionState) {}
        func send(_ conversationId: String, text: String, images: [String]) async {}
        func cancel(_ conversationId: String) { cancelled.append(conversationId) }
        func answerApproval(_ conversationId: String, callId: String, outcome: Autobot.ApprovalOutcome) {}
        func answerQuestion(_ conversationId: String, callId: String, answers: [String]?) {}
        func setToolsEnabled(_ conversationId: String, _ enabled: Bool) {}
        func setPermission(_ conversationId: String, _ preset: Autobot.PermissionPreset) {}
        func setRoute(_ conversationId: String, providerId: String, model: String) {}
        func providersChanged(_ providers: [Provider]) {}
        func compact(_ conversationId: String) {}
        func discard(_ conversationId: String) { discarded.append(conversationId) }
        func shutdown() {}
    }

    private struct Fixture {
        let store: EncryptedStore
        let conversations: ConversationRepository
        let sessions: Autobot.SessionStore
        let agent: FakeAgent
        let chats: ChatLibrary
    }

    private func fixture(window: Duration = .milliseconds(50)) -> Fixture {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("chats-\(UUID().uuidString).sqlite")
        let keys = KeyManager()
        let store = EncryptedStore(url: url, keys: keys)
        let conversations = ConversationRepository(store: store)
        let sessions = Autobot.SessionStore(store: store)
        let agent = FakeAgent()
        let chats = ChatLibrary(conversations: conversations, agent: agent, sessions: sessions, attachments: AttachmentRepository(keys: keys), window: window)
        return Fixture(store: store, conversations: conversations, sessions: sessions, agent: agent, chats: chats)
    }

    private func line(_ seq: Int64, _ type: String, _ text: String) -> (seq: Int64, json: Data) {
        let event: String = type == "user"
            ? #"{"type":"user/message","text":"\#(text)"}"#
            : #"{"type":"assistant/message","turn":0,"step":0,"text":"\#(text)"}"#
        return (seq, Data(#"{"seq":\#(seq),"time":0,"event":\#(event)}"#.utf8))
    }

    @Test func conversationsStoredBeforeOrganizationDecode() throws {
        let old = #"{"createdAt":1,"id":"c","model":"m","providerId":"p","title":"Trip","updatedAt":2}"#
        let c = try JSONDecoder.store.decode(Conversation.self, from: Data(old.utf8))
        #expect(c.title == "Trip")
        #expect(!c.pinned && !c.archived && c.folderId == nil)
        var moved = c
        moved.folderId = "f"
        moved.pinned = true
        let again = try JSONDecoder.store.decode(Conversation.self, from: JSONEncoder.store.encode(moved))
        #expect(again == moved)
    }

    @Test func renamePinArchiveAndFoldersPersist() throws {
        let f = fixture()
        let c = f.conversations.create(incognito: false, providerId: nil, model: nil)
        let updated = try #require(f.conversations.get(c.id)).updatedAt
        f.conversations.rename(c.id, "  Lisbon\nplan ")
        f.conversations.rename(c.id, "   ")
        #expect(f.conversations.get(c.id)?.title == "Lisbon plan")
        #expect(f.conversations.get(c.id)?.updatedAt == updated)
        // A renamed chat keeps its name when the first prompt arrives.
        f.conversations.titleFromPrompt(c.id, "something else")
        #expect(f.conversations.get(c.id)?.title == "Lisbon plan")

        let folder = try #require(f.conversations.createFolder(" Travel "))
        #expect(f.conversations.createFolder("travel") == nil)
        #expect(f.conversations.folderProblem("TRAVEL") != nil)
        f.conversations.setPinned([c.id], true)
        f.conversations.setArchived([c.id], true)
        f.conversations.move([c.id], to: folder.id)
        f.conversations.waitForWrites()

        let reloaded = ConversationRepository(store: f.store)
        reloaded.load()
        let stored = try #require(reloaded.get(c.id))
        #expect(stored.pinned && stored.archived && stored.folderId == folder.id)
        #expect(reloaded.folders.map(\.name) == ["Travel"])

        reloaded.deleteFolder(folder.id)
        #expect(reloaded.folders.isEmpty)
        #expect(reloaded.get(c.id)?.folderId == nil)
    }

    @Test(.timeLimit(.minutes(3))) func searchFindsMessagesWithSnippetsAndFollowsAppends() async throws {
        let f = fixture()
        let a = f.conversations.create(incognito: false, providerId: nil, model: nil, title: "Groceries")
        let b = f.conversations.create(incognito: false, providerId: nil, model: nil, title: "Rome trip")
        try f.sessions.appendJSON(a.id, [line(1, "user", "buy oat milk"), line(2, "assistant", "Added Oat milk to the list")])
        var hits = await f.chats.search("milk", in: f.conversations.conversations)
        #expect(Set(hits.keys) == [a.id])
        let hit = try #require(hits[a.id])
        let snippet = try #require(hit.snippet)
        #expect(!hit.snippetSpans.isEmpty)
        let ns = snippet as NSString
        #expect(hit.snippetSpans.allSatisfy { ns.substring(with: NSRange(location: $0.lowerBound, length: $0.count)).lowercased() == "milk" })

        #expect(Set(await f.chats.search("rome", in: f.conversations.conversations).keys) == [b.id])
        // Text appended after the first search is found too.
        try f.sessions.appendJSON(b.id, [line(1, "user", "colosseum tickets")])
        hits = await f.chats.search("colosseum", in: f.conversations.conversations)
        #expect(Set(hits.keys) == [b.id])
        #expect(await f.chats.search("   ", in: f.conversations.conversations).isEmpty)
    }

    @Test(.timeLimit(.minutes(3))) func deletionWaitsForTheUndoWindow() async throws {
        let window = Duration.milliseconds(100)
        let f = fixture(window: window)
        let keep = f.conversations.create(incognito: false, providerId: nil, model: nil)
        let gone = f.conversations.create(incognito: false, providerId: nil, model: nil)
        try f.sessions.appendJSON(gone.id, [line(1, "user", "secret")])

        let undone = f.chats.trash([keep.id], label: "x")
        #expect(f.agent.cancelled == [keep.id])
        #expect(f.chats.hidden == [keep.id])
        f.chats.undo(undone)
        #expect(f.chats.hidden.isEmpty)

        let trashed = ContinuousClock.now
        f.chats.trash([gone.id], label: "y")
        #expect(f.conversations.get(gone.id) != nil)
        // The window's timer commits the batch (never before the window, however late under load);
        // the log, search text and images then go on a background task, awaited here.
        #expect(await eventually(.seconds(60)) { f.chats.pending.isEmpty })
        #expect(ContinuousClock.now - trashed >= window)
        await f.chats.waitForPurges()
        #expect(f.conversations.get(gone.id) == nil)
        #expect(f.sessions.loadJSON(gone.id).isEmpty)
        #expect(f.agent.discarded == [gone.id])
        #expect(f.conversations.get(keep.id) != nil)
        #expect(f.chats.pending.isEmpty)

        f.chats.deleteAll()
        #expect(f.conversations.conversations.isEmpty)
        await f.chats.waitForPurges()
    }
}

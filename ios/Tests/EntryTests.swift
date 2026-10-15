import Foundation
import Testing
@testable import Autobot

struct EntryLinkTests {
    @Test func linksOpenScreensButNeverSend() {
        #expect(EntryLink.parse(URL(string: "autobot://ask?text=%20hello%20")!) == .ask(prompt: "hello", send: false))
        #expect(EntryLink.parse(URL(string: "autobot://ask")!) == .ask(prompt: nil, send: false))
        #expect(EntryLink.parse(URL(string: "autobot://voice")!) == .voice)
        #expect(EntryLink.parse(URL(string: "AUTOBOT://Imagine?prompt=a%20fox")!) == .imagine(prompt: "a fox"))
        #expect(EntryLink.parse(EntryLink.imagine) == .imagine(prompt: nil))
    }

    @Test func shareLinksTakeOnlyAUuid() {
        let id = UUID().uuidString
        #expect(EntryLink.parse(EntryLink.shared(id)) == .shared(id: id))
        #expect(EntryLink.parse(URL(string: "autobot://share?id=../../Library")!) == nil)
        #expect(EntryLink.parse(URL(string: "autobot://share")!) == nil)
    }

    @Test func foreignAndUnknownLinksAreIgnored() {
        #expect(EntryLink.parse(URL(string: "https://example.org/ask?text=hi")!) == nil)
        #expect(EntryLink.parse(URL(string: "autobot://wipe")!) == nil)
    }

    @Test func longTextIsCut() {
        let long = String(repeating: "x", count: EntryLink.maxText + 10)
        let url = URL(string: "autobot://ask?text=\(long)")!
        #expect(EntryLink.parse(url) == .ask(prompt: String(repeating: "x", count: EntryLink.maxText), send: false))
    }
}

struct EntryRoutingTests {
    private let none: (String) -> SharedInbox.Item? = { _ in nil }

    @Test func intentAskSendsLinkAskDrafts() {
        #expect(EntryRouting.plan(.ask(prompt: "hi", send: true), canChat: true, shared: none) == .chat(ChatStart(send: "hi")))
        #expect(EntryRouting.plan(.ask(prompt: "hi", send: false), canChat: true, shared: none) == .chat(ChatStart(draft: "hi")))
    }

    @Test func voiceAndImagine() {
        #expect(EntryRouting.plan(.voice, canChat: true, shared: none) == .chat(ChatStart(voice: true)))
        #expect(EntryRouting.plan(.imagine(prompt: "a fox"), canChat: false, shared: none) == .studio(prompt: "a fox"))
    }

    @Test func withoutAModelChatsGoToSetup() {
        #expect(EntryRouting.plan(.voice, canChat: false, shared: none) == .setup)
        #expect(EntryRouting.plan(.ask(prompt: "hi", send: true), canChat: false, shared: none) == .setup)
    }

    @Test func sharedContentBecomesADraftWithImages() {
        let item = SharedInbox.Item(text: "look", images: [Data([1, 2, 3])])
        #expect(EntryRouting.plan(.shared(id: "x"), canChat: true) { _ in item } == .chat(ChatStart(draft: "look", images: [Data([1, 2, 3])])))
        #expect(EntryRouting.plan(.shared(id: "x"), canChat: true, shared: none) == EntryPlan.none)
    }

    @MainActor @Test func handoffIsTakenOnce() {
        let router = EntryRouter()
        router.put(ChatStart(voice: true), for: "c1")
        #expect(router.take("c1") == ChatStart(voice: true))
        #expect(router.take("c1") == nil)
    }

    @MainActor @Test func entryOpensOnTopOfRun() {
        let nav = Navigator()
        nav.switchTo(.settings)
        nav.reset(.run, [.imagine(prompt: "a fox")])
        #expect(nav.tab == .run && nav.path == [.imagine(prompt: "a fox")])
    }
}

struct SharedInboxTests {
    private func root() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("inbox-\(UUID().uuidString)", isDirectory: true)
    }

    @Test func roundTripDeletesOnPickup() throws {
        let root = root()
        defer { try? FileManager.default.removeItem(at: root) }
        let item = SharedInbox.Item(text: "hello", images: [Data([9, 8, 7]), Data([6])])
        let id = try SharedInbox.write(item, root: root)
        #expect(SharedInbox.take(id, root: root) == item)
        #expect(SharedInbox.take(id, root: root) == nil)
        #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent(id).path))
    }

    @Test func expiredItemsAreDroppedAndSwept() throws {
        let root = root()
        defer { try? FileManager.default.removeItem(at: root) }
        let old = try SharedInbox.write(SharedInbox.Item(text: "old"), root: root)
        let later = Date().addingTimeInterval(SharedInbox.maxAge + 1)
        #expect(SharedInbox.take(old, root: root, now: later) == nil)
        let stale = try SharedInbox.write(SharedInbox.Item(text: "stale"), root: root)
        SharedInbox.sweep(root: root, now: later)
        #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent(stale).path))
    }

    @Test func onlyUuidsAreRead() throws {
        let root = root()
        defer { try? FileManager.default.removeItem(at: root) }
        #expect(SharedInbox.take("../secrets", root: root) == nil)
    }

    @Test func atMostFourImages() throws {
        let root = root()
        defer { try? FileManager.default.removeItem(at: root) }
        let id = try SharedInbox.write(SharedInbox.Item(text: nil, images: (0..<6).map { Data([UInt8($0)]) }), root: root)
        #expect(SharedInbox.take(id, root: root)?.images.count == SharedInbox.maxImages)
    }
}

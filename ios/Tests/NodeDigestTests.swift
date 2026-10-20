import AutobotShared
import Foundation
import Testing
@testable import Autobot

/// The "While you were away" card's logic on iOS: when it shows, where it starts, and that seeing it sticks.
@MainActor
@Suite
struct NodeDigestTests {
    private func defaults() -> UserDefaults {
        let name = "node-digest-tests-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        return d
    }

    private var nothing: NodeDigest {
        NodeDigests.shared.compute(activity: NodeActivity(requests: [], actions: [], facts: [], skills: [], agentProblem: nil, board: []), runs: [], since: 0, exceptDevice: nil)
    }

    @Test func theFirstLookCoversADayAndShowsTheCard() async {
        var asked: [Int64] = []
        let model = NodeDigestModel(defaults: defaults(), now: { 1_000_000 })
        await model.refresh(node: ("n1", "studio-mac")) { _, since in
            asked.append(since)
            return NodeDigests.shared.sample(now: 1_000_000, failedReceipt: false)
        }
        #expect(asked == [1_000_000 - 86_400])
        #expect(model.card?.node == "studio-mac")
        #expect(model.card?.digest.requests == 12)
    }

    @Test func dismissingMarksSeenAndTheNextLookStartsThere() async {
        var clock: Int64 = 1_000_000
        var asked: [Int64] = []
        let store = defaults()
        let model = NodeDigestModel(defaults: store, now: { clock })
        await model.refresh(node: ("n1", "studio-mac")) { _, _ in NodeDigests.shared.sample(now: 1_000_000, failedReceipt: false) }
        clock += 60
        model.markSeen()
        #expect(model.card == nil)
        #expect(model.lastSeen("n1") == 1_000_060)

        // Seen survives a new model (a relaunch); nothing new since: no card.
        let again = NodeDigestModel(defaults: store, now: { clock })
        let empty = nothing
        await again.refresh(node: ("n1", "studio-mac")) { _, since in
            asked.append(since)
            return empty
        }
        #expect(asked == [1_000_060])
        #expect(again.card == nil)
    }

    @Test func noNodeOrNoAnswerIsNoCard() async {
        let model = NodeDigestModel(defaults: defaults(), now: { 1_000_000 })
        await model.refresh(node: nil) { _, _ in NodeDigests.shared.sample(now: 1_000_000, failedReceipt: false) }
        #expect(model.card == nil)
        await model.refresh(node: ("n1", "studio-mac")) { _, _ in nil }
        #expect(model.card == nil)
    }

    @Test func markingSeenWithoutACardDoesNothing() {
        let store = defaults()
        NodeDigestModel(defaults: store, now: { 5 }).markSeen()
        #expect(NodeDigestModel(defaults: store).lastSeen("n1") == nil)
    }

    @Test func aFailedReceiptIsTheFirstLineAndAnError() {
        let lines = NodeDigestWords.shared.lines(d: NodeDigests.shared.sample(now: 1_000_000, failedReceipt: true))
        #expect(lines.first?.text == "1 receipt failed to verify ✗")
        #expect(lines.first?.tone == .error)
        #expect(NodeDigestWords.shared.needsYou(d: NodeDigests.shared.sample(now: 1_000_000, failedReceipt: false)) == "2 questions waiting")
    }

    @Test func theCardIsReadWithoutGlyphs() {
        let spoken = NodeDigestWords.shared.spoken(d: NodeDigests.shared.sample(now: 1_000_000, failedReceipt: true), node: "studio-mac")
        #expect(spoken.hasPrefix("While you were away on studio-mac. 1 receipt failed to verify."))
        #expect(!spoken.contains("✗") && !spoken.contains("✓"))
    }
}

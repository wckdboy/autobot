import AutobotShared
import Foundation
import Testing
@testable import Autobot

/// Bots mode on iOS: the sample bots, the row's words, the off confirmation, approvals by bot, the digest per bot.
@MainActor
@Suite
struct NodeBotsTests {
    private let now: Int64 = 1_800_000_000

    @Test func theDemoNodeHasThreeBotsDefaultFirst() {
        let state = BotsDemo.state(now: now)
        #expect(state.bots.map(\.name) == ["default", "ops", "support"])
        #expect(state.showList)
        #expect(state.several)
    }

    @Test func aDownChannelIsWordsFirstInTheRow() throws {
        let ops = try #require(BotsDemo.state(now: now).bot(name: "ops"))
        #expect(NodeBots.shared.kinds(bot: ops) == ["telegram", "slack"])
        #expect(NodeBots.shared.problem(bot: ops) == "slack down: token revoked ✗")
        #expect(NodeBots.shared.meta(bot: ops, now: now) == "2 queued · active 22m ago")
        #expect(NodeBots.shared.spoken(bot: ops, now: now).hasPrefix("ops, running"))
    }

    @Test func turningOffSaysWhereItStopsAnswering() {
        let state = BotsDemo.state(now: now)
        #expect(NodeBotScreen.offMessage(state.bot(name: "ops")) == "It stops answering on telegram and slack until you turn it on again.")
        // The API alone is not a place people talk to it.
        #expect(NodeBotScreen.offMessage(state.bot(name: "default")) == "It stops answering until you turn it on again.")
    }

    @Test func switchingOffStopsIt() throws {
        let ops = try #require(BotsDemo.state(now: now).bot(name: "ops"))
        let off = NodeBots.shared.switched(bot: ops, enabled: false)
        #expect(!off.enabled)
        #expect(NodeBots.shared.status(bot: off) == "off")
        #expect(NodeBots.shared.switched(bot: off, enabled: true).status == "running")
    }

    @Test func approvalsSayWhichBotWhenThereAreSeveral() {
        let approvals = NodeBots.shared.sampleApprovals(now: now)
        var n = 0
        let several = NodeAgentModel.waiting([], approvals, severalBots: true) { n += 1; return n }
        #expect(several == [.question(id: 1, approval: "a-ops-1", title: "ops · Run a command?", detail: "systemctl restart nginx", answer: nil)])
        let one = NodeAgentModel.waiting([], approvals) { n += 1; return n }
        #expect(one == [.question(id: 2, approval: "a-ops-1", title: "Run a command?", detail: "systemctl restart nginx", answer: nil)])
    }

    @Test func sessionsAreFilteredByBot() {
        let ops = NodeBots.shared.sampleSessions(now: now).filter { NodeBots.shared.of(bot: $0.bot) == "ops" }
        #expect(ops.map(\.id) == ["tg-ops-4411", "slack-ops-c07", "tg-ops-4409"])
    }

    @Test func theDigestGroupsLinesPerBot() {
        let d = NodeDigests.shared.sampleWithBots(now: now)
        #expect(NodeDigestWords.shared.bots(d: d) == ["default: 1 action", "ops: 2 actions, 2 waiting", "support: 1 action"])
        let lines = NodeDigestWords.shared.lines(d: d).map(\.text)
        #expect(lines.contains("ops: 2 actions, 2 waiting"))
        #expect(!lines.contains { $0.hasPrefix("4 actions") })
    }

    @Test func aNodeWithoutBotsShowsNoList() {
        let older = NodeBotsState(bots: [], supported: KotlinBoolean(value: false), loading: false, problem: nil)
        #expect(!older.showList)
        #expect(!older.several)
    }
}

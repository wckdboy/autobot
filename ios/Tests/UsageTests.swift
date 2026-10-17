import AutobotShared
import Foundation
import Testing
@testable import Autobot

/// The spend and quota board on iOS: the shared report decodes into the Swift model, the encrypted
/// store keeps the rows the ledger writes, plan edits go through the shared rules, receipts carry
/// their token line.
@Suite(.serialized)
@MainActor
struct UsageTests {
    private func store() -> EncryptedStore {
        EncryptedStore(url: FileManager.default.temporaryDirectory.appendingPathComponent("usage-\(UUID().uuidString).sqlite"), keys: KeyManager())
    }

    private func record(_ provider: String, _ model: String, input: Int64, output: Int64, onDevice: Bool = false, preset: String? = nil, start: Int64) -> UsageRecord {
        UsageRecord(
            start: start, span: UsageLedger.companion.FINE_SPAN, provider: provider, model: model, providerName: provider.uppercased(), presetId: preset,
            onDevice: onDevice, input: input, output: output, cacheRead: 0, cacheWrite: 0, reasoning: 0, requests: 1, unreported: 0,
        )
    }

    private let now: Int64 = 1_792_000_000_000

    private func report(plan: UsagePlan = UsagePlan(prices: [], windows: [])) throws -> UsageReportModel {
        let start = UsageLedger.companion.bucketStart(at: now - 60_000)
        let records = [
            record("anth", "claude-opus-5-5", input: 1_000_000, output: 100_000, preset: AutobotShared.ProviderPresets.shared.ANTHROPIC, start: start),
            record("local", "qwen3", input: 20_000, output: 2_000, onDevice: true, start: start),
            record("go", "glm-5", input: 40_000, output: 4_000, preset: AutobotShared.ProviderPresets.shared.OPENCODE_GO, start: start),
        ]
        let kotlin = UsageBoard.shared.report(records: records, plan: plan, now: now, zone: ZoneRulesCompanion.shared.UTC, names: ["go": "OpenCode Go"])
        return try #require(UsageReportModel.decode(UsageJson.shared.encode(report: kotlin)))
    }

    @Test func theSharedReportDecodes() throws {
        let r = try report()
        #expect(r.periods.map(\.key) == ["day", "week", "month"])
        let day = r.periods[0].sums
        #expect(day.tokens == 1_166_000)
        #expect(day.onDeviceTokens == 22_000)
        #expect(day.unpricedTokens == 44_000)
        #expect(abs(day.costUsd - 6.0) < 1e-9)
        #expect(day.costLine == "$6.00 + unknown")
        #expect(r.periods[0].providers.map(\.provider) == ["anth", "go", "local"])
        #expect(r.periods[0].providers[1].name == "OpenCode Go")
        #expect(r.series.map(\.bars.count) == [14, 8, 6])
        #expect(r.prices.map(\.source) == ["DEFAULT", "NONE"])
        #expect(UsageBoardView.priceLine(r.prices[0]) == "ANTH · in 4 · out 20 · cached 0.2 · cache write 5")
        #expect(UsageBoardView.priceLine(r.prices[1]) == "OpenCode Go · tap to enter")
        #expect(!r.empty)
    }

    @Test func windowsDecodeWithTheirResetLine() throws {
        let window = SubscriptionWindow(id: "w", provider: "go", period: .fiveHours, rolling: true, unit: .requests, limit: KotlinDouble(double: 10), anchor: nil)
        let r = try report(plan: UsagePlan(prices: [], windows: [window]))
        let w = try #require(r.windows.first)
        #expect(w.period == "FIVE_HOURS" && w.unit == "REQUESTS" && w.rolling)
        #expect(w.used == 1 && w.limit == 10 && w.fraction == 0.1)
        #expect(w.line.hasPrefix("1 requests of 10 requests · oldest use rolls off in ~5 h"))
        #expect(r.plan.windows.first?.id == "w")
    }

    @Test func theStoreKeepsWhatTheLedgerWrites() throws {
        let host = UsageStoreHost(store: store())
        let row = record("go", "glm-5", input: 5, output: 6, start: 300_000)
        host.put(id: row.id, json: UsageJson.shared.encode(record: row))
        host.put(id: "x", json: "{}") // a row that no longer decodes is skipped
        #expect(host.records() == [row])
        host.savePlan(json: UsageJson.shared.encode(plan: UsagePlan(prices: [PriceEntry(provider: "go", model: "glm-5", input: KotlinDouble(double: 1), output: KotlinDouble(double: 2), cacheRead: nil, cacheWrite: nil)], windows: [])))
        #expect(host.planObject()?.prices.first?.model == "glm-5")
        host.remove(ids: [row.id])
        #expect(host.records().isEmpty)
        host.put(id: row.id, json: UsageJson.shared.encode(record: row))
        host.removeAll()
        #expect(host.all().isEmpty)
        #expect(host.plan() != nil) // clearing the history keeps the plan
    }

    private final class FakeBridge: UsageBridging {
        var saved: [String] = []
        var cleared = 0
        func observeUsage(_ deliver: @escaping @MainActor (UsageReportModel) -> Void) -> any AutobotShared.Cancellable { NoCancel() }
        func saveUsagePlan(_ json: String) { saved.append(json) }
        func clearUsageHistory() { cleared += 1 }
    }

    private final class NoCancel: NSObject, AutobotShared.Cancellable {
        func cancel() {}
    }

    @Test func planEditsUseTheSharedRules() throws {
        let repo = UsageRepository(store: store())
        let bridge = FakeBridge()
        repo.bridge = bridge
        repo.load(try report())

        repo.savePrice(provider: "go", model: "glm-5", input: "1", output: "$3,5", cacheRead: "", cacheWrite: "nope")
        let price = try #require(UsageJson.shared.decodePlan(text: bridge.saved.last)?.prices.first)
        #expect(price.input?.doubleValue == 1 && price.output?.doubleValue == 3.5)
        #expect(price.cacheRead == nil && price.cacheWrite == nil)

        repo.addOpenCodeGoWindows(provider: "go")
        let windows = try #require(UsageJson.shared.decodePlan(text: bridge.saved.last)?.windows)
        #expect(windows.map(\.id) == ["go-5h", "go-week", "go-month"])
        #expect(windows.map(\.rolling) == [true, false, false])

        repo.saveWindow(.init(id: "w1", provider: "go", period: "WEEK", rolling: false, unit: "USD", limit: 30, anchor: 5))
        let saved = try #require(UsageJson.shared.decodePlan(text: bridge.saved.last)?.windows.first)
        #expect(saved.period == .week && saved.unit == .usd && saved.limit?.doubleValue == 30 && saved.anchor?.int64Value == 5)

        repo.clearHistory()
        #expect(bridge.cleared == 1)
    }

    @Test func receiptsCarryTheirTokenLine() throws {
        let json = #"""
        {"type":"assistant","key":"e1","text":"ok","receipt":{"turn":1,"line":"1 host · 2 KB out","onDevice":false,"tracked":true,
          "models":[{"provider":"anth","model":"claude-opus-5-5","usage":{"inputTokens":10000,"outputTokens":1000},"requests":2,"costUsd":0.0296}],
          "usage":"10k in · 1.0k out · 8.0k cached · $0.0296",
          "usageRows":[{"model":"claude-opus-5-5","line":"10k in · 1.0k out · 8.0k cached · $0.0296"}]}}
        """#
        let item = try JSONDecoder().decode(SessionItem.self, from: Data(json.utf8))
        guard case .assistant(_, _, _, _, _, _, let r?, _, _) = item else { Issue.record("expected a receipt"); return }
        #expect(r.usage == "10k in · 1.0k out · 8.0k cached · $0.0296")
        #expect(r.usageLine("claude-opus-5-5") == r.usage)
        #expect(r.usageLine("other") == nil)
    }

    @Test func receiptsFromOlderBuildsHaveNoTokenLine() throws {
        let item = try JSONDecoder().decode(SessionItem.self, from: Data(#"{"type":"assistant","key":"e2","text":"x","receipt":{"turn":1,"line":"on device"}}"#.utf8))
        guard case .assistant(_, _, _, _, _, _, let r?, _, _) = item else { Issue.record("expected a receipt"); return }
        #expect(r.usage == nil && r.usageRows.isEmpty)
    }

    @Test func usageIsInTheSettingsAgentSection() {
        #expect(SettingsSection.agent.rows.contains(.usage))
        #expect(SettingsRow.usage.title() == "Usage")
    }

    /// Perf (`AUTOBOT_PERF`): the board's report for a month of use (6 providers × 4 models, a
    /// bucket a day), its JSON size and decode time, which no longer runs on the main actor.
    @Test(.timeLimit(.minutes(5))) func usageReport() async throws {
        guard ProcessInfo.processInfo.environment["AUTOBOT_PERF"] != nil else { return }
        let now: Int64 = 1_792_000_000_000
        var records: [UsageRecord] = []
        for day in 0 ..< 30 {
            let start = UsageLedger.companion.bucketStart(at: now - Int64(day) * 86_400_000 - 60_000)
            for p in 0 ..< 6 {
                for m in 0 ..< 4 {
                    records.append(UsageRecord(
                        start: start, span: UsageLedger.companion.FINE_SPAN, provider: "p\(p)", model: "model-\(p)-\(m)", providerName: "Provider \(p)", presetId: nil,
                        onDevice: p == 0, input: 10_000, output: 1_000, cacheRead: 500, cacheWrite: 0, reasoning: 0, requests: 3, unreported: 0,
                    ))
                }
            }
        }
        let kotlin = UsageBoard.shared.report(records: records, plan: UsagePlan(prices: [], windows: []), now: now, zone: ZoneRulesCompanion.shared.UTC, names: [:])
        let json = UsageJson.shared.encode(report: kotlin)
        let clock = ContinuousClock()
        var decoded: UsageReportModel?
        let n = 20
        let decode = clock.measure { for _ in 0 ..< n { decoded = UsageReportModel.decode(json) } }
        #expect(decoded != nil)
        print("PERF usage.decode bytes=\(json.utf8.count) perReport=\(decode / n) (off the main actor since the perf pass)")
    }
}

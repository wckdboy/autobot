import AutobotShared
import Foundation
import Observation
import Synchronization

/**
 The spend and quota board's report as the shared runtime sends it (Kotlin `UsageReport`, from
 `UsageBoard`): every number is computed on this phone from the providers' usage reports.
 */
struct UsageReportModel: Codable, Equatable, Sendable {
    struct Sums: Codable, Equatable, Sendable {
        var tokens: Int64 = 0
        var input: Int64 = 0
        var output: Int64 = 0
        var cacheRead: Int64 = 0
        var cacheWrite: Int64 = 0
        var reasoning: Int64 = 0
        var requests: Int = 0
        var onDeviceTokens: Int64 = 0
        var costUsd: Double = 0
        var unpricedTokens: Int64 = 0
        var unreported: Int = 0

        /// `$1.24`, `$1.24 + unknown`, `cost unknown` (Kotlin `UsageSums.costLine`).
        var costLine: String { UsageFormat.shared.cost(usd: costUsd, partlyUnknown: unpricedTokens > 0) }
    }

    struct Group: Codable, Equatable, Sendable, Identifiable {
        var provider: String
        var name: String
        var model: String?
        var onDevice: Bool
        var sums: Sums
        var id: String { provider + "|" + (model ?? "") }
    }

    struct Period: Codable, Equatable, Sendable {
        var key: String
        var label: String
        var start: Int64
        var sums: Sums
        var providers: [Group]
        var models: [Group]
    }

    struct Bar: Codable, Equatable, Sendable {
        var label: String
        var start: Int64
        var tokens: Int64
        var onDeviceTokens: Int64
        var costUsd: Double
        var fraction: Double
    }

    struct Series: Codable, Equatable, Sendable {
        var key: String
        var label: String
        var bars: [Bar]
    }

    struct Window: Codable, Equatable, Sendable, Identifiable {
        var id: String
        var provider: String
        var providerName: String
        var period: String
        var rolling: Bool
        var unit: String
        var limit: Double?
        var used: Double
        var fraction: Double?
        var start: Int64
        var resetsAt: Int64?
        var resetsInMs: Int64?
        var line: String
        var over: Bool
    }

    struct PriceRow: Codable, Equatable, Sendable, Identifiable {
        var provider: String
        var providerName: String
        var presetId: String?
        var model: String
        var input: Double?
        var output: Double?
        var cacheRead: Double?
        var cacheWrite: Double?
        /// `YOURS`, `DEFAULT` (published, shipped) or `NONE`.
        var source: String
        var id: String { provider + "|" + model }
    }

    /// The user's price table and windows (Kotlin `UsagePlan`), edited through the Kotlin type.
    struct Plan: Codable, Equatable, Sendable {
        struct Price: Codable, Equatable, Sendable {
            var provider: String
            var model: String
            var input: Double?
            var output: Double?
            var cacheRead: Double?
            var cacheWrite: Double?
        }

        struct Window: Codable, Equatable, Sendable, Identifiable {
            var id: String
            var provider: String
            /// `FIVE_HOURS`, `DAY`, `WEEK`, `MONTH`.
            var period: String
            var rolling: Bool = true
            /// `TOKENS`, `REQUESTS`, `USD`.
            var unit: String = "TOKENS"
            var limit: Double?
            var anchor: Int64?
        }

        var prices: [Price] = []
        var windows: [Window] = []
    }

    var now: Int64
    var periods: [Period]
    var series: [Series]
    var windows: [Window]
    var prices: [PriceRow]
    var plan: Plan
    var empty: Bool

    static func decode(_ json: String) -> UsageReportModel? {
        try? JSONDecoder().decode(UsageReportModel.self, from: Data(json.utf8))
    }
}

/**
 Usage buckets and the plan sealed in the [EncryptedStore] (collections `usage` and
 `usage-plan`), stored as the shared runtime's JSON. The agent bridge calls this on its
 background threads (`UsageStoreBridge`); a panic wipe deletes it with the store.
 */
final class UsageStoreHost: NSObject, UsageStoreBridge, @unchecked Sendable {
    static let collection = "usage"
    static let planCollection = "usage-plan"
    private static let planId = "plan"
    private let store: EncryptedStore

    init(store: EncryptedStore) { self.store = store }

    func all() -> [String] { (try? store.all(Self.collection, as: String.self)) ?? [] }

    func put(id: String, json: String) { try? store.put(Self.collection, id: id, json) }

    func remove(ids: [String]) { for id in ids { try? store.delete(Self.collection, id: id) } }

    func removeAll() { for id in (try? store.ids(Self.collection)) ?? [] { try? store.delete(Self.collection, id: id) } }

    func plan() -> String? { try? store.get(Self.planCollection, id: Self.planId, as: String.self) }

    func savePlan(json: String) { try? store.put(Self.planCollection, id: Self.planId, json) }

    /// Every bucket as the shared type, for the export.
    func records() -> [UsageRecord] { all().compactMap { UsageJson.shared.decodeRecord(text: $0) } }

    /// The plan as the shared type, for the export (nil when none was saved).
    func planObject() -> UsagePlan? { UsageJson.shared.decodePlan(text: plan()) }
}

/// The bridge calls the runtime this way (the Usage screen does not know the runtime type).
@MainActor
protocol UsageBridging: AnyObject {
    /// Reports as they change, decoded off the main thread (the bridge's callback thread).
    func observeUsage(_ deliver: @escaping @MainActor (UsageReportModel) -> Void) -> any AutobotShared.Cancellable
    func saveUsagePlan(_ json: String)
    func clearUsageHistory()
}

/**
 The Usage screen's state (port of Android's `UsageViewModel`): the latest report while the
 screen observes it, and edits to the price table and windows, made with the shared
 `UsagePlan` so both platforms apply the same rules.
 */
@MainActor
@Observable
final class UsageRepository {
    private(set) var report: UsageReportModel?
    @ObservationIgnored let host: UsageStoreHost
    @ObservationIgnored weak var bridge: (any UsageBridging)?
    @ObservationIgnored private var handle: (any AutobotShared.Cancellable)?
    @ObservationIgnored private var observers = 0

    init(store: EncryptedStore) { host = UsageStoreHost(store: store) }

    /// The screen appeared: reports follow until [stop].
    func start() {
        observers += 1
        guard handle == nil, let bridge else { return }
        handle = bridge.observeUsage { [weak self] next in
            guard let self, next != report else { return }
            report = next
        }
    }

    func stop() {
        observers = max(0, observers - 1)
        guard observers == 0 else { return }
        handle?.cancel()
        handle = nil
    }

    private var plan: UsagePlan {
        let json = (try? JSONEncoder().encode(report?.plan ?? .init())).flatMap { String(data: $0, encoding: .utf8) }
        return UsageJson.shared.decodePlan(text: json) ?? UsagePlan(prices: [], windows: [])
    }

    private func save(_ plan: UsagePlan) { bridge?.saveUsagePlan(UsageJson.shared.encode(plan: plan)) }

    /// The price of [model] on [provider] from the editor's fields (blank: unknown).
    func savePrice(provider: String, model: String, input: String, output: String, cacheRead: String, cacheWrite: String) {
        let f = UsageFormat.shared
        save(plan.withPrice(entry: PriceEntry(
            provider: provider, model: model,
            input: f.parsePrice(text: input), output: f.parsePrice(text: output),
            cacheRead: f.parsePrice(text: cacheRead), cacheWrite: f.parsePrice(text: cacheWrite),
        )))
    }

    func saveWindow(_ w: UsageReportModel.Plan.Window) {
        let window = SubscriptionWindow(
            id: w.id, provider: w.provider,
            period: WindowPeriod.entries.first { $0.name == w.period } ?? .fiveHours,
            rolling: w.rolling,
            unit: LimitUnit.entries.first { $0.name == w.unit } ?? .tokens,
            limit: w.limit.map { KotlinDouble(double: $0) },
            anchor: w.anchor.map { KotlinLong(longLong: $0) },
        )
        save(plan.withWindow(window: window))
    }

    func deleteWindow(_ id: String) { save(plan.withoutWindow(id: id)) }

    /// OpenCode Go's 5 h / week / month windows for [provider] (limits left to fill in).
    func addOpenCodeGoWindows(provider: String) {
        var next = plan
        for w in UsagePlan.companion.openCodeGoWindows(provider: provider, now: nowMillis()) where !next.windows.contains(where: { $0.id == w.id }) {
            next = next.withWindow(window: w)
        }
        save(next)
    }

    func clearHistory() { bridge?.clearUsageHistory() }

    /// For tests: shows [report] without a bridge.
    func load(_ report: UsageReportModel?) { self.report = report }
}

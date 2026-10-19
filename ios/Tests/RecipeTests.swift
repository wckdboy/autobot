import AutobotShared
import Foundation
import Testing
import UserNotifications
@testable import Autobot

/// Scheduled recipes on iOS: the JSON mirror, the store, the runner's gates, the background
/// request, notification privacy and the CPU in the background.
@Suite(.serialized)
@MainActor
struct RecipeTests {
    // MARK: Fakes

    final class Host: RecipeHost {
        var protectedDataAvailable = true
        var appActive = true
        var charging = false
        var batteryLow = false
        var local: String? = "qwen"
        var models = ["qwen", "llama"]
        var chats: [(title: String, model: String)] = []
        var runs: [(session: String, model: String, recipe: String, context: String?, manual: Bool)] = []
        var plans: [RecipeBackground.Plan?] = []
        var cpuDuringRun: Bool?
        var answer = "Five headlines."
        /// Runs wait until cancelled ([cancelRun]).
        var hang = false
        var cancelled: [String] = []
        private var held: CheckedContinuation<String, Never>?
        private var heldRecord = ""

        func defaultLocalModel() -> String? { local }
        func localModels() -> [String] { models }
        func newResultChat(title: String, model: String) -> String {
            chats.append((title, model))
            return "chat-\(chats.count)"
        }
        func recentChats(since: Int64) -> String { "digest since \(since)" }
        func run(sessionId: String, model: String, recipe: String, context: String?, startedAt: Int64, manual: Bool) async -> String {
            runs.append((sessionId, model, recipe, context, manual))
            cpuDuringRun = RecipeBackground.cpuOnly
            var r = RecipeRunDoc(startedAt: startedAt, finishedAt: startedAt + 1000, status: "DONE", sessionId: sessionId, summary: answer)
            r.trigger = manual ? "MANUAL" : "SCHEDULE"
            guard hang else { return r.json }
            r.status = "FAILED"
            heldRecord = r.json
            return await withCheckedContinuation { held = $0 }
        }
        func schedule(_ plan: RecipeBackground.Plan?) { plans.append(plan) }

        // A paired node: what it was sent, and runs it has finished.
        var pushed: [String] = []
        var nodeRunsWaiting: [String] = []
        var delivered: [(run: String, chat: String)] = []
        func nodePush(_ recipe: String) async -> (json: String?, error: String?) {
            pushed.append(recipe)
            var r = RecipeDoc(json: recipe)!
            r.state.pushed = "sent"
            return (r.json, nil)
        }
        func nodeRuns(_ recipe: String) async -> (runs: [String]?, error: String?) {
            defer { nodeRunsWaiting = [] }
            return (nodeRunsWaiting, nil)
        }
        func newNodeChat(title: String, providerId: String) -> String {
            chats.append((title, providerId))
            return "chat-\(chats.count)"
        }
        func nodeDeliver(_ recipe: String, run: String, chatId: String) async -> String? {
            delivered.append((run, chatId))
            return RecipeRunDoc(startedAt: 1_000_000, finishedAt: 1_060_000, status: "DONE", sessionId: chatId, summary: "Disk is 41% full.").json
        }
        func cancelRun(sessionId: String) {
            cancelled.append(sessionId)
            held?.resume(returning: heldRecord)
            held = nil
        }
    }

    final class Notes: RecipeNotifying {
        var finished: [(name: String, record: String)] = []
        var unlock: [(id: String, name: String)] = []
        var withdrawn: [String] = []
        func requestAuthorization() async {}
        func finished(_ recipe: RecipeDoc, record: String) { finished.append((recipe.name, record)) }
        func unlockToRun(id: String, name: String) { unlock.append((id, name)) }
        func withdrawUnlock(_ ids: [String]) { withdrawn += ids }
        func removeAll() {}
    }

    private func temp(_ name: String) -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("recipes-\(name)-\(UUID().uuidString)") }

    private func repository(_ defaults: UserDefaults? = nil) -> RecipeRepository {
        let d = defaults ?? UserDefaults(suiteName: "recipes-\(UUID().uuidString)")!
        return RecipeRepository(store: EncryptedStore(url: temp("db").appendingPathExtension("sqlite"), keys: KeyManager()), defaults: d)
    }

    private func center(_ host: Host, _ notes: Notes, repository: RecipeRepository? = nil, index: URL? = nil) -> RecipeCenter {
        let c = RecipeCenter(repository: repository ?? self.repository(), notifier: notes, indexURL: index ?? temp("index"))
        c.host = host
        return c
    }

    private let brief = RecipeDoc(id: "r1", name: "Brief", prompt: "Summarise", schedule: .daily(minuteOfDay: 420))

    // MARK: Mirror

    @Test func theMirrorRoundTripsTheSharedJson() throws {
        for json in RecipeBridge.shared.templates(nowMillis: 5) {
            let doc = try #require(RecipeDoc(json: json))
            #expect(RecipeDoc(json: doc.json) == doc)
            // Kotlin reads what Swift writes, and keeps it.
            #expect(RecipeBridge.shared.problems(json: doc.json).isEmpty)
            #expect(RecipeDoc(json: RecipeBridge.shared.reconcile(json: doc.json, nowMillis: 5)) == doc)
        }
        var weekly = brief
        weekly.schedule = .weekly(dayOfWeek: 5, minuteOfDay: 1080)
        weekly.state.lastRun = RecipeRunDoc(startedAt: 1, finishedAt: 2, status: "DONE", sessionId: "s", summary: "ok")
        #expect(RecipeRules.scheduleLabel(weekly) == "Fridays at 18:00")
        #expect(RecipeDoc(json: weekly.json) == weekly)
        var hours = brief
        hours.schedule = .hours(6)
        #expect(RecipeRules.scheduleLabel(hours) == "Every 6 hours")
        #expect(RecipeScheduleDoc.daily(minuteOfDay: 555).with(kind: .weekly) == .weekly(dayOfWeek: 1, minuteOfDay: 555))
    }

    @Test func savingEnabledComputesTheNextRunAndDropsUnofferedTools() throws {
        var r = brief
        r.enabled = true
        r.tools = ["delete", "web_fetch", "read"]
        let saved = RecipeRules.saved(r, previous: nil, now: 0)
        #expect(saved.tools == ["read", "web_fetch"])
        #expect((saved.state.nextRunAt ?? 0) > 0)
        #expect(saved.usesNetwork)
    }

    // MARK: Store

    @Test func templatesAreSeededOnceInOrderAndDisabled() {
        let defaults = UserDefaults(suiteName: "recipes-\(UUID().uuidString)")!
        let repo = repository(defaults)
        let first = repo.load(now: 10)
        #expect(first.map(\.name) == ["Morning brief from my feeds", "Weekly notes tidy", "Daily digest of my chats"])
        #expect(first.allSatisfy { !$0.enabled })
        repo.delete(first[0].id)
        // Deleted templates stay deleted.
        #expect(repo.load(now: 20).count == 2)
    }

    @Test func theLockIndexHoldsOnlyScheduleFacts() throws {
        let url = temp("index")
        var r = RecipeRules.saved({ var x = brief; x.enabled = true; return x }(), previous: nil, now: 0)
        r.prompt = "secret prompt"
        RecipeIndex([r]).write(to: url)
        let text = try String(contentsOf: url, encoding: .utf8)
        #expect(!text.contains("secret prompt"))
        #expect(text.contains("Brief"))
        let index = try #require(RecipeIndex.read(from: url))
        #expect(index.due(now: r.state.nextRunAt!).map(\.id) == ["r1"])
        #expect(index.due(now: r.state.nextRunAt! - 3_600_000).isEmpty)
    }

    // MARK: Runner

    @Test func aDueRecipeRunsOnceIntoANewChatAndNotifies() async throws {
        let host = Host(), notes = Notes()
        let c = center(host, notes)
        c.load(now: 0)
        var r = brief
        r.enabled = true
        r.includeRecentChats = true
        #expect(c.save(r, now: 0).isEmpty)
        let due = try #require(c.recipe("r1")?.state.nextRunAt)
        c.runDue(now: due)
        await c.idle()
        #expect(host.runs.count == 1)
        #expect(host.runs[0].model == "qwen")
        #expect(host.runs[0].context?.hasPrefix("digest since") == true)
        #expect(host.runs[0].manual == false)
        #expect(host.chats.count == 1)
        #expect(notes.finished.map(\.name) == ["Brief"])
        let after = try #require(c.recipe("r1"))
        #expect(after.state.lastRun?.sessionId == "chat-1")
        #expect((after.state.nextRunAt ?? 0) > due)
        // Nothing else is due: no second run.
        c.runDue(now: due + 60_000)
        await c.idle()
        #expect(host.runs.count == 1)
    }

    @Test func aRecipeOnANodeIsSentThereNeverRunHereAndItsRunsAreCollected() async throws {
        let host = Host(), notes = Notes()
        let c = center(host, notes)
        c.load(now: 0)
        var r = brief
        r.enabled = true
        r.runsOn = "node-1"
        #expect(c.save(r, now: 0).isEmpty)
        for _ in 0..<100 where c.recipe("r1")?.state.pushed == nil { try await Task.sleep(for: .milliseconds(20)) }
        #expect(host.pushed.count == 1)
        #expect(c.recipe("r1")?.state.pushed == "sent")
        // Due by the clock, but the node runs it: the phone does not.
        let due = try #require(c.recipe("r1")?.state.nextRunAt)
        c.runDue(now: due)
        await c.idle()
        #expect(host.runs.isEmpty)
        #expect(RecipeRules.nextWake(c.recipes) == nil)
        // The node finished a run: it becomes a chat on the node, a record and a notification.
        host.nodeRunsWaiting = [#"{"at":1000,"finished":1060,"ok":true,"result":"Disk is 41% full.","receipts":["a"],"trigger":"schedule"}"#]
        await c.syncNodesNow()
        #expect(host.delivered.map(\.chat) == ["chat-1"])
        #expect(host.chats.map(\.model) == ["node-1"])
        #expect(notes.finished.map(\.name) == ["Brief"])
        #expect(c.recipe("r1")?.state.lastRun?.sessionId == "chat-1")
        #expect(c.recipe("r1")?.state.lastRun?.summary == "Disk is 41% full.")
        #expect(c.nodeNotice == nil)
    }

    @Test func noOnDeviceModelFailsTheRunWithoutAChat() async throws {
        let host = Host(), notes = Notes()
        host.local = nil
        let c = center(host, notes)
        c.load(now: 0)
        c.save(brief, now: 0)
        c.runNow("r1")
        await c.idle()
        #expect(host.runs.isEmpty)
        #expect(host.chats.isEmpty)
        let record = try #require(c.recipe("r1")?.state.lastRun)
        #expect(record.status == "FAILED")
        #expect(record.summary == RecipeRules.copy.NO_LOCAL_MODEL)
        #expect(record.trigger == "MANUAL")
        #expect(notes.finished.first?.name == "Brief")
    }

    @Test func aRemovedModelIsNamed() {
        let host = Host()
        var r = brief
        r.model = "gone"
        #expect(RecipeCenter.model(for: r, host: host) == .failure(.init(reason: RecipeRules.copy.modelMissing(model: "gone"))))
        r.model = "llama"
        #expect(RecipeCenter.model(for: r, host: host) == .success("llama"))
        r.model = ""
        #expect(RecipeCenter.model(for: r, host: host) == .success("qwen"))
    }

    @Test func aLockedRunWaitsForTheUnlockAndSaysSo() async throws {
        let host = Host(), notes = Notes()
        let c = center(host, notes)
        c.load(now: 0)
        c.save(brief, now: 0)
        host.protectedDataAvailable = false
        c.runNow("r1")
        await c.idle()
        #expect(host.runs.isEmpty)
        #expect(notes.unlock.map(\.name) == ["Brief"])
        #expect(RecipeRules.copy.unlockToRun(name: "Brief") == "Unlock to run Brief")
        #expect(c.recipe("r1")?.state.waitingForUnlock == true)
    }

    @Test func chargingRecipesWaitForPower() async throws {
        let host = Host(), notes = Notes()
        let c = center(host, notes)
        c.load(now: 0)
        var r = brief
        r.enabled = true
        r.requiresCharging = true
        c.save(r, now: 0)
        let due = try #require(c.recipe("r1")?.state.nextRunAt)
        c.runDue(now: due)
        await c.idle()
        #expect(host.runs.isEmpty)
        host.charging = true
        c.runDue(now: due)
        await c.idle()
        #expect(host.runs.count == 1)
    }

    @Test func aBackgroundRunUsesTheCpu() async throws {
        let host = Host(), notes = Notes()
        let c = center(host, notes)
        c.load(now: 0)
        c.save(brief, now: 0)
        host.appActive = false
        c.runNow("r1")
        await c.idle()
        #expect(host.cpuDuringRun == true)
        #expect(!RecipeBackground.cpuOnly)
        host.appActive = true
        c.runNow("r1")
        await c.idle()
        #expect(host.cpuDuringRun == false)
        #expect(RecipeBackground.backend(.gpu, cpuOnly: true) == .cpu)
        #expect(RecipeBackground.backend(.gpu, cpuOnly: false) == .gpu)
    }

    @Test func stoppingCancelsTheRunningTurn() async throws {
        let host = Host(), notes = Notes()
        let c = center(host, notes)
        c.load(now: 0)
        c.save(brief, now: 0)
        host.hang = true
        c.runNow("r1")
        for _ in 0..<200 where host.runs.isEmpty { await Task.yield() }
        #expect(c.running == "r1")
        // Background expiry or a panic wipe: the headless turn is stopped, not left running.
        c.stop()
        await c.idle()
        #expect(host.cancelled == ["chat-1"])
        #expect(c.running == nil)
    }

    // MARK: Background request

    @Test func theRequestWaitsForTheNextRunAndItsNeeds() {
        var a = brief
        a.enabled = true
        a.state.nextRunAt = 10_000_000
        a.tools = ["web_fetch"]
        var b = brief
        b.id = "r2"
        b.enabled = true
        b.requiresCharging = true
        b.state.nextRunAt = 20_000_000
        let plan = RecipeBackground.plan([a, b], now: 0)
        #expect(plan == RecipeBackground.Plan(earliest: 10_000_000, requiresPower: false, requiresNetwork: true))
        // Only charging recipes in the window: the task asks for power.
        #expect(RecipeBackground.plan([b], now: 0) == RecipeBackground.Plan(earliest: 20_000_000, requiresPower: true, requiresNetwork: false))
        #expect(RecipeBackground.plan([brief], now: 0) == nil)
        var waiting = brief
        waiting.enabled = true
        waiting.state = RecipeStateDoc(nextRunAt: 5, waitingForUnlock: true)
        #expect(RecipeBackground.plan([waiting], now: 100)?.earliest == 100 + RecipeBackground.retryMs)
    }

    @Test func aLockedTaskNotifiesOncePerDueRecipe() throws {
        let url = temp("index")
        var r = RecipeRules.saved({ var x = brief; x.enabled = true; return x }(), previous: nil, now: 0)
        r.name = "Brief"
        RecipeIndex([r]).write(to: url)
        let notes = Notes()
        let due = r.state.nextRunAt!
        let plan = RecipeCenter.lockedTask(now: due, indexURL: url, notifier: notes)
        #expect(notes.unlock.map(\.name) == ["Brief"])
        #expect(plan?.earliest == due + RecipeBackground.retryMs)
        _ = RecipeCenter.lockedTask(now: due + 10, indexURL: url, notifier: notes)
        #expect(notes.unlock.count == 1)
        // After the unlock the center marks it waiting, so it runs on open.
        let host = Host()
        let repo = repository()
        try repo.save(r)
        let c = center(host, Notes(), repository: repo, index: url)
        c.load(now: due + 20)
        #expect(c.recipe("r1")?.state.waitingForUnlock == true)
    }

    // MARK: Notifications

    @Test func notificationsCarryOnlyTheNameUnlessAskedTo() {
        var r = brief
        r.name = "Brief"
        let record = RecipeRunDoc(startedAt: 0, finishedAt: 1, status: "DONE", sessionId: "s1", summary: "Five headlines").json
        let quiet = RecipeNotifier.content(r, record: record, showSummary: false)
        #expect(quiet.title == "Recipe Brief finished")
        #expect(quiet.body == RecipeRules.copy.OPEN_TO_READ)
        #expect(quiet.categoryIdentifier == RecipeNotifier.category)
        #expect(quiet.userInfo["session"] as? String == "s1")
        let loud = RecipeNotifier.content(r, record: record, showSummary: true)
        #expect(loud.body == "Five headlines")
        let failed = RecipeNotifier.content(r, record: RecipeRunDoc(startedAt: 0, finishedAt: 1, status: "FAILED").json, showSummary: false)
        #expect(failed.title == "Recipe Brief didn't finish")
        let unlock = RecipeNotifier.unlockContent(id: "r1", name: "Brief")
        #expect(unlock.title == "Unlock to run Brief")
        #expect(unlock.body == RecipeRules.copy.UNLOCK_BODY)
    }

    @Test func aNotificationTapOpensTheResultChat() {
        #expect(EntryRouting.plan(.recipe(conversationId: "c1"), canChat: false, shared: { _ in nil }) == .recipe(conversationId: "c1"))
        #expect(EntryRouting.plan(.recipe(conversationId: nil), canChat: true, shared: { _ in nil }) == .recipe(conversationId: nil))
    }
}

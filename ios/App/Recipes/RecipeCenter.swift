import Foundation
import Observation

/// What a recipe run needs from the app (the real one is [AppRecipeHost]; tests pass fakes).
@MainActor
protocol RecipeHost: AnyObject {
    /// Whether the encrypted store can be read now (the phone is unlocked).
    var protectedDataAvailable: Bool { get }
    /// Whether the app is in the foreground (a background run uses the CPU).
    var appActive: Bool { get }
    var charging: Bool { get }
    var batteryLow: Bool { get }
    /// "This phone"'s default model, or nil when there is no on-device provider.
    func defaultLocalModel() -> String?
    /// Installed on-device chat model ids.
    func localModels() -> [String]
    /// Creates the result chat (in the Recipes folder) and returns its id.
    func newResultChat(title: String, model: String) -> String
    /// The digest of chats changed since [since] (not incognito, not recipe results).
    func recentChats(since: Int64) -> String
    /// Runs the recipe headless and answers with the run's record JSON.
    func run(sessionId: String, model: String, recipe: String, context: String?, startedAt: Int64, manual: Bool) async -> String
    /// Asks the system to wake the app for [plan].
    func schedule(_ plan: RecipeBackground.Plan?)
    /// Stops the headless turn running in [sessionId] (its run then ends as stopped).
    func cancelRun(sessionId: String)

    // A recipe on a paired node (Invincible): the node runs it; the phone sends it and collects the runs.
    /// Sends [recipe] (JSON) to its node: the recipe JSON to store, or why not.
    func nodePush(_ recipe: String) async -> (json: String?, error: String?)
    func nodeRemove(_ recipe: String) async
    func nodeRunNow(_ recipe: String) async -> String?
    /// The node's runs the phone has not collected (JSON each), or why not.
    func nodeRuns(_ recipe: String) async -> (runs: [String]?, error: String?)
    /// A result chat in the Recipes folder, on the node's provider.
    func newNodeChat(title: String, providerId: String) -> String
    /// Writes [run] into [chatId] with the node's receipts checked: the run's record JSON.
    func nodeDeliver(_ recipe: String, run: String, chatId: String) async -> String?
}

extension RecipeHost {
    func cancelRun(sessionId: String) {}
    func nodePush(_ recipe: String) async -> (json: String?, error: String?) { (nil, "No node here") }
    func nodeRemove(_ recipe: String) async {}
    func nodeRunNow(_ recipe: String) async -> String? { "No node here" }
    func nodeRuns(_ recipe: String) async -> (runs: [String]?, error: String?) { (nil, "No node here") }
    func newNodeChat(title: String, providerId: String) -> String { "" }
    func nodeDeliver(_ recipe: String, run: String, chatId: String) async -> String? { nil }
}

/**
 The Recipes screen's model and the runner (ROADMAP Phase 2.5 item 7). Recipes are loaded with
 the rest of the encrypted data; due ones run when the app is opened and when iOS gives the
 background task time. Runs are serial: one recipe at a time, each into a new chat in the Recipes
 folder, on the on-device model only.
 */
@MainActor
@Observable
final class RecipeCenter {
    private(set) var recipes: [RecipeDoc] = []
    private(set) var loaded = false
    /// The recipe running now.
    private(set) var running: String?
    /// Recipes queued behind it (Run now while another runs), with whether they were asked for by hand.
    private(set) var queue: [(id: String, manual: Bool)] = []
    private var queued: [String] { queue.map(\.id) }

    @ObservationIgnored private let repository: RecipeRepository
    @ObservationIgnored private let notifier: any RecipeNotifying
    @ObservationIgnored weak var host: (any RecipeHost)?
    @ObservationIgnored private let indexURL: URL
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var asked = false
    /// The result chat of the run in progress (stop() cancels its turn).
    @ObservationIgnored private var runningSession: String?
    /// Why a recipe's node could not be reached (it is tried again at the next check).
    private(set) var nodeNotice: String?
    @ObservationIgnored private var nodeSync: Task<Void, Never>?

    init(repository: RecipeRepository, notifier: any RecipeNotifying, indexURL: URL = RecipeIndex.url) {
        self.repository = repository
        self.notifier = notifier
        self.indexURL = indexURL
    }

    /// Reads the recipes (after the data unlock), applies what a locked background task noted,
    /// brings next runs up to date (time zone changes) and reschedules.
    func load(now: Int64 = nowMillis()) {
        let index = RecipeIndex.read(from: indexURL)
        var list = repository.load(now: now).map { RecipeRules.reconcile($0, now: now) }
        // A background task found these due while the phone was locked: they wait for this unlock.
        let notified = Set(index?.entries.filter(\.unlockNotified).map(\.id) ?? [])
        list = list.map { notified.contains($0.id) && RecipeRules.isDue($0, now: now) && !$0.state.waitingForUnlock ? RecipeRules.waitForUnlock($0) : $0 }
        list.forEach { try? repository.save($0) }
        recipes = list
        loaded = true
        persistIndex(previous: index)
        reschedule(now: now)
    }

    func recipe(_ id: String) -> RecipeDoc? { recipes.first { $0.id == id } }

    // MARK: Editing

    /// Saves an edited (or new) recipe; returns the problems that keep it from being saved.
    @discardableResult
    func save(_ recipe: RecipeDoc, now: Int64 = nowMillis()) -> [String] {
        let problems = RecipeRules.problems(recipe)
        guard problems.isEmpty else { return problems }
        let previous = self.recipe(recipe.id)
        let saved = RecipeRules.saved(recipe, previous: previous, now: now)
        store(saved)
        reschedule(now: now)
        if saved.enabled { askOnce() }
        if saved.runsOn != nil || previous?.runsOn != nil {
            Task { await self.sendToNode(saved, previous: previous) }
        }
        return []
    }

    // MARK: Recipes on a node

    /// Sends [recipe] to its node, or takes it off the node it left.
    private func sendToNode(_ recipe: RecipeDoc, previous: RecipeDoc?) async {
        guard let host else { return }
        if let old = previous, old.runsOn != nil, old.runsOn != recipe.runsOn { await host.nodeRemove(old.json) }
        guard recipe.runsOn != nil else { return }
        let (json, error) = await host.nodePush(recipe.json)
        nodeNotice = error.map { "\(recipe.name): \($0)" }
        // Keep what was sent, on whatever is stored now.
        if let json, let sent = RecipeDoc(json: json), var current = self.recipe(recipe.id) {
            current.state.pushed = sent.state.pushed
            store(current)
        }
    }

    /// One pass over the recipes on nodes (app opened, Run now): re-sends what changed, collects finished runs.
    func syncNodes() {
        guard loaded, nodeSync == nil, recipes.contains(where: { $0.runsOn != nil }) else { return }
        nodeSync = Task { [weak self] in
            await self?.collectNodes()
            self?.nodeSync = nil
        }
    }

    /// [syncNodes], awaited (tests, a background task).
    func syncNodesNow() async {
        guard loaded else { return }
        await collectNodes()
    }

    private func collectNodes() async {
        var notice: String?
        for recipe in recipes where recipe.runsOn != nil {
            if RecipeRules.nodeNeedsPush(recipe, previous: recipe) { await sendToNode(recipe, previous: recipe) }
            if let error = await collect(recipe.id).error { notice = "\(recipe.name): \(error)" }
        }
        nodeNotice = notice
    }

    /// Collects [id]'s new runs from its node into chats: how many, or why the node could not be asked.
    @discardableResult
    private func collect(_ id: String) async -> (count: Int, error: String?) {
        guard let host, let recipe = recipe(id), let node = recipe.runsOn else { return (0, nil) }
        let (runs, error) = await host.nodeRuns(recipe.json)
        guard let runs else { return (0, error) }
        for run in runs {
            guard let current = self.recipe(id) else { return (0, nil) }
            let chat = host.newNodeChat(title: RecipeRules.runChatTitle(current, run: run), providerId: node)
            guard let record = await host.nodeDeliver(current.json, run: run, chatId: chat) else { continue }
            let done = RecipeRules.finished(self.recipe(id) ?? current, record: record)
            store(done)
            notifier.finished(done, record: record)
        }
        return (runs.count, nil)
    }

    func setEnabled(_ id: String, _ on: Bool, now: Int64 = nowMillis()) {
        guard var r = recipe(id) else { return }
        r.enabled = on
        save(r, now: now)
    }

    func delete(_ id: String) {
        if let gone = recipe(id), gone.runsOn != nil, let host { Task { await host.nodeRemove(gone.json) } }
        repository.delete(id)
        recipes.removeAll { $0.id == id }
        queue.removeAll { $0.id == id }
        persistIndex()
        reschedule()
    }

    private func store(_ recipe: RecipeDoc) {
        try? repository.save(recipe)
        if let i = recipes.firstIndex(where: { $0.id == recipe.id }) { recipes[i] = recipe } else { recipes.append(recipe) }
        persistIndex()
    }

    private func askOnce() {
        guard !asked else { return }
        asked = true
        Task { await notifier.requestAuthorization() }
    }

    // MARK: Running

    /// "Run now": runs [id] after whatever is running.
    func runNow(_ id: String) {
        if let recipe = recipe(id), recipe.runsOn != nil {
            askOnce()
            Task { await self.runOnNode(recipe) }
            return
        }
        guard recipe(id) != nil, running != id, !queued.contains(id) else { return }
        askOnce()
        queue.append((id, true))
        pump()
    }

    /// "Run now" on the node: sends the recipe as it is, starts it there, and watches for the
    /// result while the app is open (later results come with the next [syncNodes]).
    private func runOnNode(_ recipe: RecipeDoc) async {
        guard let host else { return }
        running = recipe.id
        defer { running = nil }
        await sendToNode(recipe, previous: recipe)
        if let error = await host.nodeRunNow(recipe.json) {
            nodeNotice = "\(recipe.name): \(error)"
            return
        }
        for _ in 0..<60 {
            try? await Task.sleep(for: .seconds(10))
            let got = await collect(recipe.id)
            if got.error != nil || got.count > 0 || Task.isCancelled { return }
        }
    }

    /// Runs every due recipe (app opened, background task), one after another.
    func runDue(now: Int64 = nowMillis()) {
        guard loaded else { return }
        let due = recipes.filter { RecipeRules.isDue($0, now: now) && conditionsMet($0) }
        notifier.withdrawUnlock(recipes.filter { !$0.state.waitingForUnlock || due.contains($0) }.map(\.id))
        for r in due where running != r.id && !queued.contains(r.id) { queue.append((r.id, false)) }
        pump()
    }

    /// Waits until nothing runs or is queued (background task, tests).
    func idle() async {
        while let task { await task.value }
    }

    /// Stops a running recipe and drops the queue (panic wipe, background expiry).
    func stop() {
        queue.removeAll()
        task?.cancel()
        if let session = runningSession { host?.cancelRun(sessionId: session) }
    }

    /// Charging and battery conditions (iOS cannot hold a scheduled run for them, so it checks).
    private func conditionsMet(_ r: RecipeDoc) -> Bool {
        guard let host else { return false }
        if r.requiresCharging && !host.charging { return false }
        if r.requiresBatteryNotLow && host.batteryLow && !host.charging { return false }
        return true
    }

    private func pump() {
        guard task == nil else { return }
        task = Task { [weak self] in
            while let self, !Task.isCancelled, !self.queue.isEmpty {
                let next = self.queue.removeFirst()
                await self.run(next.id, manual: next.manual)
            }
            self?.task = nil
            self?.reschedule()
        }
    }

    /// One run: gate, chat, headless turn, record, notification.
    private func run(_ id: String, manual: Bool) async {
        guard let host, let recipe = recipe(id) else { return }
        let started = nowMillis()
        guard host.protectedDataAvailable else {
            store(RecipeRules.waitForUnlock(recipe))
            notifier.unlockToRun(id: recipe.id, name: recipe.name)
            return
        }
        running = id
        defer { running = nil }
        let record: String
        switch Self.model(for: recipe, host: host) {
        case .failure(let problem):
            record = RecipeRules.failedRecord(problem.reason, startedAt: started, manual: manual)
        case .success(let model):
            let context = recipe.includeRecentChats ? host.recentChats(since: RecipeRules.digestSince(recipe, now: started)) : nil
            let session = host.newResultChat(title: RecipeRules.chatTitle(recipe.name, at: started), model: model)
            RecipeBackground.began(background: !host.appActive)
            runningSession = session
            record = await host.run(sessionId: session, model: model, recipe: recipe.json, context: context, startedAt: started, manual: manual)
            runningSession = nil
            RecipeBackground.ended()
        }
        // The recipe may have been edited or deleted meanwhile: apply the run to what is stored now.
        guard let current = self.recipe(id) else { return }
        let done = RecipeRules.finished(current, record: record)
        store(done)
        notifier.withdrawUnlock([id])
        notifier.finished(done, record: record)
    }

    struct ModelProblem: Error, Equatable { let reason: String }

    /// The on-device model [recipe] runs on: its own if still installed, else This phone's default.
    static func model(for recipe: RecipeDoc, host: any RecipeHost) -> Result<String, ModelProblem> {
        guard let fallback = host.defaultLocalModel() else { return .failure(ModelProblem(reason: RecipeRules.copy.NO_LOCAL_MODEL)) }
        let model = recipe.model.isEmpty ? fallback : recipe.model
        guard model == fallback || host.localModels().contains(model) else {
            return .failure(ModelProblem(reason: RecipeRules.copy.modelMissing(model: model)))
        }
        return .success(model)
    }

    // MARK: Scheduling

    func reschedule(now: Int64 = nowMillis()) {
        host?.schedule(RecipeBackground.plan(recipes, now: now))
    }

    private func persistIndex(previous: RecipeIndex? = nil) {
        RecipeIndex(recipes, previous: previous ?? RecipeIndex.read(from: indexURL)).write(to: indexURL)
    }

    /// A background task while the phone is locked: say "Unlock to run" once per due recipe and
    /// ask for another try. Reads only the index file (the store is unreadable).
    static func lockedTask(now: Int64 = nowMillis(), indexURL: URL = RecipeIndex.url, notifier: any RecipeNotifying) -> RecipeBackground.Plan? {
        guard var index = RecipeIndex.read(from: indexURL) else { return nil }
        let due = Set(index.due(now: now).map(\.id))
        for i in index.entries.indices where due.contains(index.entries[i].id) && !index.entries[i].unlockNotified {
            notifier.unlockToRun(id: index.entries[i].id, name: index.entries[i].name)
            index.entries[i].unlockNotified = true
        }
        index.write(to: indexURL)
        let enabled = index.entries.filter(\.enabled)
        let next = enabled.compactMap(\.nextRunAt).filter { !due.isEmpty || $0 > now }.min()
        guard let next else { return nil }
        let earliest = due.isEmpty ? next : now + RecipeBackground.retryMs
        let soon = enabled.filter { due.contains($0.id) || ($0.nextRunAt.map { $0 <= earliest + RecipeBackground.batchWindowMs } ?? false) }
        return RecipeBackground.Plan(earliest: earliest, requiresPower: !soon.isEmpty && soon.allSatisfy(\.requiresCharging), requiresNetwork: soon.contains(where: \.usesNetwork))
    }
}

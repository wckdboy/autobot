import Foundation

/**
 Recipes in the encrypted store: one document per recipe (collection [collection]), its payload
 the shared JSON. The example templates are added once (a UserDefaults flag, which a panic wipe
 clears with everything else, so a wiped app starts with them again).
 */
final class RecipeRepository: @unchecked Sendable {
    static let collection = "recipes"
    static let seededKey = "ai.wckd.autobot.recipes.seeded"

    private let store: EncryptedStore
    private let defaults: UserDefaults

    init(store: EncryptedStore, defaults: UserDefaults = .standard) {
        self.store = store
        self.defaults = defaults
    }

    /// Every readable recipe (the templates are added first if they never were), oldest first.
    func load(now: Int64 = nowMillis()) -> [RecipeDoc] {
        if !defaults.bool(forKey: Self.seededKey) {
            for template in RecipeRules.templates(now: now) { try? save(template) }
            defaults.set(true, forKey: Self.seededKey)
        }
        let rows = (try? store.all(Self.collection, as: String.self)) ?? []
        return Self.ordered(rows.compactMap(RecipeDoc.init(json:)))
    }

    private static let templateOrder = ["template-morning-brief", "template-notes-tidy", "template-chat-digest"]

    /// List order, as Android's (shared `RecipeTemplates.listOrder`): oldest first, the templates in
    /// their own order, then by id.
    static func ordered(_ list: [RecipeDoc]) -> [RecipeDoc] {
        func rank(_ r: RecipeDoc) -> Int { r.template.flatMap(templateOrder.firstIndex(of:)) ?? templateOrder.count }
        return list.sorted { ($0.createdAt, rank($0), $0.id) < ($1.createdAt, rank($1), $1.id) }
    }

    func save(_ recipe: RecipeDoc) throws { try store.put(Self.collection, id: recipe.id, recipe.json) }

    func delete(_ id: String) { try? store.delete(Self.collection, id: id) }
}

/**
 What a background task may know while the phone is locked (the store is then unreadable): each
 recipe's id, name and next run, and whether it already said "Unlock to run". Nothing else, so
 the file can stay readable after the first unlock (`completeUntilFirstUserAuthentication`). It
 lives under [AppPaths.root], so a panic wipe deletes it.
 */
struct RecipeIndex: Codable, Equatable, Sendable {
    struct Entry: Codable, Equatable, Sendable {
        var id: String
        var name: String
        var nextRunAt: Int64?
        var enabled: Bool
        var requiresCharging: Bool
        var usesNetwork: Bool
        /// "Unlock to run" was shown for the current due run.
        var unlockNotified = false
    }

    var entries: [Entry] = []

    static var url: URL { AppPaths.root.appendingPathComponent("recipes-schedule.json") }

    init(entries: [Entry] = []) { self.entries = entries }

    init(_ recipes: [RecipeDoc], previous: RecipeIndex? = nil) {
        entries = recipes.map { r in
            let old = previous?.entries.first { $0.id == r.id }
            // The flag survives only while the same run is still due.
            let notified = old?.unlockNotified == true && old?.nextRunAt == r.state.nextRunAt
            return Entry(id: r.id, name: r.name, nextRunAt: r.state.nextRunAt, enabled: r.enabled, requiresCharging: r.requiresCharging,
                         usesNetwork: r.usesNetwork, unlockNotified: notified)
        }
    }

    /// Enabled entries whose run is due at [now] (the shared early slack included).
    func due(now: Int64) -> [Entry] {
        entries.filter { e in e.enabled && (e.nextRunAt.map { now + 60_000 >= $0 } ?? false) }
    }

    static func read(from url: URL = RecipeIndex.url) -> RecipeIndex? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(RecipeIndex.self, from: data)
    }

    func write(to url: URL = RecipeIndex.url) {
        guard let data = try? JSONEncoder().encode(self) else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}

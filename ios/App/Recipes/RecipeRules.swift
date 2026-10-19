import AutobotShared
import Foundation

/**
 The shared recipe rules and copy (`RecipeBridge`, `RecipeCopy` in `:shared`), typed for Swift.
 Android calls the same Kotlin, so both apps schedule, fence and word recipes alike.
 */
enum RecipeRules {
    private static var bridge: RecipeBridge { RecipeBridge.shared }
    static var copy: RecipeCopy { RecipeCopy.shared }

    static func templates(now: Int64 = nowMillis()) -> [RecipeDoc] { bridge.templates(nowMillis: now).compactMap(RecipeDoc.init(json:)) }

    static func blank(now: Int64 = nowMillis()) -> RecipeDoc? { RecipeDoc(json: bridge.blank(id: UUID().uuidString.lowercased(), nowMillis: now)) }

    /// [recipe] as saved from the editor: tools cleaned up, the next run worked out.
    static func saved(_ recipe: RecipeDoc, previous: RecipeDoc?, now: Int64 = nowMillis()) -> RecipeDoc {
        RecipeDoc(json: bridge.saved(json: recipe.json, previousJson: previous?.json, nowMillis: now)) ?? recipe
    }

    static func reconcile(_ recipe: RecipeDoc, now: Int64 = nowMillis()) -> RecipeDoc {
        RecipeDoc(json: bridge.reconcile(json: recipe.json, nowMillis: now)) ?? recipe
    }

    static func finished(_ recipe: RecipeDoc, record: String) -> RecipeDoc {
        RecipeDoc(json: bridge.finished(json: recipe.json, recordJson: record)) ?? recipe
    }

    static func waitForUnlock(_ recipe: RecipeDoc) -> RecipeDoc { RecipeDoc(json: bridge.waitForUnlock(json: recipe.json)) ?? recipe }

    static func isDue(_ recipe: RecipeDoc, now: Int64 = nowMillis()) -> Bool { bridge.isDue(json: recipe.json, nowMillis: now) }

    /// The next time the app should wake for [recipes] (nil: nothing scheduled).
    static func nextWake(_ recipes: [RecipeDoc]) -> Int64? {
        let t = bridge.nextWake(jsons: recipes.map(\.json))
        return t < 0 ? nil : t
    }

    static func problems(_ recipe: RecipeDoc) -> [String] { bridge.problems(json: recipe.json) }

    /// The list's status line; iOS times are "around" (the system decides when work runs).
    static func status(_ recipe: RecipeDoc, now: Int64 = nowMillis()) -> String { bridge.status(json: recipe.json, nowMillis: now, approximate: true) }

    static func scheduleLabel(_ recipe: RecipeDoc) -> String { bridge.scheduleLabel(json: recipe.json) }

    static func chatTitle(_ name: String, at: Int64) -> String { bridge.chatTitle(name: name, atMillis: at) }

    static func usesNetwork(_ json: String) -> Bool { bridge.usesNetwork(json: json) }

    static func digestSince(_ recipe: RecipeDoc, now: Int64 = nowMillis()) -> Int64 { bridge.digestSince(json: recipe.json, nowMillis: now) }

    static func recentChats(_ chats: [(title: String, updatedAt: Int64, text: String)], since: Int64) -> String {
        bridge.recentChats(chats: chats.map { RecipeContext.ChatExcerpt(title: $0.title, updatedAt: $0.updatedAt, text: $0.text) }, sinceMillis: since)
    }

    static func failedRecord(_ reason: String, startedAt: Int64, finishedAt: Int64 = nowMillis(), manual: Bool) -> String {
        bridge.failedRecord(reason: reason, startedAt: startedAt, finishedAt: finishedAt, manual: manual)
    }

    static func notificationTitle(_ name: String, record: String) -> String { bridge.notificationTitle(name: name, recordJson: record) }

    static func notificationBody(record: String, showSummary: Bool) -> String { bridge.notificationBody(recordJson: record, showSummary: showSummary) }

    static func nodeNeedsPush(_ recipe: RecipeDoc, previous: RecipeDoc?, now: Int64 = nowMillis()) -> Bool {
        bridge.nodeNeedsPush(json: recipe.json, previousJson: previous?.json, nowMillis: now)
    }

    static func nodeLimits(_ recipe: RecipeDoc) -> [String] { bridge.nodeLimits(json: recipe.json) }

    /// "Daily at 07:00 · on studio".
    static func where_(_ recipe: RecipeDoc, node: String?) -> String { bridge.where(json: recipe.json, node: node) }

    static func runChatTitle(_ recipe: RecipeDoc, run: String) -> String { bridge.runChatTitle(json: recipe.json, runJson: run) }

    struct ToolGroup: Identifiable, Sendable {
        let id: String
        let label: String
        let detail: String
        let tools: [String]
        let usesNetwork: Bool
    }

    static var toolGroups: [ToolGroup] {
        bridge.toolGroups.map { ToolGroup(id: $0.id, label: $0.label, detail: $0.detail, tools: $0.tools, usesNetwork: $0.usesNetwork) }
    }

    static func toggleTools(_ tools: [String], group: String, on: Bool) -> [String] { bridge.toggleTools(tools: tools, groupId: group, on: on) }

    /// "07:00".
    static func clock(_ minute: Int) -> String { String(format: "%02d:%02d", minute / 60, minute % 60) }

    static let dayNames = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
}

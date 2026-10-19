import AutobotShared
import Foundation
import UIKit

/// The app side of a recipe run: data access, the on-device route, the result chat and the
/// shared agent bridge.
@MainActor
final class AppRecipeHost: RecipeHost {
    private unowned let app: AppContainer

    init(app: AppContainer) {
        self.app = app
        UIDevice.current.isBatteryMonitoringEnabled = true
    }

    var protectedDataAvailable: Bool { UIApplication.shared.isProtectedDataAvailable }
    var appActive: Bool { UIApplication.shared.applicationState == .active }

    var charging: Bool { [.charging, .full].contains(UIDevice.current.batteryState) }

    var batteryLow: Bool {
        let level = UIDevice.current.batteryLevel
        return ProcessInfo.processInfo.isLowPowerModeEnabled || (level >= 0 && level < 0.2)
    }

    func defaultLocalModel() -> String? {
        guard let local = app.providers.get(ProviderPresets.localProviderId), local.kind == .local, !local.defaultModel.isEmpty else { return nil }
        return local.defaultModel
    }

    func localModels() -> [String] {
        app.library.models.filter(\.isLocalChat).map(\.id) + (AppleIntelligence.unavailability == nil ? [AppleIntelligence.id] : [])
    }

    /// The "Recipes" chat folder, created on first use.
    private func folder() -> ChatFolder? {
        let name = RecipeRules.copy.FOLDER
        return app.conversations.folders.first { $0.name == name } ?? app.conversations.createFolder(name)
    }

    func newResultChat(title: String, model: String) -> String {
        let chat = app.conversations.create(incognito: false, providerId: ProviderPresets.localProviderId, model: model, title: title)
        if let folder = folder() { app.conversations.move([chat.id], to: folder.id) }
        return chat.id
    }

    func recentChats(since: Int64) -> String {
        let recipes = app.conversations.folders.first { $0.name == RecipeRules.copy.FOLDER }?.id
        let chats = app.conversations.conversations.filter { c in
            !app.conversations.isIncognito(c.id) && c.folderId != recipes && c.updatedAt > since
        }
        let texts = app.sessions.searchTexts(chats.map(\.id))
        return RecipeRules.recentChats(chats.map { (title: $0.title, updatedAt: $0.updatedAt, text: texts[$0.id] ?? "") }, since: since)
    }

    func run(sessionId: String, model: String, recipe: String, context: String?, startedAt: Int64, manual: Bool) async -> String {
        guard let runtime = app.agent as? SharedAgentRuntime else {
            return RecipeRules.failedRecord(RecipeRules.copy.ON_DEVICE_ONLY, startedAt: startedAt, manual: manual)
        }
        return await runtime.runRecipe(
            sessionId: sessionId, providerId: ProviderPresets.localProviderId, model: model, recipe: recipe, context: context,
            startedAt: startedAt, manual: manual,
        )
    }

    func newNodeChat(title: String, providerId: String) -> String {
        let chat = app.conversations.create(incognito: false, providerId: providerId, model: "auto", title: title)
        if let folder = folder() { app.conversations.move([chat.id], to: folder.id) }
        return chat.id
    }

    private var runtime: SharedAgentRuntime? { app.agent as? SharedAgentRuntime }

    func nodePush(_ recipe: String) async -> (json: String?, error: String?) {
        guard let runtime else { return (nil, "My node is not available here") }
        return await runtime.nodeRecipePush(recipe)
    }

    func nodeRemove(_ recipe: String) async { _ = await runtime?.nodeRecipeRemove(recipe) }

    func nodeRunNow(_ recipe: String) async -> String? {
        guard let runtime else { return "My node is not available here" }
        return await runtime.nodeRecipeRunNow(recipe)
    }

    func nodeRuns(_ recipe: String) async -> (runs: [String]?, error: String?) {
        guard let runtime else { return (nil, "My node is not available here") }
        return await runtime.nodeRecipeRuns(recipe)
    }

    func nodeDeliver(_ recipe: String, run: String, chatId: String) async -> String? {
        await runtime?.nodeRecipeDeliver(recipe, run: run, chatId: chatId)
    }

    func schedule(_ plan: RecipeBackground.Plan?) { RecipeBackground.submit(plan) }

    func cancelRun(sessionId: String) { app.agent.cancel(sessionId) }
}

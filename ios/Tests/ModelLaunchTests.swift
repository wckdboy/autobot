import AutobotShared
import Testing
@testable import Autobot

/// Tapping an installed model opens its task (the shared `launchOf`): image → studio, chat → session.
@MainActor
struct ModelLaunchTests {
    private func installed(_ plan: Autobot.ModelPlan, _ status: Autobot.ModelStatus = .ready) -> Autobot.InstalledModel {
        Autobot.InstalledModel(
            id: plan.id, kind: plan.kind, source: plan.source, title: plan.title, subtitle: plan.subtitle, format: plan.format, engine: plan.engine,
            baseModel: plan.baseModel, license: plan.license, nsfw: plan.nsfw, totalBytes: plan.totalBytes, downloadedBytes: plan.totalBytes,
            status: status, error: nil, manifest: plan.manifest, addedAt: 0, updatedAt: 0,
        )
    }

    @Test func installedModelsOpenTheirTask() {
        #expect(ModelsModel.launch(installed(Autobot.CoreMLCatalog.all[0])).task == ModelTask.imagine)
        #expect(ModelsModel.launch(installed(Autobot.CoreMLCatalog.upscalers[0])).task == ModelTask.gallery)
        #expect(ModelsModel.launch(installed(Autobot.Catalog.chat[0])).task == ModelTask.chat)
        #expect(ModelsModel.launch(installed(Autobot.Catalog.speech[0])).task == ModelTask.none)
        #expect(ModelsModel.launch(installed(Autobot.CoreMLCatalog.all[0], .downloading)).task == ModelTask.none)
    }

    /// A missing file is reported instead of opening a screen that can't load the model.
    @Test func missingFilesAreReported() {
        let launch = ModelsModel.launch(installed(Autobot.Catalog.chat[0]), filesPresent: false)
        #expect(!launch.opens && launch.problem != nil)
    }
}

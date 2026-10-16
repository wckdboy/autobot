import Foundation
import Testing
@testable import Autobot

private func installed(_ plan: Autobot.ModelPlan, backend: Autobot.Backend = .auto) -> Autobot.InstalledModel {
    var m = Autobot.InstalledModel(
        id: plan.id, kind: plan.kind, source: plan.source, title: plan.title, subtitle: plan.subtitle, format: plan.format, engine: plan.engine,
        baseModel: plan.baseModel, license: plan.license, nsfw: plan.nsfw, totalBytes: plan.totalBytes, downloadedBytes: plan.totalBytes,
        status: .ready, error: nil, manifest: plan.manifest, addedAt: 0, updatedAt: 0,
    )
    m.backend = backend
    return m
}

private var sdxlPlan: Autobot.ModelPlan { Autobot.CoreMLCatalog.all.first { $0.baseModel?.contains("SDXL") == true }! }
private var sd15Plan: Autobot.ModelPlan { Autobot.CoreMLCatalog.all[0] }

/// Route tags name the engine that actually runs: never "CPU" for a Neural Engine / GPU model.
struct RouteTagTests {
    @Test func onDeviceImageModelsRunOnTheNeuralEngineOrGpu() {
        for plan in Autobot.CoreMLCatalog.all {
            let route = Route.onDeviceImage(installed(plan))
            #expect(route == .localNpu || route == .localGpu, "\(plan.title)")
        }
        #expect(Route.onDeviceImage(installed(sd15Plan)) == .localNpu) // split-einsum
        #expect(Route.onDeviceImage(nil) == .localNpu)
        #expect(Route.onDeviceImage(installed(sd15Plan, backend: .gpu)) == .localGpu)
        #expect(Route.onDeviceImage(installed(sd15Plan, backend: .npu)) == .localNpu)
        // Only a CPU the user forced says CPU.
        #expect(Route.onDeviceImage(installed(sd15Plan, backend: .cpu)) == .localCpu)
    }

    @Test func originalAttentionBuildsRunOnTheGpu() {
        var plan = sd15Plan
        plan.id = "hf:apple/x:original/compiled"
        #expect(Route.onDeviceImage(installed(plan)) == .localGpu)
    }

    @Test func localChatRunsOnMetal() {
        let chat = installed(Autobot.Catalog.chat[0])
        #expect(Route.localChat(chat) == .localGpu)
        #expect(Route.localChat(nil) == .localGpu)
        var npu = chat
        npu.backend = .npu // llama.cpp has no ANE backend: it runs on the GPU
        #expect(Route.localChat(npu) == .localGpu)
        #expect(Route.localChat(installed(Autobot.Catalog.chat[0], backend: .cpu)) == .localCpu)
        let local = Provider(id: "l", kind: .local, displayName: "This phone", baseUrl: "local://", defaultModel: chat.id)
        #expect(routeOf(local, model: chat) == .localGpu)
        #expect(routeOf(local) == .localGpu)
    }

    @Test func remoteRoutes() {
        let cloud = Provider(id: "c", kind: .deepseek, displayName: "DeepSeek", baseUrl: "https://api.deepseek.com", defaultModel: "x")
        let lan = Provider(id: "n", kind: .ollama, displayName: "Ollama", baseUrl: "http://192.168.1.20:11434", defaultModel: "x")
        let loop = Provider(id: "o", kind: .ollama, displayName: "Ollama", baseUrl: "http://127.0.0.1:11434", defaultModel: "x")
        #expect(routeOf(cloud) == .cloudApi)
        #expect(routeOf(lan) == .pcLan)
        #expect(routeOf(loop) == .pcLan)
        let pc = DiffusionBackend(id: "f", kind: .sdApi, name: "Forge", baseUrl: "http://127.0.0.1:7860")
        #expect(Route.imageBackend(pc, model: nil) == .pcLan)
        let phone = DiffusionBackend(id: "on-device", kind: .onDevice, name: "This phone", baseUrl: "local://this-phone")
        #expect(Route.imageBackend(phone, model: installed(sdxlPlan)) == .localNpu)
    }

    @Test func labels() {
        #expect(Route.localNpu.label == "local · npu")
        #expect(Route.localGpu.accelerator == "gpu")
        #expect(Route.pcLan.accelerator == nil)
    }
}

/// A model's recommended settings fill the form when it is selected, except what the user changed.
struct ImageDefaultsTests {
    @Test func catalogValuesWin() {
        let rec = ImageDefaults.recommended(for: installed(sdxlPlan))
        #expect(rec.width == 768 && rec.height == 768)
        #expect(rec.steps == 25)
        let sd = ImageDefaults.recommended(for: installed(sd15Plan))
        #expect(sd.width == 512 && sd.cfg == 7.5)
    }

    @Test func sdxlNeverDefaultsTo512() {
        for name in ["sd_xl_base_1.0.safetensors", "juggernautXL_v9", "ponyDiffusionV6XL", "illustriousXL_v01", "SDXL Turbo"] {
            let rec = ImageDefaults.recommended(remoteModel: name)
            #expect(rec.width == 1024 && rec.height == 1024, "\(name)")
        }
        #expect(ImageDefaults.recommended(remoteModel: "flux1-dev").width == 1024)
        #expect(ImageDefaults.recommended(remoteModel: "v1-5-pruned-emaonly").width == 512)
        // A catalog entry without a size still gets its architecture's.
        var plan = sdxlPlan
        plan.manifest.recommended = Recommended(steps: 30)
        let rec = ImageDefaults.recommended(for: installed(plan))
        #expect(rec.width == 1024 && rec.steps == 30)
    }

    @Test func applyKeepsTheUsersSettings() {
        var r = DiffusionRequest()
        r.steps = 40
        ImageDefaults.apply(ImageDefaults.sdxl, to: &r, keeping: [.steps])
        #expect(r.steps == 40)
        #expect(r.width == 1024 && r.height == 1024)
        #expect(r.cfgScale == 6)
    }

    @Test func editsAreToldApartFromTheLastSelection() {
        var r = DiffusionRequest()
        ImageDefaults.apply(ImageDefaults.sdxl, to: &r)
        let applied = ImageDefaults.snapshot(r)
        #expect(ImageDefaults.edited(r, since: applied).isEmpty)
        #expect(ImageDefaults.edited(r, since: nil).isEmpty)
        r.width = 832
        r.cfgScale = 4
        #expect(ImageDefaults.edited(r, since: applied) == [.size, .cfg])
    }

    /// Imagine: selecting an installed model applies its recommended values; a hand-set value stays.
    @MainActor
    @Test(.timeLimit(.minutes(2))) func selectingAModelInImagineAppliesItsSettings() async throws {
        let app = AppContainer()
        await app.unlockData()
        let model = app.library.add(sdxlPlan)
        app.library.progress(model.id, downloaded: model.totalBytes, status: .ready)
        defer { app.library.remove(model.id) }
        let imagine = ImagineModel(app: app)
        let backendId = imagine.backend?.id ?? DiffusionBackendRepository.onDeviceId
        imagine.setSize(512, 512)
        imagine.selectChip(ModelChip(backendId: backendId, model: model.id, label: model.title, onDevice: true))
        #expect(imagine.request.model == model.id)
        #expect(imagine.request.steps == 25)
        #expect(imagine.request.cfgScale == 7)
        // The size was set by hand before: it stays.
        #expect(imagine.request.width == 512)

        let fresh = ImagineModel(app: app)
        fresh.selectChip(ModelChip(backendId: backendId, model: model.id, label: model.title, onDevice: true))
        #expect(fresh.request.width == 768 && fresh.request.height == 768)
    }

    @MainActor
    @Test(.timeLimit(.minutes(2))) func studioImageRoomAppliesRecommendedSettings() async throws {
        let app = AppContainer()
        await app.unlockData()
        let room = ImageRoomModel(app: app)
        room.request.width = 512
        room.request.height = 512
        room.selectRemoteModel("juggernautXL_v9")
        #expect(room.request.width == 1024 && room.request.height == 1024)
        // The user changes the size, then picks another model: their size stays.
        room.request.width = 896
        room.request.height = 1152
        room.selectRemoteModel("ponyDiffusionV6XL")
        #expect(room.request.width == 896 && room.request.height == 1152)
        #expect(room.request.model == "ponyDiffusionV6XL")
    }
}

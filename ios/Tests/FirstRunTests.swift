import AutobotShared
import Testing
@testable import Autobot

/// The first-run card (shared `FirstModel`), Retry's target and the step grammar.
@MainActor
struct FirstRunTests {
    private let gb: Int64 = 1_000_000_000

    private func phone(ramGb: Int64) -> AutobotShared.DeviceCapabilities {
        AutobotShared.DeviceCapabilities(platform: .ios, totalRamBytes: ramGb * gb, freeStorageBytes: 64 * gb, cpuFeatures: [], socModel: nil,
                                         neuralEngine: true, gpuWorkingSetBytes: 0)
    }

    private func installed(_ plan: Autobot.ModelPlan, _ status: Autobot.ModelStatus, downloaded: Int64 = 0) -> Autobot.InstalledModel {
        Autobot.InstalledModel(
            id: plan.id, kind: plan.kind, source: plan.source, title: plan.title, subtitle: plan.subtitle, format: plan.format, engine: plan.engine,
            baseModel: plan.baseModel, license: plan.license, nsfw: plan.nsfw, totalBytes: plan.totalBytes, downloadedBytes: downloaded,
            status: status, error: status == .failed ? "Network lost" : nil, manifest: plan.manifest, addedAt: 0, updatedAt: 0,
        )
    }

    @Test func offersTheModelThatFitsThisPhone() {
        let big = FirstModelState.compute(device: phone(ramGb: 12), models: [], progress: nil, hasChats: false)
        #expect(big.plan?.id == "catalog:qwen3-4b-2507-q4_0")
        #expect(big.step == .offer)
        let small = FirstModelState.compute(device: phone(ramGb: 2), models: [], progress: nil, hasChats: false)
        #expect(small.plan?.id == "catalog:qwen3.5-0.8b-q4_0")
        let none = FirstModelState.compute(device: phone(ramGb: 1), models: [], progress: nil, hasChats: false)
        #expect(none.plan == nil && none.step == .noneFits)
    }

    @Test func followsTheDownloadToTheFirstChat() throws {
        let plan = try #require(Autobot.Catalog.chat.first { $0.id == "catalog:qwen3-4b-2507-q4_0" })
        let device = phone(ramGb: 12)
        let live = DownloadProgress(id: plan.id, title: plan.title, downloaded: 500, total: plan.totalBytes, bytesPerSecond: 1)
        let running = FirstModelState.compute(device: device, models: [installed(plan, .downloading, downloaded: 100)], progress: live, hasChats: false)
        #expect(running.step == .downloading && running.downloaded == 500)
        let paused = FirstModelState.compute(device: device, models: [installed(plan, .paused, downloaded: 100)], progress: nil, hasChats: false)
        #expect(paused.step == .paused && paused.downloaded == 100)
        let failed = FirstModelState.compute(device: device, models: [installed(plan, .failed)], progress: nil, hasChats: false)
        #expect(failed.step == .failed && failed.error == "Network lost")
        #expect(FirstModelState.compute(device: device, models: [installed(plan, .ready)], progress: nil, hasChats: false).step == .ready)
        // Chatting on it: the card has done its job.
        #expect(FirstModelState.compute(device: device, models: [installed(plan, .ready)], progress: nil, hasChats: true).step == .hidden)
    }

    @Test func retryResendsTheLastPrompt() {
        let answered: [Autobot.SessionItem] = [
            .user(key: "u1", text: "first", images: []),
            .assistant(key: "a1", text: "one", reasoning: nil, interrupted: false, outputTokens: nil, model: nil),
            .user(key: "u2", text: " second ", images: []),
            .assistant(key: "a2", text: "two", reasoning: nil, interrupted: false, outputTokens: nil, model: nil),
            .notice(key: "n", text: "stopped", kind: .info),
        ]
        #expect(AnswerRetry.target(answered, running: false) == .init(key: "a2", prompt: "second"))
        #expect(AnswerRetry.target(answered, running: true) == nil)
        let failed: [Autobot.SessionItem] = [.user(key: "u1", text: "ask", images: []), .notice(key: "e", text: "HTTP 500", kind: .error)]
        #expect(AnswerRetry.target(failed, running: false) == .init(key: "e", prompt: "ask"))
        #expect(AnswerRetry.target([.user(key: "u", text: "ask", images: [])], running: false) == nil)
        #expect(AnswerRetry.target([], running: false) == nil)
    }

    @Test func stepsSpeakTheTerminalGrammar() {
        #expect(StepGrammar.mark == "▪")
        #expect(StepGrammar.detail(name: "read", summary: "notes.md") == "└ read · notes.md")
        #expect(StepGrammar.detail(name: "read", summary: " ") == "└ read")
    }

    @Test func sessionsNameCatalogModels() {
        #expect(ChatListModel.modelLabel("catalog:qwen3-4b-2507-q4_0") == "Qwen3 4B Instruct 2507")
        #expect(ChatListModel.modelLabel("deepseek-chat") == "deepseek-chat")
    }

    /// The Studio's rooms are the system tab view's tabs: every room has a title and its own symbol.
    @Test func studioRoomsAreTabs() {
        #expect(StudioRoom.allCases.map { $0.label.sentenceCased } == ["Image", "Audio", "Video", "Workflows", "Library"])
        #expect(Set(StudioRoom.allCases.map(\.symbol)).count == StudioRoom.allCases.count)
    }
}

#if canImport(AutobotShared)
import AutobotShared
#endif
import Foundation
import Synchronization
import Testing
@testable import Autobot

/// Labels that count things say "1 session", not "1 sessions"; catalog rows show one size.
@MainActor
struct CountLabelTests {
    @Test func countsAgreeWithTheirNumber() {
        #expect(counted(1, "session") == "1 session")
        #expect(counted(0, "session") == "0 sessions")
        #expect(counted(2, "memory note") == "2 memory notes")
        #expect(counted(1, "child", "children") == "1 child")
        #expect(counted(3, "child", "children") == "3 children")
        #expect(LiveCountLabel.text(total: 1, live: 0) == "1 session · 0 live")
        #expect(LiveCountLabel.text(total: 12, live: 3) == "12 sessions · 3 live")
        #expect(ChatListModel.deletedLabel(count: 1, title: "Trip") == "Deleted “Trip”")
        #expect(ChatListModel.deletedLabel(count: 1, title: nil) == "Chat deleted")
        #expect(ChatListModel.deletedLabel(count: 4, title: "x") == "Deleted 4 chats")
    }

    @Test func aCatalogRowShowsItsSizeOnce() throws {
        // "Whisper base": 57 MiB in the subtitle, 59 MB measured — the row read "59 MB · 57 MB · …".
        let whisper = try #require(Catalog.speech.first)
        let row = sizedSubtitle(bytes: whisper.totalBytes, whisper.subtitle, whisper.license)
        let sizes = row.components(separatedBy: " · ").filter { $0.hasSuffix(" MB") || $0.hasSuffix(" GB") }
        #expect(sizes == [formatBytes(whisper.totalBytes)])
        #expect(row.contains("fast dictation"))
        #expect(sizedSubtitle(bytes: 2_400_000_000, "Q4_K_M · 2.3 GB · chat", nil) == "2.4 GB · Q4_K_M · chat")
        #expect(sizedSubtitle(bytes: 0, "6-bit palettized · neural engine · 512²", "mit") == "6-bit palettized · neural engine · 512² · mit")
        // Every row of the "for this phone" list.
        var plans: [Autobot.ModelPlan] = Catalog.chat
        plans += Catalog.code
        plans += Catalog.speech
        plans += CoreMLCatalog.all
        plans += CoreMLCatalog.voices
        plans += CoreMLCatalog.audio
        plans += Catalog.embedding
        let size = #/\d+(\.\d+)? (KB|MB|GB)/#
        for plan in plans {
            let parts: [String] = sizedSubtitle(bytes: plan.totalBytes, plan.subtitle, plan.license).components(separatedBy: " · ")
            let sizes = parts.filter { (try? size.wholeMatch(in: $0)) != nil }
            #expect(sizes.count <= 1, "\(plan.id)")
        }
    }
}

/// The Hub / Civitai search list explains an empty result: Offline mode, an error, or no matches.
@MainActor
struct SearchNoticeTests {
    private func notice(loading: Bool = false, results: Bool = false, searched: Bool = true, error: HubError? = nil, offline: Bool = false) -> SearchNotice? {
        ModelsModel.searchNotice(hub: "Hugging Face", loading: loading, hasResults: results, searched: searched, error: error, offline: offline)
    }

    @Test func offlineSaysSoAndLeadsToTheNetworkSetting() {
        // Even before a search: the list is empty because the kill switch is on.
        let offline = notice(searched: false, offline: true)
        #expect(offline?.title == "Offline mode is on")
        #expect(offline?.action == .networkSettings)
        #expect(offline?.actionLabel == "network settings")
        #expect(offline?.detail.contains("Privacy Center") == true)
        #expect(notice(error: HubError(message: "Blocked", code: .blocked))?.action == .networkSettings)
    }

    @Test func errorsAndNoResultsEachSayWhatToDo() {
        #expect(notice(error: HubError(message: "That token was rejected", code: .unauthorized))?.action == .account)
        #expect(notice(error: HubError(message: "slow down", code: .rateLimited))?.action == .retry)
        let failed = notice(error: HubError(message: "The request timed out."))
        #expect(failed?.title == "Couldn't search Hugging Face")
        #expect(failed?.detail == "The request timed out.")
        #expect(failed?.action == .retry)
        #expect(notice()?.title == "No results")
        #expect(notice()?.action == nil)
    }

    @Test func rowsLoadingOrNotSearchedYetShowNoNotice() {
        #expect(notice(loading: true, offline: true) == nil)
        #expect(notice(results: true, offline: true) == nil)
        #expect(notice(searched: false) == nil)
    }

    @Test(.timeLimit(.minutes(1))) func aSearchInOfflineModeIsBlockedAsSuch() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("hub-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let audit = AuditLog()
        let clients = HttpClientFactory(policy: NetworkPolicy(), auditLog: audit, protocolClasses: [ServeStub.self])
        let hub = HuggingFaceClient(clients: clients, accounts: ModelAccounts(preferences: Preferences(url: dir.appendingPathComponent("p.json")), secrets: SecretStore()))
        do {
            _ = try await hub.search("qwen", gguf: true)
            Issue.record("expected the kill switch to refuse")
        } catch let e as HubError {
            #expect(e.code == .blocked)
            #expect(ModelsModel.searchNotice(hub: "Hugging Face", loading: false, hasResults: false, searched: true, error: e, offline: false)?.action == .networkSettings)
        }
        #expect(audit.entries.contains { $0.host == "huggingface.co" && $0.blocked })
    }
}

/// A request the kill switch refused says where to turn the network on.
struct NetworkHintTests {
    @Test func offlineMessagesPointToPrivacyCenter() {
        #expect(withNetworkHint("Offline mode: network access is disabled") == "Offline mode: network access is disabled. Allow a network mode in Privacy Center to reach it.")
        #expect(withNetworkHint("Connection refused") == "Connection refused")
        let once = withNetworkHint("Offline mode: network access is disabled")
        #expect(withNetworkHint(once) == once)
        #expect(noAnswerMessage("https://pc.lan:8188/x", after: .seconds(20)).hasPrefix("No answer from pc.lan in 20 s."))
    }
}

/// Typing into a workflow number field keeps every character until it is a number.
struct NumberDraftTests {
    @Test func partialNumbersStayAsTyped() {
        #expect(NumberDraft.parse("", integer: false) == nil)
        #expect(NumberDraft.parse("-", integer: false) == nil)
        #expect(NumberDraft.parse("1.", integer: false)?.display() == ParamValue.double(1).display())
        #expect(NumberDraft.parse("0.25", integer: false)?.display() == ParamValue.double(0.25).display())
        #expect(NumberDraft.parse("12", integer: true)?.display() == "12")
        #expect(NumberDraft.parse("1.5", integer: true) == nil)
        #expect(NumberDraft.parse("inf", integer: false) == nil)
        // The draft is kept while it stands for the value; a change from elsewhere replaces it.
        let shown = ParamValue.double(1).display()
        #expect(NumberDraft.keeps("1.", shown: shown, integer: false))
        #expect(NumberDraft.keeps("1.0", shown: shown, integer: false))
        #expect(NumberDraft.keeps("-", shown: shown, integer: false))
        #expect(!NumberDraft.keeps("2", shown: shown, integer: false))
    }
}

/// The chat screen's setup banners: each problem says what to do and where.
@MainActor
struct ChatBannerTests {
    private let cloud = Provider(id: "c", kind: .openAICompatible, displayName: "OpenRouter", baseUrl: "https://openrouter.ai/api/v1", defaultModel: "m")
    private let local = Provider(id: ProviderPresets.localProviderId, kind: .local, displayName: "This phone", baseUrl: ProviderPresets.localBaseUrl, defaultModel: "x")

    @Test func eachMisconfigurationHasItsBannerAndTarget() {
        #expect(ChatBanners.list(loaded: false, noProviders: true, showOffline: false, provider: nil, needsKey: false, local: .fine).isEmpty)
        let none = ChatBanners.list(loaded: true, noProviders: true, showOffline: false, provider: nil, needsKey: false, local: .fine)
        #expect(none.map(\.target) == [.providers])

        let offline = ChatBanners.list(loaded: true, noProviders: false, showOffline: true, provider: cloud, needsKey: false, local: .fine)
        #expect(offline.map(\.target) == [.privacyCenter])

        let noKey = ChatBanners.list(loaded: true, noProviders: false, showOffline: false, provider: cloud, needsKey: true, local: .fine)
        #expect(noKey.map(\.id) == ["key"])
        #expect(noKey.first?.text.contains("OpenRouter has no API key") == true)
        var keyed = cloud
        keyed.apiKeyRef = "ref"
        #expect(ChatBanners.list(loaded: true, noProviders: false, showOffline: false, provider: keyed, needsKey: true, local: .fine).isEmpty)

        let missing = ChatBanners.list(loaded: true, noProviders: false, showOffline: false, provider: local, needsKey: false, local: .missing("Qwen3 4B"))
        #expect(missing.map(\.target) == [.models])
        #expect(missing.first?.text.contains("Qwen3 4B") == true)
        // Several problems at once are all shown.
        #expect(ChatBanners.list(loaded: true, noProviders: false, showOffline: true, provider: cloud, needsKey: true, local: .fine).count == 2)
    }

    @Test func anOnDeviceModelThatIsGoneIsNoticed() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("banner-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let library = ModelLibrary(store: EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager()))
        let id = "test:banner-\(UUID().uuidString.prefix(6))"
        let file = Autobot.ModelFile(role: .model, name: "m.gguf", url: "https://huggingface.co/a/b/resolve/main/m.gguf", sizeBytes: 3)
        library.add(Autobot.ModelPlan(id: id, kind: .chat, source: .huggingFace, title: "Tiny", subtitle: "", format: .gguf, engine: .llama, manifest: Autobot.ModelManifest(files: [file])))

        #expect(ChatBanners.local(nil, library: library) == .fine)
        #expect(ChatBanners.local(AppleIntelligence.id, library: library) == .fine)
        #expect(ChatBanners.local("catalog:removed-model", library: library) == .missing("removed-model"))
        // Still downloading: not usable yet.
        #expect(ChatBanners.local(id, library: library) == .missing("Tiny"))
        library.progress(id, downloaded: 3, status: .ready)
        // Ready in the registry, but the file was deleted.
        #expect(ChatBanners.local(id, library: library) == .missing("Tiny"))
        let path = ModelLibrary.file(of: id, file)
        try FileManager.default.createDirectory(at: path.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("abc".utf8).write(to: path)
        #expect(ChatBanners.local(id, library: library) == .fine)
        library.remove(id)
    }
}

/// The chat list's sections, filters and empty states.
@MainActor
struct ChatListStateTests {
    private func key(_ filter: String = "all", hidden: Set<String> = [], hits: [String: ChatSearchHit]? = nil, folders: [String] = []) -> ChatListModel.Key {
        .init(revision: 1, count: 4, folders: folders, providers: [], hidden: hidden, filterKey: filter, sortKey: "RECENT", hits: hits, startOfToday: 0)
    }

    private var conversations: [Conversation] {
        var pinned = Conversation(id: "p", title: "Pinned", providerId: nil, model: nil, createdAt: 1, updatedAt: 5)
        pinned.pinned = true
        var archived = Conversation(id: "a", title: "Old", providerId: nil, model: nil, createdAt: 1, updatedAt: 4)
        archived.archived = true
        var filed = Conversation(id: "f", title: "Filed", providerId: nil, model: nil, createdAt: 1, updatedAt: 3)
        filed.folderId = "travel"
        return [pinned, archived, filed, Conversation(id: "n", title: "Plain", providerId: nil, model: nil, createdAt: 1, updatedAt: 2)]
    }

    @Test func filtersFoldersHiddenAndSearch() {
        let folder = ChatFolder(id: "travel", name: "Travel", createdAt: 1)
        let all = ChatListModel().model(key(folders: ["travel"])) { (conversations, [folder], []) }
        #expect(all.total == 3)
        #expect(all.archivedCount == 1)
        #expect(!all.items.map(\.id).contains("a"))
        #expect(all.items.first?.id == "p")
        #expect(all.folderCounts["travel"] == 1)

        #expect(ChatListModel().model(key("pinned")) { (conversations, [folder], []) }.items.map(\.id) == ["p"])
        #expect(ChatListModel().model(key("archived")) { (conversations, [folder], []) }.items.map(\.id) == ["a"])
        #expect(ChatListModel().model(key("folder:travel", folders: ["travel"])) { (conversations, [folder], []) }.items.map(\.id) == ["f"])
        // A folder deleted meanwhile falls back to everything.
        let gone = ChatListModel().model(key("folder:gone")) { (conversations, [folder], []) }
        #expect(gone.filter is ChatFilterAll)
        // Chats waiting for their undo window are hidden; search keeps only hits.
        #expect(!ChatListModel().model(key(hidden: ["n"])) { (conversations, [folder], []) }.items.map(\.id).contains("n"))
        let hit = ChatSearchHit(titleSpans: [0 ..< 5], snippet: nil, snippetSpans: [])
        #expect(ChatListModel().model(key(hits: ["f": hit])) { (conversations, [folder], []) }.items.map(\.id) == ["f"])
    }

    @Test func emptyStatesSayWhyTheListIsEmpty() {
        #expect(ChatListModel.emptyState(query: "lisbon", filter: ChatFilterAll.shared).title == "no matches")
        #expect(ChatListModel.emptyState(query: "  ", filter: ChatFilterArchived.shared).title == "archive is empty")
        #expect(ChatListModel.emptyState(query: "", filter: ChatFilterPinned.shared).title == "nothing pinned")
        #expect(ChatListModel.emptyState(query: "", filter: ChatFilterFolder(id: "x")).title == "empty folder")
        #expect(ChatListModel.emptyState(query: "", filter: ChatFilterAll.shared).title == "no sessions")
    }
}

/// The downloads panel says why the queue waits, with the way to fix it.
struct DownloadsPanelStateTests {
    private func model(_ id: String, _ status: Autobot.ModelStatus, error: String? = nil, added: Int64 = 0) -> Autobot.InstalledModel {
        Autobot.InstalledModel(id: id, kind: .chat, source: .catalog, title: id, subtitle: "", format: .gguf, engine: .llama, baseModel: nil, license: nil, nsfw: false,
                       totalBytes: 100, downloadedBytes: 10, status: status, error: error, manifest: Autobot.ModelManifest(files: []), addedAt: added, updatedAt: 0)
    }

    @Test func blocksExplainTheQueueAndThePausedRows() {
        let progress = DownloadProgress(id: "run", title: "Run", downloaded: 10, total: 100, bytesPerSecond: 5)
        let running = DownloadQueue.state([model("run", .downloading), model("q", .queued, added: 2)], progress: progress, networkOff: false, connected: false)
        #expect(running.active?.id == "run")
        #expect(running.queued.map(\.id) == ["q"])
        #expect(running.blocks == [.noNetwork])

        // The kill switch pauses what it stops: nothing is queued, the paused rows say why.
        let killed = DownloadQueue.state([model("k", .paused, error: DownloadQueue.killSwitchError)], progress: nil, networkOff: true, connected: true)
        #expect(killed.block == nil)
        #expect(killed.blocks == [.killSwitch])
        #expect(DownloadBlock.killSwitch.label.contains("privacy center"))

        let full = DownloadQueue.state([model("s", .paused, error: "\(DownloadQueue.lowStoragePrefix): needs 4 GB")], progress: nil, networkOff: false, connected: true)
        #expect(full.blocks == [.lowStorage])
        #expect(DownloadQueue.state([model("u", .paused), model("f", .failed, error: "HTTP 500")], progress: nil, networkOff: false, connected: true).blocks.isEmpty)
        #expect(DownloadQueue.state([model("r", .ready)], progress: nil, networkOff: true, connected: false).isEmpty)
    }
}

/// The Studio's rooms and queue labels.
struct StudioStateTests {
    @Test func roomsAndJobStates() {
        #expect(StudioRoom.image.surface == .darkroom)
        #expect(StudioRoom.video.surface == .darkroom)
        #expect(StudioRoom.library.surface == .paper)
        #expect(StudioRoom.audio.surface == .app)
        #expect(StudioRoom.workflows.surface == .app)
        #expect(JobState.queued.label == "queued")
        #expect(JobState.running.label == "running")
        #expect(JobState.done.label == "done")
        #expect(JobState.failed.label == "failed")
        #expect(JobState.cancelled.label == "cancelled")
    }
}

/// A server that never answers: tests give up with a message instead of spinning for minutes.
@Suite(.serialized, .timeLimit(.minutes(1)))
@MainActor
struct UnreachableServerTests {
    private func clients(_ host: String) -> HttpClientFactory {
        LlmStub.serve(host) { _ in (200, [.hang]) }
        let policy = NetworkPolicy()
        policy.setMode(.direct)
        return HttpClientFactory(policy: policy, auditLog: AuditLog(), protocolClasses: [LlmStub.self])
    }

    @Test func aComfyUITestThatGetsNoAnswerSaysSo() async throws {
        let host = "comfy-\(UUID().uuidString.prefix(6).lowercased()).example.com"
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("comfy-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let keys = KeyManager()
        let hub = StudioHub(store: EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: keys), keys: keys, clients: clients(host),
                            preferences: Preferences(url: dir.appendingPathComponent("p.json")), secrets: SecretStore())
        let saved = StudioHub.testTimeout
        StudioHub.testTimeout = .seconds(1)
        defer { StudioHub.testTimeout = saved }
        let started = ContinuousClock.now
        let (ok, text) = await hub.testComfy(ComfyServer(id: "c", name: "PC", baseUrl: "https://\(host)"))
        #expect(!ok)
        #expect(text.contains("No answer from \(host)"))
        #expect(ContinuousClock.now - started < .seconds(10))
        hub.close()
    }

    @Test func importingAgainstASilentServerFallsBackToTheGraph() async throws {
        let host = "comfy-\(UUID().uuidString.prefix(6).lowercased()).example.com"
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("comfy-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let keys = KeyManager()
        let hub = StudioHub(store: EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: keys), keys: keys, clients: clients(host),
                            preferences: Preferences(url: dir.appendingPathComponent("p.json")), secrets: SecretStore())
        let saved = StudioHub.testTimeout
        StudioHub.testTimeout = .seconds(1)
        defer { StudioHub.testTimeout = saved }
        let server = ComfyServer(id: "c-\(host)", name: "PC", baseUrl: "https://\(host)")
        hub.saveComfy(server, headerValue: nil)
        let graph = #"{"3":{"class_type":"KSampler","inputs":{"seed":1,"steps":20,"cfg":7,"sampler_name":"euler","scheduler":"normal","denoise":1,"model":["4",0],"positive":["6",0],"negative":["7",0],"latent_image":["5",0]}},"4":{"class_type":"CheckpointLoaderSimple","inputs":{"ckpt_name":"sd15.safetensors"}},"5":{"class_type":"EmptyLatentImage","inputs":{"width":512,"height":512,"batch_size":1}},"6":{"class_type":"CLIPTextEncode","inputs":{"text":"a lighthouse","clip":["4",1]}},"7":{"class_type":"CLIPTextEncode","inputs":{"text":"","clip":["4",1]}},"8":{"class_type":"VAEDecode","inputs":{"samples":["3",0],"vae":["4",2]}},"9":{"class_type":"SaveImage","inputs":{"images":["8",0],"filename_prefix":"x"}}}"#
        let started = ContinuousClock.now
        let imported = await hub.importWorkflow(name: "Portrait", json: graph, backendId: server.id)
        #expect(ContinuousClock.now - started < .seconds(10))
        #expect(imported.workflow != nil, "\(imported.error ?? "")")
        #expect(!imported.checked)
        let refreshed = await hub.refreshOptions(try #require(imported.workflow), on: server.id)
        #expect(refreshed.id == imported.workflow?.id)
        hub.close()
    }

    @Test func aComfyUIBehindTheKillSwitchSaysOffline() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("comfy-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let keys = KeyManager()
        let hub = StudioHub(store: EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: keys), keys: keys,
                            clients: HttpClientFactory(policy: NetworkPolicy(), auditLog: AuditLog(), protocolClasses: [LlmStub.self]),
                            preferences: Preferences(url: dir.appendingPathComponent("p.json")), secrets: SecretStore())
        let (ok, text) = await hub.testComfy(ComfyServer(id: "c", name: "PC", baseUrl: "https://comfy.example.com"))
        #expect(!ok)
        #expect(text.localizedCaseInsensitiveContains("offline"))
        #expect(text.contains("Privacy Center"))
        hub.close()
    }

    #if canImport(AutobotShared)
    private func runtime(_ clients: HttpClientFactory) throws -> SharedAgentRuntime {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("rt-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let keys = KeyManager()
        let store = EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: keys)
        let secrets = SecretStore()
        return SharedAgentRuntime(
            conversations: ConversationRepository(store: store), providers: ProviderRepository(store: store, secrets: secrets), store: Autobot.SessionStore(store: store),
            clients: clients, llm: LocalLlm(), attachments: AttachmentRepository(keys: keys), secrets: secrets, snapshots: HostSnapshots(),
        )
    }

    @Test func providerAndMcpTestsThatGetNoAnswerSaySo() async throws {
        let host = "silent-\(UUID().uuidString.prefix(6).lowercased()).example.com"
        let agent = try runtime(clients(host))
        let saved = SharedAgentRuntime.testTimeout
        SharedAgentRuntime.testTimeout = .seconds(1)
        defer { SharedAgentRuntime.testTimeout = saved }

        let spec = ProviderSpec(id: "p", kind: .openaiCompatible, displayName: "Silent", baseUrl: "https://\(host)/v1", apiKey: "k", defaultModel: "m",
                                route: "INHERIT", presetId: nil, modelsJson: nil, helperModel: nil)
        let connection = await agent.testConnection(spec)
        #expect(!connection.ok)
        #expect(connection.message.contains("No answer from \(host)"))
        let models = await agent.listModels(spec)
        #expect(models.error?.contains("No answer") == true)

        let (ok, message) = await agent.testMcpServer(McpServer(id: "m", name: "Tools", url: "https://\(host)/mcp"), token: nil)
        #expect(!ok)
        #expect(message.contains("No answer from \(host)"))
        agent.shutdown()
    }

    @Test func aStoppedRuntimeAnswersAtOnce() async throws {
        let agent = try runtime(clients("stopped-\(UUID().uuidString.prefix(6).lowercased()).example.com"))
        agent.shutdown()
        let spec = ProviderSpec(id: "p", kind: .openaiCompatible, displayName: "x", baseUrl: "https://x.example.com/v1", apiKey: nil, defaultModel: "m",
                                route: "INHERIT", presetId: nil, modelsJson: nil, helperModel: nil)
        let result = await agent.testConnection(spec)
        #expect(!result.ok)
        #expect(result.message == "The agent runtime was stopped")
    }
    #endif
}

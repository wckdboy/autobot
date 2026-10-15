import AutobotShared
import Foundation
import Testing
@testable import Autobot

/// Providers on iOS: the shared preset table and model lists through Swift, stored rows and caches.
@MainActor
struct ProvidersTests {
    private func repository() -> ProviderRepository {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("providers-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let repo = ProviderRepository(store: EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager()), secrets: SecretStore())
        repo.load()
        return repo
    }

    @Test func presetsMirrorTheSharedTable() {
        let ids = ProviderPresets.all.map(\.id)
        #expect(ids.count == Int(AutobotShared.ProviderPresets.shared.all.count))
        #expect(Set(ids).count == ids.count)
        for kind in ProviderKind.allCases { #expect(ProviderPresets.forKind(kind).kind == kind) }
        let anthropic = try! #require(ProviderPresets.byId("anthropic"))
        #expect(anthropic.kind == .anthropic)
        #expect(anthropic.hasModelsEndpoint)
        #expect(ProviderPresets.byId("zai")?.hasModelsEndpoint == false)
        #expect(ProviderPresets.byId("local")?.isThisPhone == true)
    }

    @Test func searchIsGroupedInSharedOrder() {
        #expect(ProviderPresets.search("").map(\.group) == ["Cloud", "Open-model hosts", "Local servers", "This phone"])
        #expect(ProviderPresets.search("groq").flatMap(\.presets).map(\.id) == ["groq"])
        #expect(ProviderPresets.search("zzz-none").isEmpty)
    }

    @Test func oldRowsResolveByKindAndHost() {
        let legacy = Provider(id: "p", kind: .openAICompatible, displayName: "Groq", baseUrl: "https://api.groq.com/openai/v1", defaultModel: "m")
        #expect(ProviderPresets.resolve(legacy).id == "groq")
        var named = legacy
        named.presetId = "venice"
        #expect(ProviderPresets.resolve(named).id == "venice")
        #expect(ProviderPresets.resolve(Provider(id: "o", kind: .ollama, displayName: "O", baseUrl: "http://127.0.0.1:11434/v1", defaultModel: "x")).id == "ollama")
    }

    @Test func storedRowsWithoutPresetIdStillDecode() throws {
        let old = #"{"id":"p","kind":"DEEPSEEK","displayName":"DeepSeek","baseUrl":"https://api.deepseek.com","defaultModel":"deepseek-chat","routing":"TOR"}"#
        let p = try JSONDecoder().decode(Provider.self, from: Data(old.utf8))
        #expect(p.presetId == nil)
        #expect(p.routing == .tor)
        let anthropic = #"{"id":"a","kind":"ANTHROPIC","displayName":"A","baseUrl":"https://api.anthropic.com/v1","defaultModel":"claude-opus-5-5","routing":"INHERIT","presetId":"anthropic"}"#
        #expect(try JSONDecoder().decode(Provider.self, from: Data(anthropic.utf8)).kind == .anthropic)
        #expect(SharedMapping.kind(.anthropic) == AutobotShared.ProviderKind.anthropic)
    }

    @Test func helperModelIsStoredAndOptional() throws {
        let old = #"{"id":"g","kind":"OPENAI_COMPATIBLE","displayName":"Go","baseUrl":"https://opencode.ai/inference/go/v1","defaultModel":"kimi-k2.6","routing":"INHERIT","presetId":"opencode-go"}"#
        #expect(try JSONDecoder().decode(Provider.self, from: Data(old.utf8)).helperModel == nil)
        let repo = repository()
        var p = Provider(id: "g", kind: .openAICompatible, displayName: "Go", baseUrl: "https://opencode.ai/inference/go/v1", defaultModel: "kimi-k2.6", presetId: "opencode-go")
        p.helperModel = "glm-5.3-flash"
        _ = try repo.save(p)
        repo.load()
        #expect(repo.get("g")?.helperModel == "glm-5.3-flash")
    }

    @Test func openCodeGoStartsWithAHelperAndLabelsEachModelsDataPolicy() {
        let go = ProviderPresets.byId("opencode-go")!
        let draft = ProviderDraft.fromPreset(go)
        #expect(draft.defaultModel == "kimi-k2.6")
        #expect(draft.helperModel == "glm-5.3-flash")
        #expect(go.privacyUrl == "https://opencode.ai/docs/go/#privacy")
        let kimi = RemoteModelItem(id: "kimi-k2.6", presetId: go.id)
        #expect(kimi.caption == "no training · zero retention")
        #expect(!kimi.trains)
        let muse = RemoteModelItem(id: "muse-spark-1.3-contributor", presetId: go.id)
        #expect(muse.trains)
        #expect(muse.caption == "trains on your prompts")
        // Without a preset (or on providers with one policy) nothing is added.
        #expect(RemoteModelItem(id: "muse-spark-1.3-contributor").caption.isEmpty)
        #expect(!ModelPrivacy.trains(presetId: "groq", model: "x-contributor"))
    }

    @Test func modelListsAreCachedAndDeletedWithTheProvider() throws {
        let repo = repository()
        let p = try repo.save(Provider(id: "g", kind: .openAICompatible, displayName: "Groq", baseUrl: "https://api.groq.com/openai/v1", defaultModel: "m", presetId: "groq"))
        let cache = ModelCache(fetchedAt: 1_000, models: [RemoteModel(id: "openai/gpt-oss-120b", name: nil, contextWindow: KotlinInt(int: 131_072), created: nil, inputPerMTok: nil, outputPerMTok: nil, detail: nil)])
        repo.saveModels(p.id, fetchedAt: cache.fetchedAt, json: cache.encode())
        repo.saveModels("unknown", fetchedAt: 1, json: cache.encode()) // no provider, ignored
        #expect(repo.modelLists.keys.sorted() == ["g"])
        let models = try #require(ModelLists.models(repo.modelLists["g"]?.json))
        #expect(models.map(\.id) == ["openai/gpt-oss-120b"])
        #expect(models.first?.caption == "131k ctx")
        #expect(ProviderFactory.modelInfo(.openAICompatible, "openai/gpt-oss-120b", modelsJson: repo.modelLists["g"]?.json).contextWindow == 131_072)
        repo.load()
        #expect(repo.modelLists["g"]?.fetchedAt == 1_000)
        repo.delete("g")
        #expect(repo.modelLists["g"] == nil)
        repo.load()
        #expect(repo.modelLists.isEmpty)
    }

    @Test func filterAndAgeFollowTheSharedRules() {
        let models = ["gpt-6-luna", "claude-opus-5-5", "gpt-6-sol"].map { RemoteModelItem(id: $0) }
        #expect(ModelLists.filter(models, " GPT").map(\.id) == ["gpt-6-luna", "gpt-6-sol"])
        #expect(ModelLists.filter(models, "", limit: 1).count == 1)
        let now = Date(timeIntervalSince1970: 1_000)
        #expect(ModelLists.age(fetchedAt: 1_000_000 - 12 * 60_000, now: now) == "fetched 12 min ago")
        #expect(ModelLists.models("{broken") == nil)
    }

    @Test func draftUsesItsPresetAndRedactsTheKey() {
        var draft = ProviderDraft.fromPreset(ProviderPresets.byId("lmstudio")!)
        #expect(draft.baseUrl == "http://127.0.0.1:1234/v1")
        #expect(!draft.canSave) // no model until the server's list is fetched
        draft.defaultModel = "qwen/qwen3-8b"
        #expect(draft.canSave)
        draft.apiKeyInput = "lm-secret"
        #expect(!draft.description.contains("lm-secret"))
        #expect(draft.preset.id == "lmstudio")
    }

    @Test func chatsFollowDeletedAndNewProviders() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("routes-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let conversations = ConversationRepository(store: EncryptedStore(url: dir.appendingPathComponent("db.sqlite"), keys: KeyManager()))
        conversations.load()
        let kept = conversations.create(incognito: true, providerId: "b", model: "custom")
        let orphan = conversations.create(incognito: true, providerId: "gone", model: "gone-model")
        let unset = conversations.create(incognito: true, providerId: nil, model: nil)
        let a = Provider(id: "a", kind: .openAICompatible, displayName: "A", baseUrl: "https://a.example", defaultModel: "model-a")
        let b = Provider(id: "b", kind: .openAICompatible, displayName: "B", baseUrl: "https://b.example", defaultModel: "model-b")

        #expect(conversations.repoint(providers: []).isEmpty)
        #expect(conversations.get(orphan.id)?.providerId == "gone")

        #expect(conversations.repoint(providers: [a, b], only: orphan.id).map(\.id) == [orphan.id])
        #expect(conversations.get(unset.id)?.providerId == nil)

        let changed = conversations.repoint(providers: [a, b])
        #expect(changed.map(\.id) == [unset.id])
        #expect(conversations.get(orphan.id)?.providerId == "a")
        #expect(conversations.get(orphan.id)?.model == "model-a")
        #expect(conversations.get(unset.id)?.model == "model-a")
        #expect(conversations.get(kept.id)?.model == "custom")

        // "b" deleted: its chat moves to the first provider left, with that provider's model.
        #expect(conversations.repoint(providers: [a]).map(\.id) == [kept.id])
        #expect(conversations.get(kept.id)?.providerId == "a")
        #expect(conversations.get(kept.id)?.model == "model-a")
    }

    @Test func editorExplainsWhatAPhoneCanReach() {
        #expect(ProviderDraft.cleartextProblem("http://192.168.1.20:11434/v1")?.hasPrefix("Use https") == true)
        #expect(ProviderDraft.cleartextProblem("http://my-pc.local:1234/v1") != nil)
        #expect(ProviderDraft.cleartextProblem("https://my-pc.tail1234.ts.net/v1") == nil)
        #expect(ProviderDraft.cleartextProblem("http://127.0.0.1:11434/v1") == nil)
        #expect(ProviderDraft.loopbackNote("http://127.0.0.1:11434/v1")?.contains("this iPhone") == true)
        #expect(ProviderDraft.loopbackNote("https://api.groq.com/openai/v1") == nil)

        var draft = ProviderDraft(presetId: "groq", displayName: "Groq", baseUrl: "https://api.groq.com/openai/v1", defaultModel: "m")
        #expect(draft.missingKey)
        #expect(draft.canSave) // allowed after a confirmation
        draft.hasStoredKey = true
        #expect(!draft.missingKey)
        draft.clearStoredKey = true
        #expect(draft.missingKey)
        draft.apiKeyInput = "sk-test"
        #expect(!draft.missingKey)
        #expect(!ProviderDraft(presetId: "ollama", kind: .ollama, baseUrl: "http://127.0.0.1:11434/v1").missingKey)

        let lan = ProviderDraft(presetId: "ollama", kind: .ollama, displayName: "PC", baseUrl: "http://192.168.1.20:11434/v1", defaultModel: "m")
        #expect(!lan.canSave)
    }

    @Test func proxyConnectFailuresNameTheProxy() {
        let refused = URLError(.cannotConnectToHost)
        let mapped = HttpClient.proxyFailure(refused, proxy: .socks(host: "127.0.0.1", port: 9050))
        #expect((mapped as? URLError)?.code == .cannotConnectToHost)
        #expect(mapped.localizedDescription == "Tor/SOCKS proxy at 127.0.0.1:9050 isn't reachable")
        #expect(HttpClient.proxyFailure(refused, proxy: .none).localizedDescription == refused.localizedDescription)
        let notFound = URLError(.badServerResponse)
        #expect(HttpClient.proxyFailure(notFound, proxy: .socks(host: "h", port: 1)).localizedDescription == notFound.localizedDescription)
    }
}

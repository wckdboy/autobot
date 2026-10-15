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
}

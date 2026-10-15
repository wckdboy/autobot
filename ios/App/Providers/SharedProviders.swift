import AutobotShared
import Foundation

// Provider presets, model lists and their parsing live in `:shared`
// (`dev.wckdboy.autobot.providers.remote`): the same table and rules as Android. The Swift values
// here are plain mirrors for SwiftUI; nothing about a provider is decided on this side.

/// A shared `ProviderPreset` as a Sendable value.
struct ProviderPreset: Sendable, Identifiable, Hashable {
    let id: String
    let kind: ProviderKind
    let displayName: String
    let group: String
    let isThisPhone: Bool
    let baseUrl: String
    let host: String
    let defaultModel: String
    let suggestedModels: [String]
    let requiresApiKey: Bool
    let hasModelsEndpoint: Bool
    let note: String?
    let privacyNote: String?
    let privacyUrl: String?
    let docsUrl: String

    init(_ p: AutobotShared.ProviderPreset) {
        id = p.id
        kind = ProviderKind(rawValue: p.kind.name) ?? .openAICompatible
        displayName = p.displayName
        group = p.group.label
        isThisPhone = p.group.name == "THIS_PHONE"
        baseUrl = p.baseUrl
        host = p.host
        defaultModel = p.defaultModel
        suggestedModels = p.suggestedModels
        requiresApiKey = p.requiresApiKey
        hasModelsEndpoint = p.modelsApi.name != "NONE"
        note = p.note
        privacyNote = p.privacyNote
        privacyUrl = p.privacyUrl
        docsUrl = p.docsUrl
    }
}

/// One model of a provider's list (a shared `RemoteModel`).
struct RemoteModelItem: Sendable, Identifiable, Hashable {
    let id: String
    let name: String?
    let caption: String
    let contextWindow: Int?

    init(_ m: AutobotShared.RemoteModel) {
        id = m.id
        name = m.name
        caption = m.caption()
        contextWindow = m.contextWindow.map { Int(truncating: $0) }
    }

    init(id: String) {
        self.id = id
        name = nil
        caption = ""
        contextWindow = nil
    }
}

enum ProviderPresets {
    static let deepSeekBaseUrl = AutobotShared.ProviderPresets.shared.DEEPSEEK_BASE_URL
    static let openRouterBaseUrl = AutobotShared.ProviderPresets.shared.OPENROUTER_BASE_URL
    static let ollamaBaseUrl = AutobotShared.ProviderPresets.shared.OLLAMA_BASE_URL
    /// Placeholder base URL of the on-device provider; it is never dialled.
    static let localBaseUrl = AutobotShared.ProviderPresets.shared.LOCAL_BASE_URL
    static let localProviderId = "local-this-phone"

    static let all: [ProviderPreset] = AutobotShared.ProviderPresets.shared.all.map(ProviderPreset.init)

    static func byId(_ id: String) -> ProviderPreset? { all.first { $0.id == id } }

    /// The canonical preset of a kind (the generic OpenAI-compatible one for `.openAICompatible`).
    static func forKind(_ kind: ProviderKind) -> ProviderPreset {
        ProviderPreset(AutobotShared.ProviderPresets.shared.forKind(kind: SharedMapping.kind(kind)))
    }

    /// The preset of a stored provider (by id, else by kind and host for older rows).
    static func resolve(_ p: Provider) -> ProviderPreset {
        ProviderPreset(AutobotShared.ProviderPresets.shared.resolve(presetId: p.presetId, kind: SharedMapping.kind(p.kind), baseUrl: p.baseUrl))
    }

    /// Presets matching [query], in the shared group order (Cloud · Open-model hosts · Local servers · This phone).
    static func search(_ query: String) -> [(group: String, presets: [ProviderPreset])] {
        let shared = AutobotShared.ProviderPresets.shared
        let matching = shared.all.filter { shared.matches(preset: $0, query: query) }
        var order: [String] = []
        var groups: [String: [ProviderPreset]] = [:]
        for p in matching {
            let label = p.group.label
            if groups[label] == nil { order.append(label) }
            groups[label, default: []].append(ProviderPreset(p))
        }
        let rank = Dictionary(uniqueKeysWithValues: AutobotShared.PresetGroup.entries.enumerated().map { ($1.label, $0) })
        return order.sorted { (rank[$0] ?? 0) < (rank[$1] ?? 0) }.map { ($0, groups[$0] ?? []) }
    }
}

/// Model lists in the shared `ModelCache` encoding.
enum ModelLists {
    /// The models of a stored list, or nil when there is none (or it is unreadable).
    static func models(_ json: String?) -> [RemoteModelItem]? {
        guard let json, let cache = ModelCache.companion.decode(json: json) else { return nil }
        return cache.models.map(RemoteModelItem.init)
    }

    /// `fetched 12 min ago`.
    static func age(fetchedAt: Int64, now: Date = .now) -> String {
        "fetched " + ModelCache.companion.age(millis: Int64(now.timeIntervalSince1970 * 1000) - fetchedAt)
    }

    /// The models a picker shows for [query] (search on id and name; the shared rule).
    static func filter(_ models: [RemoteModelItem], _ query: String, limit: Int = 30) -> [RemoteModelItem] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        let hits = models.filter { q.isEmpty || $0.id.lowercased().contains(q) || ($0.name?.lowercased().contains(q) ?? false) }
        return Array(hits.prefix(limit))
    }
}

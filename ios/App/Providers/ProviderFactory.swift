import AutobotShared
import Foundation

/// What the agent needs to know about a model to budget its context.
struct ModelInfo: Sendable {
    var contextWindow: Int
    var defaultMaxTokens: Int? = nil
    var supportsReasoning = false
}

/**
 Builds a [ChatProvider] for a stored [Provider]. A fresh policy-bound client is created per call,
 so the provider always uses the route that is effective *now* (global mode + the provider's
 routing override) and is subject to the kill switch. Port of `ProviderFactory.kt`.
 */
@MainActor
final class ProviderFactory {
    private let clients: HttpClientFactory
    private let providers: ProviderRepository
    private let library: ModelLibrary
    private let compute: ComputeProfile
    private let llm: LocalLlm

    init(clients: HttpClientFactory, providers: ProviderRepository, library: ModelLibrary, compute: ComputeProfile, llm: LocalLlm) {
        self.clients = clients
        self.providers = providers
        self.library = library
        self.compute = compute
        self.llm = llm
    }

    /// Creates a provider, reading its API key from the Keychain.
    func create(_ provider: Provider) -> ChatProvider { create(provider, apiKey: providers.apiKey(provider)) }

    /// Creates a provider with an explicit [apiKey] (e.g. an unsaved key being tested).
    func create(_ provider: Provider, apiKey: Secret?) -> ChatProvider {
        if provider.kind == .local { return LocalChatProvider(library: library, compute: compute, engine: llm) }
        let client = clients.create(tag: provider.displayName, override: provider.routing.override)
        let base = provider.baseUrl.trimmingCharacters(in: .whitespaces)
        // Fallback runtime only (the shared runtime speaks Anthropic's native API): Anthropic's
        // OpenAI SDK compatibility endpoint lives at the same base URL.
        return OpenAICompatibleProvider(baseUrl: base.isEmpty ? ProviderPresets.resolve(provider).baseUrl : base, apiKey: apiKey, client: client)
    }

    /// On-device context (KV cache memory is the limit on a phone).
    nonisolated static let localContext = 8192
    /// Unknown models get a conservative window.
    nonisolated static let defaultContextWindow = 65_536

    /// Context windows from the shared table (`ProviderLlmAdapter.modelInfo`), the cached model list first.
    nonisolated static func modelInfo(_ kind: ProviderKind, _ model: String, modelsJson: String? = nil) -> ModelInfo {
        let known = modelsJson.flatMap { ModelCache.companion.decode(json: $0) }?.find(id: model)
        let info = ProviderLlmAdapter.companion.modelInfo(kind: SharedMapping.kind(kind), model: model, known: known)
        return ModelInfo(contextWindow: Int(info.contextWindow), defaultMaxTokens: info.defaultMaxTokens.map { Int(truncating: $0) }, supportsReasoning: info.supportsReasoning)
    }
}

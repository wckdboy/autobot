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
        let url: String = switch provider.kind {
        case .deepseek: base.isEmpty ? ProviderPresets.deepSeekBaseUrl : base
        case .openRouter: base.isEmpty ? ProviderPresets.openRouterBaseUrl : base
        case .ollama: base.isEmpty ? ProviderPresets.ollamaBaseUrl : base
        case .openAICompatible, .local: base
        }
        return OpenAICompatibleProvider(baseUrl: url, apiKey: apiKey, client: client)
    }

    /// On-device context (KV cache memory is the limit on a phone).
    nonisolated static let localContext = 8192
    /// Unknown models get a conservative window.
    nonisolated static let defaultContextWindow = 65_536

    /// Best-effort context windows (same table as Android's `ProviderLlmAdapter.modelInfo`).
    nonisolated static func modelInfo(_ kind: ProviderKind, _ model: String) -> ModelInfo {
        let m = model.lowercased()
        if kind == .local { return ModelInfo(contextWindow: localContext, defaultMaxTokens: 2048) }
        if m.contains("deepseek-v4") || m.contains("deepseek-flash") { return ModelInfo(contextWindow: 1_000_000, supportsReasoning: true) }
        if m == "deepseek-reasoner" || m.contains("-r1") { return ModelInfo(contextWindow: 128_000, supportsReasoning: true) }
        if m.hasPrefix("deepseek") || m.contains("/deepseek") { return ModelInfo(contextWindow: 128_000) }
        if m.contains("qwen3") { return ModelInfo(contextWindow: 128_000, supportsReasoning: true) }
        if kind == .ollama { return ModelInfo(contextWindow: 32_768) }
        if kind == .openRouter { return ModelInfo(contextWindow: 128_000) }
        return ModelInfo(contextWindow: defaultContextWindow)
    }
}

import Foundation

/// A chat thread. [providerId]/[model] record the last selection for this conversation.
struct Conversation: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var title: String
    var providerId: String?
    var model: String?
    var createdAt: Int64
    var updatedAt: Int64
}

/**
 `.local` runs on this phone (llama.cpp engine); its base URL is unused and never dialled.
 `.anthropic` speaks Anthropic's native Messages API, every other kind OpenAI chat completions;
 which provider a row is beyond that is its preset id. Raw values are the Kotlin names.
 */
enum ProviderKind: String, Codable, CaseIterable, Sendable {
    case deepseek = "DEEPSEEK", openAICompatible = "OPENAI_COMPATIBLE", openRouter = "OPENROUTER", ollama = "OLLAMA", local = "LOCAL",
         anthropic = "ANTHROPIC"
}

/// Per-provider network routing. `.inherit` follows the global network mode.
enum ProviderRouting: String, Codable, CaseIterable, Sendable {
    case direct = "DIRECT", tor = "TOR", socks5 = "SOCKS5", inherit = "INHERIT"

    var override: RouteOverride {
        switch self {
        case .direct: .direct
        case .tor: .tor
        case .socks5: .socks5
        case .inherit: .inherit
        }
    }

    var label: String {
        switch self {
        case .inherit: "Inherit"
        case .direct: "Direct"
        case .tor: "Tor"
        case .socks5: "SOCKS5"
        }
    }
}

/**
 A configured model provider. The API key itself is never stored here; [apiKeyRef] is an opaque
 reference into the Keychain-backed [SecretStore]. [presetId] names its shared preset; rows from
 before 1.4 have none and are matched by kind and host.
 */
struct Provider: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var kind: ProviderKind
    var displayName: String
    var baseUrl: String
    var defaultModel: String
    var routing: ProviderRouting = .inherit
    var apiKeyRef: String? = nil
    var presetId: String? = nil
}

/// A provider's fetched model list: [json] is the shared `ModelCache` encoding.
struct ProviderModels: Codable, Equatable, Sendable {
    var providerId: String
    var fetchedAt: Int64
    var json: String
}

/**
 A generated image. Pixels live in an encrypted file; this row holds the searchable parameters.
 [paramsJson] keeps the full request for "reuse parameters".
 */
struct GalleryItem: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var createdAt: Int64
    var mode: String
    var prompt: String
    var negativePrompt: String
    var width: Int
    var height: Int
    var seed: Int64
    var steps: Int
    var cfgScale: Double
    var sampler: String
    var engine: String
    var model: String?
    var durationMs: Int64
    var paramsJson: String
    var favorite: Bool = false
}

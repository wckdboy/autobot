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
 before 1.4 have none and are matched by kind and host. [helperModel] runs subagents and context
 summaries (nil: the chat's own model; older rows have none and decode as nil).
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
    var helperModel: String? = nil
}

/// A provider's fetched model list: [json] is the shared `ModelCache` encoding.
struct ProviderModels: Codable, Equatable, Sendable {
    var providerId: String
    var fetchedAt: Int64
    var json: String
}

/**
 A configured MCP server (Streamable HTTP). The bearer token is never stored here; [tokenRef] is
 an opaque reference into the Keychain-backed [SecretStore]. Untrusted servers ask before every
 tool call; [updatedAt] changes on every save so the agent reconnects with the new settings.
 */
struct McpServer: Codable, Identifiable, Equatable, Sendable {
    var id: String
    var name: String
    var url: String
    var routing: ProviderRouting = .inherit
    var enabled = true
    var trusted = false
    var tokenRef: String? = nil
    var updatedAt: Int64 = 0
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

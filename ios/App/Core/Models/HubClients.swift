import AutobotShared
import Foundation

/// A hub request failed (port of `HubException`).
struct HubError: LocalizedError, Sendable {
    enum Code: Sendable { case blocked, unauthorized, gated, notFound, rateLimited, failed }
    let message: String
    var code: Code = .failed
    var errorDescription: String? { message }
}

/**
 Sends a shared hub request (`HuggingFaceApi` / `CivitaiApi`) through the policy-bound client:
 kill switch, route, audit log, no cookies, no redirects. The request only ever addresses the
 hub's own host, so its token goes nowhere else.
 */
@MainActor
func hubSend(_ clients: HttpClientFactory, _ spec: HttpRequestSpec) async throws -> HubResponse {
    guard let url = URL(string: spec.url) else { throw HubError(message: "Invalid URL") }
    var request = URLRequest(url: url)
    request.httpMethod = spec.method
    spec.headers.forEach { request.setValue($0.value, forHTTPHeaderField: $0.key) }
    let tag = spec.tag
    let response: HttpResponse
    do {
        response = try await clients.create(tag: tag).send(request)
    } catch let e as NetworkBlockedError {
        throw HubError(message: e.message, code: .blocked)
    } catch {
        throw HubError(message: error.localizedDescription)
    }
    return HubResponse(status: Int32(response.status), headers: response.headers, body: String(decoding: response.body, as: UTF8.self))
}

private extension Secret {
    var token: String? { isBlank ? nil : reveal() }
}

// MARK: - Hugging Face

struct HfModelSummary: Identifiable, Equatable, Sendable {
    let id: String
    let downloads: Int64
    let likes: Int64
    let license: String?
    let gated: Bool
    let tags: [String]
}

struct HfFile: Equatable, Sendable {
    let path: String
    let sizeBytes: Int64
    let sha256: String?
}

struct HfRepo: Equatable, Sendable {
    let id: String
    let commit: String?
    let gated: Bool
    let license: String?
    let files: [HfFile]

    /// Compiled Core ML Stable Diffusion folders in this repo (`…/compiled`, `…_compiled`) that
    /// contain everything the pipeline needs, Neural Engine builds first.
    var coreMLBundles: [String] { CoreMLPackages.shared.bundles(repo: kotlin) }

    /// Bytes of a Core ML [folder] without the safety checker.
    func coreMLSize(_ folder: String) -> Int64 { CoreMLPackages.shared.sizeOf(repo: kotlin, folder: folder) }
}

struct HfUser: Sendable {
    let name: String
    let fullname: String?
}

/// Hugging Face Hub (search, file listing, whoami, install plans) over the shared `HuggingFaceApi`.
@MainActor
final class HuggingFaceClient {
    private let clients: HttpClientFactory
    private let accounts: ModelAccounts
    private let api = HuggingFaceApi.shared

    init(clients: HttpClientFactory, accounts: ModelAccounts) {
        self.clients = clients
        self.accounts = accounts
    }

    /// Searches models. [gguf] limits to GGUF repos (chat/code); [coreML] to Core ML diffusion repos.
    func search(_ query: String, gguf: Bool, coreML: Bool = false, limit: Int = 30) async throws -> [HfModelSummary] {
        let spec = api.searchRequest(query: query, gguf: gguf, limit: Int32(limit), author: nil, coreML: coreML, token: accounts.huggingFaceToken()?.token)
        let response = try await hubSend(clients, spec)
        return try HubError.rethrowing { try api.parseSearch(response: response) }.map(HfModelSummary.init)
    }

    /// Files with sizes and LFS SHA-256s, plus the commit to pin downloads to.
    func repo(_ id: String) async throws -> HfRepo {
        let response = try await hubSend(clients, api.repoRequest(id: id, token: accounts.huggingFaceToken()?.token))
        return HfRepo(try HubError.rethrowing { try api.parseRepo(response: response, id: id) })
    }

    /// Validates a token; returns the account name.
    func whoami(_ token: Secret) async throws -> HfUser {
        let response = try await hubSend(clients, api.whoamiRequest(token: token.reveal()))
        let user = try HubError.rethrowing { try api.parseWhoami(response: response) }
        return HfUser(name: user.name, fullname: user.fullname)
    }

    func resolveUrl(_ repo: String, revision: String?, path: String) -> String {
        api.resolveUrl(repo: repo, revision: revision, path: path)
    }

    /// Builds an install plan for one file of [repo] (with the vision projector for chat models).
    func plan(_ repo: HfRepo, _ file: HfFile, kind: ModelKind) -> ModelPlan {
        ModelPlan(api.plan(repo: repo.kotlin, file: file.kotlin, kind: kind.kotlin, platform: .ios))
    }

    /// Install plan for a compiled Core ML Stable Diffusion folder: every file of [folder],
    /// stored under its relative path in one model directory.
    func coreMLPlan(_ repo: HfRepo, folder: String) -> ModelPlan {
        ModelPlan(CoreMLPackages.shared.plan(repo: repo.kotlin, folder: folder))
    }
}

// MARK: - Civitai

struct CivitaiFile: Equatable, Sendable {
    let name: String
    let sizeBytes: Int64
    let sha256: String?
    let format: String?
    let primary: Bool
    let downloadUrl: String
}

struct CivitaiVersion: Equatable, Sendable {
    let id: Int64
    let name: String
    let baseModel: String?
    let trainedWords: [String]
    let files: [CivitaiFile]
    let previewUrl: String?
    let previewMature: Bool
    let earlyAccess: Bool
}

struct CivitaiModel: Identifiable, Equatable, Sendable {
    let id: Int64
    let name: String
    let type: String
    let nsfw: Bool
    let creator: String?
    let downloads: Int64
    let allowCommercialUse: String?
    let versions: [CivitaiVersion]
}

struct CivitaiPage: Sendable {
    let items: [CivitaiModel]
    let nextCursor: String?
}

/// Civitai public API v1 (civitai.com and civitai.red serve the same API) over the shared `CivitaiApi`.
@MainActor
final class CivitaiClient {
    private let clients: HttpClientFactory
    private let accounts: ModelAccounts
    private let api = CivitaiApi.shared

    init(clients: HttpClientFactory, accounts: ModelAccounts) {
        self.clients = clients
        self.accounts = accounts
    }

    func search(_ query: String, types: [String], baseModels: [String] = [], cursor: String? = nil, limit: Int = 20) async throws -> CivitaiPage {
        let account = accounts.state
        let spec = api.searchRequest(host: account.civitaiHost.kotlin, query: query, types: types, baseModels: baseModels, cursor: cursor,
                                     limit: Int32(limit), showMature: account.showMature, key: accounts.civitaiKey()?.token)
        let response = try await hubSend(clients, spec)
        let page = try HubError.rethrowing { try api.parseSearch(response: response, showMature: account.showMature) }
        return CivitaiPage(items: page.items.map(CivitaiModel.init), nextCursor: page.nextCursor)
    }

    /// Validates an API key; returns the username.
    func me(_ key: Secret) async throws -> String {
        let response = try await hubSend(clients, api.meRequest(host: accounts.state.civitaiHost.kotlin, key: key.reveal()))
        return try HubError.rethrowing { try api.parseMe(response: response) }
    }

    /// Maps a model version to an install plan (primary file only).
    func plan(_ model: CivitaiModel, _ version: CivitaiVersion) -> ModelPlan? { civitaiPlan(model, version) }
}

/// Maps a Civitai version to an install plan (primary file only). Checkpoints and LoRAs have no
/// engine on iOS (Core ML needs converted models): they are for PC backends.
func civitaiPlan(_ model: CivitaiModel, _ version: CivitaiVersion) -> ModelPlan? {
    HubsKt.civitaiPlan(model: model.kotlin, version: version.kotlin, platform: .ios).map(ModelPlan.init)
}

/**
 Fetches catalog preview images (Civitai) through the policy-bound client: kill switch, route and
 audit log apply, no cookies. Only the known image CDN is allowed and responses are capped.
 */
@MainActor
final class PreviewImages {
    private static let maxBytes = 3 * 1024 * 1024
    private static let allowedHosts: Set<String> = ["image.civitai.com"]
    /// Encoded previews kept, by URL: bounded by their bytes (64 entries of up to 3 MB could hold
    /// 192 MB) and evicted under memory pressure.
    static let cacheBytes = 12 * 1024 * 1024
    private let clients: HttpClientFactory
    private let cache: NSCache<NSString, NSData> = {
        let c = NSCache<NSString, NSData>()
        c.totalCostLimit = PreviewImages.cacheBytes
        return c
    }()

    init(clients: HttpClientFactory) { self.clients = clients }

    func load(_ url: String, width: Int = 320) async -> Data? {
        if let cached = cache.object(forKey: url as NSString) { return cached as Data }
        guard let parsed = URL(string: url), let host = parsed.host(), Self.allowedHosts.contains(host) else { return nil }
        // Civitai's image CDN resizes on request via a `width=` path segment.
        let sized = URL(string: url.replacingOccurrences(of: "/original=true/", with: "/width=\(width)/")) ?? parsed
        var request = URLRequest(url: sized)
        request.setValue("image/*", forHTTPHeaderField: "Accept")
        guard let response = try? await clients.create(tag: "models:preview").send(request), response.isSuccessful,
              response.body.count <= Self.maxBytes else { return nil }
        cache.setObject(response.body as NSData, forKey: url as NSString, cost: response.body.count)
        return response.body
    }
}

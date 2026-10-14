import Foundation

/// A hub request failed (port of `HubException`).
struct HubError: LocalizedError, Sendable {
    enum Code: Sendable { case blocked, unauthorized, gated, notFound, rateLimited, failed }
    let message: String
    var code: Code = .failed
    var errorDescription: String? { message }
}

/// Shared GET-JSON helper: policy-bound client, typed failures, no cookies, no redirects.
func hubGetJSON(_ clients: HttpClientFactory, tag: String, url: URL, bearer: Secret? = nil) async throws -> Any {
    var request = URLRequest(url: url)
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    if let bearer, !bearer.isBlank { request.setValue("Bearer \(bearer.reveal())", forHTTPHeaderField: "Authorization") }
    let response: HttpResponse
    do {
        response = try await clients.create(tag: tag).send(request)
    } catch let e as NetworkBlockedError {
        throw HubError(message: e.message, code: .blocked)
    } catch {
        throw HubError(message: error.localizedDescription)
    }
    if response.isSuccessful {
        guard let json = try? JSONSerialization.jsonObject(with: response.body) else { throw HubError(message: "Unexpected response") }
        return json
    }
    if response.header("X-Error-Code") == "GatedRepo" {
        throw HubError(message: "This repository is gated: accept its terms on huggingface.co, then sign in", code: .gated)
    }
    switch response.status {
    case 401, 403: throw HubError(message: "Not authorized (HTTP \(response.status))", code: .unauthorized)
    case 404: throw HubError(message: "Not found", code: .notFound)
    case 429: throw HubError(message: "Rate limited — try again in a few minutes", code: .rateLimited)
    default: throw HubError(message: "HTTP \(response.status)")
    }
}

private func str(_ v: Any?) -> String? { v as? String }
private func long(_ v: Any?) -> Int64? { (v as? NSNumber)?.int64Value }
private func bool(_ v: Any?) -> Bool? { (v as? NSNumber).flatMap { CFGetTypeID($0) == CFBooleanGetTypeID() ? $0.boolValue : nil } }

/// `gated` is `false`, `"auto"` or `"manual"`.
private func isGated(_ v: Any?) -> Bool { bool(v) == true || (str(v).map { $0 != "false" } ?? false) }

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
    /// contain everything the pipeline needs.
    var coreMLBundles: [String] {
        let dirs = Set(files.compactMap { f -> String? in
            guard let r = f.path.range(of: ".mlmodelc/") else { return nil }
            let model = f.path[..<r.lowerBound]
            return model.contains("/") ? String(model[..<model.lastIndex(of: "/")!]) : ""
        })
        return dirs.filter { dir in
            let prefix = dir.isEmpty ? "" : dir + "/"
            let has = { (name: String) in files.contains { $0.path == prefix + name || $0.path.hasPrefix(prefix + name + "/") } }
            return has("VAEDecoder.mlmodelc") && has("vocab.json") && has("merges.txt") &&
                (has("Unet.mlmodelc") || has("UnetChunk1.mlmodelc")) && has("TextEncoder.mlmodelc")
        }.sorted { a, b in
            // Neural Engine builds first.
            let ae = a.contains("split_einsum"), be = b.contains("split_einsum")
            return ae != be ? ae : a < b
        }
    }
}

struct HfUser: Sendable {
    let name: String
    let fullname: String?
}

/// Hugging Face Hub REST API (search, file listing, whoami) — port of `HuggingFaceClient`.
@MainActor
final class HuggingFaceClient {
    private let clients: HttpClientFactory
    private let accounts: ModelAccounts
    private let base = URL(string: "https://huggingface.co")!

    init(clients: HttpClientFactory, accounts: ModelAccounts) {
        self.clients = clients
        self.accounts = accounts
    }

    /// Searches models. [gguf] limits to GGUF repos (chat/code); [coreML] to Core ML diffusion repos.
    func search(_ query: String, gguf: Bool, coreML: Bool = false, limit: Int = 30) async throws -> [HfModelSummary] {
        var c = URLComponents(url: base.appending(path: "api/models"), resolvingAgainstBaseURL: false)!
        var items: [URLQueryItem] = []
        let q = query.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty { items.append(URLQueryItem(name: "search", value: q)) }
        items += [
            URLQueryItem(name: "sort", value: "downloads"),
            URLQueryItem(name: "direction", value: "-1"),
            URLQueryItem(name: "limit", value: "\(limit)"),
        ]
        if coreML {
            items.append(URLQueryItem(name: "filter", value: "core-ml"))
        } else {
            items.append(URLQueryItem(name: "filter", value: gguf ? "gguf" : "text-to-image"))
        }
        for e in ["downloads", "likes", "gated", "tags"] { items.append(URLQueryItem(name: "expand[]", value: e)) }
        c.queryItems = items
        let json = try await hubGetJSON(clients, tag: "models:huggingface", url: c.url!, bearer: accounts.huggingFaceToken())
        return (json as? [[String: Any]] ?? []).compactMap { o in
            guard let id = str(o["id"]) else { return nil }
            let tags = (o["tags"] as? [Any])?.compactMap(str) ?? []
            return HfModelSummary(
                id: id,
                downloads: long(o["downloads"]) ?? 0,
                likes: long(o["likes"]) ?? 0,
                license: tags.first { $0.hasPrefix("license:") }.map { String($0.dropFirst(8)) },
                gated: isGated(o["gated"]),
                tags: tags,
            )
        }
    }

    /// Files with sizes and LFS SHA-256s, plus the commit to pin downloads to.
    func repo(_ id: String) async throws -> HfRepo {
        var c = URLComponents(url: base.appending(path: "api/models/\(id)"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "blobs", value: "true")]
        guard let o = try await hubGetJSON(clients, tag: "models:huggingface", url: c.url!, bearer: accounts.huggingFaceToken()) as? [String: Any] else {
            throw HubError(message: "Unexpected response")
        }
        let siblings = (o["siblings"] as? [[String: Any]] ?? []).compactMap { s -> HfFile? in
            guard let path = str(s["rfilename"]) else { return nil }
            let lfs = s["lfs"] as? [String: Any]
            return HfFile(path: path, sizeBytes: long(s["size"]) ?? long(lfs?["size"]) ?? 0, sha256: str(lfs?["sha256"])?.lowercased())
        }
        return HfRepo(
            id: str(o["id"]) ?? id,
            commit: str(o["sha"]),
            gated: isGated(o["gated"]),
            license: str((o["cardData"] as? [String: Any])?["license"]),
            files: siblings,
        )
    }

    /// Validates a token; returns the account name.
    func whoami(_ token: Secret) async throws -> HfUser {
        guard let o = try await hubGetJSON(clients, tag: "models:huggingface", url: base.appending(path: "api/whoami-v2"), bearer: token) as? [String: Any],
              let name = str(o["name"]) else { throw HubError(message: "Unexpected whoami response") }
        return HfUser(name: name, fullname: str(o["fullname"]))
    }

    func resolveUrl(_ repo: String, revision: String?, path: String) -> String {
        "https://huggingface.co/\(repo)/resolve/\(revision ?? "main")/\(path.split(separator: "/").map { $0.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? String($0) }.joined(separator: "/"))"
    }

    /// Best vision projector in a repo (F16 preferred, then Q8_0, then any).
    private func visionProjector(_ repo: HfRepo) -> HfFile? {
        let projectors = repo.files.filter {
            ($0.path.split(separator: "/").last ?? "").lowercased().hasPrefix("mmproj") && $0.path.lowercased().hasSuffix(".gguf")
        }
        return projectors.first { $0.path.lowercased().contains("f16") } ?? projectors.first { $0.path.lowercased().contains("q8_0") } ?? projectors.first
    }

    /// Builds an install plan for one file of [repo] (with the vision projector for chat models).
    func plan(_ repo: HfRepo, _ file: HfFile, kind: ModelKind) -> ModelPlan {
        let owner = String(repo.id.split(separator: "/").first ?? "")
        let format = formatOf(file.path, owner: owner)
        let name = String(file.path.split(separator: "/").last ?? "")
        var files = [ModelFile(role: .model, name: name, url: resolveUrl(repo.id, revision: repo.commit, path: file.path), sizeBytes: file.sizeBytes, sha256: file.sha256, auth: .huggingFace)]
        if format == .gguf, kind == .chat || kind == .code, let p = visionProjector(repo) {
            files.append(ModelFile(role: .mmproj, name: String(p.path.split(separator: "/").last ?? ""), url: resolveUrl(repo.id, revision: repo.commit, path: p.path), sizeBytes: p.sizeBytes, sha256: p.sha256, auth: .huggingFace))
        }
        return ModelPlan(
            id: "hf:\(repo.id):\(file.path)",
            kind: kind,
            source: .huggingFace,
            title: (name as NSString).deletingPathExtension,
            subtitle: repo.id,
            format: format,
            engine: engineFor(kind, format),
            license: repo.license,
            manifest: ModelManifest(
                files: files,
                pageUrl: "https://huggingface.co/\(repo.id)",
                gated: repo.gated,
                notes: format == .localDream ? "Local Dream package: run it with Local Dream's host mode (Imagine → backends)." : nil,
            ),
        )
    }

    /// Install plan for a compiled Core ML Stable Diffusion folder: every file of [folder],
    /// stored under its relative path in one model directory.
    func coreMLPlan(_ repo: HfRepo, folder: String) -> ModelPlan {
        let prefix = folder.isEmpty ? "" : folder + "/"
        let files = repo.files.filter { $0.path.hasPrefix(prefix) && !$0.path.dropFirst(prefix.count).hasPrefix("SafetyChecker") }.map { f in
            ModelFile(role: .other, name: String(f.path.dropFirst(prefix.count)), url: resolveUrl(repo.id, revision: repo.commit, path: f.path),
                      sizeBytes: f.sizeBytes, sha256: f.sha256, auth: .huggingFace, keepName: true)
        }
        let xl = files.contains { $0.name.hasPrefix("TextEncoder2.mlmodelc") }
        let size = xl ? 768 : 512
        return ModelPlan(
            id: "hf:\(repo.id):\(folder)",
            kind: .image,
            source: .huggingFace,
            title: String(repo.id.split(separator: "/").last ?? Substring(repo.id)),
            subtitle: "\(repo.id) · \(folder.isEmpty ? "compiled" : folder)",
            format: .coreML,
            engine: .coreML,
            baseModel: xl ? "SDXL" : nil,
            license: repo.license,
            manifest: ModelManifest(
                files: files,
                pageUrl: "https://huggingface.co/\(repo.id)",
                recommended: Recommended(steps: 25, cfg: 7.5, sampler: "DPM++ 2M", scheduler: "Karras", width: size, height: size),
                gated: repo.gated,
            ),
        )
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

/// Civitai public API v1 (civitai.com and civitai.red serve the same API).
@MainActor
final class CivitaiClient {
    private let clients: HttpClientFactory
    private let accounts: ModelAccounts

    init(clients: HttpClientFactory, accounts: ModelAccounts) {
        self.clients = clients
        self.accounts = accounts
    }

    private var base: URL { URL(string: "https://\(accounts.state.civitaiHost.host)")! }

    func search(_ query: String, types: [String], baseModels: [String] = [], cursor: String? = nil, limit: Int = 20) async throws -> CivitaiPage {
        let account = accounts.state
        var c = URLComponents(url: base.appending(path: "api/v1/models"), resolvingAgainstBaseURL: false)!
        var items: [URLQueryItem] = []
        let q = query.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty { items.append(URLQueryItem(name: "query", value: q)) }
        items += types.map { URLQueryItem(name: "types", value: $0) }
        items += baseModels.map { URLQueryItem(name: "baseModels", value: $0) }
        items += [
            URLQueryItem(name: "sort", value: "Most Downloaded"),
            URLQueryItem(name: "limit", value: "\(limit)"),
            URLQueryItem(name: "nsfw", value: account.showMature ? "true" : "false"),
            URLQueryItem(name: "primaryFileOnly", value: "true"),
        ]
        if let cursor { items.append(URLQueryItem(name: "cursor", value: cursor)) }
        c.queryItems = items
        guard let root = try await hubGetJSON(clients, tag: "models:civitai", url: c.url!, bearer: accounts.civitaiKey()) as? [String: Any] else {
            throw HubError(message: "Unexpected response")
        }
        let models = (root["items"] as? [[String: Any]] ?? []).compactMap { parseCivitaiModel($0, showMature: account.showMature) }
        return CivitaiPage(items: models, nextCursor: str((root["metadata"] as? [String: Any])?["nextCursor"]))
    }

    /// Validates an API key; returns the username.
    func me(_ key: Secret) async throws -> String {
        guard let o = try await hubGetJSON(clients, tag: "models:civitai", url: base.appending(path: "api/v1/me"), bearer: key) as? [String: Any],
              let name = str(o["username"]) else { throw HubError(message: "Unexpected /me response") }
        return name
    }

    /// Maps a model version to an install plan (primary file only).
    func plan(_ model: CivitaiModel, _ version: CivitaiVersion) -> ModelPlan? { civitaiPlan(model, version) }
}

/// Parses one `items[]` entry; drops minors, real people and (unless allowed) mature models.
func parseCivitaiModel(_ o: [String: Any], showMature: Bool) -> CivitaiModel? {
    // Never surface content flagged as depicting minors or real people.
    if bool(o["minor"]) == true || bool(o["poi"]) == true { return nil }
    let nsfw = bool(o["nsfw"]) == true
    if nsfw && !showMature { return nil }
    let versions = (o["modelVersions"] as? [[String: Any]] ?? []).compactMap { v -> CivitaiVersion? in
        guard let id = long(v["id"]) else { return nil }
        let files = (v["files"] as? [[String: Any]] ?? []).compactMap { f -> CivitaiFile? in
            guard let name = str(f["name"]), let url = str(f["downloadUrl"]) else { return nil }
            return CivitaiFile(
                name: name,
                sizeBytes: Int64(((f["sizeKB"] as? NSNumber)?.doubleValue ?? 0) * 1024),
                sha256: str((f["hashes"] as? [String: Any])?["SHA256"])?.lowercased(),
                format: str((f["metadata"] as? [String: Any])?["format"]),
                primary: bool(f["primary"]) == true,
                downloadUrl: url,
            )
        }
        let image = (v["images"] as? [[String: Any]] ?? []).lazy.compactMap { im -> (String, Bool)? in
            if bool(im["minor"]) == true || bool(im["poi"]) == true { return nil }
            let level = long(im["nsfwLevel"]) ?? 1
            if level & 32 != 0 { return nil }
            return str(im["url"]).map { ($0, level & 28 != 0) }
        }.first
        return CivitaiVersion(
            id: id,
            name: str(v["name"]) ?? "",
            baseModel: str(v["baseModel"]),
            trainedWords: (v["trainedWords"] as? [Any])?.compactMap(str) ?? [],
            files: files,
            previewUrl: image?.0,
            previewMature: image?.1 ?? false,
            earlyAccess: str(v["availability"]) == "EarlyAccess",
        )
    }
    guard let id = long(o["id"]) else { return nil }
    let commercial: String? = if let list = o["allowCommercialUse"] as? [Any] { list.compactMap(str).joined(separator: ",") } else { str(o["allowCommercialUse"]) }
    return CivitaiModel(
        id: id,
        name: str(o["name"]) ?? "",
        type: str(o["type"]) ?? "",
        nsfw: nsfw,
        creator: str((o["creator"] as? [String: Any])?["username"]),
        downloads: long((o["stats"] as? [String: Any])?["downloadCount"]) ?? 0,
        allowCommercialUse: commercial,
        versions: versions,
    )
}

/// Maps a Civitai version to an install plan (primary file only). Checkpoints and LoRAs have no
/// engine on iOS (Core ML needs converted models): they are for PC backends.
func civitaiPlan(_ model: CivitaiModel, _ version: CivitaiVersion) -> ModelPlan? {
    guard let file = version.files.first(where: \.primary) ?? version.files.first else { return nil }
    let kind: ModelKind = switch model.type {
    case "Checkpoint": .image
    case "LORA", "LoCon", "DoRA": .lora
    case "TextualInversion": .embedding
    case "VAE": .vae
    case "Upscaler": .upscaler
    case "LLM": .chat
    default: .other
    }
    let format = formatOf(file.name)
    return ModelPlan(
        id: "civitai:\(version.id)",
        kind: kind,
        source: .civitai,
        title: model.name,
        subtitle: [version.name, version.baseModel].compactMap { $0 }.joined(separator: " · "),
        format: format,
        engine: engineFor(kind, format),
        baseModel: version.baseModel,
        license: model.allowCommercialUse.map { "civitai (commercial: \($0))" },
        nsfw: model.nsfw,
        manifest: ModelManifest(
            files: [ModelFile(role: .model, name: file.name, url: file.downloadUrl, sizeBytes: file.sizeBytes, sha256: file.sha256, auth: .civitai)],
            pageUrl: "https://civitai.com/models/\(model.id)",
            trainedWords: version.trainedWords,
        ),
    )
}

/**
 Fetches catalog preview images (Civitai) through the policy-bound client: kill switch, route and
 audit log apply, no cookies. Only the known image CDN is allowed and responses are capped.
 */
@MainActor
final class PreviewImages {
    private static let maxBytes = 3 * 1024 * 1024
    private static let allowedHosts: Set<String> = ["image.civitai.com"]
    private let clients: HttpClientFactory
    private var cache: [String: Data] = [:]

    init(clients: HttpClientFactory) { self.clients = clients }

    func load(_ url: String, width: Int = 320) async -> Data? {
        if let cached = cache[url] { return cached }
        guard let parsed = URL(string: url), let host = parsed.host(), Self.allowedHosts.contains(host) else { return nil }
        // Civitai's image CDN resizes on request via a `width=` path segment.
        let sized = URL(string: url.replacingOccurrences(of: "/original=true/", with: "/width=\(width)/")) ?? parsed
        var request = URLRequest(url: sized)
        request.setValue("image/*", forHTTPHeaderField: "Accept")
        guard let response = try? await clients.create(tag: "models:preview").send(request), response.isSuccessful,
              response.body.count <= Self.maxBytes else { return nil }
        if cache.count > 64 { cache.removeAll() }
        cache[url] = response.body
        return response.body
    }
}

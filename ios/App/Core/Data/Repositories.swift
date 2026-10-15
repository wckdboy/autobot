import CryptoKit
import Foundation
import Observation

/**
 Conversations. Transparently routes incognito conversations (id prefix [incognitoPrefix]) to
 process memory and everything else to the [EncryptedStore]. Incognito threads never touch disk.
 */
@MainActor
@Observable
final class ConversationRepository {
    nonisolated static let defaultTitle = "New chat"
    nonisolated static let incognitoPrefix = "incognito-"
    private nonisolated static let titleLength = 48
    private nonisolated static let collection = "conversations"

    /// Persistent conversations and in-memory incognito ones, newest first.
    private(set) var conversations: [Conversation] = []
    @ObservationIgnored private var stored: [String: Conversation] = [:]
    @ObservationIgnored private var incognito: [String: Conversation] = [:]
    @ObservationIgnored private let store: EncryptedStore

    init(store: EncryptedStore) {
        self.store = store
    }

    /// Loads persisted conversations (needs the device to be unlocked).
    func load() {
        stored = Dictionary(((try? store.all(Self.collection, as: Conversation.self)) ?? []).map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        publish()
    }

    func isIncognito(_ id: String) -> Bool { id.hasPrefix(Self.incognitoPrefix) }

    func get(_ id: String) -> Conversation? { isIncognito(id) ? incognito[id] : stored[id] }

    @discardableResult
    func create(incognito isIncognito: Bool, providerId: String?, model: String?, title: String = ConversationRepository.defaultTitle) -> Conversation {
        let now = nowMillis()
        let id = (isIncognito ? Self.incognitoPrefix : "") + UUID().uuidString.lowercased()
        let conversation = Conversation(id: id, title: title, providerId: providerId, model: model, createdAt: now, updatedAt: now)
        update(conversation)
        return conversation
    }

    func update(_ conversation: Conversation) {
        if isIncognito(conversation.id) {
            incognito[conversation.id] = conversation
        } else {
            stored[conversation.id] = conversation
            try? store.put(Self.collection, id: conversation.id, conversation)
        }
        publish()
    }

    /// Titles a new conversation from its first prompt and bumps it to the top.
    func titleFromPrompt(_ id: String, _ prompt: String) {
        guard var c = get(id) else { return }
        if c.title == Self.defaultTitle {
            let first = prompt.split(separator: "\n", omittingEmptySubsequences: false).first.map(String.init) ?? ""
            let trimmed = first.trimmingCharacters(in: .whitespaces)
            c.title = String((trimmed.isEmpty ? "Image" : trimmed).prefix(Self.titleLength))
        }
        c.updatedAt = nowMillis()
        update(c)
    }

    func touch(_ id: String) {
        guard var c = get(id) else { return }
        c.updatedAt = nowMillis()
        update(c)
    }

    func delete(_ id: String) {
        if isIncognito(id) {
            incognito.removeValue(forKey: id)
        } else {
            stored.removeValue(forKey: id)
            try? store.delete(Self.collection, id: id)
            try? store.deleteEvents(sessionId: id)
        }
        publish()
    }

    /// Drops every incognito conversation (panic wipe).
    func clearIncognito() {
        incognito.removeAll()
        publish()
    }

    private func publish() {
        conversations = (Array(incognito.values) + Array(stored.values)).sorted { $0.updatedAt > $1.updatedAt }
    }
}

/// How [ProviderRepository.save] treats the stored API key.
enum ApiKeyUpdate: CustomStringConvertible {
    /// Leave the existing key (if any) unchanged.
    case keep
    /// Delete the stored key.
    case clear
    /// Replace the key.
    case set(Secret)

    var description: String {
        switch self {
        case .keep: "Keep"
        case .clear: "Clear"
        case .set: "Set(\(Secret.redacted))"
        }
    }
}

/**
 Provider configurations. API keys are stored exclusively in the Keychain ([SecretStore]); the
 row only holds an opaque reference.
 */
@MainActor
@Observable
final class ProviderRepository {
    private nonisolated static let collection = "providers"
    private nonisolated static let modelsCollection = "provider_models"

    /// Providers in display order.
    private(set) var providers: [Provider] = []
    /// Fetched model lists by provider id (sealed in the encrypted store like the providers).
    private(set) var modelLists: [String: ProviderModels] = [:]
    @ObservationIgnored private let store: EncryptedStore
    @ObservationIgnored private let secrets: SecretStore
    /// Called after every change (keeps the agent bridge's provider snapshot current).
    @ObservationIgnored var onChange: (([Provider]) -> Void)?

    init(store: EncryptedStore, secrets: SecretStore) {
        self.store = store
        self.secrets = secrets
    }

    func load() {
        providers = sorted((try? store.all(Self.collection, as: Provider.self)) ?? [])
        let lists = (try? store.all(Self.modelsCollection, as: ProviderModels.self)) ?? []
        modelLists = Dictionary(lists.map { ($0.providerId, $0) }, uniquingKeysWith: { a, _ in a })
        onChange?(providers)
    }

    /// Stores a fetched model list ([json]: `ModelCache`) for an existing provider.
    func saveModels(_ providerId: String, fetchedAt: Int64, json: String) {
        guard get(providerId) != nil else { return }
        let row = ProviderModels(providerId: providerId, fetchedAt: fetchedAt, json: json)
        try? store.put(Self.modelsCollection, id: providerId, row)
        modelLists[providerId] = row
        onChange?(providers)
    }

    func get(_ id: String) -> Provider? { providers.first { $0.id == id } }

    /// The first provider in display order, used when a conversation has none selected.
    var firstProviderId: String? { providers.first?.id }

    func newId() -> String { UUID().uuidString.lowercased() }

    /// Upserts [provider] and applies [apiKey]. Returns the stored provider.
    @discardableResult
    func save(_ provider: Provider, apiKey: ApiKeyUpdate = .keep) throws -> Provider {
        let currentRef = get(provider.id)?.apiKeyRef ?? provider.apiKeyRef
        var ref: String?
        switch apiKey {
        case .keep:
            ref = currentRef
        case .clear:
            if let currentRef { secrets.delete(currentRef) }
            ref = nil
        case .set(let secret):
            let newRef = currentRef ?? secrets.newRef()
            try secrets.put(newRef, secret)
            ref = newRef
        }
        var stored = provider
        stored.apiKeyRef = ref
        try store.put(Self.collection, id: stored.id, stored)
        providers = sorted(providers.filter { $0.id != stored.id } + [stored])
        onChange?(providers)
        return stored
    }

    /// Deletes [id] and its stored API key.
    func delete(_ id: String) {
        if let ref = get(id)?.apiKeyRef { secrets.delete(ref) }
        try? store.delete(Self.collection, id: id)
        try? store.delete(Self.modelsCollection, id: id)
        modelLists[id] = nil
        providers.removeAll { $0.id == id }
        onChange?(providers)
    }

    /// Decrypts the API key of [provider], if one is stored.
    func apiKey(_ provider: Provider) -> Secret? { provider.apiKeyRef.flatMap(secrets.get) }

    func hasApiKey(_ provider: Provider) -> Bool { provider.apiKeyRef.map(secrets.contains) ?? false }

    private func sorted(_ list: [Provider]) -> [Provider] {
        list.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
    }
}

/**
 Generated images. Metadata lives in the encrypted store; pixels are AES-256-GCM sealed files
 under `gallery/` using the media key, with the item id as associated data so files cannot be
 swapped between rows. Nothing is written to the photo library unless the user exports an image.
 */
@MainActor
@Observable
final class GalleryRepository {
    private nonisolated static let collection = "gallery"
    private nonisolated static let image = ".img"
    private nonisolated static let thumb = ".thumb"

    /// Newest first.
    private(set) var items: [GalleryItem] = []
    @ObservationIgnored private let store: EncryptedStore
    @ObservationIgnored private let keys: KeyManager

    init(store: EncryptedStore, keys: KeyManager) {
        self.store = store
        self.keys = keys
    }

    func load() {
        items = ((try? store.all(Self.collection, as: GalleryItem.self)) ?? []).sorted { $0.createdAt > $1.createdAt }
    }

    func get(_ id: String) -> GalleryItem? { items.first { $0.id == id } }

    func newId() -> String { UUID().uuidString.lowercased() }

    /// Stores [image] (full size) and [thumbnail] encrypted, then the row.
    func save(_ item: GalleryItem, image: Data, thumbnail: Data) async throws {
        let key = try keys.mediaKey()
        let id = item.id
        try await Task.detached {
            try Self.write(key, image, id: id, suffix: Self.image)
            try Self.write(key, thumbnail, id: id, suffix: Self.thumb)
        }.value
        try store.put(Self.collection, id: item.id, item)
        items = ([item] + items.filter { $0.id != item.id }).sorted { $0.createdAt > $1.createdAt }
    }

    func image(_ id: String) async -> Data? { await read(id, Self.image) }

    func thumbnail(_ id: String) async -> Data? { await read(id, Self.thumb) }

    func setFavorite(_ id: String, _ favorite: Bool) {
        guard var item = get(id) else { return }
        item.favorite = favorite
        try? store.put(Self.collection, id: id, item)
        items = items.map { $0.id == id ? item : $0 }
    }

    func delete(_ id: String) {
        try? store.delete(Self.collection, id: id)
        items.removeAll { $0.id == id }
        try? FileManager.default.removeItem(at: Self.file(id, Self.image))
        try? FileManager.default.removeItem(at: Self.file(id, Self.thumb))
    }

    private func read(_ id: String, _ suffix: String) async -> Data? {
        guard let key = try? keys.mediaKey() else { return nil }
        return await Task.detached {
            guard let sealed = try? Data(contentsOf: Self.file(id, suffix)) else { return nil }
            return try? AesGcmEnvelope.open(key, sealed, aad: Data((id + suffix).utf8))
        }.value
    }

    private nonisolated static func file(_ id: String, _ suffix: String) -> URL {
        precondition(id.range(of: "^[A-Za-z0-9-]{1,64}$", options: .regularExpression) != nil, "Invalid gallery id")
        return AppPaths.gallery.appendingPathComponent(id + suffix)
    }

    private nonisolated static func write(_ key: SymmetricKey, _ bytes: Data, id: String, suffix: String) throws {
        try AppPaths.write(try AesGcmEnvelope.seal(key, bytes, aad: Data((id + suffix).utf8)), to: file(id, suffix))
    }
}

/**
 Images attached to chat messages, AES-256-GCM sealed under `attachments/` with the media key
 (the id is associated data). An engine that needs a plain file gets a short-lived copy via
 [openPlain], which the caller deletes when done.
 */
final class AttachmentRepository: Sendable {
    private let keys: KeyManager

    init(keys: KeyManager) { self.keys = keys }

    /// Seals [bytes] (an encoded image) and returns its id.
    func put(_ bytes: Data) throws -> String {
        let id = UUID().uuidString.lowercased()
        try AppPaths.write(try AesGcmEnvelope.seal(keys.mediaKey(), bytes, aad: aad(id)), to: file(id))
        return id
    }

    /// Stores [bytes] under an existing [id] (import of an export bundle).
    func restore(_ id: String, _ bytes: Data) throws {
        try AppPaths.write(try AesGcmEnvelope.seal(keys.mediaKey(), bytes, aad: aad(id)), to: file(id))
    }

    func bytes(_ id: String) -> Data? {
        guard let sealed = try? Data(contentsOf: file(id)), let key = try? keys.mediaKey() else { return nil }
        return try? AesGcmEnvelope.open(key, sealed, aad: aad(id))
    }

    /// Decrypts [id] into a private temporary file for an engine to read; delete it afterwards.
    func openPlain(_ id: String) -> URL? {
        guard let data = bytes(id) else { return nil }
        let url = AppPaths.plainCache.appendingPathComponent(UUID().uuidString + ".img")
        return (try? AppPaths.write(data, to: url)) != nil ? url : nil
    }

    func delete(_ id: String) { try? FileManager.default.removeItem(at: file(id)) }

    private func file(_ id: String) -> URL {
        precondition(id.range(of: "^[A-Za-z0-9-]{1,64}$", options: .regularExpression) != nil, "Invalid attachment id")
        return AppPaths.attachments.appendingPathComponent(id + ".img")
    }

    private func aad(_ id: String) -> Data { Data("attachment:\(id)".utf8) }
}

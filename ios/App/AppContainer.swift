import Foundation
import Observation

/**
 The app's object graph (Android uses Hilt). One instance lives for the process; screens read it
 from the environment. Construction never touches protected data: [unlockData] loads the
 encrypted repositories once the device (and the app lock) allow it.
 */
@MainActor
@Observable
final class AppContainer {
    // Platform
    let keys = KeyManager()
    let secrets = SecretStore()
    let policy = NetworkPolicy()
    let auditLog = AuditLog()
    let preferences: Preferences
    let lock = AppLockManager()
    let settings: SettingsStore
    let clients: HttpClientFactory

    // Data
    let store: EncryptedStore
    let conversations: ConversationRepository
    let providers: ProviderRepository
    let gallery: GalleryRepository
    let attachments: AttachmentRepository
    let library: ModelLibrary
    let compute: ComputeProfile
    let accounts: ModelAccounts
    let downloader: ModelDownloader
    let backends: DiffusionBackendRepository

    // Engines, providers, agent
    let llm = LocalLlm()
    let speech = LocalSpeech()
    let providerFactory: ProviderFactory
    let engines: DiffusionEngineFactory
    let generator: ImageGenerator
    let agent: any AgentRuntime
    let panicWipe: PanicWipe
    let sessions: SessionStore
    #if canImport(AutobotShared)
    let snapshots = HostSnapshots()
    #endif

    private(set) var dataLoaded = false

    init() {
        AppPaths.prepare()
        preferences = Preferences()
        settings = SettingsStore(preferences: preferences, policy: policy, lock: lock)
        clients = HttpClientFactory(policy: policy, auditLog: auditLog)
        store = EncryptedStore(keys: keys)
        conversations = ConversationRepository(store: store)
        providers = ProviderRepository(store: store, secrets: secrets)
        gallery = GalleryRepository(store: store, keys: keys)
        attachments = AttachmentRepository(keys: keys)
        library = ModelLibrary(store: store)
        compute = ComputeProfile(store: store, library: library)
        accounts = ModelAccounts(preferences: preferences, secrets: secrets)
        downloader = ModelDownloader(library: library, clients: clients, accounts: accounts)
        backends = DiffusionBackendRepository(preferences: preferences)
        providerFactory = ProviderFactory(clients: clients, providers: providers, library: library, compute: compute, llm: llm)
        engines = DiffusionEngineFactory(clients: clients, llm: llm)
        generator = ImageGenerator(engines: engines, gallery: gallery, backends: backends, library: library)
        sessions = SessionStore(store: store)
        #if canImport(AutobotShared)
        let shared = SharedAgentRuntime(
            conversations: conversations, providers: providers, store: sessions, clients: clients, llm: llm,
            attachments: attachments, secrets: secrets, snapshots: snapshots,
        )
        shared.registerTool(GenerateImageTool(generator: generator, backends: backends))
        agent = shared
        #else
        agent = InterimAgentRuntime(store: sessions, conversations: conversations, providers: providers, factory: providerFactory, attachments: attachments)
        #endif

        let agent = agent, conversations = conversations, downloader = downloader, generator = generator, clients = clients, store = store, llm = llm
        panicWipe = PanicWipe(keys: keys, secrets: secrets, participants: [
            { agent.shutdown() },
            { downloader.cancelAll() },
            { generator.cancel() },
            { conversations.clearIncognito() },
            { await llm.unload() },
            { clients.evictAll(cancelInFlight: true) },
            { store.close() },
        ])

        providers.onChange = { [weak self] list in self?.providersChanged(list) }
        library.onChange = { [weak self] in self?.modelsChanged() }
    }

    private func providersChanged(_ list: [Provider]) {
        #if canImport(AutobotShared)
        snapshots.setProviders(list)
        #endif
    }

    /// Model registry changed: refresh the built-in providers and the bridge's model snapshot.
    func modelsChanged() {
        guard dataLoaded else { return }
        syncLocalProvider()
        #if canImport(AutobotShared)
        var map: [String: HostSnapshots.LocalModel] = [:]
        for model in library.models where model.isReady && model.engine == .llama {
            let paths = library.paths(model)
            guard let path = paths[.model] else { continue }
            let context = min(model.manifest.recommended?.contextLength ?? LocalChatProvider.defaultContext, LocalChatProvider.maxContext)
            map[model.id] = .init(path: path.path, mmproj: paths[.mmproj]?.path, contextLength: context, backend: compute.resolve(model))
        }
        snapshots.setModels(map)
        #endif
    }

    /// Loads the encrypted repositories (after the device and the app lock are unlocked).
    func unlockData() {
        guard !dataLoaded else { return }
        conversations.load()
        providers.load()
        gallery.load()
        library.load()
        compute.load()
        downloader.recoverAfterRestart()
        #if DEBUG
        DemoSeed.seedIfEmpty(self)
        #endif
        dataLoaded = true
        modelsChanged()
    }

    /**
     Keeps the built-in "This phone" provider and image backend in step with installed models:
     they appear when the first on-device model finishes downloading and disappear when the last
     one is deleted (Android's `LocalProviderSync` / `OnDeviceBackendSync`).
     */
    func syncLocalProvider() {
        let ready = library.models.filter { $0.isReady && $0.engine == .llama }.sorted { $0.addedAt < $1.addedAt }.map(\.id)
        let existing = providers.get(ProviderPresets.localProviderId)
        if ready.isEmpty {
            if existing != nil { providers.delete(ProviderPresets.localProviderId) }
        } else {
            let model = existing.flatMap { ready.contains($0.defaultModel) ? $0.defaultModel : nil } ?? ready[0]
            if existing?.defaultModel != model || existing?.kind != .local {
                _ = try? providers.save(Provider(id: ProviderPresets.localProviderId, kind: .local, displayName: "This phone", baseUrl: ProviderPresets.localBaseUrl, defaultModel: model))
            }
        }
        backends.syncOnDevice(available: library.models.contains { $0.isReady && $0.engine == .coreML && $0.kind == .image })
    }
}

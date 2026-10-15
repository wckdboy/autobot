import Foundation
import Observation
import UIKit

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
    let mcpServers: McpServerRepository
    let gallery: GalleryRepository
    let attachments: AttachmentRepository
    let library: ModelLibrary
    let compute: ComputeProfile
    let accounts: ModelAccounts
    let downloader: ModelDownloader
    let backends: DiffusionBackendRepository
    let memory: MemoryRepository
    let vault: VaultRepository

    // Engines, providers, agent
    let llm = LocalLlm()
    let speech = LocalSpeech()
    let providerFactory: ProviderFactory
    let engines: DiffusionEngineFactory
    let generator: ImageGenerator
    let agent: any AgentRuntime
    let panicWipe: PanicWipe
    let sessions: SessionStore
    /// Chat deletion (with undo) and search.
    let chats: ChatLibrary
    #if canImport(AutobotShared)
    let snapshots = HostSnapshots()
    @ObservationIgnored private let searchSource: WebSearchSourceHost
    @ObservationIgnored private var memoryOn = false
    @ObservationIgnored private var vaultOn = false
    @ObservationIgnored private var searchOn = false
    #endif

    private(set) var dataLoaded = false
    @ObservationIgnored private var activeObserver: (any NSObjectProtocol)?

    init() {
        AppPaths.prepare()
        preferences = Preferences()
        settings = SettingsStore(preferences: preferences, policy: policy, lock: lock)
        clients = HttpClientFactory(policy: policy, auditLog: auditLog)
        store = EncryptedStore(keys: keys)
        conversations = ConversationRepository(store: store)
        providers = ProviderRepository(store: store, secrets: secrets)
        mcpServers = McpServerRepository(store: store, secrets: secrets)
        gallery = GalleryRepository(store: store, keys: keys)
        attachments = AttachmentRepository(keys: keys)
        library = ModelLibrary(store: store)
        compute = ComputeProfile(store: store, library: library)
        // Read-aloud and voice mode prefer the neural voice once it is downloaded.
        Speakers.register(KokoroSpeaker(library: library))
        accounts = ModelAccounts(preferences: preferences, secrets: secrets)
        downloader = ModelDownloader(library: library, clients: clients, accounts: accounts)
        backends = DiffusionBackendRepository(preferences: preferences)
        memory = MemoryRepository(store: store)
        vault = VaultRepository(store: store, llm: llm)
        providerFactory = ProviderFactory(clients: clients, providers: providers, library: library, compute: compute, llm: llm)
        engines = DiffusionEngineFactory(clients: clients, llm: llm)
        generator = ImageGenerator(engines: engines, gallery: gallery, backends: backends, library: library)
        sessions = SessionStore(store: store)
        #if canImport(AutobotShared)
        searchSource = WebSearchSourceHost(secrets: secrets)
        let shared = SharedAgentRuntime(
            conversations: conversations, providers: providers, store: sessions, clients: clients, llm: llm,
            attachments: attachments, secrets: secrets, snapshots: snapshots,
        )
        shared.registerTool(GenerateImageTool(generator: generator, backends: backends))
        agent = shared
        #else
        agent = InterimAgentRuntime(store: sessions, conversations: conversations, providers: providers, factory: providerFactory, attachments: attachments)
        #endif

        chats = ChatLibrary(conversations: conversations, agent: agent, sessions: sessions, attachments: attachments)

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
        mcpServers.onChange = { [weak self] list in self?.mcpServersChanged(list) }
        settings.onChange = { [weak self] s in
            self?.agentFeaturesChanged(s)
            self?.downloader.networkChanged(offline: s.mode == .offline)
        }
        // A background relaunch hands its completion handler back once the downloader settled.
        BackgroundEvents.shared.settled = { [weak downloader] in
            guard let downloader else { return true }
            return !downloader.preparing && (!downloader.busy || downloader.hasActiveTransfer)
        }
        agentFeaturesChanged(settings.settings)
        library.onChange = { [weak self] in self?.modelsChanged() }
        // Apple Intelligence can be switched on (or finish preparing) while the app is in the background.
        activeObserver = NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.becameActive() }
        }
    }

    /// Back in the foreground: re-check what "This phone" can run.
    private func becameActive() {
        guard dataLoaded else { return }
        syncLocalProvider()
    }

    /// Opt-in agent features follow settings: memory and the vault while on, web search while a provider is set.
    private func agentFeaturesChanged(_ s: AppSettings) {
        #if canImport(AutobotShared)
        guard let shared = agent as? SharedAgentRuntime else { return }
        searchSource.update(s)
        if s.memoryEnabled != memoryOn {
            memoryOn = s.memoryEnabled
            shared.setMemory(memoryOn ? memory.host : nil)
        }
        if s.vaultEnabled != vaultOn {
            vaultOn = s.vaultEnabled
            shared.setVault(vaultOn ? vault.host : nil)
        }
        if s.webSearchOn != searchOn {
            searchOn = s.webSearchOn
            shared.setWebSearch(searchOn ? searchSource : nil)
        }
        shared.setQuotaFallback(s.quotaFallback, onDevice: providers.get(ProviderPresets.localProviderId))
        #endif
    }

    private func providersChanged(_ list: [Provider]) {
        #if canImport(AutobotShared)
        snapshots.setProviders(list)
        snapshots.setModelLists(providers.modelLists.mapValues(\.json))
        (agent as? SharedAgentRuntime)?.setQuotaFallback(settings.settings.quotaFallback, onDevice: list.first { $0.id == ProviderPresets.localProviderId })
        #endif
        // A chat whose provider was deleted (or that had none yet) moves to the first provider.
        agent.providersChanged(list)
    }

    private func mcpServersChanged(_ list: [McpServer]) {
        #if canImport(AutobotShared)
        snapshots.setMcpServers(list)
        (agent as? SharedAgentRuntime)?.setMcpServers(list)
        #endif
    }

    /// Model registry changed: refresh the built-in providers and the bridge's model snapshot.
    func modelsChanged() {
        guard dataLoaded else { return }
        vault.embedder.update(library)
        syncLocalProvider()
        #if canImport(AutobotShared)
        var map: [String: HostSnapshots.LocalModel] = [:]
        let device = DeviceProfile.current()
        for model in library.models where model.isLocalChat {
            let paths = library.paths(model)
            guard let path = paths[.model] else { continue }
            let context = min(model.manifest.recommended?.contextLength ?? LocalChatProvider.defaultContext, LocalChatProvider.maxContext)
            map[model.id] = .init(
                path: path.path, mmproj: paths[.mmproj]?.path, contextLength: context, backend: compute.resolve(model),
                tooBig: device.fit(model.kind, bytes: model.totalBytes, engine: model.engine) == .tooBig,
            )
        }
        snapshots.setModels(map)
        #endif
    }

    /**
     iOS relaunched (or woke) the app in the background for a model download's session events.
     Only the model registry is loaded (no UI, conversations or other repositories), and only while
     the device is unlocked: the registry's key is not readable while it is locked. Then a finished
     body stays on disk and the queue continues on the next unlock or launch.
     */
    func resumeDownloadsInBackground(_ identifier: String) {
        downloader.attachBackgroundSession(identifier)
        guard !dataLoaded, UIApplication.shared.isProtectedDataAvailable else {
            BackgroundEvents.shared.check()
            return
        }
        library.load()
        downloader.recoverAfterRestart()
    }

    /// Loads the encrypted repositories (after the device and the app lock are unlocked).
    func unlockData() {
        guard !dataLoaded else { return }
        conversations.load()
        providers.load()
        mcpServers.load()
        gallery.load()
        memory.load()
        vault.load()
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
     they appear when the first on-device model finishes downloading (or when Apple Intelligence
     can answer) and disappear when nothing is left (Android's `LocalProviderSync` /
     `OnDeviceBackendSync`).
     */
    func syncLocalProvider() {
        let installed = library.models.filter(\.isLocalChat).sorted { $0.addedAt < $1.addedAt }.map(\.id)
        let ready = installed + (AppleIntelligence.unavailability == nil ? [AppleIntelligence.id] : [])
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

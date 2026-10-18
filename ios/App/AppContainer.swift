import AutobotShared
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
    /// The app PIN, the duress PIN and the attempt counter (read before anything else opens).
    let pinLock: PinLock
    let lock: AppLockManager
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
    /// The spend and quota board (usage buckets, prices, subscription windows).
    let usage: UsageRepository
    let vault: VaultRepository
    /// The Creative Studio (projects, assets, queue, engines, ComfyUI, workflows).
    let studio: StudioHub

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
    /// Scheduled on-device recipes (ROADMAP Phase 2.5 item 7).
    let recipes: RecipeCenter
    @ObservationIgnored private var recipeHost: AppRecipeHost?
    #if canImport(AutobotShared)
    let snapshots = HostSnapshots()
    @ObservationIgnored private let searchSource: WebSearchSourceHost
    @ObservationIgnored private var memoryOn = false
    @ObservationIgnored private var vaultOn = false
    @ObservationIgnored private var searchOn = false
    /// The speculative decoding setting the bridge's model snapshot was built with.
    @ObservationIgnored private var speculativeShown = "auto"
    #endif

    private(set) var dataLoaded = false
    @ObservationIgnored private var unlocking = false
    @ObservationIgnored private var activeObserver: (any NSObjectProtocol)?
    @ObservationIgnored private var observers: [any NSObjectProtocol] = []
    @ObservationIgnored private let engineRelease = EngineReleaseScheduler()

    init() {
        LaunchTrace.mark("container.start")
        pinLock = PinLock()
        // First of all: a wipe that a kill interrupted (keys already gone) is finished before the
        // store, the preferences or anything else opens.
        WipeSteps.live(lock: pinLock).resumeIfNeeded()
        lock = AppLockManager(pin: pinLock)
        AppPaths.prepare()
        preferences = Preferences(background: true)
        settings = SettingsStore(preferences: preferences, policy: policy, lock: lock)
        clients = HttpClientFactory(policy: policy, auditLog: auditLog)
        LaunchTrace.mark("container.settings")
        store = EncryptedStore(keys: keys)
        conversations = ConversationRepository(store: store)
        providers = ProviderRepository(store: store, secrets: secrets)
        mcpServers = McpServerRepository(store: store, secrets: secrets)
        gallery = GalleryRepository(store: store, keys: keys)
        conversations.unsavedImages = gallery.unsaved
        attachments = AttachmentRepository(keys: keys)
        library = ModelLibrary(store: store)
        compute = ComputeProfile(store: store, library: library)
        // Read-aloud and voice mode prefer the neural voice once it is downloaded.
        Speakers.register(KokoroSpeaker(library: library))
        accounts = ModelAccounts(preferences: preferences, secrets: secrets)
        downloader = ModelDownloader(library: library, clients: clients, accounts: accounts)
        backends = DiffusionBackendRepository(preferences: preferences)
        memory = MemoryRepository(store: store)
        usage = UsageRepository(store: store)
        vault = VaultRepository(store: store, llm: llm)
        providerFactory = ProviderFactory(clients: clients, providers: providers, library: library, compute: compute, llm: llm)
        engines = DiffusionEngineFactory(clients: clients, llm: llm) { [compute] in compute.resolve($0) }
        generator = ImageGenerator(engines: engines, gallery: gallery, backends: backends, library: library)
        sessions = SessionStore(store: store)
        LaunchTrace.mark("container.repositories")
        studio = StudioHub(store: store, keys: keys, clients: clients, preferences: preferences, secrets: secrets)
        LaunchTrace.mark("container.studio")
        #if canImport(AutobotShared)
        searchSource = WebSearchSourceHost(secrets: secrets)
        let shared = SharedAgentRuntime(
            conversations: conversations, providers: providers, store: sessions, clients: clients, llm: llm,
            attachments: attachments, secrets: secrets, snapshots: snapshots,
        )
        shared.registerTool(GenerateImageTool(generator: generator, backends: backends))
        // Every model call is counted into the encrypted usage store (read on first use, after unlock).
        shared.setUsage(usage.host)
        usage.bridge = shared
        // `run_workflow` while there is a saved Studio workflow to run.
        let runner = studio.bridge.runner
        studio.onWorkflowsChanged = { [weak shared] any in shared?.setStudio(any ? runner : nil) }
        agent = shared
        LaunchTrace.mark("container.bridge")
        #else
        agent = InterimAgentRuntime(store: sessions, conversations: conversations, providers: providers, factory: providerFactory, attachments: attachments)
        #endif

        chats = ChatLibrary(conversations: conversations, agent: agent, sessions: sessions, attachments: attachments)
        recipes = RecipeCenter(repository: RecipeRepository(store: store), notifier: RecipeNotifier())
        let recipes = recipes

        let agent = agent, conversations = conversations, downloader = downloader, generator = generator, clients = clients, store = store, llm = llm
        let preferences = preferences
        let studio = studio
        let unsaved = gallery.unsaved
        let library = library
        panicWipe = PanicWipe(steps: WipeSteps.live(lock: pinLock), keptModels: { library.exportReady() }, participants: [
            { preferences.close() },
            // Recipes: nothing scheduled or shown survives a wipe (their documents go with the store).
            { recipes.stop(); RecipeBackground.submit(nil); RecipeNotifier().removeAll() },
            { agent.shutdown() },
            { studio.close() },
            { downloader.cancelAll() },
            { generator.cancel() },
            { conversations.clearIncognito() },
            { unsaved.clear() },
            { AppContainer.clearDecryptedCaches() },
            { await llm.unload() },
            { clients.evictAll(cancelInFlight: true) },
            { store.close() },
        ])

        // After the duress PIN: an empty app that opens like a used one, past the welcome.
        if pinLock.record.quietStart {
            pinLock.clearQuietStart()
            settings.setOnboarded()
            lock.beginQuietStart()
        }
        lock.onPanic = { [weak self] landing, carried in await self?.panic(landing, carriedPin: carried) }
        let host = AppRecipeHost(app: self)
        recipeHost = host
        recipes.host = host

        providers.onChange = { [weak self] list in self?.providersChanged(list) }
        mcpServers.onChange = { [weak self] list in self?.mcpServersChanged(list) }
        settings.onChange = { [weak self] s in
            #if canImport(AutobotShared)
            // The network policy may have changed: model adapters are rebuilt on their next step.
            (self?.agent as? SharedAgentRuntime)?.invalidateProviders()
            #endif
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
        let center = NotificationCenter.default
        // The engines' models are the bulk of the app's memory: free them before the system
        // terminates the app (at once on a memory warning; in the background, where it is first
        // in line, after a grace so a quick app switch does not reload the chat model).
        let release = engineRelease
        observers.append(center.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { release.memoryWarning() }
        })
        observers.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                release.enteredBackground()
                // No MCP list-change stream stays open or reconnects in the background.
                #if canImport(AutobotShared)
                (self?.agent as? SharedAgentRuntime)?.setForeground(false)
                #endif
            }
            preferences.flush()
        })
        observers.append(center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                release.becameActive()
                #if canImport(AutobotShared)
                (self?.agent as? SharedAgentRuntime)?.setForeground(true)
                #endif
            }
        })
        // Preferences unreadable at launch (before the first unlock) are read now.
        observers.append(center.addObserver(forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: nil) { _ in
            preferences.reloadIfNeeded()
        })
        LaunchTrace.mark("container.done")
    }

    /**
     A wipe from the lock screen: the failed-unlock limit lands on the welcome screen, the duress
     PIN on a quiet, empty app with [carriedPin] as its only PIN. The renamed data folder is
     deleted in the background; then the scene starts over on a new container.
     */
    private func panic(_ landing: WipeLanding, carriedPin: PinVerifier?) async {
        let record = pinLock.record
        _ = await panicWipe.wipe(landing: landing, keepModels: !record.wipeModels, carriedPin: carriedPin, biometrics: record.biometrics, asyncDelete: true)
        ContainerHolder.shared.restart()
    }

    /// Drops decrypted content cached for rendering (gallery thumbnails, parsed chat text): panic wipe.
    static func clearDecryptedCaches() {
        GalleryThumbs.removeAll()
        StudioThumbs.removeAll()
        MarkdownCache.shared.removeAll()
    }

    /// Back in the foreground: re-check what "This phone" can run.
    private func becameActive() {
        guard dataLoaded else { return }
        syncLocalProvider()
        // Recipes iOS did not get to (or that waited for an unlock) run now.
        recipes.runDue()
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
        shared.setRedactionDefault(s.redactCloud)
        shared.setCascadeDefault(s.cascade)
        // The draft models in the bridge's snapshot follow the speculative decoding setting.
        if s.speculativeMode != speculativeShown { modelsChanged() }
        #endif
    }

    private func providersChanged(_ list: [Provider]) {
        #if canImport(AutobotShared)
        snapshots.setProviders(list)
        snapshots.setModelLists(providers.modelLists.mapValues(\.json))
        (agent as? SharedAgentRuntime)?.setUsageNames(list)
        // Providers or their model lists changed: the bridge's cached model adapters go.
        (agent as? SharedAgentRuntime)?.invalidateProviders()
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

    /// A chat on the on-device model [modelId] opened: load it now so its first message does not
    /// wait for the load (skipped for models too big for this device).
    func prepareLocalChat(modelId: String) async {
        #if canImport(AutobotShared)
        guard let model = snapshots.model(modelId), !model.tooBig else { return }
        await llm.prepare(modelPath: model.path, contextLength: model.contextLength, backend: model.backend)
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
        let speculative = settings.settings.speculativeMode
        speculativeShown = speculative
        for model in library.models where model.isLocalChat {
            let paths = library.paths(model)
            guard let path = paths[.model] else { continue }
            let context = min(model.manifest.recommended?.contextLength ?? LocalChatProvider.defaultContext, LocalChatProvider.maxContext)
            let backend = compute.resolve(model)
            map[model.id] = .init(
                path: path.path, mmproj: paths[.mmproj]?.path, contextLength: context, backend: backend,
                tooBig: device.fit(model.kind, bytes: model.totalBytes, engine: model.engine) == .tooBig,
                draft: SpeculativeDraftPicker.pick(for: model, mode: speculative, library: library, device: device, context: context, backend: backend),
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

    /**
     Loads the encrypted repositories (after the device and the app lock are unlocked). The rows
     are read and decrypted off the main thread (SQLite, AES-GCM, the Keychain and the Secure
     Enclave unwrap of the data key), then published here.
     */
    func unlockData() async {
        guard !dataLoaded, !unlocking else { return }
        unlocking = true
        defer { unlocking = false }
        let store = store, memoryHost = memory.host
        // What the first screens show first (the chat list, routes): decrypted and published
        // before the rest, which follows in a second pass.
        let first = await Task.detached(priority: .userInitiated) {
            (conversations: ConversationRepository.read(store), providers: ProviderRepository.read(store))
        }.value
        guard !dataLoaded else { return }
        conversations.load(first.conversations)
        providers.load(first.providers)
        LaunchTrace.mark("data.first")
        let rows = await Task.detached(priority: .userInitiated) {
            (
                mcpServers: McpServerRepository.read(store),
                gallery: GalleryRepository.read(store),
                memory: memoryHost.records(),
                library: ModelLibrary.read(store),
                compute: ComputeProfile.read(store),
            )
        }.value
        guard !dataLoaded else { return }
        mcpServers.load(rows.mcpServers)
        gallery.load(rows.gallery)
        memory.load(rows.memory)
        // The vault reads its documents on the bridge's own thread and publishes on main.
        vault.load()
        library.load(rows.library)
        compute.load(rows.compute)
        downloader.recoverAfterRestart()
        #if DEBUG
        DemoSeed.seedIfEmpty(self)
        #endif
        dataLoaded = true
        modelsChanged()
        recipes.load()
        if UIApplication.shared.applicationState == .active { recipes.runDue() }
        LaunchTrace.mark("data.loaded")
        await studio.load()
        LaunchTrace.mark("studio.loaded")
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
        studio.syncEngines(app: self)
    }
}

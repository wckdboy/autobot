import AutobotShared
import Observation
import SwiftUI
import UIKit

enum ModelsTab: String, CaseIterable, Hashable {
    case recommended, huggingFace, civitai

    var label: String {
        switch self {
        case .recommended: "For this phone"
        case .huggingFace: "Hugging Face"
        case .civitai: "Civitai"
        }
    }
}

/// What the Hub search looks for.
enum HfScope: String, CaseIterable, Hashable {
    case chat, code, image

    var label: String { rawValue }

    var kind: ModelKind {
        switch self {
        case .chat: .chat
        case .code: .code
        case .image: .image
        }
    }
}

enum CivitaiScope: String, CaseIterable, Hashable {
    case checkpoint, lora

    var label: String { self == .checkpoint ? "checkpoints" : "lora" }
    var types: [String] { self == .checkpoint ? ["Checkpoint"] : ["LORA", "LoCon", "DoRA"] }
}

/// Why a hub search shows no rows, and what the user can do about it.
struct SearchNotice: Equatable {
    enum Action: Equatable { case networkSettings, account, retry }

    let title: String
    let detail: String
    let action: Action?

    var actionLabel: String? {
        switch action {
        case .networkSettings: "network settings"
        case .account: "account"
        case .retry: "retry"
        case nil: nil
        }
    }
}

/// An open Hub repository (sheet item).
struct OpenRepo: Identifiable {
    let repo: HfRepo
    var id: String { repo.id }
}

/// Models screen state (port of `ModelsViewModel.kt`).
@MainActor
@Observable
final class ModelsModel {
    /// Base models PC backends run (Civitai filter, as Android).
    static let supportedBases = ["SD 1.5", "SD 1.5 LCM", "SD 1.5 Hyper", "SDXL 1.0", "SDXL Turbo", "SDXL Lightning", "Pony", "Illustrious", "NoobAI"]

    var tab: ModelsTab = .recommended
    var hfScope: HfScope = .chat
    var hfQuery = ""
    var hfLoading = false
    var hfResults: [HfModelSummary] = []
    /// The last search failed (nil while it runs or after a success).
    private(set) var hfError: HubError?
    private(set) var hfSearched = false
    var openRepo: OpenRepo?
    var openLoading = false
    var civitaiScope: CivitaiScope = .checkpoint
    var civitaiQuery = ""
    var civitaiLoading = false
    var civitaiResults: [CivitaiModel] = []
    var civitaiCursor: String?
    private(set) var civitaiError: HubError?
    private(set) var civitaiSearched = false
    var message: String?
    var device: DeviceProfile?
    /// The engines package's hardware facts, read once (not on every render of the profile).
    @ObservationIgnored private(set) var deviceInfo: (machine: String, name: String?, chip: String?, neuralEngine: Bool, gpu: String?, availableRam: Int64?)?

    @ObservationIgnored private var searchTask: Task<Void, Never>?
    @ObservationIgnored private var app: AppContainer?
    @ObservationIgnored private(set) var hub: HuggingFaceClient?
    @ObservationIgnored private(set) var civitai: CivitaiClient?
    @ObservationIgnored private(set) var previews: PreviewImages?

    func attach(_ app: AppContainer) {
        guard self.app == nil else { return }
        self.app = app
        hub = HuggingFaceClient(clients: app.clients, accounts: app.accounts)
        civitai = CivitaiClient(clients: app.clients, accounts: app.accounts)
        previews = PreviewImages(clients: app.clients)
        device = DeviceProfile.current()
        deviceInfo = LocalDeviceInfo.current()
    }

    func install(_ plan: ModelPlan) {
        app?.downloader.install(plan)
        message = "Queued \(plan.title)"
    }

    /// What tapping [m] opens, by the shared rules (nothing while it downloads, or for speech).
    static func launch(_ m: InstalledModel, device: DeviceProfile? = nil, filesPresent: Bool = true) -> ModelLaunch {
        ModelLaunchKt.launchOf(
            kind: m.kind.kotlin, engine: m.engine.kotlin, ready: m.isReady,
            fit: device?.capabilities.fit(kind: m.kind.kotlin, bytes: m.totalBytes, engine: m.engine.kotlin, contextTokens: 0), filesPresent: filesPresent, platform: .ios,
        )
    }

    /**
     Puts an installed model to work: a new session on "This phone" for chat and code models, the
     image studio set up for image models, the gallery for upscalers.
     */
    func use(_ m: InstalledModel, nav: Navigator) {
        guard let app else { return }
        let files = app.library.paths(m).values.allSatisfy { FileManager.default.fileExists(atPath: $0.path) }
        let launch = Self.launch(m, device: device, filesPresent: files)
        if let problem = launch.problem {
            message = problem
            return
        }
        switch launch.task {
        case .chat:
            guard app.providers.get(ProviderPresets.localProviderId) != nil else {
                message = "\"This phone\" isn't ready as a provider yet — try again in a moment"
                return
            }
            let conversation = app.conversations.create(incognito: app.settings.settings.incognitoByDefault, providerId: ProviderPresets.localProviderId, model: m.id)
            app.agent.setToolsEnabled(conversation.id, launch.tools)
            nav.push(.chat(conversationId: conversation.id))
        case .imagine: nav.push(.imagine(modelId: m.id))
        case .gallery: nav.push(.gallery())
        default: break
        }
    }

    // MARK: Hugging Face

    func setHfScope(_ scope: HfScope) {
        hfScope = scope
        searchHf()
    }

    func searchHf() {
        // Searches can be triggered before the screen's .task created the clients.
        guard let hub else { return }
        let scope = hfScope, query = hfQuery
        searchTask?.cancel()
        searchTask = Task {
            hfLoading = true
            hfError = nil
            defer { hfLoading = false }
            let outcome = await searching {
                if scope == .image {
                    try await hub.search(query.isEmpty ? "stable diffusion" : query, gguf: false, coreML: true)
                } else {
                    try await hub.search(query.isEmpty ? "instruct" : query, gguf: true)
                }
            }
            guard !Task.isCancelled, let outcome else { return }
            hfSearched = true
            switch outcome {
            case .success(let results): hfResults = results
            case .failure(let error):
                hfResults = []
                hfError = error
            }
        }
    }

    func open(_ id: String) {
        guard let hub else { return }
        Task {
            openLoading = true
            defer { openLoading = false }
            if let repo = await guarded({ try await hub.repo(id) }) { openRepo = OpenRepo(repo: repo) }
        }
    }

    func installFile(_ repo: HfRepo, _ path: String) {
        guard let file = repo.files.first(where: { $0.path == path }), let hub else { return }
        openRepo = nil
        install(hub.plan(repo, file, kind: hfScope.kind))
    }

    func installCoreML(_ repo: HfRepo, folder: String) {
        guard let hub else { return }
        openRepo = nil
        install(hub.coreMLPlan(repo, folder: folder))
    }

    // MARK: Civitai

    func setCivitaiScope(_ scope: CivitaiScope) {
        civitaiScope = scope
        searchCivitai(reset: true)
    }

    func searchCivitai(reset: Bool = true) {
        if !reset && civitaiCursor == nil { return }
        guard let civitai else { return }
        let scope = civitaiScope, query = civitaiQuery, cursor = reset ? nil : civitaiCursor
        searchTask?.cancel()
        searchTask = Task {
            civitaiLoading = true
            civitaiError = nil
            defer { civitaiLoading = false }
            let outcome = await searching { try await civitai.search(query, types: scope.types, baseModels: Self.supportedBases, cursor: cursor) }
            guard !Task.isCancelled, let outcome else { return }
            civitaiSearched = true
            switch outcome {
            case .success(let page):
                civitaiResults = reset ? page.items : civitaiResults + page.items
                civitaiCursor = page.nextCursor
            case .failure(let error):
                if reset { civitaiResults = [] }
                civitaiCursor = nil
                civitaiError = error
            }
        }
    }

    func installCivitai(_ model: CivitaiModel) {
        guard let version = model.versions.first, let plan = civitai?.plan(model, version) else {
            message = "No downloadable file in this version"
            return
        }
        install(plan)
    }

    /// A search's result, or its failure as a [HubError]; nil when it was cancelled.
    private func searching<T>(_ block: () async throws -> T) async -> Result<T, HubError>? {
        do {
            return .success(try await block())
        } catch is CancellationError {
            return nil
        } catch let e as HubError {
            return .failure(e)
        } catch let e as NetworkBlockedError {
            return .failure(HubError(message: e.message, code: .blocked))
        } catch {
            return Task.isCancelled ? nil : .failure(HubError(message: error.localizedDescription))
        }
    }

    /**
     What an empty result list says: Offline mode (the kill switch blocks the hub) with the way to
     the network setting, a refused token, a failure to retry, or no matches. Nil while a search
     runs, when there are rows, or before the first search.
     */
    static func searchNotice(hub: String, loading: Bool, hasResults: Bool, searched: Bool, error: HubError?, offline: Bool) -> SearchNotice? {
        if loading || hasResults { return nil }
        if offline || error?.code == .blocked {
            return SearchNotice(
                title: "Offline mode is on",
                detail: "Searching \(hub) needs the network, and the kill switch blocks it. Allow a network mode in Privacy Center, then search again.",
                action: .networkSettings,
            )
        }
        if let error {
            switch error.code {
            case .unauthorized, .gated:
                return SearchNotice(title: "\(hub) refused the request", detail: error.message, action: .account)
            case .rateLimited:
                return SearchNotice(title: "\(hub) asks to slow down", detail: "Too many searches in a short time. Wait a moment, then retry.", action: .retry)
            default:
                return SearchNotice(title: "Couldn't search \(hub)", detail: error.message, action: .retry)
            }
        }
        guard searched else { return nil }
        return SearchNotice(title: "No results", detail: "Nothing on \(hub) matches this search. Try another name or fewer words.", action: nil)
    }

    private func guarded<T>(_ block: () async throws -> T) async -> T? {
        do {
            return try await block()
        } catch is CancellationError {
            return nil
        } catch {
            if !Task.isCancelled { message = error.localizedDescription }
            return nil
        }
    }
}

struct ModelsScreen: View {
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @State private var model = ModelsModel()

    var body: some View {
        let _ = RenderProbe.hit("body.models")
        let installedIds = Set(app.library.models.map(\.id))
        @Bindable var model = model
        VStack(spacing: 0) {
            AutobotTopBar(title: "Models") {
                Button("Compute") { nav.push(.compute) }
                    .accessibilityHint("Benchmarks for this phone")
            }
            List {
                profile
                firstModel
                DownloadsPanel()
                if !app.library.models.isEmpty {
                    Section("Installed · \(app.library.models.count)") {
                        ForEach(app.library.models) { m in InstalledRow(model: m) { model.use(m, nav: nav) } }
                    }
                }
                Section {
                    Picker("Source", selection: $model.tab) {
                        ForEach(ModelsTab.allCases, id: \.self) { Text($0.label).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .labelsHidden()
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets())
                }
                switch model.tab {
                case .recommended: recommended(installedIds)
                case .huggingFace: huggingFace
                case .civitai: civitaiTab(installedIds)
                }
            }
            .listStyle(.insetGrouped)
            .modifier(HubSearch(model: model))
            .scrollDismissesKeyboard(.interactively)
            .animation(.default, value: app.library.models.map(\.id))
        }
        .overlay(alignment: .bottom) { Snackbar(message: $model.message) }
        .sheet(item: $model.openRepo) { open in
            RepoSheet(repo: open.repo, model: model)
                .presentationDetents([.large])
        }
        .task { model.attach(app) }
    }

    @ViewBuilder private var profile: some View {
        if let d = model.device, let info = model.deviceInfo {
            // One line: what decides which models fit.
            let chip = info.chip ?? d.chip ?? info.name ?? d.machine
            Section {
                Label("\(chip) · \(formatBytes(d.totalRamBytes)) RAM · \(formatBytes(d.freeStorageBytes)) free", systemImage: "iphone")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// "Start here": the first-run model for this phone, until it chats on one.
    @ViewBuilder private var firstModel: some View {
        if let d = model.device {
            let state = FirstModelState.compute(device: d.capabilities, models: app.library.models, progress: app.downloader.progress,
                                                hasChats: !app.conversations.conversations.isEmpty)
            FirstModelCard(
                state: state, ramBytes: d.totalRamBytes,
                offline: app.settings.settings.mode == .offline,
                onNetwork: { online in app.settings.setNetworkMode(online ? .direct : .offline) },

                onDownload: { if let plan = state.plan { model.install(plan) } },
                onPause: { if let id = state.plan?.id { app.downloader.pause(id) } },
                onResume: { if let id = state.plan?.id { app.downloader.resume(id) } },
                onStart: {
                    if let id = state.plan?.id, let m = app.library.models.first(where: { $0.id == id }) { model.use(m, nav: nav) }
                },
            )
        }
    }

    @ViewBuilder private func recommended(_ installed: Set<String>) -> some View {
        // Chat first: it is what a new phone needs; images and the rest follow.
        let sections: [(String, [ModelPlan])] = [
            ("Chat", Catalog.chat),
            ("Code", Catalog.code),
            ("Image · neural engine", CoreMLCatalog.all),
            ("Upscale · neural engine", CoreMLCatalog.upscalers),

            ("Speech · dictation", Catalog.speech),
            ("Voice · read aloud", CoreMLCatalog.voices),
            ("Audio · studio", CoreMLCatalog.audio),
            ("Embedding · vault", Catalog.embedding),
        ]
        ForEach(sections, id: \.0) { label, plans in
            Section(label) {
                ForEach(plans) { plan in
                    CatalogRow(plan: plan, fit: model.device?.fit(plan.kind, bytes: plan.totalBytes, engine: plan.engine), installed: installed.contains(plan.id)) {
                        model.install(plan)
                    }
                }
            }
        }
    }

    @ViewBuilder private var huggingFace: some View {
        @Bindable var model = model
        Section {
            AccountRow(text: app.accounts.state.huggingFaceUser ?? "anonymous") { nav.push(.accounts) }
            Picker("Search for", selection: Binding(get: { model.hfScope }, set: { model.setHfScope($0) })) {
                ForEach(HfScope.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            if model.hfLoading || model.openLoading { ProgressView().frame(maxWidth: .infinity) }
        }
        Section {
            if let notice = ModelsModel.searchNotice(hub: "Hugging Face", loading: model.hfLoading, hasResults: !model.hfResults.isEmpty,
                                                     searched: model.hfSearched, error: model.hfError, offline: app.settings.settings.mode == .offline) {
                SearchNoticeView(notice: notice) { act(notice.action, retry: model.searchHf) }
            }
            ForEach(model.hfResults) { m in
                Button { model.open(m.id) } label: {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(String(m.id.split(separator: "/").last ?? Substring(m.id))).foregroundStyle(Color.primary).lineLimit(1)
                            Text("\(m.id.split(separator: "/").first ?? "") · \(m.downloads) dl\(m.license.map { " · \($0)" } ?? "")")
                                .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        if m.gated { CapsuleTag("gated", color: .orange) }
                        Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(Color(uiColor: .tertiaryLabel))
                            .accessibilityHidden(true)
                    }
                    .contentShape(Rectangle())
                }
            }
        }
    }

    private func act(_ action: SearchNotice.Action?, retry: () -> Void) {
        switch action {
        case .networkSettings: nav.push(.privacyCenter)
        case .account: nav.push(.accounts)
        case .retry: retry()
        case nil: break
        }
    }

    @ViewBuilder private func civitaiTab(_ installed: Set<String>) -> some View {
        @Bindable var model = model
        let account = app.accounts.state
        Section {
            AccountRow(text: [account.civitaiUser ?? "no api key", account.showMature ? "mature on" : "sfw"].joined(separator: " · ")) { nav.push(.accounts) }
            Picker("Type", selection: Binding(get: { model.civitaiScope }, set: { model.setCivitaiScope($0) })) {
                ForEach(CivitaiScope.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            .pickerStyle(.segmented)
            if model.civitaiLoading { ProgressView().frame(maxWidth: .infinity) }
        }
        Section {
            if let notice = ModelsModel.searchNotice(hub: "Civitai", loading: model.civitaiLoading, hasResults: !model.civitaiResults.isEmpty,
                                                     searched: model.civitaiSearched, error: model.civitaiError, offline: app.settings.settings.mode == .offline) {
                SearchNoticeView(notice: notice) { act(notice.action, retry: { model.searchCivitai() }) }
            }
            ForEach(model.civitaiResults) { m in
                CivitaiRow(model: m, installed: installed, previews: model.previews) { model.installCivitai(m) }
            }
            if model.civitaiCursor != nil {
                Button("More") { model.searchCivitai(reset: false) }
                    .frame(maxWidth: .infinity)
            }
        }
    }
}

/// The hub search: the system search field (in the navigation bar's drawer) on the Hugging Face and
/// Civitai tabs, bound to that tab's query; Search runs it.
private struct HubSearch: ViewModifier {
    @Bindable var model: ModelsModel

    func body(content: Content) -> some View {
        switch model.tab {
        case .recommended:
            content
        case .huggingFace:
            content
                .searchable(text: $model.hfQuery, placement: .navigationBarDrawer(displayMode: .always),
                            prompt: model.hfScope == .image ? "search core ml repos (stable diffusion, sdxl…)" : "search gguf repos (qwen, gemma, phi…)")
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit(of: .search) { model.searchHf() }
        case .civitai:
            content
                .searchable(text: $model.civitaiQuery, placement: .navigationBarDrawer(displayMode: .always),
                            prompt: "search sd 1.5 / sdxl / pony / illustrious")
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit(of: .search) { model.searchCivitai() }
        }
    }
}

/// Who the hub sees (signed-in user or anonymous) and the way to the account screen.
private struct AccountRow: View {
    let text: String
    let onAccount: () -> Void

    var body: some View {
        HStack {
            Label(text, systemImage: "person.crop.circle").foregroundStyle(.secondary).lineLimit(1)
            Spacer(minLength: 8)
            Button("Account", action: onAccount).buttonStyle(.borderless)
        }
    }
}

/// Why a search list is empty, with what to do about it.
struct SearchNoticeView: View {
    let notice: SearchNotice
    let onAction: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label(notice.title, systemImage: symbol)
        } description: {
            Text(notice.detail)
        } actions: {
            if let label = notice.actionLabel {
                Button(label.prefix(1).uppercased() + label.dropFirst(), action: onAction)
                    .modifier(NoticeActionStyle(prominent: notice.action == .networkSettings))
            }
        }
    }

    private var symbol: String {
        switch notice.action {
        case .networkSettings: "wifi.slash"
        case .account: "person.crop.circle.badge.exclamationmark"
        case .retry: "exclamationmark.triangle"
        case nil: "magnifyingglass"
        }
    }
}

private struct NoticeActionStyle: ViewModifier {
    let prominent: Bool

    func body(content: Content) -> some View {
        if prominent { content.glassProminentButton() } else { content.glassButton() }
    }
}

/// Transient message at the bottom of the screen.
struct Snackbar: View {
    @Binding var message: String?

    var body: some View {
        if let text = message {
            Text(text)
                .font(.subheadline)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: 560, alignment: .leading)
                .background(.regularMaterial, in: Capsule())
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task(id: text) {
                    try? await Task.sleep(for: .seconds(3))
                    withAnimation { if message == text { message = nil } }
                }
                .onTapGesture { message = nil }
        }
    }
}

private struct InstalledRow: View {
    let model: InstalledModel
    let onUse: () -> Void
    @Environment(AppContainer.self) private var app
    @State private var confirm = false

    var body: some View {
        let subtitle = [
            model.kind.label,
            model.totalBytes > 0 ? formatBytes(model.totalBytes) : nil,
            model.engine.runsOnIOS ? nil : "not runnable here",
        ].compactMap { $0 }.joined(separator: " · ")
        // Tapping a ready model opens the task it runs (swipe or long-press removes it).
        let opens = ModelsModel.launch(model).task != ModelTask.none
        HStack(spacing: 12) {
            Button(action: onUse) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(model.title).foregroundStyle(Color.primary).lineLimit(1)
                    Text(subtitle).font(.caption).foregroundStyle(Color(uiColor: .secondaryLabel)).lineLimit(1)
                    if model.status == .paused || model.status == .failed {
                        ProgressView(value: model.fraction).tint(model.status == .failed ? .red : .secondary)
                    }
                    if let paused = model.pausedLabel { Text(paused).font(.caption).foregroundStyle(Color(uiColor: .secondaryLabel)) }
                    if let error = model.error { Text(error).font(.caption).foregroundStyle(.red).lineLimit(4) }
                }
                // A borderless button centres several lines and tints a hierarchical style:
                // leading text and concrete colours keep the row a row.
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .disabled(!opens)
            status.buttonStyle(.borderless)
        }
        .swipeActions(edge: .trailing) {
            Button("Delete", role: .destructive) { confirm = true }
        }
        .contextMenu {
            Button("Delete", systemImage: "trash", role: .destructive) { confirm = true }
        }
        .confirmationDialog("Delete \(model.title)?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                app.downloader.remove(model.id)
                app.compute.deleteFor(modelId: model.id)
            }
        } message: {
            Text("frees \(formatBytes(model.totalBytes))")
        }
    }

    @ViewBuilder private var status: some View {
        switch model.status {
        case .ready: CapsuleTag("✓ ready", color: .secondary)
        case .downloading: Button("\(Int(model.fraction * 100))%") { app.downloader.pause(model.id) }.monospacedDigit()
            .accessibilityHint("Pauses the download")
        case .queued: Button("Queued") { app.downloader.pause(model.id) }.foregroundStyle(.secondary)
        case .paused: Button("Resume") { app.downloader.resume(model.id) }
        case .failed: Button("Retry") { app.downloader.resume(model.id) }.tint(.red)
        }
    }
}

/// "Get": the App Store's small bordered capsule.
private struct GetButton: View {
    var label = "Get"
    var tint: Color? = nil
    let action: () -> Void

    var body: some View {
        Button(label, action: action)
            .buttonStyle(.bordered)
            .buttonBorderShape(.capsule)
            .controlSize(.small)
            .tint(tint)
    }
}

private struct CatalogRow: View {
    let plan: ModelPlan
    let fit: Fit?
    let installed: Bool
    let onGet: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(plan.title).lineLimit(1)
                Text(sizedSubtitle(bytes: plan.totalBytes, plan.subtitle)).font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let fit, fit != .good {
                CapsuleTag(fit.label, color: fitColor(fit))
            }
            if installed {
                Text("Added").font(.subheadline).foregroundStyle(.secondary)
            } else {
                GetButton(action: onGet)
            }
        }
    }
}

/// How well a model fits this phone, as a system colour.
func fitColor(_ fit: Fit) -> Color {
    switch fit {
    case .good: .secondary
    case .tight: .orange
    case .tooBig, .unsupported: .red
    }
}

private struct RepoSheet: View {
    let repo: HfRepo
    let model: ModelsModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let owner = String(repo.id.split(separator: "/").first ?? "")
        let files = repo.files.filter { f in
            let n = f.path.lowercased()
            return (n.hasSuffix(".gguf") || n.hasSuffix(".safetensors") || n.hasSuffix(".ckpt")) && !n.contains("mmproj")
        }
        let bundles = repo.coreMLBundles
        NavigationStack {
            List {
                Section {
                    Text([repo.license, repo.gated ? "gated — accept on huggingface.co" : nil, repo.commit.map { String($0.prefix(10)) }].compactMap { $0 }.joined(separator: " · "))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                if !bundles.isEmpty {
                    Section("Core ML · whole folder, one model") {
                        ForEach(bundles, id: \.self) { folder in
                            let size = repo.coreMLSize(folder)
                            let fit = model.device?.fit(.image, bytes: size, engine: .coreML)
                            fileRow(title: folder.isEmpty ? "compiled" : folder, subtitle: "\(formatBytes(size)) · \(folder.lowercased().contains("original") ? "gpu" : "neural engine")") {
                                if let fit, fit != .good { CapsuleTag(fit.label, color: fitColor(fit)) }
                                GetButton { model.installCoreML(repo, folder: folder) }
                            }
                        }
                    }
                }
                if files.isEmpty && bundles.isEmpty {
                    MarkUnavailable("no model files in this repo")
                }
                if !files.isEmpty {
                    Section {
                        ForEach(files, id: \.path) { f in
                            let kind = model.hfScope.kind
                            let format = formatOf(f.path, owner: owner)
                            let fit = model.device?.fit(kind, bytes: f.sizeBytes, engine: engineFor(kind, format))
                            fileRow(title: String(f.path.split(separator: "/").last ?? ""), subtitle: "\(formatBytes(f.sizeBytes))\(f.sha256 != nil ? " · sha-256 known" : "")") {
                                if let fit, fit != .good { CapsuleTag(fit.label, color: .orange) }
                                if format == .safetensors || format == .ckpt {
                                    PcOnlyGetTag { model.installFile(repo, f.path) }
                                } else {
                                    GetButton { model.installFile(repo, f.path) }
                                }
                            }
                        }
                    } footer: {
                        if files.contains(where: { formatOf($0.path, owner: owner) == .safetensors }) {
                            Text("safetensors run on a pc backend").foregroundStyle(.orange)
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(repo.id)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
    }

    private func fileRow<Trailing: View>(title: String, subtitle: String, @ViewBuilder trailing: () -> Trailing) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).lineLimit(2)
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
    }
}

/// "Get" for a file this phone can't run (checkpoints for a PC backend): a second tap confirms.
private struct PcOnlyGetTag: View {
    let action: () -> Void
    @State private var confirm = false

    var body: some View {
        if confirm {
            GetButton(label: "pc only · get anyway", tint: .orange) {
                confirm = false
                action()
            }
        } else {
            CapsuleTag("pc", color: .secondary)
            GetButton { confirm = true }
        }
    }
}

private struct CivitaiRow: View {
    let model: CivitaiModel
    let installed: Set<String>
    let previews: PreviewImages?
    let onGet: () -> Void
    @State private var preview: UIImage?
    @ScaledMetric(relativeTo: .body) private var thumb: CGFloat = 52

    var body: some View {
        let version = model.versions.first
        let file = version?.files.first(where: \.primary) ?? version?.files.first
        HStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 8).fill(.quaternary)
                if let preview {
                    Image(uiImage: preview).resizable().scaledToFill().blur(radius: version?.previewMature == true ? 12 : 0)
                }
            }
            .frame(width: thumb, height: thumb)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(model.name).lineLimit(1)
                Text([version?.baseModel, file.map { formatBytes($0.sizeBytes) }, model.creator, "\(model.downloads) dl"].compactMap { $0 }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if version?.earlyAccess == true {
                CapsuleTag("early access", color: .orange)
            } else if let version, installed.contains("civitai:\(version.id)") {
                Text("Added").font(.subheadline).foregroundStyle(.secondary)
            } else {
                PcOnlyGetTag(action: onGet)
            }
        }
        .task(id: version?.previewUrl) {
            guard let url = version?.previewUrl, let data = await previews?.load(url) else { return }
            preview = await decodeImage(data, maxSide: 160)
        }
    }
}

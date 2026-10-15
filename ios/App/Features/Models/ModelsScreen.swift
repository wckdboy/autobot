import AutobotShared
import Observation
import SwiftUI
import UIKit

enum ModelsTab: String, CaseIterable, Hashable {
    case recommended, huggingFace, civitai

    var label: String {
        switch self {
        case .recommended: "for this phone"
        case .huggingFace: "hugging face"
        case .civitai: "civitai"
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
    var openRepo: OpenRepo?
    var openLoading = false
    var civitaiScope: CivitaiScope = .checkpoint
    var civitaiQuery = ""
    var civitaiLoading = false
    var civitaiResults: [CivitaiModel] = []
    var civitaiCursor: String?
    var message: String?
    var device: DeviceProfile?

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
    }

    func install(_ plan: ModelPlan) {
        app?.downloader.install(plan)
        message = "Queued \(plan.title)"
    }

    /// What tapping [m] opens, by the shared rules (nothing while it downloads, or for speech).
    static func launch(_ m: InstalledModel, device: DeviceProfile? = nil, filesPresent: Bool = true) -> ModelLaunch {
        ModelLaunchKt.launchOf(
            kind: m.kind.kotlin, engine: m.engine.kotlin, ready: m.isReady,
            fit: device?.capabilities.fit(kind: m.kind.kotlin, bytes: m.totalBytes, engine: m.engine.kotlin), filesPresent: filesPresent, platform: .ios,
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
        let scope = hfScope, query = hfQuery
        searchTask?.cancel()
        searchTask = Task {
            hfLoading = true
            defer { hfLoading = false }
            let results = await guarded {
                if scope == .image {
                    try await hub!.search(query.isEmpty ? "stable diffusion" : query, gguf: false, coreML: true)
                } else {
                    try await hub!.search(query.isEmpty ? "instruct" : query, gguf: true)
                }
            }
            if !Task.isCancelled { hfResults = results ?? [] }
        }
    }

    func open(_ id: String) {
        Task {
            openLoading = true
            defer { openLoading = false }
            if let repo = await guarded({ try await hub!.repo(id) }) { openRepo = OpenRepo(repo: repo) }
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
        let scope = civitaiScope, query = civitaiQuery, cursor = reset ? nil : civitaiCursor
        searchTask?.cancel()
        searchTask = Task {
            civitaiLoading = true
            defer { civitaiLoading = false }
            let page = await guarded { try await civitai!.search(query, types: scope.types, baseModels: Self.supportedBases, cursor: cursor) }
            guard !Task.isCancelled else { return }
            civitaiResults = reset ? (page?.items ?? []) : civitaiResults + (page?.items ?? [])
            civitaiCursor = page?.nextCursor
        }
    }

    func installCivitai(_ model: CivitaiModel) {
        guard let version = model.versions.first, let plan = civitai?.plan(model, version) else {
            message = "No downloadable file in this version"
            return
        }
        install(plan)
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
    @Environment(\.scheme) private var c
    @State private var model = ModelsModel()

    var body: some View {
        let installedIds = Set(app.library.models.map(\.id))
        VStack(spacing: 0) {
            AutobotTopBar(title: "Models") {
                TextAction("Compute", color: c.onSurface) { nav.push(.compute) }
            }
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    profile
                    if let p = app.downloader.progress { progressPanel(p) }
                    if !app.library.models.isEmpty {
                        SectionLabel("installed · \(app.library.models.count)")
                        ForEach(app.library.models) { m in InstalledRow(model: m) { model.use(m, nav: nav) }.transition(.inkDissolve) }
                        Spacer().frame(height: 18)
                    }
                    Segmented(options: ModelsTab.allCases, selected: model.tab, label: \.label) { model.tab = $0 }
                    Spacer().frame(height: 12)
                    switch model.tab {
                    case .recommended: recommended(installedIds)
                    case .huggingFace: huggingFace
                    case .civitai: civitaiTab(installedIds)
                    }
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
                .padding(.vertical, 8)
                .animation(.inkDissolve, value: app.library.models.map(\.id))
            }
            .scrollDismissesKeyboard(.interactively)
        }
        .background(c.background)
        .overlay(alignment: .bottom) { Snackbar(message: $model.message) }
        .sheet(item: $model.openRepo) { open in
            RepoSheet(repo: open.repo, model: model)
                .presentationDetents([.large])
                .environment(\.scheme, c)
        }
        .task { model.attach(app) }
    }

    @ViewBuilder private var profile: some View {
        if let d = model.device {
            let info = LocalDeviceInfo.current()
            let name = [info.name ?? d.machine, info.chip ?? d.chip].compactMap { $0 }.joined(separator: " · ")
            SectionLabel("this phone · \(name)")
            Spacer().frame(height: 4)
            MicroLabel(
                "\(formatBytes(d.totalRamBytes)) ram · \(formatBytes(d.freeStorageBytes)) free · \(d.cores) cores" +
                    (info.neuralEngine ? " · neural engine" : "") + (d.gpu != nil ? " · metal" : ""),
                lines: 2,
            )
        }
        Spacer().frame(height: 14)
    }

    private func progressPanel(_ p: DownloadProgress) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    MicroLabel("downloading", color: c.primary)
                    Text(p.title).textStyle(.titleMedium).foregroundStyle(c.onSurface).lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                TextAction("Pause", color: c.primary) { app.downloader.pause(p.id) }
            }
            ProgressLine(fraction: p.fraction, running: true)
            MicroLabel("\(formatBytes(p.downloaded)) / \(formatBytes(p.total)) · \(formatBytes(p.bytesPerSecond))/s")
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainerHigh))
        .padding(.bottom, 14)
    }

    @ViewBuilder private func recommended(_ installed: Set<String>) -> some View {
        let sections: [(String, [ModelPlan])] = [
            ("image · neural engine", CoreMLCatalog.all),
            ("upscale · neural engine", CoreMLCatalog.upscalers),
            ("chat", Catalog.chat),
            ("code", Catalog.code),
            ("speech · dictation", Catalog.speech),
            ("voice · read aloud", CoreMLCatalog.voices),
        ]
        ForEach(sections, id: \.0) { label, plans in
            SectionLabel(label, color: c.onSurfaceVariant)
            ForEach(plans) { plan in
                CatalogRow(plan: plan, fit: model.device?.fit(plan.kind, bytes: plan.totalBytes, engine: plan.engine), installed: installed.contains(plan.id)) {
                    model.install(plan)
                }
            }
            Spacer().frame(height: 14)
        }
        MicroLabel("core ml images run on the neural engine, gguf models on the gpu (metal) or cpu · downloads are verified (sha-256) before use", lines: 3)
    }

    @ViewBuilder private var huggingFace: some View {
        @Bindable var model = model
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                MicroLabel(app.accounts.state.huggingFaceUser.map { "signed in · \($0)" } ?? "anonymous · gated models need a token")
                    .frame(maxWidth: .infinity, alignment: .leading)
                Tag("account") { nav.push(.accounts) }
            }
            Segmented(options: HfScope.allCases, selected: model.hfScope, label: \.label) { model.setHfScope($0) }
            ConsoleTextField(
                text: $model.hfQuery,
                placeholder: model.hfScope == .image ? "search core ml repos (stable diffusion, sdxl…)" : "search gguf repos (qwen, gemma, phi…)",
                kind: .mono,
                onSubmit: model.searchHf,
            )
            if model.hfLoading || model.openLoading { InkLoader() }
            Hairline()
        }
        if model.hfResults.isEmpty && !model.hfLoading { EmptyResults() }
        ForEach(model.hfResults) { m in
            RunRow(
                title: String(m.id.split(separator: "/").last ?? Substring(m.id)),
                subtitle: "\(m.id.split(separator: "/").first ?? "") · \(m.downloads) dl\(m.license.map { " · \($0)" } ?? "")",
                action: { model.open(m.id) },
            ) {
                if m.gated { Tag("gated", accent: AutobotColors.amber) }
            }
        }
    }

    @ViewBuilder private func civitaiTab(_ installed: Set<String>) -> some View {
        @Bindable var model = model
        let account = app.accounts.state
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                MicroLabel([account.civitaiHost.host, account.civitaiUser.map { "signed in · \($0)" } ?? "no api key", account.showMature ? "mature on" : "sfw"].joined(separator: " · "))
                    .frame(maxWidth: .infinity, alignment: .leading)
                Tag("account") { nav.push(.accounts) }
            }
            Segmented(options: CivitaiScope.allCases, selected: model.civitaiScope, label: \.label) { model.setCivitaiScope($0) }
            ConsoleTextField(text: $model.civitaiQuery, placeholder: "search sd 1.5 / sdxl / pony / illustrious", kind: .mono) { model.searchCivitai() }
            MicroLabel("checkpoints and loras run on pc backends (a1111 / forge) — core ml on this phone needs converted models", color: AutobotColors.amber, lines: 3)
            if model.civitaiLoading { InkLoader() }
            Hairline()
        }
        if model.civitaiResults.isEmpty && !model.civitaiLoading { EmptyResults() }
        ForEach(model.civitaiResults) { m in
            CivitaiRow(model: m, installed: installed, previews: model.previews) { model.installCivitai(m) }
        }
        if model.civitaiCursor != nil {
            TextAction("More", color: c.primary) { model.searchCivitai(reset: false) }
        }
    }
}

/// No search results yet: a patch of living paper.
private struct EmptyResults: View {
    var body: some View {
        Color.clear
            .frame(height: 180)
            .inkGround()
            .mask(LinearGradient(colors: [.black, .clear], startPoint: .center, endPoint: .bottom))
            .accessibilityHidden(true)
    }
}

/// 3pt progress bar: red on a hairline track.
struct ProgressLine: View {
    let fraction: Double
    var height: CGFloat = 3
    var running = false

    var body: some View { InkProgressBar(fraction: fraction, height: height, running: running) }
}

/// Transient message at the bottom of the screen (Material Snackbar).
struct Snackbar: View {
    @Binding var message: String?
    @Environment(\.scheme) private var c

    var body: some View {
        if let text = message {
            Text(text)
                .textStyle(.bodyMedium)
                .foregroundStyle(c.inverseOnSurface)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: AutobotShapes.medium).fill(c.inverseSurface))
                .padding(12)
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
    @Environment(\.scheme) private var c
    @State private var confirm = false

    var body: some View {
        let subtitle = [
            model.kind.label,
            model.format.label,
            model.totalBytes > 0 ? formatBytes(model.totalBytes) : nil,
            model.engine.runsOnIOS ? model.engine.label : "not runnable here",
        ].compactMap { $0 }.joined(separator: " · ")
        // Tapping a ready model opens the task it runs (the × tag still removes it).
        let opens = ModelsModel.launch(model).task != ModelTask.none
        RunRow(title: model.title, subtitle: subtitle, action: opens ? onUse : nil) {
            ZStack {
                switch model.status {
                case .ready: Tag("ready", accent: c.primary, filled: true)
                case .downloading: Tag("\(Int(model.fraction * 100))%", accent: c.primary)
                case .queued: Tag("queued")
                case .paused: Tag("resume", accent: c.primary) { app.downloader.resume(model.id) }
                case .failed: Tag("retry", accent: c.error) { app.downloader.resume(model.id) }
                }
            }
            .stamp(model.status == .ready) { $0 }
            Tag(confirm ? "sure?" : "×", accent: confirm ? c.error : c.onSurfaceVariant) {
                if confirm {
                    app.downloader.remove(model.id)
                    app.compute.deleteFor(modelId: model.id)
                } else {
                    confirm = true
                }
            }
        }
        if confirm {
            MicroLabel("Deletes \(formatBytes(model.totalBytes)) from this phone. Chats that use it need another model picked.", color: c.error, lines: 2)
                .padding(.bottom, 8)
        }
        if model.status == .paused { InkProgressBar(.paused(model.fraction), height: 2).padding(.bottom, 6) }
        if model.status == .failed { InkProgressBar(.error(model.fraction), height: 2).padding(.bottom, 6) }
        if let paused = model.pausedLabel { MicroLabel(paused).padding(.bottom, 8) }
        if let error = model.error {
            MicroLabel(error, color: c.error, lines: 4).padding(.bottom, 8)
        }
    }
}

private struct CatalogRow: View {
    let plan: ModelPlan
    let fit: Fit?
    let installed: Bool
    let onGet: () -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        RunRow(title: plan.title, subtitle: [formatBytes(plan.totalBytes), plan.subtitle, plan.license].compactMap { $0 }.joined(separator: " · ")) {
            if let fit {
                Tag(fit.label, accent: fitColor(fit, c))
            }
            if installed {
                Tag("added", accent: c.onSurfaceVariant)
            } else {
                Tag("get", accent: c.primary, filled: true, action: onGet)
            }
        }
    }
}

func fitColor(_ fit: Fit, _ c: AutobotScheme) -> Color {
    switch fit {
    case .good: c.onSurfaceVariant
    case .tight: AutobotColors.amber
    case .tooBig, .unsupported: c.error
    }
}

private struct RepoSheet: View {
    let repo: HfRepo
    let model: ModelsModel
    @Environment(\.scheme) private var c

    var body: some View {
        let owner = String(repo.id.split(separator: "/").first ?? "")
        let files = repo.files.filter { f in
            let n = f.path.lowercased()
            return (n.hasSuffix(".gguf") || n.hasSuffix(".safetensors") || n.hasSuffix(".ckpt")) && !n.contains("mmproj")
        }
        let bundles = repo.coreMLBundles
        ScrollView {
            InkStack(alignment: .leading, spacing: 8) {
                SectionLabel(repo.id)
                Text([repo.license, repo.gated ? "gated — accept on huggingface.co" : nil, repo.commit.map { String($0.prefix(10)) }].compactMap { $0 }.joined(separator: " · "))
                    .textStyle(.micro)
                    .foregroundStyle(c.onSurfaceVariant)
                if !bundles.isEmpty {
                    MicroLabel("core ml · whole folder, one model")
                    ForEach(bundles, id: \.self) { folder in
                        let size = repo.coreMLSize(folder)
                        let fit = model.device?.fit(.image, bytes: size, engine: .coreML)
                        RunRow(title: folder.isEmpty ? "compiled" : folder, subtitle: "\(formatBytes(size)) · \(folder.contains("split_einsum") ? "neural engine" : "gpu")") {
                            if let fit { Tag(fit.label, accent: fitColor(fit, c)) }
                            Tag("get", accent: c.primary, filled: true) { model.installCoreML(repo, folder: folder) }
                        }
                    }
                }
                if files.isEmpty && bundles.isEmpty { MicroLabel("no model files in this repo") }
                ForEach(files, id: \.path) { f in
                    let kind = model.hfScope.kind
                    let fit = model.device?.fit(kind, bytes: f.sizeBytes, engine: engineFor(kind, formatOf(f.path, owner: owner)))
                    RunRow(title: String(f.path.split(separator: "/").last ?? ""), subtitle: "\(formatBytes(f.sizeBytes))\(f.sha256 != nil ? " · sha-256 known" : "")") {
                        if let fit { Tag(fit.label, accent: fit == .good ? c.onSurfaceVariant : AutobotColors.amber) }
                        let format = formatOf(f.path, owner: owner)
                        if format == .safetensors || format == .ckpt {
                            PcOnlyGetTag { model.installFile(repo, f.path) }
                        } else {
                            Tag("get", accent: c.primary, filled: true) { model.installFile(repo, f.path) }
                        }
                    }
                }
                if files.contains(where: { formatOf($0.path, owner: owner) == .safetensors }) {
                    MicroLabel("safetensors checkpoints need a pc backend — on this phone images run from core ml builds", color: AutobotColors.amber, lines: 3)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 20)
        }
        .background(c.surfaceContainerLow.ignoresSafeArea())
    }
}

/// "get" for a file this phone can't run (checkpoints for a PC backend): a second tap confirms.
private struct PcOnlyGetTag: View {
    let action: () -> Void
    @State private var confirm = false
    @Environment(\.scheme) private var c

    var body: some View {
        if confirm {
            Tag("pc only · get anyway", accent: AutobotColors.amber, filled: true) {
                confirm = false
                action()
            }
        } else {
            Tag("pc", accent: c.onSurfaceVariant)
            Tag("get", accent: c.primary, filled: true) { confirm = true }
        }
    }
}

private struct CivitaiRow: View {
    let model: CivitaiModel
    let installed: Set<String>
    let previews: PreviewImages?
    let onGet: () -> Void
    @Environment(\.scheme) private var c
    @State private var preview: UIImage?

    var body: some View {
        let version = model.versions.first
        let file = version?.files.first(where: \.primary) ?? version?.files.first
        RunRow(
            title: model.name,
            subtitle: [version?.baseModel, file.map { formatBytes($0.sizeBytes) }, model.creator, "\(model.downloads) dl"].compactMap { $0 }.joined(separator: " · "),
            leading: {
                ZStack {
                    RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainerHigh)
                    if let preview {
                        Image(uiImage: preview).resizable().scaledToFill().blur(radius: version?.previewMature == true ? 12 : 0)
                    }
                }
                .frame(width: 52, height: 52)
                .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall))
            },
            trailing: {
                if version?.earlyAccess == true {
                    Tag("early access", accent: AutobotColors.amber)
                } else if let version, installed.contains("civitai:\(version.id)") {
                    Tag("added")
                } else {
                    PcOnlyGetTag(action: onGet)
                }
            },
        )
        .task(id: version?.previewUrl) {
            guard let url = version?.previewUrl, let data = await previews?.load(url) else { return }
            preview = await decodeImage(data, maxSide: 160)
        }
    }
}

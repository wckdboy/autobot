import AutobotShared
import Observation
import SwiftUI

/**
 Video room state (spec §4): video is made on the user's PC through ComfyUI workflows (LTX-Video,
 Wan…), labelled "runs on <backend>"; the controls come from the workflow's parameters.
 */
@MainActor
@Observable
final class VideoRoomModel {
    var workflowId: String?
    var values: [String: ParamValue] = [:]
    var prompt = ""
    var negative = ""
    var seedMode: SeedMode = .random
    var seed: Int64 = Int64.random(in: 0 ... 4_294_967_295)
    var selectedId: String?
    private(set) var jobIds: [String] = []

    @ObservationIgnored private let app: AppContainer

    init(app: AppContainer) {
        self.app = app
        workflowId = app.studio.library.workflows.first { $0.output == .video }?.id
    }

    var workflows: [StudioWorkflow] { app.studio.library.workflows.filter { $0.output == .video } }
    var workflow: StudioWorkflow? { app.studio.library.workflow(workflowId) ?? workflows.first }
    var server: ComfyServer? { workflow.flatMap { app.studio.comfyServer($0.backendId) } ?? app.studio.comfyServers.first }
    var videos: [StudioAsset] { app.studio.library.assets.filter { $0.type == .video && $0.projectId == app.studio.projectId } }
    var selected: StudioAsset? { selectedId.flatMap { app.studio.library.asset($0) } ?? videos.first }
    var runningJob: StudioJob? { jobIds.reversed().lazy.compactMap { self.app.studio.job($0) }.first { !$0.state.isFinished } }

    var blocker: String? {
        guard let w = workflow else { return "Import a video workflow in Workflows" }
        if server == nil { return "Add your ComfyUI PC in Workflows" }
        if w.param(name: "prompt") != nil && prompt.trimmingCharacters(in: .whitespaces).isEmpty { return "Describe the shot" }
        if let p = w.params.first(where: { $0.type == .image && $0.default_ == nil }), values[p.name] == nil { return "\(p.label): choose an image" }
        return nil
    }

    func generate() {
        guard blocker == nil, let w = workflow else { return }
        var params = values.filter { w.param(name: $0.key) != nil }
        if w.param(name: "prompt") != nil { params["prompt"] = .text(prompt) }
        if w.param(name: "negative") != nil, !negative.isEmpty { params["negative"] = .text(negative) }
        let r = Recipe(engine: EngineKinds.shared.COMFYUI, backendId: server?.id, model: nil, params: params, inputs: [], workflowId: w.id,
                       workflowVersion: KotlinInt(value: w.version), seed: seedMode == .random ? nil : KotlinLong(value: seed), output: .video)
        jobIds += app.studio.submitBatch(r, count: 1, mode: seedMode).map(\.id)
        if seedMode == .walk { seed = (seed + 1) % 4_294_967_296 }
        selectedId = nil
    }

    /// "Use as input": an image for the workflow's first image parameter (image-to-video).
    func useAsInput(_ asset: StudioAsset) {
        guard let p = workflow?.params.first(where: { $0.type == (asset.type == .audio ? .audio : .image) }) else { return }
        values[p.name] = ParamValue.AssetValue(ref: AssetRef(assetId: asset.id, role: p.role ?? p.name))
    }

    func reuse(_ recipe: Recipe) {
        workflowId = recipe.workflowId
        values = recipe.params
        prompt = recipe.text("prompt") ?? prompt
        negative = recipe.text("negative") ?? negative
        if let s = recipe.seedValue { seed = s; seedMode = .locked }
    }
}

struct VideoRoom: View {
    @Bindable var model: VideoRoomModel
    let session: StudioSession
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c

    var body: some View {
        if model.workflows.isEmpty || app.studio.comfyServers.isEmpty {
            ScrollView {
                StudioEmpty(app.studio.comfyServers.isEmpty ? StudioRoomWords.shared.videoNoPc : StudioRoomWords.shared.videoNoWorkflow) { nav.studio = .workflows }
                if !model.videos.isEmpty { library.padding(.horizontal, Tokens.Spacing.gutter) }
            }
        } else {
            VStack(spacing: 0) {
                stage.frame(minHeight: 170, maxHeight: .infinity)
                StudioPanel(title: "shot", collapsed: Binding(get: { session.collapsed }, set: { session.collapsed = $0 })) { controls }
                    .frame(maxHeight: session.collapsed ? nil : 320)
                    // The controls get their height first; the canvas takes the rest.
                    .layoutPriority(1)
                runBar
            }
        }
    }

    private var stage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if let job = model.runningJob {
                    ZStack(alignment: .bottomLeading) {
                        RunningPlate(job: job).aspectRatio(16 / 9, contentMode: .fit)
                        Tag(job.status ?? (job.steps > 0 ? "step \(job.step)/\(job.steps)" : job.state.label), accent: c.primary, filled: true).padding(8)
                    }
                } else if let v = model.selected {
                    VideoStage(asset: v)
                } else {
                    ZStack(alignment: .bottomLeading) {
                        Color.clear
                    }
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .background(c.surfaceContainerLowest)
                }
                if !model.videos.isEmpty { library }
            }
            .padding(.horizontal, Tokens.Spacing.gutter)
            .padding(.top, 6)
        }
    }

    private var library: some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionLabel("takes · \(model.videos.count)")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(model.videos.prefix(30), id: \.id) { v in
                        AssetThumb(asset: v, size: 64)
                            .overlay { if v.id == model.selected?.id { Rectangle().strokeBorder(c.primary, lineWidth: Tokens.Stroke.selection) } }
                            .onTapGesture { model.selectedId = v.id }
                            .accessibilityAddTraits(v.id == model.selected?.id ? [.isButton, .isSelected] : .isButton)
                    }
                }
            }
        }
    }

    @ViewBuilder private var controls: some View {
        if model.workflows.count > 1 {
            SelectField(label: "Workflow", value: model.workflow?.name ?? "", options: model.workflows.map(\.id), optionLabel: { id in model.workflows.first { $0.id == id }?.name ?? id }) {
                model.workflowId = $0
                model.values = [:]
            }
        }
        if let w = model.workflow {
            RunsOnTag(label: model.server?.name ?? "your pc", route: model.server?.route ?? .pcLan)
            if w.param(name: "prompt") != nil {
                PromptBox(prompt: $model.prompt, negative: $model.negative, placeholder: "a slow dolly through fog at dawn", showNegative: w.param(name: "negative") != nil,
                          negativePresets: PromptLibrary.shared.negativePresets.filter { $0.id == "neg-video" || $0.id == "neg-general" })
            }
            WorkflowForm(workflow: w, values: $model.values) { ["prompt", "negative"].contains($0.name) || $0.type == .seed }
            SeedControl(mode: $model.seedMode, seed: $model.seed)
        }
    }

    private var runBar: some View {
        let blocker = model.blocker
        return VStack(spacing: 6) {
            Text(blocker ?? "\(model.workflow?.name ?? "") · runs on \(model.server?.name ?? "your pc")").textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
                .lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
            PrimaryButton("Generate", enabled: blocker == nil) { model.generate() }
        }
        .padding(.horizontal, Tokens.Spacing.gutter)
        .padding(.vertical, 8)
        .background(c.background)
        .hairlineEdge(top: true, bottom: false)
    }
}

/// A video asset: decrypted to a short-lived file for the player (removed when it goes away).
struct VideoStage: View {
    let asset: StudioAsset
    @Environment(AppContainer.self) private var app
    @State private var url: URL?
    /// Animated WEBP / GIF bytes (ComfyUI's animated saves), played without a file.
    @State private var animated: Data?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if !asset.mimeType.hasPrefix("video/") {
                if let animated {
                    AnimatedImageView(data: animated).id(asset.id)
                } else {
                    InkLoader(.block, caption: "decrypting").frame(maxWidth: .infinity, minHeight: 180)
                }
            } else if let url {
                StudioVideoPlayer(url: url) { frame in
                    Task {
                        guard let png = ImageCodec.png(frame) else { return }
                        let r = asset.recipe.doCopy(engine: asset.recipe.engine, backendId: asset.recipe.backendId, model: asset.recipe.model, params: asset.recipe.params,
                                                    inputs: [AssetRef(assetId: asset.id, role: "video")], workflowId: asset.recipe.workflowId,
                                                    workflowVersion: asset.recipe.workflowVersion, seed: asset.recipe.seed, output: .image)
                        _ = try? await app.studio.addAsset(png, type: .image, mime: "image/png", recipe: r, width: frame.width, height: frame.height, parent: asset.id, label: "frame")
                        app.studio.notice("Frame saved to the project")
                    }
                }
                .id(url)
            } else {
                InkLoader(.block, caption: "decrypting").frame(maxWidth: .infinity, minHeight: 180)
            }
            MicroLabel(StudioText.meta(asset))
        }
        .task(id: asset.id) {
            if !asset.mimeType.hasPrefix("video/") {
                animated = await app.studio.bytes(asset.id)
                return
            }
            let files = app.studio.files, id = asset.id, ext = StudioCodec.shared.extension(mimeType: asset.mimeType)
            let old = url
            url = await Task.detached { files.openPlain(id, ext: ext) }.value
            if let old { try? FileManager.default.removeItem(at: old) }
        }
        .onDisappear {
            if let url { try? FileManager.default.removeItem(at: url) }
        }
    }
}

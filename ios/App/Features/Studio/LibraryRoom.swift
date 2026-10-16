import AutobotShared
import SwiftUI

/**
 The library (spec §1), on paper: every asset of the project (or all projects) as a grid or a
 list, filtered by type and favourites. An asset opens with its recipe and the actions: favourite,
 use as input, reuse recipe, compare (A/B slider for images, A/B playback for audio), export
 (explicit and confirmed: an unencrypted copy leaves the app) and delete.
 */
struct LibraryRoom: View {
    let session: StudioSession
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @AppStorage("studio_library_list") private var list = false
    @State private var type: String = "all"
    @State private var favorites = false
    @State private var allProjects = false
    @State private var opened: StudioAsset?
    @State private var comparing: StudioAsset?
    @State private var pair: ComparePair?

    var body: some View {
        let _ = InkProbe.hit("body.library")
        let studio = app.studio
        let types: Set<AssetType> = switch type {
        case "image": [.image]
        case "audio": [.audio]
        case "video": [.video]
        default: [.image, .audio, .video]
        }
        let assets = studio.library.assets(in: allProjects ? nil : studio.projectId, types: types, favoritesOnly: favorites)
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                Segmented(options: ["all", "image", "audio", "video"], selected: type, label: { $0 }) { type = $0 }
                HStack(spacing: 6) {
                    Tag("★ favourites", accent: favorites ? c.primary : c.onSurfaceVariant, filled: favorites) { favorites.toggle() }
                    Tag(allProjects ? "all projects" : "this project", accent: c.onSurfaceVariant) { allProjects.toggle() }
                    Spacer()
                    IconAction(symbol: list ? "square.grid.2x2" : "list.bullet", label: list ? "Show as grid" : "Show as list", size: 16) { list.toggle() }
                }
                if let comparing {
                    HStack {
                        MicroLabel("compare · pick a second \(comparing.type.name.lowercased())", color: c.primary)
                        Spacer()
                        TextAction("Cancel") { self.comparing = nil }
                    }
                }
            }
            .padding(.horizontal, Tokens.Spacing.gutter)
            .padding(.vertical, 8)
            if assets.isEmpty {
                StudioEmpty(title: favorites ? "no favourites here" : "nothing here yet", text: "Everything the Studio makes lands in its project, encrypted on this iPhone, with the recipe that made it.")
                Spacer()
            } else {
                ScrollView {
                    if list {
                        LazyVStack(spacing: 0) {
                            ForEach(assets, id: \.id) { a in
                                RunRow(title: StudioText.describe(a), subtitle: a.label ?? StudioText.meta(a), action: { tap(a) }, leading: { AssetThumb(asset: a, size: 44) }) {
                                    if a.favorite { Text("★").foregroundStyle(c.primary) }
                                }
                                .transition(.inkDissolve)
                            }
                        }
                        .padding(.horizontal, Tokens.Spacing.gutter)
                    } else {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 4)], spacing: 4) {
                            ForEach(assets, id: \.id) { a in
                                AssetThumb(asset: a)
                                    .aspectRatio(1, contentMode: .fill)
                                    .overlay(alignment: .topTrailing) {
                                        if a.favorite { Text("★").textStyle(.labelSmall).foregroundStyle(c.primary).padding(4) }
                                    }
                                    .overlay { if comparing?.id == a.id { Rectangle().strokeBorder(c.primary, lineWidth: Tokens.Stroke.selection) } }
                                    .contentShape(Rectangle())
                                    .onTapGesture { tap(a) }
                                    .accessibilityAddTraits(.isButton)
                                    .transition(.inkDissolve)
                            }
                        }
                        .padding(.horizontal, Tokens.Spacing.gutter)
                    }
                }
                .animation(.inkDissolve, value: assets.map(\.id))
            }
        }
        .background(c.background)
        .sheet(item: $opened) { a in
            AssetDetail(asset: a, session: session) { action in
                opened = nil
                if case .compare = action { comparing = a }
            }
            .presentationDetents([.large])
        }
        .fullScreenCover(item: $pair) { p in
            CompareView(a: p.a, b: p.b, player: session.player) { pair = nil }
        }
    }

    private func tap(_ a: StudioAsset) {
        if let first = comparing {
            guard a.id != first.id, a.type == first.type else { return }
            comparing = nil
            pair = ComparePair(a: first, b: a)
        } else {
            opened = a
        }
    }
}

extension StudioAsset: @retroactive Identifiable {}

private struct ComparePair: Identifiable {
    let a: StudioAsset
    let b: StudioAsset
    var id: String { a.id + b.id }
}

enum AssetAction { case done, compare }

/// One asset: preview, recipe, actions.
private struct AssetDetail: View {
    let asset: StudioAsset
    let session: StudioSession
    let onClose: (AssetAction) -> Void
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var confirmExport = false
    @State private var confirmDelete = false
    @State private var exportAudio: AudioFileDocument?

    var body: some View {
        let studio = app.studio
        let a = studio.library.asset(asset.id) ?? asset
        ScrollView {
            InkStack(alignment: .leading, spacing: 12) {
                HStack {
                    SectionLabel(a.type.name.lowercased() + (a.label.map { " · \($0)" } ?? ""))
                    TextAction("Close") { onClose(.done) }
                }
                switch a.type {
                case .image: CanvasImage(asset: a).frame(height: 340)
                case .video: VideoStage(asset: a)
                default: AudioPreview(asset: a, player: session.player)
                }
                if let prompt = a.recipe.text("prompt") ?? a.recipe.text("text") {
                    Text(prompt).textStyle(.bodyMedium).foregroundStyle(c.onSurface).textSelection(.enabled)
                }
                MicroLabel(StudioText.meta(a), lines: 2)
                Text(recipeText(a.recipe)).textStyle(.codeBlock).foregroundStyle(c.onSurfaceVariant).textSelection(.enabled)
                ImagineTagFlow(spacing: 6) {
                    Tag(a.favorite ? "★ favourite" : "☆ favourite", accent: c.primary, filled: a.favorite) { studio.setFavorite(a.id, !a.favorite) }
                    Tag("→ use as input") { useAsInput(a) }
                    Tag("↺ reuse recipe", accent: c.primary) { reuse(a) }
                    Tag("compare") { onClose(.compare) }
                    Tag("export", accent: AutobotColors.amber) { confirmExport = true }
                    Tag("delete", accent: c.error) { confirmDelete = true }
                }
            }
            .padding(16)
        }
        .background(c.surfaceContainerLow)
        .risoAlert($confirmExport, title: "Export unencrypted copy?",
                   message: a.type == .audio ? "The clip is saved where you choose, outside Autobot's encryption." : "A copy is saved to your photo library, outside Autobot's encryption.",
                   confirm: "Export", destructive: false) { export(a) }
        .risoAlert($confirmDelete, title: "Delete \(a.type.name.lowercased())?", message: "The encrypted file and its recipe are removed. This cannot be undone.", confirm: "Delete") {
            studio.deleteAsset(a.id)
            onClose(.done)
        }
        .fileExporter(isPresented: Binding(get: { exportAudio != nil }, set: { if !$0 { exportAudio = nil } }), document: exportAudio, contentType: .audio,
                      defaultFilename: "autobot-\(a.id).\(StudioCodec.shared.extension(mimeType: a.mimeType))") { r in
            if case .success = r { studio.notice("Exported") }
        }
    }

    private func recipeText(_ r: Recipe) -> String {
        var lines = ["engine \(r.engine)\(r.backendId.map { " @ \($0)" } ?? " · this iphone")"]
        if let m = r.model { lines.append("model \(m)") }
        if let w = app.studio.library.workflow(r.workflowId) { lines.append("workflow \(w.name) v\(r.workflowVersion?.intValue ?? Int(w.version))") }
        for (k, v) in r.params.sorted(by: { $0.key < $1.key }) where !["prompt", "text", "mask"].contains(k) { lines.append("\(k) \(v.display().prefix(60))") }
        if !r.inputs.isEmpty { lines.append("inputs \(r.inputs.map { "\($0.role)=\($0.assetId)" }.joined(separator: ", "))") }
        return lines.joined(separator: "\n")
    }

    private func useAsInput(_ a: StudioAsset) {
        switch a.type {
        case .image:
            Task { await session.image.useAsInput(a) }
            if session.video.workflow?.params.contains(where: { $0.type == .image }) == true && session.image.backends.isEmpty {
                session.video.useAsInput(a)
                nav.studio = .video
            } else {
                nav.studio = .image
            }
        case .audio:
            session.video.useAsInput(a)
            nav.studio = .workflows
        default:
            app.studio.notice("Videos can't be an input yet")
        }
        onClose(.done)
    }

    private func reuse(_ a: StudioAsset) {
        if a.type == .audio {
            session.audio.reuse(a.recipe)
            nav.studio = .audio
        } else if a.type == .video {
            session.video.reuse(a.recipe)
            nav.studio = .video
        } else {
            Task { await session.image.reuse(a.recipe) }
            nav.studio = .image
        }
        onClose(.done)
    }

    private func export(_ a: StudioAsset) {
        let isVideo = a.type == .video, isAudio = a.type == .audio, ext = StudioCodec.shared.extension(mimeType: a.mimeType)
        Task {
            guard let data = await app.studio.bytes(a.id) else { app.studio.notice("Export failed"); return }
            if isAudio {
                exportAudio = AudioFileDocument(data: data)
            } else {
                app.studio.notice(await StudioMedia.exportToPhotos(data, video: isVideo, ext: ext))
            }
        }
    }
}

/// An audio asset in the detail sheet: waveform and play / loop.
private struct AudioPreview: View {
    let asset: StudioAsset
    let player: StudioAudioPlayer
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var peaks: [Float] = []

    var body: some View {
        let playing = player.playingId == asset.id
        VStack(alignment: .leading, spacing: 8) {
            PlayheadWaveform(id: asset.id, peaks: peaks, player: player)
                .frame(height: 96)
            HStack {
                IconAction(symbol: playing ? "stop.fill" : "play.fill", label: playing ? "Stop" : "Play") {
                    if playing { player.stop() } else { Task { if let d = await app.studio.bytes(asset.id) { player.play(asset.id, data: d) } } }
                }
                Tag(player.loop ? "loop on" : "loop off", accent: player.loop ? c.primary : c.onSurfaceVariant, filled: player.loop) { player.setLoop(!player.loop) }
            }
        }
        .task { peaks = await app.studio.waveform(asset) }
    }
}

/// A/B: images under a draggable divider; audio switching between the two clips at the same position.
struct CompareView: View {
    let a: StudioAsset
    let b: StudioAsset
    let player: StudioAudioPlayer
    let onClose: () -> Void
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var split = 0.5
    @State private var imageA: UIImage?
    @State private var imageB: UIImage?
    @State private var peaksA: [Float] = []
    @State private var peaksB: [Float] = []

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                SectionLabel("compare · a / b")
                TextAction("Done") { player.stop(); onClose() }
            }
            .padding(.horizontal, Tokens.Spacing.gutter)
            if a.type == .image {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        if let imageB { Image(uiImage: imageB).resizable().scaledToFit().frame(width: geo.size.width, height: geo.size.height) }
                        if let imageA {
                            Image(uiImage: imageA).resizable().scaledToFit().frame(width: geo.size.width, height: geo.size.height)
                                .mask(alignment: .leading) { Rectangle().frame(width: geo.size.width * split) }
                        }
                        Rectangle().fill(c.primary).frame(width: 2).offset(x: geo.size.width * split - 1)
                        Text("A").textStyle(.labelLarge).foregroundStyle(c.onPrimary).padding(6).background(c.primary).padding(8)
                            .frame(maxHeight: .infinity, alignment: .top)
                        Text("B").textStyle(.labelLarge).foregroundStyle(c.onPrimary).padding(6).background(c.primary).padding(8)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                    }
                    .contentShape(Rectangle())
                    .gesture(DragGesture(minimumDistance: 0).onChanged { g in split = min(max(g.location.x / max(geo.size.width, 1), 0), 1) })
                    .accessibilityElement()
                    .accessibilityLabel("A/B comparison")
                    .accessibilityValue("\(Int(split * 100)) percent A")
                    .accessibilityAdjustableAction { d in split = d == .increment ? min(split + 0.1, 1) : max(split - 0.1, 0) }
                }
                MicroLabel("drag across to wipe between a and b").padding(.horizontal, Tokens.Spacing.gutter)
            } else if a.type == .audio {
                VStack(alignment: .leading, spacing: 14) {
                    side("A", a, peaksA)
                    side("B", b, peaksB)
                    MicroLabel("switching keeps the position · loop is \(player.loop ? "on" : "off")", lines: 2)
                }
                .padding(.horizontal, Tokens.Spacing.gutter)
                Spacer()
            } else {
                ScrollView {
                    VStack(spacing: 10) {
                        MicroLabel("a")
                        VideoStage(asset: a)
                        MicroLabel("b")
                        VideoStage(asset: b)
                    }
                    .padding(.horizontal, Tokens.Spacing.gutter)
                }
            }
            HStack(alignment: .top, spacing: 8) {
                MicroLabel("a · \(StudioText.meta(a))", lines: 3).frame(maxWidth: .infinity, alignment: .leading)
                MicroLabel("b · \(StudioText.meta(b))", lines: 3).frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, Tokens.Spacing.gutter)
        }
        .padding(.vertical, 12)
        .background(c.background.ignoresSafeArea())
        .task {
            if a.type == .image {
                if let d = await app.studio.bytes(a.id) { imageA = await decodeImage(d, maxSide: 1536) }
                if let d = await app.studio.bytes(b.id) { imageB = await decodeImage(d, maxSide: 1536) }
            } else if a.type == .audio {
                peaksA = await app.studio.waveform(a)
                peaksB = await app.studio.waveform(b)
            }
        }
    }

    private func side(_ name: String, _ asset: StudioAsset, _ peaks: [Float]) -> some View {
        let playing = player.playingId == asset.id
        return HStack(spacing: 10) {
            Button {
                Task { if let d = await app.studio.bytes(asset.id) { player.play(asset.id, data: d, keepPosition: player.playingId != nil) } }
            } label: {
                Text(name).textStyle(.titleLarge).foregroundStyle(playing ? c.onPrimary : c.onSurface).frame(width: 52, height: 52)
                    .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(playing ? c.primary : c.surfaceContainerHigh))
            }
            .buttonStyle(PressStyle())
            .accessibilityLabel("Play \(name)")
            PlayheadWaveform(id: asset.id, peaks: peaks, player: player).frame(height: 52)
        }
    }
}

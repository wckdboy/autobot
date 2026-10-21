import AutobotShared
import SwiftUI

/**
 The library (spec §1): every asset of the project (or all projects) as a grid or a list, filtered
 by type and favourites. An asset opens with its recipe and the actions: favourite, use as input,
 reuse recipe, compare (A/B slider for images, A/B playback for audio), export (explicit and
 confirmed: an unencrypted copy leaves the app) and delete.
 */
struct LibraryRoom: View {
    let session: StudioSession
    @Environment(AppContainer.self) private var app
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
        Group {
            if assets.isEmpty {
                StudioEmpty(favorites ? StudioRoomWords.shared.libraryNoFavourites : StudioRoomWords.shared.libraryEmpty)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if list {
                List(assets, id: \.id) { a in
                    Button { tap(a) } label: {
                        HStack(spacing: 12) {
                            AssetThumb(asset: a, size: 44)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(StudioText.describe(a)).lineLimit(1)
                                Text(a.label ?? StudioText.meta(a)).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            if a.favorite { Image(systemName: "star.fill").foregroundStyle(.tint).accessibilityLabel("Favourite") }
                        }
                    }
                    .tint(.primary)
                    .listRowBackground(comparing?.id == a.id ? Color.accentColor.opacity(0.15) : nil)
                    .contextMenu { menu(a) }
                }
                .listStyle(.plain)
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 4)], spacing: 4) {
                        ForEach(assets, id: \.id) { a in
                            AssetThumb(asset: a)
                                .aspectRatio(1, contentMode: .fill)
                                .overlay(alignment: .topTrailing) {
                                    if a.favorite {
                                        Image(systemName: "star.fill").font(.caption2).foregroundStyle(.white).padding(4)
                                            .background(Circle().fill(.black.opacity(0.45))).padding(4)
                                    }
                                }
                                .overlay {
                                    if comparing?.id == a.id { RoundedRectangle(cornerRadius: 6, style: .continuous).strokeBorder(.tint, lineWidth: 3) }
                                }
                                .contentShape(Rectangle())
                                .onTapGesture { tap(a) }
                                .contextMenu { menu(a) }
                                .accessibilityAddTraits(.isButton)
                                .accessibilityLabel((a.favorite ? "Favourite. " : "") + StudioText.describe(a))
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 16)
                }
            }
        }
        .animation(.smooth, value: assets.map(\.id))
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                Picker("Type", selection: $type) {
                    Text("All").tag("all")
                    Text("Image").tag("image")
                    Text("Audio").tag("audio")
                    Text("Video").tag("video")
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                HStack(spacing: 6) {
                    StudioChip("★ Favourites", active: favorites) { favorites.toggle() }
                    StudioChip(allProjects ? "All projects" : "This project") { allProjects.toggle() }
                    Spacer()
                    Button { list.toggle() } label: { Image(systemName: list ? "square.grid.2x2" : "list.bullet") }
                        .buttonStyle(.borderless)
                        .accessibilityLabel(list ? "Show as grid" : "Show as list")
                }
                if let comparing {
                    HStack {
                        Text("Compare · pick a second \(comparing.type.name.lowercased())").font(.footnote).foregroundStyle(.tint)
                        Spacer()
                        Button("Cancel") { self.comparing = nil }.buttonStyle(.borderless)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(.bar)
        }
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

    @ViewBuilder private func menu(_ a: StudioAsset) -> some View {
        Button(a.favorite ? "Remove from favourites" : "Add to favourites", systemImage: a.favorite ? "star.slash" : "star") {
            app.studio.setFavorite(a.id, !a.favorite)
        }
        Button("Compare", systemImage: "square.split.2x1") { comparing = a }
        Button("Open", systemImage: "info.circle") { opened = a }
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
    @State private var confirmExport = false
    @State private var confirmDelete = false
    @State private var exportAudio: AudioFileDocument?

    var body: some View {
        let studio = app.studio
        let a = studio.library.asset(asset.id) ?? asset
        NavigationStack {
            List {
                Section {
                    switch a.type {
                    case .image: CanvasImage(asset: a).frame(height: 340)
                    case .video: VideoStage(asset: a)
                    default: AudioPreview(asset: a, player: session.player)
                    }
                }
                Section {
                    if let prompt = a.recipe.text("prompt") ?? a.recipe.text("text") {
                        Text(prompt).textSelection(.enabled)
                    }
                    Text(StudioText.meta(a)).font(.footnote).foregroundStyle(.secondary)
                }
                Section("Recipe") {
                    Text(recipeText(a.recipe)).font(.footnote.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
                }
                Section {
                    Button(a.favorite ? "Favourite" : "Add to favourites", systemImage: a.favorite ? "star.fill" : "star") { studio.setFavorite(a.id, !a.favorite) }
                        .accessibilityAddTraits(a.favorite ? .isSelected : [])
                    Button("Use as input", systemImage: "arrow.right.circle") { useAsInput(a) }
                    Button("Reuse recipe", systemImage: "arrow.counterclockwise") { reuse(a) }
                    Button("Compare", systemImage: "square.split.2x1") { onClose(.compare) }
                    Button("Export", systemImage: "square.and.arrow.up") { confirmExport = true }
                    Button("Delete", systemImage: "trash", role: .destructive) { confirmDelete = true }
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle(a.type.name.lowercased().sentenceCased + (a.label.map { " · \($0)" } ?? ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { onClose(.done) } } }
        }
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
    @State private var peaks: [Float] = []

    var body: some View {
        let playing = player.playingId == asset.id
        VStack(alignment: .leading, spacing: 8) {
            PlayheadWaveform(id: asset.id, peaks: peaks, player: player)
                .frame(height: 96)
            HStack(spacing: 8) {
                Button {
                    if playing { player.stop() } else { Task { if let d = await app.studio.bytes(asset.id) { player.play(asset.id, data: d) } } }
                } label: {
                    Image(systemName: playing ? "stop.fill" : "play.fill").frame(width: 28, height: 28)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(playing ? "Stop" : "Play")
                StudioChip(player.loop ? "Loop on" : "Loop off", active: player.loop) { player.setLoop(!player.loop) }
            }
        }
        .padding(.vertical, 4)
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
    @State private var split = 0.5
    @State private var imageA: UIImage?
    @State private var imageB: UIImage?
    @State private var peaksA: [Float] = []
    @State private var peaksB: [Float] = []

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                if a.type == .image {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            if let imageB { Image(uiImage: imageB).resizable().scaledToFit().frame(width: geo.size.width, height: geo.size.height) }
                            if let imageA {
                                Image(uiImage: imageA).resizable().scaledToFit().frame(width: geo.size.width, height: geo.size.height)
                                    .mask(alignment: .leading) { Rectangle().frame(width: geo.size.width * split) }
                            }
                            Rectangle().fill(.tint).frame(width: 2).offset(x: geo.size.width * split - 1)
                            sideLabel("A").padding(8).frame(maxHeight: .infinity, alignment: .top)
                            sideLabel("B").padding(8).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                        }
                        .contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 0).onChanged { g in split = min(max(g.location.x / max(geo.size.width, 1), 0), 1) })
                        .accessibilityElement()
                        .accessibilityLabel("A/B comparison")
                        .accessibilityValue("\(Int(split * 100)) percent A")
                        .accessibilityAdjustableAction { d in split = d == .increment ? min(split + 0.1, 1) : max(split - 0.1, 0) }
                    }
                    Text("Drag across to wipe between A and B").font(.footnote).foregroundStyle(.secondary)
                } else if a.type == .audio {
                    VStack(alignment: .leading, spacing: 14) {
                        side("A", a, peaksA)
                        side("B", b, peaksB)
                        Text("Switching keeps the position · loop is \(player.loop ? "on" : "off")").font(.footnote).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 16)
                    Spacer()
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("A").font(.headline)
                            VideoStage(asset: a)
                            Text("B").font(.headline)
                            VideoStage(asset: b)
                        }
                        .padding(.horizontal, 16)
                    }
                }
                HStack(alignment: .top, spacing: 8) {
                    Text("A · \(StudioText.meta(a))").frame(maxWidth: .infinity, alignment: .leading)
                    Text("B · \(StudioText.meta(b))").frame(maxWidth: .infinity, alignment: .leading)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(3)
                .padding(.horizontal, 16)
            }
            .padding(.vertical, 12)
            .navigationTitle("Compare · A / B")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { player.stop(); onClose() } } }
        }
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

    private func sideLabel(_ name: String) -> some View {
        Text(name)
            .font(.headline)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .glassSurface(in: Capsule())
    }

    private func side(_ name: String, _ asset: StudioAsset, _ peaks: [Float]) -> some View {
        let playing = player.playingId == asset.id
        return HStack(spacing: 12) {
            Button {
                Task { if let d = await app.studio.bytes(asset.id) { player.play(asset.id, data: d, keepPosition: player.playingId != nil) } }
            } label: {
                Text(name).font(.title2.weight(.semibold)).frame(width: 52, height: 52)
            }
            .modifier(SideButtonStyle(playing: playing))
            .accessibilityLabel("Play \(name)")
            .accessibilityAddTraits(playing ? .isSelected : [])
            PlayheadWaveform(id: asset.id, peaks: peaks, player: player).frame(height: 52)
        }
    }
}

/// The playing side of an A/B is the prominent button.
private struct SideButtonStyle: ViewModifier {
    let playing: Bool

    func body(content: Content) -> some View {
        if playing { content.glassProminentButton() } else { content.glassButton() }
    }
}

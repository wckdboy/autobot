import Photos
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Decrypted thumbnails, kept for the process (they are small; the source stays encrypted).
@MainActor
private enum ThumbCache {
    static var images: [String: UIImage] = [:]
}

/// The encrypted gallery (port of `GalleryScreen.kt` / `GalleryViewModel.kt`).
struct GalleryScreen: View {
    var openId: String? = nil
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var favoritesOnly = false
    @State private var query = ""
    @State private var open: String?
    @State private var message: String?

    var body: some View {
        let all = app.gallery.items
        let items = all.filter { item in
            (!favoritesOnly || item.favorite) &&
                (query.isEmpty || item.prompt.localizedCaseInsensitiveContains(query) || item.engine.localizedCaseInsensitiveContains(query))
        }
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    IconAction(symbol: "arrow.left", label: "Back") { nav.back() }
                    Text("GALLERY").textStyle(.titleMedium).foregroundStyle(c.onBackground).frame(maxWidth: .infinity, alignment: .leading)
                    MicroLabel("\(items.count)/\(all.count) · encrypted").padding(.trailing, 8)
                    IconAction(symbol: "star.fill", label: "Favorites only", tint: favoritesOnly ? AutobotColors.amber : c.onSurfaceVariant) { favoritesOnly.toggle() }
                }
                .padding(4)
                ConsoleTextField(text: $query, placeholder: "search prompts / backends", kind: .mono)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 8)
            }
            .hairlineEdge()
            if let message {
                HStack {
                    MicroLabel(message, color: c.primary).frame(maxWidth: .infinity, alignment: .leading)
                    TextAction("OK") { self.message = nil }
                }
                .padding(.horizontal, 16)
            }
            if items.isEmpty {
                Spacer()
                MicroLabel(all.isEmpty ? "nothing generated yet" : "no matches")
                Spacer()
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 112), spacing: 4)], spacing: 4) {
                        ForEach(items) { item in
                            Thumb(item: item).onTapGesture { open = item.id }
                        }
                    }
                    .padding(8)
                }
            }
        }
        .background(c.background)
        .onAppear { if let openId { open = openId } }
        .fullScreenCover(item: Binding(get: { open.map(OpenId.init) }, set: { open = $0?.id })) { target in
            if let item = app.gallery.get(target.id) {
                DetailView(item: item, onClose: { open = nil }, onMessage: { message = $0 }) { asInit in
                    open = nil
                    nav.push(.imagine(handoffId: item.id, asInit: asInit))
                }
            }
        }
    }
}

private struct OpenId: Identifiable { let id: String }

private struct Thumb: View {
    let item: GalleryItem
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.clear
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            if item.favorite {
                Image(systemName: "star.fill").font(.system(size: 12)).foregroundStyle(AutobotColors.amber).padding(4)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainerLow))
        .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall))
        .contentShape(Rectangle())
        .accessibilityLabel(String(item.prompt.prefix(60)))
        .task(id: item.id) {
            if let cached = ThumbCache.images[item.id] {
                image = cached
                return
            }
            guard let bytes = await app.gallery.thumbnail(item.id), let decoded = await decodeImage(bytes) else { return }
            ThumbCache.images[item.id] = decoded
            image = decoded
        }
    }
}

private struct DetailView: View {
    let item: GalleryItem
    let onClose: () -> Void
    let onMessage: (String) -> Void
    let onReuse: (Bool) -> Void
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var image: UIImage?
    @State private var confirmDelete = false
    @State private var confirmExport = false
    @State private var scale: CGFloat = 1
    @State private var baseScale: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var baseOffset: CGSize = .zero

    var body: some View {
        let current = app.gallery.get(item.id) ?? item
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                IconAction(symbol: "xmark", label: "Close", action: onClose)
                MicroLabel(relativeTime(item.createdAt)).frame(maxWidth: .infinity, alignment: .leading)
                IconAction(symbol: "star.fill", label: "Favorite", tint: current.favorite ? AutobotColors.amber : c.onSurfaceVariant) {
                    app.gallery.setFavorite(item.id, !current.favorite)
                }
            }
            .padding(4)
            Hairline()
            ZStack {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .scaleEffect(scale)
                        .offset(offset)
                        .gesture(
                            MagnifyGesture()
                                .onChanged { scale = min(max(baseScale * $0.magnification, 1), 8) }
                                .onEnded { _ in
                                    baseScale = scale
                                    if scale == 1 { offset = .zero; baseOffset = .zero }
                                }
                                .simultaneously(with: DragGesture().onChanged { g in
                                    guard scale > 1 else { return }
                                    offset = CGSize(width: baseOffset.width + g.translation.width, height: baseOffset.height + g.translation.height)
                                }.onEnded { _ in baseOffset = offset }),
                        )
                } else {
                    MicroLabel("decrypting…")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
            Hairline()
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    MicroLabel("Prompt")
                    Text(item.prompt).textStyle(.code).foregroundStyle(c.onSurface).textSelection(.enabled)
                    if !item.negativePrompt.isEmpty {
                        MicroLabel("Negative")
                        Text(item.negativePrompt).textStyle(.codeBlock).foregroundStyle(c.onSurfaceVariant)
                    }
                    ImagineTagFlow(spacing: 18) {
                        Readout(label: "Seed", value: "\(item.seed)", valueColor: c.primary)
                        Readout(label: "Size", value: "\(item.width)×\(item.height)")
                        Readout(label: "Steps", value: "\(item.steps)")
                        Readout(label: "CFG", value: String(format: "%.1f", item.cfgScale))
                        Readout(label: "Sampler", value: item.sampler)
                        Readout(label: "Mode", value: item.mode.lowercased())
                        Readout(label: "Engine", value: item.engine)
                        Readout(label: "Time", value: String(format: "%.1fs", Double(item.durationMs) / 1000))
                        if let model = item.model { Readout(label: "Model", value: model) }
                    }
                    ImagineTagFlow(spacing: 6) {
                        Tag("reuse params", accent: c.primary) { onReuse(false) }
                        Tag("→ img2img", accent: AutobotColors.uv) { onReuse(true) }
                        Tag("copy params", accent: c.onSurfaceVariant) {
                            UIPasteboard.general.setItems([[UTType.plainText.identifier: parametersText(item)]], options: [.localOnly: true])
                            onMessage("Parameters copied")
                        }
                        Tag("export", accent: AutobotColors.amber) { confirmExport = true }
                        Tag("delete", accent: c.error) { confirmDelete = true }
                    }
                    MicroLabel("4× npu upscaling is android-only (hexagon npu)", color: c.outline, lines: 2)
                    Spacer().frame(height: 8)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 320)
        }
        .background(c.surfaceContainerLowest.ignoresSafeArea())
        .task {
            guard let bytes = await app.gallery.image(item.id) else { return }
            image = await decodeImage(bytes, maxSide: 2048)
        }
        .risoAlert($confirmExport, title: "Export unencrypted copy?",
                   message: "A plain image will be added to your photo library, where other apps and backups can read it.",
                   confirm: "Export", destructive: false) { export() }
        .risoAlert($confirmDelete, title: "Delete image?", message: "The encrypted file and its parameters are removed. This cannot be undone.",
                   confirm: "Delete") {
            app.gallery.delete(item.id)
            ThumbCache.images[item.id] = nil
            onMessage("Deleted")
            onClose()
        }
    }

    /// Writes a decrypted copy to the photo library (add-only access). This deliberately leaves
    /// the encrypted vault, so the UI asks first.
    private func export() {
        let id = item.id
        Task {
            guard let bytes = await app.gallery.image(id) else {
                onMessage("Export failed")
                return
            }
            let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            guard status == .authorized || status == .limited else {
                onMessage("Photo library access was not granted")
                return
            }
            do {
                try await PHPhotoLibrary.shared().performChanges {
                    PHAssetCreationRequest.forAsset().addResource(with: .photo, data: bytes, options: nil)
                }
                onMessage("Exported to Photos")
            } catch {
                onMessage("Export failed")
            }
        }
    }

    /// A1111-style "parameters" text, for pasting into other tools.
    private func parametersText(_ item: GalleryItem) -> String {
        var s = item.prompt
        if !item.negativePrompt.isEmpty { s += "\nNegative prompt: \(item.negativePrompt)" }
        s += "\nSteps: \(item.steps), Sampler: \(item.sampler), CFG scale: \(item.cfgScale), Seed: \(item.seed), Size: \(item.width)x\(item.height)"
        if let model = item.model { s += ", Model: \(model)" }
        return s
    }
}

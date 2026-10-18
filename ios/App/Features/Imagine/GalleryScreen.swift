import Photos
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/**
 Decrypted gallery thumbnails, decoded at about twice the grid cell and bounded by their pixel
 bytes (the system also evicts under memory pressure). Cleared by a delete and by a panic wipe:
 nothing decrypted outlives the item.
 */
@MainActor
enum GalleryThumbs {
    /// Longest side decoded, in pixels (cells are ~112–130 pt; 3x screens).
    static let maxSide: CGFloat = 384
    private static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.totalCostLimit = 48 * 1024 * 1024
        return c
    }()

    static func get(_ id: String) -> UIImage? { cache.object(forKey: id as NSString) }

    static func put(_ id: String, _ image: UIImage) {
        let pixels = image.size.width * image.scale * image.size.height * image.scale
        cache.setObject(image, forKey: id as NSString, cost: Int(pixels) * 4)
    }

    static func remove(_ id: String) { cache.removeObject(forKey: id as NSString) }

    static func removeAll() { cache.removeAllObjects() }
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
        let _ = InkProbe.hit("body.gallery")
        let all = app.gallery.items
        let items = all.filter { item in
            (!favoritesOnly || item.favorite) &&
                (query.isEmpty || item.prompt.localizedCaseInsensitiveContains(query) || item.engine.localizedCaseInsensitiveContains(query))
        }
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                // The same top bar as every other screen (it was a smaller, one-off title).
                AutobotTopBar(title: "Gallery", onBack: { nav.back() }) {
                    MicroLabel(items.count == all.count ? "\(all.count)" : "\(items.count)/\(all.count)")
                    IconAction(symbol: "star.fill", label: "Favorites only", tint: favoritesOnly ? c.inks.amber : c.onSurfaceVariant) { favoritesOnly.toggle() }
                        .accessibilityAddTraits(favoritesOnly ? .isSelected : [])
                }
                ConsoleTextField(text: $query, placeholder: "Search", kind: .mono)
                    .padding(.horizontal, Tokens.Spacing.gutter)
                    .padding(.bottom, 8)
            }
            .hairlineEdge()
            Group {
                if let message {
                    HStack {
                        MicroLabel(message, color: c.primary).frame(maxWidth: .infinity, alignment: .leading)
                        TextAction("OK") { self.message = nil }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .stamp(message) { $0 != nil }
            if items.isEmpty {
                MicroLabel(all.isEmpty ? "nothing generated yet" : "no matches")
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, Tokens.Spacing.gutter)
                    .inkClearance(c.background)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .inkGround(fade: .center)
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 112), spacing: 4)], spacing: 4) {
                        ForEach(items) { item in
                            Thumb(item: item).onTapGesture { open = item.id }.accessibilityAddTraits(.isButton).transition(.inkDissolve)
                        }
                    }
                    .padding(8)
                    .animation(.inkDissolve, value: items.map(\.id))
                }
            }
        }
        .background(c.background)
        .onAppear { if let openId, app.gallery.get(openId) != nil { open = openId } }
        .fullScreenCover(item: Binding(get: { open.map(OpenId.init) }, set: { open = $0?.id })) { target in
            if let item = app.gallery.get(target.id) {
                DetailView(item: item, onClose: { open = nil }, onMessage: { message = $0 }, onOpen: { open = $0 }) { asInit in
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
            if let image { Image(uiImage: image).resizable().scaledToFill().transition(.opacity) }
            if item.favorite {
                Image(systemName: "star.fill").symbolSize(12).foregroundStyle(AutobotColors.amber).padding(4)
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .background(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall).fill(c.surfaceContainerLow))
        .clipShape(RoundedRectangle(cornerRadius: AutobotShapes.extraSmall))
        .contentShape(Rectangle())
        .accessibilityLabel((item.favorite ? "Favorite. " : "") + String(item.prompt.prefix(60)))
        .task(id: item.id) {
            if let cached = GalleryThumbs.get(item.id) {
                image = cached
                return
            }
            guard let bytes = await app.gallery.thumbnail(item.id), let decoded = await decodeImage(bytes, maxSide: GalleryThumbs.maxSide) else { return }
            GalleryThumbs.put(item.id, decoded)
            withAnimation(.inkReduced) { image = decoded }
        }
    }
}

private struct DetailView: View {
    let item: GalleryItem
    let onClose: () -> Void
    let onMessage: (String) -> Void
    /// Shows another item (the upscaled copy).
    let onOpen: (String) -> Void
    let onReuse: (Bool) -> Void
    @Environment(AppContainer.self) private var app
    @Environment(\.scheme) private var c
    @State private var image: UIImage?
    @State private var confirmDelete = false
    @State private var confirmExport = false
    @State private var upscaling: String?
    /// Tiles done / total while upscaling (nil until the first tile).
    @State private var upscaleTiles: (done: Int, total: Int)?
    /// The "saved" stamp after an export.
    @State private var saved = false
    private let upscaler = LocalUpscale()
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
                IconAction(symbol: "star.fill", label: "Favorite", tint: current.favorite ? c.inks.amber : c.onSurfaceVariant) {
                    app.gallery.setFavorite(item.id, !current.favorite)
                }
                .stamp(current.favorite) { $0 }
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
                    InkLoader(.block, caption: "decrypting")
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .clipped()
            .overlay(alignment: .topTrailing) {
                Group {
                    if saved { Tag("saved to photos", accent: c.primary, filled: true).padding(12) }
                }
                .stamp(saved) { $0 }
            }
            .task(id: saved) {
                guard saved else { return }
                try? await Task.sleep(for: .seconds(1.6))
                withAnimation(.inkReduced) { saved = false }
            }
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
                        Tag("→ img2img", accent: c.inks.uv) { onReuse(true) }
                        Tag("copy params", accent: c.onSurfaceVariant) {
                            UIPasteboard.general.setItems([[UTType.plainText.identifier: parametersText(item)]], options: [.localOnly: true])
                            onMessage("Parameters copied")
                        }
                        if upscaling != nil || GalleryUpscale.canUpscale(item) {
                            Tag("upscale 4×", accent: c.primary, busy: upscaling != nil) { upscale(current) }
                        }
                        Tag("export", accent: c.inks.amber) { confirmExport = true }
                        Tag("delete", accent: c.error) { confirmDelete = true }
                    }
                    if let upscaling {
                        InkProgressBar(upscaleTiles.map { .progress(Double($0.done) / Double(max($0.total, 1)), running: true) } ?? .indeterminate, height: 2)
                        MicroLabel(upscaling, color: c.primary, lines: 2)
                    }
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
            GalleryThumbs.remove(item.id)
            onMessage("Deleted")
            onClose()
        }
    }

    /// Writes a decrypted copy to the photo library (add-only access). This deliberately leaves
    /// the encrypted vault, so the UI asks first.
    /// Upscales 4× on the Neural Engine and opens the new gallery item.
    private func upscale(_ item: GalleryItem) {
        guard upscaling == nil else { return }
        upscaling = "upscaling 4× on the neural engine"
        upscaleTiles = nil
        Task {
            do {
                let copy = try await GalleryUpscale.run(item, app: app, upscale: upscaler) { done, total in
                    Task { @MainActor in
                        upscaling = "upscaling 4× on the neural engine · tile \(done)/\(total)"
                        upscaleTiles = (done, total)
                    }
                }
                upscaling = nil
                onMessage("Upscaled to \(copy.width)×\(copy.height)")
                onOpen(copy.id)
            } catch {
                upscaling = nil
                onMessage("Upscale failed: \(error.localizedDescription)")
            }
        }
    }

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
                saved = true
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

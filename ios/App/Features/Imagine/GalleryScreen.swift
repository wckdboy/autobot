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
    /// [openId] that opens the newest image (`-AutobotOpen viewer`).
    static let newest = "*newest"
    var openId: String? = nil
    @Environment(AppContainer.self) private var app
    @Environment(Navigator.self) private var nav
    @Environment(\.scheme) private var c
    @State private var favoritesOnly = false
    @State private var query = ""
    @State private var open: ImageViewerTarget?
    @State private var openedInitial = false
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
                ConsoleTextField(text: $query, placeholder: "Search images", kind: .mono)
                    .padding(.horizontal, Tokens.Spacing.gutter)
                    .padding(.bottom, 8)
            }
            .hairlineEdge()
            Group {
                if let message {
                    HStack {
                        MicroLabel(message, color: c.onSurface).frame(maxWidth: .infinity, alignment: .leading)
                        TextAction("OK") { self.message = nil }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .stamp(message) { $0 != nil }
            if items.isEmpty {
                EmptyState(all.isEmpty ? "Nothing generated yet. Images you make land here." : "No images match.")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 112), spacing: 4)], spacing: 4) {
                        ForEach(items) { item in
                            Thumb(item: item)
                                .onTapGesture { open = ImageViewerTarget(ids: items.map(\.id), start: item.id) }
                                .accessibilityAddTraits(.isButton)
                                .accessibilityHint("Opens the image full screen")
                                .transition(.inkDissolve)
                        }
                    }
                    .padding(8)
                    .animation(.inkDissolve, value: items.map(\.id))
                }
            }
        }
        .background(c.background)
        // Once, as soon as the item is there (the gallery can still be loading when this appears).
        .task(id: all.count) {
            guard !openedInitial, let openId else { return }
            let start = openId == Self.newest ? items.first?.id : openId
            guard let start, app.gallery.get(start) != nil else { return }
            openedInitial = true
            open = ImageViewerTarget(ids: items.map(\.id), start: start)
        }
        .imageViewer($open) { id, intent in nav.push(.imagine(handoffId: id, intent: intent)) }
    }
}

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


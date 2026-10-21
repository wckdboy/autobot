import AutobotShared
import Photos
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// What the full-screen viewer opens on: gallery images to page through and the one shown first.
struct ImageViewerTarget: Identifiable, Equatable {
    let ids: [String]
    let start: String
    var id: String { start }

    init(ids: [String], start: String) {
        self.ids = ids.contains(start) ? ids : [start] + ids
        self.start = start
    }
}

extension View {
    /// Presents [ImageViewer] full screen while [target] is set; [onHandoff] takes an image into Imagine.
    func imageViewer(_ target: Binding<ImageViewerTarget?>, onHandoff: @escaping (String, ImageHandoff) -> Void) -> some View {
        fullScreenCover(item: target) { t in
            ImageViewer(target: t, onClose: { target.wrappedValue = nil }) { id, intent in
                target.wrappedValue = nil
                onHandoff(id, intent)
            }
        }
    }
}

/**
 Generated images full screen: pinch and double-tap to zoom (up to past 1:1 pixels), pan, swipe
 sideways between images, swipe down to close, tap to hide the controls. The info sheet shows the
 real pixel size and every parameter; the action bar takes the image into Imagine (img2img, vary,
 inpaint, reuse settings, same seed), upscales it, shares, saves to Photos, deletes or favorites it.
 An unsaved incognito image is shown from memory: viewing it writes nothing; only Save (the user's
 explicit tap) puts it in the gallery, and the actions that would store a copy (favorite, upscale)
 wait for that.
 */
struct ImageViewer: View {
    let target: ImageViewerTarget
    let onClose: () -> Void
    let onHandoff: (String, ImageHandoff) -> Void
    @Environment(AppContainer.self) private var app
    @State private var ids: [String] = []
    @State private var current: String = ""
    @State private var chrome = true
    @State private var zoomed = false
    @State private var dismissProgress: CGFloat = 0
    @State private var showInfo = false
    @State private var confirmDelete = false
    @State private var confirmSave = false
    @State private var message: String?
    @State private var upscaling: String?
    /// The shown image's encoded bytes (share, save) and real pixel size.
    @State private var bytes: Data?
    @State private var pixels: (width: Int, height: Int)?
    private let upscaler = LocalUpscale()

    var body: some View {
        let item = app.gallery.get(current)
        ZStack {
            Color.black.opacity(1 - dismissProgress * 0.85).ignoresSafeArea()
            TabView(selection: $current) {
                ForEach(ids, id: \.self) { id in
                    ViewerPage(id: id, onTap: { withAnimation(.easeOut(duration: 0.2)) { chrome.toggle() } },
                               onZoom: { zoomed = $0 }, onDismissProgress: { dismissProgress = $0 }, onDismiss: onClose)
                        .tag(id)
                        .ignoresSafeArea()
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .ignoresSafeArea()
            .accessibilityElement(children: .contain)
            if chrome && dismissProgress == 0 {
                VStack(spacing: 0) {
                    topBar(item)
                    Spacer(minLength: 0)
                    if let message { messageLine(message) }
                    if let item { actionBar(item) }
                }
                .transition(.opacity)
            }
        }
        .environment(\.colorScheme, .dark)
        .statusBarHidden(!chrome)
        .onAppear {
            if ids.isEmpty {
                ids = target.ids.filter { app.gallery.get($0) != nil }
                current = ids.contains(target.start) ? target.start : ids.first ?? ""
            }
        }
        .task(id: current) {
            bytes = nil
            pixels = nil
            message = nil
            guard !current.isEmpty, let data = await app.gallery.image(current) else { return }
            bytes = data
            pixels = ImageSizing.pixelSize(data)
        }
        .task(id: message) {
            guard message != nil else { return }
            try? await Task.sleep(for: .seconds(2.4))
            withAnimation(.easeOut(duration: 0.2)) { message = nil }
        }
        .sheet(isPresented: $showInfo) {
            if let item { ImageInfoSheet(item: item, pixels: pixels) { message = $0 }.presentationDetents([.medium, .large]) }
        }
        .risoAlert($confirmSave, title: "Save unencrypted copy?",
                   message: "A plain image will be added to your photo library, where other apps and backups can read it.",
                   confirm: "Save", destructive: false) { save() }
        .risoAlert($confirmDelete, title: "Delete image?", message: "The encrypted file and its parameters are removed. This cannot be undone.",
                   confirm: "Delete") { delete() }
    }

    // MARK: Chrome

    private var unsaved: Bool { UnsavedImages.isUnsaved(current) }

    private func topBar(_ item: GalleryItem?) -> some View {
        ViewerTopBar(
            position: ids.firstIndex(of: current).map { "\($0 + 1) / \(ids.count)" } ?? "",
            favorite: item?.favorite ?? false,
            unsaved: unsaved,
            onClose: onClose,
            onInfo: { showInfo = true },
            onFavorite: { if let item { app.gallery.setFavorite(item.id, !item.favorite) } },
        )
    }

    private func messageLine(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .glassSurface(in: Capsule())
            .padding(.bottom, 8)
            .accessibilityAddTraits(.updatesFrequently)
    }

    private func actionBar(_ item: GalleryItem) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 2) {
                if unsaved {
                    ViewerAction(symbol: "tray.and.arrow.down", label: PanicCopy.shared.SAVE, hint: "Saves this incognito image to the encrypted gallery", tint: .yellow) { keep() }
                }
                ViewerAction(symbol: "photo.on.rectangle.angled", label: "img2img", hint: "Opens Imagine with this image as the input") { onHandoff(item.id, .img2img) }
                ViewerAction(symbol: "sparkles", label: "Vary", hint: "Opens Imagine for a close variation of this image") { onHandoff(item.id, .vary) }
                ViewerAction(symbol: "paintbrush.pointed", label: "Inpaint", hint: "Opens Imagine to repaint part of this image") { onHandoff(item.id, .inpaint) }
                ViewerAction(symbol: "slider.horizontal.3", label: "Reuse", hint: "Opens Imagine with this image's settings and a new seed") { onHandoff(item.id, .reuse) }
                ViewerAction(symbol: "number", label: "Same seed", hint: "Opens Imagine with this image's settings and seed") { onHandoff(item.id, .sameSeed) }
                if !unsaved, upscaling != nil || (GalleryUpscale.upscaler(app) != nil && GalleryUpscale.canUpscale(item)) {
                    ViewerAction(symbol: "arrow.up.left.and.arrow.down.right", label: upscaling == nil ? "Upscale 4×" : "Upscaling", busy: upscaling != nil) { upscale(item) }
                }
                if !unsaved, let bytes, let image = UIImage(data: bytes) {
                    ShareLink(item: SharedImage(data: bytes), preview: SharePreview(String(item.prompt.prefix(60)), image: Image(uiImage: image))) {
                        ViewerActionLabel(symbol: "square.and.arrow.up", label: "Share")
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Share")
                }
                if !unsaved {
                    ViewerAction(symbol: "square.and.arrow.down", label: "Save", hint: "Saves an unencrypted copy to Photos", enabled: bytes != nil) { confirmSave = true }
                }
                ViewerAction(symbol: "doc.on.doc", label: "Copy", hint: "Copies the prompt and parameters") {
                    UIPasteboard.general.setItems([[UTType.plainText.identifier: GalleryExport.parametersText(item, pixels: pixels)]], options: [.localOnly: true])
                    message = "Parameters copied"
                }
                if !unsaved { ViewerAction(symbol: "trash", label: "Delete", tint: .red) { confirmDelete = true } }
            }
            .padding(.horizontal, 8)
        }
        .padding(.vertical, 6)
        .glassSurface(in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    // MARK: Actions

    private func delete() {
        let id = current
        let index = ids.firstIndex(of: id) ?? 0
        app.gallery.delete(id)
        GalleryThumbs.remove(id)
        ids.removeAll { $0 == id }
        if ids.isEmpty { onClose(); return }
        current = ids[min(index, ids.count - 1)]
        message = "Deleted"
    }

    /// Save on an incognito image: into the gallery, shown under its new id from then on.
    private func keep() {
        let id = current
        Task {
            guard let saved = try? await app.gallery.keep(id) else { message = "Couldn't save the image"; return }
            ids = ids.map { $0 == id ? saved : $0 }
            if current == id { current = saved }
            message = "Saved to the gallery"
        }
    }

    private func save() {
        guard let bytes else { return }
        Task { message = await GalleryExport.toPhotos(bytes) }
    }

    private func upscale(_ item: GalleryItem) {
        guard upscaling == nil else { return }
        upscaling = "Upscaling 4×"
        Task {
            do {
                let copy = try await GalleryUpscale.run(item, app: app, upscale: upscaler) { done, total in
                    Task { @MainActor in upscaling = "Upscaling 4× · tile \(done)/\(total)"; message = upscaling }
                }
                upscaling = nil
                let index = (ids.firstIndex(of: item.id) ?? -1) + 1
                ids.insert(copy.id, at: min(index, ids.count))
                current = copy.id
                message = "Upscaled to \(copy.width)×\(copy.height)"
            } catch {
                upscaling = nil
                message = "Upscale failed: \(error.localizedDescription)"
            }
        }
    }
}

/// Encoded image bytes for the share sheet (PNG or JPEG as stored).
struct SharedImage: Transferable {
    let data: Data

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(exportedContentType: .png) { $0.data }
            .suggestedFileName("autobot.png")
    }
}

/// Writing a decrypted copy to the photo library (add-only access).
enum GalleryExport {
    /// Saves [bytes]; returns the line to show.
    @MainActor
    static func toPhotos(_ bytes: Data) async -> String {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { return "Photo library access was not granted" }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                PHAssetCreationRequest.forAsset().addResource(with: .photo, data: bytes, options: nil)
            }
            return "Saved to Photos"
        } catch {
            return "Saving failed"
        }
    }

    /// A1111-style "parameters" text, for pasting into other tools.
    static func parametersText(_ item: GalleryItem, pixels: (width: Int, height: Int)? = nil) -> String {
        var s = item.prompt
        if !item.negativePrompt.isEmpty { s += "\nNegative prompt: \(item.negativePrompt)" }
        let size = pixels ?? (item.width, item.height)
        s += "\nSteps: \(item.steps), Sampler: \(item.sampler), CFG scale: \(item.cfgScale), Seed: \(item.seed), Size: \(size.width)x\(size.height)"
        if let model = item.model { s += ", Model: \(model)" }
        return s
    }
}

// MARK: - Pieces

private struct ViewerTopBar: View {
    let position: String
    let favorite: Bool
    var unsaved = false
    let onClose: () -> Void
    let onInfo: () -> Void
    let onFavorite: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            ViewerRoundButton(symbol: "xmark", label: "Close", action: onClose)
            Text(position)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .accessibilityLabel(position.replacingOccurrences(of: " / ", with: " of "))
            ViewerRoundButton(symbol: "info", label: "Image info", action: onInfo)
            if unsaved {
                Text(PanicCopy.shared.NOT_SAVED.sentenceCased)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.yellow)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .glassSurface(in: Capsule())
            } else {
                ViewerRoundButton(symbol: favorite ? "star.fill" : "star", label: favorite ? "Remove from favorites" : "Add to favorites",
                                  tint: favorite ? .yellow : .white, action: onFavorite)
                    .symbolEffect(.bounce, value: favorite)
                    .accessibilityAddTraits(favorite ? .isSelected : [])
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 4)
    }
}

/// A round glass button over the image.
private struct ViewerRoundButton: View {
    let symbol: String
    let label: String
    var tint: Color = .white
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.body.weight(.semibold))
                .foregroundStyle(tint)
                .frame(width: 24, height: 24)
        }
        .glassButton()
        .buttonBorderShape(.circle)
        .accessibilityLabel(label)
    }
}

private struct ViewerActionLabel: View {
    let symbol: String
    let label: String
    var tint: Color = .white
    var busy = false

    var body: some View {
        VStack(spacing: 4) {
            Group {
                if busy {
                    ProgressView().tint(tint)
                } else {
                    Image(systemName: symbol).font(.title3)
                }
            }
            .frame(height: 24)
            Text(label.sentenceCased).font(.caption2).lineLimit(1)
        }
        .foregroundStyle(tint)
        .frame(minWidth: 64, minHeight: 52)
        .padding(.horizontal, 2)
        .contentShape(Rectangle())
    }
}

private struct ViewerAction: View {
    let symbol: String
    let label: String
    var hint: String? = nil
    var tint: Color = .white
    var enabled = true
    var busy = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ViewerActionLabel(symbol: symbol, label: label, tint: enabled ? tint : tint.opacity(0.38), busy: busy)
        }
        .buttonStyle(.plain)
        .disabled(!enabled || busy)
        .accessibilityLabel(label)
        .accessibilityHint(hint ?? "")
    }
}

/// One page: the image decoded at up to 4096 px in a zooming scroll view.
private struct ViewerPage: View {
    let id: String
    let onTap: () -> Void
    let onZoom: (Bool) -> Void
    let onDismissProgress: (CGFloat) -> Void
    let onDismiss: () -> Void
    @Environment(AppContainer.self) private var app
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                ZoomableImage(image: image, onTap: onTap, onZoom: onZoom, onDismissProgress: onDismissProgress, onDismiss: onDismiss)
                    .accessibilityElement()
                    .accessibilityLabel(app.gallery.get(id).map { "Image: \($0.prompt.prefix(120))" } ?? "Image")
                    .accessibilityHint("Pinch or double-tap to zoom, swipe down to close")
                    .accessibilityAddTraits(.isImage)
            } else {
                ProgressView("Decrypting").tint(.white).foregroundStyle(.white)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task(id: id) {
            if let thumb = GalleryThumbs.get(id), image == nil { image = thumb }
            guard let bytes = await app.gallery.image(id), let full = await decodeImage(bytes, maxSide: 4096) else { return }
            image = full
        }
    }
}

/**
 UIKit zoom: a scroll view around the image (pinch, pan, rubber-band), double tap toggles between
 fit and 3× at the tapped point, and at fit size a vertical drag pulls the image away to close.
 */
struct ZoomableImage: UIViewRepresentable {
    let image: UIImage
    var onTap: () -> Void = {}
    var onZoom: (Bool) -> Void = { _ in }
    var onDismissProgress: (CGFloat) -> Void = { _ in }
    var onDismiss: () -> Void = {}

    func makeUIView(context: UIViewRepresentableContext<ZoomableImage>) -> ZoomingImageView { ZoomingImageView() }

    func updateUIView(_ view: ZoomingImageView, context: UIViewRepresentableContext<ZoomableImage>) {
        view.onTap = onTap
        view.onZoom = onZoom
        view.onDismissProgress = onDismissProgress
        view.onDismiss = onDismiss
        if view.image !== image { view.image = image }
    }
}

final class ZoomingImageView: UIScrollView, UIScrollViewDelegate, UIGestureRecognizerDelegate {
    let imageView = UIImageView()
    var onTap: () -> Void = {}
    var onZoom: (Bool) -> Void = { _ in }
    var onDismissProgress: (CGFloat) -> Void = { _ in }
    var onDismiss: () -> Void = {}
    private var laidOutFor: CGSize = .zero
    private var wasZoomed = false
    private lazy var dismissPan = UIPanGestureRecognizer(target: self, action: #selector(pulled(_:)))

    var image: UIImage? {
        didSet {
            imageView.image = image
            // A sharper decode of the same picture (thumbnail → full) keeps the zoom; another starts at fit.
            if let old = oldValue, let new = image, ImageSizing.sameAspect(old.size.pair, new.size.pair), laidOutFor != .zero {
                updateMaximumZoom()
            } else {
                laidOutFor = .zero
                setNeedsLayout()
            }
        }
    }

    init() {
        super.init(frame: .zero)
        delegate = self
        showsHorizontalScrollIndicator = false
        showsVerticalScrollIndicator = false
        contentInsetAdjustmentBehavior = .never
        decelerationRate = .fast
        bouncesZoom = true
        alwaysBounceVertical = false
        backgroundColor = .clear
        imageView.contentMode = .scaleAspectFit
        imageView.isAccessibilityElement = false
        addSubview(imageView)
        let double = UITapGestureRecognizer(target: self, action: #selector(doubleTapped(_:)))
        double.numberOfTapsRequired = 2
        addGestureRecognizer(double)
        let single = UITapGestureRecognizer(target: self, action: #selector(tapped))
        single.require(toFail: double)
        addGestureRecognizer(single)
        dismissPan.delegate = self
        addGestureRecognizer(dismissPan)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        if bounds.size != laidOutFor, bounds.width > 0 { fit() }
        centre()
    }

    /// The image fitted to the view at zoom 1; the maximum zoom passes 1:1 pixels.
    private func fit() {
        guard let image, image.size.width > 0, image.size.height > 0 else { return }
        laidOutFor = bounds.size
        minimumZoomScale = 1
        zoomScale = 1
        let fit = min(bounds.width / image.size.width, bounds.height / image.size.height)
        let size = CGSize(width: image.size.width * fit, height: image.size.height * fit)
        imageView.frame = CGRect(origin: .zero, size: size)
        contentSize = size
        updateMaximumZoom()
        centre()
    }

    /// At least 4×, and twice the zoom at which one image pixel is one screen pixel.
    private func updateMaximumZoom() {
        guard let image, laidOutFor.width > 0 else { return }
        let fitWidth = imageView.frame.width / max(zoomScale, 0.01)
        let pixelsPerPoint = image.size.width * image.scale / max(fitWidth, 1) / max(traitCollection.displayScale, 1)
        maximumZoomScale = max(4, pixelsPerPoint * 2)
    }

    private func centre() {
        let dx = max(0, (bounds.width - imageView.frame.width) / 2)
        let dy = max(0, (bounds.height - imageView.frame.height) / 2)
        contentInset = UIEdgeInsets(top: dy, left: dx, bottom: dy, right: dx)
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }

    func scrollViewDidZoom(_ scrollView: UIScrollView) {
        centre()
        let zoomed = zoomScale > minimumZoomScale + 0.01
        if zoomed != wasZoomed {
            wasZoomed = zoomed
            onZoom(zoomed)
        }
    }

    @objc private func tapped() { onTap() }

    @objc private func doubleTapped(_ g: UITapGestureRecognizer) {
        if zoomScale > minimumZoomScale + 0.01 {
            setZoomScale(minimumZoomScale, animated: true)
        } else {
            let scale = min(3, maximumZoomScale)
            let p = g.location(in: imageView)
            let size = CGSize(width: bounds.width / scale, height: bounds.height / scale)
            zoom(to: CGRect(x: p.x - size.width / 2, y: p.y - size.height / 2, width: size.width, height: size.height), animated: true)
        }
    }

    // MARK: Swipe down to close

    override func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        guard g === dismissPan else { return super.gestureRecognizerShouldBegin(g) }
        guard zoomScale <= minimumZoomScale + 0.01 else { return false }
        let v = dismissPan.velocity(in: self)
        return abs(v.y) > abs(v.x) * 1.2
    }

    @objc private func pulled(_ g: UIPanGestureRecognizer) {
        let t = g.translation(in: self)
        switch g.state {
        case .changed:
            let progress = min(1, abs(t.y) / 320)
            imageView.transform = CGAffineTransform(translationX: t.x * 0.3, y: t.y).scaledBy(x: 1 - progress * 0.2, y: 1 - progress * 0.2)
            onDismissProgress(progress)
        case .ended, .cancelled, .failed:
            let v = g.velocity(in: self)
            if g.state == .ended && (abs(t.y) > 120 || abs(v.y) > 900) {
                onDismiss()
            } else {
                UIView.animate(withDuration: 0.22, delay: 0, options: [.curveEaseOut]) { self.imageView.transform = .identity }
                onDismissProgress(0)
            }
        default:
            break
        }
    }
}

private extension CGSize {
    var pair: (Int, Int) { (Int(width), Int(height)) }
}

// MARK: - Info

/// Everything about one image: the prompt, the real pixel size and the parameters.
struct ImageInfoSheet: View {
    let item: GalleryItem
    let pixels: (width: Int, height: Int)?
    let onMessage: (String) -> Void

    var body: some View {
        let params = ImageGenerator.decodeParams(item)
        let size = pixels ?? (item.width, item.height)
        NavigationStack {
            Form {
                Section("Prompt") {
                    Text(item.prompt.isEmpty ? "—" : item.prompt).font(.callout.monospaced()).textSelection(.enabled)
                }
                if !item.negativePrompt.isEmpty {
                    Section("Negative") {
                        Text(item.negativePrompt).font(.callout.monospaced()).foregroundStyle(.secondary).textSelection(.enabled)
                    }
                }
                Section("Parameters") {
                    LabeledContent("Size", value: "\(size.width)×\(size.height) px")
                    LabeledContent("Seed") { Text("\(item.seed)").monospacedDigit().textSelection(.enabled) }
                    LabeledContent("Steps", value: "\(item.steps)")
                    LabeledContent("CFG", value: String(format: "%.1f", item.cfgScale))
                    LabeledContent("Sampler", value: item.sampler)
                    LabeledContent("Mode", value: item.mode.lowercased())
                    if let p = params, p.mode != .txt2img { LabeledContent("Strength", value: String(format: "%.2f", p.denoiseStrength)) }
                    LabeledContent("Engine", value: item.engine)
                    LabeledContent("Time", value: String(format: "%.1f s", Double(item.durationMs) / 1000))
                    if let model = item.model {
                        LabeledContent("Model") { Text(model).font(.callout.monospaced()).textSelection(.enabled) }
                    }
                    if let loras = params?.loras, !loras.isEmpty {
                        LabeledContent("LoRA", value: loras.map { "\($0.name) \(String(format: "%.2f", $0.weight))" }.joined(separator: ", "))
                    }
                }
                Section {
                    Button {
                        UIPasteboard.general.setItems([[UTType.plainText.identifier: GalleryExport.parametersText(item, pixels: pixels)]], options: [.localOnly: true])
                        onMessage("Parameters copied")
                    } label: {
                        Label("Copy parameters", systemImage: "doc.on.doc")
                    }
                }
            }
            .navigationTitle("Image · \(relativeTime(item.createdAt))")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

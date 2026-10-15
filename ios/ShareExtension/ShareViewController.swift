import ImageIO
import UIKit
import UniformTypeIdentifiers

/**
 "Autobot" in the share sheet: text, a link or up to four images. The content is written to the
 App Group inbox ([SharedInbox]: complete file protection, deleted on pickup) and the app opens a
 new chat with it as a draft. Images are downscaled and re-encoded here, which drops EXIF and GPS
 metadata before anything touches the disk. Nothing is sent anywhere.
 */
@objc(ShareViewController)
final class ShareViewController: UIViewController {
    private var started = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 0.055, green: 0.055, blue: 0.055, alpha: 1)
        let label = UILabel()
        label.text = "Opening Autobot…"
        label.textColor = UIColor(red: 0.957, green: 0.933, blue: 0.902, alpha: 1)
        label.font = .monospacedSystemFont(ofSize: 15, weight: .regular)
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !started else { return }
        started = true
        Task { await handOff() }
    }

    private func handOff() async {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        var texts: [String] = []
        var images: [Data] = []
        for provider in providers {
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                if images.count < SharedInbox.maxImages, let data = await Self.data(provider, .image), let jpeg = Self.downscaled(data) {
                    images.append(jpeg)
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                if let url = await Self.load(provider, URL.self), !url.isFileURL { texts.append(url.absoluteString) }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                if let text = await Self.load(provider, String.self) { texts.append(text) }
            }
        }
        let text = texts.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }.joined(separator: "\n")
        let item = SharedInbox.Item(text: text.isEmpty ? nil : text, images: images)
        if item.text != nil || !item.images.isEmpty, let root = SharedInbox.defaultRoot, let id = try? SharedInbox.write(item, root: root) {
            openApp(URL(string: "autobot://share?id=\(id)")!)
        }
        extensionContext?.completeRequest(returningItems: nil)
    }

    /**
     A share extension cannot open URLs itself; the application in its responder chain can. Its
     `open(_:options:completionHandler:)` is marked unavailable to extensions at compile time, so it
     is called through the Objective-C runtime.
     */
    private func openApp(_ url: URL) {
        typealias Open = @convention(c) (AnyObject, Selector, NSURL, NSDictionary, AnyObject?) -> Void
        let selector = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self
        while let current = responder {
            if current is UIApplication, let method = class_getInstanceMethod(type(of: current), selector) {
                unsafeBitCast(method_getImplementation(method), to: Open.self)(current, selector, url as NSURL, NSDictionary(), nil)
                return
            }
            responder = current.next
        }
    }

    private static func load(_ provider: NSItemProvider, _ type: URL.Type) async -> URL? {
        await withCheckedContinuation { continuation in
            _ = provider.loadObject(ofClass: URL.self) { value, _ in continuation.resume(returning: value) }
        }
    }

    private static func load(_ provider: NSItemProvider, _ type: String.Type) async -> String? {
        await withCheckedContinuation { continuation in
            _ = provider.loadObject(ofClass: String.self) { value, _ in continuation.resume(returning: value) }
        }
    }

    private static func data(_ provider: NSItemProvider, _ type: UTType) async -> Data? {
        await withCheckedContinuation { continuation in
            _ = provider.loadDataRepresentation(for: type) { data, _ in continuation.resume(returning: data) }
        }
    }

    /// At most 1344 px on the longest side, upright, JPEG without metadata.
    static func downscaled(_ data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 1344,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.88] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? out as Data : nil
    }
}

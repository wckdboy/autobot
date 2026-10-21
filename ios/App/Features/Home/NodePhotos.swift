import AutobotShared
import ImageIO
import SwiftUI
import UIKit

/// Photos for the node's agent (as Android's `NodePhotos`): downscaled and re-encoded on the phone.
enum NodePhotos {
    /**
     Decodes [data] (any format iOS reads, HEIC too) straight to at most `NodeImages.MAX_SIDE`
     (1568 px) on its longest side, upright, and re-encodes it as JPEG: the new file carries no
     EXIF, so the photo's location and camera data never leave the phone.
     */
    static func encode(_ data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(NodeImages.shared.MAX_SIDE),
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary), image.width > 0, image.height > 0 else { return nil }
        return ImageCodec.jpeg(image, quality: Double(NodeImages.shared.QUALITY) / 100)
    }
}

/// A photo's thumbnail: square, cropped, on a hairline.
struct PhotoThumb: View {
    let data: Data
    let side: CGFloat

    var body: some View {
        if let image = UIImage(data: data)?.preparingThumbnail(of: CGSize(width: side * 3, height: side * 3)) ?? UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: side, height: side)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color(uiColor: .separator)))
        }
    }
}

/// The system camera, for one photo; [done] gets its JPEG, or nil when cancelled.
struct CameraPicker: UIViewControllerRepresentable {
    let done: (Data?) -> Void

    func makeUIViewController(context: Self.Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Self.Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(done: done) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (Data?) -> Void
        init(done: @escaping (Data?) -> Void) { self.done = done }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            done((info[.originalImage] as? UIImage)?.jpegData(compressionQuality: 0.9))
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { done(nil) }
    }
}

/// The demo node's chat (`-AutobotDemo`): `NodeChatSample`, with a drawn photo, as Android shows it.
enum NodeChatDemo {
    static func rows(photo: Data, next: () -> Int) -> [NodeAgentRow] {
        let s = NodeChatSample.shared
        return [
            .user(id: next(), text: s.QUESTION, photos: [photo], count: 1),
            .note(id: next(), text: "read_file notes/lab.md", tone: .tool),
            .reply(id: next(), text: s.ANSWER, route: s.ROUTE),
            .kept(id: next(), text: NodeBotDetails.shared.kept(text: s.KEPT)),
            .user(id: next(), text: s.FOLLOW_UP),
            .reply(id: next(), text: s.FOLLOW_ANSWER, route: s.ROUTE),
        ]
    }

    /// A shelf, roughly: a warm gradient, three boards and a dark band (no real photo in the app).
    static func photo(width: CGFloat = 960, height: CGFloat = 720) -> Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: {
            let f = UIGraphicsImageRendererFormat()
            f.scale = 1
            return f
        }())
        let image = renderer.image { ctx in
            let g = ctx.cgContext
            let colors = [UIColor(red: 196 / 255, green: 160 / 255, blue: 120 / 255, alpha: 1).cgColor, UIColor(red: 70 / 255, green: 58 / 255, blue: 50 / 255, alpha: 1).cgColor] as CFArray
            if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1]) {
                g.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: width, y: height), options: [])
            }
            UIColor(red: 48 / 255, green: 40 / 255, blue: 34 / 255, alpha: 1).setFill()
            g.fill(CGRect(x: 0, y: height * 0.70, width: width, height: height * 0.06))
            UIColor(red: 46 / 255, green: 110 / 255, blue: 74 / 255, alpha: 1).setFill()
            for i in 0..<3 {
                g.fill(CGRect(x: width * (0.14 + CGFloat(i) * 0.26), y: height * 0.40, width: width * 0.18, height: height * 0.30))
            }
        }
        return image.jpegData(compressionQuality: 0.85) ?? Data()
    }
}

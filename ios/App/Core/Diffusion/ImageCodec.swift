import Foundation
import ImageIO
import UIKit
import UniformTypeIdentifiers

/// Image helpers for the studio and the gallery (port of `ImageCodec.kt`).
enum ImageCodec {
    static let thumbnailSide: CGFloat = 256

    /// Decodes [data], downscaled so its long side is at most [maxSide] pixels.
    static func decode(_ data: Data, maxSide: CGFloat? = nil) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        if let maxSide {
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxSide,
            ]
            if let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) { return UIImage(cgImage: cg) }
        }
        guard let cg = CGImageSourceCreateImageAtIndex(source, 0, nil) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// PNG bytes (no metadata) of [image].
    static func png(_ image: CGImage) -> Data? {
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, nil)
        return CGImageDestinationFinalize(dest) ? data as Data : nil
    }

    /// JPEG bytes (no EXIF/GPS: the pixels are re-encoded) of [image].
    static func jpeg(_ image: CGImage, quality: Double = 0.88) -> Data? {
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(dest) ? data as Data : nil
    }

    /// A small JPEG thumbnail (long side ≤ 256) for grids and cards.
    static func thumbnail(_ data: Data) -> Data? {
        guard let image = decode(data, maxSide: thumbnailSide)?.cgImage else { return nil }
        return jpeg(image, quality: 0.85)
    }

    /// Scales [image] to cover [width]×[height] and crops the centre (img2img inputs).
    static func cover(_ image: CGImage, width: Int, height: Int) -> CGImage? {
        guard let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue,
        ) else { return nil }
        let scale = max(CGFloat(width) / CGFloat(image.width), CGFloat(height) / CGFloat(image.height))
        let w = CGFloat(image.width) * scale, h = CGFloat(image.height) * scale
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: (CGFloat(width) - w) / 2, y: (CGFloat(height) - h) / 2, width: w, height: h))
        return context.makeImage()
    }

    /// Normalises orientation (drawn upright, no metadata).
    static func upright(_ image: UIImage, maxSide: CGFloat) -> CGImage? {
        let scale = min(1, maxSide / max(image.size.width, image.size.height))
        let size = CGSize(width: max(1, (image.size.width * scale).rounded()), height: max(1, (image.size.height * scale).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }.cgImage
    }

    /// Whether [data] is a PNG.
    static func isPNG(_ data: Data) -> Bool { data.count > 8 && data[data.startIndex + 1] == 0x50 && data[data.startIndex + 2] == 0x4E }
}

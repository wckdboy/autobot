import Foundation
import ImageIO
import PDFKit
import UniformTypeIdentifiers
import Vision

/// Why a picked file was not added.
struct VaultSkip: Error, Sendable {
    let message: String
}

/**
 Reads files the user picked into text for the vault: plain text and Markdown as UTF-8, PDFs
 page by page with PDFKit, photos and scans with Vision's on-device text recognition. Only the
 extracted text is kept (encrypted); the file itself is neither copied nor uploaded.
 */
enum VaultImport {
    static let maxTextBytes: Int64 = 20_000_000

    /// What the document picker offers.
    static let contentTypes: [UTType] = [.plainText, .text, UTType(filenameExtension: "md") ?? .plainText, .pdf, .image]

    /// `VaultFileType` name for a file, or nil when it can't be read.
    static func type(of url: URL) -> String? {
        let ext = url.pathExtension.lowercased()
        if ["md", "markdown"].contains(ext) { return "MARKDOWN" }
        guard let type = UTType(filenameExtension: ext) else { return nil }
        if type.conforms(to: .pdf) { return "PDF" }
        if type.conforms(to: .image) { return "IMAGE" }
        if type.conforms(to: .text) || ["txt", "csv", "log", "json", "org", "rst"].contains(ext) { return "TEXT" }
        return nil
    }

    static func read(_ url: URL) async -> Result<VaultFile, VaultSkip> {
        await Task.detached(priority: .userInitiated) { readNow(url) }.value
    }

    private static func readNow(_ url: URL) -> Result<VaultFile, VaultSkip> {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let name = url.lastPathComponent
        guard let type = type(of: url) else { return .failure(VaultSkip(message: "unsupported file type")) }
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
        switch type {
        case "PDF":
            guard let pdf = PDFDocument(url: url) else { return .failure(VaultSkip(message: "cannot be opened")) }
            let pages = (0 ..< pdf.pageCount).map { pdf.page(at: $0)?.string ?? "" }
            if pages.allSatisfy({ $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) {
                return .failure(VaultSkip(message: "no text layer (a scanned PDF: add the pages as photos)"))
            }
            return .success(VaultFile(name: name, type: type, size: size, pages: pages))
        case "IMAGE":
            switch recognize(url) {
            case .success(let text) where !text.isEmpty: return .success(VaultFile(name: name, type: type, size: size, pages: [text]))
            case .success: return .failure(VaultSkip(message: "no text found in the photo"))
            case .failure(let e): return .failure(e)
            }
        default:
            if size > maxTextBytes { return .failure(VaultSkip(message: "larger than \(maxTextBytes / 1_000_000) MB")) }
            guard let data = try? Data(contentsOf: url) else { return .failure(VaultSkip(message: "cannot be read")) }
            return .success(VaultFile(name: name, type: type, size: Int64(data.count), pages: [String(decoding: data, as: UTF8.self)]))
        }
    }

    /// Text in a photo, read on this device (Vision, accurate mode), in reading order.
    static func recognize(_ url: URL) -> Result<String, VaultSkip> {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
        else { return .failure(VaultSkip(message: "cannot be decoded")) }
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.automaticallyDetectsLanguage = true
        do {
            try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        } catch {
            return .failure(VaultSkip(message: "text recognition failed"))
        }
        let lines = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
        return .success(lines.joined(separator: "\n"))
    }
}

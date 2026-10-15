import Foundation
import Synchronization

/**
 What a paused background transfer leaves behind: the system's resume data (the bytes received
 so far stay in the background session's own temporary file) and the request it belongs to, so
 the next transfer of the same part file continues from there instead of starting over.

 Saved next to the part file (`*.part.resume`), so it survives a relaunch and goes away with the
 model (remove, panic wipe). Resume data embeds its request: when that request carries a hub
 credential it is kept in process memory only.
 */
struct TransferResume: Codable, Equatable, Sendable {
    /// The request URL the resume data replays (hub CDN URLs are re-signed on every resolution,
    /// so [matches] compares the resource, not the signature).
    let url: String
    let range: String?
    /// Body bytes the system already received.
    let bytes: Int64
    let data: Data

    private static let memory = Mutex<[String: TransferResume]>([:])

    init(url: String, range: String?, bytes: Int64, data: Data) {
        self.url = url
        self.range = range
        self.bytes = bytes
        self.data = data
    }

    /// Resume data of a transfer of [url] from [range]; nil when the system produced none.
    init?(resumeData: Data?, url: URL?, range: String?) {
        guard let resumeData, !resumeData.isEmpty, let url else { return nil }
        self.init(url: url.absoluteString, range: range, bytes: Self.receivedBytes(resumeData), data: resumeData)
    }

    /// Whether this continues [request]: the same resource (scheme, host, port, path) and range.
    func matches(_ request: URLRequest) -> Bool {
        guard let mine = Self.resource(URL(string: url)) else { return false }
        return mine == Self.resource(request.url) && range == request.value(forHTTPHeaderField: "Range")
    }

    /// Whether a transfer started with [earlier] continues [request] (see [matches]).
    static func continues(_ earlier: URLRequest?, _ request: URLRequest) -> Bool {
        guard let earlier, let mine = resource(earlier.url) else { return false }
        return mine == resource(request.url) && earlier.value(forHTTPHeaderField: "Range") == request.value(forHTTPHeaderField: "Range")
    }

    /// Where the resume data of [part] is kept.
    static func file(for part: URL) -> URL { part.appendingPathExtension("resume") }

    /// A path that stays the same across launches (the app container may move between them).
    static func tag(_ url: URL) -> String {
        let home = NSHomeDirectory()
        return url.path.hasPrefix(home) ? String(url.path.dropFirst(home.count)) : url.path
    }

    /// The file a [tag] names in this launch.
    static func path(_ tag: String) -> URL {
        tag.hasPrefix(NSHomeDirectory()) ? URL(fileURLWithPath: tag) : URL(fileURLWithPath: NSHomeDirectory() + tag)
    }

    static func resource(_ url: URL?) -> String? {
        guard let url, let scheme = url.scheme?.lowercased(), let host = url.host()?.lowercased() else { return nil }
        return "\(scheme)://\(host):\(url.port ?? (scheme == "https" ? 443 : 80))\(url.path())"
    }

    /// Keeps [resume] for [file]; on disk only when its request carries no credential.
    static func save(_ resume: TransferResume, at file: URL, persist: Bool) {
        memory.withLock { $0[tag(file)] = resume }
        guard persist, let data = try? JSONEncoder.store.encode(resume) else {
            try? FileManager.default.removeItem(at: file)
            return
        }
        // Only into an existing model directory (a removed model stays removed).
        guard FileManager.default.fileExists(atPath: file.deletingLastPathComponent().path) else { return }
        try? data.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    /// Keeps the system's resume [data] (if any) of a transfer of [url] from [range] for [file].
    static func keep(_ data: Data?, url: URL?, range: String?, at file: URL, persist: Bool) {
        guard let resume = TransferResume(resumeData: data, url: url, range: range) else { return }
        save(resume, at: file, persist: persist)
    }

    /// Removes the resume data kept for [file] and returns it when it continues [request].
    static func take(_ file: URL, matching request: URLRequest) -> TransferResume? {
        let kept = memory.withLock { $0.removeValue(forKey: tag(file)) } ?? load(file)
        try? FileManager.default.removeItem(at: file)
        return kept.flatMap { $0.matches(request) ? $0 : nil }
    }

    static func discard(_ file: URL) {
        _ = memory.withLock { $0.removeValue(forKey: tag(file)) }
        try? FileManager.default.removeItem(at: file)
    }

    /// Bytes held by resume data under [dir] (received, not yet in a part file).
    static func bytes(under dir: URL) -> Int64 {
        let prefix = tag(dir).hasSuffix("/") ? tag(dir) : tag(dir) + "/"
        var found: [String: Int64] = [:]
        if let walker = FileManager.default.enumerator(at: dir, includingPropertiesForKeys: nil) {
            for case let url as URL in walker where url.pathExtension == "resume" {
                if let r = load(url) { found[tag(url)] = r.bytes }
            }
        }
        memory.withLock { m in for (key, r) in m where key.hasPrefix(prefix) { found[key] = r.bytes } }
        return found.values.reduce(0, +)
    }

    /// Drops what is kept in memory for files under [dir] (a removed model).
    static func discard(under dir: URL) {
        let prefix = tag(dir) + "/"
        memory.withLock { m in m = m.filter { !$0.key.hasPrefix(prefix) } }
    }

    /// Drops everything kept in memory (panic wipe; the files go with the models directory).
    static func forgetAll() { memory.withLock { $0.removeAll() } }

    private static func load(_ file: URL) -> TransferResume? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        return try? JSONDecoder.store.decode(TransferResume.self, from: data)
    }

    /// `NSURLSessionResumeBytesReceived` of the system's resume data (0 when it has none).
    static func receivedBytes(_ data: Data) -> Int64 {
        let plist = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any]
        return (plist?["NSURLSessionResumeBytesReceived"] as? NSNumber)?.int64Value ?? 0
    }
}

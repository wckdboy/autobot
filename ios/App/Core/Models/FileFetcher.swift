import CryptoKit
import Foundation
import Synchronization

/// A failure the user can fix (or retry); the model is left paused / failed with this message.
struct DownloadError: LocalizedError, Equatable, Sendable {
    let message: String
    var retryable = true
    var errorDescription: String? { message }
}

/// Moves the body of an already admitted request somewhere other than the policy client's own
/// session (the background URLSession). Returns a temporary file with the body.
protocol BulkTransfer: Sendable {
    func download(_ request: URLRequest, proxy: Proxy, progress: @escaping @Sendable (Int64) -> Void) async throws -> (URL, HTTPURLResponse)
}

/**
 Transfers one file (port of `FileFetcher.kt`): resolves redirects hop by hop (each hop passes
 the kill switch and is audited; credentials are only sent to their own hub), appends to
 `*.part` with `Range` resume, verifies SHA-256 (known up front, or the Hub's `X-Linked-ETag`)
 and atomically renames to the target.

 Bodies stream through the policy client by default. With a [BulkTransfer] (the background
 session) the headers of the final hop are read through the policy client, then the body is
 handed to the transfer with the same scrubbed request and route.
 */
struct FileFetcher: Sendable {
    static let maxHops = 6
    private static let chunk = 256 * 1024

    let clients: HttpClientFactory
    var bulk: BulkTransfer? = nil

    /// Downloads [file] to [target]; returns it with its verified size and SHA-256.
    func fetch(_ file: ModelFile, to target: URL, credential: Secret?, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> ModelFile {
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        if file.zipEntry != nil { throw DownloadError(message: "Zip packages are Android-only", retryable: false) }
        let part = target.appendingPathExtension("part")
        var expectedSha = file.sha256?.lowercased()
        var expectedSize = file.sizeBytes
        guard var url = URL(string: file.url) else { throw DownloadError(message: "Invalid URL", retryable: false) }
        var hops = 0
        while true {
            try Task.checkCancellation()
            hops += 1
            if hops > Self.maxHops { throw DownloadError(message: "Too many redirects") }
            let have = Self.size(part)
            let request = Self.request(url, offset: have, credential: credential, auth: file.auth)
            let client = clients.create(tag: "models:download")
            let (bytes, response) = try await client.stream(request)
            let status = response.statusCode
            if [301, 302, 303, 307, 308].contains(status) {
                bytes.task.cancel()
                // The Hub reports the LFS SHA-256 and size on the redirect.
                if let etag = header(response, "X-Linked-ETag")?.trimmingCharacters(in: CharacterSet(charactersIn: "\" ")).lowercased(),
                   Self.isSha256(etag), expectedSha == nil {
                    expectedSha = etag
                }
                if let size = header(response, "X-Linked-Size").flatMap({ Int64($0) }) { expectedSize = size }
                guard let location = header(response, "Location"), let next = URL(string: location, relativeTo: url)?.absoluteURL else {
                    throw DownloadError(message: "Redirect without location")
                }
                url = next
                continue
            }
            switch status {
            case 200, 206:
                let append = status == 206 && have > 0
                let length = response.expectedContentLength
                let total = append ? have + max(length, 0) : length
                if expectedSize <= 0 && total > 0 { expectedSize = total }
                if let bulk {
                    bytes.task.cancel()
                    let (admitted, proxy) = try client.admitForTransfer(request)
                    let base = append ? have : 0
                    let size = expectedSize
                    let (tmp, result) = try await bulk.download(admitted, proxy: proxy) { written in
                        onProgress(base + written, max(size, base + written))
                    }
                    guard result.statusCode == status else { throw DownloadError(message: "HTTP \(result.statusCode)") }
                    try Self.commitChunk(tmp, to: part, append: append)
                } else {
                    try await Self.stream(bytes, to: part, append: append, expectedSize: expectedSize, onProgress: onProgress)
                }
            case 416:
                bytes.task.cancel()
                if expectedSize > 0 && have != expectedSize {
                    try? FileManager.default.removeItem(at: part)
                    continue
                }
            default:
                let body = await bytes.prefixData(2048)
                throw failure(response, body: body, file: file)
            }
            return try await Task.detached { try Self.verifyAndCommit(part: part, target: target, file: file, expectedSha: expectedSha, expectedSize: expectedSize) }.value
        }
    }

    /// Reads at most [limit] bytes of [url] (small JSON / text), following redirects hop by hop.
    func readSmall(_ url: URL, credential: Secret?, auth: AuthHost?, limit: Int = 4 << 20) async throws -> HttpResponse {
        var current = url
        for _ in 0 ..< Self.maxHops {
            let response = try await clients.create(tag: "models:download").send(Self.request(current, offset: 0, credential: credential, auth: auth))
            if response.isRedirect, let location = response.header("Location"), let next = URL(string: location, relativeTo: current)?.absoluteURL {
                current = next
                continue
            }
            return response
        }
        throw DownloadError(message: "Too many redirects")
    }

    static func request(_ url: URL, offset: Int64, credential: Secret?, auth: AuthHost?, end: Int64 = -1) -> URLRequest {
        var r = URLRequest(url: url)
        r.httpMethod = "GET"
        r.setValue("*/*", forHTTPHeaderField: "Accept")
        if end >= 0 {
            r.setValue("bytes=\(offset)-\(end)", forHTTPHeaderField: "Range")
        } else if offset > 0 {
            r.setValue("bytes=\(offset)-", forHTTPHeaderField: "Range")
        }
        // Credentials go only to their own hub, never to CDNs or third parties.
        if let credential, !credential.isBlank, let auth, let host = url.host(), isHubHost(host, auth) {
            r.setValue("Bearer \(credential.reveal())", forHTTPHeaderField: "Authorization")
        }
        return r
    }

    static func isHubHost(_ host: String, _ auth: AuthHost) -> Bool {
        switch auth {
        case .huggingFace: host == "huggingface.co"
        case .civitai: host == "civitai.com" || host == "civitai.red"
        }
    }

    static func isSha256(_ s: String) -> Bool { s.count == 64 && s.allSatisfy { $0.isHexDigit && !$0.isUppercase } }

    static func size(_ url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.int64Value ?? 0
    }

    private func header(_ r: HTTPURLResponse, _ name: String) -> String? { r.value(forHTTPHeaderField: name) }

    private static func stream(_ bytes: URLSession.AsyncBytes, to part: URL, append: Bool, expectedSize: Int64, onProgress: @Sendable (Int64, Int64) -> Void) async throws {
        let fm = FileManager.default
        if !append { try? fm.removeItem(at: part) }
        if !fm.fileExists(atPath: part.path) {
            fm.createFile(atPath: part.path, contents: nil, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        }
        let handle = try FileHandle(forWritingTo: part)
        defer { try? handle.close() }
        try handle.seekToEnd()
        var written = append ? size(part) : 0
        var buffer = Data(capacity: chunk)
        var lastReport = written
        do {
            for try await byte in bytes {
                buffer.append(byte)
                if buffer.count >= chunk {
                    try handle.write(contentsOf: buffer)
                    written += Int64(buffer.count)
                    buffer.removeAll(keepingCapacity: true)
                    if written - lastReport >= Int64(chunk) {
                        lastReport = written
                        onProgress(written, max(expectedSize, written))
                    }
                }
            }
        } catch {
            // Keep what arrived so the next attempt resumes from it.
            try? handle.write(contentsOf: buffer)
            throw error
        }
        try handle.write(contentsOf: buffer)
        written += Int64(buffer.count)
        try handle.synchronize()
        onProgress(written, max(expectedSize, written))
    }

    /// Appends (or moves) a transferred chunk onto the part file.
    static func commitChunk(_ chunk: URL, to part: URL, append: Bool) throws {
        let fm = FileManager.default
        defer { try? fm.removeItem(at: chunk) }
        if !append || !fm.fileExists(atPath: part.path) {
            try? fm.removeItem(at: part)
            try fm.moveItem(at: chunk, to: part)
            return
        }
        let out = try FileHandle(forWritingTo: part)
        defer { try? out.close() }
        try out.seekToEnd()
        let input = try FileHandle(forReadingFrom: chunk)
        defer { try? input.close() }
        while let data = try input.read(upToCount: 4 << 20), !data.isEmpty { try out.write(contentsOf: data) }
        try out.synchronize()
    }

    static func verifyAndCommit(part: URL, target: URL, file: ModelFile, expectedSha: String?, expectedSize: Int64) throws -> ModelFile {
        let fm = FileManager.default
        guard fm.fileExists(atPath: part.path) else { throw DownloadError(message: "Download produced no data") }
        let length = size(part)
        if expectedSize > 0 && length != expectedSize {
            throw DownloadError(message: "Incomplete download (\(formatBytes(length)) of \(formatBytes(expectedSize))); resume to continue")
        }
        let actual = try sha256(part)
        if let expectedSha, actual != expectedSha {
            try? fm.removeItem(at: part)
            throw DownloadError(message: "Checksum mismatch for \(file.name): the file was corrupted or altered. It was deleted; retry the download.")
        }
        try? fm.removeItem(at: target)
        do { try fm.moveItem(at: part, to: target) } catch { throw DownloadError(message: "Could not store \(file.name)") }
        var done = file
        done.sizeBytes = length
        done.sha256 = actual
        return done
    }

    static func sha256(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try handle.read(upToCount: 4 << 20), !data.isEmpty { hasher.update(data: data) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private func failure(_ r: HTTPURLResponse, body: Data, file: ModelFile) -> DownloadError {
        let text = String(decoding: body, as: UTF8.self)
        if r.value(forHTTPHeaderField: "X-Error-Code") == "GatedRepo" {
            return DownloadError(message: "Gated model: accept its terms on huggingface.co with your account, then sign in under Remote → Accounts.", retryable: false)
        }
        switch r.statusCode {
        case 401 where file.auth == .civitai:
            return DownloadError(message: "Civitai requires login for this file: add an API key under Remote → Accounts.", retryable: false)
        case 401, 403:
            return DownloadError(message: "Access denied (HTTP \(r.statusCode))\(text.contains("Early Access") ? ": early-access file" : "")", retryable: false)
        case 404, 410:
            return DownloadError(message: "File no longer available (HTTP \(r.statusCode))", retryable: false)
        case 429:
            return DownloadError(message: "Rate limited by the server — resume later")
        default:
            return DownloadError(message: "HTTP \(r.statusCode)")
        }
    }
}

/**
 Bulk bodies in a background URLSession, so a multi-gigabyte model keeps downloading while the
 app is in the background. A session exists per route (its proxy is part of the configuration);
 requests reach it only after the kill switch admitted them. The session has no cookies, cache
 or credential storage. Background sessions follow redirects on their own, so only final URLs
 (already resolved hop by hop) are handed over.
 */
final class BackgroundTransfer: NSObject, BulkTransfer, URLSessionDownloadDelegate, Sendable {
    static let identifier = "dev.wckdboy.autobot.models"

    private struct Pending {
        let continuation: CheckedContinuation<(URL, HTTPURLResponse), Error>
        let progress: @Sendable (Int64) -> Void
        var file: URL?
    }

    private let sessions = Mutex<[String: URLSession]>([:])
    private let pending = Mutex<[String: Pending]>([:])

    func download(_ request: URLRequest, proxy: Proxy, progress: @escaping @Sendable (Int64) -> Void) async throws -> (URL, HTTPURLResponse) {
        let session = session(for: proxy)
        let task = session.downloadTask(with: request)
        let key = "\(session.configuration.identifier ?? ""):\(task.taskIdentifier)"
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                pending.withLock { $0[key] = Pending(continuation: continuation, progress: progress) }
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    /// Cancels every running transfer (panic wipe, network switched off).
    func cancelAll() {
        let all = sessions.withLock { s -> [URLSession] in defer { s.removeAll() }; return Array(s.values) }
        all.forEach { $0.invalidateAndCancel() }
    }

    private func session(for proxy: Proxy) -> URLSession {
        let key: String = switch proxy {
        case .none: "direct"
        case .socks(let host, let port): "socks-\(host)-\(port)"
        }
        return sessions.withLock { s in
            if let existing = s[key] { return existing }
            let config = URLSessionConfiguration.background(withIdentifier: "\(Self.identifier).\(key)")
            config.isDiscretionary = false
            config.sessionSendsLaunchEvents = false
            config.urlCache = nil
            config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            config.httpCookieStorage = nil
            config.httpShouldSetCookies = false
            config.httpCookieAcceptPolicy = .never
            config.urlCredentialStorage = nil
            config.httpAdditionalHeaders = ["User-Agent": HeaderScrub.userAgent, "Accept-Language": HeaderScrub.acceptLanguage]
            config.proxyConfigurations = RouteResolver.proxyConfigurations(proxy)
            let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
            s[key] = session
            return session
        }
    }

    private func key(_ session: URLSession, _ task: URLSessionTask) -> String { "\(session.configuration.identifier ?? ""):\(task.taskIdentifier)" }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite _: Int64) {
        let progress = pending.withLock { $0[key(session, downloadTask)]?.progress }
        progress?(totalBytesWritten)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The system deletes [location] when this returns: move it now.
        let kept = AppPaths.models.appendingPathComponent(".transfer-\(UUID().uuidString)")
        let moved = (try? FileManager.default.moveItem(at: location, to: kept)) != nil
        pending.withLock { $0[key(session, downloadTask)]?.file = moved ? kept : nil }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let entry = pending.withLock({ $0.removeValue(forKey: key(session, task)) }) else { return }
        if let error {
            if let file = entry.file { try? FileManager.default.removeItem(at: file) }
            entry.continuation.resume(throwing: error)
        } else if let file = entry.file, let response = task.response as? HTTPURLResponse {
            entry.continuation.resume(returning: (file, response))
        } else {
            entry.continuation.resume(throwing: DownloadError(message: "Download produced no data"))
        }
    }
}

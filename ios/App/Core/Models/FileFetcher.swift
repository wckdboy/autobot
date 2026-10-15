import AutobotShared
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
    /// [vetHop] checks every redirect the transfer meets (kill switch, route, audit, credentials)
    /// and returns the request to follow; it throws to refuse the hop.
    func download(
        _ request: URLRequest,
        proxy: Proxy,
        vetHop: @escaping @Sendable (URLRequest) throws -> URLRequest,
        progress: @escaping @Sendable (Int64) -> Void,
    ) async throws -> (URL, HTTPURLResponse)
}

/// The redirect policy of a transfer handed to another session: the policy client admits the
/// hop (kill switch, route, audit) and a hub credential only travels to the host it was issued
/// for — never to a different host, even another hub host.
func vetTransferHop(_ request: URLRequest, client: HttpClient, proxy: Proxy, original: URL, auth: AuthHost?) throws -> URLRequest {
    var hop = request
    if !FetchRules.shared.keepsCredential(hopUrl: hop.url?.absoluteString ?? "", originalUrl: original.absoluteString, auth: auth?.kotlin) {
        hop.setValue(nil, forHTTPHeaderField: "Authorization")
    }
    return try client.admitRedirect(hop, sessionProxy: proxy)
}

/**
 Moves the bytes of one file for the shared `FetchSession`, which decides everything else:
 redirects hop by hop (each hop passes the kill switch and is audited; credentials are only sent
 to their own hub), `Range` resume of `*.part`, SHA-256 verification (known up front, or the Hub's
 `X-Linked-ETag`) and what happens to a part file that fails it.

 Bodies stream through the policy client by default. With a [BulkTransfer] (the background
 session) the headers of the final hop are read through the policy client, then the body is
 handed to the transfer with the same scrubbed request and route.
 */
struct FileFetcher: Sendable {
    private static let chunk = 256 * 1024

    let clients: HttpClientFactory
    var bulk: BulkTransfer? = nil

    /// Downloads [file] to [target]; returns it with its verified size and SHA-256.
    func fetch(_ file: ModelFile, to target: URL, credential: Secret?, onProgress: @escaping @Sendable (Int64, Int64) -> Void) async throws -> ModelFile {
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Remote zip entries are Android's NPU packages; Core ML bundles are plain files.
        if file.zipEntry != nil { throw DownloadError(message: "Zip packages are Android-only", retryable: false) }
        let part = target.appendingPathExtension("part")
        let session = FetchSession(file: file.kotlin, credential: credential.flatMap { $0.isBlank ? nil : $0.reveal() })
        var step = session.begin(have: Self.size(part))
        while true {
            if step is FetchStepNext {
                try Task.checkCancellation()
                let have = Self.size(part)
                let next = try DownloadError.rethrowing { try session.request(have: have) }
                guard let url = URL(string: next.url) else { throw DownloadError(message: "Invalid URL", retryable: false) }
                let request = Self.request(url, headers: next.headers)
                let client = clients.create(tag: "models:download")
                let (bytes, response) = try await client.stream(request)
                let status = response.statusCode
                let headers = HttpClient.headers(response)
                let answer = session.onResponse(status: Int32(status), headers: headers, have: have, contentLength: response.expectedContentLength)
                if let write = answer as? FetchStepWrite {
                    let append = write.append
                    let expected = session.expectedSize
                    if let bulk {
                        bytes.task.cancel()
                        let (admitted, proxy) = try client.admitForTransfer(request)
                        let base = append ? have : 0
                        let auth = file.auth
                        let (tmp, result) = try await bulk.download(admitted, proxy: proxy, vetHop: { hop in
                            try vetTransferHop(hop, client: client, proxy: proxy, original: url, auth: auth)
                        }) { written in
                            onProgress(base + written, max(expected, base + written))
                        }
                        guard result.statusCode == status else { throw DownloadError(message: "HTTP \(result.statusCode)") }
                        try Self.commitChunk(tmp, to: part, append: append)
                    } else {
                        try await Self.stream(bytes, to: part, append: append, expectedSize: expected, onProgress: onProgress)
                    }
                    step = FetchStepVerify.shared
                } else if answer is FetchStepReject {
                    let body = await bytes.prefixData(2048)
                    throw DownloadError(session.failure(status: Int32(status), headers: headers, body: String(decoding: body, as: UTF8.self)))
                } else {
                    bytes.task.cancel()
                    step = answer
                }
            } else if step is FetchStepRestart {
                try? FileManager.default.removeItem(at: part)
                step = FetchStepNext.shared
            } else if let fail = step as? FetchStepFail {
                throw DownloadError(fail.error)
            } else {
                return try await verifyAndCommit(session, part: part, target: target)
            }
        }
    }

    /// Checks the part file against the session (size, then SHA-256) and moves it to [target].
    private func verifyAndCommit(_ session: FetchSession, part: URL, target: URL) async throws -> ModelFile {
        let fm = FileManager.default
        let length = Self.size(part)
        try DownloadError.rethrowing { try session.checkComplete(partSize: fm.fileExists(atPath: part.path) ? length : -1) }
        let actual = try await Task.detached { try Self.sha256(part) }.value
        let verified: AutobotShared.ModelFile
        do {
            verified = try DownloadError.rethrowing { try session.verify(sha256: actual, size: length) }
        } catch {
            try? fm.removeItem(at: part)
            throw error
        }
        try? fm.removeItem(at: target)
        do { try fm.moveItem(at: part, to: target) } catch { throw DownloadError(message: "Could not store \(session.file.name)") }
        return ModelFile(verified)
    }

    static func request(_ url: URL, headers: [String: String]) -> URLRequest {
        var r = URLRequest(url: url)
        r.httpMethod = "GET"
        headers.forEach { r.setValue($0.value, forHTTPHeaderField: $0.key) }
        return r
    }

    /// A GET from [offset] (to [end] inclusive when `>= 0`); the credential only goes to its own hub.
    static func request(_ url: URL, offset: Int64, credential: Secret?, auth: AuthHost?, end: Int64 = -1) -> URLRequest {
        let headers = FetchRules.shared.headers(url: url.absoluteString, offset: offset, end: end,
                                                credential: credential.flatMap { $0.isBlank ? nil : $0.reveal() }, auth: auth?.kotlin)
        return request(url, headers: headers)
    }

    static func isHubHost(_ host: String, _ auth: AuthHost) -> Bool { FetchRules.shared.isHubHost(host: host, auth: auth.kotlin) }

    static func size(_ url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.int64Value ?? 0
    }

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

    static func sha256(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try handle.read(upToCount: 4 << 20), !data.isEmpty { hasher.update(data: data) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/**
 Bulk bodies in a background URLSession, so a multi-gigabyte model keeps downloading while the
 app is in the background. A session exists per route (its proxy is part of the configuration);
 requests reach it only after the kill switch admitted them. The session has no cookies, cache
 or credential storage. Only final URLs (already resolved hop by hop) are handed over; should a
 transfer still meet a redirect, the hop goes through the kill switch, the route check and the
 audit log in [urlSession(_:task:willPerformHTTPRedirection:newRequest:)], and hub credentials
 are dropped for any other host. Background sessions may follow redirects without asking their
 delegate, so a transfer whose final URL differs from every vetted one is discarded (fail closed).
 */
final class BackgroundTransfer: NSObject, BulkTransfer, URLSessionDownloadDelegate, Sendable {
    static let identifier = "ai.wckd.autobot.models"

    private struct Pending {
        let continuation: CheckedContinuation<(URL, HTTPURLResponse), Error>
        let vetHop: @Sendable (URLRequest) throws -> URLRequest
        let progress: @Sendable (Int64) -> Void
        var file: URL?
        /// URLs this transfer may end at: the original and every vetted hop.
        var vetted: Set<URL>
        var refused: Error?
    }

    private let sessions = Mutex<[String: URLSession]>([:])
    private let pending = Mutex<[String: Pending]>([:])
    /// Session configuration per route; tests substitute an ephemeral one with a stub protocol.
    private let makeConfiguration: @Sendable (String) -> URLSessionConfiguration

    init(makeConfiguration: @escaping @Sendable (String) -> URLSessionConfiguration = { URLSessionConfiguration.background(withIdentifier: $0) }) {
        self.makeConfiguration = makeConfiguration
    }

    func download(
        _ request: URLRequest,
        proxy: Proxy,
        vetHop: @escaping @Sendable (URLRequest) throws -> URLRequest,
        progress: @escaping @Sendable (Int64) -> Void,
    ) async throws -> (URL, HTTPURLResponse) {
        let session = session(for: proxy)
        let task = session.downloadTask(with: request)
        let key = key(session, task)
        let start = Set([request.url].compactMap { $0 })
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                pending.withLock { $0[key] = Pending(continuation: continuation, vetHop: vetHop, progress: progress, vetted: start) }
                task.resume()
            }
        } onCancel: {
            task.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest) async -> URLRequest? {
        let key = key(session, task)
        guard let vet = pending.withLock({ $0[key]?.vetHop }) else { return nil }
        do {
            let hop = try vet(request)
            pending.withLock { p in if let url = hop.url { p[key]?.vetted.insert(url) } }
            return hop
        } catch {
            pending.withLock { $0[key]?.refused = error }
            task.cancel()
            return nil
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
            let config = makeConfiguration("\(Self.identifier).\(key)")
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
        let final = (task.response as? HTTPURLResponse)?.url ?? task.currentRequest?.url
        if let refused = entry.refused {
            if let file = entry.file { try? FileManager.default.removeItem(at: file) }
            entry.continuation.resume(throwing: refused)
        } else if error == nil, let final, !entry.vetted.contains(final) {
            // Followed a redirect without asking: nothing vetted that hop, so its bytes are discarded.
            if let file = entry.file { try? FileManager.default.removeItem(at: file) }
            entry.continuation.resume(throwing: NetworkBlockedError(message: "Download was redirected to \(final.host() ?? "another host") without the kill switch; discarded"))
        } else if let error {
            if let file = entry.file { try? FileManager.default.removeItem(at: file) }
            entry.continuation.resume(throwing: error)
        } else if let file = entry.file, let response = task.response as? HTTPURLResponse {
            entry.continuation.resume(returning: (file, response))
        } else {
            entry.continuation.resume(throwing: DownloadError(message: "Download produced no data"))
        }
    }
}

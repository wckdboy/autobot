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
    /// and returns the request to follow; it throws to refuse the hop. [resume] is where the
    /// transfer keeps its resume data when it is paused or interrupted ([TransferResume]), and
    /// what it continues from when that data matches [request].
    func download(
        _ request: URLRequest,
        proxy: Proxy,
        resume: URL?,
        vetHop: @escaping @Sendable (URLRequest) throws -> URLRequest,
        progress: @escaping @Sendable (Int64) -> Void,
    ) async throws -> (URL, HTTPURLResponse)
}

extension BulkTransfer {
    func download(
        _ request: URLRequest,
        proxy: Proxy,
        vetHop: @escaping @Sendable (URLRequest) throws -> URLRequest,
        progress: @escaping @Sendable (Int64) -> Void,
    ) async throws -> (URL, HTTPURLResponse) {
        try await download(request, proxy: proxy, resume: nil, vetHop: vetHop, progress: progress)
    }
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
                        let (tmp, result) = try await bulk.download(admitted, proxy: proxy, resume: TransferResume.file(for: part), vetHop: { hop in
                            try vetTransferHop(hop, client: client, proxy: proxy, original: url, auth: auth)
                        }) { written in
                            onProgress(base + written, max(expected, base + written))
                        }
                        guard let appends = Self.bulkAppends(status: result.statusCode, have: have) else {
                            try? FileManager.default.removeItem(at: tmp)
                            throw DownloadError(message: "HTTP \(result.statusCode)")
                        }
                        try Self.commitChunk(tmp, to: part, append: appends)
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
                TransferResume.discard(TransferResume.file(for: part))
                CompletedTransfer.discard(TransferResume.file(for: part))
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
        TransferResume.discard(TransferResume.file(for: part))
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

    /**
     Whether a body the background session brought ([status]) continues a part file of [have]
     bytes; nil for a status that is no body of the file. Its request asked for `bytes=have-` when
     [have] > 0, so a 206 continues the part file and a 200 (the range was ignored, or a resumed
     transfer the server restarted) is the whole file. A transfer resumed from the system's resume
     data reports the 206 of its last leg even when it started from 0: still the whole file.
     */
    static func bulkAppends(status: Int, have: Int64) -> Bool? {
        switch status {
        case 200: false
        case 206: have > 0
        default: nil
        }
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
        // One pool per chunk: a multi-gigabyte copy must not keep every chunk alive until it returns.
        while try autoreleasepool(invoking: {
            guard let data = try input.read(upToCount: 4 << 20), !data.isEmpty else { return false }
            try out.write(contentsOf: data)
            return true
        }) {}
        try out.synchronize()
    }

    static func sha256(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while try autoreleasepool(invoking: {
            guard let data = try handle.read(upToCount: 4 << 20), !data.isEmpty else { return false }
            hasher.update(data: data)
            return true
        }) {}
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

 Nothing received is thrown away: a pause (or a dropped connection) keeps the system's resume
 data ([TransferResume]) and the next transfer of the same part file continues from it. After a
 relaunch [reconnect] finds the transfers an earlier process left running; the fetch of the same
 part file adopts its transfer instead of starting a second one, and [dropOrphans] stops the
 rest (keeping their resume data).

 The sessions send launch events: when a transfer finishes while the app is suspended or was
 terminated by the system, iOS relaunches it in the background ([BackgroundEvents]). A body that
 finishes with no fetch waiting for it is kept on disk next to its part file ([CompletedTransfer])
 so it survives until the downloader (which needs the unlocked device for the model registry)
 verifies and stores it, and then starts the next file in the same session. A transfer the user
 cancelled by swiping the app away is reported to [ForceQuitNotice]; its resume data is kept.
 */
final class BackgroundTransfer: NSObject, BulkTransfer, URLSessionDownloadDelegate, Sendable {
    static let identifier = "ai.wckd.autobot.models"
    /// Routes that have had a background session, so a relaunch can reconnect to them.
    static let routesKey = "ai.wckd.autobot.models.routes"

    private typealias Outcome = Result<(URL, HTTPURLResponse), Error>

    private struct Pending {
        let continuation: CheckedContinuation<(URL, HTTPURLResponse), Error>
        let vetHop: @Sendable (URLRequest) throws -> URLRequest
        let progress: @Sendable (Int64) -> Void
        /// The request as admitted, and where its resume data goes.
        let request: URLRequest
        let resume: URL?
        /// URLs this transfer may end at: the original and every vetted hop.
        var vetted: Set<URL>
        var file: URL?
        var refused: Error?
        /// Paused: the outcome waits for the resume data, so the caller sees the bytes kept.
        var pausing = false
        var resumeKept = false
        var outcome: Outcome?
    }

    /// A transfer an earlier process started, until the fetch of its part file adopts it.
    private struct Orphan {
        let task: URLSessionDownloadTask
        var file: URL?
        var completed = false
        var failed = false
    }

    private struct State {
        var pending: [String: Pending] = [:]
        var orphans: [String: Orphan] = [:]
    }

    private let sessions = Mutex<[String: URLSession]>([:])
    private let state = Mutex(State())
    /// Session configuration per route; tests substitute an ephemeral one with a stub protocol.
    private let makeConfiguration: @Sendable (String) -> URLSessionConfiguration
    /// Thread-safe (documented), though not marked Sendable.
    nonisolated(unsafe) private let routes: UserDefaults?

    init(
        makeConfiguration: @escaping @Sendable (String) -> URLSessionConfiguration = { URLSessionConfiguration.background(withIdentifier: $0) },
        routes: UserDefaults? = .standard,
    ) {
        self.makeConfiguration = makeConfiguration
        self.routes = routes
    }

    func download(
        _ request: URLRequest,
        proxy: Proxy,
        resume: URL?,
        vetHop: @escaping @Sendable (URLRequest) throws -> URLRequest,
        progress: @escaping @Sendable (Int64) -> Void,
    ) async throws -> (URL, HTTPURLResponse) {
        let session = session(for: proxy)
        if let resume, let done = CompletedTransfer.take(resume, matching: request) {
            // Finished while no process was waiting for it (a background relaunch, or before this one).
            let tag = TransferResume.tag(resume)
            state.withLock { s in s.orphans = s.orphans.filter { $0.value.task.taskDescription != tag } }
            return done
        }
        if let resume, let adopted = await adoptable(resume, request: request, session: session, vetHop: vetHop) {
            return try await run(session, adopted.task, request: request, vetted: adopted.vetted, resume: resume, vetHop: vetHop, progress: progress)
        }
        if let resume, let saved = TransferResume.take(resume, matching: request) {
            do {
                let task = session.downloadTask(withResumeData: saved.data)
                let (file, response) = try await run(session, task, request: request, vetted: [URL(string: saved.url)].compactMap { $0 },
                                                     resume: resume, vetHop: vetHop, progress: progress)
                if [200, 206].contains(response.statusCode) { return (file, response) }
                // The URL it replays went stale (an expired signature): start over from the part file.
                try? FileManager.default.removeItem(at: file)
            } catch let e as URLError where e.code != .cancelled && e.downloadTaskResumeData == nil {
                // The system could not continue (its temporary file is gone): start over.
            }
            try Task.checkCancellation()
        }
        return try await run(session, session.downloadTask(with: request), request: request, vetted: [], resume: resume, vetHop: vetHop, progress: progress)
    }

    private func run(
        _ session: URLSession,
        _ task: URLSessionDownloadTask,
        request: URLRequest,
        vetted: [URL],
        resume: URL?,
        vetHop: @escaping @Sendable (URLRequest) throws -> URLRequest,
        progress: @escaping @Sendable (Int64) -> Void,
    ) async throws -> (URL, HTTPURLResponse) {
        try Task.checkCancellation()
        if let resume, task.taskDescription == nil { task.taskDescription = TransferResume.tag(resume) }
        let key = key(session, task)
        let allowed = Set([request.url].compactMap { $0 } + vetted)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let entry = Pending(continuation: continuation, vetHop: vetHop, progress: progress, request: request, resume: resume, vetted: allowed)
                // An adopted transfer may have finished already.
                let finished: Orphan? = state.withLock { s in
                    let orphan = s.orphans.removeValue(forKey: key)
                    if let orphan, orphan.completed { return orphan }
                    var e = entry
                    e.file = orphan?.file
                    s.pending[key] = e
                    return nil
                }
                if let finished {
                    var e = entry
                    e.file = finished.file
                    if e.file == nil, let resume, let done = CompletedTransfer.take(resume, matching: request) {
                        continuation.resume(returning: done)
                    } else {
                        continuation.resume(with: outcome(e, task, error: nil))
                    }
                } else {
                    task.resume()
                    Task { @MainActor in BackgroundEvents.shared.check() }
                }
            }
        } onCancel: {
            pause(session, task)
        }
    }

    /// Cancels [task] keeping its resume data; the caller's outcome waits until it is kept.
    private func pause(_ session: URLSession, _ task: URLSessionDownloadTask) {
        let key = key(session, task)
        let entry = state.withLock { s -> Pending? in
            s.pending[key]?.pausing = true
            return s.pending[key]
        }
        guard let entry else {
            task.cancel()
            return
        }
        let (request, resume) = (entry.request, entry.resume)
        task.cancel(byProducingResumeData: { [weak self] data in
            if let resume { Self.keep(data, task: task, request: request, at: resume) }
            self?.resumeKept(key)
        })
    }

    private func resumeKept(_ key: String) {
        let done = state.withLock { s -> Pending? in
            guard s.pending[key]?.outcome == nil else { return s.pending.removeValue(forKey: key) }
            s.pending[key]?.resumeKept = true
            return nil
        }
        if let done, let outcome = done.outcome { done.continuation.resume(with: outcome) }
    }

    /// The transfer an earlier process left for [resume], when it continues [request] on this
    /// route; any other one is stopped (its resume data kept, for [TransferResume.take]).
    private func adoptable(
        _ resume: URL,
        request: URLRequest,
        session: URLSession,
        vetHop: @Sendable (URLRequest) throws -> URLRequest,
    ) async -> (task: URLSessionDownloadTask, vetted: [URL])? {
        let tag = TransferResume.tag(resume)
        let prefix = "\(session.configuration.identifier ?? ""):"
        guard let found = state.withLock({ s in s.orphans.first { $0.value.task.taskDescription == tag } }) else { return nil }
        let (key, orphan) = (found.key, found.value)
        let task = orphan.task
        // Wherever it was redirected in the earlier process must pass the kill switch now.
        let reached = [task.currentRequest?.url, (task.response as? HTTPURLResponse)?.url].compactMap { $0 }
        let vetted = reached.allSatisfy { url in
            url == task.originalRequest?.url || (try? vetHop(URLRequest(url: url))) != nil
        }
        if key.hasPrefix(prefix), !orphan.failed, vetted, TransferResume.continues(task.originalRequest, request) {
            return (task, [task.originalRequest?.url].compactMap { $0 } + reached)
        }
        let dropped = state.withLock { $0.orphans.removeValue(forKey: key) }
        if let file = dropped?.file { try? FileManager.default.removeItem(at: file) }
        if dropped?.completed == false {
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                task.cancel(byProducingResumeData: { data in
                    Self.keep(data, task: task, request: nil, at: resume)
                    done.resume()
                })
            }
        }
        return nil
    }

    /**
     After a relaunch: reconnects to the background session of every route an earlier process
     used and keeps their transfers for the fetch of the same part file to adopt. Returns the
     resume tags ([TransferResume.tag]) of the part files they belong to.
     */
    func reconnect() async -> Set<String> {
        for route in routes?.stringArray(forKey: Self.routesKey) ?? [] {
            guard let proxy = Self.proxy(route: route) else { continue }
            let session = session(for: proxy)
            for task in await session.allTasks {
                guard let download = task as? URLSessionDownloadTask, download.taskDescription != nil,
                      task.state == .running || task.state == .suspended else {
                    if task.state != .completed { task.cancel() }
                    continue
                }
                let key = key(session, download)
                state.withLock { s in
                    if s.pending[key] == nil && s.orphans[key] == nil { s.orphans[key] = Orphan(task: download) }
                }
            }
        }
        return state.withLock { Set($0.orphans.values.compactMap(\.task.taskDescription)) }
    }

    /// Stops the transfers no fetch adopted, keeping their resume data for a later resume.
    func dropOrphans() {
        let left = state.withLock { s -> [Orphan] in
            defer { s.orphans.removeAll() }
            return Array(s.orphans.values)
        }
        for orphan in left {
            if let file = orphan.file { try? FileManager.default.removeItem(at: file) }
            guard !orphan.completed, let tag = orphan.task.taskDescription else { continue }
            let task = orphan.task
            task.cancel(byProducingResumeData: { data in Self.keep(data, task: task, request: nil, at: TransferResume.path(tag)) })
        }
    }

    /// Keeps resume [data] of [task] (the transfer of [request], when it is one of ours).
    private static func keep(_ data: Data?, task: URLSessionTask, request: URLRequest?, at resume: URL) {
        let original = task.originalRequest
        // The URL the data replays; the range is the part file's (a resumed task's own request
        // does not say where the part file stood).
        let range = (request ?? original)?.value(forHTTPHeaderField: "Range")
        let persist = [request, original].allSatisfy { $0?.value(forHTTPHeaderField: "Authorization") == nil }
        TransferResume.keep(data, url: original?.url ?? request?.url, range: range, at: resume, persist: persist)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest) async -> URLRequest? {
        let key = key(session, task)
        guard let vet = state.withLock({ $0.pending[key]?.vetHop }) else {
            // Nothing vets a hop for a transfer no fetch adopted yet: stop it.
            if state.withLock({ $0.orphans.removeValue(forKey: key) }) != nil { task.cancel() }
            return nil
        }
        do {
            let hop = try vet(request)
            state.withLock { s in if let url = hop.url { s.pending[key]?.vetted.insert(url) } }
            return hop
        } catch {
            state.withLock { $0.pending[key]?.refused = error }
            task.cancel()
            return nil
        }
    }

    /// Cancels every running transfer and forgets all resume data (panic wipe).
    func cancelAll() {
        let all = sessions.withLock { s -> [URLSession] in defer { s.removeAll() }; return Array(s.values) }
        all.forEach { $0.invalidateAndCancel() }
        let orphans = state.withLock { s -> [Orphan] in defer { s.orphans.removeAll() }; return Array(s.orphans.values) }
        orphans.compactMap(\.file).forEach { try? FileManager.default.removeItem(at: $0) }
        TransferResume.forgetAll()
    }

    private func session(for proxy: Proxy) -> URLSession {
        let route = Self.route(proxy)
        return sessions.withLock { s in
            if let existing = s[route] { return existing }
            let config = makeConfiguration("\(Self.identifier).\(route)")
            config.isDiscretionary = false
            // Relaunch the app in the background when a transfer finishes, so the next file starts.
            config.sessionSendsLaunchEvents = true
            config.urlCache = nil
            config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            config.httpCookieStorage = nil
            config.httpShouldSetCookies = false
            config.httpCookieAcceptPolicy = .never
            config.urlCredentialStorage = nil
            config.httpAdditionalHeaders = ["User-Agent": HeaderScrub.userAgent, "Accept-Language": HeaderScrub.acceptLanguage]
            config.proxyConfigurations = RouteResolver.proxyConfigurations(proxy)
            let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
            s[route] = session
            if config.identifier != nil, let routes {
                let known = routes.stringArray(forKey: Self.routesKey) ?? []
                if !known.contains(route) { routes.set(known + [route], forKey: Self.routesKey) }
            }
            return session
        }
    }

    /// Whether a transfer a fetch is waiting for is running (the queue goes on without the app).
    var hasActiveTransfer: Bool { state.withLock { !$0.pending.isEmpty } }

    /// iOS relaunched the app for [identifier]: recreate that session so its events are delivered.
    func attach(identifier: String) {
        let prefix = Self.identifier + "."
        guard identifier.hasPrefix(prefix), let proxy = Self.proxy(route: String(identifier.dropFirst(prefix.count))) else { return }
        _ = session(for: proxy)
    }

    /// The session key of a route (part of the background session identifier).
    static func route(_ proxy: Proxy) -> String {
        switch proxy {
        case .none: "direct"
        case .socks(let host, let port): "socks-\(host)-\(port)"
        }
    }

    /// The route of a session key; nil when it is not one.
    static func proxy(route: String) -> Proxy? {
        if route == "direct" { return Proxy.none }
        guard route.hasPrefix("socks-"), let dash = route.lastIndex(of: "-"), let port = Int(route[route.index(after: dash)...]) else { return nil }
        let host = String(route[route.index(route.startIndex, offsetBy: 6) ..< dash])
        return host.isEmpty ? nil : .socks(host: host, port: port)
    }

    private func key(_ session: URLSession, _ task: URLSessionTask) -> String { "\(session.configuration.identifier ?? ""):\(task.taskIdentifier)" }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite _: Int64) {
        let progress = state.withLock { $0.pending[key(session, downloadTask)]?.progress }
        progress?(totalBytesWritten)
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        // The system deletes [location] when this returns: move it now.
        let kept = AppPaths.models.appendingPathComponent(".transfer-\(UUID().uuidString)")
        let moved = (try? FileManager.default.moveItem(at: location, to: kept)) != nil
        let key = key(session, downloadTask)
        let owned = state.withLock { s -> Bool in
            if s.pending[key] != nil {
                s.pending[key]?.file = moved ? kept : nil
                return true
            }
            // Finished while no fetch was waiting: kept on disk for the fetch that adopts it.
            guard downloadTask.taskDescription != nil else { return false }
            var orphan = s.orphans[key] ?? Orphan(task: downloadTask)
            orphan.file = nil
            s.orphans[key] = orphan
            return true
        }
        if owned, moved, let tag = downloadTask.taskDescription, state.withLock({ $0.pending[key] == nil }) {
            if !CompletedTransfer.keep(kept, task: downloadTask, at: TransferResume.path(tag)) { try? FileManager.default.removeItem(at: kept) }
        } else if !owned, moved {
            try? FileManager.default.removeItem(at: kept)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let key = key(session, task)
        let entry = state.withLock { s -> Pending? in
            if let entry = s.pending[key] { return entry }
            s.orphans[key]?.completed = true
            s.orphans[key]?.failed = error != nil
            return nil
        }
        guard let entry else {
            // An earlier process's transfer that failed: keep what it received.
            if let tag = task.taskDescription { Self.keep((error as? URLError)?.downloadTaskResumeData, task: task, request: nil, at: TransferResume.path(tag)) }
            if ForceQuitNotice.isForceQuit(error) { ForceQuitNotice.record() }
            return
        }
        let outcome = outcome(entry, task, error: error)
        let deliver = state.withLock { s -> Pending? in
            guard let current = s.pending[key] else { return nil }
            if current.pausing && !current.resumeKept {
                s.pending[key]?.outcome = outcome
                return nil
            }
            return s.pending.removeValue(forKey: key)
        }
        deliver?.continuation.resume(with: outcome)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        guard let identifier = session.configuration.identifier else { return }
        Task { @MainActor in BackgroundEvents.shared.didFinishEvents(identifier) }
    }

    private func outcome(_ entry: Pending, _ task: URLSessionTask, error: Error?) -> Outcome {
        let final = (task.response as? HTTPURLResponse)?.url ?? task.currentRequest?.url
        func discard() { if let file = entry.file { try? FileManager.default.removeItem(at: file) } }
        if let refused = entry.refused {
            discard()
            return .failure(refused)
        } else if error == nil, let final, !entry.vetted.contains(final) {
            // Followed a redirect without asking: nothing vetted that hop, so its bytes are discarded.
            discard()
            return .failure(NetworkBlockedError(message: "Download was redirected to \(final.host() ?? "another host") without the kill switch; discarded"))
        } else if let error {
            discard()
            // A dropped connection: keep what arrived for the next attempt.
            if let resume = entry.resume, let data = (error as? URLError)?.downloadTaskResumeData {
                Self.keep(data, task: task, request: entry.request, at: resume)
            }
            return .failure(error)
        } else if let file = entry.file, let response = task.response as? HTTPURLResponse {
            return .success((file, response))
        } else {
            return .failure(DownloadError(message: "Download produced no data"))
        }
    }
}

/**
 A background transfer body that finished with no fetch waiting for it (the app was relaunched in
 the background, or the device was locked so the model registry could not be read). The body is
 kept next to the part file (`*.part.resume.body`, under the models directory's
 until-first-unlock protection) with what it answered (`*.part.resume.done`: the resource, the
 range, the status), so it survives until a fetch of the same part file takes it, even across
 another relaunch. Only bodies that ended at the URL they were requested from are kept: a
 redirect no kill switch vetted is discarded.
 */
struct CompletedTransfer: Codable, Equatable, Sendable {
    /// [TransferResume.resource] of the request (no query: hub CDN signatures are not stored).
    let resource: String
    let range: String?
    let status: Int

    static func body(_ resume: URL) -> URL { resume.appendingPathExtension("body") }
    static func record(_ resume: URL) -> URL { resume.appendingPathExtension("done") }

    /// Keeps [file] (the finished body of [task]) for the part file of [resume]; false when it is not kept.
    @discardableResult
    static func keep(_ file: URL, task: URLSessionTask, at resume: URL) -> Bool {
        guard let original = task.originalRequest, let response = task.response as? HTTPURLResponse,
              let resource = TransferResume.resource(original.url), TransferResume.resource(response.url) == resource else { return false }
        return keep(file, record: CompletedTransfer(resource: resource, range: original.value(forHTTPHeaderField: "Range"), status: response.statusCode), at: resume)
    }

    static func keep(_ file: URL, record: CompletedTransfer, at resume: URL) -> Bool {
        let fm = FileManager.default
        // Only into an existing model directory (a removed model stays removed).
        guard fm.fileExists(atPath: resume.deletingLastPathComponent().path), let data = try? JSONEncoder().encode(record) else { return false }
        try? fm.removeItem(at: body(resume))
        do {
            try fm.moveItem(at: file, to: body(resume))
            try data.write(to: self.record(resume), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return true
        } catch {
            discard(resume)
            return false
        }
    }

    /// The kept body for [resume] when it answers [request] (same resource and range); removes it either way.
    static func take(_ resume: URL, matching request: URLRequest) -> (URL, HTTPURLResponse)? {
        let fm = FileManager.default
        guard let data = try? Data(contentsOf: record(resume)) else { return nil }
        try? fm.removeItem(at: record(resume))
        guard let kept = try? JSONDecoder().decode(CompletedTransfer.self, from: data), let url = request.url,
              kept.resource == TransferResume.resource(url), kept.range == request.value(forHTTPHeaderField: "Range"),
              fm.fileExists(atPath: body(resume).path),
              let response = HTTPURLResponse(url: url, statusCode: kept.status, httpVersion: "HTTP/1.1", headerFields: nil) else {
            try? fm.removeItem(at: body(resume))
            return nil
        }
        let out = AppPaths.models.appendingPathComponent(".transfer-\(UUID().uuidString)")
        guard (try? fm.moveItem(at: body(resume), to: out)) != nil else { return nil }
        return (out, response)
    }

    static func discard(_ resume: URL) {
        try? FileManager.default.removeItem(at: body(resume))
        try? FileManager.default.removeItem(at: record(resume))
    }
}

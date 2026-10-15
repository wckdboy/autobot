import Foundation
import Synchronization

/**
 Normalizes outgoing headers so requests do not fingerprint the device: replaces `User-Agent`
 with [userAgent], pins `Accept-Language` (URLSession would otherwise send the device's locale)
 and removes `Referer`, `Cookie`, `Origin` and common tracking / device headers.
 `Authorization` and content headers set by the caller are left untouched.
 */
enum HeaderScrub {
    static let userAgent = "Autobot"
    static let acceptLanguage = "en"
    static let strippedHeaders = [
        "Referer",
        "Cookie",
        "Origin",
        "X-Requested-With",
        "X-Device-Id",
        "X-Client-Data",
        "X-Forwarded-For",
        "HTTP-Referer",
        "X-Title",
    ]

    static func scrub(_ request: URLRequest) -> URLRequest {
        var r = request
        strippedHeaders.forEach { r.setValue(nil, forHTTPHeaderField: $0) }
        r.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        r.setValue(acceptLanguage, forHTTPHeaderField: "Accept-Language")
        r.httpShouldHandleCookies = false
        r.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        return r
    }
}

/// Never follows redirects: they would bypass the kill switch. The 3xx response is returned.
private final class NoRedirects: NSObject, URLSessionTaskDelegate, Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest) async -> URLRequest? {
        nil
    }
}

/**
 Builds clients that honour the [NetworkPolicy] — the iOS counterpart of `HttpClientFactory.kt`.

 Every client:
 - runs the kill switch first (blocks before DNS/socket I/O, writes the [AuditLog]) and then the
   header scrub;
 - uses an ephemeral URLSession with no cache, no cookies and no credential storage;
 - uses timeouts suited to token streaming (long idle timeout, no overall timeout);
 - never follows redirects and does not retry silently.

 The route is resolved **when the client is created** and fixed for its lifetime. Create a fresh
 client per logical operation; if the route changes while an old client is still used, the kill
 switch rejects its requests. Sessions are kept separate per route so a connection opened
 directly can never be reused once Tor is selected.
 */
final class HttpClientFactory: Sendable {
    static let requestTimeout: TimeInterval = 180
    static let proxyRequestTimeout: TimeInterval = 240
    static let resourceTimeout: TimeInterval = 7 * 24 * 3600

    let policy: NetworkPolicy
    let auditLog: AuditLog
    /// Extra URLProtocol classes (tests stub the transport with these).
    nonisolated(unsafe) private let protocolClasses: [AnyClass]
    private let sessions = Mutex<[String: URLSession]>([:])
    private let redirects = NoRedirects()

    init(policy: NetworkPolicy, auditLog: AuditLog, protocolClasses: [AnyClass] = []) {
        self.policy = policy
        self.auditLog = auditLog
        self.protocolClasses = protocolClasses
        policy.addObserver { [weak self] mode in self?.evictAll(cancelInFlight: mode == .offline) }
    }

    /// Creates a client for [tag] (shown in the audit log) with the given routing [override].
    func create(tag: String, override: RouteOverride = .inherit) -> HttpClient {
        let effective = RouteResolver.effectiveMode(policy.mode, override, socksFallback: policy.socksFallback)
        return HttpClient(factory: self, tag: tag, override: override, expectedRoute: effective)
    }

    /// Drops every session (e.g. after the network mode changes). Switching to Offline also
    /// cancels requests still in flight.
    func evictAll(cancelInFlight: Bool = false) {
        let old = sessions.withLock { s -> [URLSession] in
            defer { s.removeAll() }
            return Array(s.values)
        }
        old.forEach { cancelInFlight ? $0.invalidateAndCancel() : $0.finishTasksAndInvalidate() }
    }

    fileprivate func session(for proxy: Proxy, route: String) -> URLSession {
        let key: String = switch proxy {
        case .none: "\(route)|direct"
        case .socks(let host, let port): "\(route)|socks:\(host):\(port)"
        }
        return sessions.withLock { s in
            if let existing = s[key] { return existing }
            let config = URLSessionConfiguration.ephemeral
            config.urlCache = nil
            config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
            config.httpCookieStorage = nil
            config.httpShouldSetCookies = false
            config.httpCookieAcceptPolicy = .never
            config.urlCredentialStorage = nil
            config.waitsForConnectivity = false
            config.tlsMinimumSupportedProtocolVersion = .TLSv12
            config.httpAdditionalHeaders = ["User-Agent": HeaderScrub.userAgent, "Accept-Language": HeaderScrub.acceptLanguage]
            config.timeoutIntervalForRequest = proxy == .none ? Self.requestTimeout : Self.proxyRequestTimeout
            config.timeoutIntervalForResource = Self.resourceTimeout
            config.proxyConfigurations = RouteResolver.proxyConfigurations(proxy)
            if !protocolClasses.isEmpty { config.protocolClasses = protocolClasses + (config.protocolClasses ?? []) }
            let session = URLSession(configuration: config, delegate: redirects, delegateQueue: nil)
            s[key] = session
            return session
        }
    }
}

/// Error-free view of a response body for small JSON requests.
struct HttpResponse: Sendable {
    let status: Int
    let headers: [String: String]
    let body: Data
    let url: URL?

    var isSuccessful: Bool { (200 ..< 300).contains(status) }
    var isRedirect: Bool { [301, 302, 303, 307, 308].contains(status) }

    func header(_ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }
}

/// The agent turn a request is made for, recorded with it in the audit log (privacy receipts).
struct AuditTrace: Equatable, Sendable {
    let session: String
    let turn: Int
}

/// A policy-bound client created by [HttpClientFactory.create].
final class HttpClient: Sendable {
    let tag: String
    let override: RouteOverride
    /// The effective mode this client was built for.
    let expectedRoute: NetworkMode
    private let factory: HttpClientFactory

    fileprivate init(factory: HttpClientFactory, tag: String, override: RouteOverride, expectedRoute: NetworkMode) {
        self.factory = factory
        self.tag = tag
        self.override = override
        self.expectedRoute = expectedRoute
    }

    private struct Admitted {
        let request: URLRequest
        let session: URLSession
        let proxy: Proxy
        let route: String
        let tag: String
        let trace: AuditTrace?
    }

    /**
     The kill switch. A request is refused with [NetworkBlockedError] when:
     - the global mode is Offline and the host is not loopback (or loopback is not allowed);
     - it uses cleartext HTTP to a non-loopback host;
     - the effective route changed since the client was built (e.g. Direct → Tor).
     */
    private func admit(_ request: URLRequest, tag requestTag: String?, trace: AuditTrace? = nil) throws -> Admitted {
        let policy = factory.policy
        let url = request.url
        let host = url?.host() ?? ""
        let loopback = Loopback.isLoopback(host)
        let global = policy.mode
        let effective = RouteResolver.effectiveMode(global, override, socksFallback: policy.socksFallback)
        let label = requestTag ?? tag

        let blockReason = RouteResolver.blockReason(
            global: global, effective: effective, allowLoopbackWhenOffline: policy.allowLoopbackWhenOffline,
            url: url, expectedRoute: expectedRoute,
        )

        let route = loopback ? "loopback" : RouteResolver.routeLabel(effective)
        if let blockReason {
            audit(request, tag: label, route: route, trace: trace, blocked: true, error: blockReason)
            throw NetworkBlockedError(message: blockReason)
        }
        let proxy = RouteResolver.proxy(for: expectedRoute, host: host) ?? .none
        return Admitted(request: HeaderScrub.scrub(request), session: factory.session(for: proxy, route: route), proxy: proxy, route: route, tag: label, trace: trace)
    }

    @discardableResult
    private func audit(_ request: URLRequest, tag: String?, route: String, trace: AuditTrace? = nil, blocked: Bool, status: Int? = nil, received: Int64? = nil, error: String? = nil) -> AuditEntry {
        factory.auditLog.record(
            method: request.httpMethod ?? "GET",
            host: request.url?.host() ?? "",
            path: request.url?.path(percentEncoded: true) ?? "",
            bytesSent: request.httpBody.map { Int64($0.count) },
            bytesReceived: received,
            status: status,
            tag: tag,
            route: route,
            blocked: blocked,
            error: error,
            session: trace?.session,
            turn: trace?.turn,
        )
    }

    private func failure(_ error: Error, _ a: Admitted) -> Error {
        let name = (error as? URLError).map { "URLError(\($0.code.rawValue))" } ?? String(describing: type(of: error))
        if !(error is CancellationError) && (error as? URLError)?.code != .cancelled {
            audit(a.request, tag: a.tag, route: a.route, trace: a.trace, blocked: false, error: name)
        }
        return Self.proxyFailure(error, proxy: a.proxy)
    }

    /**
     A request through a SOCKS proxy that could not connect almost always means the proxy itself
     (Orbot, a custom SOCKS5 app) is not running: say so instead of URLSession's generic "Could not
     connect to the server". The code stays the same for callers that check it.
     */
    static func proxyFailure(_ error: Error, proxy: Proxy) -> Error {
        guard case .socks(let host, let port) = proxy, let url = error as? URLError,
              [.cannotConnectToHost, .timedOut, .networkConnectionLost].contains(url.code) else { return error }
        return URLError(url.code, userInfo: [NSLocalizedDescriptionKey: "Tor/SOCKS proxy at \(host):\(port) isn't reachable", NSUnderlyingErrorKey: url])
    }

    /// Sends [request] and buffers the whole body (JSON APIs, small downloads).
    func send(_ request: URLRequest, tag: String? = nil) async throws -> HttpResponse {
        let a = try admit(request, tag: tag)
        do {
            let (data, response) = try await a.session.data(for: a.request)
            let http = response as? HTTPURLResponse
            audit(a.request, tag: a.tag, route: a.route, blocked: false, status: http?.statusCode, received: http.map { $0.expectedContentLength })
            return HttpResponse(status: http?.statusCode ?? 0, headers: Self.headers(http), body: data, url: http?.url)
        } catch {
            throw failure(error, a)
        }
    }

    /// Starts [request] and returns the streaming body (SSE, large files).
    func stream(_ request: URLRequest, tag: String? = nil) async throws -> (URLSession.AsyncBytes, HTTPURLResponse) {
        let (bytes, http, _) = try await streamAudited(request, tag: tag)
        return (bytes, http)
    }

    /**
     [stream] for an agent turn ([trace]), also returning the audit entry's id: the caller reports
     the body bytes it actually read with `AuditLog.received` (a stream has no Content-Length).
     */
    func streamAudited(_ request: URLRequest, tag: String? = nil, trace: AuditTrace? = nil) async throws -> (URLSession.AsyncBytes, HTTPURLResponse, Int64) {
        let a = try admit(request, tag: tag, trace: trace)
        do {
            let (bytes, response) = try await a.session.bytes(for: a.request)
            guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
            let entry = audit(a.request, tag: a.tag, route: a.route, trace: a.trace, blocked: false, status: http.statusCode, received: http.expectedContentLength)
            return (bytes, http, entry.id)
        } catch {
            throw failure(error, a)
        }
    }

    /// Downloads [request] into a temporary file (moved by the caller before it is deleted).
    func download(_ request: URLRequest, tag: String? = nil, delegate: (any URLSessionTaskDelegate)? = nil) async throws -> (URL, HTTPURLResponse) {
        let a = try admit(request, tag: tag)
        do {
            let (url, response) = try await a.session.download(for: a.request, delegate: delegate)
            guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
            audit(a.request, tag: a.tag, route: a.route, blocked: false, status: http.statusCode, received: http.expectedContentLength)
            return (url, http)
        } catch {
            throw failure(error, a)
        }
    }

    /// Runs the kill switch for a transfer handed to another session (background downloads).
    /// Returns the scrubbed request and the proxy it must use.
    func admitForTransfer(_ request: URLRequest, tag: String? = nil) throws -> (URLRequest, Proxy) {
        let a = try admit(request, tag: tag)
        audit(a.request, tag: a.tag, route: a.route, blocked: false, error: "handed to background transfer")
        return (a.request, RouteResolver.proxy(for: expectedRoute, host: request.url?.host() ?? "") ?? .none)
    }

    /**
     Vets a redirect hop of a transfer running in another session (background downloads): the
     kill switch and route check run for the new URL exactly as for a fresh request, the hop is
     audited, and the session's fixed [proxy] must still be the right one for the new host.
     Returns the scrubbed request to follow.
     */
    func admitRedirect(_ request: URLRequest, sessionProxy proxy: Proxy, tag: String? = nil) throws -> URLRequest {
        let a = try admit(request, tag: tag)
        let needed = RouteResolver.proxy(for: expectedRoute, host: request.url?.host() ?? "") ?? .none
        guard needed == proxy else {
            let reason = "Redirect to a host that needs a different route"
            audit(a.request, tag: a.tag, route: a.route, blocked: true, error: reason)
            throw NetworkBlockedError(message: reason)
        }
        audit(a.request, tag: a.tag, route: a.route, blocked: false, error: "redirect hop (background transfer)")
        return a.request
    }

    static func headers(_ response: HTTPURLResponse?) -> [String: String] {
        var out: [String: String] = [:]
        response?.allHeaderFields.forEach { key, value in out["\(key)"] = "\(value)" }
        return out
    }
}

extension URLSession.AsyncBytes {
    /// Collects at most [limit] bytes (error bodies).
    func prefixData(_ limit: Int) async -> Data {
        var data = Data()
        do {
            for try await byte in self {
                data.append(byte)
                if data.count >= limit { break }
            }
        } catch {}
        return data
    }
}

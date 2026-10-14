import Foundation

/**
 Loopback host detection without DNS lookups (resolving a name could itself leak).

 `10.0.2.2` (the Android emulator's alias for the host machine) is treated as loopback on both
 platforms so the shared tests and settings behave identically.
 */
enum Loopback {
    static let emulatorHost = "10.0.2.2"

    private static let ipv4Loopback = try! NSRegularExpression(pattern: #"^127(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$"#)

    static func isLoopback(_ host: String?) -> Bool {
        guard let host, !host.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        var h = host.trimmingCharacters(in: .whitespaces).lowercased()
        if h.hasPrefix("[") { h.removeFirst() }
        if h.hasSuffix("]") { h.removeLast() }
        return h == "localhost" ||
            h.hasSuffix(".localhost") ||
            h == "::1" ||
            h == "0:0:0:0:0:0:0:1" ||
            h == emulatorHost ||
            ipv4Loopback.firstMatch(in: h, range: NSRange(h.startIndex..., in: h)) != nil
    }

    /// Extracts the host part of a URL string without resolving it.
    static func isLoopbackURL(_ url: String?) -> Bool {
        guard let url, !url.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        let afterScheme = url.range(of: "://").map { String(url[$0.upperBound...]) } ?? url
        let authority = String(afterScheme.split(separator: "/", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
        let hostPort = authority.split(separator: "@").last.map(String.init) ?? authority
        let host: String
        if hostPort.hasPrefix("[") {
            host = String(hostPort.split(separator: "]", maxSplits: 1, omittingEmptySubsequences: false).first ?? "") + "]"
        } else {
            host = String(hostPort.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
        }
        return isLoopback(host)
    }
}

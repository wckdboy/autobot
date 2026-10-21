import SwiftUI

/// Network privacy state shown to the user.
enum PrivacyStatus: String, CaseIterable, Sendable {
    case offline, direct, tor, proxy

    var label: String {
        switch self {
        case .offline: "Offline"
        case .direct: "Direct"
        case .tor: "Tor"
        case .proxy: "SOCKS5"
        }
    }

    var description: String {
        switch self {
        case .offline: "No network access"
        case .direct: "Direct internet connection"
        case .tor: "Routed through Tor"
        case .proxy: "Routed through a SOCKS5 proxy"
        }
    }

    /// The status ink on [c]'s ground (text and dot).
    func color(_ c: AutobotScheme) -> Color {
        switch self {
        case .offline: c.primary
        case .direct: c.inks.amber
        case .tor: c.inks.uv
        case .proxy: c.inks.cyan
        }
    }
}

/// "12.4 tok/s · 256 tokens". Either part may be omitted.
struct TokenRateLabel: View {
    var tokensPerSecond: Double? = nil
    var totalTokens: Int? = nil

    var body: some View {
        let parts = [tokensPerSecond.map { String(format: "%.1f tok/s", $0) }, totalTokens.map { "\($0) tokens" }].compactMap { $0 }
        if !parts.isEmpty {
            Text(parts.joined(separator: " · ")).font(.caption.monospacedDigit()).foregroundStyle(.secondary)
        }
    }
}

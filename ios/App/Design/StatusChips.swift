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

    var color: Color {
        switch self {
        case .offline: AutobotColors.green
        case .direct: AutobotColors.amber
        case .tor: AutobotColors.violet
        case .proxy: AutobotColors.cyan
        }
    }
}

/// Inference backend that produced (or will produce) a response.
enum Accelerator: String, CaseIterable, Sendable {
    case npu = "NPU", gpu = "GPU", cpu = "CPU", remote = "Remote"
}

/// Compact pill with a status dot, e.g. "● Offline". Tappable when [action] is set.
struct PrivacyStatusPill: View {
    let status: PrivacyStatus
    var action: (() -> Void)? = nil

    var body: some View {
        let accent = status.color
        let pill = HStack(spacing: 6) {
            Circle().fill(accent).frame(width: Tokens.Spacing.pillDot, height: Tokens.Spacing.pillDot)
            Text(status.label).textStyle(.labelMedium, weight: 600).foregroundStyle(accent)
        }
        .padding(.horizontal, Tokens.Spacing.pillPaddingHorizontal)
        .padding(.vertical, Tokens.Spacing.pillPaddingVertical)
        .background(Capsule().fill(accent.opacity(0.12)))
        .fixedSize()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Network: \(status.description)")
        if let action {
            Button(action: action) { pill }.buttonStyle(PressStyle())
        } else {
            pill
        }
    }
}

/// Small chip naming the compute backend.
struct AcceleratorChip: View {
    let accelerator: Accelerator
    @Environment(\.scheme) private var c

    var body: some View {
        let accent: Color = switch accelerator {
        case .npu: AutobotColors.violet
        case .gpu: AutobotColors.cyan
        case .cpu: c.onSurfaceVariant
        case .remote: AutobotColors.amber
        }
        Text(accelerator.rawValue.uppercased())
            .textStyle(.labelSmall, weight: 700)
            .foregroundStyle(accent)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(RoundedRectangle(cornerRadius: AutobotShapes.small).fill(accent.opacity(0.10)))
            .accessibilityLabel("Runs on \(accelerator.rawValue)")
    }
}

/// "12.4 tok/s · 256 tokens". Either part may be omitted.
struct TokenRateLabel: View {
    var tokensPerSecond: Double? = nil
    var totalTokens: Int? = nil
    @Environment(\.scheme) private var c

    var body: some View {
        let parts = [tokensPerSecond.map { String(format: "%.1f tok/s", $0) }, totalTokens.map { "\($0) tokens" }].compactMap { $0 }
        if !parts.isEmpty {
            Text(parts.joined(separator: " · ")).textStyle(.labelSmall).foregroundStyle(c.onSurfaceVariant)
        }
    }
}

import SwiftUI

extension Receipt: Identifiable {
    var id: Int { turn }

    /// Short policy wording of a model that trains on prompts (Kotlin `Receipts.policyLabel`).
    static let trains = "trains on prompts"
}

/**
 The receipt under a turn's last answer: `ON DEVICE` or `2 HOSTS · 14 KB OUT · NO RETENTION`.
 It stamps in when the turn ends; a tap opens [ReceiptSheet].
 */
struct ReceiptLine: View {
    let receipt: Receipt?
    let onOpen: (Receipt) -> Void
    @Environment(\.scheme) private var c

    var body: some View {
        ZStack(alignment: .leading) {
            if let receipt {
                let accent: Color = receipt.onDevice ? c.primary
                    : receipt.models.contains { $0.policy == Receipt.trains } ? AutobotColors.amber : c.onSurfaceVariant
                Tag("▤ " + receipt.line, accent: accent, filled: receipt.onDevice) { onOpen(receipt) }
                    .accessibilityLabel("Privacy receipt: \(receipt.line)")
            }
        }
        .stamp(receipt?.line, when: { $0 != nil })
    }
}

/// Everything one turn shared, and with whom. Built on this phone; never sent anywhere.
struct ReceiptSheet: View {
    let receipt: Receipt
    /// Opens the spend and quota board (nil: no link).
    var onOpenUsage: (() -> Void)? = nil
    @Environment(\.scheme) private var c

    var body: some View {
        ScrollView {
            InkStack(alignment: .leading, spacing: 10) {
                SectionLabel("receipt · turn \(receipt.turn)")
                Text(headline).textStyle(.headlineSmall).foregroundStyle(c.onBackground).fixedSize(horizontal: false, vertical: true)

                if !receipt.hosts.isEmpty {
                    Hairline().padding(.vertical, 2)
                    MicroLabel("hosts")
                    ForEach(Array(receipt.hosts.enumerated()), id: \.offset) { _, host in hostRow(host) }
                }

                if !receipt.models.isEmpty {
                    Hairline().padding(.vertical, 2)
                    MicroLabel("model")
                    ForEach(Array(receipt.models.enumerated()), id: \.offset) { _, m in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 8) {
                                Text(m.model).textStyle(.code).foregroundStyle(c.onSurface).lineLimit(1).truncationMode(.middle)
                                if let policy = m.policy {
                                    Tag(policy, accent: policy == Receipt.trains ? AutobotColors.amber : m.onDevice ? c.primary : c.onSurfaceVariant)
                                }
                            }
                            Text([m.name, m.policyNote ?? (m.policy == nil ? "data policy not documented" : nil)].compactMap { $0 }.joined(separator: " · "))
                                .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                            if let line = receipt.usageLine(m.model) { MicroLabel(line, lines: 2) }
                        }
                    }
                }

                if let cascade = receipt.cascade {
                    Hairline().padding(.vertical, 2)
                    MicroLabel(cascade.role == "cloud" ? "phone first · cloud follow-up" : "phone first", color: c.primary)
                    Text(cascade.summary).textStyle(.bodySmall).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
                    ChatFlow(spacing: 6) {
                        ForEach(Array(cascade.details.enumerated()), id: \.offset) { _, d in Tag(d) }
                    }
                }

                if receipt.redacted {
                    Hairline().padding(.vertical, 2)
                    MicroLabel("redacted")
                    if receipt.redactions.isEmpty {
                        Text("Nothing personal found").textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant)
                    } else {
                        ChatFlow(spacing: 6) {
                            ForEach(Array(receipt.redactions.enumerated()), id: \.offset) { _, r in Tag(r.label, accent: c.primary) }
                        }
                        Text("The provider saw placeholders like [PERSON_1]. The real values were put back on this phone.")
                            .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                    }
                }

                if let usage = receipt.usage {
                    Hairline().padding(.vertical, 2)
                    HStack(spacing: 8) {
                        MicroLabel("tokens").frame(maxWidth: .infinity, alignment: .leading)
                        if let onOpenUsage { Tag("usage", action: onOpenUsage) }
                    }
                    Text(usage).textStyle(.code).foregroundStyle(c.onSurface).fixedSize(horizontal: false, vertical: true)
                    Text("Counted from the provider's own usage report. Cost uses your price table; models without a price stay unknown.")
                        .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
                }

                if !receipt.tools.isEmpty {
                    Hairline().padding(.vertical, 2)
                    MicroLabel("tools")
                    ChatFlow(spacing: 6) {
                        ForEach(Array(receipt.tools.enumerated()), id: \.offset) { _, t in
                            Tag(t.network ? "\(t.name) · net" : t.name, accent: t.network ? c.primary : c.onSurfaceVariant)
                        }
                    }
                }

                if !receipt.fallbacks.isEmpty {
                    Hairline().padding(.vertical, 2)
                    MicroLabel("fallbacks")
                    ForEach(receipt.fallbacks, id: \.self) { Text($0).textStyle(.bodySmall).foregroundStyle(AutobotColors.amber) }
                }

                Hairline().padding(.vertical, 2)
                Text("Built on this phone from the network audit log. Receipts stay in this chat and are never sent anywhere.")
                    .textStyle(.bodySmall).foregroundStyle(c.onSurfaceVariant).fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 16)
            .padding(.top, 20)
            .padding(.bottom, 24)
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(c.surfaceContainerLow)
        .presentationDragIndicator(.visible)
    }

    private var headline: String {
        if receipt.onDevice { return "Nothing left this phone" }
        if !receipt.tracked { return "Traffic not tracked for this turn" }
        return "\(Receipt.formatBytes(receipt.bytesSent)) sent · \(Receipt.formatBytes(receipt.bytesReceived)) received"
    }

    /// `api.deepseek.com` / `TOR · 2 REQ · 14 KB OUT · 30 KB IN`
    private func hostRow(_ host: Receipt.Host) -> some View {
        let detail = [
            host.route,
            "\(host.requests) req",
            "\(Receipt.formatBytes(host.bytesSent)) out",
            "\(Receipt.formatBytes(host.bytesReceived)) in",
            host.blocked > 0 ? "\(host.blocked) blocked" : nil,
            host.tags.isEmpty ? nil : host.tags.joined(separator: ", "),
        ].compactMap { $0 }.joined(separator: " · ")
        return VStack(alignment: .leading, spacing: 2) {
            Text(host.host).textStyle(.code).foregroundStyle(c.onSurface).lineLimit(1).truncationMode(.middle)
            MicroLabel(detail, color: host.blocked == host.requests ? AutobotColors.amber : nil, lines: 2)
        }
    }
}
